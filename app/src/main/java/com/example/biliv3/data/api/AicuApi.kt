package com.example.biliv3.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * aicu.cc API 客户端。
 *
 * ## 🔴 安全红线：必须是独立的 OkHttpClient，绝不能复用 B 站的 client
 *
 * 共享 client = 共享 `AuthCookieJar` = **把 B 站 `SESSDATA` / `bili_jct`
 * 明文发给第三方站点**。这是本项目最容易犯的严重错误 ——
 * 而且方向是反的：`DanmakuRepository` 当年踩的是"忘了传共享 client"
 * （导致没有 cookie、写操作失败），这里的坑是"传了共享 client"
 * （导致凭据外泄）。
 *
 * 所以本类的 client **不挂 CookieJar**，也不带任何 B 站 cookie 头。
 * 抓包验证：aicu 请求里不应出现 `SESSDATA` / `bili_jct`。
 *
 * ## 队列（ticket）机制 —— 必须先走，否则 `-419`
 *
 * aicu 有**强制排队**设计，所有 `search` 系接口都要带有效 ticket：
 *
 * | 方法 | 路径 | 说明 |
 * |---|---|---|
 * | POST | `/api/v4/queue/enqueue` | 取 ticket |
 * | GET | `/api/v4/queue/stream?ticket=` | SSE，推 `event: ready` |
 * | POST | `/api/v4/queue/cancel?ticket=` | 取消排队 |
 *
 * `enqueue` 返回的 `status` 直接是 `"ready"` 时**无需等 SSE**，可立刻查
 * （实测绝大多数情况都是 ready）。[getJson] 遇 `-419` 会自动重新
 * enqueue 并重试一次。
 *
 * ## 为什么也手写解析（不用 Retrofit / Moshi）
 *
 * 与 `BiliApi` 同一理由：第三方站点的字段类型同样不稳定，
 * 强类型反序列化会在字段变动时整页崩。而且 `org.json` 在 JVM 单测里
 * 是 stub，手写最小解析器能让纯函数部分完全可测。
 */
