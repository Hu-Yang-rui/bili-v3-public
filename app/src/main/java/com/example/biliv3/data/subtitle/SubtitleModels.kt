package com.example.biliv3.data.subtitle

/**
 * 一条字幕轨。
 *
 * ## `aiType` 是识别 AI 翻译的关键
 *
 * 官方 `SubtitleAiType` 枚举：
 * - `Normal(0)` —— 普通字幕（UP 主上传 / CC）
 * - `Translate(1)` —— **AI 翻译轨**
 *
 * 这是从官方 APK 反编译确认的（`com.bapis.bilibili.subtitle.SubtitleAiType`）。
 */
data class SubtitleTrack(
    val id: Long,
    val lan: String,
    val lanDoc: String,
    val subtitleUrl: String,
    /** 0=CC 1=AI（`SubtitleType`）。 */
    val type: Int,
    /** 0=Normal 1=Translate（`SubtitleAiType`）。**1 即 AI 翻译轨**。 */
    val aiType: Int,
    val aiStatus: Int,
    val author: String,
) {
    /** 是否 AI 翻译轨。 */
    val isAiTranslate: Boolean get() = aiType == AI_TYPE_TRANSLATE

    /** 是否 AI 生成（含 AI 识别 + AI 翻译）。 */
    val isAi: Boolean get() = type == TYPE_AI || isAiTranslate

    companion object {
        const val TYPE_CC = 0
        const val TYPE_AI = 1
        const val AI_TYPE_NORMAL = 0
        const val AI_TYPE_TRANSLATE = 1
    }
}

/**
 * 一条字幕（带时间轴）。
 *
 * `from` / `to` 单位是**秒**（浮点），来自字幕 JSON 的 `body[]`：
 * ```json
 * {"from":1.23,"to":3.45,"location":2,"content":"你好"}
 * ```
 */
data class SubtitleCue(
    val from: Double,
    val to: Double,
    val content: String,
) {
    /** 该时间点是否应显示本条。 */
    fun isActive(positionSeconds: Double): Boolean =
        positionSeconds >= from && positionSeconds < to
}

/** 字幕整体（某个轨的全部 cue）。 */
data class SubtitleBody(
    val track: SubtitleTrack,
    val cues: List<SubtitleCue>,
) {
    /**
     * 找当前时间应显示的字幕。
     *
     * 用**二分查找**而不是线性扫描：一条长视频可能有几千条字幕，
     * 而播放器每秒要查多次（进度更新），线性扫描会明显浪费。
     * 字幕按 `from` 升序排列，可以二分。
     */
    fun cueAt(positionSeconds: Double): SubtitleCue? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val c = cues[mid]
            when {
                positionSeconds < c.from -> hi = mid - 1
                positionSeconds >= c.to -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }
}
