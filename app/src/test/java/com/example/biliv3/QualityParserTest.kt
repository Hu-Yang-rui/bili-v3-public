package com.example.biliv3

import com.example.biliv3.data.model.QualityAvailability
import com.example.biliv3.data.quality.QualityParser
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * 清晰度档位解析测试（**未发版**）。
 *
 * ## 样本来源
 *
 * 下面的 JSON 是**模拟器实测抓到的真实响应**（真实登录账号、非大会员、
 * 真实视频），不是构造的理想数据。
 *
 * 实测关键事实：
 *
 * | qn | 名称 | limit_watch_reason | dash.video 里有吗 |
 * |---|---|---|---|
 * | 120 | 4K 超高清 | **1** | ❌ |
 * | 112 | 1080P 高码率 | **1** | ❌ |
 * | 80 | 1080P 高清 | 0 | ✅ |
 * | 64 / 32 / 16 | 720P / 480P / 360P | 0 | ✅ |
 *
 * 即 **`limit=1` 的档位确实拿不到流** —— 标记与事实一致。
 *
 * ## 为什么这组测试重要
 *
 * 旧实现直接渲染 `accept_quality`，会**显示 112（1080P 高码率）**，
 * 但 `dash.video` 只给到 80 —— 用户选中后实际拿到 1080P，
 * **UI 与实际不符**（需求第八条点名的失败情形）。
 */
class QualityParserTest {

    // ---------------- 真实响应样本 ----------------

    /**
     * **去掉 `fourk` 的实测**：没有 4K 档，112（1080P 高码率）受限。
     *
     * ⚠️ 这**不是** App 的请求参数 —— App 是 `fnval=16 + fourk=1`。
     * 保留它是因为实测确认了 `fourk` 的作用（去掉就没有 4K）。
     */
    private val noFourK = JSONObject(
        """
        {"quality":64,
         "accept_quality":[112,80,64,32,16],
         "accept_description":["高清 1080P+","高清 1080P","高清 720P","清晰 480P","流畅 360P"],
         "support_formats":[
           {"quality":112,"new_description":"1080P 高码率","superscript":"高码率",
            "can_watch_qn_reason":0,"limit_watch_reason":1},
           {"quality":80,"new_description":"1080P 高清","superscript":"",
            "can_watch_qn_reason":0,"limit_watch_reason":0},
           {"quality":64,"new_description":"720P 准高清","superscript":"",
            "can_watch_qn_reason":0,"limit_watch_reason":0},
           {"quality":32,"new_description":"480P 标清","superscript":"",
            "can_watch_qn_reason":0,"limit_watch_reason":0},
           {"quality":16,"new_description":"360P 流畅","superscript":"",
            "can_watch_qn_reason":0,"limit_watch_reason":0}],
         "dash":{"video":[
           {"id":80,"width":1920,"height":1080,"frameRate":30,"codecs":"avc1"},
           {"id":64,"width":1280,"height":720,"frameRate":30,"codecs":"avc1"},
           {"id":32,"width":852,"height":480,"frameRate":30,"codecs":"avc1"},
           {"id":16,"width":640,"height":360,"frameRate":30,"codecs":"avc1"}]}}
        """.trimIndent(),
    )

    /**
     * 🔴 **App 真实参数**（`fnval=16 + fnver=0 + fourk=1`）的实测响应。
     *
     * 与 `fnval=4048 + fourk=1` 的结果**完全一致**（实测对比过）。
     *
     * 关键：含 4K（120）与 1080P60（116），两者 `limit_watch_reason=1`。
     */
    private val appParams = JSONObject(
        """
        {"quality":64,
         "accept_quality":[120,116,80,64,32,16],
         "accept_description":["超清 4K","高清 1080P60","高清 1080P","高清 720P","清晰 480P","流畅 360P"],
         "support_formats":[
           {"quality":120,"new_description":"4K 超高清","superscript":"",
            "can_watch_qn_reason":0,"limit_watch_reason":1},
           {"quality":116,"new_description":"1080P 60帧","superscript":"60帧",
            "can_watch_qn_reason":0,"limit_watch_reason":1},
           {"quality":80,"new_description":"1080P 高清","superscript":"",
            "can_watch_qn_reason":0,"limit_watch_reason":0},
           {"quality":64,"new_description":"720P 准高清","superscript":"",
            "can_watch_qn_reason":0,"limit_watch_reason":0},
           {"quality":32,"new_description":"480P 标清","superscript":"",
            "can_watch_qn_reason":0,"limit_watch_reason":0},
           {"quality":16,"new_description":"360P 流畅","superscript":"",
            "can_watch_qn_reason":0,"limit_watch_reason":0}],
         "dash":{"video":[
           {"id":80,"width":1920,"height":1080,"frameRate":30,"codecs":"avc1"},
           {"id":64,"width":1280,"height":720,"frameRate":30,"codecs":"avc1"},
           {"id":32,"width":852,"height":480,"frameRate":30,"codecs":"avc1"},
           {"id":16,"width":640,"height":360,"frameRate":30,"codecs":"avc1"}]}}
        """.trimIndent(),
    )

