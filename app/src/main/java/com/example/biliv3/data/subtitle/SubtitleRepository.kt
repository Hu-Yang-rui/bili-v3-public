package com.example.biliv3.data.subtitle

import android.util.Log
import com.example.biliv3.data.api.BiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 字幕仓库：AI 翻译字幕的获取入口。
 *
 * ## 双路径策略
 *
 * | 路径 | 接口 | 覆盖 | 依赖 |
 * |---|---|---|---|
 * | 主 | gRPC `bilibili.subtitle.Subtitle/SubtitleView` | **AI 翻译轨** | 需 App 鉴权 |
 * | 备 | REST `x/player/wbi/v2` 的 `subtitle.subtitles[]` | CC / UP 上传（登录后含 AI） | WBI 签名 |
 *
 * ## 实测结论（2026-09-28）
 *
 * - gRPC 端点**可达**（`HTTP 200` + `grpc-status: 2`），无鉴权时 `-400`
 * - REST `x/player/v2` / `x/player/wbi/v2` **未登录时 `subtitles[]` 恒为空**
 *   —— 字幕轨（含 AI 翻译）需要登录态
 *
 * 因此两条路径都**需要登录**。未登录时 UI 应提示"登录后可用"，
 * 而不是显示"加载失败"。
 */
class SubtitleRepository(
    private val api: BiliApi,
    private val grpc: SubtitleGrpcClient = SubtitleGrpcClient(),
    private val http: OkHttpClient = defaultHttpClient(),
) {

    /**
     * 拉字幕轨列表。
     *
     * 先试 gRPC（能拿到 AI 翻译轨），失败再退 REST。
     *
     * @param authorization App 鉴权串；为空时 gRPC 大概率返回 -400
     */
    suspend fun tracks(
        aid: Long,
        cid: Long,
        epId: Long = 0L,
        authorization: String = "",
    ): List<SubtitleTrack> {
        // ---- 主路径：gRPC ----
        val viaGrpc = runCatching {
            grpc.subtitleView(aid = aid, cid = cid, epId = epId, authorization = authorization)
        }.onFailure {
            Log.i(TAG, "gRPC 路径失败，转 REST：${it.message}")
        }.getOrDefault(emptyList())

        if (viaGrpc.isNotEmpty()) {
            Log.i(TAG, "gRPC 拿到 ${viaGrpc.size} 条字幕轨")
            return viaGrpc
        }

        // ---- 备路径：REST ----
        return runCatching { restTracks(aid, cid) }
            .onFailure { Log.i(TAG, "REST 路径也失败：${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * REST 路径：`x/player/wbi/v2`。
     *
     * 返回体结构（实测）：
     * ```json
     * {"code":0,"data":{"subtitle":{
     *    "allow_submit":false,"lan":"","lan_doc":"",
     *    "subtitles":[],"subtitle_position":null,"font_size_type":0}}}
     * ```
     * 未登录时 `subtitles` 为空数组。
     */
    private suspend fun restTracks(aid: Long, cid: Long): List<SubtitleTrack> {
        val json = api.getOk(
            path = "x/player/wbi/v2",
            query = mapOf("aid" to aid.toString(), "cid" to cid.toString()),
            signed = true,
        )
        val arr = json.optJSONObject("data")
            ?.optJSONObject("subtitle")
            ?.optJSONArray("subtitles")
            ?: return emptyList()

        val out = ArrayList<SubtitleTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                SubtitleTrack(
                    id = o.optLong("id"),
                    lan = o.optString("lan"),
                    lanDoc = o.optString("lan_doc"),
                    subtitleUrl = o.optString("subtitle_url"),
                    type = o.optInt("type"),
                    aiType = o.optInt("ai_type"),
                    aiStatus = o.optInt("ai_status"),
                    author = o.optString("author"),
                ),
            )
        }
        return out
    }

    /**
     * 拉某条字幕轨的正文。
     *
     * `subtitle_url` 形如 `//i0.hdslb.com/bfs/subtitle/xxx.json`，
     * 内容是：
     * ```json
     * {"font_size":0.4,"font_color":"#FFFFFF",
     *  "body":[{"from":1.23,"to":3.45,"location":2,"content":"你好"}]}
     * ```
     */
    suspend fun body(track: SubtitleTrack): SubtitleBody = withContext(Dispatchers.IO) {
        val url = track.subtitleUrl.let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("http://") -> "https://" + it.removePrefix("http://")
                else -> it
            }
        }
        if (url.isEmpty()) throw SubtitleException("字幕地址为空")

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            // 字幕文件在 hdslb CDN 上，同样需要 Referer，否则 403
            .header("Referer", "https://www.bilibili.com/")
            .build()

        val text = try {
            http.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw SubtitleException("字幕下载失败：${e.message}")
        }

        val cues = parseBody(text)
        SubtitleBody(track = track, cues = cues)
    }

    /** 解析字幕 JSON 的 `body[]`。 */
    private fun parseBody(text: String): List<SubtitleCue> {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw SubtitleException("字幕内容不是合法 JSON")
        }
        val arr = json.optJSONArray("body") ?: return emptyList()

        val out = ArrayList<SubtitleCue>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("content")
            if (content.isEmpty()) continue
            out.add(
                SubtitleCue(
                    from = o.optDouble("from", 0.0),
                    to = o.optDouble("to", 0.0),
                    content = content,
                ),
            )
        }
        // 按开始时间升序 —— cueAt 的二分查找依赖有序
        out.sortBy { it.from }
        return out
    }

    companion object {
        private const val TAG = "BiliSubtitle"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
