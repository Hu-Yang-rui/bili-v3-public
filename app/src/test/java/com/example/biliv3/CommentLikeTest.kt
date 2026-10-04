package com.example.biliv3

import com.example.biliv3.data.model.CommentItem
import com.example.biliv3.ui.video.updateCommentInTree
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 评论树更新的回归测试。
 *
 * ## 为什么需要这组测试
 *
 * v1.4.2 修了一个**用户能看见、但单测缺失**的 bug：
 * 「展开评论里给回复点赞，结果给主评论点了赞」。
 *
 * 根因有两层，两层都是"数据流错位"而不是"UI 画错"：
 *
 * 1. **UI 层**：`CommentRow` 的回调曾是零参 `() -> Unit`，
 *    父级 `items { c -> }` 已经把它绑成 `{ onLike(c) }`；
 *    渲染内嵌回复时直接下传，回复行调用的仍是**父评论**的 rpid。
 * 2. **ViewModel 层**：乐观更新只 `map` 了顶层 `_comments`，
 *    而回复存放在 `CommentItem.replies` 里 —— 找不到匹配就静默无操作，
 *    API 实际成功但 UI 永远不变。
 *
 * 这里覆盖第 2 层（纯逻辑，可单测）。第 1 层由类型系统保证：
 * 回调改成 `(CommentItem) -> Unit` 后，"传错对象"不再能通过编译。
 */
class CommentLikeTest {

    private fun comment(
        rpid: Long,
        liked: Boolean = false,
        likeCount: Int = 0,
        replies: List<CommentItem> = emptyList(),
    ) = CommentItem(
        rpid = rpid,
        oid = 100L,
        mid = 1L,
        userName = "u$rpid",
        userFace = "",
        content = "c$rpid",
        likeCount = likeCount,
        replyCount = replies.size,
        ctime = 0L,
        floor = 0,
        isUp = false,
        liked = liked,
        replies = replies,
    )

    @Test
    fun `顶层评论点赞只改自己`() {
        val tree = listOf(comment(1), comment(2))
        val out = updateCommentInTree(tree, 1L) { it.copy(liked = true, likeCount = 1) }

        assertThat(out[0].liked).isTrue()
        assertThat(out[0].likeCount).isEqualTo(1)
        // 兄弟评论不受影响
        assertThat(out[1].liked).isFalse()
        assertThat(out[1].likeCount).isEqualTo(0)
    }

    @Test
    fun `回复点赞改的是回复而不是父评论`() {
        // 主评论 1，下面挂着回复 11 / 12
        val tree = listOf(
            comment(1, replies = listOf(comment(11), comment(12))),
        )

        val out = updateCommentInTree(tree, 11L) { it.copy(liked = true, likeCount = 5) }

        // 目标回复被改
        assertThat(out[0].replies[0].rpid).isEqualTo(11L)
        assertThat(out[0].replies[0].liked).isTrue()
        assertThat(out[0].replies[0].likeCount).isEqualTo(5)
        // ⚠️ 父评论必须**不受影响** —— 这正是原 bug 的表现
        assertThat(out[0].liked).isFalse()
        assertThat(out[0].likeCount).isEqualTo(0)
        // 另一条回复也不受影响
        assertThat(out[0].replies[1].liked).isFalse()
    }

    @Test
    fun `回复点赞不影响其它楼层的同序号回复`() {
        // 两栋楼各有回复，rpid 不同
        val tree = listOf(
            comment(1, replies = listOf(comment(11))),
            comment(2, replies = listOf(comment(21))),
        )
        val out = updateCommentInTree(tree, 21L) { it.copy(liked = true) }

        assertThat(out[0].replies[0].liked).isFalse()
        assertThat(out[1].replies[0].liked).isTrue()
    }

    @Test
    fun `rpid 不存在时原样返回同一个引用`() {
        val tree = listOf(comment(1, replies = listOf(comment(11))))
        val out = updateCommentInTree(tree, 999L) { it.copy(liked = true) }

        // 返回同一引用 → Compose 不会因为"内容相同的新列表"而多余重组
        assertThat(out).isSameInstanceAs(tree)
    }

    @Test
    fun `取消点赞把计数减回去`() {
        val tree = listOf(
            comment(1, liked = true, likeCount = 3, replies = listOf(comment(11, liked = true, likeCount = 1))),
        )
        val out = updateCommentInTree(tree, 11L) { it.copy(liked = false, likeCount = 0) }

        assertThat(out[0].replies[0].liked).isFalse()
        assertThat(out[0].replies[0].likeCount).isEqualTo(0)
        // 父评论保持自己的已赞状态
        assertThat(out[0].liked).isTrue()
        assertThat(out[0].likeCount).isEqualTo(3)
    }
}
