package com.example.biliv3.data.ai

/**
 * AI 总结的来源。
 *
 * ## 🔴 为什么"来源"必须是模型的一部分
 *
 * 需求要求"B 站官方总结优先，拿不到再用第三方"。两种来源的**可信度
 * 与责任归属完全不同**：
 *
 * - 官方总结由 B 站生成，是**站内数据**
 * - 第三方总结由用户自己配的模型生成，是**本应用算出来的**
 *
 * 如果把两者在 UI 上长得一样，用户无法判断眼前这段文字是谁说的 ——
 * 尤其当第三方模型出现幻觉时，会被误当成"B 站官方说的"。
 *
 * 所以来源进模型、进 UI（页面上明确标注），不只是内部字段。
 */
enum class SummarySource {
    /** B 站官方 AI 总结。 */
    OFFICIAL,

    /** 第三方 AI（用户自己配置的模型 + 本项目的自定义 Prompt）。 */
    THIRD_PARTY,
}

/**
 * 一条 AI 总结。
 *
 * @param source 来源（**必须显示给用户**）
 * @param overview 视频概述
 * @param outline 分段要点（官方叫 `outline`，第三方的"核心内容"也映射到这里）
 * @param conclusion 简短总结
 * @param model 生成用的模型名（仅第三方有；官方为"B 站官方"）
 * @param truncated 字幕是否因过长被截断（**必须告知用户**，
 *   否则他会以为总结覆盖了全片）
 */
data class VideoSummary(
    val source: SummarySource,
    val overview: String,
    val outline: List<SummarySection> = emptyList(),
    val conclusion: String = "",
    val model: String = "",
    val truncated: Boolean = false,
) {
    /** 是否为空（三段都没有内容）。 */
    val isEmpty: Boolean
        get() = overview.isBlank() && outline.isEmpty() && conclusion.isBlank()

    companion object {
        /** 官方总结的展示用"模型名"。 */
        const val OFFICIAL_LABEL = "B 站官方 AI"
    }
}

/**
 * 总结里的一个分段。
 *
 * @param title 小标题（官方结构里是 `title`；第三方按时间点生成）
 * @param points 该段的要点
 * @param timestampSeconds 时间戳（秒）；0 = 无时间点
 */
data class SummarySection(
    val title: String,
    val points: List<String> = emptyList(),
    val timestampSeconds: Int = 0,
) {
    /** `03:21` 形式的时间标签；无时间点返回空串。 */
    val timeLabel: String
        get() {
            if (timestampSeconds <= 0) return ""
            val m = timestampSeconds / 60
            val s = timestampSeconds % 60
            return "%02d:%02d".format(m, s)
        }
}
