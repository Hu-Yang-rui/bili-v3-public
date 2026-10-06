package com.example.biliv3

import com.example.biliv3.ui.video.CoinThrow
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * 投币动画相位机测试。
 *
 * ## 为什么这组测试重要
 *
 * 需求里有一条硬约束：**不允许瞬移**。
 * 而"瞬移"恰恰是这类代码最容易出的错 —— 相位切换那一帧位置算错，
 * 硬币就从手上"跳"到半空中。它不崩、不报错，只是看起来廉价，
 * 且取决于帧率与调度，人工点测很难稳定复现。
 *
 * 所以这里用**采样连续性**把它钉死：沿时间轴密集采样，
 * 相邻两点的位移必须小于一个与采样间隔相称的阈值。
 */
class CoinThrowTest {

    /** 采样步长（毫秒）。比一帧（约 16ms）更细，确保能抓到单帧级的跳变。 */
    private val stepMs = 8L

    private fun samples(fn: (Long) -> Float): List<Pair<Long, Float>> =
        (0L..CoinThrow.TOTAL_MS + stepMs step stepMs).map { it to fn(it) }

    // ---------------- 相位边界 ----------------

    /**
     * 边界必须是**左闭右开**。
     *
     * 闭区间会让两个相位在同一毫秒同时成立，调用方取哪个都可能 ——
     * 表现就是"偶尔抖一下"。
     */
    @Test
    fun `相位边界是左闭右开`() {
        assertThat(CoinThrow.phaseAt(0L)).isEqualTo(CoinThrow.Phase.Reaching)
        assertThat(CoinThrow.phaseAt(CoinThrow.REACH_MS - 1)).isEqualTo(CoinThrow.Phase.Reaching)
        assertThat(CoinThrow.phaseAt(CoinThrow.REACH_MS)).isEqualTo(CoinThrow.Phase.Holding)

        val holdEnd = CoinThrow.REACH_MS + CoinThrow.HOLD_MS
        assertThat(CoinThrow.phaseAt(holdEnd - 1)).isEqualTo(CoinThrow.Phase.Holding)
        assertThat(CoinThrow.phaseAt(holdEnd)).isEqualTo(CoinThrow.Phase.Throwing)

        assertThat(CoinThrow.phaseAt(CoinThrow.TOTAL_MS - 1)).isEqualTo(CoinThrow.Phase.Throwing)
        assertThat(CoinThrow.phaseAt(CoinThrow.TOTAL_MS)).isEqualTo(CoinThrow.Phase.Done)
    }

    /** 负时间 = 静止态（还没开始投）。 */
    @Test
    fun `负时间表示静止态`() {
        assertThat(CoinThrow.phaseAt(-1L)).isEqualTo(CoinThrow.Phase.Hovering)
        assertThat(CoinThrow.phaseAt(-99999L)).isEqualTo(CoinThrow.Phase.Hovering)
    }

    /** 相位必须随时间**单调前进**，不能回退。 */
    @Test
    fun `相位随时间单调前进`() {
        val order = listOf(
            CoinThrow.Phase.Hovering,
            CoinThrow.Phase.Reaching,
            CoinThrow.Phase.Holding,
            CoinThrow.Phase.Throwing,
            CoinThrow.Phase.Done,
        )
        var last = -1
        var t = -stepMs
        while (t <= CoinThrow.TOTAL_MS + stepMs) {
            val idx = order.indexOf(CoinThrow.phaseAt(t))
            assertThat(idx).isAtLeast(last)
            last = idx
            t += stepMs
        }
    }

    // ---------------- 相位内进度 ----------------

    /** 每个动态相位内进度都要从 0 走到接近 1。 */
    @Test
    fun `相位内进度从零到一`() {
        assertThat(CoinThrow.progressAt(0L)).isEqualTo(0f)
        assertThat(CoinThrow.progressAt(CoinThrow.REACH_MS - 1)).isGreaterThan(0.9f)

        val holdStart = CoinThrow.REACH_MS
        assertThat(CoinThrow.progressAt(holdStart)).isEqualTo(0f)
        assertThat(CoinThrow.progressAt(CoinThrow.REACH_MS + CoinThrow.HOLD_MS - 1))
            .isGreaterThan(0.9f)

        val throwStart = CoinThrow.REACH_MS + CoinThrow.HOLD_MS
        assertThat(CoinThrow.progressAt(throwStart)).isEqualTo(0f)
        assertThat(CoinThrow.progressAt(CoinThrow.TOTAL_MS - 1)).isGreaterThan(0.9f)
    }

    /** 进度始终落在 0..1（越界会让位移飞出屏幕）。 */
    @Test
    fun `进度恒在零到一之间`() {
        samples { CoinThrow.progressAt(it) }.forEach { (t, p) ->
            assertThat(p).isAtLeast(0f)
            assertThat(p).isAtMost(1f)
        }
    }

    // ---------------- 连续性（核心：不允许瞬移）----------------

    /**
     * ⚠️ 这是本文件的**核心断言**：硬币纵向位置不能跳变。
     *
     * 采样间隔 8ms，取币与投掷的正常速度下每步位移远小于 0.2 个单位。
     * 任何相位边界算错（例如 Reaching 结束直接跳到 Throwing 的中段）
     * 都会产生一个大跳，被这条测试抓住。
     */
    @Test
    fun `硬币纵向位置连续无跳变`() {
        val s = samples { CoinThrow.coinLift(it) }
        s.zipWithNext { (t0, v0), (t1, v1) ->
            assertThat(abs(v1 - v0))
                .isLessThan(CONTINUITY_TOLERANCE)
        }
    }

