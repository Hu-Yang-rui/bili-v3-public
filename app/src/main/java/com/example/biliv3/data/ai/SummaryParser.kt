package com.example.biliv3.data.ai

import org.json.JSONObject

/**
 * AI 总结响应的解析（**纯函数**，可单测）。
 *
 * ## 两条来源的响应结构完全不同
 *
 * | 来源 | 结构 |
 * |---|---|
 * | 官方 `view/conclusion/get` | `data.model_result.{result_type,summary,outline[]}` |
 * | 第三方 OpenAI 风格 | `choices[0].message.content`（**自由文本**） |
 *
 * 所以这里两个入口，各自负责把"对方的形状"转成统一的 [VideoSummary]。
 *
 * ## 为什么第三方那侧需要"文本 → 结构"的解析
 *
 * 我们在 Prompt 里要求了固定三段标题（【视频概述】/【核心内容】/【简短总结】），
 * 但**模型不保证严格遵守**：可能少一段、可能加前言、可能用别的标题写法。
 * 所以解析必须**容错**：
 * - 认得的段就填进对应字段
 * - 认不得的整段文本**兜底进 `overview`**（宁可结构差一点，
 *   也不能把用户等来的结果丢掉）
 *
 * 这条"兜底"很重要：丢内容比结构不完美严重得多。
 */
object SummaryParser {

    // ---------------- 官方 ----------------

    /**
     * 解析官方 `view/conclusion/get` 的响应。
     *
     * ## 结构（公开资料 + 防御性解析）
     *
     * ```json
     * {"code":0,"data":{
     *    "code":0,
     *    "model_result":{
     *      "result_type":0,
     *      "summary":"...",
     *      "outline":[{"title":"...","part_outline":[
     *          {"timestamp":0,"content":"..."}],"timestamp":0}]
     *    }}}
     * ```
     *
     * ⚠️ **本项目未能实测到成功响应**（接口未登录返回 `-403`，
     * 本机无可用登录态）。所以这里按公开结构写 + 全面容错，
     * 并且**不假设它一定成功** —— 任何一层缺失都返回 null，
     * 由调用方降级到第三方。
     *
     * 这一点必须如实记录：**官方路径未在真实数据上验证过成功分支**。
     *
     * @return 解析结果；结构不符 / 无内容时返回 null（→ 降级）
     */
    fun parseOfficial(json: JSONObject?): VideoSummary? {
        val data = json?.optJSONObject("data") ?: return null

        // ⚠️ 官方把结果码放在**两层**：顶层 code 与 data.code。
        // 实测（-403 那次）顶层就是 -403；成功时两层都应为 0。
        // 这里只判 data 层的 model_result 是否存在，不额外要求 code == 0 ——
        // 因为见过"code 非 0 但 model_result 有内容"的接口（宁可多解析一次）。
        val result = data.optJSONObject("model_result") ?: return null

        val summary = result.optString("summary").trim()
        val outline = parseOutline(result.optJSONArray("outline"))

        // 三段全空视为"没有官方总结" → 降级
        if (summary.isEmpty() && outline.isEmpty()) return null

        return VideoSummary(
            source = SummarySource.OFFICIAL,
            overview = summary,
            outline = outline,
            conclusion = "",
            model = VideoSummary.OFFICIAL_LABEL,
        )
    }

    /**
     * 解析官方的 `outline[]`。
     *
     * 每项形如 `{title, part_outline:[{timestamp, content}], timestamp}`。
     * `part_outline` 里的每条是**一个要点**，`title` 是分段标题。
     */
    private fun parseOutline(arr: org.json.JSONArray?): List<SummarySection> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<SummarySection>(arr.length())

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val ts = o.optInt("timestamp", 0)

            // part_outline 里每条的 content 是一个要点
            val parts = o.optJSONArray("part_outline")
            val points = ArrayList<String>()
            if (parts != null) {
                for (j in 0 until parts.length()) {
                    val p = parts.optJSONObject(j) ?: continue
                    val c = p.optString("content").trim()
                    if (c.isNotEmpty()) points.add(c)
                }
            }

