package com.example.biliv3

import com.example.biliv3.data.emote.Emote
import com.example.biliv3.data.emote.EmoteParser
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * 表情面板解析测试（v1.6.8）。
 *
 * ## 样本来源
 *
 * 下面的 JSON 是**模拟器实测抓到的真实响应**（真实登录态，
 * `x/emote/user/panel?business=reply`）。
 *
 * 实测结构（未登录时 `packages=null`，所以**必须在登录态抓**）：
 * ```
 * data.packages = JSONArray（5 个包 / 431 个表情）
 *   type 分布：1=图片 359 / 4=颜文字 52 / 3=收藏集 20
 *   flags.no_access=true 的有 20 个（收藏集，**不可发送**）
 *   text 以 [ 开头的有 379 个；url == text（纯颜文字）的有 52 个
 * ```
 *
 * ## 为什么这组测试重要
 *
 * 🔴 **发送时必须用 `text` 原文（官方 token），不是表情名。**
 * 插错的话发出去就是普通文字 —— 任务书明确禁止这一点。
 * 这条测试把"token 从哪来"钉死。
 */
class EmoteParserTest {

    // ---------------- 真实响应样本 ----------------

    /** 图片表情（type=1，实测 359 个里的第一个）。 */
    private val imageEmote = """
        {"id":83964,"package_id":1,"text":"[doge_金箍]",
         "url":"https://i0.hdslb.com/bfs/emote/aadaca.png",
         "mtime":1724910828,"type":1,"attr":0,
         "meta":{"size":1,"suggest":[""],"alias":"金箍"},
         "flags":{"unlocked":false},"activity":null}
    """.trimIndent()

    /** 颜文字（type=4，`url == text`）。 */
    private val kaomojiEmote = """
        {"id":2185,"package_id":4,"text":"( ゜- ゜)つロ",
         "url":"( ゜- ゜)つロ","mtime":1626263287,"type":4,"attr":0,
         "meta":{"size":1,"suggest":[""],"alias":"( ゜- ゜)つロ"},
         "flags":{"unlocked":false},"activity":null}
    """.trimIndent()

    /** 未解锁收藏集（type=3 + `no_access`）。 */
    private val lockedEmote = """
        {"id":32335,"package_id":1858,"text":"[2233·群星闪耀时收藏集表情包_达咩]",
         "url":"https://i0.hdslb.com/bfs/garb/item/afbafcb8.png",
         "mtime":1686194006,"type":3,"attr":0,
         "meta":{"size":2,"suggest":[""],"alias":"达咩"},
         "flags":{"no_access":true,"unlocked":false},"activity":null}
    """.trimIndent()

    private fun panel(vararg emotesJson: String): JSONObject = JSONObject(
        """{"code":0,"data":{"packages":[
             {"id":1,"text":"小黄脸","emote":[${emotesJson.joinToString(",")}]}
           ]}}""",
    )

    // ---------------- token：发送用的唯一依据 ----------------

    /**
     * 🔴 **回归测试**：`token` 必须是 `text` 原文（官方 token）。
     *
     * 发送时用它；用表情名（`金箍`）发出去只是普通文字。
     */
    @Test
    fun `token 是官方方括号格式`() {
        val pkgs = EmoteParser.parse(panel(imageEmote))
        val e = pkgs.first().emotes.first()
        assertThat(e.token).isEqualTo("[doge_金箍]")
        // 不是别名
        assertThat(e.token).isNotEqualTo("金箍")
        // 确实带方括号
        assertThat(e.token).startsWith("[")
        assertThat(e.token).endsWith("]")
    }

    /** 别名单独存（用于搜索），不混进 token。 */
    @Test
    fun `别名单独保存`() {
        val e = EmoteParser.parse(panel(imageEmote)).first().emotes.first()
        assertThat(e.alias).isEqualTo("金箍")
    }

    // ---------------- 三类表情 ----------------

    /** 图片表情：`isImage` 为真，url 是 http 地址。 */
    @Test
    fun `图片表情可渲染`() {
        val e = EmoteParser.parse(panel(imageEmote)).first().emotes.first()
        assertThat(e.type).isEqualTo(Emote.TYPE_IMAGE)
        assertThat(e.isImage).isTrue()
        assertThat(e.url).startsWith("https://")
    }

    /**
     * 颜文字：**不能当图片加载**（它的 url 就是文字本身，加载会 404）。
     */
    @Test
    fun `颜文字不当图片`() {
        val e = EmoteParser.parse(panel(kaomojiEmote)).first().emotes.first()
        assertThat(e.type).isEqualTo(Emote.TYPE_KAOMOJI)
        assertThat(e.isImage).isFalse()
        assertThat(e.url).isEqualTo(e.token)
    }

    /**
     * 🔴 未解锁收藏集必须标 `usable = false` ——
     * 否则用户点了会发送失败（服务端拒），而 UI 却给了入口。
     */
    @Test
    fun `未解锁收藏集不可用`() {
        val e = EmoteParser.parse(panel(lockedEmote)).first().emotes.first()
        assertThat(e.usable).isFalse()
    }

    /** 普通表情默认可发送。 */
    @Test
    fun `普通表情可用`() {
        val e = EmoteParser.parse(panel(imageEmote)).first().emotes.first()
        assertThat(e.usable).isTrue()
    }

