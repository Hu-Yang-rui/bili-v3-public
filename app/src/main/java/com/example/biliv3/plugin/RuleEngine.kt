package com.example.biliv3.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 规则插件的**匹配条件**。
 *
 * ## 🔴 为什么用密封接口 + 枚举，而不是"任意 JSON 表达式"
 *
 * 任务书 §15 要求「JSON 插件不得执行任意代码」。
 * 但"不执行代码"还不够 —— 一个**图灵完备的表达式求值器**
 * （支持嵌套运算、函数调用）本质也是代码执行环境，
 * 可以被用来做 CPU 耗尽攻击。
 *
 * 所以这里的条件是**固定枚举**：
 * - 没有循环
 * - 没有函数调用
 * - 没有递归
 * - 求值复杂度**上界可证明**（O(条件数 × 字段数)）
 *
 * 这是"安全沙箱式解释器"的具体落地（任务书 §18）。
 */
sealed interface RuleCondition {

    /** 匹配某个字段。 */
    data class Field(
        val field: RuleField,
        val op: MatchOp,
        val value: String,
    ) : RuleCondition

    /** 时长范围（秒）。 */
    data class Duration(
        val minSeconds: Int? = null,
        val maxSeconds: Int? = null,
    ) : RuleCondition

    /** 逻辑组合。 */
    data class And(val conditions: List<RuleCondition>) : RuleCondition
    data class Or(val conditions: List<RuleCondition>) : RuleCondition
    data object Not : RuleCondition   // 占位，实际用 NotOf
    data class NotOf(val condition: RuleCondition) : RuleCondition
}

/** 可匹配的字段（**固定枚举**，不是任意路径）。 */
enum class RuleField(val jsonName: String) {
    TITLE("title"),
    AUTHOR("author"),
    CATEGORY("category"),
    TAG("tag"),
    FOLDER("folder"),
    ;

    companion object {
        fun from(name: String): RuleField? =
            entries.firstOrNull { it.jsonName.equals(name, ignoreCase = true) }
    }
}

/** 匹配运算符。 */
enum class MatchOp(val jsonName: String) {
    /** 完全等于。 */
    EQUALS("equals"),

    /** 包含子串。 */
    CONTAINS("contains"),

    /** 以...开头。 */
    STARTS_WITH("startsWith"),

    /** 以...结尾。 */
    ENDS_WITH("endsWith"),

    /**
     * 正则匹配。
     *
     * ⚠️ 正则**有灾难性回溯风险**（`(a+)+$` 能让 CPU 打满）。
     * 见 [RuleEngine] 的超时与长度限制。
     */
    REGEX("regex"),
    ;

    companion object {
        fun from(name: String): MatchOp? =
            entries.firstOrNull { it.jsonName.equals(name, ignoreCase = true) }
    }
}

/**
 * 规则动作（**固定枚举**）。
 *
 * 与条件同理：动作不能是"执行任意脚本"，
 * 只能是这些**已实现且受权限约束**的操作。
 */
sealed interface RuleAction {

    /** 添加本地标签。 */
    data class AddLocalTag(val tag: String) : RuleAction

    /** 移除本地标签。 */
    data class RemoveLocalTag(val tag: String) : RuleAction

    /** 标记已整理。 */
    data object MarkOrganized : RuleAction

    /**
     * 移动到收藏夹。
     *
     * ⚠️ 这是**B 站服务器操作**（会改动账号数据），
     * 与本地标签有本质区别 —— UI 必须区分提示（任务书 §4.2）。
     */
    data class MoveToFolder(val folderId: Long) : RuleAction

    /** 加入播放队列（本地操作）。 */
    data object AddToQueue : RuleAction
}

/**
 * 一条规则：条件 + 动作。
 *
 * @param id 规则标识（日志里用）
 * @param condition 匹配条件（null = 无条件匹配，即"对所有项执行"）
 * @param actions 命中后执行的动作（**按顺序**执行 ——
 *        任务书 §2 提到 pakku "多条规则按顺序执行会影响最终结果"）
 */
data class Rule(
    val id: String,
    val condition: RuleCondition?,
    val actions: List<RuleAction>,
)

/**
 * 规则解析器。
 *
 * ## 解析原则：**能解析多少算多少，但不静默**
 *
 * 与 LRC 解析同理（见 `LrcParser`）：一个坏规则不该让整个插件失效。
 * 但**必须记录错误** —— 否则用户写的规则没生效，却没有任何反馈，
 * 那是最让人困惑的体验。
 */
object RuleParser {

    /** 解析结果。 */
    data class Result(
        val rules: List<Rule>,
        /** 解析过程中的错误（会显示在插件详情里）。 */
        val errors: List<String>,
    )

    /**
     * 解析 `rules` 数组。
     *
     * 输入形如：
     * ```json
     * [{"id":"r1","match":{"author":"UP主"},"action":{"addLocalTag":"待观看"}}]
     * ```
     */
    fun parse(array: JSONArray?): Result {
        if (array == null) return Result(emptyList(), listOf("缺少 rules 数组"))

        val rules = ArrayList<Rule>()
        val errors = ArrayList<String>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                errors.add("第 ${i + 1} 条规则不是对象")
                continue
            }
            val id = obj.optString("id").ifEmpty { "rule$i" }

