package com.example.biliv3

import com.example.biliv3.plugin.MatchOp
import com.example.biliv3.plugin.PluginPermission
import com.example.biliv3.plugin.RiskLevel
import com.example.biliv3.plugin.RuleAction
import com.example.biliv3.plugin.RuleCondition
import com.example.biliv3.plugin.RuleEngine
import com.example.biliv3.plugin.RuleField
import com.example.biliv3.plugin.RuleParser
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * 插件规则引擎与权限系统单测。
 *
 * ## 为什么这些必须测
 *
 * 规则引擎是**用户可写的输入**，是攻击面：
 * - 灾难性回溯正则能让 CPU 打满
 * - 深层嵌套能让栈溢出
 * - 拼错的字段名会让规则**静默失效**（用户以为生效了）
 *
 * 权限系统则是安全边界：没申请的权限必须真的拿不到。
 */
class PluginRuleTest {

    private fun engineOf(json: String): RuleEngine {
        val parsed = RuleParser.parse(JSONArray(json))
        return RuleEngine(parsed.rules)
    }

    private fun subject(
        title: String = "",
        author: String = "",
        category: String = "",
        tags: List<String> = emptyList(),
        folder: String = "",
        duration: Int = 0,
    ) = RuleEngine.Subject(title, author, category, tags, folder, duration)

    // ---------------- 基础匹配 ----------------

    @Test
    fun `简写字符串等于 equals 匹配`() {
        val e = engineOf("""[{"id":"r","match":{"author":"某UP"},"action":{"markOrganized":true}}]""")
        assertThat(e.evaluate(subject(author = "某UP"))).hasSize(1)
        assertThat(e.evaluate(subject(author = "别的UP"))).isEmpty()
    }

    @Test
    fun `等于匹配忽略大小写`() {
        val e = engineOf("""[{"id":"r","match":{"title":"Hello"},"action":{"markOrganized":true}}]""")
        assertThat(e.evaluate(subject(title = "hello"))).hasSize(1)
    }

    @Test
    fun `包含匹配`() {
        val e = engineOf(
            """[{"id":"r","match":{"title":{"contains":"教程"}},"action":{"markOrganized":true}}]""",
        )
        assertThat(e.evaluate(subject(title = "Kotlin 教程 第一期"))).hasSize(1)
        assertThat(e.evaluate(subject(title = "游戏实况"))).isEmpty()
    }

    @Test
    fun `正则匹配`() {
        val e = engineOf(
            """[{"id":"r","match":{"title":{"regex":"^第[0-9]+期"}},"action":{"markOrganized":true}}]""",
        )
        assertThat(e.evaluate(subject(title = "第12期 讲解"))).hasSize(1)
        assertThat(e.evaluate(subject(title = "讲解 第12期"))).isEmpty()
    }

    @Test
    fun `多字段是 AND 语义`() {
        val e = engineOf(
            """[{"id":"r","match":{"author":"UP","title":{"contains":"教程"}},"action":{"markOrganized":true}}]""",
        )
        assertThat(e.evaluate(subject(author = "UP", title = "教程"))).hasSize(1)
        // 只满足一个条件不该命中
        assertThat(e.evaluate(subject(author = "UP", title = "实况"))).isEmpty()
        assertThat(e.evaluate(subject(author = "别人", title = "教程"))).isEmpty()
    }

    @Test
    fun `标签字段任一命中即算命中`() {
        val e = engineOf("""[{"id":"r","match":{"tag":"音乐"},"action":{"markOrganized":true}}]""")
        assertThat(e.evaluate(subject(tags = listOf("音乐", "翻唱")))).hasSize(1)
        assertThat(e.evaluate(subject(tags = listOf("游戏")))).isEmpty()
    }

    @Test
    fun `无条件规则匹配所有`() {
        val e = engineOf("""[{"id":"r","action":{"addLocalTag":"全部"}}]""")
        assertThat(e.evaluate(subject(title = "任意"))).hasSize(1)
    }

    // ---------------- 时长范围 ----------------

    @Test
    fun `时长范围匹配`() {
        val e = engineOf(
            """[{"id":"r","match":{"duration":{"min":60,"max":600}},"action":{"markOrganized":true}}]""",
        )
        assertThat(e.evaluate(subject(duration = 300))).hasSize(1)
        assertThat(e.evaluate(subject(duration = 60))).hasSize(1)     // 闭区间
        assertThat(e.evaluate(subject(duration = 600))).hasSize(1)
        assertThat(e.evaluate(subject(duration = 59))).isEmpty()
        assertThat(e.evaluate(subject(duration = 601))).isEmpty()
    }

    @Test
    fun `只有下限的时长范围`() {
        val e = engineOf("""[{"id":"r","match":{"duration":{"min":3600}},"action":{"markOrganized":true}}]""")
        assertThat(e.evaluate(subject(duration = 7200))).hasSize(1)
        assertThat(e.evaluate(subject(duration = 60))).isEmpty()
    }

