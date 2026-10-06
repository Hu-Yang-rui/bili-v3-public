package com.example.biliv3.data.ai

import android.util.Log
import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.api.BiliHeaders
import com.example.biliv3.data.api.Endpoints
import com.example.biliv3.data.subtitle.SubtitleBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 视频总结仓库（v1.6.3）。
 *
 * ## 逻辑（严格按需求）
 *
 * ```
 * 点击「AI 总结」
 *      ↓
 * ① 试 B 站官方总结（需要登录）
 *      ↓ 成功
 *   显示官方总结（标注来源：B 站官方 AI）
 *      ↓ 失败 / 不存在
 * ② 查第三方 AI 配置
 *      ↓ 已配置
 *   用现有字幕能力取字幕 → 调第三方 → 显示（标注来源：模型名）
 *      ↓ 未配置
 *   提示去配置（明确说明还缺哪一项）
 * ```
 *
 * ## 🔴 安全红线：第三方请求**绝不带 B 站 Cookie**
 *
 * 与 `AicuApi` 完全同一条红线（§4.2）：
 * 第三方 AI 服务是**另一个域**，共享 OkHttpClient = 把
 * `SESSDATA` / `bili_jct` 明文发给它。
 *
 * 所以第三方调用走 [aiClient] —— **独立的 OkHttpClient，不挂 CookieJar**。
 * 而官方总结走 [api]（共享 client，本来就该带 cookie）。
 * 两个 client 各司其职，这是本类最容易出错的地方。
 *
 * ## 复用现有字幕能力（不重建一套）
 *
 * 视频文本来自 `SubtitleRepository`（项目已有：gRPC 主路径 + REST 备路径）。
 * 本类**只调用它**，不自己发字幕请求 —— 需求明确要求"优先复用现有字幕能力"。
 *
 * ## 缓存
 *
 * 总结按 `bvid:cid` 缓存在内存（`SummaryCache`）。
 * **不做磁盘持久化**：总结是"当次观看的辅助信息"，
 * 且第三方总结的时效性依赖字幕与模型，存盘反而会让用户看到过期内容。
 * 与 `PlaybackProgressStore`（需要跨重启）的取舍不同，这是刻意的。
 */
