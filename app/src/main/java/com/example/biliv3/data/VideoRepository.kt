package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.api.Endpoints
import com.example.biliv3.data.model.PlayInfo
import com.example.biliv3.data.model.QualityAvailability
import com.example.biliv3.data.quality.QualityParser
import com.example.biliv3.data.model.VideoDetail
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.data.model.VideoPage
import org.json.JSONObject

/**
 * `x/player/v2` 里我们关心的两项（v1.5.3）。
 *
 * 合并成一个数据类是为了**一次请求取回两项** ——
 * 「在看人数」与「章节」本来就在同一个响应里（`online_count` / `view_points`），
 * 分两次拉纯属浪费一次往返。
 *
 * @param online 在看人数（未取到为 0）
 * @param chapters 章节（没有则空列表 —— **这是常态**，实测 60 个视频全为空）
 */
data class PlayerMeta(
    val online: Int = 0,
    val chapters: List<VideoChapter> = emptyList(),
)

/**
 * 视频详情与取流。
 *
 * ## 两个接口的调用时机不同
 *
 * - [detail]：进入详情页立刻调，用于渲染标题/UP/简介
 * - [playInfo]：**可以延后**，等播放器要起播时再调
 *
 * 分开的理由：详情页首屏不该等取流。取流涉及签名 + CDN 调度，
 * 比详情慢；先出文字信息再出画面，感知速度明显更好
 * （`AGENTS.md` §5.1「动效只服务反馈」）。
 *
 * ## ⚠️ 取流结果绝不缓存
 *
 * 见 [PlayInfo] 的说明 —— URL 约 2h 过期。
 */