    private fun ok(v: QualityAvailability): List<com.example.biliv3.data.model.QualityOption> {
        assertThat(v).isInstanceOf(QualityAvailability.Ok::class.java)
        return (v as QualityAvailability.Ok).options
    }

    // ---------------- 受限档位：必须保留并标记 ----------------

    /**
     * 🔴 **回归测试**：受限档位必须**保留在列表里**且标 `limited`。
     *
     * 需求第三条：不要直接隐藏，保留选项并标记「需要大会员」。
     */
    @Test
    fun `受限档位被保留并标记`() {
        val opts = ok(QualityParser.parse(noFourK))
        val q112 = opts.firstOrNull { it.id == 112 }
        assertThat(q112).isNotNull()
        assertThat(q112!!.limited).isTrue()
        // 受限 = 不可播（实测确实拿不到流）
        assertThat(q112.playable).isFalse()
        // 但**可以点**（点了弹会员提示，而不是禁用）
        assertThat(q112.selectable).isTrue()
    }

    /** 4K 与 1080P60 在 **App 真实参数**下出现，且都被标记为受限。 */
    @Test
    fun `4K 与 1080P60 受限`() {
        val opts = ok(QualityParser.parse(appParams))
        val q4k = opts.firstOrNull { it.id == 120 }
        assertThat(q4k).isNotNull()
        assertThat(q4k!!.limited).isTrue()
        assertThat(q4k.selectable).isTrue()

        val q116 = opts.firstOrNull { it.id == 116 }
        assertThat(q116).isNotNull()
        assertThat(q116!!.limited).isTrue()
        assertThat(q116.selectable).isTrue()
    }

    /**
     * 🔴 **回归测试**：去掉 `fourk` 后**接口不返回 4K**，
     * 所以列表里不该凭空出现 4K。
     *
     * 需求第四条：接口没返回的档位**不要显示会员标记**。
     *
     * ⚠️ 这也证明了 `fourk=1` 的作用 —— 而 App **本来就带**它。
     */
    @Test
    fun `无 fourk 时不出现 4K`() {
        val opts = ok(QualityParser.parse(noFourK))
        assertThat(opts.map { it.id }).doesNotContain(120)
    }

    /** App 真实参数下**确实**能看到 4K（因为带了 `fourk=1`）。 */
    @Test
    fun `App 参数下可见 4K`() {
        val ids = ok(QualityParser.parse(appParams)).map { it.id }
        assertThat(ids).contains(120)
        assertThat(ids).contains(116)
    }

    // ---------------- 可用档位 ----------------

    /** 免费档位标 `playable`，且带真实分辨率。 */
    @Test
    fun `免费档位可播且有分辨率`() {
        val opts = ok(QualityParser.parse(noFourK))
        val q80 = opts.first { it.id == 80 }
        assertThat(q80.playable).isTrue()
        assertThat(q80.limited).isFalse()
        assertThat(q80.width).isEqualTo(1920)
        assertThat(q80.height).isEqualTo(1080)
    }

    /** 档位顺序保持服务端给的（高 → 低）。 */
    @Test
    fun `档位顺序为高到低`() {
        val ids = ok(QualityParser.parse(appParams)).map { it.id }
        assertThat(ids).isEqualTo(listOf(120, 116, 80, 64, 32, 16))
    }

    /** 名称优先用 `new_description`（比 `accept_description` 更准）。 */
    @Test
    fun `名称优先用 new_description`() {
        val opts = ok(QualityParser.parse(noFourK))
        assertThat(opts.first { it.id == 80 }.label).isEqualTo("1080P 高清")
        // accept_description 里叫"高清 1080P"，两者不同 —— 用前者
        assertThat(opts.first { it.id == 80 }.label).isNotEqualTo("高清 1080P")
    }

