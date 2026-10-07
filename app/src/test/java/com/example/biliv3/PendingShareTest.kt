package com.example.biliv3

import com.example.biliv3.data.PendingShare
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 分享 → 站内私信「自动发送」的一次性容器测试（v1.6.7）。
 *
 * ## 为什么这组测试重要
 *
 * 自动发送是**用户没有点发送按钮**就发生的写操作。它一旦重复，
 * 就是明确的**刷屏行为**（任务书禁止）。
 *
 * 重复的风险点有三个：
 * 1. 会话页 `load()` 会被多次调用（刷新、收到新消息）
 * 2. 用户切走再回来
 * 3. VM 被重建
 *
 * 这组测试把"取走即清空"的语义钉死 —— 它是防重复的**唯一**机制。
 */
class PendingShareTest {

    // ---------------- 取走即清空（防重复的核心）----------------

    /**
     * 🔴 **回归测试**：`take()` 必须清空。
     *
     * 不清空的话，"切走再回来"或"刷新触发 load()"都会**再发一次**。
     */
    @Test
    fun `take 后为空`() {
        val ps = PendingShare()
        ps.post("标题", "https://b.com/video/BV1")

        assertThat(ps.take()).isNotNull()
        // 第二次取必须是 null —— 这是防重复的关键
        assertThat(ps.take()).isNull()
    }

    /** 连续 take 只会成功一次。 */
    @Test
    fun `只能取走一次`() {
        val ps = PendingShare()
        ps.post("t", "u")
        var count = 0
        repeat(5) { if (ps.take() != null) count++ }
        assertThat(count).isEqualTo(1)
    }

    /** 空容器 take 返回 null（不抛异常）。 */
    @Test
    fun `空容器 take 为 null`() {
        assertThat(PendingShare().take()).isNull()
    }

    /** `clear()` 之后 take 为 null（用户取消自动发送）。 */
    @Test
    fun `clear 后取不到`() {
        val ps = PendingShare()
        ps.post("t", "u")
        ps.clear()
        assertThat(ps.take()).isNull()
    }

    // ---------------- 挂载 ----------------

    /** 正常挂载。 */
    @Test
    fun `挂载后可读`() {
        val ps = PendingShare()
        ps.post("标题", "https://b.com/video/BV1")
        val p = ps.pending.value
        assertThat(p).isNotNull()
        assertThat(p!!.title).isEqualTo("标题")
        assertThat(p.url).isEqualTo("https://b.com/video/BV1")
    }

    /**
     * 🔴 URL 为空时**不挂载**。
     *
     * 分享的核心是链接；没有链接的消息发出去是垃圾
     * （而且用户以为发了东西）。
     */
    @Test
    fun `URL 为空不挂载`() {
        val ps = PendingShare()
        ps.post("标题", "")
        assertThat(ps.pending.value).isNull()

        ps.post("标题", "   ")
        assertThat(ps.pending.value).isNull()
    }

    /** 标题可以为空（只发链接）。 */
    @Test
    fun `标题可为空`() {
        val ps = PendingShare()
        ps.post("", "https://b.com/video/BV1")
        val p = ps.pending.value
        assertThat(p).isNotNull()
        assertThat(p!!.title).isEmpty()
    }

    /** 首尾空白被去掉（避免发出 `【 标题 】` 这种多余空格）。 */
    @Test
    fun `首尾空白被裁剪`() {
        val ps = PendingShare()
        ps.post("  标题  ", "  https://b.com/video/BV1  ")
        val p = ps.pending.value!!
        assertThat(p.title).isEqualTo("标题")
        assertThat(p.url).isEqualTo("https://b.com/video/BV1")
    }

    /** 后一次挂载覆盖前一次（用户改了分享对象不该发两条）。 */
    @Test
    fun `后挂载覆盖前挂载`() {
        val ps = PendingShare()
        ps.post("旧", "https://b.com/video/OLD")
        ps.post("新", "https://b.com/video/NEW")
        val p = ps.take()!!
        assertThat(p.title).isEqualTo("新")
        assertThat(p.url).isEqualTo("https://b.com/video/NEW")
    }

    // ---------------- 正文组装 ----------------

    /** 有标题时：`【标题】\n链接`。 */
    @Test
    fun `正文含标题与链接`() {
        val p = PendingShare.Pending(title = "某个视频", url = "https://b.com/video/BV1")
        assertThat(p.body).isEqualTo("【某个视频】\nhttps://b.com/video/BV1")
    }

    /**
     * 无标题时**只发链接** —— 不能留下一个空的 `【】`。
     *
     * 空标题在 UI 上表现为一个孤零零的 `【】`，看起来像 bug。
     */
    @Test
    fun `无标题时只发链接`() {
        val p = PendingShare.Pending(title = "", url = "https://b.com/video/BV1")
        assertThat(p.body).isEqualTo("https://b.com/video/BV1")
        assertThat(p.body).doesNotContain("【")
    }

    /** 标题里的换行不应破坏格式（模型/接口可能给多行标题）。 */
    @Test
    fun `标题含换行仍可发送`() {
        val p = PendingShare.Pending(title = "第一行\n第二行", url = "u")
        // 不崩、不丢内容即可（格式上多一个换行是可接受的）
        assertThat(p.body).contains("第一行")
        assertThat(p.body).contains("第二行")
        assertThat(p.body).contains("u")
    }

    /** 长标题不被截断（私信正文有服务端上限，客户端不擅自裁剪）。 */
    @Test
    fun `长标题不截断`() {
        val long = "很长的标题".repeat(50)
        val p = PendingShare.Pending(title = long, url = "u")
        assertThat(p.body).contains(long)
    }

    // ---------------- 幂等 / 边界 ----------------

    /** `clear()` 在空容器上是安全的（不抛）。 */
    @Test
    fun `空容器 clear 安全`() {
        val ps = PendingShare()
        ps.clear()
        ps.clear()
        assertThat(ps.pending.value).isNull()
    }

    /**
     * take 之后可以再 post（同一会话再分享一次视频）。
     *
     * ⚠️ 这**不是**"重复发送"—— 是用户又主动分享了一次，
     * 语义上应该发第二条。
     */
    @Test
    fun `取走后可再次挂载`() {
        val ps = PendingShare()
        ps.post("第一次", "u1")
        assertThat(ps.take()!!.title).isEqualTo("第一次")

        ps.post("第二次", "u2")
        assertThat(ps.take()!!.title).isEqualTo("第二次")
    }
}