    // ---------------- 包 ----------------

    /** 包名取自 `text`。 */
    @Test
    fun `解析包名`() {
        val pkg = EmoteParser.parse(panel(imageEmote)).first()
        assertThat(pkg.name).isEqualTo("小黄脸")
        assertThat(pkg.id).isEqualTo(1L)
    }

    /** 混合三类时都要解析出来（不能因为一类不认识就丢整包）。 */
    @Test
    fun `混合类型全解析`() {
        val pkgs = EmoteParser.parse(panel(imageEmote, kaomojiEmote, lockedEmote))
        assertThat(pkgs).hasSize(1)
        assertThat(pkgs.first().emotes).hasSize(3)
        assertThat(pkgs.first().emotes.map { it.type })
            .containsExactly(Emote.TYPE_IMAGE, Emote.TYPE_KAOMOJI, Emote.TYPE_COLLECTION)
    }

    /** `usableCount` 剔除不可用的。 */
    @Test
    fun `可用计数剔除未解锁`() {
        val pkg = EmoteParser.parse(panel(imageEmote, lockedEmote)).first()
        assertThat(pkg.emotes).hasSize(2)
        assertThat(pkg.usableCount).isEqualTo(1)
    }

    // ---------------- 容错 ----------------

    /** 未登录：`packages = null` → 空列表（调用方据此显示"登录后可用"）。 */
    @Test
    fun `未登录返回空列表`() {
        val json = JSONObject("""{"code":0,"data":{"packages":null}}""")
        assertThat(EmoteParser.parse(json)).isEmpty()
    }

    /** 各种损坏输入不抛异常。 */
    @Test
    fun `损坏输入不抛`() {
        assertThat(EmoteParser.parse(null)).isEmpty()
        assertThat(EmoteParser.parse(JSONObject("{}"))).isEmpty()
        assertThat(EmoteParser.parse(JSONObject("""{"data":{}}"""))).isEmpty()
        assertThat(EmoteParser.parse(JSONObject("""{"data":{"packages":[]}}"""))).isEmpty()
    }

    /**
     * `text` 为空的条目要丢弃 ——
     * 空 token 发出去是空消息，且在面板里占一个点不动的格子。
     */
    @Test
    fun `丢弃空 token`() {
        val empty = """{"id":1,"text":"","url":"https://x.png","type":1}"""
        val pkgs = EmoteParser.parse(panel(empty))
        // 整包只有空 token → 包也被丢弃（空包不显示）
        assertThat(pkgs).isEmpty()
    }

    /** 空包不显示（一个点不开的分组是噪音）。 */
    @Test
    fun `空包被丢弃`() {
        val json = JSONObject(
            """{"code":0,"data":{"packages":[
                 {"id":1,"text":"空包","emote":[]},
                 {"id":2,"text":"有内容","emote":[$imageEmote]}
               ]}}""",
        )
        val pkgs = EmoteParser.parse(json)
        assertThat(pkgs).hasSize(1)
        assertThat(pkgs.first().name).isEqualTo("有内容")
    }

    /** 缺 `emote` 字段的包不崩。 */
    @Test
    fun `缺 emote 字段不崩`() {
        val json = JSONObject("""{"code":0,"data":{"packages":[{"id":1,"text":"x"}]}}""")
        assertThat(EmoteParser.parse(json)).isEmpty()
    }

    /** 缺 `flags` 时默认可用（不能因为字段缺失就把表情全禁掉）。 */
    @Test
    fun `缺 flags 默认可发送`() {
        val noFlags = """{"id":1,"text":"[ok]","url":"https://x.png","type":1}"""
        val e = EmoteParser.parse(panel(noFlags)).first().emotes.first()
        assertThat(e.usable).isTrue()
    }

    // ---------------- 搜索 ----------------

    /** 按 token 搜。 */
    @Test
    fun `按 token 搜索`() {
        val e = EmoteParser.parse(panel(imageEmote)).first().emotes.first()
        assertThat(e.matches("doge")).isTrue()
        assertThat(e.matches("金箍")).isTrue()
    }

    /** 按别名搜（用户往往只记得图，不记得 token）。 */
    @Test
    fun `按别名搜索`() {
        val e = EmoteParser.parse(panel(imageEmote)).first().emotes.first()
        assertThat(e.alias).isEqualTo("金箍")
        assertThat(e.matches("金箍")).isTrue()
    }

    /** 按包名搜。 */
    @Test
    fun `按包名搜索`() {
        val e = EmoteParser.parse(panel(imageEmote)).first().emotes.first()
        assertThat(e.packageName).isEqualTo("小黄脸")
        assertThat(e.matches("小黄脸")).isTrue()
    }

    /** 空查询匹配一切（清空搜索框应显示全部）。 */
    @Test
    fun `空查询匹配全部`() {
        val e = EmoteParser.parse(panel(imageEmote)).first().emotes.first()
        assertThat(e.matches("")).isTrue()
    }

    /** 搜不到就是搜不到。 */
    @Test
    fun `搜不到返回 false`() {
        val e = EmoteParser.parse(panel(imageEmote)).first().emotes.first()
        assertThat(e.matches("zzzz不存在")).isFalse()
    }
}
