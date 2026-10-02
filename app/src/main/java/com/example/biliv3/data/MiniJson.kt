package com.example.biliv3.data

/**
 * 极简 JSON 读取器（**零依赖**，可在 JVM 单测里跑）。
 *
 * ## 为什么不用 `org.json`
 *
 * Android 的 `org.json` 在本地单元测试里是**空实现** —— 调用任何方法都会抛
 * `Method ... not mocked`。要用它就得引入 mockable-android-jar 或
 * Robolectric（拖慢测试），或者引入 Gson（多一个依赖）。
 *
 * 本项目已经**手写过 protobuf**（`Proto.kt`）与 aicu 的最小 JSON 解析
 * （`AicuApi`）来解决同类问题 —— 这里沿用同一思路：
 * 我们只需要读**固定形状**的响应，不需要一个通用 JSON 库。
 *
 * ## 与 `AicuApi` 里那套的关系
 *
 * `AicuApi` 的 helper 是它的实例方法（`internal fun`），只服务于 aicu 的
 * 扁平结构。这里需要处理**两层嵌套**（数组 → 对象 → 数组 → 对象），
 * 所以单独抽一份更通用的实现，而不是去改 `AicuApi`（它有 47 个测试，
 * 改动风险大于收益）。
 *
 * ## 能力边界（够用即止，不做通用库）
 *
 * - 取对象字段（字符串 / 数字 / 数组 / 对象）
 * - 把顶层数组切成元素
 * - 字符串转义（`\"` `\\` `\n` 等）
 *
 * **不**做：数字精度校验、Unicode 转义（`\uXXXX` 保持原样）、
 * 非法 JSON 的宽容修复。遇到不合法输入宁可返回 null / 空，
 * 由调用方决定降级行为。
 */
object MiniJson {

    /**
     * 取顶层数组的元素（原样子串，不递归解析）。
     *
     * 会正确处理：
     * - 元素是对象 `{...}`、数组 `[...]`、数字、字符串
     * - 字符串里的括号与转义（`"\""` 不会被误判为字符串结束）
     *
     * @param json 形如 `[a, b, c]` 的文本；非数组返回空列表
     */
    fun elements(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val open = json.indexOf('[')
        if (open < 0) return emptyList()

        val out = ArrayList<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var start = -1

        // ⚠️ 从外层 `[` **之后**开始扫描，且 depth 从 0 起。
        //
        // 曾经的 bug：从 `open` 开始扫描，于是外层 `[` 把 depth 顶到 1，
        // 元素的 `{` 再顶到 2 —— 而收集条件写的是 `depth == 0`，
        // 结果**一个元素都收不到**（返回空列表，表现为"接口有数据但解析为 0 条"）。
        //
        // 正确语义：`[` 之后、`]` 之前，每个 depth==0 的完整 `{...}` 就是一个元素。
        for (i in (open + 1) until json.length) {
            val c = json[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '[' || c == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                c == ']' || c == '}' -> {
                    depth--
                    if (depth == 0) {
                        // `}` 收一个元素；`]` 说明外层数组结束
                        if (c == '}' && start >= 0) out.add(json.substring(start, i + 1))
                        start = -1
                        if (c == ']') break
                    } else if (depth < 0) {
                        // 遇到不属于本数组的 `]`
                        break
                    }
                }
                // 顶层标量元素（数字 / true / null）用逗号切分
                depth == 0 && c == ',' -> {
                    if (start >= 0) {
                        out.add(json.substring(start, i).trim())
                        start = -1
                    }
                }
                depth == 0 && !c.isWhitespace() && start < 0 -> start = i
            }
        }
        // 收尾：最后一个标量元素没有 `}` 或 `,` 结束
        if (start >= 0) {
            var end = json.length
            val close = json.indexOf(']', start)
            if (close >= 0) end = close
            val tail = json.substring(start, end).trim()
            if (tail.isNotEmpty()) out.add(tail)
        }
        return out
    }

    /**
     * 取对象的字符串字段值（已反转义）。
     *
     * 支持字段值里含转义引号 —— 第三方数据的 `description` 可能含引号。
     */
    fun string(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(json) ?: return null
        return unescape(m.groupValues[1])
    }

    /**
     * 取数字字段（兼容字符串形态的数字）。
     *
     * ⚠️ 第三方接口的字段类型不稳定 —— 同一个 `cid` 可能是
     * `29181414442`（数字）也可能是 `"29181414442"`（字符串）。
     * 这也是本项目**不用强类型反序列化**的原因（AGENTS.md §3.2）。
     */
    fun long(json: String?, key: String): Long? {
        if (json.isNullOrBlank()) return null
        val num = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(json)
        if (num != null) return num.groupValues[1].toLongOrNull()
        return string(json, key)?.toLongOrNull()
    }

    /** 取整数字段。 */
    fun int(json: String?, key: String): Int? = long(json, key)?.toInt()

    /** 取浮点字段（时间点用）。同样兼容字符串形态。 */
    fun double(json: String?, key: String): Double? {
        if (json.isNullOrBlank()) return null
        val num = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(json)
        if (num != null) return num.groupValues[1].toDoubleOrNull()
        return string(json, key)?.toDoubleOrNull()
    }

    /**
     * 取数组字段的原始文本（形如 `[...]`）。
     *
     * 用于 `"segment":[464.631,526.993]` 这类数值数组。
     */
    fun arrayText(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        val keyIdx = json.indexOf("\"$key\"")
        if (keyIdx < 0) return null
        val colon = json.indexOf(':', keyIdx + key.length + 2)
        if (colon < 0) return null
        val open = json.indexOf('[', colon)
        if (open < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until json.length) {
            val c = json[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '[' -> depth++
                !inString && c == ']' -> {
                    depth--
                    if (depth == 0) return json.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /**
     * 取对象字段的原始文本（形如 `{...}`）。
     *
     * 用于从分组数组里取出 `segments` 数组所在的上下文。
     */
    fun objectText(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        val keyIdx = json.indexOf("\"$key\"")
        if (keyIdx < 0) return null
        val colon = json.indexOf(':', keyIdx + key.length + 2)
        if (colon < 0) return null
        val open = json.indexOf('{', colon)
        if (open < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until json.length) {
            val c = json[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return json.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /**
     * 把数值数组文本解析成 Double 列表。
     *
     * `[464.631,526.993]` → `[464.631, 526.993]`
     */
    fun doubleArray(arrayText: String?): List<Double> =
        elements(arrayText).mapNotNull { it.trim().toDoubleOrNull() }

    /** 反转义 JSON 字符串字面量。 */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                sb.append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                // \uXXXX 保持原样（本项目用不到，不引入解码复杂度）
                else -> {
                    sb.append('\\').append(n)
                }
            }
            i += 2
        }
        return sb.toString()
    }
}
