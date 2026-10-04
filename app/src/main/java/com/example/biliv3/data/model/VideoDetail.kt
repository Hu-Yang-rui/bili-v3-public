package com.example.biliv3.data.model

/**
 * 视频详情（`x/web-interface/wbi/view`）。
 *
 * 字段按实际接口返回建模，只取详情页真正要用的部分。
 * B 站字段类型不稳定，解析层已做容错（见 `BiliApi`）。
 */
data class VideoDetail(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val title: String,
    val cover: String,
    val desc: String,
    /** 发布时刻（Unix 秒）。 */
    val publishedAt: Long,
    /** 时长（秒）。 */
    val durationSeconds: Int,

    // ---- UP 主 ----
    val ownerMid: Long,
    val ownerName: String,
    val ownerFace: String,

    /**
     * UP 主粉丝数。
     *
     * ⚠️ **不在** `view` 接口里 —— 它来自 `x/relation/stat` 的
     * `data.follower`（实测可用）。这是详情页额外的一次请求，
     * 失败时静默为 0（不显示粉丝数，而不是显示错误值）。
     */
    val ownerFans: Int = 0,

    /**
     * UP 主投稿视频数。
     *
     * 来自 `x/space/wbi/arc/search` 的 `page.count`。
     * 与 [ownerFans] 同样属于"增强信息"，失败静默为 0。
     */
    val ownerVideoCount: Int = 0,

    // ---- 统计 ----
    val viewCount: Int,
    val danmakuCount: Int,
    val likeCount: Int,
    val coinCount: Int,
    val favoriteCount: Int,
    val shareCount: Int,
    val replyCount: Int,

    /**
     * 当前在看人数。
     *
     * 来自 `x/player/v2` 的 `data.online_count`（实测可用）。
     * 0 表示未取到 —— UI 据此**不显示**这一项（而不是显示"0 人在看"）。
     */
    val viewers: Int = 0,

    /** 分P。单P视频也有 1 个元素。 */
    val pages: List<VideoPage>,
) {
    /**
     * 封面地址（走全应用统一的 [CoverUrls.cover]，含 http→https 升级）。
     *
     * ⚠️ 默认宽度与 [VideoItem.coverUrl] **一致**（480）。
     * 原先这里默认 960、列表默认 480，同一视频拼出两个不同 URL ——
     * Coil 按 URL 缓存，于是同一张封面被下载两份，
     * 且 B 站对不同尺寸返回不同质量的图，看起来就是"两张不同的图"。
     */
    fun coverUrl(width: Int = CoverUrls.DEFAULT_COVER_WIDTH): String =
        CoverUrls.cover(cover, width)

    fun ownerFaceUrl(size: Int = 96): String = CoverUrls.avatar(ownerFace, size)

    /** 是否多P。决定详情页要不要渲染分P选择器。 */
    val isMultiPart: Boolean get() = pages.size > 1
}

/**
 * 分P。
 *
 * `cid` 是取流和弹幕的关键参数 —— 同一个 bvid 下每个分P 有独立 cid。
 */
data class VideoPage(
    val cid: Long,
    val page: Int,
    val title: String,
    val durationSeconds: Int,
) {
    /** 时长标签，`12:34` / `1:02:03`。 */
    val durationLabel: String
        get() {
            if (durationSeconds <= 0) return ""
            val h = durationSeconds / 3600
            val m = (durationSeconds % 3600) / 60
            val s = durationSeconds % 60
            return if (h > 0) {
                "$h:${pad(m)}:${pad(s)}"
            } else {
                "$m:${pad(s)}"
            }
        }

    private fun pad(v: Int) = v.toString().padStart(2, '0')
}

/**
 * 取流结果。
 *
 * ## ⚠️ 绝不缓存
 *
 * B 站取流 URL **约 2 小时过期**，缓存下来会导致"播到一半 403"。
 * 每次进入详情页/切换清晰度都必须重新请求。
 *
 * ## 为什么要分开存视频和音频 URL
 *
 * `fnval=16` 请求 DASH 时，音视频是**分离的两条流**：
 * - `dash.video` 是数组，按清晰度档位排列（实测未登录给 4 条）
 * - `dash.audio` 也是数组，通常取第一条
 *
 * Media3 用 `MergingMediaSource` 把两条合轨播放。
 */
data class PlayInfo(
    /**
     * 这份流对应的 **cid**（v1.5.1 新增）。
     *
     * ## 🔴 为什么必须带上它
     *
     * 判断"要不要换流"**不能只比 URL**。切分P 时两个 P 有可能拿到
     * **完全相同的 URL**（同清晰度 + CDN 复用，实测会出现）——
     * 那时只比 URL 会得出"流没变"的结论，于是**播放器不换流，仍播上一个 P**。
     *
     * 表现就是用户报告的「选 P2/P3 还是播 P1」。
     *
     * `cid` 是分P 的**唯一标识**，把它放进 `LaunchedEffect` 的 key
     * 与 `PlayerHolder.bindMedia` 的 `same` 判断，才可靠。
     */
    val cid: Long = 0L,
    /** 可选的清晰度档位，如 [120,116,80,64,32,16]（4K→360P）。 */
    val acceptQuality: List<Int>,
    /** 档位对应的中文描述，与 [acceptQuality] 一一对应。 */
    val acceptDescription: List<String>,
    /** 当前选中的清晰度。 */
    val currentQuality: Int,
    val videoUrl: String,
    val audioUrl: String,
    /** 视频编码，如 `avc1.64001F` / `hev1.1.6.L120.90`。 */
    val videoCodecs: String,
    val width: Int,
    val height: Int,
    /** 总时长（秒）。 */
    val durationSeconds: Int,
) {
    /** 当前清晰度的中文名。找不到时回退到 `清晰度 {id}`。 */
    val currentQualityLabel: String
        get() {
            val idx = acceptQuality.indexOf(currentQuality)
            return if (idx >= 0 && idx < acceptDescription.size) {
                acceptDescription[idx]
            } else {
                "清晰度 $currentQuality"
            }
        }
}
