package com.example.biliv3

import com.example.biliv3.data.FavoritesSync
import com.example.biliv3.data.Settings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 收藏状态广播与设置的测试。
 *
 * ## 为什么测 [FavoritesSync]
 *
 * 「收藏不能做成孤立功能」这条需求，落地就靠这个广播。
 * 它坏掉的表现是**状态不同步**（在 A 页收藏，B 页不更新）——
 * 这种 bug 不报错、不崩溃，只是"数据看着不对"，
 * 属于必须用测试钉死的一类。
 */
class FavoritesSyncTest {

    @Test
    fun `初始版本号为 0`() = runTest {
        val sync = FavoritesSync()
        assertThat(sync.version.first()).isEqualTo(0L)
    }

    /**
     * 核心不变量：每次通知版本号**必须递增**。
     *
     * 订阅方用 `LaunchedEffect(version)` 触发刷新 ——
     * 如果版本号不变，`LaunchedEffect` 不会重新执行，刷新就不会发生，
     * 表现就是"收藏了但列表没更新"。
     */
    @Test
    fun `每次通知版本号递增`() = runTest {
        val sync = FavoritesSync()
        sync.notifyChanged()
        assertThat(sync.version.first()).isEqualTo(1L)
        sync.notifyChanged()
        assertThat(sync.version.first()).isEqualTo(2L)
        sync.notifyChanged()
        assertThat(sync.version.first()).isEqualTo(3L)
    }

    /** 连续通知不会合并（每次都要触发一次刷新）。 */
    @Test
    fun `快速连续通知不会丢失`() = runTest {
        val sync = FavoritesSync()
        repeat(10) { sync.notifyChanged() }
        assertThat(sync.version.first()).isEqualTo(10L)
    }
}

/**
 * 设置默认值测试。
 *
 * ## 为什么测默认值
 *
 * 设置项一旦默认值写错，**首次安装的用户**就会遇到异常行为，
 * 而开发者本地因为已经存过设置值，**永远复现不出来**。
 *
 * 尤其 `preferH264` 必须是 true —— 它是修「登录后无法播放」时加的，
 * 默认 false 会让部分设备直接黑屏（HEVC 硬解不支持）。
 */
class SettingsDefaultsTest {

    @Test
    fun `优先 H264 默认开启`() {
        // ⚠️ 这条是回归防护：改成 false 会让不支持 HEVC 的设备黑屏
        assertThat(Settings().preferH264).isTrue()
    }

    @Test
    fun `自动起播默认关闭`() {
        // 省流量 + 首屏更快，与既有行为一致
        assertThat(Settings().autoPlay).isFalse()
    }

    @Test
    fun `弹幕默认开启且不透明度为 0_9`() {
        assertThat(Settings().danmakuEnabled).isTrue()
        assertThat(Settings().danmakuAlpha).isWithin(0.001f).of(0.9f)
    }

    @Test
    fun `默认倍速为 1 倍且清晰度为自动`() {
        assertThat(Settings().defaultSpeed).isEqualTo(1f)
        assertThat(Settings().defaultQuality).isEqualTo(0)
    }

    @Test
    fun `隐私默认保存历史与个性化推荐`() {
        assertThat(Settings().saveHistory).isTrue()
        assertThat(Settings().personalizedRecommend).isTrue()
    }
}
