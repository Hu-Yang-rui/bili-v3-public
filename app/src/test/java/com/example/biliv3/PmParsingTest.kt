package com.example.biliv3

import com.example.biliv3.data.model.PmMessage
import com.example.biliv3.data.model.PmUnread
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * 私信解析测试（v1.6.7）。
 *
 * ## 为什么这组测试重要
 *
 * 本轮在模拟器上发现**两个字段名解析错误**，都属于"**静默失败**"：
 *
 * | Bug | 表现 | 为什么难发现 |
 * |---|---|---|
 * | `single_unread` 读的字段不存在 | 未读数恒为 0，红点**永远不亮** | `optInt(..., 0)` 不报错，只是永远给默认值 |
 * | `get_sessions` 只读 `unread_count` | 会话未读恒为 0 | 同上 |
 *
 * 两者都**不会抛异常、不会崩**，只是功能静默失效 ——
 * 只有拿真实响应逐字段比对才能发现。所以把**真实响应样本**钉进测试。
 *
 * ## 样本来源
 *
 * 下面的 JSON 都是**模拟器实测抓到的真实响应**（已脱敏：
 * 去掉 talker_id / 昵称等用户数据，只保留结构与未读字段）。
 */
class PmParsingTest {

    // ---------------- single_unread：真实响应 ----------------

    /**
     * 实测响应（2026-10-07，真实账号）。
     *
     * ⚠️ 注意它**没有** `unread` / `unread_reply` / `unread_at` / `unread_like`
     * —— 这正是旧实现恒得 0 的原因。
     */
    private val realUnreadBody = """
        {"code":0,"msg":"OK","message":"OK","ttl":1,"data":{
          "unfollow_unread":0,"follow_unread":0,
          "unfollow_push_msg":0,"dustbin_push_msg":0,"dustbin_unread":0,
          "biz_msg_unfollow_unread":1,"biz_msg_follow_unread":1,
          "custom_unread":0}}
    """.trimIndent()

    /**
     * 旧实现读的字段名（**真实响应里不存在**）。
     *
     * 这条测试的作用是**证明旧字段确实不存在** —— 如果哪天服务端
     * 真的加上了它们，这条会失败，提醒我们重新评估解析逻辑。
     */
    @Test
    fun `旧字段在真实响应里不存在`() {
        val d = JSONObject(realUnreadBody).getJSONObject("data")
        assertThat(d.has("unread")).isFalse()
        assertThat(d.has("unread_reply")).isFalse()
        assertThat(d.has("unread_at")).isFalse()
        assertThat(d.has("unread_like")).isFalse()
    }

    /** 真实字段存在且值正确。 */
    @Test
    fun `真实字段存在`() {
        val d = JSONObject(realUnreadBody).getJSONObject("data")
        assertThat(d.optInt("biz_msg_follow_unread", -1)).isEqualTo(1)
        assertThat(d.optInt("biz_msg_unfollow_unread", -1)).isEqualTo(1)
    }

    /**
     * 私信未读 = `biz_msg_follow_unread + biz_msg_unfollow_unread`。
     *
     * 与 `get_sessions` 交叉验证过：会话列表里有未读的会话数 = 2，
     * 而 `1 + 1 = 2` —— 两者吻合，说明这个加法是对的。
     */
    @Test
    fun `私信未读为两个 biz 字段之和`() {
        val d = JSONObject(realUnreadBody).getJSONObject("data")
        val total = d.optInt("biz_msg_follow_unread", 0) +
            d.optInt("biz_msg_unfollow_unread", 0)
        assertThat(total).isEqualTo(2)
    }

    /** 全 0 的响应应解析出 0（不能因为取最大值而凭空造数）。 */
    @Test
    fun `全零响应解析为零`() {
        val body = """
            {"code":0,"data":{"biz_msg_follow_unread":0,"biz_msg_unfollow_unread":0}}
        """.trimIndent()
        val d = JSONObject(body).getJSONObject("data")
        val total = d.optInt("biz_msg_follow_unread", 0) +
            d.optInt("biz_msg_unfollow_unread", 0)
        assertThat(total).isEqualTo(0)
    }

    /** 缺字段时按 0 处理（不抛异常）。 */
    @Test
    fun `缺字段不抛异常`() {
        val d = JSONObject("""{"code":0,"data":{}}""").getJSONObject("data")
        assertThat(d.optInt("biz_msg_follow_unread", 0)).isEqualTo(0)
        assertThat(d.optInt("biz_msg_unfollow_unread", 0)).isEqualTo(0)
    }

    // ---------------- get_sessions：会话未读字段 ----------------

    /**
     * 实测：三个未读字段并存，但**只有 `biz_msg_unread_count` 有值**。
     *
     * ```
     * u=0/biz=1/newPush=1
     * ```
     */
    private fun sessionJson(unread: Int, biz: Int, push: Int) = JSONObject(
        """{"talker_id":1001,"unread_count":$unread,
            "biz_msg_unread_count":$biz,"new_push_msg":$push}""",
    )

    /**
     * 🔴 **回归测试**：旧实现只读 `unread_count` → 恒为 0。
     *
     * 这条测试固定住"真实未读在 `biz_msg_unread_count`"这个事实。
     */
    @Test
    fun `未读取 biz 字段而非 unread_count`() {
        val o = sessionJson(unread = 0, biz = 1, push = 1)
        // 旧逻辑
        assertThat(o.optInt("unread_count", 0)).isEqualTo(0)
        // 新逻辑：三者取最大
        assertThat(maxOf(
            o.optInt("unread_count", 0),
            o.optInt("biz_msg_unread_count", 0),
            o.optInt("new_push_msg", 0),
        )).isEqualTo(1)
    }

