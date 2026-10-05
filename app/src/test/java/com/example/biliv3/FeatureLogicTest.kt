package com.example.biliv3

import androidx.compose.ui.graphics.luminance
import com.example.biliv3.data.NotLoggedInException
import com.example.biliv3.data.PlaybackProgressStore
import com.example.biliv3.data.Settings
import com.example.biliv3.data.danmaku.DanmakuItem
import com.example.biliv3.data.model.CoverUrls
import com.example.biliv3.data.model.PlayInfo
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.tokens.DarkColors
import com.example.biliv3.ui.component.userMessageFor
import com.example.biliv3.ui.video.isBlocked
import com.example.biliv3.ui.video.playerAspectRatio
import com.example.biliv3.ui.video.semanticMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 本轮补齐功能的核心逻辑测试。
 *
 * ## 为什么这些必须测
 *
 * 三个功能各有"**看起来对、其实错**"的陷阱，且都不会报错：
 *
 * 1. **续播阈值** —— 判错会让"只看了一眼"的视频每次都弹续播提示
 *    （用户会以为进度记错了），或"快看完"的视频提示从头看
 * 2. **弹幕类型屏蔽** —— 接口的 `mode` 有 1/2/3/6 四种滚动，
 *    若只挡 `mode == 1`，"屏蔽滚动弹幕"这个开关**看起来生效实则漏一半**
 * 3. **主题模式解析** —— 存的是字符串，解析失败会静默回落到跟随系统，
 *    用户"设置了强制深色但没生效"却查不出原因
 */
class FeatureLogicTest {

    // ---------------------------------------------------------------------
    // 1. 续播阈值
    // ---------------------------------------------------------------------

    @Test
    fun `续播阈值——看太少不提示`() {
        val duration = 100_000L
        // 3% —— 用户只是点了一下封面
        assertThat(PlaybackProgressStore.shouldResume(3_000L, duration)).isFalse()
        // 5% 是边界（含）
        assertThat(PlaybackProgressStore.shouldResume(5_000L, duration)).isTrue()
    }

    @Test
    fun `续播阈值——快看完不提示`() {
        val duration = 100_000L
        // 96% —— 视为看完，提示续播没有意义
        assertThat(PlaybackProgressStore.shouldResume(96_000L, duration)).isFalse()
        // 95% 是边界（含）
        assertThat(PlaybackProgressStore.shouldResume(95_000L, duration)).isTrue()
    }

    @Test
    fun `续播阈值——中间区间提示`() {
        val duration = 100_000L
        assertThat(PlaybackProgressStore.shouldResume(50_000L, duration)).isTrue()
        assertThat(PlaybackProgressStore.shouldResume(1L, duration)).isFalse()
    }

    @Test
    fun `续播阈值——无进度或时长未知时不提示`() {
        // 时长未知（详情还没加载）时不能提示，否则会 seek 到一个随机位置
        assertThat(PlaybackProgressStore.shouldResume(50_000L, 0L)).isFalse()
        assertThat(PlaybackProgressStore.shouldResume(0L, 100_000L)).isFalse()
        // 负数（异常数据）不能崩
        assertThat(PlaybackProgressStore.shouldResume(-1L, 100_000L)).isFalse()
    }

    @Test
    fun `进度键——不同分P 必须不同`() {
        // ⚠️ 键相同会让 P1 的进度显示到 P2 上，是分P 视频的经典串味 bug
        assertThat(PlaybackProgressStore.keyOf("BV1xx411c7mD", 111L))
            .isNotEqualTo(PlaybackProgressStore.keyOf("BV1xx411c7mD", 222L))
        assertThat(PlaybackProgressStore.keyOf("BV1xx411c7mD", 111L))
            .isEqualTo("BV1xx411c7mD:111")
    }

    // ---------------------------------------------------------------------
    // 2. 弹幕屏蔽
    // ---------------------------------------------------------------------

    private fun danmaku(
        content: String,
        mode: Int = DanmakuItem.MODE_SCROLL,
    ) = DanmakuItem(
        id = 1L,
        idStr = "1",
        progressMs = 0,
        mode = mode,
        fontSize = 25,
        color = 16777215,
        content = content,
    )

