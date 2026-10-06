package com.example.biliv3

import com.example.biliv3.ui.video.CoinChoice
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 投币选择的**页码 ↔ 数量**映射测试。
 *
 * ## 为什么这组测试重要
 *
 * v1.6.3 把两个卡片换成了 `HorizontalPager`，于是出现了两份可能打架的状态：
 * 点选改的"选中数量"、滑动改的"当前页"。若两者不同步，
 * 会出现**高亮显示 1 枚、实际投出 2 枚** ——
 * 而硬币是不可撤销的消耗品，这个 bug 的代价是真实的硬币。
 *
 * 生产逻辑在 `CoinChoice`（纯 Kotlin），测试与生产调同一份实现。
 * 与既有的 `CoinSelectionTest`（两步交互语义）互补，不重复：
 * 那个管"选择不能等于提交"，这个管"两种输入指向同一份状态"。
 */
class CoinChoiceTest {

    /** 可选数量只有 1 与 2（官方上限）。 */
    @Test
    fun `可选数量是一和二`() {
        assertThat(CoinChoice.COUNTS).containsExactly(1, 2).inOrder()
        assertThat(CoinChoice.size).isEqualTo(2)
    }

    /** 默认 2 枚（与官方一致，也与改动前行为一致）。 */
    @Test
    fun `默认选中两枚`() {
        assertThat(CoinChoice.DEFAULT_COUNT).isEqualTo(2)
        assertThat(CoinChoice.countAt(CoinChoice.defaultPage)).isEqualTo(2)
    }

    /** 页码 → 数量。 */
    @Test
    fun `页码映射到数量`() {
        assertThat(CoinChoice.countAt(0)).isEqualTo(1)
        assertThat(CoinChoice.countAt(1)).isEqualTo(2)
    }

    /** 数量 → 页码。 */
    @Test
    fun `数量映射到页码`() {
        assertThat(CoinChoice.pageOf(1)).isEqualTo(0)
        assertThat(CoinChoice.pageOf(2)).isEqualTo(1)
    }

    /**
     * 越界页码**夹取**而不是抛异常。
     *
     * 快速 fling 时 pager 可能短暂报告超出范围的页号；
     * 为此崩掉一个投币弹窗是不可接受的。
     */
    @Test
    fun `越界页码被夹取`() {
        assertThat(CoinChoice.countAt(-1)).isEqualTo(1)
        assertThat(CoinChoice.countAt(99)).isEqualTo(2)
    }

    /**
     * 非法数量返回 null，而不是悄悄落到第一项。
     *
     * 悄悄兜底会让"想投 3 枚却投了 1 枚"这种错误无声发生。
     */
    @Test
    fun `非法数量不映射到任何页码`() {
        assertThat(CoinChoice.pageOf(0)).isNull()
        assertThat(CoinChoice.pageOf(3)).isNull()
        assertThat(CoinChoice.pageOf(-1)).isNull()
        assertThat(CoinChoice.pageAfterClick(3)).isNull()
    }

    /**
     * ⚠️ 这是本次改动的**回归点**：两种输入必须收敛到同一状态。
     *
     * 点击走 `pageAfterClick` → 滚到该页；滑动直接改页。
     * 两条路都最终读 `countAt(currentPage)`，所以结果必须一致。
     */
    @Test
    fun `点击与滑动得到同一个数量`() {
        // 模拟"滑到第 1 页（2 枚）后又点了 1 枚"
        val afterSwipe = 1
        assertThat(CoinChoice.countAt(afterSwipe)).isEqualTo(2)

        val clickedPage = CoinChoice.pageAfterClick(1)
        assertThat(clickedPage).isNotNull()
        assertThat(CoinChoice.countAt(clickedPage!!)).isEqualTo(1)
    }

    /** 往返映射必须恒等（页码 → 数量 → 页码）。 */
    @Test
    fun `往返映射恒等`() {
        (0 until CoinChoice.size).forEach { page ->
            val count = CoinChoice.countAt(page)
            assertThat(CoinChoice.pageOf(count)).isEqualTo(page)
        }
    }
}
