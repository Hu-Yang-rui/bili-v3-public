package com.example.biliv3.data.emote

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个表情（**未发版**）。
 *
 * ---
 *
 * # 🔴 `token` 是发送时**唯一**要用的东西
 *
 * 实测（真实登录态，`x/emote/user/panel?business=reply`）：
 *
 * ```json
 * {"id":83964,"package_id":1,"text":"[doge_金箍]",
 *  "url":"https://i0.hdslb.com/bfs/emote/....png",
 *  "type":1,"meta":{"size":1,"alias":"金箍"},"flags":{"unlocked":false}}
 * ```
 *
 * 发送时必须发 **`text` 原文**（`[doge_金箍]`）—— 这是 B 站识别表情的
 * **内联 token**，不是表情名（`金箍` 发出去只会是纯文字）。
 *
 * 任务书明确禁止「只把表情名称插入文本」—— 本条就是那条规则的判据。
 *
 * @param token 发送用的内联 token（**`text` 原样**）
 * @param url 图片地址；**颜文字类为空**（它本身就是文字）
 * @param type 实测取值见 [EmoteType]
 * @param alias 别名（`meta.alias`，用于搜索）
 * @param packageName 所属表情包名（用于分组标题）
 * @param usable 能否发送（`flags.no_access` 为 true 时**不能**）
 */
data class Emote(
    val token: String,
    val url: String = "",
    val type: Int = TYPE_IMAGE,
    val alias: String = "",
    val packageName: String = "",
    val usable: Boolean = true,
) {
    /**
     * 是否**图片**表情（需要渲染成图）。
     *
     * 颜文字（[TYPE_KAOMOJI]）的 `url` 与 `text` 相同，
     * 直接当文字显示即可 —— 不要试图把它当图片加载（会 404）。
     */
    val isImage: Boolean get() = type == TYPE_IMAGE && url.isNotEmpty()

    /**
     * 搜索匹配。
     *
     * 覆盖 token / 别名 / 包名 —— 用户可能按任一种方式找：
     * 记得图长什么样就搜别名，记得包就搜包名。
     */
    fun matches(query: String): Boolean {
        if (query.isEmpty()) return true
        return token.contains(query, ignoreCase = true) ||
            alias.contains(query, ignoreCase = true) ||
            packageName.contains(query, ignoreCase = true)
    }

    companion object {
        /** 图片表情（实测 359 个）。 */
        const val TYPE_IMAGE = 1

        /**
         * 收藏集表情（实测 20 个，**全部带 `no_access`**）。
         *
         * ⚠️ 这一类需要**购买收藏集**才能用 —— 未解锁时发送会被拒。
         */
        const val TYPE_COLLECTION = 3

        /** 颜文字（实测 52 个，`url == text`，纯文字）。 */
        const val TYPE_KAOMOJI = 4
    }
}

/**
 * 一个表情包（分组）。
 *
 * @param id 包 id
 * @param name 包名（实测：小黄脸 / 热词系列一 / tv_小电视 / 颜文字 / …）
 * @param emotes 包内表情
 */
data class EmotePackage(
    val id: Long,
    val name: String,
    val emotes: List<Emote>,
) {
    /** 可发送的表情数（`no_access` 的已剔除）。 */
    val usableCount: Int get() = emotes.count { it.usable }
}

/**
 * 表情面板解析（**纯函数**，可单测）。
 *
 * ---
 *
 * # 实测结构（2026-10-07，真实登录态）
 *
 * ```
 * GET x/emote/user/panel?business=reply
 * data.packages = JSONArray（5 个包）
 *   └─ package: {id, text, url, mtime, type, attr, meta, emote, flags, ...}
 *        └─ emote: {id, package_id, text, url, mtime, type, attr, meta, flags, activity}
 * ```
 *
 * ⚠️ **未登录时 `packages` 是 `null`** —— 所以这个结构**必须**在登录态下抓，
 * 不能凭公开资料猜。本项目是先抓真实响应再写解析的。
 *
 * # 字段语义（实测）
 *
 * | 字段 | 说明 |
 * |---|---|
 * | `text` | **发送用的 token**（`[doge_金箍]`）|
 * | `url` | 图片地址；**颜文字时为文字本身** |
 * | `type` | `1`=图片 / `3`=收藏集 / `4`=颜文字 |
 * | `meta.alias` | 别名（`金箍`），用于搜索 |
 * | `flags.no_access` | `true` = **不能发送**（未购买收藏集）|
 */
object EmoteParser {

    /** 单次解析的包上限（防超大响应）。 */
    const val MAX_PACKAGES = 50

    /** 单个包的表情上限。 */
    const val MAX_EMOTES_PER_PACKAGE = 500

    /**
     * 解析表情面板。
     *
     * @param json `x/emote/user/panel` 的完整响应
     * @return 表情包列表；无法解析时返回**空列表**（调用方据此显示空态）
     */
    fun parse(json: JSONObject?): List<EmotePackage> {
        val data = json?.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("packages") ?: return emptyList()
        return parsePackages(arr)
    }

    private fun parsePackages(arr: JSONArray): List<EmotePackage> {
        val out = ArrayList<EmotePackage>(arr.length())
        for (i in 0 until minOf(arr.length(), MAX_PACKAGES)) {
            val o = arr.optJSONObject(i) ?: continue
            val pkgName = o.optString("text").trim()
            val emotes = parseEmotes(o.optJSONArray("emote"), pkgName)
            // 空包不显示 —— 一个点不开的分组是噪音
            if (emotes.isEmpty()) continue
            out.add(
                EmotePackage(
                    id = o.optLong("id", 0L),
                    name = pkgName.ifEmpty { "表情包 ${o.optLong("id", 0L)}" },
                    emotes = emotes,
                ),
            )
        }
        return out
    }

    private fun parseEmotes(arr: JSONArray?, pkgName: String): List<Emote> {
        if (arr == null) return emptyList()
        val out = ArrayList<Emote>(arr.length())
        for (i in 0 until minOf(arr.length(), MAX_EMOTES_PER_PACKAGE)) {
            val o = arr.optJSONObject(i) ?: continue
            val token = o.optString("text").trim()
            // token 为空的条目无法发送 —— 直接丢弃，不要在面板里占位
            if (token.isEmpty()) continue

            val type = o.optInt("type", Emote.TYPE_IMAGE)
            // ⚠️ `flags.no_access` = 未购买收藏集，**发送会被拒**。
            //    把它标出来而不是偷偷允许发送（那会让用户以为发出去了）。
            val noAccess = o.optJSONObject("flags")?.optBoolean("no_access") == true

            out.add(
                Emote(
                    token = token,
                    url = o.optString("url").trim(),
                    type = type,
                    alias = o.optJSONObject("meta")?.optString("alias").orEmpty().trim(),
                    packageName = pkgName,
                    usable = !noAccess,
                ),
            )
        }
        return out
    }
}