    @Test
    fun `弹幕类型归并——三种滚动都算滚动`() {
        // ⚠️ 这是"屏蔽滚动弹幕"看起来生效实则漏一半的根因：
        // 接口的 mode 有 1/2/3 三种滚动 + 6 逆向滚动
        assertThat(semanticMode(danmaku("a", DanmakuItem.MODE_SCROLL))).isEqualTo(1)
        assertThat(semanticMode(danmaku("a", DanmakuItem.MODE_SCROLL2))).isEqualTo(1)
        assertThat(semanticMode(danmaku("a", DanmakuItem.MODE_SCROLL3))).isEqualTo(1)
        assertThat(semanticMode(danmaku("a", DanmakuItem.MODE_REVERSE))).isEqualTo(1)
    }

    @Test
    fun `弹幕类型归并——顶底各自独立`() {
        assertThat(semanticMode(danmaku("a", DanmakuItem.MODE_TOP))).isEqualTo(5)
        assertThat(semanticMode(danmaku("a", DanmakuItem.MODE_BOTTOM))).isEqualTo(4)
    }

    @Test
    fun `弹幕类型屏蔽——屏蔽滚动会挡掉全部四种滚动`() {
        val blockModes = setOf(1)
        assertThat(isBlocked(danmaku("x", DanmakuItem.MODE_SCROLL), blockModes, emptyList())).isTrue()
        assertThat(isBlocked(danmaku("x", DanmakuItem.MODE_SCROLL2), blockModes, emptyList())).isTrue()
        assertThat(isBlocked(danmaku("x", DanmakuItem.MODE_SCROLL3), blockModes, emptyList())).isTrue()
        assertThat(isBlocked(danmaku("x", DanmakuItem.MODE_REVERSE), blockModes, emptyList())).isTrue()
        // 顶部/底部不该被"屏蔽滚动"影响
        assertThat(isBlocked(danmaku("x", DanmakuItem.MODE_TOP), blockModes, emptyList())).isFalse()
        assertThat(isBlocked(danmaku("x", DanmakuItem.MODE_BOTTOM), blockModes, emptyList())).isFalse()
    }

    @Test
    fun `弹幕关键词屏蔽——子串匹配且大小写不敏感`() {
        val words = listOf("剧透", "FGO")
        assertThat(isBlocked(danmaku("这里剧透了别信"), emptySet(), words)).isTrue()
        // 英文大小写：用户屏蔽 FGO 时 "fgo" 也该挡
        assertThat(isBlocked(danmaku("fgo 玩家路过"), emptySet(), words)).isTrue()
        assertThat(isBlocked(danmaku("正常弹幕"), emptySet(), words)).isFalse()
    }

    @Test
    fun `弹幕屏蔽——空规则不误伤`() {
        // ⚠️ 空规则时若判定为"命中"，会导致**所有弹幕都消失**
        assertThat(isBlocked(danmaku("任意内容"), emptySet(), emptyList())).isFalse()
        // 空关键词不能被当成"命中所有"
        assertThat(isBlocked(danmaku("任意内容"), emptySet(), listOf(""))).isFalse()
    }

    // ---------------------------------------------------------------------
    // 3. 主题（v1.1.3 起只有深色）
    // ---------------------------------------------------------------------

    /**
     * 守住"只有深色"这个决定。
     *
     * ## 为什么用测试钉住
     *
     * 移除浅色主题不是随手改的 —— 原因是**静态页玻璃在物理上做不出来**
     * （背后是纯色底，没有东西可模糊）。
     *
     * 这个理由容易被遗忘，然后有人"顺手把浅色加回来"。
     * 一旦加回来，就会重新出现"浅色下材质是假的"这个问题。
     *
     * 所以：`DarkColors` 必须确实是深色（底够暗），
     * 否则说明有人动了令牌。
     */
    @Test
    fun `深色主题的底色必须是暗的`() {
        val bg = DarkColors.bgBase
        // 相对亮度 < 0.2 才算"深色底"（#0E1116 约 0.006）
        assertThat(bg.luminance()).isLessThan(0.2f)
        // 卡片要比底色亮（深色下靠"提亮"分层，不是靠投影）
        assertThat(DarkColors.bgCard.luminance())
            .isGreaterThan(DarkColors.bgBase.luminance())
    }

    /**
     * 深色下**投影不可见**，分层必须靠描边。
     *
     * 如果哪天有人把 `borderHairline` 改成完全透明，
     * 深色卡片就会"糊成一片"（黑底黑影 + 无描边）。
     */
    @Test
    fun `深色主题的描边必须可见`() {
        assertThat(DarkColors.borderHairline.alpha).isGreaterThan(0f)
    }

