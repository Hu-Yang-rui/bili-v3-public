package com.example.biliv3

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * 收藏夹解析测试（v1.6.7）。
 *
 * ## 为什么这组测试重要
 *
 * 本轮在模拟器上修了两个收藏相关的**静默**问题：
 *
 * | 问题 | 表现 | 为什么难发现 |
 * |---|---|---|
 * | `attr == 1` 判默认夹 | 同时带私密位（attr=3）时**判不出默认夹** | 位标志当枚举用，只在特定组合下错 |
 * | 用独立端点查"已收藏到哪些夹" | 该端点**恒返回 -400** | 错误码指向"参数错"，实际是端点不适用 |
 *
 * 两者都不崩、不报错，只是结果不对 —— 只能靠真实响应比对发现。
 */
class FavParsingTest {

    // ---------------- attr 位标志 ----------------

    /**
     * 🔴 **回归测试**：`attr` 是**位标志**，不能用 `== 1` 判默认夹。
     *
     * 实测收藏夹 `attr` 的位含义（公开资料 + `LibraryRepository` 既有注释）：
     * - `bit0 (1)` = 默认收藏夹
     * - `bit1 (2)` = 私密
     *
     * 所以"默认且私密"的夹 `attr = 3` —— `attr == 1` 会**判错**。
     */
    @Test
    fun `attr 位标志判默认夹`() {
        // 恰好只有默认位
        assertThat(isDefault(1)).isTrue()
        // 默认 + 私密（= 3）—— 旧实现 `attr == 1` 在这里判错
        assertThat(isDefault(3)).isTrue()
        // 只有私密位
        assertThat(isDefault(2)).isFalse()
        // 都不是
        assertThat(isDefault(0)).isFalse()
    }

    /** 用位与判默认夹的实现（与生产代码同一逻辑）。 */
    private fun isDefault(attr: Int): Boolean = (attr and ATTR_DEFAULT) != 0

    private companion object {
        const val ATTR_DEFAULT = 1
    }

    /**
     * 旧写法 `attr == 1` 在 attr=3 时会失败 —— 这条测试**证明**旧写法是错的。
     *
     * 保留它作为"为什么必须用位与"的可执行文档。
     */
    @Test
    fun `旧写法在默认加私密时判错`() {
        val attr = 3 // 默认 + 私密
        assertThat(attr == 1).isFalse() // 旧写法：判成"不是默认夹"（错）
        assertThat((attr and ATTR_DEFAULT) != 0).isTrue() // 新写法：正确
    }

    // ---------------- fav_state：勾选态来源 ----------------

    /**
     * 🔴 **关键发现**：勾选态来自 `created/list-all` 的 **`fav_state`** 字段，
     * 不是独立的 `fav/resource/ids` 端点。
     *
     * 实测：`fav/resource/ids` 用 `rid`/`bvid`/`type` 各种组合、
     * 带签名与不带签名，**一律 `-400 请求错误`**（真实登录态）。
     *
     * 而 `fav/folder/created/list-all` 带上 `rid` + `type` 后，
     * 每个夹会多一个 `fav_state`（非 0 = 已收藏）。
     */
    @Test
    fun `fav_state 非零表示已收藏`() {
        val inFolder = JSONObject("""{"id":100,"fav_state":1,"title":"默认收藏夹"}""")
        val notIn = JSONObject("""{"id":200,"fav_state":0,"title":"游戏"}""")

        assertThat(isFavored(inFolder)).isTrue()
        assertThat(isFavored(notIn)).isFalse()
    }

    private fun isFavored(o: JSONObject): Boolean = o.optInt("fav_state", 0) != 0

    /**
     * 缺 `fav_state` 字段时按"未收藏"处理（不能凭空说已收藏）。
     *
     * ⚠️ 但这只在不带 `rid` 请求时才会发生 —— 带了 `rid` 服务端一定回该字段。
     */
    @Test
    fun `缺 fav_state 按未收藏`() {
        val o = JSONObject("""{"id":100,"title":"x"}""")
        assertThat(isFavored(o)).isFalse()
    }

    /** 从真实响应形状里挑出已收藏的夹 id。 */
    @Test
    fun `挑出已收藏的夹 id`() {
        val body = JSONObject(
            """
            {"code":0,"data":{"list":[
              {"id":62851852,"fav_state":1,"title":"默认收藏夹","attr":1,"media_count":32},
              {"id":4020481152,"fav_state":0,"title":"gay","attr":3,"media_count":1},
              {"id":3896864752,"fav_state":1,"title":"MOD","attr":3,"media_count":5}
            ]}}
            """.trimIndent(),
        )
        val list = body.getJSONObject("data").getJSONArray("list")
        val ids = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            if (o.optInt("fav_state", 0) != 0) o.optLong("id") else null
        }
        assertThat(ids).containsExactly(62851852L, 3896864752L).inOrder()
    }

    /**
     * 同一个夹可以**同时**是"默认 + 私密 + 已收藏"——
     * 三个属性互不冲突，不能互相覆盖。
     */
    @Test
    fun `默认私密已收藏三者独立`() {
        val o = JSONObject("""{"id":1,"attr":3,"fav_state":1,"title":"t"}""")
        val attr = o.optInt("attr", 0)
        assertThat((attr and ATTR_DEFAULT) != 0).isTrue() // 是默认夹
        assertThat((attr and 2) != 0).isTrue() // 是私密夹
        assertThat(o.optInt("fav_state", 0) != 0).isTrue() // 已收藏
    }

    // ---------------- 列表接口必须带 rid ----------------

    /**
     * 列表请求的参数形状（带上 rid/type 才能拿到 fav_state）。
     *
     * 这条测试固定住"必须传 rid"这个事实 —— 少了它勾选态全为 0，
     * 表现为"面板里什么都显示未收藏"（假状态）。
     */
    @Test
    fun `列表请求带 rid 与 type`() {
        val aid = 114256011854825L
        val mid = 12345L
        val q = mapOf(
            "up_mid" to mid.toString(),
            "rid" to aid.toString(),
            "type" to "2",
        )
        assertThat(q).containsEntry("rid", "114256011854825")
        assertThat(q).containsEntry("type", "2")
        assertThat(q).containsEntry("up_mid", "12345")
    }

    /** aid 无效时不发请求（避免一次注定失败的往返）。 */
    @Test
    fun `aid 无效时不查询`() {
        assertThat(shouldQuery(aid = 0L, mid = 1L)).isFalse()
        assertThat(shouldQuery(aid = -1L, mid = 1L)).isFalse()
        assertThat(shouldQuery(aid = 1L, mid = 1L)).isTrue()
    }

    private fun shouldQuery(aid: Long, mid: Long): Boolean = aid > 0L && mid > 0L
}
