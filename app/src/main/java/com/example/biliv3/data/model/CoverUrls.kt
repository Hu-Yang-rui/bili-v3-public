package com.example.biliv3.data.model

/**
 * 封面图地址的**唯一**构造函数。
 *
 * ## ⚠️ 为什么必须集中到一处（这是一个真实的 bug 根因）
 *
 * 修复前，每个模型各自拼后缀，宽度**六个不同的值**：
 *
 * | 调用点 | 宽度 |
 * |---|---|
 * | `VideoItem.coverUrl()`（首页卡片 / 搜索卡片 / 详情页相关推荐） | 480 |
 * | `VideoDetail.coverUrl()`（详情页播放器封面） | 960 |
 * | `LibraryScreens`（历史 / 收藏列表缩略图） | 320 |
 * | `RankingScreen` | 320 |
 * | `HomeModels.RankItem` | 160 |
 * | `HomeModels.LiveItem` / `TopicItem` | 192 / 240 |
 *
 * 后果有两个，都会表现为**「同一视频封面不一致」**：
 *
 * 1. **Coil 按 URL 字符串做缓存键**。`@480w` 与 `@960w` 是两个不同的键
 *    → 同一张图被下载并缓存**多份**，内存与磁盘双份浪费。
 * 2. 更严重的是**观感不一致**：B 站图片服务对不同尺寸目标会返回
 *    **不同质量/不同裁切**的结果（实测 `@480w_270h` 得到 16.9KB webp，
 *    `@960w_540h` 得到 36.2KB webp）。列表用低清、详情用高清时，
 *    同一个视频在搜索结果里和在详情页里**看起来就是两张图**。
 *
 * 现在全应用只走这一个函数：**同一个视频 → 同一个 URL 字符串 → 同一份缓存
 * → 同一张图**。
 *
 * ## 尺寸策略
 *
 * 统一取 [DEFAULT_COVER_WIDTH]（480）。理由：
 *
 * - 手机卡片封面实际渲染宽度约 180~200dp，2x/3x 屏下 480px 足够清晰
 * - 详情页播放器封面最宽约 400dp，480px 在 2x 屏下略有放大但可接受
 *   （而且详情页一旦起播就被播放器画面覆盖，封面只在未起播时短暂可见）
 * - 统一尺寸换来的「缓存命中 + 视觉一致」远比这点分辨率差异有价值
 *
 * ## 关于 `@` 后缀
 *
 * B 站图片服务支持 `@<宽>w_<高>h_<裁切>.<格式>` 做服务端缩放。
 * 若原地址**已带** `@` 后缀（部分接口会返回带后缀的地址），
 * 需要先剥掉再套我们自己的尺寸 —— 否则会拼出
 * `xxx.jpg@480w_270h.jpg@480w_270h` 这种非法地址，
 * 表现为**图片加载失败（灰块）**。
 * 实测 B 站对双后缀返回 `HTTP 400`。
 */
object CoverUrls {

    /**
     * 全应用统一的封面宽度。
     *
     * 改这一个值即可全局生效（见 [cover] 的说明）。
     */
    const val DEFAULT_COVER_WIDTH = 480

    /**
     * 构造封面地址。
     *
     * @param raw 接口返回的原始 `pic` / `cover` 字段，允许三种形态：
     *            `//host/...`（协议相对）、`http://...`、`https://...`
     * @param width 目标宽度。**除极特殊情况不要传** ——
     *              传不同宽度就等于放弃缓存复用与视觉一致，
     *              这正是本 bug 的成因。
     * @param aspectRatio 宽高比。视频封面是 16:9；**番剧封面是 3:2 竖版**
     *                    （官方 `viewAspectRatio` 不同），必须区分，
     *                    否则服务端按 16:9 裁切会把竖版海报裁掉上下。
     * @return 可直接交给 Coil 的 https 地址；入参为空时返回空串
     */
    fun cover(
        raw: String,
        width: Int = DEFAULT_COVER_WIDTH,
        aspectRatio: Float = VIDEO_ASPECT_RATIO,
    ): String {
        val base = normalizeHost(stripSizeSuffix(toHttps(raw)))
        if (base.isEmpty()) return ""
        val h = (width / aspectRatio).toInt()
        return "$base@${width}w_${h}h_1c.webp"
    }

    /** 视频封面比例 16:9。 */
    const val VIDEO_ASPECT_RATIO = 16f / 9f

    /**
     * 番剧海报比例 3:2（竖版）。
     *
     * 番剧 / 影视索引返回的 `cover` 是**竖版海报**，
     * 用 16:9 去裁会把人物头部切掉。
     */
    const val BANGUMI_ASPECT_RATIO = 3f / 2f