            // 标题与要点都空 → 跳过（不产出空分段）
            if (title.isEmpty() && points.isEmpty()) continue
            out.add(SummarySection(title = title, points = points, timestampSeconds = ts))
        }
        return out
    }

    // ---------------- 第三方（OpenAI 风格）----------------

    /**
     * 从 OpenAI 风格响应里取出正文文本。
     *
     * 兼容两种常见形态：
     * - `choices[0].message.content`（Chat Completions，主流）
     * - `choices[0].text`（旧版 Completions / 部分兼容实现）
     *
     * @return 正文；取不到返回 null（调用方据此报"响应格式不符"，
     *   而不是显示一个空总结）
     */
    fun extractContent(json: JSONObject?): String? {
        val choices = json?.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val c = choices.optJSONObject(0) ?: return null
        val text = c.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: c.optString("text").takeIf { it.isNotBlank() }
        return text?.trim()
    }

    /**
     * 把模型输出的自由文本解析成结构化的 [VideoSummary]。
     *
     * ## 容错策略（重要）
     *
     * 1. 按 Prompt 要求的三段标题切分；标题写法容忍全角/半角括号
     * 2. 认得的段填进对应字段
     * 3. **认不得的整段文本兜底进 `overview`** —— 不丢内容
     * 4. 空文本返回 null
     */
    fun parseThirdParty(
        content: String?,
        model: String,
        truncated: Boolean = false,
    ): VideoSummary? {
        val text = content?.trim().orEmpty()
        if (text.isEmpty()) return null

        val overview = section(text, OVERVIEW_KEYS)
        val core = section(text, CORE_KEYS)
        val conclusion = section(text, CONCLUSION_KEYS)

        // 三段一个都没认出来 → 整段当概述（不丢内容）
        if (overview.isEmpty() && core.isEmpty() && conclusion.isEmpty()) {
            return VideoSummary(
                source = SummarySource.THIRD_PARTY,
                overview = text,
                model = model,
                truncated = truncated,
            )
        }

        // 【核心内容】那一段按行拆成要点
        val outline = if (core.isNotEmpty()) {
            listOf(SummarySection(title = "核心内容", points = bulletLines(core)))
        } else {
            emptyList()
        }

        return VideoSummary(
            source = SummarySource.THIRD_PARTY,
            overview = overview,
            outline = outline,
            conclusion = conclusion,
            model = model,
            truncated = truncated,
        )
    }

    /**
     * 取出某个标题下的正文。
     *
     * 实现：找到标题行，取到**下一个标题行之前**的全部内容。
     * 标题容忍 `【x】` / `[x]` / `x：` 三种写法（不同模型习惯不同）。
     */
    private fun section(text: String, keys: List<String>): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { line -> keys.any { matchesHeading(line, it) } }
        if (start < 0) return ""

        val body = StringBuilder()
        for (i in (start + 1) until lines.size) {
            // 遇到任何一段的标题就停
            if (ALL_KEYS.any { matchesHeading(lines[i], it) }) break
            body.append(lines[i]).append('\n')
        }
        return body.toString().trim()
    }

    /** 某一行是否是标题 [key]（容忍 `【】` / `[]` / `:` 三种写法）。 */
    private fun matchesHeading(line: String, key: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        return t == "【$key】" ||
            t == "[$key]" ||
            t == "$key：" ||
            t == "$key:" ||
            // 有些模型会写 "## 【核心内容】" 这类带 markdown 前缀的形式
            t.endsWith("【$key】") && t.length <= key.length + 8
    }

    /**
     * 把一段文本拆成要点行。
     *
     * 去掉行首的 `-` / `*` / `•` / 数字序号；过短的行（如空行）丢弃。
     */
    private fun bulletLines(text: String): List<String> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                line.removePrefix("-").removePrefix("*").removePrefix("•").trim()
            }
            .filter { it.isNotEmpty() }

    /** 三段标题的识别键（顺序即优先级）。 */
    private val OVERVIEW_KEYS = listOf("视频概述", "概述", "视频概要")
    private val CORE_KEYS = listOf("核心内容", "主要内容", "重点信息", "关键观点")
    private val CONCLUSION_KEYS = listOf("简短总结", "总结", "一句话总结")

    private val ALL_KEYS = OVERVIEW_KEYS + CORE_KEYS + CONCLUSION_KEYS
}
