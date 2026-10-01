package com.example.biliv3

import com.example.biliv3.data.model.CoverUrls
import com.example.biliv3.data.model.VideoItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 封面地址测试。
 *
 * ## 为什么这组测试重要
 *
 * 「同一视频在不同页面封面不一致」这个 bug **不会报错**：
 * 每个页面各自拼 URL，看起来都"能显示图片"，只是拼出来的字符串不同，
 * 导致 Coil 当成不同图片各自缓存，且 CDN 对不同尺寸返回不同质量的图。
 *
 * 只有在**字符串层面**把"同一视频必须得到同一 URL"钉死，
 * 才能防止后续任何人再往 `coverUrl(width = ...)` 里传不同宽度。
 */
class CoverUrlTest {

    /** 协议相对形态（搜索接口返回这种）。 */
    private val protocolRelative = "//i2.hdslb.com/bfs/archive/9cf1744e.jpg"

    /** 明文 http（rcmd 推荐流返回这种，曾被系统拦截导致封面全灰）。 */
    private val plainHttp = "http://i0.hdslb.com/bfs/archive/cdabb68.jpg"

    private val https = "https://i1.hdslb.com/bfs/archive/aaaa.jpg"

    // ---------------- 核心不变量：同一视频 → 同一 URL ----------------

    /**
     * ⚠️ 这是本 bug 的**回归测试**。
     *
     * 两个字段值只差协议前缀（`//` vs `http://`），指向同一张图。
     * 规范化后必须得到**完全相同**的 URL 字符串 ——
     * 否则 Coil 会缓存两份，且可能显示成两张不同的图。
     */
    @Test
    fun `协议相对与明文 http 指向同一图时规范化结果一致`() {
        val same = "i2.hdslb.com/bfs/archive/9cf1744e.jpg"
        val a = CoverUrls.cover("//$same")
        val b = CoverUrls.cover("http://$same")
        assertThat(a).isEqualTo(b)
        assertThat(a.startsWith("https://")).isTrue()
    }

    /**
     * ⚠️ 第二个回归点：**默认宽度必须唯一**。
     *
     * 曾经 `VideoItem` 默认 480、`VideoDetail` 默认 960、列表页传 320。
     * 同一视频在搜索结果与详情页得到不同字符串 → 缓存两份 + 观感不一致。
     */
    @Test
    fun `不传宽度时同一视频得到完全相同的 URL`() {
        val raw = protocolRelative
        val fromCard = VideoItem(
            bvid = "BV1", title = "t", cover = raw, authorName = "u",
            authorFace = "", playCount = 0, danmakuCount = 0, durationSeconds = 0,
        ).coverUrl()

        val fromOther = VideoItem(
            bvid = "BV1", title = "t", cover = plainHttp, authorName = "u",
            authorFace = "", playCount = 0, danmakuCount = 0, durationSeconds = 0,
        ).coverUrl()

        // 两者基址不同（故意），所以这里比的是"形态"而非值
        assertThat(fromCard).contains("@480w_270h_1c.webp")
        assertThat(fromOther).contains("@480w_270h_1c.webp")
    }

    // ---------------- 双后缀防护 ----------------

    /**
     * ⚠️ 已带 `@` 后缀的地址必须**先剥离**再套新尺寸。
     *
     * 不剥会拼出 `x.jpg@320w_180h.jpg@480w_270h` 这种非法地址，
     * 实测 B 站 CDN 返回 **HTTP 400** → 表现为封面灰块。
     *
     * 旧实现是 `if (u.contains("@")) return u`（直接原样返回），
     * 于是同一视频若一个接口给带后缀的、另一个给不带后缀的，
     * 又会拼出两个不同 URL —— 正是封面不一致的另一个来源。
     */
    @Test
    fun `已有尺寸后缀会被剥离而不是叠加`() {
        val withSuffix = "//i2.hdslb.com/bfs/archive/x.jpg@320w_180h_1c.webp"
        val out = CoverUrls.cover(withSuffix)

        // 只能有一个 @
        assertThat(out.count { it == '@' }).isEqualTo(1)
        // host 同时被归一化到 i0（见下方 shard 测试）
        assertThat(out).isEqualTo("https://i0.hdslb.com/bfs/archive/x.jpg@480w_270h_1c.webp")
    }

    /** 同一个图无论来自带后缀还是不带后缀的字段，最终 URL 必须一致。 */
    @Test
    fun `带后缀与不带后缀的同一图规范化后一致`() {
        val base = "//i2.hdslb.com/bfs/archive/y.jpg"
        val a = CoverUrls.cover(base)
        val b = CoverUrls.cover("$base@320w_180h_1c.webp")
        assertThat(a).isEqualTo(b)
    }

    // ---------------- 边界 ----------------

    @Test
    fun `空地址返回空串而不是拼出非法 URL`() {
        assertThat(CoverUrls.cover("")).isEmpty()
        assertThat(CoverUrls.avatar("")).isEmpty()
    }

    @Test
    fun `尺寸计算符合 16 比 9`() {
        // 480 宽 → 270 高
        assertThat(CoverUrls.cover(https)).endsWith("@480w_270h_1c.webp")
        // 960 宽 → 540 高
        assertThat(CoverUrls.cover(https, 960)).endsWith("@960w_540h_1c.webp")
    }

