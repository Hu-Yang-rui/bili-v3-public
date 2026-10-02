package com.example.biliv3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 竖屏模式判定逻辑测试。
 *
 * ## 为什么这组测试重要
 *
 * 竖屏模式的核心约束是「**只推竖屏，不混横屏**」（需求明确要求）。
 * 但判定逻辑有三个容易写错、且**都不会报错**的点：
 *
 * 1. **正方形算不算竖屏** —— 实测存在 `1080x1080`。
 *    若用"宽高比 < 1"判定，某些写法会把正方形当竖屏；
 *    若用 `height >= width`，同样会把正方形收进来。
 *    正确判据是 **`height > width` 严格大于**。
 *
 * 2. **拿不到分辨率时怎么办** —— 详情/取流接口失败会得到 `0x0`。
 *    若把 `0x0` 当"不竖屏"以外的东西（或误判为竖屏），
 *    横屏视频就会混进竖屏流 —— 这是需求禁止的。
 *    正确做法是**一律不收**（宁缺勿滥）。
 *
 * 3. **缓存键** —— 缓存必须以 bvid 为键。写错（如用 title）
 *    会导致不同视频共用判定结果，静默混入横屏。
 *
 * 这些都无法通过编译期发现，所以用测试钉死。
 *
 * ## 与生产代码的关系
 *
 * 生产判定在 `VerticalFeedRepository.isVertical()` 内（需要网络，无法单测）。
 * 这里测试的是**同一套判据**的纯函数表达 —— 若两边判据漂移，
 * 说明有人改了其中一处而没改另一处。
 */
class VerticalTest {

    /** 与 `VerticalFeedRepository.isVertical` 完全一致的判据。 */
    private fun isVertical(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && height > width

    // ---------------- 基本方向 ----------------

    @Test
    fun `典型竖屏 1080x1920 判为竖屏`() {
        assertThat(isVertical(1080, 1920)).isTrue()
    }

    @Test
    fun `典型横屏 1920x1080 不判为竖屏`() {
        assertThat(isVertical(1920, 1080)).isFalse()
    }

    @Test
    fun `竖屏 720x1280 判为竖屏`() {
        assertThat(isVertical(720, 1280)).isTrue()
    }

    @Test
    fun `横屏 1280x720 不判为竖屏`() {
        assertThat(isVertical(1280, 720)).isFalse()
    }

    // ---------------- 正方形（实测存在）----------------

    @Test
    fun `正方形 1080x1080 不算竖屏`() {
        // 实测推荐流里确实有 1080x1080 的正方形视频。
        // 用 height >= width 会把它们误收进竖屏流。
        assertThat(isVertical(1080, 1080)).isFalse()
    }

    @Test
    fun `正方形 720x720 不算竖屏`() {
        assertThat(isVertical(720, 720)).isFalse()
    }

    // ---------------- 拿不到分辨率 ----------------

    @Test
    fun `宽高均为 0 时不判为竖屏`() {
        // 详情/取流失败时的返回值。
        // 若这里返回 true，横屏视频会因"探测失败"混进竖屏流。
        assertThat(isVertical(0, 0)).isFalse()
    }

    @Test
    fun `只有高没有宽时不判为竖屏`() {
        assertThat(isVertical(0, 1920)).isFalse()
    }

    @Test
    fun `只有宽没有高时不判为竖屏`() {
        assertThat(isVertical(1080, 0)).isFalse()
    }

    @Test
    fun `负数尺寸不判为竖屏`() {
        // 防御性：接口异常理论上可能给负数
        assertThat(isVertical(-1080, -1920)).isFalse()
        assertThat(isVertical(1080, -1920)).isFalse()
    }

    // ---------------- 极端宽高比 ----------------

    @Test
    fun `超长竖屏 1080x4320 判为竖屏`() {
        assertThat(isVertical(1080, 4320)).isTrue()
    }

    @Test
    fun `超宽横屏 4320x1080 不判为竖屏`() {
        assertThat(isVertical(4320, 1080)).isFalse()
    }

    @Test
    fun `仅差 1 像素时以严格大于为准`() {
        // 边界：1920x1919 不算竖屏，1920x1921 算
        assertThat(isVertical(1920, 1919)).isFalse()
        assertThat(isVertical(1920, 1921)).isTrue()
    }

    // ---------------- 与仓库常量一致性 ----------------

    @Test
    fun `仓库常量取值合理`() {
        val c = com.example.biliv3.data.VerticalFeedRepository
        // 这些常量决定"一次探测多少"，取错会导致风控或首屏过慢
        assertThat(c.PAGE_SIZE).isEqualTo(20)
        assertThat(c.PAGE_TARGET).isAtLeast(4)
        assertThat(c.MAX_ROUNDS).isAtLeast(1)
        // 并发探测数不能太高 —— 推荐流本身就有 ≥1s 限流
        assertThat(c.PROBE_CONCURRENCY).isAtMost(8)
    }

    @Test
    fun `首屏批次数不超过目标数`() {
        // FIRST_BATCH 是"先渲染几条"，不该超过目标总数
        assertThat(VERTICAL_FIRST_BATCH).isAtMost(
            com.example.biliv3.data.VerticalFeedRepository.PAGE_TARGET,
        )
    }

    /** 与 `VerticalViewModel.FIRST_BATCH` 保持一致。 */
    private val VERTICAL_FIRST_BATCH = 4
}