class AicuApi(
    /**
     * 注入点保留给测试与将来的 DoH 策略调整。
     *
     * ⚠️ 默认值里**没有 CookieJar** —— 这不是遗漏，是安全设计（见类注释）。
     */
    private val client: OkHttpClient = defaultClient(),
) {

    /** 当前 ticket 与它的获取时间。 */
    @Volatile
    private var ticket: String? = null

    @Volatile
    private var ticketAt: Long = 0L

    // ---------------- 公开接口 ----------------

    /**
     * 拿一个可用 ticket（带缓存）。
     *
     * ## status 的取值与处理
     *
     * | status | 处理 |
     * |---|---|
     * | `"ready"`（实测绝大多数） | 立即返回 |
     * | 缺失 | 也接受 —— 实测个别响应不带该字段但 ticket 可用 |
     * | 其他（如 `"waiting"`） | **照样返回该 ticket** |
     *
     * ## ⚠️ 为什么 waiting 也直接返回，而不是轮询/等 SSE
     *
     * 两个原因：
     *
     * 1. **轮询 `enqueue` 会重新排队** —— 反复调这个接口等于不断插队，
     *    对 aicu 这种免费站点是骚扰，也可能让我们排得更靠后。
     * 2. **已有更合适的兜底**：[getJson] 遇 `-419` 会自动重新排队并重试一次。
     *    也就是说"ticket 还不能用"这件事由查询那一步自然处理，
     *    不需要在这里阻塞等待。
     *
     * 另外不做 SSE（`queue/stream`）阻塞等待：让用户盯一个排队进度条
     * 是更差的体验，而为极少数情况维持一条长连接不划算。
     *
     * @return ticket；连 `enqueue` 都失败时返回 null
     */
    suspend fun enqueue(): String? {
        cachedTicket()?.let { return it }

        val json = runCatching { post("/api/v4/queue/enqueue", emptyMap()) }.getOrNull()
            ?: return null

        val data = jsonField(json, "data") ?: return null
        val t = stringField(data, "ticket")?.takeIf { it.isNotEmpty() } ?: return null

        ticket = t
        ticketAt = System.currentTimeMillis()
        return t
    }

    /** 取消排队。失败静默（取消是尽力而为的清理动作）。 */
    suspend fun cancel(t: String) {
        runCatching { post("/api/v4/queue/cancel?ticket=$t", emptyMap()) }
        if (ticket == t) {
            ticket = null
            ticketAt = 0L
        }
    }

    /**
     * 发 GET 并返回响应体原文。
     *
     * @param path 以 `/` 开头的完整路径
     * @param query 查询参数（ticket 由本方法自动注入）
     * @param withTicket 是否自动带 ticket（`home` 下的公开接口不需要）
     * @param baseUrl 接口所在域名。默认 [BASE_URL]；
     *   用户资料在 [WORKER_BASE_URL]（**另一个域名**，走错会 404）。
     */
    suspend fun getRaw(
        path: String,
        query: Map<String, String> = emptyMap(),
        withTicket: Boolean = true,
        baseUrl: String = BASE_URL,
    ): String = withContext(Dispatchers.IO) {
        val finalQuery = LinkedHashMap(query)
        if (withTicket) {
            val t = cachedTicket() ?: enqueue() ?: ""
            if (t.isNotEmpty()) finalQuery["ticket"] = t
        }

        val url = buildString {
            append(baseUrl).append(path)
            if (finalQuery.isNotEmpty()) {
                append('?').append(
                    finalQuery.entries.joinToString("&") { (k, v) ->
                        "$k=${encodeQueryValue(v)}"
                    },
                )
            }
        }
        execute(Request.Builder().url(url).applyHeaders().get().build())
    }

    /**
     * 查询值编码。
     *
     * 必须编码：`keyword` 可能是中文，直接拼进 URL 会被 OkHttp 拒
     * （`IllegalArgumentException: unexpected url`）或服务端解析错。
     * 用 `okhttp3.HttpUrl` 的规范实现而不是 `URLEncoder` ——
     * 后者会把空格编成 `+`（query 里 `+` 是字面加号，语义不同）。
     *
     * ## ⚠️ 必须取 `encodedQuery` 而不是 `queryParameter`
     *
     * `queryParameter(name)` 返回的是**已解码**的值 ——
     * 拿它当"编码结果"会得到一个恒等于入参的字符串（等于没编码）。
     * 这是本项目实测踩到的坑：单测里"中文被编码"那条直接失败。
     */
    internal fun encodeQueryValue(v: String): String =
        okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("x")
            .addQueryParameter("k", v)
            .build()
            .encodedQuery
            .orEmpty()
            .removePrefix("k=")

    /**
     * 带 `-419` 自动重试的查询。
     *
     * `-419` 的含义是「排队凭据无效或已过期，请重新发起查询」——
     * 唯一正确的处理就是**重新 enqueue 再查一次**。
     * 只重试 1 次：连续 -419 说明站点侧排队系统异常，
     * 无限重试只会变成死循环。
     */
    suspend fun getJson(
        path: String,
        query: Map<String, String> = emptyMap(),
        withTicket: Boolean = true,
        baseUrl: String = BASE_URL,
    ): String {
        val first = getRaw(path, query, withTicket, baseUrl)
        if (!isTicketExpired(first)) return first

        // 凭据过期 → 作废缓存、重新排队、重试一次
        ticket = null
        ticketAt = 0L
        enqueue()
        return getRaw(path, query, withTicket, baseUrl)
    }

    /** 是否是 ticket 失效错误。 */
    internal fun isTicketExpired(json: String): Boolean = intField(json, "code") == -419

    // ---------------- 请求实现 ----------------

    private suspend fun post(path: String, query: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(BASE_URL).append(path)
                if (query.isNotEmpty()) {
                    append('?').append(
                        query.entries.joinToString("&") { (k, v) ->
                            "$k=${encodeQueryValue(v)}"
                        },
                    )
                }
            }
            // enqueue / cancel 都是无 body 的 POST（实测带空 form body 也可）
            execute(
                Request.Builder()
                    .url(url)
                    .applyHeaders()
                    .post(EMPTY_BODY)
                    .build(),
            )
        }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && body.isEmpty()) {
                throw AicuException(resp.code, "HTTP ${resp.code}")
            }
            body
        }

    private fun Request.Builder.applyHeaders(): Request.Builder = this
        .header("User-Agent", BiliHeaders.DESKTOP_UA)
        // 站点要求带 Referer（实测不带会被拒）
        .header("Referer", "https://www.aicu.cc/")
        .header("Accept", "application/json, text/plain, */*")
        .header("Accept-Language", "zh-CN,zh;q=0.9")

    private fun cachedTicket(): String? =
        ticket?.takeIf { System.currentTimeMillis() - ticketAt < TICKET_TTL_MS }

    // ---------------- 最小 JSON 解析（可单测）----------------

    /**
     * 取对象字段的原始 JSON 片段（不解析成对象，避免依赖 `org.json`）。
     *
     * 实现方式：定位 `"key"` 后的 `{`，做花括号配平截取。
     * 字符串内的花括号不参与配平 —— 需要跳过转义与引号区间。
     */
    internal fun jsonField(json: String, key: String): String? {
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

    /** 取数组字段的原始 JSON 片段（`"key": [ ... ]`）。 */
    internal fun jsonArray(json: String, key: String): String? {
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
     * 取字符串字段。
     *
     * 支持转义引号 —— 评论正文里出现 `\"` 是常态
     * （用户会发带引号的评论），不处理会把值截断。
     */
    internal fun stringField(json: String, key: String): String? {
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(json) ?: return null
        return unescapeJson(m.groupValues[1])
    }

    /**
     * 取整数字段。
     *
     * 兼容三种形态（第三方站点同样不稳定）：
     * - 数字：`"progress":20639`
     * - 字符串数字：`"roomid":"662240"`
     * - 缺失：返回 null
     */
    internal fun intField(json: String, key: String): Int? {
        val num = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(json)
        if (num != null) return num.groupValues[1].toIntOrNull()

        val str = stringField(json, key) ?: return null
        return str.toIntOrNull()
    }

    /** 取长整数字段（同样兼容字符串形态）。 */
    internal fun longField(json: String, key: String): Long? {
        val num = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(json)
        if (num != null) return num.groupValues[1].toLongOrNull()

        val str = stringField(json, key) ?: return null
        return str.toLongOrNull()
    }

    /** 把数组片段切成元素列表（按顶层 `{...}` 切分）。 */
    internal fun splitArray(arrayJson: String?): List<String> {
        if (arrayJson.isNullOrEmpty()) return emptyList()
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false

        for (i in arrayJson.indices) {
            val c = arrayJson[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(arrayJson.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }

    /** 反转义 JSON 字符串字面量。 */
    internal fun unescapeJson(s: String): String {
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
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 6
                        continue
                    }
                    sb.append(n)
                }
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }

    companion object {
        /** aicu API 主域名。 */
        const val BASE_URL = "https://api.aicu.cc"

        /** 用户资料代理域名。 */
        const val WORKER_BASE_URL = "https://worker.aicu.cc"

        /** ticket 缓存时长。 */
        const val TICKET_TTL_MS = 120_000L

        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null, 0, 0)

        /**
         * 默认客户端。
         *
         * ## ⚠️ 这里没有 `.cookieJar(...)` 是**有意的**
         *
         * 见类注释的安全红线。`AicuDns` 接管 DNS 解析，
         * 让请求能绕开投毒直连 Cloudflare 真实 IP。
         *
         * 超时给得比 B 站宽松：aicu 是免费站点，有时较慢，
         * 15s 的读超时会误判成"失败"。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // DNS 接管：绕过投毒，直连真实 Cloudflare IP
            .dns(AicuDns)
            // ⛔ 刻意不挂 CookieJar —— 见类注释
            .build()
    }
}

/** aicu 请求失败。 */
class AicuException(val httpCode: Int, override val message: String) : Exception(message)