    // ---------------------------------------------------------------------
    // 4. 设置默认值（空转开关修完后新增字段的默认必须合理）
    // ---------------------------------------------------------------------

    @Test
    fun `设置默认值——弹幕屏蔽默认为空（不屏蔽任何弹幕）`() {
        val s = Settings()
        assertThat(s.danmakuBlockKeywords).isEmpty()
        assertThat(s.danmakuBlockModes).isEmpty()
    }

    // ---------------------------------------------------------------------
    // 5. 封面 URL 归一化（新页面复用了统一构造，这里守住不回归）
    // ---------------------------------------------------------------------

    @Test
    fun `封面 URL——http 升级为 https`() {
        // ⚠️ rcmd 返回的 pic 是 http://，而 manifest 是 usesCleartextTraffic=false
        // → 封面全灰块。这是本项目最早的缺陷之一，任何新页面都必须走统一构造。
        val url = CoverUrls.cover("http://i2.hdslb.com/bfs/archive/abc.jpg", 480)
        assertThat(url).startsWith("https://")
    }

    @Test
    fun `封面 URL——协议相对地址升级为 https`() {
        val url = CoverUrls.cover("//i0.hdslb.com/bfs/archive/abc.jpg", 480)
        assertThat(url).startsWith("https://")
    }

    // ---------------------------------------------------------------------
    // 6. 未登录文案（回归：此前被误译成「加载失败」）
    // ---------------------------------------------------------------------

    @Test
    fun `未登录异常必须提示去登录而不是加载失败`() {
        // 回归：userMessageFor 此前只判网络类错误，NotLoggedInException
        // 掉进 else 被翻译成「加载失败，请稍后重试」——
        // 语义完全错：用户要的是"去登录"，重试多少次都不会成功。
        assertThat(userMessageFor(NotLoggedInException())).isEqualTo("请先登录")
    }

    @Test
    fun `以请先登录为文案的通用异常也要走登录提示`() {
        // 有些仓库直接 throw IllegalStateException("请先登录")
        assertThat(userMessageFor(IllegalStateException("请先登录"))).isEqualTo("请先登录")
    }

    // ---------------- 错误文案不得泄露英文技术细节 ----------------

    /**
     * 回归：断网时首页曾显示
     * `Unable to resolve host "api.bilibili.com": No address associated with hostname`。
     *
     * ## 根因（不在 `userMessageFor`，而在 `BiliException.userMessage`）
     *
     * 网络层会把 `UnknownHostException` 包装成
     * `BiliException(code = -1, message = <原始英文>)`，
     * 而 `userMessage` 的 `else` 分支当时是 `message.ifEmpty { ... }` ——
     * **原样透传**。
     *
     * `userMessageFor` 里虽然有网络识别，但 `BiliException` 分支**优先命中**，
     * 根本走不到那里。所以两处都要能识别。
     */
    @Test
    fun `BiliException 不得把英文异常原样透出`() {
        val e = com.example.biliv3.data.api.BiliException(
            code = -1,
            message = "Unable to resolve host \"api.bilibili.com\": " +
                "No address associated with hostname",
        )
        val msg = e.userMessage
        // 必须是中文，且不含英文技术细节
        assertThat(msg).isEqualTo("网络不可用，请检查连接后重试")
        assertThat(msg).doesNotContain("Unable to resolve")
        assertThat(msg).doesNotContain("api.bilibili.com")
    }

    @Test
    fun `BiliException 的超时与连接失败也要翻译`() {
        assertThat(
            com.example.biliv3.data.api.BiliException(-1, "connect timeout").userMessage,
        ).isEqualTo("网络不可用，请检查连接后重试")

        assertThat(
            com.example.biliv3.data.api.BiliException(-1, "Failed to connect to /1.2.3.4").userMessage,
        ).isEqualTo("连接服务器失败，请稍后重试")
    }

    /** 已经是中文的自定义文案应原样保留（不要被兜底覆盖）。 */
    @Test
    fun `BiliException 的中文自定义文案原样保留`() {
        assertThat(
            com.example.biliv3.data.api.BiliException(-1, "这个视频需要登录才能看").userMessage,
        ).isEqualTo("这个视频需要登录才能看")
    }

    /** 纯英文且识别不出的，兜底成通用中文（绝不透出英文）。 */
    @Test
    fun `BiliException 无法识别的英文兜底为中文`() {
        val msg = com.example.biliv3.data.api.BiliException(-1, "some weird failure").userMessage
        assertThat(msg).isEqualTo("加载失败，请稍后重试")
    }

