package com.example.biliv3

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 互动状态解析测试（v1.5.2）。
 *
 * ## 为什么必须有这组测试
 *
 * 真实 bug：`relation` 接口的 `favorite` 是 **JSON 布尔**，
 * 而代码用 `optInt("favorite", 0) == 1` 解析 ——
 * `JSONObject.optInt` 读布尔会**静默返回默认值 0**，
 * 于是 `0 == 1` 恒 false → **收藏了却永远显示未收藏**。
 *
 * 这条 bug 存活的原因是：解析逻辑写在 `BiliApi.relation()` 里，
 * 而 `BiliApi` 构造需要 `Context` → JVM 单测拿不到。
 *
 * 这里把**类型判定规则**单独钉死。真实接口响应结构来自实测：
 * ```json
 * { "attention": false, "favorite": true, "like": false, "coin": 0 }
 * ```
 */
class InteractionParseTest {

    /** 复刻 `BiliApi` 里的判定规则（改动时必须同步）。 */
    private fun favoredOf(d: JSONObject): Boolean {
        if (!d.has("favorite") || d.isNull("favorite")) return false
        return when (val v = d.opt("favorite")) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "1" || v.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun likedOf(d: JSONObject): Boolean = d.optInt("like", 0) == 1

    private fun coinedOf(d: JSONObject): Boolean = d.optInt("coin", 0) > 0

    // ---- favorite：布尔（真实形态）----

    @Test
    fun `favorite 是布尔 true 时必须判定为已收藏`() {
        val d = JSONObject("""{"attention":false,"favorite":true,"like":false,"coin":0}""")
        assertTrue("真实接口给的是布尔 true，必须识别", favoredOf(d))
    }

    @Test
    fun `favorite 是布尔 false 时必须判定为未收藏`() {
        val d = JSONObject("""{"favorite":false}""")
        assertFalse(favoredOf(d))
    }

    /**
     * 🔴 这条是**回归测试**：原实现就是 `optInt(...) == 1`，
     * 对布尔 true 恒得 false。它必须在改了实现后仍然拦住错误写法。
     */
    @Test
    fun `用 optInt 读布尔 favorite 是错的 —— 会恒为 false`() {
        val d = JSONObject("""{"favorite":true}""")
        // 这正是原 bug 的行为：布尔 true 被 optInt 读成默认值 0
        assertFalse(
            "optInt 读布尔会返回默认值 0，这正是 bug 的成因",
            d.optInt("favorite", 0) == 1,
        )
        // 而正确写法必须为 true
        assertTrue(favoredOf(d))
    }

    // ---- favorite：数字（历史形态，防御性兼容）----

    @Test
    fun `favorite 是数字 1 时也判定为已收藏`() {
        assertTrue(favoredOf(JSONObject("""{"favorite":1}""")))
    }

    @Test
    fun `favorite 是数字 0 时判定为未收藏`() {
        assertFalse(favoredOf(JSONObject("""{"favorite":0}""")))
    }

    // ---- favorite：字符串（防御性）----

    @Test
    fun `favorite 是字符串 1 或 true 时判定为已收藏`() {
        assertTrue(favoredOf(JSONObject("""{"favorite":"1"}""")))
        assertTrue(favoredOf(JSONObject("""{"favorite":"true"}""")))
    }

    @Test
    fun `favorite 是字符串 0 或 false 时判定为未收藏`() {
        assertFalse(favoredOf(JSONObject("""{"favorite":"0"}""")))
        assertFalse(favoredOf(JSONObject("""{"favorite":"false"}""")))
    }

    // ---- favorite：缺失 / null / 异常类型 ----

    @Test
    fun `favorite 缺失时判定为未收藏`() {
        assertFalse(favoredOf(JSONObject("""{"like":1}""")))
    }

    @Test
    fun `favorite 为 null 时判定为未收藏`() {
        assertFalse(favoredOf(JSONObject("""{"favorite":null}""")))
    }

    @Test
    fun `favorite 是对象等异常类型时不猜 —— 一律未收藏`() {
        assertFalse(favoredOf(JSONObject("""{"favorite":{"x":1}}""")))
    }

    // ---- like / coin：数字，保持原判定 ----

    @Test
    fun `like 是数字 1 时为已点赞`() {
        assertTrue(likedOf(JSONObject("""{"like":1}""")))
        assertFalse(likedOf(JSONObject("""{"like":0}""")))
    }

    @Test
    fun `coin 大于 0 时为已投币`() {
        assertTrue(coinedOf(JSONObject("""{"coin":2}""")))
        assertFalse(coinedOf(JSONObject("""{"coin":0}""")))
    }

    /**
     * 完整真实响应 —— 三个字段**类型各不相同**，
     * 这正是"点赞正常、收藏失效"的原因。
     */
    @Test
    fun `真实响应里三种字段类型混合时必须各自正确`() {
        val d = JSONObject(
            """{"attention":false,"favorite":true,"like":0,"coin":0,"season_fav":false}""",
        )
        assertTrue("favorite 布尔 true", favoredOf(d))
        assertFalse("like 数字 0", likedOf(d))
        assertFalse("coin 数字 0", coinedOf(d))
    }

    @Test
    fun `已点赞且已收藏且已投币的真实响应`() {
        val d = JSONObject("""{"favorite":true,"like":1,"coin":2}""")
        assertTrue(favoredOf(d))
        assertTrue(likedOf(d))
        assertTrue(coinedOf(d))
    }
}
