package com.example.biliv3.data

import android.util.Log
import com.example.biliv3.data.api.AicuDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 空降助手（SponsorBlock for Bilibili）仓库。
 *
 * 数据来源：[bsbsb.top](https://bsbsb.top/) —— 社区标注的"可跳过片段"
 * （恰饭广告 / 片头片尾 / 一键三连提示等）。移植自
 * [SponsorBlock](https://sponsor.ajay.app/)。
 *
 * ## 🔴 第三方站点，**不挂 CookieJar**
 *
 * 与 [com.example.biliv3.data.api.AicuApi] 同一条红线（见 AGENTS.md §4.2）：
 * 共享 client = 把 B 站 `SESSDATA` / `bili_jct` 明文发给第三方。
 * 所以这里用**独立 client**，不带 cookie。
 *
 * ## 两种接口，返回形状不同（实测确认）
 *
 * | 接口 | 返回 |
 * |---|---|
 * | `GET /api/skipSegments?videoID=BV...` | `[{segment, category, ...}]` **扁平数组** |
 * | `GET /api/skipSegments/{sha256前4位}` | `[{videoID, segments:[...]}]` **按视频分组** |
 *
 * 混用会解析出 `undefined`（我第一版就踩了）。
 *
 * 这里用**哈希前缀**方式：它是插件实际用的、且服务端**不知道**你在看哪个视频
 * （隐私更好）。代价是返回同一前缀下所有视频，需要自己按 bvid 过滤。
 *
 * ## ⚠️ 同一条广告可能有多个重复提交
 *
 * 实测 `BV1tnZqYTEx6` 的同一个 sponsor 段有 **6 条**提交
 * （`[464.6,527.0]` votes=4、`[460.4,527.5]` votes=1、`[460.9,527.1]` votes=1 …）——
 * 服务端**不合并**。若不处理，播放器会在同一区域**反复 seek**
 * （跳到 464s、播 3 秒又发现 460s 那段"还没跳"、再跳回去）。
 *
 * 所以 [merge] 会按时间重叠合并、取并集区间。
 */
class SponsorBlockRepository(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 拉某个视频的可跳过片段。
     *
     * @param bvid 视频 BVID
     * @param cid 分P 的 cid。**必须传** —— 多分P 视频各分P 的片段不同，
     *   而哈希前缀接口返回的是整视频的，需要用 cid 精确过滤。
     * @param categories 只保留这些类别（空集 = 全要）
     * @return 已合并、按开始时间升序的片段
     */
    suspend fun segments(
        bvid: String,
        cid: Long,
        categories: Set<String> = emptySet(),
    ): List<SkipSegment> = withContext(Dispatchers.IO) {
        if (bvid.isEmpty() || cid <= 0L) return@withContext emptyList()

        val prefix = sha256Hex(bvid).take(HASH_PREFIX_LEN)
        val req = Request.Builder()
            .url("$BASE_URL/$prefix")
            .header("User-Agent", UA)
            // 文档要求：区分调用来源方。用一个自述标识，不冒充官方插件。
            .header("origin", ORIGIN)
            .header("x-ext-version", EXT_VERSION)
            .build()

        val raw = try {
            client.newCall(req).execute().use { resp ->
                // ⚠️ **不要靠 404 判断"该视频没片段"**。
                //
                // 实测（2026-10-02）：哈希前缀接口**几乎总是返回 200** ——
                // 它返回的是"同前缀的其它视频"的片段，例如查 `ffff`
                // 也能拿到 `BV1z9cnenEmT` 的数据。
                //
                // 所以"本视频无片段"表现为：**返回里没有这个 videoID**
                // （由 parseAndFilter 过滤后得到空列表），而不是 404。
                //
                // 这里保留 404 分支只是防御性的（万一服务端改了行为）。
                if (resp.code == 404) return@withContext emptyList()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "空降助手 HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "空降助手请求失败: ${e.message}")
            return@withContext emptyList()
        }

        if (raw.isEmpty()) return@withContext emptyList()

        runCatching { parseAndFilter(raw, bvid, cid, categories) }
            .onSuccess { list ->
                // 记一条 INFO：排查"为什么没跳过"时这是第一手线索
                // （区分"没拉到片段"与"拉到了但判定没命中"）
                Log.i(TAG, "bvid=$bvid cid=$cid 可跳过片段 ${list.size} 条")
            }
            .onFailure { Log.w(TAG, "空降助手解析失败: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * 解析分组结构并过滤。
     *
     * 返回形如：
     * ```json
     * [{"videoID":"BV...","segments":[{"cid":"...","category":"sponsor",
     *    "actionType":"skip","segment":[657.4,779.6],"votes":0,...}]}]
     * ```
     */
    private fun parseAndFilter(
        raw: String,
        bvid: String,
        cid: Long,
        categories: Set<String>,
    ): List<SkipSegment> = SponsorBlockLogic.parse(raw, bvid, cid, categories)

    /**
     * 合并重叠区间。
     *
     * ## 为什么必须做
     *
     * 实测同一个 sponsor 段会有多个用户各自提交，服务端**不合并**：
     * ```
     * [464.631, 526.993]  votes=4
     * [460.406, 527.500]  votes=1
     * [460.905, 527.054]  votes=1
     * ```
     * 不合并的话播放器会在 460~527 之间**反复跳**（跳到 527 后
     * 播放器位置仍在 460 那段区间内，于是再跳一次）。
     *
     * ## 合并规则
     *
     * 按开始时间排序，若当前区间与上一个**有重叠或相邻**（间隔 < [MERGE_GAP_SECONDS]）
     * 则并入上一个（取并集）。类别取**优先级更高**的那个
     * （恰饭广告比片头更值得显示在提示里）。
     */
    private fun merge(items: List<SkipSegment>): List<SkipSegment> =
        SponsorBlockLogic.merge(items)

    /** 生成 bvid 的 sha256 十六进制。 */
    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "BiliSponsorBlock"

        private const val BASE_URL = "https://bsbsb.top/api/skipSegments"

        private const val HASH_PREFIX_LEN = 4

        private const val ACTION_SKIP = "skip"

        /** 区间间隔小于它就视为同一段（避免把紧邻的两段切成两个提示）。 */
        private const val MERGE_GAP_SECONDS = 1.0

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * 来源标识。
         *
         * ⚠️ **不冒充官方插件** —— 官方 origin 是
         * `chrome-extension://eaoelafamejbnggahofapllmfhlhajdd`。
         * 服务端用它统计来源；冒用会污染对方数据。
         */
        private const val ORIGIN = "biliv3-android"

        private const val EXT_VERSION = "1.0.0"

        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * 独立的 OkHttpClient。
         *
         * 🔴 **绝不挂 CookieJar** —— 否则把 B 站登录凭据发给第三方
         * （AGENTS.md §4.2 的红线）。
         *
         * 复用 [AicuDns]：它只接管 aicu 域名、其余回落系统 DNS，
         * 所以对 bsbsb.top 是走系统解析（没有额外收益，也没有坏处）。
         * 若实测 bsbsb.top 解析异常，再把它加进 `AicuDns.AICU_HOSTS`。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(AicuDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 一个可跳过片段。
 *
 * @param startSeconds 开始时间（秒）
 * @param endSeconds 结束时间（秒）
 * @param category 类别，见 [Category]
 * @param votes 社区投票数（越高越可信）
 */
data class SkipSegment(
    val startSeconds: Double,
    val endSeconds: Double,
    val category: String,
    val uuid: String = "",
    val votes: Int = 0,
) {
    /** 时长（秒）。 */
    val durationSeconds: Double get() = (endSeconds - startSeconds).coerceAtLeast(0.0)

    /**
     * 该片段的中文名（用于提示条）。
     *
     * 未知类别返回 `null` —— 由调用方决定怎么显示，
     * 而不是硬编一个"未知片段"给用户看。
     */
    val categoryLabel: String? get() = LABELS[category]

    companion object {
        /**
         * 类别全集（实测从接口拿到的）。
         *
         * ⚠️ `fill` 是**服务端拒绝**的类别（传它会 400），不要放进可选项。
         */
        val LABELS: Map<String, String> = mapOf(
            "sponsor" to "恰饭广告",
            "selfpromo" to "一键三连提示",
            "intro" to "片头",
            "outro" to "片尾",
            "preview" to "预告",
            "music_offtopic" to "非音乐部分",
            "interaction" to "互动提示",
            "poi_highlight" to "精彩片段",
            "exclusive_access" to "独占内容",
            // 实测出现的类别（`BV1dHRSYVEsv` 的 `[1008,1274]`）。
            // 中文名按社区惯例叫"填充内容"（非正片的过渡/花絮段）。
            "filler" to "填充内容",
        )

        /** 可在设置里选择的类别（默认四项在最前）。 */
        val SELECTABLE: List<String> = listOf(
            "sponsor", "selfpromo", "intro", "outro",
            "preview", "music_offtopic", "interaction", "filler",
        )
    }
}

/**
 * 空降助手的**纯逻辑**（无网络、无 Android 依赖）。
 *
 * ## 为什么单独抽出来
 *
 * 这些判断是"不报错但行为错"的类型 —— 合并写错会表现为
 * "播放器在同一段广告里反复跳"，而这在编译期完全看不出来。
 * 抽成纯函数后可以单测钉死（见 `SponsorBlockTest`），
 * 且**生产代码与测试调用同一份实现**，不会出现"测的是副本"。
 */
object SponsorBlockLogic {

    /** 只处理"跳过"语义的动作类型。 */
    const val ACTION_SKIP = "skip"

    /**
     * 解析哈希前缀接口的分组响应并过滤。
     *
     * ## 响应形状（实测）
     *
     * ```json
     * [{"videoID":"BV...","segments":[
     *    {"cid":"...","category":"sponsor","actionType":"skip",
     *     "segment":[657.4,779.6],"UUID":"...","votes":0,"videoDuration":792.6}]}]
     * ```
     *
     * ## 🔴 三层过滤，每一层都是实测必需的
     *
     * 1. **按 videoID 过滤** —— 前缀接口返回的是"同哈希前缀的**所有**视频"。
     *    实测查 `BV1cyZnY2Ehg`（前缀 4 字符）会返回 **8 个不同视频**。
     *    不过滤就会拿到别人的片段，跳到完全无关的位置。
     * 2. **按 cid 过滤** —— 同一视频可能有多条不同 cid 的记录。
     *    实测 `BV1tnZqYTEx6` 只有 1 个分P（cid `29181414442`），
     *    但接口返回了 3 个 cid 共 6 段 —— 另外两个是**视频重新转码前的旧 cid**。
     *    旧 cid 的时间点未必与新版本对齐，用了会跳错位置。
     * 3. **只保留 actionType == "skip"** —— `poi`（定位到精彩片段）与
     *    `full`（整段标记，`segment` 恒为 `[0,0]`）不是"跳过"，
     *    对它们 seek 会让用户莫名其妙。
     *
     * 另外还会丢掉无效区间（`end <= start`）—— 实测存在 `[0,0]`。
     *
     * @param categories 空集 = 不过滤类别
     */
    fun parse(
        raw: String,
        bvid: String,
        cid: Long,
        categories: Set<String> = emptySet(),
    ): List<SkipSegment> {
        // ⚠️ 用自写的 MiniJson 而不是 org.json ——
        // Android 的 org.json 在本地单测里是空实现（调用即抛
        // "Method not mocked"），用它就等于这块逻辑无法单测。
        // 本项目已有先例（AicuApi / Proto.kt），见 MiniJson 的说明。
        val groups = MiniJson.elements(raw)
        val out = ArrayList<SkipSegment>()

        for (group in groups) {
            // ⚠️ 前缀接口返回的是"同哈希前缀的**所有**视频"。
            // 实测查一个前缀会返回 8 个不同视频 —— 不过滤就会跳到别人的位置。
            if (MiniJson.string(group, "videoID") != bvid) continue

            val segsText = MiniJson.arrayText(group, "segments") ?: continue
            for (seg in MiniJson.elements(segsText)) {
                // ⚠️ 同一视频可能有多条不同 cid 的记录。实测某视频只有 1 个分P，
                // 但接口返回了 3 个 cid 共 6 段 —— 另两个是重新转码前的旧 cid，
                // 时间点未必与新版本对齐，用了会跳错位置。
                if (MiniJson.long(seg, "cid") != cid) continue

                val cat = MiniJson.string(seg, "category").orEmpty()
                if (categories.isNotEmpty() && cat !in categories) continue
                if (MiniJson.string(seg, "actionType") != ACTION_SKIP) continue

                val times = MiniJson.doubleArray(MiniJson.arrayText(seg, "segment"))
                if (times.size < 2) continue
                val start = times[0]
                val end = times[1]
                // 丢掉无效区间：实测存在 `[0,0]`（那是 full 类型的"整段标记"）
                if (start < 0 || end <= start) continue

                out.add(
                    SkipSegment(
                        startSeconds = start,
                        endSeconds = end,
                        category = cat,
                        uuid = MiniJson.string(seg, "UUID").orEmpty(),
                        votes = MiniJson.int(seg, "votes") ?: 0,
                    ),
                )
            }
        }

        return merge(out)
    }

    /**
     * 合并重叠 / 相邻的区间。
     *
     * ## 为什么必须做
     *
     * 实测同一个赞助段会有多个用户各自提交，服务端**不合并**：
     * ```
     * [464.631, 526.993]  votes=4
     * [460.406, 527.500]  votes=1
     * [460.905, 527.054]  votes=1
     * ```
     * 不合并的话播放器会在这 60 秒里**反复 seek** ——
     * 跳到 527s 后，下一个轮询发现 460s 那段"还没跳过"，又跳一次。
     *
     * ## 规则
     *
     * 按开始时间排序；当前区间与上一个有重叠**或间隔 < [MERGE_GAP_SECONDS]**
     * 就并入（取并集），否则新起一段。类别取优先级更高的
     * （恰饭广告比片头更值得出现在提示里）。
     */
    fun merge(
        items: List<SkipSegment>,
        mergeGapSeconds: Double = MERGE_GAP_SECONDS,
    ): List<SkipSegment> {
        if (items.size <= 1) return items

        val sorted = items.sortedBy { it.startSeconds }
        val merged = ArrayList<SkipSegment>(sorted.size)

        for (s in sorted) {
            val last = merged.lastOrNull()
            if (last != null && s.startSeconds <= last.endSeconds + mergeGapSeconds) {
                merged[merged.size - 1] = last.copy(
                    // 并集：结束时间取更晚的
                    endSeconds = maxOf(last.endSeconds, s.endSeconds),
                    // 票数取更高的（更可信），用于提示
                    votes = maxOf(last.votes, s.votes),
                    category = pickCategory(last.category, s.category),
                )
            } else {
                merged.add(s)
            }
        }
        return merged
    }

    /**
     * 判断某个位置是否落在片段内、该不该跳。
     *
     * @param segments 已合并的片段（升序）
     * @param positionSeconds 当前播放位置
     * @param alreadySkipped 已跳过的 UUID（跳过的**不再跳**）
     * @return 命中的片段；null = 不跳
     */
    fun findSegmentAt(
        segments: List<SkipSegment>,
        positionSeconds: Double,
        alreadySkipped: Set<String> = emptySet(),
        tailMarginSeconds: Double = TAIL_MARGIN_SECONDS,
    ): SkipSegment? = segments.firstOrNull { s ->
        positionSeconds >= s.startSeconds &&
            positionSeconds < s.endSeconds - tailMarginSeconds &&
            (s.uuid.isEmpty() || s.uuid !in alreadySkipped)
    }

    /**
     * 两个类别合并时保留哪个。
     *
     * 按"用户更在意"排序：恰饭广告 > 自我推广 > 片头/片尾。
     * 未知类别排最后（不认识的类型不该覆盖已知的）。
     */
    fun pickCategory(a: String, b: String): String {
        val rankA = CATEGORY_RANK.indexOf(a).let { if (it < 0) Int.MAX_VALUE else it }
        val rankB = CATEGORY_RANK.indexOf(b).let { if (it < 0) Int.MAX_VALUE else it }
        return if (rankA <= rankB) a else b
    }

    /** 区间间隔小于它就视为同一段。 */
    const val MERGE_GAP_SECONDS = 1.0

    /**
     * 跳过片段的尾部余量（秒）。
     *
     * 位置已非常接近片段末尾时不再跳 —— 否则会产生
     * "跳到 527.0s，而当前位置 526.9s"这种无意义跳转，
     * 且容易与 `endSeconds` 的浮点误差打架形成抖动。
     */
    const val TAIL_MARGIN_SECONDS = 0.5

    /** 合并时的类别优先级（越靠前越"重要"）。 */
    private val CATEGORY_RANK = listOf(
        "sponsor", "selfpromo", "intro", "outro",
        "preview", "music_offtopic", "interaction", "filler",
    )
}