    // ---------------- 逻辑组合 ----------------

    @Test
    fun `or 组合任一命中`() {
        val e = engineOf(
            """[{"id":"r","match":{"or":[{"author":"A"},{"author":"B"}]},"action":{"markOrganized":true}}]""",
        )
        assertThat(e.evaluate(subject(author = "A"))).hasSize(1)
        assertThat(e.evaluate(subject(author = "B"))).hasSize(1)
        assertThat(e.evaluate(subject(author = "C"))).isEmpty()
    }

    // ---------------- 动作 ----------------

    @Test
    fun `多个动作按顺序执行`() {
        // 任务书 §2 提到 pakku 的"多条规则按顺序执行会影响最终结果"
        val e = engineOf(
            """[{"id":"r","match":{"author":"UP"},"action":[{"addLocalTag":"A"},{"addLocalTag":"B"}]}]""",
        )
        val actions = e.evaluate(subject(author = "UP"))
        assertThat(actions).hasSize(2)
        assertThat((actions[0] as RuleAction.AddLocalTag).tag).isEqualTo("A")
        assertThat((actions[1] as RuleAction.AddLocalTag).tag).isEqualTo("B")
    }

    @Test
    fun `移动收藏夹动作解析`() {
        val e = engineOf(
            """[{"id":"r","match":{"author":"UP"},"action":{"moveToFolder":12345}}]""",
        )
        val a = e.evaluate(subject(author = "UP")).single()
        assertThat((a as RuleAction.MoveToFolder).folderId).isEqualTo(12345L)
    }

    @Test
    fun `多条规则全部命中时动作累积`() {
        val e = engineOf(
            """
            [
              {"id":"r1","match":{"author":"UP"},"action":{"addLocalTag":"标签1"}},
              {"id":"r2","match":{"author":"UP"},"action":{"addLocalTag":"标签2"}}
            ]
            """.trimIndent(),
        )
        assertThat(e.evaluate(subject(author = "UP"))).hasSize(2)
    }

    // ---------------- 错误处理（关键） ----------------

    @Test
    fun `拼错的字段名会被报错而不是静默失效`() {
        // ⚠️ 这是最重要的用例之一：用户写 "autor"（拼错）时，
        // 若静默忽略，规则永远不匹配且没有任何反馈 —— 极难自查
        val parsed = RuleParser.parse(
            JSONArray("""[{"id":"r","match":{"autor":"UP"},"action":{"markOrganized":true}}]"""),
        )
        assertThat(parsed.errors).isNotEmpty()
        assertThat(parsed.errors.first()).contains("autor")
    }

    @Test
    fun `未知动作会被报错`() {
        val parsed = RuleParser.parse(
            JSONArray("""[{"id":"r","match":{"author":"UP"},"action":{"execShell":"rm -rf /"}}]"""),
        )
        assertThat(parsed.errors).isNotEmpty()
        // 而且不能产生任何动作
        assertThat(parsed.rules).isEmpty()
    }

    @Test
    fun `没有动作的规则被跳过并报错`() {
        val parsed = RuleParser.parse(
            JSONArray("""[{"id":"r","match":{"author":"UP"}}]"""),
        )
        assertThat(parsed.rules).isEmpty()
        assertThat(parsed.errors).isNotEmpty()
    }

    @Test
    fun `非法正则会报错且不影响其它规则`() {
        val e = engineOf(
            """
            [
              {"id":"bad","match":{"title":{"regex":"[unclosed"}},"action":{"addLocalTag":"x"}},
              {"id":"good","match":{"author":"UP"},"action":{"addLocalTag":"y"}}
            ]
            """.trimIndent(),
        )
        // 坏正则不匹配（不崩），好规则照常工作
        assertThat(e.evaluate(subject(title = "任意", author = "UP"))).hasSize(1)
    }

    @Test
    fun `缺少 rules 数组时报错`() {
        val parsed = RuleParser.parse(null)
        assertThat(parsed.rules).isEmpty()
        assertThat(parsed.errors).isNotEmpty()
    }

    @Test
    fun `空 rules 数组不报错但也没有规则`() {
        val parsed = RuleParser.parse(JSONArray("[]"))
        assertThat(parsed.rules).isEmpty()
        assertThat(parsed.errors).isEmpty()
    }

    // ---------------- 安全约束（关键） ----------------

    @Test
    fun `超长正则模式被拒绝执行`() {
        // 灾难性回溯的常见形态是长模式；超过上限直接不匹配
        val longPattern = "a".repeat(RuleEngine.MAX_REGEX_LEN + 1)
        val e = engineOf(
            """[{"id":"r","match":{"title":{"regex":"$longPattern"}},"action":{"markOrganized":true}}]""",
        )
        assertThat(e.evaluate(subject(title = "a".repeat(1000)))).isEmpty()
    }

