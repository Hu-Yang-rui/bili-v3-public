package com.example.biliv3

import com.example.biliv3.data.ai.SummaryParser
import com.example.biliv3.data.ai.SummaryPrompt
import com.example.biliv3.data.ai.SummarySource
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * AI 总结的解析与 Prompt 构造测试。
 *
 * ## 为什么这组测试重要
 *
 * 两条来源的响应结构完全不同，而**第三方那侧是自由文本** ——
 * 模型不保证遵守我们在 Prompt 里要求的格式。解析写错的后果是：
 * 用户等了 30 秒，结果页面上是空白或半截内容。
 *
 * 所以这里钉死两条：
 * 1. 官方结构解析正确（含各种字段缺失的容错）
 * 2. **第三方文本解析不丢内容**（认不出结构时整段兜底进概述）
 */
class AiSummaryTest {

    // ---------------- 官方结构 ----------------

    /** 公开结构的正常形态。 */
    @Test
    fun `解析官方总结的完整结构`() {
        val json = JSONObject(
            """
            {"code":0,"data":{"code":0,"model_result":{
              "result_type":0,
              "summary":"这个视频讲了如何用 Kotlin 写一个播放器。",
              "outline":[
                {"title":"开场","timestamp":0,
                 "part_outline":[{"timestamp":0,"content":"介绍背景"}]},
                {"title":"核心实现","timestamp":120,
                 "part_outline":[
                   {"timestamp":120,"content":"Media3 的装配方式"},
                   {"timestamp":200,"content":"状态同步的坑"}]}
              ]}}}
            """.trimIndent(),
        )

        val s = SummaryParser.parseOfficial(json)
        assertThat(s).isNotNull()
        assertThat(s!!.source).isEqualTo(SummarySource.OFFICIAL)
        assertThat(s.overview).contains("Kotlin")
        assertThat(s.outline).hasSize(2)
        assertThat(s.outline[0].title).isEqualTo("开场")
        assertThat(s.outline[1].points).hasSize(2)
        assertThat(s.outline[1].timestampSeconds).isEqualTo(120)
        assertThat(s.outline[1].timeLabel).isEqualTo("02:00")
    }

    /** 没有 `model_result` → null（调用方据此降级到第三方）。 */
    @Test
    fun `缺少 model_result 返回 null`() {
        assertThat(SummaryParser.parseOfficial(JSONObject("""{"code":0,"data":{}}""")))
            .isNull()
    }

    /** 结构存在但内容全空 → null（不能返回一个空总结）。 */
    @Test
    fun `内容全空返回 null`() {
        val json = JSONObject(
            """{"code":0,"data":{"model_result":{"summary":"","outline":[]}}}""",
        )
        assertThat(SummaryParser.parseOfficial(json)).isNull()
    }

    @Test
    fun `null 输入返回 null`() {
        assertThat(SummaryParser.parseOfficial(null)).isNull()
    }

    /** 空的分段被丢弃（不产出空标题行）。 */
    @Test
    fun `空分段被丢弃`() {
        val json = JSONObject(
            """
            {"code":0,"data":{"model_result":{
              "summary":"有概述",
              "outline":[{"title":"","part_outline":[]},{"title":"有效","part_outline":[]}]
            }}}
            """.trimIndent(),
        )
        val s = SummaryParser.parseOfficial(json)!!
        assertThat(s.outline).hasSize(1)
        assertThat(s.outline[0].title).isEqualTo("有效")
    }

    // ---------------- 第三方：OpenAI 响应 ----------------

    @Test
    fun `提取 chat completions 的正文`() {
        val json = JSONObject(
            """{"choices":[{"message":{"role":"assistant","content":"总结内容"}}]}""",
        )
        assertThat(SummaryParser.extractContent(json)).isEqualTo("总结内容")
    }

    /** 兼容旧版 Completions 的 `text` 字段。 */
    @Test
    fun `兼容 text 字段`() {
        val json = JSONObject("""{"choices":[{"text":"老格式正文"}]}""")
        assertThat(SummaryParser.extractContent(json)).isEqualTo("老格式正文")
    }

    @Test
    fun `空 choices 返回 null`() {
        assertThat(SummaryParser.extractContent(JSONObject("""{"choices":[]}"""))).isNull()
        assertThat(SummaryParser.extractContent(JSONObject("""{}"""))).isNull()
        assertThat(SummaryParser.extractContent(null)).isNull()
    }

    // ---------------- 第三方：自由文本 → 结构 ----------------

    /** 模型严格按格式输出时，三段各就各位。 */
    @Test
    fun `解析规范格式的三段`() {
        val text = """
            【视频概述】
            这个视频介绍了 Kotlin 协程的基本用法。

            【核心内容】
            - suspend 函数的含义
            - 结构化并发的作用
            - 常见误用

            【简短总结】
            协程的核心价值是把异步写得像同步。
        """.trimIndent()

        val s = SummaryParser.parseThirdParty(text, "gpt-4o-mini")!!
        assertThat(s.source).isEqualTo(SummarySource.THIRD_PARTY)
        assertThat(s.overview).contains("协程的基本用法")
        assertThat(s.outline).hasSize(1)
        assertThat(s.outline[0].points).containsExactly(
            "suspend 函数的含义",
            "结构化并发的作用",
            "常见误用",
        )
        assertThat(s.conclusion).contains("异步写得像同步")
        assertThat(s.model).isEqualTo("gpt-4o-mini")
    }