    /** 手的高度同样必须连续（抬手是平滑动作）。 */
    @Test
    fun `手的高度连续无跳变`() {
        val s = samples { CoinThrow.handLift(it) }
        s.zipWithNext { (_, v0), (_, v1) ->
            assertThat(abs(v1 - v0)).isLessThan(CONTINUITY_TOLERANCE)
        }
    }

    /** 横向偏移连续。 */
    @Test
    fun `硬币横向位置连续无跳变`() {
        val s = samples { CoinThrow.coinDrift(it) }
        s.zipWithNext { (_, v0), (_, v1) ->
            assertThat(abs(v1 - v0)).isLessThan(CONTINUITY_TOLERANCE)
        }
    }

    /** 不透明度连续（淡出不能一帧闪没）。 */
    @Test
    fun `硬币不透明度连续无跳变`() {
        val s = samples { CoinThrow.coinAlpha(it) }
        s.zipWithNext { (_, v0), (_, v1) ->
            assertThat(abs(v1 - v0)).isLessThan(CONTINUITY_TOLERANCE)
        }
    }

    /** 特效强度连续（取币时渐弱，不是"啪"地消失）。 */
    @Test
    fun `悬浮特效强度连续无跳变`() {
        val s = samples { CoinThrow.hoverFxIntensity(it) }
        s.zipWithNext { (_, v0), (_, v1) ->
            assertThat(abs(v1 - v0)).isLessThan(CONTINUITY_TOLERANCE)
        }
    }

    // ---------------- 流程语义 ----------------

    /**
     * 静止态：手抬起 + 硬币停在悬浮位 + 特效最强。
     *
     * 需求原文是"小人保持抬手动作，手的上方悬浮一枚硬币" ——
     * 抬手与悬浮是**常态**，不是动画的某一帧。
     */
    @Test
    fun `静止态手抬起且硬币悬浮`() {
        assertThat(CoinThrow.handLift(-1L)).isEqualTo(1f)
        assertThat(CoinThrow.coinLift(-1L)).isEqualTo(0f)
        assertThat(CoinThrow.hoverFxIntensity(-1L)).isEqualTo(1f)
    }

    /**
     * ⚠️ 取币必须真的把硬币**拿下来**。
     *
     * 需求："小人从空中取下浮空的硬币"。
     * 硬币要落到手的位置（-1），而不是原地消失、再在手里出现 ——
     * 那就是瞬移。
     */
    @Test
    fun `取币阶段硬币落到手里`() {
        val holdEnd = CoinThrow.REACH_MS + CoinThrow.HOLD_MS - 1
        assertThat(CoinThrow.coinLift(holdEnd)).isWithin(0.05f).of(-1f)
    }

    /** 取币期间特效必须减弱（硬币都被拿走了，还留着光晕是错的）。 */
    @Test
    fun `取币后悬浮特效归零`() {
        assertThat(CoinThrow.hoverFxIntensity(CoinThrow.REACH_MS + CoinThrow.HOLD_MS))
            .isEqualTo(0f)
        assertThat(CoinThrow.hoverFxIntensity(CoinThrow.TOTAL_MS)).isEqualTo(0f)
    }

    /**
     * 投掷必须有**向上的完整位移**（飞向视频），且终点高于起点。
     *
     * 需求："小人将硬币向视频方向投出 / 硬币产生运动轨迹"。
     * 若终点低于起点，看起来像"把硬币扔到地上"。
     */
    @Test
    fun `投掷阶段硬币向上飞出`() {
        val start = CoinThrow.REACH_MS + CoinThrow.HOLD_MS
        val y0 = CoinThrow.coinLift(start)
        val y1 = CoinThrow.coinLift(CoinThrow.TOTAL_MS - 1)
        assertThat(y1).isGreaterThan(y0)
        // 至少要飞出"悬浮位"以上，才读得出"投向了画面"
        assertThat(y1).isGreaterThan(0f)
    }

    /** 投掷后段淡出，避免"飞出屏幕被硬裁掉"。 */
    @Test
    fun `投掷末段硬币淡出`() {
        assertThat(CoinThrow.coinAlpha(CoinThrow.TOTAL_MS - 1)).isLessThan(0.2f)
        assertThat(CoinThrow.coinAlpha(CoinThrow.TOTAL_MS)).isEqualTo(0f)
    }

    /** 动画总时长必须等于三段之和（否则会有一段"静默不动"）。 */
    @Test
    fun `总时长等于各段之和`() {
        assertThat(CoinThrow.TOTAL_MS)
            .isEqualTo(CoinThrow.REACH_MS + CoinThrow.HOLD_MS + CoinThrow.THROW_MS)
    }

    companion object {
        /**
         * 相邻采样点允许的最大位移。
         *
         * 8ms 步长下正常速度远小于此值；设成 0.2 是为了"抓跳变而不是抓斜率"——
         * 太小会因为缓动本身的快速段误报。
         */
        private const val CONTINUITY_TOLERANCE = 0.2f
    }
}