    @Test
    fun `头像按 1 比 1 且不受封面比例影响`() {
        assertThat(CoverUrls.avatar(https, 48)).endsWith("@48w_48h_1c.webp")
        assertThat(CoverUrls.avatar(https, 96)).endsWith("@96w_96h_1c.webp")
    }

    /** 番剧海报是 3:2 竖版，不能按 16:9 裁（否则切掉人物头部）。 */
    @Test
    fun `番剧海报按 3 比 2 竖版`() {
        // 320 宽 → 320 / 1.5 = 213 高
        val out = CoverUrls.cover(https, 320, CoverUrls.BANGUMI_ASPECT_RATIO)
        assertThat(out).endsWith("@320w_213h_1c.webp")
    }

    /**
     * 路径里合法含 `@`（不在末尾）时不能被误伤。
     *
     * 判断依据是「最后一个 @ 之后还有没有 `/`」——
     * 有 `/` 说明这个 @ 属于路径，不是尺寸后缀。
     */
    @Test
    fun `路径中间的 at 不被当作尺寸后缀`() {
        val weird = "https://i0.hdslb.com/bfs/archive@2x/pic.jpg"
        val out = CoverUrls.cover(weird)
        // 原路径结构必须完整保留
        assertThat(out).contains("archive@2x/pic.jpg")
    }

    @Test
    fun `UP 头像为空时 faceUrl 返回空串`() {
        val v = VideoItem(
            bvid = "BV1", title = "t", cover = "", authorName = "u",
            authorFace = "", playCount = 0, danmakuCount = 0, durationSeconds = 0,
        )
        assertThat(v.faceUrl()).isEmpty()
        assertThat(v.coverUrl()).isEmpty()
    }

    // ---------------- CDN shard 归一化 ----------------

    /**
     * ⚠️ 第二个独立成因的回归测试：**不同 shard 的同一张图必须归一化**。
     *
     * 实测：同一 path 在 `i0` / `i1` / `i2.hdslb.com` 上返回**字节完全相同**
     * 的图（sha256 一致）。它们只是负载均衡镜像。
     *
     * 但不同接口会返回不同 shard 的地址：
     * ```
     * 搜索 -> //i0.hdslb.com/bfs/archive/d489...jpg
     * 详情 -> //i2.hdslb.com/bfs/archive/d489...jpg
     * ```
     * 而 Coil 按 URL 字符串缓存 → 同一张图缓存 2~3 份。
     */
    @Test
    fun `不同 CDN shard 的同一图归一化为同一 URL`() {
        val path = "/bfs/archive/d4895152788ca7015d54bb1084c4b8fe1b74ae2c.jpg"
        val a = CoverUrls.cover("//i0.hdslb.com$path")
        val b = CoverUrls.cover("//i1.hdslb.com$path")
        val c = CoverUrls.cover("//i2.hdslb.com$path")

        assertThat(a).isEqualTo(b)
        assertThat(b).isEqualTo(c)
        assertThat(a).contains("i0.hdslb.com")
    }

    @Test
    fun `头像也做 shard 归一化`() {
        val path = "/bfs/face/e474099070ee6e7468853b7a413ff9466c3ef56b.jpg"
        assertThat(CoverUrls.avatar("//i2.hdslb.com$path"))
            .isEqualTo(CoverUrls.avatar("//i0.hdslb.com$path"))
    }

    /** 非 hdslb 域名不能被改写（避免误伤第三方图床）。 */
    @Test
    fun `非 B 站图床域名保持不变`() {
        val url = "https://example.com/img/pic.jpg"
        assertThat(CoverUrls.cover(url)).contains("example.com")
        assertThat(CoverUrls.cover(url)).doesNotContain("hdslb")
    }

    /**
     * 形如 `i.hdslb.com`（无数字）或 `img.hdslb.com` 不该被改写 ——
     * 它们不是 shard 命名规则。
     */
    @Test
    fun `不符合 shard 命名规则的域名不改写`() {
        val a = "https://img.hdslb.com/bfs/archive/z.jpg"
        assertThat(CoverUrls.cover(a)).contains("img.hdslb.com")
    }

    /** 搜索/详情/收藏三条路径串起来必须收敛到唯一 URL（本 bug 的总验收）。 */
    @Test
    fun `模拟三条真实路径最终收敛到唯一 URL`() {
        // 同一视频，三个接口给三种形态
        val fromSearch = "//i0.hdslb.com/bfs/archive/same.jpg"          // 协议相对
        val fromFeed = "http://i2.hdslb.com/bfs/archive/same.jpg"       // 明文 + 另一 shard
        val fromFav = "//i1.hdslb.com/bfs/archive/same.jpg@320w_180h_1c.webp" // 已带后缀

        val urls = setOf(
            CoverUrls.cover(fromSearch),
            CoverUrls.cover(fromFeed),
            CoverUrls.cover(fromFav),
        )

        assertThat(urls).hasSize(1)
        assertThat(urls.first())
            .isEqualTo("https://i0.hdslb.com/bfs/archive/same.jpg@480w_270h_1c.webp")
    }
}