class AiSummaryRepository(
    /** 共享 client（带 B 站 CookieJar）—— **只用于官方接口**。 */
    private val api: BiliApi,
    private val subtitleRepository: com.example.biliv3.data.subtitle.SubtitleRepository,
    private val configStore: AiConfigStore,
    /**
     * 第三方 AI 专用 client。
     *
     * 🔴 **绝不挂 CookieJar** —— 见类文档的红线说明。
     */
    private val aiClient: OkHttpClient = defaultAiClient(),
) {

    /** 是否已配置第三方 AI。 */
    fun thirdPartyUsable(): Boolean = configStore.snapshot().usable

    /** 当前配置（给 UI 显示脱敏 Key / 缺失提示）。 */
    fun config(): AiConfig = configStore.snapshot()

    /**
     * 取 AI 总结（官方优先）。
     *
     * @param aid 视频 aid（官方接口要 `up_mid`，不直接用它）
     * @param cid 分P 的 cid（官方接口必填）
     * @param upMid UP 主 mid（官方接口必填）
     * @param title 视频标题（第三方 Prompt 用）
     * @param desc 视频简介（第三方 Prompt 用）
     * @return 成功时返回总结；官方与第三方都拿不到时返回失败原因
     */
    suspend fun summarize(
        bvid: String,
        cid: Long,
        upMid: Long,
        title: String,
        desc: String,
    ): Result<VideoSummary> {
        // ---- ① 官方优先 ----
        val official = runCatching { official(bvid, cid, upMid) }
        official.getOrNull()?.let { return Result.success(it) }

        // 官方失败的原因要**带出来**（用户需要知道为什么没走官方）
        val officialReason = official.exceptionOrNull()?.let { readable(it) }

        // ---- ② 第三方 ----
        val cfg = configStore.snapshot()
        if (!cfg.usable) {
            return Result.failure(
                AiSummaryException(
                    "官方 AI 总结不可用" +
                        (officialReason?.let { "（$it）" } ?: "") +
                        "；第三方 AI 尚未配置：${cfg.missingHint}",
                ),
            )
        }

        return thirdParty(bvid, cid, title, desc, cfg)
    }

    // ---------------------------------------------------------------------
    // ① 官方
    // ---------------------------------------------------------------------

    /**
     * B 站官方 AI 总结。
     *
     * ⚠️ **实测：未登录返回 `-403 访问权限不足`**（见 `Endpoints.AI_CONCLUSION`）。
     * 已登录时的成功分支**本项目未验证过** —— 本机没有可用登录态。
     *
     * 所以这里按公开结构解析 + 全面容错，失败就抛异常让调用方降级，
     * **绝不伪造**官方结果。
     */
    private suspend fun official(bvid: String, cid: Long, upMid: Long): VideoSummary {
        if (bvid.isEmpty() || cid <= 0L) throw BiliException(-1, "缺少视频标识")

        val json = api.getRaw(
            path = Endpoints.AI_CONCLUSION,
            query = mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "up_mid" to upMid.toString(),
            ),
            signed = false,
        )

        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(code, json.optString("message", "官方 AI 总结不可用"))
        }

        return SummaryParser.parseOfficial(json)
            ?: throw BiliException(-1, "该视频没有官方 AI 总结")
    }

    // ---------------------------------------------------------------------
    // ② 第三方
    // ---------------------------------------------------------------------

    /**
     * 第三方 AI 总结：现有字幕 → 自定义 Prompt → OpenAI 风格接口。
     *
     * ## 字幕从哪来
     *
     * 复用 [subtitleRepository]（项目已有能力）：
     * 1. `tracks()` 取字幕轨列表
     * 2. 优先选**中文轨**（总结要中文；英文轨会让模型先翻译再总结，多一次失真）
     * 3. `body()` 取正文，拼成纯文本
     *
     * ## 拿不到字幕时怎么办
     *
     * **如实报错**，不用标题/简介硬凑 —— 那样生成的内容会大量幻觉
     * （模型会按标题编内容），比"没有总结"更糟。
     * 需求也明确要求"不凭空添加字幕中不存在的信息"。
     */
    private suspend fun thirdParty(
        bvid: String,
        cid: Long,
        title: String,
        desc: String,
        cfg: AiConfig,
    ): Result<VideoSummary> {
        // ---- 字幕 ----
        val body = runCatching {
            val tracks = subtitleRepository.tracks(aid = 0L, cid = cid)
            val track = pickTrack(tracks)
                ?: throw AiSummaryException("该视频没有可用字幕，无法生成总结")
            subtitleRepository.body(track)
        }.getOrElse { e ->
            return Result.failure(
                AiSummaryException("获取字幕失败：${readable(e)}"),
            )
        }

        val text = flatten(body)
        if (text.isBlank()) {
            return Result.failure(AiSummaryException("字幕内容为空，无法生成总结"))
        }

        val (userPrompt, truncated) = SummaryPrompt.buildUser(
            title = title,
            desc = desc,
            subtitleText = text,
        )

        // ---- 调模型 ----
        val content = runCatching { chat(cfg, userPrompt) }
            .getOrElse { e -> return Result.failure(AiSummaryException(readable(e))) }

        val parsed = SummaryParser.parseThirdParty(
            content = content,
            model = cfg.model,
            truncated = truncated,
        ) ?: return Result.failure(AiSummaryException("模型返回了空内容"))

        return Result.success(parsed)
    }

    /**
     * 选一条字幕轨：**中文优先**，其次第一条。
     *
     * 为什么中文优先：总结要求中文输出。若喂英文轨，模型要先翻译再总结，
     * 多一次失真，而且专有名词更容易译错。
     */
    private fun pickTrack(
        tracks: List<com.example.biliv3.data.subtitle.SubtitleTrack>,
    ): com.example.biliv3.data.subtitle.SubtitleTrack? =
        tracks.firstOrNull { it.lan.startsWith("zh", ignoreCase = true) }
            ?: tracks.firstOrNull()

    /** 把字幕 cue 拼成纯文本（一句一行，便于模型断句）。 */
    private fun flatten(body: SubtitleBody): String =
        body.cues.joinToString("\n") { it.content.trim() }
            .trim()

    /**
     * 调用 OpenAI 风格的 `/chat/completions`。
     *
     * ## 请求形状（刻意保持最通用）
     *
     * ```json
     * POST {baseUrl}/chat/completions
     * Authorization: Bearer {key}
     * {"model":"...","messages":[{"role":"system",...},{"role":"user",...}]}
     * ```
     *
     * 这是 OpenAI 兼容接口的**最小公共子集** —— 绝大多数服务
     * （OpenAI / DeepSeek / 通义 / 本地 vLLM / Ollama 的兼容层）都支持。
     * 刻意**不用**任何厂商专有字段（如 `response_format` 的变体），
     * 那样会把 Prompt 与某一个模型绑死（需求明确禁止）。
     *
     * ## 错误处理
     *
     * HTTP 非 2xx 时把**状态码 + 响应体片段**带进异常 ——
     * 用户最需要知道的是"401 是 Key 错了"还是"404 是地址错了"，
     * 一句"请求失败"帮不上忙。
     * ⚠️ 但响应体里**可能回显 Key**（少数服务会），所以只截断前 200 字。
     */
    private suspend fun chat(cfg: AiConfig, userPrompt: String): String =
        withContext(Dispatchers.IO) {
            val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"

            val payload = JSONObject().apply {
                put("model", cfg.model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SummaryPrompt.SYSTEM)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                })
                // 温度压低：总结要忠实于字幕，不需要创造性
                put("temperature", 0.3)
            }

            val req = Request.Builder()
                .url(url)
                // ⚠️ 明文 Key 只在这一行出现。不打印、不进异常、不进 UI。
                .header("Authorization", "Bearer ${configStore.plainKey()}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val (code, text) = try {
                aiClient.newCall(req).execute().use { resp ->
                    resp.code to resp.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                // ⚠️ 这里**不打印 url 之外的任何东西**（尤其不打印请求体，
                // 它含 user prompt；也不打印 header，它含 Key）
                Log.w(TAG, "第三方 AI 请求失败: ${e.message}")
                throw AiSummaryException("无法连接 AI 服务：${e.message ?: "网络错误"}")
            }

            if (code !in 200..299) {
                Log.w(TAG, "第三方 AI HTTP $code")
                throw AiSummaryException(
                    "AI 服务返回 HTTP $code：${text.take(ERROR_BODY_PREVIEW)}",
                )
            }

            val json = runCatching { JSONObject(text) }.getOrElse {
                throw AiSummaryException("AI 响应不是合法 JSON")
            }
            SummaryParser.extractContent(json)
                ?: throw AiSummaryException("AI 响应里没有内容（格式不符）")
        }

    /** 把异常翻译成用户能看懂的一句话。 */
    private fun readable(e: Throwable): String = when (e) {
        is AiSummaryException -> e.message ?: "AI 总结失败"
        is BiliException -> e.userMessage
        else -> e.message ?: "AI 总结失败"
    }

    companion object {
        private const val TAG = "BiliAiSummary"

        /** 错误响应体的截断长度（防止回显超长内容）。 */
        private const val ERROR_BODY_PREVIEW = 200

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 第三方 AI 专用 client。
         *
         * ## 🔴 绝不挂 CookieJar
         *
         * 挂了就等于把 B 站登录凭据发给第三方 AI 服务（§4.2 红线）。
         * 这个 client 连 `BiliHeaders` 都不用 —— 那些头里带
         * `Referer: bilibili.com`，对第三方服务毫无意义且暴露来源。
         *
         * 超时给得比较长：LLM 生成总结可能耗时数十秒，
         * 用默认的 15s 读超时会在长视频上必然失败。
         */
        fun defaultAiClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // LLM 生成慢 —— 这是**唯一的**长超时，且只作用于第三方调用
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
    }
}

/** AI 总结相关的用户可见错误。 */
class AiSummaryException(message: String) : Exception(message)