class VideoRepository(
    private val api: BiliApi = BiliApi(),
) {

    /**
     * 视频详情。
     *
     * ## 为什么同时接受 `av` 号
     *
     * `x/web-interface/view` 官方就支持 `aid` 与 `bvid` 二选一，
     * 所以不需要"av→bv 换算"这种额外步骤（换算表本身还会随 av 号增长失效）。
     *
     * 用途：查成分（aicu）返回的评论只带 **av 号**（`oid` 是纯数字），
     * 要让用户"回到 App 内看这条评论的上下文"，就得能用 av 号直接取详情。
     * 约定：入参形如 `av123456` 时走 `aid`，否则一律当 bvid。
     */
    suspend fun detail(bvid: String): VideoDetail {
        val avId = bvid.removePrefix("av").takeIf { bvid.startsWith("av") }
            ?.toLongOrNull()

        val query = if (avId != null) {
            mapOf("aid" to avId.toString())
        } else {
            mapOf("bvid" to bvid)
        }

        val json = api.getOk(
            path = Endpoints.VIDEO_VIEW,
            query = query,
            signed = true,
        )
        val d = json.optJSONObject("data")
            ?: throw BiliException(-1, "详情响应缺少 data")

        val owner = d.optJSONObject("owner")
        val stat = d.optJSONObject("stat")

        return VideoDetail(
            bvid = d.optString("bvid").ifEmpty { bvid },
            aid = optLongLoose(d, "aid"),
            cid = optLongLoose(d, "cid"),
            title = d.optString("title"),
            cover = d.optString("pic"),
            desc = d.optString("desc"),
            publishedAt = optLongLoose(d, "pubdate"),
            durationSeconds = optIntLoose(d, "duration"),
            ownerMid = optLongLoose(owner, "mid"),
            ownerName = owner?.optString("name").orEmpty(),
            ownerFace = owner?.optString("face").orEmpty(),
            viewCount = optIntLoose(stat, "view"),
            danmakuCount = optIntLoose(stat, "danmaku"),
            likeCount = optIntLoose(stat, "like"),
            coinCount = optIntLoose(stat, "coin"),
            favoriteCount = optIntLoose(stat, "favorite"),
            shareCount = optIntLoose(stat, "share"),
            replyCount = optIntLoose(stat, "reply"),
            pages = parsePages(d.optJSONArray("pages")),
        )
    }

    /**
     * UP 主粉丝数。
     *
     * ## ⚠️ 为什么单独一个请求（不在 view 接口里）
     *
     * 实测 `x/web-interface/view` 的 `owner` **只有 `mid`/`name`/`face`**，
     * 没有粉丝数。粉丝数在 `x/relation/stat` 的 `data.follower`。
     *
     * 这是详情页的**增强信息**：失败时返回 0，UI 不显示该项
     * （而不是显示"0 粉丝"这种错误信息）。
     */
    suspend fun ownerFans(mid: Long): Int {
        if (mid <= 0) return 0
        return runCatching {
            val json = api.getRaw(
                path = Endpoints.RELATION_STAT,
                query = mapOf("vmid" to mid.toString()),
                signed = false,
            )
            if (json.optInt("code", -1) != 0) return@runCatching 0
            json.optJSONObject("data")?.optInt("follower", 0) ?: 0
        }.getOrDefault(0)
    }

    /**
     * `player/v2` 里我们关心的两项：在看人数 + 章节。
     *
     * ## 🔴 v1.5.3：合并成一个请求
     *
     * 「在看人数」和「章节」来自**同一个** `x/player/v2` 响应
     * （`data.online_count` 与 `data.view_points`）。
     * 原实现只有 `viewerCount`，加章节时若再发一次请求就是**白白多一次往返**。
     *
     * 所以合并成这一个函数，一次拉回两项。UI 侧各取所需。
     *
     * @return `online` = 在看人数（未取到 0）；`chapters` = 章节（没有则空列表）
     */
    suspend fun playerMeta(aid: Long, cid: Long): PlayerMeta {
        if (aid <= 0 || cid <= 0) return PlayerMeta()
        return runCatching {
            val json = api.getRaw(
                path = Endpoints.PLAYER_V2,
                query = mapOf("aid" to aid.toString(), "cid" to cid.toString()),
                signed = false,
            )
            if (json.optInt("code", -1) != 0) return@runCatching PlayerMeta()
            val d = json.optJSONObject("data") ?: return@runCatching PlayerMeta()
            PlayerMeta(
                online = d.optInt("online_count", 0),
                chapters = ChapterParser.parse(d),
            )
        }.getOrDefault(PlayerMeta())
    }

    /**
     * 当前在看人数。
     *
     * 来自 `x/player/v2` 的 `data.online_count`（实测可用）。
     * 未取到返回 0，UI 据此不显示该项。
     *
     * ⚠️ 需要人数**和章节**时用 [playerMeta]，不要调两次（同一次响应）。
     */
    suspend fun viewerCount(aid: Long, cid: Long): Int = playerMeta(aid, cid).online

    /**
     * 取流。
     *
     * @param quality 目标清晰度。传 0 表示"让服务端给默认档"。
     *
     * `fnval=16` 是 DASH 标志位，`fnver=0` / `fourk=1` 与之配套 ——
     * 这三个参数缺一个就拿不到 DASH 流，会退化成整段 flv/mp4。
     */
    suspend fun playInfo(
        bvid: String,
        cid: Long,
        quality: Int = 0,
        /**
         * 当前是否登录（未发版）。
         *
         * ## 为什么由调用方传入，而不是本类自己判断
         *
         * [VideoRepository] **没有** `AuthStore` 依赖 —— 它的其它方法
         * （详情 / 相关推荐）都是公开接口，加一个 auth 依赖只为这一个参数
         * 会扩大耦合面。
         *
         * 而调用方 `VideoDetailViewModel` 本来就知道登录态。
         *
         * ⚠️ 它只影响**"无档位时怎么解释"**（未登录 → 「登录后查看」，
         * 已登录 → 「当前视频不支持」），**不影响取流本身**。
         */
        loggedIn: Boolean = true,
    ): PlayInfo {
        val query = mutableMapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "fnval" to "16",
            "fnver" to "0",
            "fourk" to "1",
        )
        if (quality > 0) query["qn"] = quality.toString()

        val json = api.getOk(
            path = Endpoints.PLAY_URL,
            query = query,
            signed = true,
        )
        val d = json.optJSONObject("data")
            ?: throw BiliException(-1, "取流响应缺少 data")

        val acceptQuality = d.optJSONArray("accept_quality").toIntList()
        val acceptDesc = d.optJSONArray("accept_description").toStringList()
        val currentQuality = d.optInt("quality", acceptQuality.firstOrNull() ?: 0)

        // ---- DASH：音视频分离 ----
        val dash = d.optJSONObject("dash")
        if (dash != null) {
            val videoArr = dash.optJSONArray("video")
            val audioArr = dash.optJSONArray("audio")

            // ⚠️ 视频流选择必须同时考虑「档位」和「编码兼容性」。
            // 详见 [pickVideoStream]。
            val video = pickVideoStream(videoArr, currentQuality)
                ?: throw BiliException(-1, "DASH 无可用视频流")

            // 音频同样要挑编码。B 站会返回多档码率的 AAC，
            // 取第一条通常是 64k（30216）—— 音质偏低。
            // 挑最高码率的那条，音质更好且都是 mp4a，兼容性无差异。
            val audio = pickAudioStream(audioArr)

            val videoUrl = video.optString("baseUrl")
                .ifEmpty { video.optString("base_url") }
            // 音频缺失时不阻塞播放：先出画面（静音），好过整页失败
            val audioUrl = audio?.optString("baseUrl").orEmpty()
                .ifEmpty { audio?.optString("base_url").orEmpty() }

            if (videoUrl.isEmpty()) {
                throw BiliException(-1, "DASH 视频流地址为空")
            }

            return PlayInfo(
                // 🔴 v1.5.1：带上 cid —— 判断"要不要换流"不能只比 URL（见 PlayInfo.cid）
                cid = cid,
                acceptQuality = acceptQuality,
                acceptDescription = acceptDesc,
                currentQuality = currentQuality,
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                videoCodecs = video.optString("codecs"),
                width = video.optInt("width"),
                height = video.optInt("height"),
                durationSeconds = optIntLoose(dash, "duration"),
                // 档位（含权限状态）—— 见 QualityParser 的说明
                qualities = when (val q = QualityParser.parse(d, loggedIn = loggedIn)) {
                    is QualityAvailability.Ok -> q.options
                    else -> emptyList()
                },
            )
        }

        // ---- 退化路径：durl（整段流）----
        // 第三方客户端不做 flv 合并，直接把第一条当整段播放。
        // 能出画面好过直接报错，但会在 UI 上标明是低清整段。
        val durl = d.optJSONArray("durl")
        val first = durl?.optJSONObject(0)
        val url = first?.optString("url").orEmpty()
        if (url.isEmpty()) {
            throw BiliException(-1, "既无 DASH 也无 durl，无法播放")
        }
        return PlayInfo(
            cid = cid,
            acceptQuality = acceptQuality,
            acceptDescription = acceptDesc,
            currentQuality = currentQuality,
            videoUrl = url,
            audioUrl = "",
            videoCodecs = "",
            width = 0,
            height = 0,
            durationSeconds = optIntLoose(first, "length").let {
                // durl 的 length 单位是**毫秒**，转成秒
                if (it > 0) it / 1000 else optIntLoose(d, "timelength") / 1000
            },
            // 退化路径（整段流）没有 dash.video，所以只能靠 support_formats
            // 判断受限档 —— 解析器会处理"没有 dash"的情况。
            qualities = when (val q = QualityParser.parse(d, loggedIn = loggedIn)) {
                is QualityAvailability.Ok -> q.options
                else -> emptyList()
            },
        )
    }

    /** 相关推荐。失败返回空列表（是增强模块，不该阻断详情页）。 */
    suspend fun related(bvid: String): List<VideoItem> {
        val json = try {
            api.getRaw(
                path = Endpoints.RELATED,
                query = mapOf("bvid" to bvid),
                signed = false,
            )
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val item = parseRelated(o) ?: continue
            out.add(item)
        }
        return out
    }

    // ---------------- 解析 ----------------

    /**
     * 从 DASH 视频流数组里挑目标档位。
     *
     * 优先精确匹配 [quality]；没有匹配项时退到第一条
     * （服务端可能因为权限降档，此时第一条即它能给的最好画质）。
     */
    /**
     * 从 DASH 视频流里挑目标档位，**并优先选 H.264**。
     *
     * ## ⚠️ 这是「登录后无法观看视频」的根因
     *
     * 实测 `playurl` 返回的 `dash.video` 里，**同一个 id 会有多条不同编码**：
     * ```
     * id=32:  [0] avc1.64001F (H.264)   [1] hvc1.1.6.L120.90 (HEVC)
     * id=16:  [0] hvc1.1.6.L120.90      [1] avc1.64001E
     * ```
     *
     * 注意 `id=16` 的**第一条就是 HEVC**。
     *
     * 旧实现只按 `id` 匹配、取第一条，于是：
     * - 选 480P（id=32）→ 恰好是 avc1 → 能播（运气）
     * - 选 360P（id=16）→ 拿到 hvc1 → **模拟器/部分设备不支持 HEVC 硬解 → 黑屏**
     *
     * 登录后可选档位变多、更容易命中 HEVC，所以现象表现为
     * 「登录后反而看不了视频」。
     *
     * ## 策略
     *
     * 1. 先按 id 过滤出目标档位的所有候选
     * 2. 在候选里**优先选 avc1（H.264）** —— 兼容性最好，几乎所有设备都能硬解
     * 3. 没有 avc1 才退到其它编码（HEVC 在较新设备上也能播）
     * 4. 目标档位完全没有时，退到"第一个含 avc1 的流"，而不是无脑第一条
     */
    private fun pickVideoStream(arr: org.json.JSONArray?, quality: Int): JSONObject? {
        if (arr == null || arr.length() == 0) return null

        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        if (all.isEmpty()) return null

        // 1. 目标档位的候选
        val candidates = if (quality > 0) {
            all.filter { it.optInt("id", -1) == quality }
        } else {
            emptyList()
        }

        // 2. 在候选里优先 avc1
        candidates.firstOrNull { isAvc(it) }?.let { return it }
        // 3. 候选里没有 avc1，退到候选第一条（至少档位是对的）
        candidates.firstOrNull()?.let { return it }

        // 4. 目标档位不存在（服务端降档）：全局找第一条 avc1，
        //    而不是无脑取 all[0] —— 那可能就是 HEVC
        all.firstOrNull { isAvc(it) }?.let { return it }
        return all.firstOrNull()
    }

    /**
     * 挑音频流。
     *
     * B 站返回多档码率的 AAC（30216=64k / 30232=132k / 30280=192k），
     * 旧实现取 `audio[0]` 会拿到**最低码率**那条。
     * 这里取码率最高的一条 —— 都是 `mp4a`，兼容性无差异，没理由选差的。
     */
    private fun pickAudioStream(arr: org.json.JSONArray?): JSONObject? {
        if (arr == null || arr.length() == 0) return null
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        return all.maxByOrNull { it.optInt("bandwidth", 0) } ?: all.firstOrNull()
    }

    /** 是否 H.264（兼容性最好）。codecs 形如 `avc1.64001F`。 */
    private fun isAvc(o: JSONObject): Boolean =
        o.optString("codecs").startsWith("avc", ignoreCase = true)

    private fun parsePages(arr: org.json.JSONArray?): List<VideoPage> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<VideoPage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                VideoPage(
                    cid = optLongLoose(o, "cid"),
                    page = optIntLoose(o, "page").let { if (it > 0) it else i + 1 },
                    title = o.optString("part"),
                    durationSeconds = optIntLoose(o, "duration"),
                ),
            )
        }
        return out
    }

    /** 相关推荐条目。结构与推荐流类似，但字段更少。 */
    private fun parseRelated(o: JSONObject): VideoItem? {
        val bvid = o.optString("bvid")
        if (bvid.isEmpty()) return null
        val owner = o.optJSONObject("owner")
        val stat = o.optJSONObject("stat")
        return VideoItem(
            bvid = bvid,
            title = o.optString("title"),
            cover = o.optString("pic"),
            authorName = owner?.optString("name").orEmpty(),
            authorFace = owner?.optString("face").orEmpty(),
            playCount = optIntLoose(stat, "view"),
            danmakuCount = optIntLoose(stat, "danmaku"),
            durationSeconds = optIntLoose(o, "duration"),
            publishedAt = optLongLoose(o, "pubdate").takeIf { it > 0 },
        )
    }

    // ---------------- 容错取值 ----------------
    // B 站同一字段可能是 Int / Long / Double / String / 缺失。

    private fun optIntLoose(o: JSONObject?, key: String): Int {
        if (o == null) return 0
        return when (val v = o.opt(key)) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun optLongLoose(o: JSONObject?, key: String): Long {
        if (o == null) return 0
        return when (val v = o.opt(key)) {
            is Long -> v
            is Int -> v.toLong()
            is Double -> v.toLong()
            is String -> v.toLongOrNull() ?: 0
            else -> 0
        }
    }

    private fun org.json.JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        val out = ArrayList<Int>(length())
        for (i in 0 until length()) out.add(optInt(i))
        return out
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out.add(optString(i))
        return out
    }
}