    @Test
    fun `网络类异常仍走原有文案不被登录分支吃掉`() {
        assertThat(userMessageFor(java.net.UnknownHostException("Unable to resolve host x")))
            .isEqualTo("网络不可用，请检查连接后重试")
        assertThat(userMessageFor(RuntimeException("something else")))
            .isEqualTo("加载失败，请稍后重试")
    }

    // ---------------------------------------------------------------------
    // 7. 播放器比例（回归：此前忽略真实分辨率，导致画面变形/黑边）
    // ---------------------------------------------------------------------

    private fun info(w: Int, h: Int) = PlayInfo(
        acceptQuality = listOf(80),
        acceptDescription = listOf("1080P"),
        currentQuality = 80,
        videoUrl = "https://example.com/v.m4s",
        audioUrl = "https://example.com/a.m4s",
        videoCodecs = "avc1.64001F",
        width = w,
        height = h,
        durationSeconds = 100,
    )

    @Test
    fun `横屏视频的容器至少 4 比 3 高 —— 否则竖屏手机上播放区域太小`() {
        // 🔴 v1.6.2 行为变更（用户要求"播放区域扩大"）。
        //
        // ⚠️ 方向容易搞反：`aspectRatio = 宽/高`，**值越小容器越高**。
        //   - 16:9 = 1.78 → 高 = 0.56×宽（1080 宽屏 = 608px 高）
        //   - 4:3  = 1.33 → 高 = 0.75×宽（810px 高）← 更大
        // 所以"扩大播放区域"= 取**更小**的比例值。
        //
        // 画面由 PlayerView 的 RESIZE_MODE_FIT 按原比例缩放，
        // **不会变形**，只是上下黑边更宽。
        val r = playerAspectRatio(WindowSize.Mobile, info(1920, 1080))
        assertThat(r).isWithin(0.001f).of(4f / 3f)
        // 判据：必须**小于** 16:9（即容器比原来更高）
        assertThat(r).isLessThan(16f / 9f)
    }

    @Test
    fun `比 4 比 3 更高的横屏视频保持原比例 —— 不被压矮`() {
        // 4:3 本身不动
        assertThat(playerAspectRatio(WindowSize.Mobile, info(1440, 1080)))
            .isWithin(0.001f).of(4f / 3f)
        // 更接近正方形（1.11 < 1.33）的视频已经比 4:3 更高，不该被改成 4:3
        assertThat(playerAspectRatio(WindowSize.Mobile, info(1200, 1080)))
            .isWithin(0.001f).of(1200f / 1080f)
    }

    @Test
    fun `竖版视频用 9 比 16 而不是被压成横版`() {
        val r = playerAspectRatio(WindowSize.Mobile, info(1080, 1920))
        assertThat(r).isWithin(0.001f).of(9f / 16f)
    }

    @Test
    fun `竖版视频不被放大到 4 比 3 —— 否则会顶掉整屏`() {
        // 竖版本来就高，再放大到 4:3 会把下面的简介/评论挤出屏幕。
        // 判据：9:16 的视频必须**小于** 4:3。
        val r = playerAspectRatio(WindowSize.Mobile, info(1080, 1920))
        assertThat(r).isLessThan(4f / 3f)
    }

    @Test
    fun `未起播时回退到断点值以保证封面不跳变`() {
        assertThat(playerAspectRatio(WindowSize.Mobile, null))
            .isWithin(0.001f).of(4f / 3f)
        assertThat(playerAspectRatio(WindowSize.Tablet, null))
            .isWithin(0.001f).of(16f / 9f)
    }

    @Test
    fun `脏分辨率不产生塌陷或撑爆的比例`() {
        // 接口脏数据（0 / 负数 / 极端值）必须回退，不能把容器压成一条线
        assertThat(playerAspectRatio(WindowSize.Mobile, info(0, 1080)))
            .isWithin(0.001f).of(4f / 3f)
        assertThat(playerAspectRatio(WindowSize.Mobile, info(1920, 0)))
            .isWithin(0.001f).of(4f / 3f)
        assertThat(playerAspectRatio(WindowSize.Mobile, info(1, 9999)))
            .isWithin(0.001f).of(4f / 3f)
        assertThat(playerAspectRatio(WindowSize.Mobile, info(9999, 1)))
            .isWithin(0.001f).of(4f / 3f)
    }
}