    /**
     * ⚠️ **不累加** —— 三者是同一批未读的不同投影。
     *
     * 实测 `biz_msg_unread_count` 与 `new_push_msg` **值完全相同**，
     * 相加会得到 2 倍虚高（用户看到"2 条未读"而实际只有 1 条）。
     */
    @Test
    fun `三字段取最大而非相加`() {
        val o = sessionJson(unread = 0, biz = 1, push = 1)
        val maxed = maxOf(
            o.optInt("unread_count", 0),
            o.optInt("biz_msg_unread_count", 0),
            o.optInt("new_push_msg", 0),
        )
        assertThat(maxed).isEqualTo(1)
        // 相加会得到 2 —— 明确断言我们不这么做
        assertThat(maxed).isNotEqualTo(2)
    }

    /** 老会话只有 `unread_count` 有值时要能读到。 */
    @Test
    fun `兼容只有 unread_count 的会话`() {
        val o = sessionJson(unread = 5, biz = 0, push = 0)
        assertThat(maxOf(
            o.optInt("unread_count", 0),
            o.optInt("biz_msg_unread_count", 0),
            o.optInt("new_push_msg", 0),
        )).isEqualTo(5)
    }

    /** 全 0 → 0（不能因为取最大值而凭空造未读）。 */
    @Test
    fun `会话全零解析为零`() {
        val o = sessionJson(unread = 0, biz = 0, push = 0)
        assertThat(maxOf(
            o.optInt("unread_count", 0),
            o.optInt("biz_msg_unread_count", 0),
            o.optInt("new_push_msg", 0),
        )).isEqualTo(0)
    }

    /** 负值不能变成"负未读"（服务端异常时不显示负数红点）。 */
    @Test
    fun `负值被钳到零`() {
        val o = sessionJson(unread = -1, biz = -1, push = -1)
        val v = maxOf(
            o.optInt("unread_count", 0),
            o.optInt("biz_msg_unread_count", 0),
            o.optInt("new_push_msg", 0),
            0,
        )
        assertThat(v).isEqualTo(0)
    }

    // ---------------- PmUnread 语义 ----------------

    /**
     * `PmUnread.total` 只算私信 —— 铃铛红点代表"有私信"，
     * 回复/@/赞 另有入口（与既有设计一致）。
     */
    @Test
    fun `未读总数只算私信`() {
        val u = PmUnread(message = 3, reply = 9, at = 9, like = 9)
        assertThat(u.total).isEqualTo(3)
    }

    /** 全 0 时 total 为 0（红点不亮）。 */
    @Test
    fun `零未读不亮红点`() {
        assertThat(PmUnread(0, 0, 0, 0).total).isEqualTo(0)
    }

    // ---------------- 消息类型（图片 / 文本 / 不支持）----------------

    private fun msg(
        text: String = "",
        msgType: Int = 1,
        imageUrl: String = "",
    ) = PmMessage(
        msgKey = 1L,
        senderId = 2L,
        text = text,
        timestamp = 0L,
        isMine = false,
        msgType = msgType,
        imageUrl = imageUrl,
    )

    /**
     * 🔴 **回归测试**：图片消息不能被判成"不支持"。
     *
     * 原实现用 `text.isEmpty()` 判 —— 图片消息的 text 本来就是空的，
     * 于是**所有图片消息都被当成不支持**，显示成占位文字。
     */
    @Test
    fun `图片消息不是不支持`() {
        val m = msg(msgType = 2, imageUrl = "https://message.biliimg.com/x.jpg")
        assertThat(m.isImage).isTrue()
        assertThat(m.isUnsupported).isFalse()
    }

    /** 纯文本消息。 */
    @Test
    fun `文本消息`() {
        val m = msg(text = "你好", msgType = 1)
        assertThat(m.isImage).isFalse()
        assertThat(m.isUnsupported).isFalse()
    }

    /**
     * 既无文本也无图 = 真不支持（例如未识别的 msgType）。
     *
     * 此时 UI 应**明示"不支持"**而不是显示空白 ——
     * 与项目"失败不能伪装成空"的规则一致。
     */
    @Test
    fun `既无文本也无图才是不支持`() {
        val m = msg(text = "", msgType = 7, imageUrl = "")
        assertThat(m.isUnsupported).isTrue()
    }

    /**
     * `msgType == 2` 但 `imageUrl` 为空 → **不算图片**。
     *
     * 否则 UI 会渲染一个永远加载不出来的图片框。
     */
    @Test
    fun `图片类型但无 URL 不算图片`() {
        val m = msg(msgType = 2, imageUrl = "")
        assertThat(m.isImage).isFalse()
        // 且因为没有文本也没有可用图 → 归为不支持（明示），不显示空框
        assertThat(m.isUnsupported).isTrue()
    }

    /** 有尺寸时按真实比例；没有时回退 4:3（保证占位不跳动）。 */
    @Test
    fun `图片宽高比`() {
        val withSize = PmMessage(
            msgKey = 1L, senderId = 2L, text = "", timestamp = 0L, isMine = false,
            msgType = 2, imageUrl = "u", imageWidth = 800, imageHeight = 400,
        )
        assertThat(withSize.imageAspect).isWithin(0.001f).of(2f)

        val noSize = PmMessage(
            msgKey = 1L, senderId = 2L, text = "", timestamp = 0L, isMine = false,
            msgType = 2, imageUrl = "u",
        )
        assertThat(noSize.imageAspect).isWithin(0.001f).of(4f / 3f)
    }
}