    /**
     * 角标被解析（"高码率"）。
     *
     * ⚠️ 实测 `new_description = "1080P 高码率"` 且 `superscript = "高码率"` ——
     * 名称**已含**角标，所以 `fullLabel` **不追加**（否则重复）。
     */
    @Test
    fun `解析角标`() {
        val q112 = ok(QualityParser.parse(noFourK)).first { it.id == 112 }
        assertThat(q112.superscript).isEqualTo("高码率")
        // 名称已含"高码率" → 不重复拼接
        assertThat(q112.fullLabel).isEqualTo("1080P 高码率")
    }

    /** 名称**不含**角标时才追加（如名称"4K" + 角标"超高清"）。 */
    @Test
    fun `名称不含角标时追加`() {
        val j = JSONObject(
            """{"accept_quality":[120],"accept_description":["超清 4K"],
                "support_formats":[{"quality":120,"new_description":"4K",
                  "superscript":"超高清","limit_watch_reason":1}],
                "dash":{"video":[{"id":80,"width":1920,"height":1080}]}}""",
        )
        val q120 = ok(QualityParser.parse(j)).first { it.id == 120 }
        assertThat(q120.fullLabel).isEqualTo("4K 超高清")
    }

    /**
     * 🔴 **回归测试**：名称里已含角标时**不能重复拼接**。
     *
     * 模拟器实测出现过 `1080P 60帧 60帧` —— 因为：
     * ```
     * new_description = "1080P 60帧"
     * superscript     = "60帧"      ← 已包含在名称里
     * ```
     * 直接拼接就重复了。
     */
    @Test
    fun `名称含角标时不重复拼接`() {
        val j = JSONObject(
            """{"accept_quality":[116],"accept_description":["高清 1080P60"],
                "support_formats":[{"quality":116,"new_description":"1080P 60帧",
                  "superscript":"60帧","limit_watch_reason":1}],
                "dash":{"video":[{"id":80,"width":1920,"height":1080}]}}""",
        )
        val opts = ok(QualityParser.parse(j))
        val q116 = opts.first { it.id == 116 }
        assertThat(q116.fullLabel).isEqualTo("1080P 60帧")
        // 不能出现两次
        assertThat(q116.fullLabel.count { it == '6' }).isEqualTo(1)
    }

    /** 无角标时 `fullLabel` 就是 label（不留多余空格）。 */
    @Test
    fun `无角标时 fullLabel 等于 label`() {
        val q80 = ok(QualityParser.parse(noFourK)).first { it.id == 80 }
        assertThat(q80.fullLabel).isEqualTo("1080P 高清")
        assertThat(q80.fullLabel).doesNotContain("  ")
    }

    // ---------------- 实际分辨率 / 帧率（需求第八条）----------------

    /**
     * 🔴 需求第八条：**UI 显示的清晰度必须与实际取流一致**。
     *
     * 所以档位要带**真实分辨率**，而不是按 id 猜。
     */
    @Test
    fun `档位带真实分辨率`() {
        val opts = ok(QualityParser.parse(noFourK))
        assertThat(opts.first { it.id == 80 }.height).isEqualTo(1080)
        assertThat(opts.first { it.id == 64 }.height).isEqualTo(720)
        assertThat(opts.first { it.id == 32 }.height).isEqualTo(480)
        assertThat(opts.first { it.id == 16 }.height).isEqualTo(360)
    }

    /** 4K 判定按**实际分辨率**，不按 id 猜。 */
    @Test
    fun `4K 判定用分辨率`() {
        val opts = ok(QualityParser.parse(noFourK))
        // 没有任何档是 4K（dash 最高 1080）
        assertThat(opts.none { it.is4K }).isTrue()
    }

    /** 高帧率判定按**实际帧率**（>30），不按 id 猜。 */
    @Test
    fun `高帧率判定用帧率`() {
        val with60 = JSONObject(
            """{"accept_quality":[80],"accept_description":["1080P"],
                "dash":{"video":[{"id":80,"width":1920,"height":1080,"frameRate":60}]}}""",
        )
        val opts = ok(QualityParser.parse(with60))
        assertThat(opts.first().isHighFrameRate).isTrue()
    }

    /** `frame_rate` 下划线写法也要能读（实测两种都有）。 */
    @Test
    fun `兼容下划线帧率字段`() {
        val j = JSONObject(
            """{"accept_quality":[80],"accept_description":["1080P"],
                "dash":{"video":[{"id":80,"width":1920,"height":1080,"frame_rate":60}]}}""",
        )
        assertThat(ok(QualityParser.parse(j)).first().frameRate).isEqualTo(60f)
    }

