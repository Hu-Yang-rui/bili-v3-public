package com.example.biliv3

import com.example.biliv3.data.subtitle.SubtitleBody
import com.example.biliv3.data.subtitle.SubtitleCue
import com.example.biliv3.data.subtitle.SubtitleTrack
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 字幕模型与 AI 翻译轨识别测试。
 *
 * ## 为什么这组测试重要
 *
 * 「AI 翻译」在数据层唯一的表现就是 `SubtitleItem.ai_type == 1`
 * （官方 `SubtitleAiType.Translate`）。一旦这个判断写错：
 * - 用户看不到"AI 翻译"标签 → 找不到功能
 * - 或者把普通 CC 字幕标成 AI → 误导用户
 *
 * 两者都不会报错，只会静默地表现不对，所以用测试锁死。
 */
class SubtitleTest {

    private fun track(aiType: Int, type: Int = 0, lan: String = "ai-zh") = SubtitleTrack(
        id = 1L,
        lan = lan,
        lanDoc = "中文(自动翻译)",
        subtitleUrl = "//i0.hdslb.com/bfs/subtitle/x.json",
        type = type,
        aiType = aiType,
        aiStatus = 2,
        author = "",
    )

    // ---------------- AI 翻译轨识别 ----------------

    @Test
    fun `ai_type 为 1 时识别为 AI 翻译轨`() {
        assertThat(track(aiType = 1).isAiTranslate).isTrue()
    }

    @Test
    fun `ai_type 为 0 时不是 AI 翻译轨`() {
        assertThat(track(aiType = 0).isAiTranslate).isFalse()
    }

    @Test
    fun `AI 翻译轨也算 AI 内容`() {
        // type=CC 但 ai_type=Translate —— 这种组合是真实存在的：
        // 字幕本身是 UP 上传的 CC，AI 在此基础上做翻译
        assertThat(track(aiType = 1, type = 0).isAi).isTrue()
    }

    @Test
    fun `type 为 1 的 AI 字幕算 AI 内容`() {
        assertThat(track(aiType = 0, type = 1).isAi).isTrue()
    }

    @Test
    fun `普通 CC 字幕不算 AI`() {
        assertThat(track(aiType = 0, type = 0).isAi).isFalse()
    }

    // ---------------- 时间轴定位 ----------------

    private val body = SubtitleBody(
        track = track(1),
        cues = listOf(
            SubtitleCue(0.0, 2.0, "第一句"),
            SubtitleCue(2.0, 4.0, "第二句"),
            SubtitleCue(4.0, 6.0, "第三句"),
            SubtitleCue(10.0, 12.0, "跳过了中间"),
        ),
    )

    @Test
    fun `命中区间内的字幕`() {
        assertThat(body.cueAt(1.0)?.content).isEqualTo("第一句")
        assertThat(body.cueAt(3.0)?.content).isEqualTo("第二句")
        assertThat(body.cueAt(5.0)?.content).isEqualTo("第三句")
    }

    @Test
    fun `边界时间点归属后一条`() {
        // from 是闭区间、to 是开区间：2.0 应属于"第二句"
        assertThat(body.cueAt(2.0)?.content).isEqualTo("第二句")
        assertThat(body.cueAt(4.0)?.content).isEqualTo("第三句")
    }

    @Test
    fun `间隙时间点返回 null`() {
        // 6.0~10.0 之间没有字幕
        assertThat(body.cueAt(7.0)).isNull()
        assertThat(body.cueAt(9.99)).isNull()
    }

    @Test
    fun `超出末尾返回 null`() {
        assertThat(body.cueAt(100.0)).isNull()
    }

    @Test
    fun `空字幕体不崩溃`() {
        val empty = SubtitleBody(track = track(1), cues = emptyList())
        assertThat(empty.cueAt(5.0)).isNull()
    }

    @Test
    fun `二分查找在大量字幕下仍正确`() {
        // 构造 5000 条连续字幕，验证二分不会漏
        val many = (0 until 5000).map { i ->
            SubtitleCue(i.toDouble(), (i + 1).toDouble(), "第${i}句")
        }
        val b = SubtitleBody(track = track(1), cues = many)

        assertThat(b.cueAt(0.5)?.content).isEqualTo("第0句")
        assertThat(b.cueAt(2500.5)?.content).isEqualTo("第2500句")
        assertThat(b.cueAt(4999.5)?.content).isEqualTo("第4999句")
        assertThat(b.cueAt(5001.0)).isNull()
    }

    // ---------------- 语言标签 ----------------

    @Test
    fun `语言标签用于 UI 展示`() {
        val t = track(aiType = 1, lan = "ai-zh")
        assertThat(t.lanDoc).isEqualTo("中文(自动翻译)")
        assertThat(t.lan).isEqualTo("ai-zh")
    }
}
