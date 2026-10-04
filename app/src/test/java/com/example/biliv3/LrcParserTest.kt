package com.example.biliv3

import com.example.biliv3.data.lyrics.LrcParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * LRC 解析单测。
 *
 * 重点覆盖**真实脏数据**：网络歌词来源质量参差，
 * 解析器如果因为一行坏数据丢掉整首，用户看到的就是"没歌词"。
 */
class LrcParserTest {

    // ---------------- 基础 ----------------

    @Test
    fun `解析标准两行歌词`() {
        val lrc = LrcParser.parse(
            """
            [00:01.00]第一行
            [00:12.34]第二行
            """.trimIndent(),
        )
        assertThat(lrc.lines).hasSize(2)
        assertThat(lrc.lines[0].timeMs).isEqualTo(1000L)
        assertThat(lrc.lines[0].text).isEqualTo("第一行")
        assertThat(lrc.lines[1].timeMs).isEqualTo(12_340L)
    }

    @Test
    fun `元信息行被忽略`() {
        val lrc = LrcParser.parse(
            """
            [ti:歌名]
            [ar:歌手]
            [al:专辑]
            [by:制作]
            [00:01.00]正文
            """.trimIndent(),
        )
        assertThat(lrc.lines).hasSize(1)
        assertThat(lrc.lines[0].text).isEqualTo("正文")
    }

    @Test
    fun `空文本返回空歌词而不是崩溃`() {
        assertThat(LrcParser.parse("").isEmpty).isTrue()
        assertThat(LrcParser.parse("   \n  \n").isEmpty).isTrue()
    }

    @Test
    fun `完全不含时间轴的文本返回空歌词`() {
        val lrc = LrcParser.parse("这是一段没有时间轴的文本\n第二行")
        assertThat(lrc.isEmpty).isTrue()
    }

    // ---------------- 小数位数 ----------------

    @Test
    fun `一位小数是十分之一秒不是一毫秒`() {
        // [00:12.5] = 12.5s = 12500ms。早期实现会算成 12005ms
        val lrc = LrcParser.parse("[00:12.5]测试")
        assertThat(lrc.lines[0].timeMs).isEqualTo(12_500L)
    }

    @Test
    fun `两位小数是百分秒`() {
        val lrc = LrcParser.parse("[00:12.34]测试")
        assertThat(lrc.lines[0].timeMs).isEqualTo(12_340L)
    }

    @Test
    fun `三位小数是毫秒`() {
        val lrc = LrcParser.parse("[00:12.345]测试")
        assertThat(lrc.lines[0].timeMs).isEqualTo(12_345L)
    }

    @Test
    fun `无小数按整秒`() {
        val lrc = LrcParser.parse("[00:12]测试")
        assertThat(lrc.lines[0].timeMs).isEqualTo(12_000L)
    }

    @Test
    fun `用冒号分隔小数也支持`() {
        val lrc = LrcParser.parse("[00:12:34]测试")
        assertThat(lrc.lines[0].timeMs).isEqualTo(12_340L)
    }

    // ---------------- 多时间标签 ----------------

    @Test
    fun `一行多个时间标签会展开成多行`() {
        // 副歌复用：同一句歌词在多个时间点出现
        val lrc = LrcParser.parse("[00:10.00][00:30.00][01:00.00]重复的副歌")
        assertThat(lrc.lines).hasSize(3)
        assertThat(lrc.lines.map { it.timeMs })
            .containsExactly(10_000L, 30_000L, 60_000L).inOrder()
        assertThat(lrc.lines.all { it.text == "重复的副歌" }).isTrue()
    }

    // ---------------- 脏数据容错 ----------------

    @Test
    fun `乱序时间轴会被重新排序`() {
        val lrc = LrcParser.parse(
            """
            [00:30.00]后面的
            [00:10.00]前面的
            [00:20.00]中间的
            """.trimIndent(),
        )
        assertThat(lrc.lines.map { it.timeMs })
            .containsExactly(10_000L, 20_000L, 30_000L).inOrder()
    }

    @Test
    fun `重复时间轴只保留先出现的`() {
        val lrc = LrcParser.parse(
            """
            [00:10.00]第一次
            [00:10.00]第二次
            """.trimIndent(),
        )
        assertThat(lrc.lines).hasSize(1)
        assertThat(lrc.lines[0].text).isEqualTo("第一次")
    }

    @Test
    fun `秒数越界的时间轴被跳过而不是让整首失败`() {
        // [00:99] 是脏数据；同一份文件里的好行必须保留
        val lrc = LrcParser.parse(
            """
            [00:99.00]坏行
            [00:10.00]好行
            """.trimIndent(),
        )
        assertThat(lrc.lines).hasSize(1)
        assertThat(lrc.lines[0].text).isEqualTo("好行")
    }