    @Test
    fun `灾难性回溯正则不会挂住（模式长度受限）`() {
        // (a+)+$ 是经典的回溯爆炸模式。这里验证：
        // 1. 长输入被截断
        // 2. 有长度上限兜底
        // 若实现没做限制，这个测试会超时而不是失败
        val e = engineOf(
            """[{"id":"r","match":{"title":{"regex":"(a+)+$"}},"action":{"markOrganized":true}}]""",
        )
        val evil = "a".repeat(2000) + "b"
        val started = System.currentTimeMillis()
        e.evaluate(subject(title = evil))
        val cost = System.currentTimeMillis() - started
        // 截断到 500 字符后应快速返回（给 2 秒余量，正常在毫秒级）
        assertThat(cost).isLessThan(2000L)
    }

    @Test
    fun `深层嵌套不会栈溢出`() {
        // 构造超过 MAX_DEPTH 的嵌套
        val depth = RuleEngine.MAX_DEPTH + 10
        val sb = StringBuilder()
        repeat(depth) { sb.append("""{"and":[""") }
        sb.append("""{"author":"UP"}""")
        repeat(depth) { sb.append("]}") }
        val parsed = RuleParser.parse(
            JSONArray("""[{"id":"r","match":$sb,"action":{"markOrganized":true}}]"""),
        )
        // 不崩即可（深度超限时返回 false）
        RuleEngine(parsed.rules).evaluate(subject(author = "UP"))
    }

    @Test
    fun `超长标题被截断参与匹配`() {
        // 保证超长输入不会拖慢匹配
        val e = engineOf(
            """[{"id":"r","match":{"title":{"contains":"目标"}},"action":{"markOrganized":true}}]""",
        )
        val long = "x".repeat(10_000) + "目标"
        // "目标" 在截断范围之外 → 不匹配（这是可接受的取舍：
        // 正常标题不会超过 500 字符）
        assertThat(e.evaluate(subject(title = long))).isEmpty()
    }

    @Test
    fun `规则条数上限常量存在且合理`() {
        assertThat(RuleEngine.MAX_RULES).isAtMost(1000)
    }

    // ---------------- 权限系统 ----------------

    @Test
    fun `权限从字符串解析忽略大小写`() {
        assertThat(PluginPermission.from("player_read"))
            .isEqualTo(PluginPermission.PLAYER_READ)
        assertThat(PluginPermission.from("PLAYER_READ"))
            .isEqualTo(PluginPermission.PLAYER_READ)
    }

    @Test
    fun `未知权限返回 null`() {
        assertThat(PluginPermission.from("SUPER_USER")).isNull()
        assertThat(PluginPermission.from("")).isNull()
    }

    @Test
    fun `写类权限的风险等级高于读类`() {
        // 安装页要按风险等级提示用户 —— 顺序错了提示就失去意义
        assertThat(PluginPermission.FAVORITES_WRITE.level)
            .isEqualTo(RiskLevel.HIGH)
        assertThat(PluginPermission.FAVORITES_READ.level)
            .isEqualTo(RiskLevel.MEDIUM)
        assertThat(PluginPermission.PLAYER_READ.level)
            .isEqualTo(RiskLevel.LOW)
    }

    @Test
    fun `网络与收藏写入是高风险`() {
        // 这两类直接关系到账号安全与数据外泄
        assertThat(PluginPermission.NETWORK.level).isEqualTo(RiskLevel.HIGH)
        assertThat(PluginPermission.FAVORITES_WRITE.level).isEqualTo(RiskLevel.HIGH)
    }

    @Test
    fun `每个权限都有展示名与说明`() {
        // 空说明会让安装页出现"这个插件需要：________"的空白
        PluginPermission.entries.forEach { p ->
            assertThat(p.displayName).isNotEmpty()
            assertThat(p.description).isNotEmpty()
        }
    }

    // ---------------- 字段与运算符枚举 ----------------

    @Test
    fun `字段名解析`() {
        assertThat(RuleField.from("title")).isEqualTo(RuleField.TITLE)
        assertThat(RuleField.from("AUTHOR")).isEqualTo(RuleField.AUTHOR)
        assertThat(RuleField.from("nonsense")).isNull()
    }

    @Test
    fun `运算符名解析`() {
        assertThat(MatchOp.from("contains")).isEqualTo(MatchOp.CONTAINS)
        assertThat(MatchOp.from("STARTSWITH")).isEqualTo(MatchOp.STARTS_WITH)
        assertThat(MatchOp.from("eval")).isNull()   // 不存在"求值"运算符
    }

    @Test
    fun `条件与动作都是密封类型不能塞任意 JSON`() {
        // 编译期保证：RuleCondition / RuleAction 的所有子类型都是固定的。
        // 这里用一次实例化表达"只有这些形态"
        val c: RuleCondition = RuleCondition.Field(RuleField.TITLE, MatchOp.EQUALS, "x")
        val a: RuleAction = RuleAction.AddLocalTag("t")
        assertThat(c).isNotNull()
        assertThat(a).isNotNull()
    }
}
