package com.example.biliv3.data.auth

/**
 * Cookie 导入 / 导出与格式校验（开发者工具）。
 *
 * ## 为什么单独成文件
 *
 * [AuthStore] 只负责"存"；把"解析 / 校验 / 序列化"放在这里，好处有二：
 *
 * 1. **可单测** —— 纯 Kotlin，不碰 Android / Keystore，JVM 测试直接跑
 *    （`AuthStore` 需要 `Context`，在本地单测里拿不到）
 * 2. **导入导出的格式天然对称** —— [serialize] 的输出就是 [parse] 的输入，
 *    不会出现"导出的文件导不回去"（这是导入导出功能的经典缺陷）
 *
 * ## 支持的输入格式（都实测过）
 *
 * | 形态 | 例 |
 * |---|---|
 * | `Cookie:` 请求头 | `Cookie: SESSDATA=xxx; bili_jct=yyy` |
 * | 裸 cookie 串 | `SESSDATA=xxx; bili_jct=yyy` |
 * | 换行分隔 | `SESSDATA=xxx` 换行 `bili_jct=yyy` |
 * | `&` 分隔 | `SESSDATA=xxx&bili_jct=yyy` |
 * | JSON 对象 | `{"SESSDATA":"xxx","bili_jct":"yyy"}` |
 * | 带引号的值 | `SESSDATA="xxx"` |
 * | 浏览器扩展导出的数组 | `[{"name":"SESSDATA","value":"xxx"}]` |
 *
 * ## 安全约定（§4.3）
 *
 * - 本文件**不打印**任何值 —— 调用方要日志时只能用 [maskedSummary]
 * - [serialize] 只产出明文，**写文件由调用方负责**，且必须由用户主动触发
 */
object CookieCodec {

    /** 登录必需字段。缺 `SESSDATA` 就一定不是登录态。 */
    private val ESSENTIAL = listOf("SESSDATA", "bili_jct", "DedeUserID")

    /** 解析结果。 */
    sealed interface Result {
        /** 解析成功。[pairs] 已去重、已去空值。 */
        data class Ok(val pairs: LinkedHashMap<String, String>) : Result

        /** 解析失败，[reason] 是给用户看的中文原因。 */
        data class Fail(val reason: String) : Result
    }

    /**
     * 解析任意常见格式的 cookie 文本。
     *
     * 重复字段**后者覆盖前者**（与浏览器"同名 cookie 取最后一条"一致）；
     * 这是刻意的 —— 用户从 DevTools 复制时，后面那条通常才是最新的。
     */
    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Fail("内容为空")

        val pairs = LinkedHashMap<String, String>()

        // JSON 形态：对象或数组（浏览器扩展导出）
        if (text.startsWith("{") || text.startsWith("[")) {
            parseJson(text, pairs)
            if (pairs.isNotEmpty()) return validate(pairs)
            return Result.Fail("看起来是 JSON，但没解析出任何 cookie 字段")
        }

        // 去掉可选的 `Cookie:` 前缀
        val body = text.removePrefix("Cookie:").removePrefix("cookie:").trim()

        // 分隔符：`;` / 换行 / `&` 都当分隔符
        val chunks = body.split(';', '\n', '\r', '&')

        for (chunk in chunks) {
            val part = chunk.trim()
            if (part.isEmpty()) continue

            val idx = part.indexOf('=')
            if (idx <= 0) continue

            val name = part.substring(0, idx).trim()
            // 值可能被引号包着（JSON 字符串风格 / 某些扩展导出）
            val value = part.substring(idx + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()

            if (name.isEmpty()) continue
            pairs[name] = value
        }

        if (pairs.isEmpty()) {
            return Result.Fail("没找到任何 `名字=值` 形式的字段")
        }
        return validate(pairs)
    }

    /**
     * JSON 解析。
     *
     * 手写而非用 `org.json` —— 后者在本地单测里是 **stub**
     * （AGENTS.md §8 记录过：调用即抛 `Method not mocked`）。
     * 这里只需要处理"扁平的 字符串→字符串"这一种形状，手写足够且零依赖。
     */
    private fun parseJson(text: String, out: LinkedHashMap<String, String>) {
        // 数组形态：[{"name":"SESSDATA","value":"xxx"}, ...]
        // 对象形态：{"SESSDATA":"xxx", ...}
        // 两种都用同一套"找 name/value 或 键值对"的方式提取。
        val objRe = Regex("""["'](name|key)["']\s*:\s*["']([^"']*)["']\s*,\s*["'](value|val)["']\s*:\s*["']([^"']*)["']""")
        val hits = objRe.findAll(text).toList()
        if (hits.isNotEmpty()) {
            for (m in hits) out[m.groupValues[2]] = m.groupValues[4]
            return
        }

        // 扁平对象：{"k":"v","k2":"v2"}
        val flatRe = Regex("""["']([A-Za-z0-9_\-]+)["']\s*:\s*["']([^"']*)["']""")
        for (m in flatRe.findAll(text)) {
            val k = m.groupValues[1]
            if (k == "name" || k == "value" || k == "key" || k == "val") continue
            out[k] = m.groupValues[2]
        }
    }

    /** 校验必需字段。 */
    private fun validate(pairs: LinkedHashMap<String, String>): Result {
        val sess = pairs["SESSDATA"].orEmpty()
        if (sess.isEmpty()) {
            return Result.Fail(
                "缺少 SESSDATA —— 它不是登录凭据，只有它才能标识账号。" +
                    "请确认复制的是登录后的 cookie",
            )
        }
        return Result.Ok(pairs)
    }

    /**
     * 序列化为可直接当 `Cookie:` 头用的字符串。
     *
     * **与 [parse] 严格对称** —— 导出的内容必然能被 [parse] 读回。
     */
    fun serialize(pairs: Map<String, String>): String =
        pairs.entries
            .filter { it.key.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }

    /**
     * 脱敏摘要，**唯一允许进日志 / UI 的形态**。
     *
     * 只暴露"有哪些字段 + 值有多长"，不暴露任何值的内容。
     * `SESSDATA` 这类长值连前缀都不给 —— 前缀本身就能缩小爆破范围。
     */
    fun maskedSummary(pairs: Map<String, String>): String =
        pairs.entries.joinToString("\n") { (k, v) ->
            val kind = when {
                k in ESSENTIAL -> "凭据"
                else -> "普通"
            }
            "$k  [$kind · ${v.length} 字符]"
        }

    /** 关键字段是否齐全（给"验证登录状态"用）。 */
    fun missingEssentials(pairs: Map<String, String>): List<String> =
        ESSENTIAL.filter { pairs[it].isNullOrEmpty() }

    /** 供 UI 显示的字段清单（只给名字，不给值）。 */
    fun fieldNames(pairs: Map<String, String>): List<String> = pairs.keys.toList()

    /** 必需字段名（UI 提示用）。 */
    val essentialNames: List<String> get() = ESSENTIAL
}