    @Test
    fun `非数字时间标签被跳过`() {
        val lrc = LrcParser.parse(
            """
            [ab:cd]坏行
            [00:10.00]好行
            """.trimIndent(),
        )
        assertThat(lrc.lines).hasSize(1)
        assertThat(lrc.lines[0].text).isEqualTo("好行")
    }

    @Test
    fun `只有时间没有文本的行被跳过`() {
        val lrc = LrcParser.parse(
            """
            [00:05.00]
            [00:10.00]有文本
            """.trimIndent(),
        )
        assertThat(lrc.lines).hasSize(1)
    }

    @Test
    fun `BOM 不影响第一行解析`() {
        val lrc = LrcParser.parse("\uFEFF[00:01.00]第一行")
        assertThat(lrc.lines).hasSize(1)
        assertThat(lrc.lines[0].text).isEqualTo("第一行")
    }

    // ---------------- offset ----------------

    @Test
    fun `解析 offset 标签`() {
        val lrc = LrcParser.parse(
            """
            [offset:-500]
            [00:10.00]测试
            """.trimIndent(),
        )
        assertThat(lrc.offsetMs).isEqualTo(-500L)
    }

    @Test
    fun `正 offset 也可以解析`() {
        val lrc = LrcParser.parse(
            """
            [offset:+300]
            [00:10.00]测试
            """.trimIndent(),
        )
        assertThat(lrc.offsetMs).isEqualTo(300L)
    }

    // ---------------- indexAt ----------------

    @Test
    fun `前奏期间不高亮任何行`() {
        // 返回 -1 而不是 0 —— 否则前奏时第一行一直亮着，像卡住了
        val lrc = LrcParser.parse(
            """
            [00:10.00]第一行
            [00:20.00]第二行
            """.trimIndent(),
        )
        assertThat(lrc.indexAt(0L)).isEqualTo(-1)
        assertThat(lrc.indexAt(9_999L)).isEqualTo(-1)
    }

    @Test
    fun `正好到点的高亮该行`() {
        val lrc = LrcParser.parse(
            """
            [00:10.00]第一行
            [00:20.00]第二行
            """.trimIndent(),
        )
        assertThat(lrc.indexAt(10_000L)).isEqualTo(0)
        assertThat(lrc.indexAt(19_999L)).isEqualTo(0)
        assertThat(lrc.indexAt(20_000L)).isEqualTo(1)
    }

    @Test
    fun `超过最后一行一直高亮最后一行`() {
        val lrc = LrcParser.parse("[00:10.00]唯一行")
        assertThat(lrc.indexAt(999_999L)).isEqualTo(0)
    }

    @Test
    fun `空歌词的 indexAt 返回 -1`() {
        assertThat(LrcParser.parse("").indexAt(1000L)).isEqualTo(-1)
    }

    @Test
    fun `offset 正值让歌词延后出现`() {
        // offset = +500：歌词应比时间轴晚 500ms 出现
        val lrc = LrcParser.parse(
            """
            [offset:500]
            [00:10.00]第一行
            """.trimIndent(),
        )
        assertThat(lrc.indexAt(10_000L)).isEqualTo(-1)   // 还没到
        assertThat(lrc.indexAt(10_500L)).isEqualTo(0)    // 到了
    }

    @Test
    fun `offset 负值让歌词提前出现`() {
        val lrc = LrcParser.parse(
            """
            [offset:-500]
            [00:10.00]第一行
            """.trimIndent(),
        )
        assertThat(lrc.indexAt(9_500L)).isEqualTo(0)
    }

    // ---------------- 文本保留 ----------------

    @Test
    fun `歌词文本中的方括号不会被误当时间标签`() {
        val lrc = LrcParser.parse("[00:10.00][这是歌词里的方括号]")
        assertThat(lrc.lines).hasSize(1)
        assertThat(lrc.lines[0].text).isEqualTo("[这是歌词里的方括号]")
    }

    @Test
    fun `多行歌词按顺序索引正确`() {
        val lrc = LrcParser.parse(
            """
            [00:00.00]一
            [00:05.00]二
            [00:10.00]三
            [00:15.00]四
            """.trimIndent(),
        )
        assertThat(lrc.indexAt(0L)).isEqualTo(0)
        assertThat(lrc.indexAt(7_000L)).isEqualTo(1)
        assertThat(lrc.indexAt(12_000L)).isEqualTo(2)
        assertThat(lrc.indexAt(20_000L)).isEqualTo(3)
    }
}