    /** 同一 qn 多条编码时取**分辨率最高**的那条。 */
    @Test
    fun `同档多编码取最高分辨率`() {
        val j = JSONObject(
            """{"accept_quality":[80],"accept_description":["1080P"],
                "dash":{"video":[
                  {"id":80,"width":640,"height":360,"codecs":"avc1"},
                  {"id":80,"width":1920,"height":1080,"codecs":"hevc"}]}}""",
        )
        assertThat(ok(QualityParser.parse(j)).first().height).isEqualTo(1080)
    }

    // ---------------- 状态区分（需求第四条：不能混成一个）----------------

    /** 请求失败 → `Failed`（不是"没有档位"）。 */
    @Test
    fun `请求失败返回 Failed`() {
        val v = QualityParser.parse(data = null, failure = "网络不可用")
        assertThat(v).isInstanceOf(QualityAvailability.Failed::class.java)
        assertThat((v as QualityAvailability.Failed).message).isEqualTo("网络不可用")
    }

    /** `data` 为 null 且无 failure → 也归 `Failed`（不能假装没档位）。 */
    @Test
    fun `data 缺失归为 Failed`() {
        assertThat(QualityParser.parse(null))
            .isInstanceOf(QualityAvailability.Failed::class.java)
    }

    /** 未登录且无档位 → `NotLoggedIn`（不是"不支持"）。 */
    @Test
    fun `未登录返回 NotLoggedIn`() {
        val v = QualityParser.parse(JSONObject("{}"), loggedIn = false)
        assertThat(v).isInstanceOf(QualityAvailability.NotLoggedIn::class.java)
    }

    /** 已登录但确实没档位 → `Unsupported`。 */
    @Test
    fun `已登录无档位为 Unsupported`() {
        val v = QualityParser.parse(JSONObject("{}"), loggedIn = true)
        assertThat(v).isInstanceOf(QualityAvailability.Unsupported::class.java)
    }

    /**
     * ⚠️ **未登录优先于"不支持"**：未登录时接口本来就少给档位，
     * 说成"视频不支持"是错的（需求第四条要求区分）。
     */
    @Test
    fun `未登录不误报为不支持`() {
        val v = QualityParser.parse(JSONObject("{}"), loggedIn = false)
        assertThat(v).isNotInstanceOf(QualityAvailability.Unsupported::class.java)
    }

    // ---------------- 容错 ----------------

    /** 缺 `support_formats` 时仍能列出可播档位（只是没有会员标记）。 */
    @Test
    fun `缺 support_formats 仍可用`() {
        val j = JSONObject(
            """{"accept_quality":[80,64],"accept_description":["1080P","720P"],
                "dash":{"video":[{"id":80,"width":1920,"height":1080}]}}""",
        )
        val opts = ok(QualityParser.parse(j))
        assertThat(opts.map { it.id }).containsExactly(80)
        assertThat(opts.first().limited).isFalse()
    }

    /**
     * 既不可播又无限制原因的档位**不显示** ——
     * 点了没反应的选项是噪音。
     */
    @Test
    fun `不可播且无原因的档位被过滤`() {
        val j = JSONObject(
            """{"accept_quality":[80,64],"accept_description":["1080P","720P"],
                "dash":{"video":[{"id":80,"width":1920,"height":1080}]}}""",
        )
        val ids = ok(QualityParser.parse(j)).map { it.id }
        assertThat(ids).containsExactly(80)
        assertThat(ids).doesNotContain(64)
    }

    /** 损坏输入不抛异常。 */
    @Test
    fun `损坏输入不抛`() {
        assertThat(QualityParser.parse(JSONObject("""{"accept_quality":"x"}""")))
            .isInstanceOf(QualityAvailability.Unsupported::class.java)
        assertThat(QualityParser.parse(JSONObject("""{"dash":null}""")))
            .isInstanceOf(QualityAvailability.Unsupported::class.java)
    }

    /** `accept_quality` 里的 0 / 负数被丢弃。 */
    @Test
    fun `非法档位 id 被丢弃`() {
        val j = JSONObject(
            """{"accept_quality":[0,-1,80],"accept_description":["","","1080P"],
                "dash":{"video":[{"id":80,"width":1920,"height":1080}]}}""",
        )
        assertThat(ok(QualityParser.parse(j)).map { it.id }).containsExactly(80)
    }

    /** 帧率缺失时按 0 处理，且**不误判为高帧率**。 */
    @Test
    fun `缺帧率不误判高帧率`() {
        val j = JSONObject(
            """{"accept_quality":[80],"accept_description":["1080P"],
                "dash":{"video":[{"id":80,"width":1920,"height":1080}]}}""",
        )
        val o = ok(QualityParser.parse(j)).first()
        assertThat(o.frameRate).isEqualTo(0f)
        assertThat(o.isHighFrameRate).isFalse()
    }
}
