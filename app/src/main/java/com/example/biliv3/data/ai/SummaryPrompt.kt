package com.example.biliv3.data.ai

/**
 * AI 总结的提示词构造。
 *
 * ---
 *
 * # ⚠️ 重要声明：这是**项目自定义 Prompt**，不是 B 站官方 Prompt
 *
 * ## 依据（不是推测，是检索结论）
 *
 * 已对**官方 B 站 Android 客户端 APK**（`tv.danmaku.bili` v9.13.0，
 * 33 个 dex / 1,516,451 条字符串）做过完整静态检索：
 *
 * | 检索项 | 结果 |
 * |---|---|
 * | `ai_summary` / `video_summary` / `summary_prompt` | **0 命中** |
 * | `view/conclusion` / `conclusion/get` | **0 命中** |
 * | `AI总结` / `视频总结` / `智能总结` / `一键总结` | **0 命中** |
 * | `你是一个` / `请根据` / `输出格式` / `提取要点` | **0 命中** |
 * | `You are a` / `Summarize the` / `Return a JSON` | **0 命中** |
 * | 18 个 locales 的 strings.xml + 2,302 个 assets + 93 个 .so | **0 命中** |
 *
 * 检索带**正控制词**（`视频`/`关注`/`弹幕`/`播放` 均有大量命中），
 * 证明检索方法有效，不是"搜不到"。
 *
 * **结论：B 站官方 AI 总结由服务端生成，客户端不含 Prompt 模板。**
 * 所以本文件里的任何文本都**不可能**是官方 Prompt ——
 * 声称"与官方一致"是不诚实的。
 *
 * ## 如果将来拿到官方 Prompt
 *
 * 需要运行时抓包（本机无任何抓包数据）或分析 native 库（未做）。
 * 在拿到之前，这里就是**我们自己写的** Prompt，按下面的目标设计。
 *
 * ---
 *
 * ## 设计目标（对齐需求）
 *
 * 1. **只依据字幕**，不凭空添加字幕里没有的信息
 * 2. 提取主要内容 / 核心观点 / 重点信息
 * 3. 删除无意义的口头重复
 * 4. 保持事实准确，无法确认的不推测
 * 5. **按视频实际长度调整总结长度**
 * 6. 结构清晰、默认中文、不输出与总结无关的内容
 *
 * ## 为什么把"结构"写成硬约束
 *
 * 自由格式的总结在不同模型下差异极大（有的输出一大段，有的只给一句话），
 * UI 无法稳定渲染。这里要求**固定三段 + 明确分隔符**，
 * 于是解析是确定性的，UI 也就稳定 —— 这是"能解析"与"看运气"的区别。
 */
object SummaryPrompt {

    /**
     * System Prompt（角色与硬约束）。
     *
     * ⚠️ **项目自定义，非官方。**
     */
    val SYSTEM = """
        你是一个视频内容总结助手。你的唯一任务是：根据用户提供的**视频字幕**，
        生成一份结构化的中文总结。

        必须遵守：
        1. **只依据字幕内容**。字幕里没有的信息一律不写，绝不推测或补充背景知识。
        2. 无法从字幕确认的内容，直接不提；不要写"可能""大概"这类猜测。
        3. 删除口头禅、语气词、重复表述、与主题无关的闲聊。
        4. 保持事实准确：人名、数字、专有名词必须与字幕一致。
        5. 总结长度**按字幕实际长度调整**：内容少就短，内容多才长，
           不要为了凑长度而重复或注水。
        6. 默认使用中文输出。
        7. 只输出下面的三段结构，**不要**任何前言、客套话、解释或结语。

        输出格式（严格使用这三个标题，标题独占一行）：

        【视频概述】
        一到三句话说明这个视频讲了什么。

        【核心内容】
        按内容顺序列出要点，每行以「- 」开头。每行只讲一件事。
        如果字幕里出现了明确的时间点或阶段划分，可在行首加时间（如 03:21）。

        【简短总结】
        一句话收尾，说明这个视频最值得记住的是什么。
    """.trimIndent()

    /**
     * 构造 User Prompt（视频信息 + 字幕）。
     *
     * ## 为什么标题与简介也要给
     *
     * 字幕是**口语**，经常缺主语（"这个""那个"）。标题与简介提供了
     * 指代对象，能显著减少"这段在说什么"的歧义 —— 但它们只是**上下文**，
     * 不是总结素材（System Prompt 里已限定"只依据字幕"）。
     *
     * ## 为什么字幕要截断
     *
     * 长视频的字幕可达数万字，会超出多数模型的上下文窗口。
     * 截断时必须**如实告知**（返回值里的 `truncated`），
     * 由 UI 显示"总结基于前 N 分钟" —— 不能让用户以为覆盖了全片。
     *
     * @param title 视频标题
     * @param desc 视频简介（可为空）
     * @param subtitleText 字幕正文（已按行拼接）
     * @param maxChars 字幕最大字符数（超出则截断）
     * @return (User Prompt, 是否被截断)
     */
    fun buildUser(
        title: String,
        desc: String,
        subtitleText: String,
        maxChars: Int = MAX_SUBTITLE_CHARS,
    ): Pair<String, Boolean> {
        val truncated = subtitleText.length > maxChars
        val body = if (truncated) subtitleText.take(maxChars) else subtitleText

        val sb = StringBuilder(body.length + 512)
        sb.append("视频标题：").append(title.ifBlank { "（无标题）" }).append('\n')
        if (desc.isNotBlank()) {
            // 简介也可能很长，只取前一段够用的量（它只是上下文）
            sb.append("视频简介：")
                .append(desc.take(MAX_DESC_CHARS).replace('\n', ' '))
                .append('\n')
        }
        sb.append('\n')
        sb.append("以下是该视频的字幕（可能包含口语与重复，请自行提炼）：\n")
        sb.append("---\n")
        sb.append(body)
        if (truncated) {
            sb.append("\n---\n")
            sb.append("（注意：字幕过长已截断，上面的内容不完整。）")
        } else {
            sb.append("\n---\n")
        }
        return sb.toString() to truncated
    }

    /**
     * 字幕的最大字符数。
     *
     * 取 12000：多数模型（含较小的开源模型）都能容纳，
     * 且覆盖约 40~60 分钟的口语内容（中文口语约 200~300 字/分钟）。
     * 更长的视频会截断并**明确标注**。
     */
    const val MAX_SUBTITLE_CHARS = 12_000

    /** 简介的截断长度（它只是上下文，不需要全给）。 */
    const val MAX_DESC_CHARS = 300
}