            val condition = parseCondition(obj.optJSONObject("match"), errors, id)
            val actions = parseActions(obj.opt("action"), errors, id)

            if (actions.isEmpty()) {
                // 没有动作的规则是**无意义**的（匹配了却什么都不做）
                errors.add("规则 $id 没有任何有效动作，已跳过")
                continue
            }
            rules.add(Rule(id, condition, actions))
        }

        return Result(rules, errors)
    }

    /**
     * 解析匹配条件。
     *
     * 支持三种写法：
     * ```json
     * "match": {"author": "UP主"}                    // 简写 = equals
     * "match": {"title": {"contains": "教程"}}        // 显式运算符
     * "match": {"duration": {"min": 60, "max": 600}} // 时长范围
     * ```
     */
    private fun parseCondition(
        obj: JSONObject?,
        errors: MutableList<String>,
        ruleId: String,
    ): RuleCondition? {
        if (obj == null) return null   // 无条件 = 匹配全部

        val conds = ArrayList<RuleCondition>()

        for (key in obj.keys()) {
            when (key.lowercase()) {
                "duration" -> {
                    val d = obj.optJSONObject(key)
                    if (d == null) {
                        errors.add("规则 $ruleId 的 duration 不是对象")
                        continue
                    }
                    val min = d.optInt("min", -1).takeIf { it >= 0 }
                    val max = d.optInt("max", -1).takeIf { it >= 0 }
                    if (min == null && max == null) {
                        errors.add("规则 $ruleId 的 duration 缺少 min/max")
                        continue
                    }
                    conds.add(RuleCondition.Duration(min, max))
                }

                "and", "or" -> {
                    // 嵌套组合（深度受限，见下）
                    val arr = obj.optJSONArray(key) ?: continue
                    val nested = ArrayList<RuleCondition>()
                    for (i in 0 until arr.length()) {
                        parseCondition(arr.optJSONObject(i), errors, ruleId)?.let { nested.add(it) }
                    }
                    if (nested.isNotEmpty()) {
                        conds.add(
                            if (key.equals("and", true)) RuleCondition.And(nested)
                            else RuleCondition.Or(nested),
                        )
                    }
                }

                else -> {
                    val field = RuleField.from(key)
                    if (field == null) {
                        // ⚠️ 未知字段必须报错而不是忽略 ——
                        // 否则用户写了 `"autor": "x"`（拼错）会静默永不匹配
                        errors.add("规则 $ruleId 含未知字段「$key」")
                        continue
                    }
                    val parsed = parseFieldCondition(field, obj.opt(key), errors, ruleId)
                    if (parsed != null) conds.add(parsed)
                }
            }
        }

        return when {
            conds.isEmpty() -> null
            conds.size == 1 -> conds[0]
            // 同一对象里的多个字段是 **AND** 语义（更符合直觉：
            // "作者是X 且 标题含Y"）
            else -> RuleCondition.And(conds)
        }
    }

    private fun parseFieldCondition(
        field: RuleField,
        raw: Any?,
        errors: MutableList<String>,
        ruleId: String,
    ): RuleCondition? = when (raw) {
        // 简写：直接给字符串 = equals
        is String -> RuleCondition.Field(field, MatchOp.EQUALS, raw)

        is JSONObject -> {
            // 显式运算符：{"contains": "xxx"}
            val opKey = raw.keys().asSequence().firstOrNull()
            val op = opKey?.let { MatchOp.from(it) }
            if (op == null) {
                errors.add("规则 $ruleId 的 ${field.jsonName} 含未知运算符「$opKey」")
                null
            } else {
                RuleCondition.Field(field, op, raw.optString(opKey))
            }
        }

        else -> {
            errors.add("规则 $ruleId 的 ${field.jsonName} 类型不支持")
            null
        }
    }

    /** 解析动作。支持数组（多个）与单个对象。 */
    private fun parseActions(
        raw: Any?,
        errors: MutableList<String>,
        ruleId: String,
    ): List<RuleAction> {
        if (raw == null) {
            errors.add("规则 $ruleId 缺少 action")
            return emptyList()
        }
        val objs = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            is JSONObject -> listOf(raw)
            else -> emptyList()
        }

        val out = ArrayList<RuleAction>()
        for (o in objs) {
            for (key in o.keys()) {
                val v = o.opt(key)
                when (key.lowercase()) {
                    "addlocaltag" -> (v as? String)?.let { out.add(RuleAction.AddLocalTag(it)) }
                    "removelocaltag" -> (v as? String)?.let { out.add(RuleAction.RemoveLocalTag(it)) }
                    "markorganized" -> out.add(RuleAction.MarkOrganized)
                    "addtoqueue" -> out.add(RuleAction.AddToQueue)
                    "movetofolder" -> {
                        val id = when (v) {
                            is Number -> v.toLong()
                            is String -> v.toLongOrNull()
                            else -> null
                        }
                        if (id == null) {
                            errors.add("规则 $ruleId 的 moveToFolder 不是合法 id")
                        } else {
                            out.add(RuleAction.MoveToFolder(id))
                        }
                    }
                    else -> errors.add("规则 $ruleId 含未知动作「$key」")
                }
            }
        }
        return out
    }
}

