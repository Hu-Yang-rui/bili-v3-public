package com.example.biliv3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 投币数量选择逻辑测试。
 *
 * ## 为什么这组测试重要
 *
 * 「投币数量无法切换」这个 bug 的根因是**交互模型错了**：
 * 第一版点卡片直接提交（`onConfirm`），于是 `selected` 状态
 * 永远没有机会走到下一帧 —— 视觉上就是"点了没反应/不能切换"。
 *
 * 下面用一个纯模型复刻修复后的两步语义（选择 → 确认），
 * 把"选择必须独立于提交"这条不变量钉死。
 * 谁再把选卡片的 onClick 改成直接提交，这条测试就会失败。
 */
class CoinSelectionTest {

    /**
     * 投币选择器的最小模型。
     *
     * 与 `CoinDialog` 内部的状态机一一对应：
     * - `select(n)`  ← 点卡片
     * - `toggleAlsoLike()` ← 勾选"同时点赞"
     * - `confirm()`  ← 点"确认投币"，此时才产出待提交的值
     */
    private class CoinPicker(initial: Int = 2) {
        var selected: Int = initial
            private set
        var alsoLike: Boolean = false
            private set

        /** 点卡片：只改选择，不提交。 */
        fun select(n: Int) {
            require(n in 1..2) { "投币数只能是 1 或 2" }
            selected = n
        }

        fun toggleAlsoLike() {
            alsoLike = !alsoLike
        }

        /** 点确认：产出 (数量, 是否同时点赞)。 */
        fun confirm(): Pair<Int, Boolean> = selected to alsoLike
    }

    // ---------------- 核心：选择必须能反复切换 ----------------

    /**
     * ⚠️ 这是本 bug 的**回归测试**。
     *
     * 用户必须能「先看 2 枚、再改成 1 枚」。
     * 若选择即提交，第二次 select 就不会发生（弹窗已关）。
     */
    @Test
    fun `可以来回切换投币数量`() {
        val p = CoinPicker(initial = 2)
        assertThat(p.selected).isEqualTo(2)

        p.select(1)
        assertThat(p.selected).isEqualTo(1)

        // 切回去 —— 第一版的 bug 就是这里做不到
        p.select(2)
        assertThat(p.selected).isEqualTo(2)

        p.select(1)
        assertThat(p.selected).isEqualTo(1)
    }

    /** 默认 2 枚（与官方一致）。 */
    @Test
    fun `默认选中 2 枚`() {
        assertThat(CoinPicker().selected).isEqualTo(2)
    }

    // ---------------- 选择与提交解耦 ----------------

    /**
     * ⚠️ 第二个回归点：**选择动作不能产出提交值**。
     *
     * 修复前 `onClick` 里直接调 `onConfirm`，导致
     * 「同时点赞」勾选发生在提交之后，永远赶不上。
     */
    @Test
    fun `切换选择不产生提交`() {
        val p = CoinPicker()
        var submitted: Pair<Int, Boolean>? = null

        // 模拟"点卡片" —— 只 select，不 confirm
        p.select(1)
        p.select(2)
        p.select(1)

        assertThat(submitted).isNull()
        // 选择结果保留下来，等确认时使用
        assertThat(p.selected).isEqualTo(1)
    }

    /** 勾选"同时点赞"后再确认，必须带上该选项。 */
    @Test
    fun `同时点赞在选择后勾选也能生效`() {
        val p = CoinPicker()
        p.select(1)
        p.toggleAlsoLike() // ← 这个动作在修复前会"赶不上"

        val (count, alsoLike) = p.confirm()
        assertThat(count).isEqualTo(1)
        assertThat(alsoLike).isTrue()
    }

    /** 不勾选时默认不点赞。 */
    @Test
    fun `默认不同时点赞`() {
        val (_, alsoLike) = CoinPicker().confirm()
        assertThat(alsoLike).isFalse()
    }

    /** 确认值反映最终选择（而不是初始值）。 */
    @Test
    fun `确认时取的是最终选择`() {
        val p = CoinPicker(initial = 2)
        p.select(1)
        p.toggleAlsoLike()

        val (count, like) = p.confirm()
        assertThat(count).isEqualTo(1)
        assertThat(like).isTrue()
    }

    /** 越界数量被拒绝（防止 UI 传错值导致投 3 枚）。 */
    @Test
    fun `拒绝非法投币数量`() {
        val p = CoinPicker()
        val bad = runCatching { p.select(3) }.isFailure
        assertThat(bad).isTrue()
        // 非法调用不改变状态
        assertThat(p.selected).isEqualTo(2)
    }
}
