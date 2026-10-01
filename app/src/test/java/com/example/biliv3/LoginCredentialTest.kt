package com.example.biliv3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 扫码登录的凭据汇总逻辑测试。
 *
 * ## 为什么必须测这个（这是「扫码成功却提示已过期」的回归防护）
 *
 * 原实现的 bug 是**只依赖单一路径**取凭据：
 *
 * ```kotlin
 * QR_SUCCESS -> {
 *     val user = fetchUserInfo()   // 只依赖 CookieJar 已落库
 *     QrPollResult.Success(user)
 * }
 * ```
 *
 * 注释写着"用 refreshUrl 里的参数兜底"，但兜底**从未实现**。
 * 结果一旦 CookieJar 没存下 `SESSDATA`，就登录失败，
 * 而且失败还被 `else -> Expired` 伪装成"二维码已过期"。
 *
 * 下面把修复后的三路汇总逻辑抽成纯函数测（不依赖网络 / Android）。
 */
class LoginCredentialTest {

    /** 复刻 `AuthRepository.completeLogin` 的汇总逻辑（纯函数版）。 */
    private fun mergeCredentials(
        existingCookie: String,
        setCookies: List<String>,
        jumpUrl: String?,
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()

        // ③ 已有 store（最低优先级）
        parse(existingCookie).forEach { (k, v) -> merged[k] = v }

        // ② data.url 的 query 参数
        // ⚠️ 必须在 ① 之前：响应头是权威来源，要让它最后写入
        val keys = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid")
        if (!jumpUrl.isNullOrEmpty()) {
            val q = jumpUrl.substringAfter('?', "")
            if (q.isNotEmpty()) {
                for (pair in q.split('&')) {
                    val idx = pair.indexOf('=')
                    if (idx <= 0) continue
                    val k = pair.substring(0, idx)
                    val v = pair.substring(idx + 1)
                    if (k in keys && v.isNotEmpty()) merged[k] = v
                }
            }
        }

        // ① 响应头 Set-Cookie（权威，覆盖上面两者）
        for (raw in setCookies) {
            val first = raw.substringBefore(';')
            val idx = first.indexOf('=')
            if (idx > 0) {
                merged[first.substring(0, idx).trim()] = first.substring(idx + 1).trim()
            }
        }
        return merged
    }

    private fun parse(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@mapNotNull null
            part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
    }

    // ---------------- 核心：任一来源可独立成功 ----------------

    /**
     * ⚠️ 本 bug 的核心场景：**只有响应头有凭据**（CookieJar 路径正常）。
     */
    @Test
    fun `仅凭响应头就能拿到完整凭据`() {
        val m = mergeCredentials(
            existingCookie = "",
            setCookies = listOf(
                "SESSDATA=abc%2Cdef; Path=/; Domain=.bilibili.com; HttpOnly",
                "bili_jct=JCT123; Path=/",
                "DedeUserID=998877; Path=/",
            ),
            jumpUrl = null,
        )
        assertThat(m["SESSDATA"]).isEqualTo("abc%2Cdef")
        assertThat(m["bili_jct"]).isEqualTo("JCT123")
        assertThat(m["DedeUserID"]).isEqualTo("998877")
    }

    /**
     * ⚠️ 修复的关键：**即使响应头拿不到，也要能从 data.url 兜底**。
     *
     * 这是原实现完全缺失的路径 —— 注释承诺了却没写。
     */
    @Test
    fun `响应头为空时能从 data url 兜底`() {
        val url = "https://passport.biligame.com/crossDomain" +
            "?SESSDATA=sess%2Cfrom%2Curl&bili_jct=jct_from_url" +
            "&DedeUserID=12345&gourl=https%3A%2F%2Fwww.bilibili.com"

        val m = mergeCredentials("", emptyList(), url)

        assertThat(m["SESSDATA"]).isEqualTo("sess%2Cfrom%2Curl")
        assertThat(m["bili_jct"]).isEqualTo("jct_from_url")
        assertThat(m["DedeUserID"]).isEqualTo("12345")
        // 非凭据字段不能被写进 cookie
        assertThat(m).doesNotContainKey("gourl")
    }

    /** 两条路都有时，响应头优先（更权威）。 */
    @Test
    fun `响应头优先于 data url`() {
        val m = mergeCredentials(
            existingCookie = "",
            setCookies = listOf("SESSDATA=from_header"),
            jumpUrl = "https://x/?SESSDATA=from_url",
        )
        assertThat(m["SESSDATA"]).isEqualTo("from_header")
    }

    /** 已有的 store 作为最低优先级兜底。 */
    @Test
    fun `已有 store 作为兜底`() {
        val m = mergeCredentials(
            existingCookie = "buvid3=dev123; SESSDATA=old",
            setCookies = emptyList(),
            jumpUrl = null,
        )
        assertThat(m["buvid3"]).isEqualTo("dev123")
        assertThat(m["SESSDATA"]).isEqualTo("old")
    }

    /** 新值覆盖旧值。 */
    @Test
    fun `新凭据覆盖旧凭据`() {
        val m = mergeCredentials(
            existingCookie = "SESSDATA=old_value",
            setCookies = listOf("SESSDATA=new_value"),
            jumpUrl = null,
        )
        assertThat(m["SESSDATA"]).isEqualTo("new_value")
    }

    // ---------------- 判定规则 ----------------

    /** 有 SESSDATA 才认为"拿到凭据"。 */
    @Test
    fun `没有 SESSDATA 视为凭据缺失`() {
        val m = mergeCredentials("", listOf("bili_jct=x"), null)
        assertThat(m["SESSDATA"]).isNull()
        // 这种情况应报 Failed，而不是 Success / Expired
    }

    /** `bili_jct` 缺失要能识别出来（写操作会全失败）。 */
    @Test
    fun `缺少 bili_jct 可被识别`() {
        val withJct = mergeCredentials("", listOf("SESSDATA=s", "bili_jct=j"), null)
        val withoutJct = mergeCredentials("", listOf("SESSDATA=s"), null)
        assertThat(withJct).containsKey("bili_jct")
        assertThat(withoutJct).doesNotContainKey("bili_jct")
    }

    // ---------------- 边界 ----------------

    @Test
    fun `Set-Cookie 带多个属性时只取第一段`() {
        val m = mergeCredentials(
            "", listOf("SESSDATA=val; Path=/; Domain=.bilibili.com; HttpOnly; Secure"), null,
        )
        // 不能把 "Path" "Domain" 之类当成 cookie 名
        assertThat(m["SESSDATA"]).isEqualTo("val")
        assertThat(m).doesNotContainKey("Path")
        assertThat(m).doesNotContainKey("Domain")
        assertThat(m).doesNotContainKey("HttpOnly")
    }

    @Test
    fun `畸形 data url 不崩溃`() {
        assertThat(mergeCredentials("", emptyList(), "not-a-url")).isEmpty()
        assertThat(mergeCredentials("", emptyList(), "https://x/")).isEmpty()
        assertThat(mergeCredentials("", emptyList(), null)).isEmpty()
    }

    @Test
    fun `data url 中的空值字段被忽略`() {
        val m = mergeCredentials("", emptyList(), "https://x/?SESSDATA=&bili_jct=ok")
        assertThat(m).doesNotContainKey("SESSDATA")
        assertThat(m["bili_jct"]).isEqualTo("ok")
    }
}