    /**
     * 构造正方形头像地址（UP 主 / 评论者）。
     *
     * 头像天然是 1:1，与封面的 16:9 不同，所以单独一个函数；
     * 同样剥离已有后缀，避免双后缀非法地址。
     */
    fun avatar(raw: String, size: Int = 96): String {
        val base = normalizeHost(stripSizeSuffix(toHttps(raw)))
        if (base.isEmpty()) return ""
        return "$base@${size}w_${size}h_1c.webp"
    }

    /**
     * 把 B 站 CDN 的负载均衡域名归一化到 [CANONICAL_IMAGE_HOST]。
     *
     * ## 为什么必须做（这是「封面不一致」的第二个独立成因）
     *
     * 同一张图，B 站会在不同接口里返回**不同 shard** 的地址：
     *
     * ```
     * 搜索接口:  //i0.hdslb.com/bfs/archive/d4895152...jpg
     * 详情接口:  //i2.hdslb.com/bfs/archive/d4895152...jpg   ← 同一张图！
     * ```
     *
     * 实测这三个 shard 是**完全等价的镜像**：
     * 同一 path 在 `i0` / `i1` / `i2` 上返回的字节
     * SHA-256 完全一致（三次请求 sha 相同、字节数相同）。
     *
     * 但 **Coil 按 URL 字符串做缓存键** ——
     * `i0` 与 `i2` 会被当成两张不同的图，各下载、各缓存一份。
     * 后果：
     * 1. 同一张封面在内存/磁盘里存了 2~3 份（浪费）
     * 2. 某一份若恰好加载失败或过期，用户就看到"这张有、那张没有/不一样"
     *
     * 归一化到单一 host 后，缓存键唯一，同一张图全局只存一份。
     *
     * ## 只改写图片域名
     *
     * 仅当 host 以 `.hdslb.com` 结尾且以 `i` + 数字开头时改写，
     * 不动其它域名（避免误伤第三方图床或将来换 CDN）。
     */
    /**
     * 把任意接口给的图片地址**归一化成可直接加载的 URL**。
     *
     * ## 为什么需要它（v1.5.3）
     *
     * 不同接口给的图片地址形态不一致，实测见过三种：
     * - `//i0.hdslb.com/xxx.jpg` —— **协议相对**（`cover` 接口常见）
     * - `http://i1.hdslb.com/xxx.jpg` —— **明文 http**
     * - `https://message.biliimg.com/xxx.jpg` —— 已经是对的
     *
     * 直接丢给 Coil 加载 `//` 开头的地址会失败（不是合法 URL），
     * 明文 `http` 在 `targetSdk 28+` 会被系统 cleartext 策略拦掉。
     *
     * ## 与 [cover] / [avatar] 的分工
     *
     * 那两个是**带尺寸参数**的封面/头像专用（会拼 `@480w_270h_1c`）。
     * 私信图片**不需要也不能**加尺寸后缀（原图就是原图），
     * 所以单独开这一个只做「补协议 + 归一 host」的函数。
     *
     * ⚠️ 空串进空串出（调用方据此判"没有图"）。
     */
    fun normalize(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) return url
        val withScheme = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> url
        }
        return normalizeHost(withScheme)
    }

    private fun normalizeHost(url: String): String {
        val idx = url.indexOf("://")
        if (idx < 0) return url
        val schemeEnd = idx + 3
        val slash = url.indexOf('/', schemeEnd)
        if (slash < 0) return url

        val host = url.substring(schemeEnd, slash)
        // 形如 i0.hdslb.com / i1.hdslb.com / i2.hdslb.com
        val isShard = host.length > 3 &&
            host.startsWith("i") &&
            host[1].isDigit() &&
            host.endsWith(".hdslb.com")
        if (!isShard) return url

        return url.substring(0, schemeEnd) + CANONICAL_IMAGE_HOST + url.substring(slash)
    }

    /**
     * 归一化后的图片域名。
     *
     * 选 `i0` 只是因为它是各接口里出现频率最高的一个，没有其它含义
     * —— 三个 shard 字节完全相同，选哪个都一样。
     */
    const val CANONICAL_IMAGE_HOST = "i0.hdslb.com"

    /**
     * 剥掉已存在的 `@...` 尺寸后缀。
     *
     * B 站 CDN 的尺寸后缀一定出现在**最后一个 `@`** 之后，
     * 且其中不含 `/`（是 `480w_270h_1c.webp` 这种形态）。
     * 用「最后一个 @ 之后是否还有 /」来判断，避免误伤
     * 路径里合法含 `@` 的情况。
     */
    private fun stripSizeSuffix(url: String): String {
        val at = url.lastIndexOf('@')
        if (at < 0) return url
        val tail = url.substring(at + 1)
        // 后缀里不该有路径分隔符；有的话说明这个 @ 属于路径而不是尺寸
        if (tail.contains('/')) return url
        return url.substring(0, at)
    }
}
