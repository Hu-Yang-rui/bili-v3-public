package com.example.biliv3.data.lyrics

/**
 * 一行歌词。
 *
 * @param timeMs 该行开始时间（毫秒）
 * @param text 歌词文本（已 trim）
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/**
 * 解析后的歌词。
 *
 * @param lines **已按时间升序**、已去重
 * @param offsetMs 来自 LRC 的 `[offset:]` 标签（毫秒，正值表示歌词应**延后**）
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val offsetMs: Long = 0L,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * 查当前时间应高亮的行下标。
     *
     * ## 为什么返回 -1 而不是 0
     *
     * 第一行歌词之前（前奏）**不该高亮任何行** ——
     * 返回 0 会让前奏期间第一行一直亮着，看起来像"卡住了"。
     *
     * 用二分查找而不是线性扫描：歌词页 100ms 刷新一次，
     * 一首 5 分钟的歌词约 150 行，线性扫描每秒 1500 次比较 —— 不值得。
     */
    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        // offset 正值 = 歌词延后 → 比较时要从播放位置里减掉
        val t = positionMs - offsetMs
        if (t < lines[0].timeMs) return -1

        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

/**
 * LRC 解析器。
 *
 * ## 支持的形式
 *
 * ```
 * [ti:标题]                     ← 元信息，忽略
 * [offset:-500]                 ← 时间偏移，解析
 * [00:12.34]第一行              ← 标准形式（分:秒.百分秒）
 * [00:12.345]第二行             ← 三位小数（毫秒）
 * [00:12]第三行                 ← 无小数
 * [00:12.34][00:20.00]重复行    ← 一行多个时间标签（副歌复用）
 * ```
 *
 * ## ⚠️ 必须容错的真实脏数据
 *
 * 网络歌词来源质量参差，实测会遇到：
 * - **时间轴重复**：同一时间两条不同文本 → 保留**先出现的**
 * - **时间轴乱序**：`[00:30]` 在 `[00:10]` 之前 → 必须**重新排序**
 * - **时间轴异常**：`[99:99.99]`、负数、`[ab:cd]` → **跳过该行**，不让整首解析失败
 * - **空行 / 纯元信息行** → 跳过
 * - **BOM** → 去掉（Windows 工具生成的 LRC 常见）
 *
 * 原则：**能解析多少算多少**，绝不因为一行坏数据丢掉整首歌词。
 */
object LrcParser {

    /** 时间标签：`[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]` */
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** offset 标签：`[offset:+500]` / `[offset:-500]` */
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /**
     * 解析 LRC 文本。
     *
     * @return 永不抛异常；解析不出内容时返回 [Lyrics.isEmpty] 为 true 的对象
     */
    fun parse(raw: String): Lyrics {
        if (raw.isBlank()) return Lyrics(emptyList())

        // BOM 会让第一行的 `[ti:` 匹配失败
        val text = raw.removePrefix("\uFEFF")

        var offsetMs = 0L
        val out = ArrayList<LyricLine>()

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // offset 标签（可能出现在任意行，取最后一个）
            OFFSET_TAG.find(trimmed)?.let { m ->
                offsetMs = m.groupValues[1].toLongOrNull() ?: 0L
            }

            val matches = TIME_TAG.findAll(trimmed).toList()
            if (matches.isEmpty()) continue   // 元信息行 / 纯文本行

            // 文本 = 最后一个时间标签之后的内容
            val lastEnd = matches.last().range.last + 1
            val content = trimmed.substring(lastEnd).trim()
            if (content.isEmpty()) continue   // 只有时间没有文本

            for (m in matches) {
                val ms = toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                // 异常时间轴（解析失败 / 负数）跳过该标签，不影响其它标签
                if (ms != null && ms >= 0L) out.add(LyricLine(ms, content))
            }
        }

        if (out.isEmpty()) return Lyrics(emptyList(), offsetMs)

        // 排序 + 去重：同一时间保留先出现的（文件里的原始顺序更有意义）
        out.sortBy { it.timeMs }
        val deduped = ArrayList<LyricLine>(out.size)
        for (l in out) {
            if (deduped.isNotEmpty() && deduped.last().timeMs == l.timeMs) continue
            deduped.add(l)
        }

        return Lyrics(deduped, offsetMs)
    }

    /**
     * 把 `mm` / `ss` / `frac` 转成毫秒。
     *
     * @return null = 数值非法（调用方跳过该行）
     */
    private fun toMillis(mm: String, ss: String, frac: String?): Long? {
        val m = mm.toLongOrNull() ?: return null
        val s = ss.toLongOrNull() ?: return null
        // 秒数 >= 60 视为异常（`[00:99]` 这种脏数据）
        if (s >= 60L) return null

        val fraction = when {
            frac.isNullOrEmpty() -> 0L
            // `[00:12.5]` 是十分之一秒 = 500ms，不是 5ms —— 必须按位数补零
            frac.length == 1 -> frac.toLong() * 100
            frac.length == 2 -> frac.toLong() * 10
            else -> frac.substring(0, 3).toLong()
        }
        return m * 60_000L + s * 1_000L + fraction
    }
}