    /**
     * ⚠️ **核心容错**：模型完全没按格式输出时，内容不能丢。
     *
     * 用户等了半分钟，宁可结构不完美，也不能给一个空白页。
     */
    @Test
    fun `认不出结构时整段兜底进概述`() {
        val text = "这个视频讲了 A、B、C 三件事，最后总结了 D。"
        val s = SummaryParser.parseThirdParty(text, "m")!!
        assertThat(s.overview).isEqualTo(text)
        assertThat(s.outline).isEmpty()
    }

    /** 半角方括号与冒号写法也要认（不同模型习惯不同）。 */
    @Test
    fun `容忍方括号与冒号写法`() {
        val text = """
            [视频概述]
            概述内容。

            [核心内容]
            - 要点一
        """.trimIndent()

        val s = SummaryParser.parseThirdParty(text, "m")!!
        assertThat(s.overview).isEqualTo("概述内容。")
        assertThat(s.outline[0].points).containsExactly("要点一")
    }

    /** 只有一段时，其余字段为空而不是报错。 */
    @Test
    fun `只认出一段也能用`() {
        val text = "【视频概述】\n只有概述。"
        val s = SummaryParser.parseThirdParty(text, "m")!!
        assertThat(s.overview).isEqualTo("只有概述。")
        assertThat(s.conclusion).isEmpty()
    }

    /** 空文本 → null（调用方报"模型返回空内容"）。 */
    @Test
    fun `空文本返回 null`() {
        assertThat(SummaryParser.parseThirdParty("", "m")).isNull()
        assertThat(SummaryParser.parseThirdParty("   ", "m")).isNull()
        assertThat(SummaryParser.parseThirdParty(null, "m")).isNull()
    }

    /** 截断标记必须透传到结果（UI 要显示"基于前 N 分钟"）。 */
    @Test
    fun `截断标记被保留`() {
        val s = SummaryParser.parseThirdParty("【视频概述】\nx", "m", truncated = true)!!
        assertThat(s.truncated).isTrue()
    }

    /** 要点行首的 `-` / `*` / `•` 都要剥掉（否则 UI 会出现双层符号）。 */
    @Test
    fun `要点行首符号被剥离`() {
        val text = """
            【核心内容】
            - 短横线
            * 星号
            • 圆点
        """.trimIndent()
        val s = SummaryParser.parseThirdParty(text, "m")!!
        assertThat(s.outline[0].points).containsExactly("短横线", "星号", "圆点")
    }

    // ---------------- Prompt 构造 ----------------

    /** User Prompt 必须带上标题、字幕，并用分隔符隔开。 */
    @Test
    fun `user prompt 包含标题与字幕`() {
        val (p, truncated) = SummaryPrompt.buildUser(
            title = "测试标题",
            desc = "简介内容",
            subtitleText = "第一句\n第二句",
        )
        assertThat(p).contains("测试标题")
        assertThat(p).contains("简介内容")
        assertThat(p).contains("第一句")
        assertThat(truncated).isFalse()
    }

    /**
     * ⚠️ 超长字幕必须截断，且**如实标记**。
     *
     * 不标记的话用户会以为总结覆盖了全片，而实际只看了前面一部分 ——
     * 那是一个会误导人的假象。
     */
    @Test
    fun `超长字幕被截断并标记`() {
        val long = "字".repeat(SummaryPrompt.MAX_SUBTITLE_CHARS + 5000)
        val (p, truncated) = SummaryPrompt.buildUser("t", "", long)

        assertThat(truncated).isTrue()
        assertThat(p).contains("字幕过长已截断")
        // prompt 本身不该无限增长
        assertThat(p.length).isLessThan(SummaryPrompt.MAX_SUBTITLE_CHARS + 2000)
    }

    /** 简介也要截断（它只是上下文，不需要全给）。 */
    @Test
    fun `超长简介被截断`() {
        val (p, _) = SummaryPrompt.buildUser("t", "简".repeat(5000), "字幕")
        assertThat(p.length).isLessThan(SummaryPrompt.MAX_SUBTITLE_CHARS)
    }

    /**
     * System Prompt 必须包含那些硬约束。
     *
     * 这几条是需求明确要求的（不凭空添加、按长度调整、默认中文…），
     * 删掉任意一条都会让输出质量退化。
     */
    @Test
    fun `system prompt 包含关键约束`() {
        val s = SummaryPrompt.SYSTEM
        assertThat(s).contains("只依据字幕内容")
        assertThat(s).contains("中文")
        assertThat(s).contains("【视频概述】")
        assertThat(s).contains("【核心内容】")
        assertThat(s).contains("【简短总结】")
    }
}
