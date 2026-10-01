package com.example.biliv3.data.model

/**
 * 视频卡片 —— 主页最核心的数据模型。
 *
 * 推荐流 / 热门榜 / 搜索 / 相关推荐都用它，避免为每个接口写一套 UI 模型。
 */
data class VideoItem(
    val bvid: String,
    val title: String,
    val cover: String,
    val authorName: String,
    val authorFace: String,
    val playCount: Int,
    val danmakuCount: Int,
    val durationSeconds: Int,
    val publishedAt: Long? = null,
) {
    /** `12:34` / `1:02:03`。时长未知时返回空串（角标不渲染）。 */
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

    /**
     * 封面地址。
     *
     * ## ⚠️ 必须走 [CoverUrls.cover]（曾经漏掉，导致封面全灰）
     *
     * `rcmd` 推荐流返回的 `pic` 字段**实测 20/20 条都是 `http://` 明文**：
     * ```
     * pic = http://i0.hdslb.com/bfs/archive/9bfe6865...jpg
     * ```
     * 而 `AndroidManifest.xml` 是 `usesCleartextTraffic="false"`（且没有
     * networkSecurityConfig 例外），Android 9+ 会**直接拦截明文 HTTP**。
     *
     * 早期实现只重写 `//` 协议相对前缀（注释里也这么写的），
     * 于是 `http://` 原样透传 → Coil 请求被系统拒绝 → 整屏封面都是
     * `skeletonBase` 灰块（实测像素 `#EDEEF0`）。
     *
     * 对照证据：同一次请求里 `owner.face` 是 `https://`（20/20），
     * 同一张卡片上的头像能正常渲染 —— 排除 Coil / 网络问题，就是 scheme。
     *
     * ## 尺寸统一（第二个 bug）
     *
     * 原先这里默认 480、`VideoDetail` 默认 960、列表页传 320 ——
     * 同一视频在不同页面拼出**不同的 URL**，Coil 视为不同图片各自缓存，
     * 且 B 站对不同尺寸返回不同质量的图，观感就是"封面不一致"。
     * 现在统一走 [CoverUrls.cover] 的单一宽度。
     */
    fun coverUrl(width: Int = CoverUrls.DEFAULT_COVER_WIDTH): String =
        CoverUrls.cover(cover, width)

    /** UP 头像地址（带尺寸）。同样走 [CoverUrls.avatar] 兜底。 */
    fun faceUrl(size: Int = 48): String = CoverUrls.avatar(authorFace, size)
}

/**
 * 把图片 URL 规整成 `https://`。
 *
 * 覆盖三种实际出现过的形态：
 * - `//i0.hdslb.com/...` —— 协议相对（老文档假设的形态）
 * - `http://i0.hdslb.com/...` —— **当前接口的实际返回**，明文会被系统拦截
 * - `https://i0.hdslb.com/...` —— 已是安全协议，原样返回
 *
 * ## ⚠️ 只应由 [CoverUrls] 调用
 *
 * 早期各模型各自调它拼 URL，导致同一张图在不同页面拼出不同字符串
 * （宽度不同 / shard 不同 / 后缀叠加），Coil 按字符串缓存 → 缓存分裂
 * → 表现为「同一视频封面不一致」。
 *
 * 现在 URL 构造**统一收口到 [CoverUrls]**，本函数只是它内部的一步。
 * 新增页面不要再直接调它，走 `CoverUrls.cover()` / `CoverUrls.avatar()`。
 */
internal fun toHttps(url: String): String = when {
    url.startsWith("//") -> "https:$url"
    url.startsWith("http://") -> "https://" + url.removePrefix("http://")
    else -> url
}

/** 播放量格式化：12345 → `1.2万`，123456789 → `1.2亿`。 */
fun formatCount(n: Int): String = when {
    n < 10_000 -> n.toString()
    n < 100_000_000 -> {
        val w = n / 10_000.0
        if (w >= 100) "${w.toInt()}万" else "${(w * 10).toInt() / 10.0}万"
    }
    else -> {
        val y = n / 100_000_000.0
        if (y >= 100) "${y.toInt()}亿" else "${(y * 10).toInt() / 10.0}亿"
    }
}

/** 相对时间：`3天前`。 */
fun formatRelativeTime(epochSeconds: Long?): String {
    if (epochSeconds == null || epochSeconds <= 0) return ""
    val diff = System.currentTimeMillis() / 1000 - epochSeconds
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60}分钟前"
        diff < 86400 -> "${diff / 3600}小时前"
        diff < 86400 * 30 -> "${diff / 86400}天前"
        diff < 86400 * 365 -> "${diff / (86400 * 30)}个月前"
        else -> "${diff / (86400 * 365)}年前"
    }
}
