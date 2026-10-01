package com.example.biliv3.data.api

import java.security.MessageDigest

/**
 * B 站 WBI 签名。
 *
 * 流程：
 *   1. 从 `/x/web-interface/nav` 拿 `img_url` / `sub_url`，取文件名 → imgKey / subKey
 *   2. imgKey + subKey 按 [MIXIN_KEY_ENC_TAB] 重排，取前 32 位 → mixinKey
 *   3. 参数过滤 `[!'()*]`，加 wts，按 key 排序，percent-encode，拼 `&`
 *   4. `md5(query + mixinKey)` → `w_rid`
 *
 * ## ⚠️ 编码语义（最容易错的地方）
 *
 * 官方实现是 JS 的 `encodeURIComponent`：**空格编码为 `%20`，不是 `+`**。
 * 因此**不能**用 `java.net.URLEncoder` —— 它会把空格编成 `+`、
 * 把 `~` 编成 `%7E`，签名必然对不上。
 *
 * 本实现由 `WbiTest` 的黄金向量锁死（实测可拿到真实推荐流数据）。
 */
object Wbi {

    /** 官方混淆表，长期稳定。 */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    private val FILTER = Regex("[!'()*]")
    private const val HEX = "0123456789ABCDEF"

    private val UNRESERVED = BooleanArray(128).also { arr ->
        for (c in 'A'..'Z') arr[c.code] = true
        for (c in 'a'..'z') arr[c.code] = true
        for (c in '0'..'9') arr[c.code] = true
        for (c in "-_.~") arr[c.code] = true
    }

    data class Keys(val imgKey: String, val subKey: String)

    /** 从 nav 返回的图片 URL 里取文件名（去扩展名）。 */
    fun fileName(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.')

    /** imgKey + subKey → mixinKey（64 字符按表重排取前 32）。 */
    fun mixinKey(keys: Keys): String {
        val raw = keys.imgKey + keys.subKey
        require(raw.length >= 64) {
            "mixinKey 源串长度不足: ${raw.length}"
        }
        return buildString(32) {
            for (i in 0 until 32) append(raw[MIXIN_KEY_ENC_TAB[i]])
        }
    }

    /**
     * `encodeURIComponent` 语义的 percent-encoding（空格 → `%20`）。
     *
     * ⚠️ 不要替换成 `java.net.URLEncoder`。
     */
    fun percentEncode(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            if (i < 128 && UNRESERVED[i]) {
                sb.append(i.toChar())
            } else {
                sb.append('%').append(HEX[i ushr 4]).append(HEX[i and 0x0F])
            }
        }
        return sb.toString()
    }

    /**
     * 签名。返回**包含** `wts` 与 `w_rid` 的完整参数表（原始值，未编码）。
     *
     * [wts] 显式传入，便于用固定向量做单元测试。
     */
    fun sign(
        params: Map<String, String>,
        keys: Keys,
        wts: Long,
    ): Map<String, String> {
        val mixin = mixinKey(keys)

        val cleaned = LinkedHashMap<String, String>(params.size + 2)
        params.forEach { (k, v) -> cleaned[k] = FILTER.replace(v, "") }
        cleaned["wts"] = wts.toString()

        val query = cleaned.entries
            .sortedBy { it.key }
            .joinToString("&") { "${percentEncode(it.key)}=${percentEncode(it.value)}" }

        cleaned["w_rid"] = md5Hex(query + mixin)
        return cleaned
    }

    /** 把签名后的参数转成 query string。 */
    fun toQueryString(signed: Map<String, String>): String =
        signed.entries
            .sortedBy { it.key }
            .joinToString("&") { "${percentEncode(it.key)}=${percentEncode(it.value)}" }

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (b in digest) {
            val i = b.toInt() and 0xFF
            sb.append(HEX[i ushr 4]).append(HEX[i and 0x0F])
        }
        return sb.toString().lowercase()
    }

    /** 当前 Unix 秒。 */
    fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
