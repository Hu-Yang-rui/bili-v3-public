package com.example.biliv3.ui.video

/**
 * 投币数量选择器的**选择逻辑**（纯 Kotlin，可单测）。
 *
 * ## 为什么需要它（需求："不能只支持点击"）
 *
 * 原实现只有两个 `CoinOptionCard`，各自一个 `onClick`。
 * 需求要求**同时**支持点击与左右滑动，于是出现一个新问题：
 * **两种输入必须指向同一份状态**。
 *
 * 若各自维护一份（点选改 `selected`，滑动改 pager 的 `currentPage`），
 * 就会出现"滑到第 2 枚、点一下第 1 枚，高亮和实际投出的数量不一致" ——
 * 而投币是**不可撤销**的消耗品，这种不一致的代价是真实的硬币。
 *
 * 所以：页码是唯一真相，`selected` 由页码派生。本对象就是这个派生的
 * 全部逻辑，且与 UI 解耦，可以被单测钉死。
 *
 * ## 为什么只有 1 和 2
 *
 * B 站一个视频最多投 2 枚（官方上限）。第 3 枚服务端会返回
 * `-403 超过投币上限`，所以选项集合是固定的两项。
 */
object CoinChoice {

    /** 可选的投币数量（官方上限 2 枚）。 */
    val COUNTS = listOf(1, 2)

    /** 默认选中 2 枚（与官方一致，也是原有行为）。 */
    const val DEFAULT_COUNT = 2

    /** 总数。 */
    val size: Int get() = COUNTS.size

    /**
     * 默认页码（指向 [DEFAULT_COUNT]）。
     *
     * ⚠️ 用 `indexOf` 而不是写死 `1` —— 选项顺序将来若调整，
     * 写死会静默指向另一个数量。
     */
    val defaultPage: Int get() = COUNTS.indexOf(DEFAULT_COUNT).coerceAtLeast(0)

    /**
     * 页码 → 投币数量。
     *
     * 越界时**夹取**而不是抛异常：滑动手势在快速 fling 时可能短暂
     * 报告超出范围的页码，为此崩掉一个投币弹窗是不可接受的。
     */
    fun countAt(page: Int): Int = COUNTS[page.coerceIn(0, size - 1)]

    /**
     * 数量 → 页码（`null` = 该数量不是合法选项）。
     *
     * 返回 null 而不是 `0`：非法输入应当被调用方**显式处理**，
     * 悄悄落到第一项会让"点了 3 枚却投了 1 枚"这种错误无声发生。
     */
    fun pageOf(count: Int): Int? = COUNTS.indexOf(count).takeIf { it >= 0 }

    /**
     * 点击某个数量后的目标页码。
     *
     * 非法数量返回 `null`（调用方忽略这次点击），**不改变当前选择**。
     * 这与 `CoinSelectionTest` 里"拒绝非法投币数量"的既有约束一致。
     */
    fun pageAfterClick(count: Int): Int? = pageOf(count)
}
