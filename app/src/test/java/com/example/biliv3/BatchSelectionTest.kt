package com.example.biliv3

import com.example.biliv3.ui.library.BatchSelection
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 多选状态单测。
 *
 * 「反选」与「当前页全选」是最容易写错的两个操作 ——
 * 错了不会崩，只会**批量操作了用户没想操作的视频**（可能误删）。
 * 所以必须钉死。
 */
class BatchSelectionTest {

    private fun sel(vararg keys: String) = BatchSelection(keys.toList())

    // ---------------- 基础 ----------------

    @Test
    fun `初始状态没有任何选中`() {
        val s = sel("a", "b", "c")
        assertThat(s.count).isEqualTo(0)
        assertThat(s.isEmpty).isTrue()
    }

    @Test
    fun `切换单条选中与取消`() {
        val s = sel("a", "b")
        s.toggle("a")
        assertThat(s.contains("a")).isTrue()
        assertThat(s.count).isEqualTo(1)
        s.toggle("a")
        assertThat(s.contains("a")).isFalse()
        assertThat(s.count).isEqualTo(0)
    }

    @Test
    fun `重复切换不会产生重复项`() {
        val s = sel("a")
        s.toggle("a")
        s.toggle("a")
        s.toggle("a")
        assertThat(s.count).isEqualTo(1)
        assertThat(s.keys()).containsExactly("a")
    }

    // ---------------- 全选 / 取消 ----------------

    @Test
    fun `全选只选已加载项`() {
        // 关键安全约束：不能选服务器上没看到的内容
        val s = sel("a", "b", "c")
        s.selectAll()
        assertThat(s.count).isEqualTo(3)
        assertThat(s.isAllSelected()).isTrue()
    }

    @Test
    fun `空列表的全选不产生选中`() {
        val s = sel()
        s.selectAll()
        assertThat(s.count).isEqualTo(0)
        // 空列表不该被认为"已全选"
        assertThat(s.isAllSelected()).isFalse()
    }

    @Test
    fun `取消全选清空一切`() {
        val s = sel("a", "b")
        s.selectAll()
        s.clear()
        assertThat(s.count).isEqualTo(0)
        assertThat(s.isAllSelected()).isFalse()
    }

    @Test
    fun `部分选中时 isAllSelected 为 false`() {
        val s = sel("a", "b", "c")
        s.toggle("a")
        assertThat(s.isAllSelected()).isFalse()
    }

    // ---------------- 反选（最容易错） ----------------

    @Test
    fun `反选把选中与未选对调`() {
        val s = sel("a", "b", "c")
        s.toggle("a")
        s.invert()
        assertThat(s.keys()).containsExactly("b", "c")
    }

    @Test
    fun `全选后反选得到空`() {
        val s = sel("a", "b", "c")
        s.selectAll()
        s.invert()
        assertThat(s.count).isEqualTo(0)
    }

    @Test
    fun `空选后反选得到全部`() {
        val s = sel("a", "b", "c")
        s.invert()
        assertThat(s.keys()).containsExactly("a", "b", "c")
    }

    @Test
    fun `反选两次回到原状`() {
        val s = sel("a", "b", "c")
        s.toggle("b")
        s.invert()
        s.invert()
        assertThat(s.keys()).containsExactly("b")
    }

    @Test
    fun `反选会丢弃已不在列表中的选中项`() {
        // 安全约束：列表变了（切收藏夹/刷新）后，
        // 不能保留"选中了但看不见"的项 —— 否则会批量操作看不到的视频
        val s = sel("a", "b")
        s.toggle("a")
        // 列表变成只有 b、c（a 消失）
        val next = s.updateKeys(listOf("b", "c"))
        assertThat(next.contains("a")).isFalse()
        next.invert()
        // b 未被选中 → 反选后选中；c 同理
        assertThat(next.keys()).containsExactly("b", "c")
    }

    // ---------------- 当前页 ----------------

    @Test
    fun `当前页全选只影响该页`() {
        val s = sel("a", "b", "c", "d")
        s.selectPage(listOf("a", "b"))
        assertThat(s.keys()).containsExactly("a", "b")
        assertThat(s.count).isEqualTo(2)
    }

    @Test
    fun `当前页全选与其它页选择叠加`() {
        val s = sel("a", "b", "c", "d")
        s.selectPage(listOf("a", "b"))
        s.selectPage(listOf("c", "d"))
        assertThat(s.count).isEqualTo(4)
        assertThat(s.isAllSelected()).isTrue()
    }

    @Test
    fun `当前页取消只移除该页`() {
        val s = sel("a", "b", "c", "d")
        s.selectAll()
        s.deselectPage(listOf("a", "b"))
        assertThat(s.keys()).containsExactly("c", "d")
    }

    @Test
    fun `判断当前页是否已全选`() {
        val s = sel("a", "b", "c")
        s.selectPage(listOf("a", "b"))
        assertThat(s.isPageFullySelected(listOf("a", "b"))).isTrue()
        assertThat(s.isPageFullySelected(listOf("a", "b", "c"))).isFalse()
    }

    @Test
    fun `空页不算已全选`() {
        val s = sel("a")
        // "全选一个空页"没有语义，返回 false 让按钮显示"全选"
        assertThat(s.isPageFullySelected(emptyList())).isFalse()
    }

    // ---------------- updateKeys ----------------

    @Test
    fun `updateKeys 保留仍然存在的选中项`() {
        val s = sel("a", "b", "c")
        s.toggle("a")
        s.toggle("b")
        val next = s.updateKeys(listOf("a", "b", "c", "d"))
        assertThat(next.keys()).containsExactly("a", "b")
    }

    @Test
    fun `updateKeys 移除已消失的选中项`() {
        val s = sel("a", "b")
        s.toggle("a")
        val next = s.updateKeys(listOf("b"))
        assertThat(next.contains("a")).isFalse()
        assertThat(next.count).isEqualTo(0)
    }

    @Test
    fun `updateKeys 后可全选新列表`() {
        val s = sel("a")
        s.selectAll()
        // 加载了第二页
        val next = s.updateKeys(listOf("a", "b"))
        assertThat(next.isAllSelected()).isFalse()   // b 还没选
        next.selectAll()
        assertThat(next.isAllSelected()).isTrue()
    }

    // ---------------- 顺序 ----------------

    @Test
    fun `选中顺序被保留`() {
        // UI 展示"已选 N 个"时按选择顺序更符合直觉
        val s = sel("a", "b", "c")
        s.toggle("c")
        s.toggle("a")
        assertThat(s.keys()).containsExactly("c", "a").inOrder()
    }
}
