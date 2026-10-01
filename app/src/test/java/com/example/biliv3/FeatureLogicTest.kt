package com.example.biliv3

import com.example.biliv3.data.PlaybackProgressStore
import com.example.biliv3.data.Settings
import com.example.biliv3.data.ThemeMode
import com.example.biliv3.data.danmaku.DanmakuItem
import com.example.biliv3.data.model.CoverUrls
import com.example.biliv3.ui.video.isBlocked
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
    // 3. 主题模式
    // ---------------------------------------------------------------------

    @Test
    fun `主题模式解析——三种取值与未知兜底`() {
        assertThat(ThemeMode.fromKey("dark")).isEqualTo(ThemeMode.Dark)
        assertThat(ThemeMode.fromKey("light")).isEqualTo(ThemeMode.Light)
        assertThat(ThemeMode.fromKey("system")).isEqualTo(ThemeMode.System)
        // 未知 / 缺失 → 跟随系统（不能崩，也不能静默变成强制深色）
        assertThat(ThemeMode.fromKey(null)).isEqualTo(ThemeMode.System)
        assertThat(ThemeMode.fromKey("")).isEqualTo(ThemeMode.System)
        assertThat(ThemeMode.fromKey("garbage")).isEqualTo(ThemeMode.System)
    }

    @Test
    fun `主题模式——key 与枚举一一对应`() {
        ThemeMode.entries.forEach { mode ->
            assertThat(ThemeMode.fromKey(mode.key)).isEqualTo(mode)
        }
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

    @Test
    fun `设置默认值——主题默认跟随系统`() {
        assertThat(Settings().themeMode).isEqualTo(ThemeMode.System)
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
}
