package com.example.biliv3.ui.library

/**
 * 多选状态（批量整理用）。
 *
 * ## 为什么把选择逻辑抽成独立类
 *
 * 任务书 §4.1 要求 6 种选择操作：多选 / 全选 / 取消全选 / 反选 /
 * 当前页全选 / 已选数量。
 *
 * 这些操作**全是指标运算**，塞进 Composable 会：
 * 1. 无法单测（"反选"的边界最容易写错）
 * 2. 与分页逻辑纠缠（"当前页全选"要知道哪些是当前页）
 *
 * 抽出来后是纯 Kotlin，可单测。
 *
 * ## 「当前页全选」为什么需要单独处理
 *
 * 收藏夹是**分页加载**的。用户看到 20 条点了"全选"，
 * 直觉是"选中我看到的这些"，而不是"选中服务器上全部 500 条"。
 * 后者会触发 500 个删除请求 —— 灾难性的误操作。
 *
 * 所以本类**只管理已加载的项**，`selectAll` 也只选已加载的。
 * 这是**有意的安全约束**，不是功能缺失。
 *
 * @param allKeys 当前已加载的全部 key（顺序即列表顺序）
 */
class BatchSelection(private val allKeys: List<String> = emptyList()) {

    private val selected = LinkedHashSet<String>()

    /** 已选数量。 */
    val count: Int get() = selected.size

    val isEmpty: Boolean get() = selected.isEmpty()
    val isNotEmpty: Boolean get() = selected.isNotEmpty()

    /** 是否已选中某条。 */
    fun contains(key: String): Boolean = key in selected

    /** 已选的全部 key（顺序 = 选择顺序）。 */
    fun keys(): List<String> = selected.toList()

    /** 切换某条。 */
    fun toggle(key: String) {
        if (!selected.remove(key)) selected.add(key)
    }

    /** 选中某条。 */
    fun select(key: String) {
        selected.add(key)
    }

    /** 取消某条。 */
    fun deselect(key: String) {
        selected.remove(key)
    }

    /**
     * 全选（**仅已加载项**）。
     *
     * 见类注释：不选未加载的项，避免误删服务器上没看到的内容。
     */
    fun selectAll() {
        selected.addAll(allKeys)
    }

    /** 取消全选。 */
    fun clear() {
        selected.clear()
    }

    /**
     * 反选（**仅已加载项**）。
     *
     * 语义：已加载项里，选中的变未选、未选的变选中。
     * 未加载的项**不受影响**（它们本来就没被选中）。
     */
    fun invert() {
        val next = LinkedHashSet<String>()
        for (k in allKeys) {
            if (k !in selected) next.add(k)
        }
        // 保留"选中了但不在 allKeys 里"的项？
        // ⚠️ 不保留 —— 那说明列表已经变了（切了收藏夹/刷新），
        // 留着会导致"操作了一批看不到的视频"。这是**安全选择**。
        selected.clear()
        selected.addAll(next)
    }

    /**
     * 当前页全选。
     *
     * @param pageKeys 当前页的 key（**必须由调用方传入** ——
     *        本类不知道分页信息，那是 Repository/ViewModel 的职责）
     */
    fun selectPage(pageKeys: List<String>) {
        selected.addAll(pageKeys)
    }

    /** 当前页取消全选。 */
    fun deselectPage(pageKeys: List<String>) {
        selected.removeAll(pageKeys.toSet())
    }

    /**
     * 当前页是否**全部**已选（决定"当前页全选"按钮显示"全选"还是"取消"）。
     *
     * 空页返回 false（没有"全选一个空页"这种语义）。
     */
    fun isPageFullySelected(pageKeys: List<String>): Boolean =
        pageKeys.isNotEmpty() && pageKeys.all { it in selected }

    /**
     * 是否全部已选（决定"全选"按钮的显示文案）。
     *
     * 注意：`allKeys` 为空时返回 false。
     */
    fun isAllSelected(): Boolean =
        allKeys.isNotEmpty() && allKeys.all { it in selected }

    /**
     * 列表数据变化后同步（如刷新 / 翻页加载了更多）。
     *
     * ⚠️ 必须调用：否则 `selectAll` / `invert` 会基于**过期的** allKeys 计算，
     * 表现为"全选后新加载的那页没被选上"。
     */
    fun updateKeys(keys: List<String>): BatchSelection = BatchSelection(keys).also {
        // 保留仍然存在的选中项（移除已消失的）
        val alive = keys.toHashSet()
        it.selected.addAll(selected.filter { k -> k in alive })
    }
}
