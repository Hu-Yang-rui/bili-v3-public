package com.example.biliv3.data.meme

/**
 * 内置烂梗库（v1.6.5）。
 *
 * ---
 *
 * # 来源
 *
 * 全部条目由**本项目整理**，是直播/弹幕场景的常用语，
 * **不是**任何第三方站点的数据，也**不是** B 站官方内容。
 *
 * ## 内容取向
 *
 * 只收**直播弹幕里通用、无害**的短句 —— 用于暖场、接话、表达情绪。
 * **刻意不收**：
 * - 带攻击性 / 地域黑 / 人身指向的
 * - 涉及刷屏诱导的（"刷起来"这类）
 * - 需要特定主播上下文才成立的（换直播间就是莫名其妙）
 *
 * 理由：这个功能的用途是"懒得打字时快速发一句合适的"，
 * 而不是"批量刷屏工具"。收进攻击性内容等于把工具变成骚扰工具。
 *
 * ## 为什么用 `listOf` 常量而不是 JSON 资源
 *
 * 数据量小（几十条）、无嵌套，用 Kotlin 常量：
 * - 编译期就能查错（JSON 拼错要到运行时才发现）
 * - 零解析开销、零 assets 读取
 * - 单测可直接引用
 */
object MemeLibrary {

    /** 内置条目。分类名即 UI 上的分组名。 */
    val BUILT_IN: List<Meme> = listOf(
        // ---- 打招呼 / 暖场 ----
        Meme("主播好", "打招呼"),
        Meme("来了来了", "打招呼"),
        Meme("前排", "打招呼"),
        Meme("打卡", "打招呼"),
        Meme("晚上好", "打招呼"),
        Meme("早上好", "打招呼"),
        Meme("新人报道", "打招呼"),
        Meme("路过看看", "打招呼"),

        // ---- 情绪 / 反应 ----
        Meme("哈哈哈哈哈", "情绪"),
        Meme("笑死我了", "情绪"),
        Meme("绷不住了", "情绪"),
        Meme("太真实了", "情绪"),
        Meme("典", "情绪", "表示很典型、很符合预期"),
        Meme("好家伙", "情绪"),
        Meme("我裂开了", "情绪"),
        Meme("麻了", "情绪"),
        Meme("泪目", "情绪"),
        Meme("破防了", "情绪"),

        // ---- 认可 / 称赞 ----
        Meme("牛", "称赞"),
        Meme("666", "称赞"),
        Meme("厉害了", "称赞"),
        Meme("好活", "称赞", "夸主播做得好"),
        Meme("专业", "称赞"),
        Meme("学到了", "称赞"),
        Meme("这波可以", "称赞"),

        // ---- 互动 / 提问 ----
        Meme("主播能说一下吗", "互动"),
        Meme("求个链接", "互动"),
        Meme("这个怎么弄的", "互动"),
        Meme("什么时候播", "互动"),
        Meme("下次还来", "互动"),
        Meme("先码住", "互动"),

        // ---- 支持 ----
        Meme("支持主播", "支持"),
        Meme("已三连", "支持"),
        Meme("关注了", "支持"),
        Meme("加油", "支持"),
        Meme("辛苦了", "支持"),

        // ---- 收尾 ----
        Meme("先撤了", "收尾"),
        Meme("下次见", "收尾"),
        Meme("晚安", "收尾"),
        Meme("拜拜", "收尾"),
    )

    /**
     * 未分类条目的归类名。
     *
     * ⚠️ 与"空分类"区分：`category` 为空的条目归到这一组，
     * 而不是被丢掉。
     */
    const val UNCATEGORIZED = "其它"

    /** 全部分类（按条目出现顺序，未分类排最后）。 */
    fun categories(memes: List<Meme> = BUILT_IN): List<MemeCategory> {
        val grouped = LinkedHashMap<String, MutableList<Meme>>()
        for (m in memes) {
            val cat = m.category.ifBlank { UNCATEGORIZED }
            grouped.getOrPut(cat) { mutableListOf() }.add(m)
        }
        // 未分类永远排最后（它是兜底，不该插在正经分类中间）
        return grouped.entries
            .sortedBy { if (it.key == UNCATEGORIZED) 1 else 0 }
            .map { MemeCategory(it.key, it.value) }
    }
}

/**
 * 梗库的**搜索**（纯函数，可单测）。
 *
 * ## 为什么搜索逻辑要单独一个对象
 *
 * "搜索"看起来是 `filter { it.text.contains(q) }`，但有几个真实的取舍：
 *
 * 1. **大小写**：梗里有 `666`、`Orz` 这类，忽略大小写才不会
 *    "搜 orz 搜不到 Orz"
 * 2. **前后空格**：用户在输入框里手滑带空格是常态
 * 3. **空查询返回全部**（而不是空）—— 否则清空输入框会让列表突然空了
 * 4. **搜索范围**：正文 + 备注 + 分类名。只搜正文的话，
 *    用户按分类词搜（如"称赞"）会得到 0 结果
 */
object MemeSearch {

    /**
     * 搜索。
     *
     * @param query 关键词；空 / 纯空白 = 返回全部
     * @return 命中的条目（保持输入顺序）
     */
    fun search(memes: List<Meme>, query: String): List<Meme> {
        val q = query.trim()
        if (q.isEmpty()) return memes
        return memes.filter { m ->
            m.text.contains(q, ignoreCase = true) ||
                m.note.contains(q, ignoreCase = true) ||
                m.category.contains(q, ignoreCase = true)
        }
    }

    /**
     * 按分类分组（搜索结果也要分组显示）。
     *
     * 与 [MemeLibrary.categories] 同一实现 —— 搜索后仍按分类呈现，
     * 而不是退化成一个平铺长列表（那会让用户失去位置感）。
     */
    fun group(memes: List<Meme>): List<MemeCategory> = MemeLibrary.categories(memes)
}