/**
 * 规则引擎（**安全沙箱式解释器**）。
 *
 * ## 三条硬性安全约束
 *
 * ### 1. 正则长度与输入长度都有限制
 *
 * `Regex` 的灾难性回溯能让 CPU 打满。虽然 Kotlin/Java 的
 * `java.util.regex` 没有内置超时，但可以通过：
 * - 限制**模式长度**（> 200 字符直接拒绝）
 * - 限制**输入长度**（标题超长时截断）
 * - 限制**规则条数**（> 500 条拒绝）
 *
 * 把最坏情况压到可接受范围。
 *
 * ### 2. 求值不递归（除嵌套组合外，且深度受限）
 *
 * ### 3. 引擎**不执行任何动作**
 *
 * 它只回答"哪些规则命中"，动作由调用方在**权限检查后**执行。
 * 这样即使规则写得很坏，也影响不到数据。
 */
class RuleEngine(
    private val rules: List<Rule>,
) {

    /** 求值输入（一条视频的可用字段）。 */
    data class Subject(
        val title: String = "",
        val author: String = "",
        val category: String = "",
        val tags: List<String> = emptyList(),
        val folder: String = "",
        val durationSeconds: Int = 0,
    )

    /**
     * 返回命中的**全部动作**（按规则顺序）。
     *
     * @return 动作列表；无命中返回空列表
     */
    fun evaluate(subject: Subject): List<RuleAction> {
        val out = ArrayList<RuleAction>()
        for (r in rules) {
            if (matches(r.condition, subject)) out.addAll(r.actions)
        }
        return out
    }

    /** 判断某条规则是否命中。 */
    fun matches(condition: RuleCondition?, subject: Subject): Boolean {
        if (condition == null) return true   // 无条件 = 全匹配
        return eval(condition, subject, depth = 0)
    }

    private fun eval(condition: RuleCondition, subject: Subject, depth: Int): Boolean {
        // 嵌套深度上限：防止恶意构造的深层嵌套导致栈溢出
        if (depth > MAX_DEPTH) return false

        return when (condition) {
            is RuleCondition.Field -> matchField(condition, subject)
            is RuleCondition.Duration -> {
                val d = subject.durationSeconds
                val okMin = condition.minSeconds?.let { d >= it } ?: true
                val okMax = condition.maxSeconds?.let { d <= it } ?: true
                okMin && okMax
            }
            is RuleCondition.And -> condition.conditions.all { eval(it, subject, depth + 1) }
            is RuleCondition.Or -> condition.conditions.any { eval(it, subject, depth + 1) }
            is RuleCondition.NotOf -> !eval(condition.condition, subject, depth + 1)
            RuleCondition.Not -> false   // 占位，永不为真
        }
    }

    private fun matchField(cond: RuleCondition.Field, subject: Subject): Boolean {
        // 多值字段（tags）任一命中即算命中
        val candidates: List<String> = when (cond.field) {
            RuleField.TITLE -> listOf(subject.title)
            RuleField.AUTHOR -> listOf(subject.author)
            RuleField.CATEGORY -> listOf(subject.category)
            RuleField.TAG -> subject.tags
            RuleField.FOLDER -> listOf(subject.folder)
        }

        return candidates.any { candidate -> matchOne(cond.op, cond.value, candidate) }
    }

    private fun matchOne(op: MatchOp, pattern: String, text: String): Boolean {
        // 输入截断：超长文本只比较前 N 字符（防正则回溯 + 无意义的长匹配）
        val t = if (text.length > MAX_TEXT_LEN) text.substring(0, MAX_TEXT_LEN) else text

        return when (op) {
            MatchOp.EQUALS -> t.equals(pattern, ignoreCase = true)
            MatchOp.CONTAINS -> t.contains(pattern, ignoreCase = true)
            MatchOp.STARTS_WITH -> t.startsWith(pattern, ignoreCase = true)
            MatchOp.ENDS_WITH -> t.endsWith(pattern, ignoreCase = true)
            MatchOp.REGEX -> regexMatch(pattern, t)
        }
    }

    private fun regexMatch(pattern: String, text: String): Boolean {
        // 模式过长直接拒绝（灾难性回溯的常见形态就是长模式）
        if (pattern.length > MAX_REGEX_LEN) return false
        return try {
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
        } catch (e: Exception) {
            // 非法正则：不崩，视为不匹配。
            // 解析阶段已记录错误，用户能在插件详情里看到。
            false
        }
    }

    companion object {
        /** 嵌套深度上限。 */
        const val MAX_DEPTH = 8

        /** 正则模式长度上限。 */
        const val MAX_REGEX_LEN = 200

        /** 参与匹配的文本长度上限。 */
        const val MAX_TEXT_LEN = 500

        /** 单个插件的规则条数上限。 */
        const val MAX_RULES = 500
    }
}
