package com.example.biliv3.data.api

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * aicu.cc 专用 DNS 解析器。
 *
 * ## 为什么需要它（根因，已实测）
 *
 * 中国大陆访问 `*.aicu.cc` **不通的根因是纯 DNS 投毒，不是 IP 封锁**：
 *
 * | 证据 | 实测结果 |
 * |---|---|
 * | 本机 DNS 反复解析 `api.aicu.cc` | 每次 IP 都不同：`75.126.164.178` / `69.63.176.59` / `199.59.150.12` —— 全是已知污染段 |
 * | 裸 TCP 直连真实 Cloudflare IP `104.26.11.4:443` | ✅ `TcpTestSucceeded=True` |
 * | `curl --noproxy '*' --resolve api.aicu.cc:443:104.26.11.4` | ✅ HTTP 200 + 真实数据 |
 * | 腾讯 DoH（`doh.pub`）查 `api.aicu.cc` | ✅ 返回正确 Cloudflare 三 IP |
 * | 阿里 DoH（`dns.alidns.com`）查 `api.aicu.cc` | ❌ 返回污染结果（国内递归已被污染） |
 *
 * 结论：**IP 层通、DNS 层被投毒 → 只要绕过本地 DNS 就能零代理直连。**
 *
 * ## 方案：接管 OkHttp 的 `Dns` 接口
 *
 * 自己返回真实 IP，但 **TLS SNI 与 Host 头仍是 `api.aicu.cc`**，
 * Cloudflare 正常路由，证书校验也正常通过。
 * 比"改 hosts"干净、比"代理"轻、比"IP 直连 + 关证书校验"安全。
 *
 * ```
 * OkHttp(Dns = 本对象)
 *    ├─ DoH 引导：用【硬编码 IP】直连 DoH 服务器（解决"先有鸡还是先有蛋"）
 *    │    主：doh.pub        → 1.12.12.12 / 120.53.53.53   GET /dns-query
 *    │    备：dns.alidns.com → 223.5.5.5                    GET /resolve
 *    │        ⚠️ 路径是 /resolve 不是 /dns-query，
 *    │        且对 aicu.cc 会返回污染结果 → 只做兜底 + 结果校验
 *    ├─ 结果校验：必须是 Cloudflare 段（104.26.x / 172.67.x），否则丢弃
 *    └─ 兜底：硬编码 [KNOWN_GOOD]
 * ```
 *
 * ## ⚠️ DoH 引导必须用 IP 直连
 *
 * `1.12.12.12` / `223.5.5.5` 是硬编码的，**不能写域名** ——
 * 写域名就又被投毒了，等于绕了个圈子回到起点。
 * 这也是 [bootstrapClient] 存在的唯一理由。
 *
 * ## 为什么是 `object`
 *
 * 全应用唯一实例 → 内存缓存全应用共享，且与 `AppContainer` 的手写 DI
 * 风格一致（单用户项目不引入 Hilt，见 `AppContainer` 说明）。
 */
object AicuDns : Dns {

    /** 需要接管的域名。其余域名一律走系统 DNS，不做多余的事。 */
    val AICU_HOSTS: Set<String> = setOf(
        "api.aicu.cc",
        "www.aicu.cc",
        "worker.aicu.cc",
    )

    /**
     * 兜底 IP（实测三台都返回 HTTP 200，可轮询）。
     *
     * 这是"所有 DoH 都失败"时的最后一道防线 ——
     * 宁可打一个可能过期的 IP，也不要直接抛 `UnknownHostException`
     * 让用户看到"网络错误"（那种报错完全不指向真实原因）。
     */
    val KNOWN_GOOD: List<String> = listOf(
        "104.26.11.4",
        "104.26.10.4",
        "172.67.72.100",
    )

    /** DoH 主服务器（腾讯，实测对 aicu.cc 返回正确结果）。 */
    const val DOH_PRIMARY_HOST = "doh.pub"
    const val DOH_PRIMARY_PATH = "/dns-query"

    /** DoH 主服务器的硬编码引导 IP。 */
    val DOH_PRIMARY_IPS: List<String> = listOf("1.12.12.12", "120.53.53.53")

    /**
     * DoH 备用服务器（阿里）。
     *
     * ⚠️ 路径是 `/resolve`，与腾讯的 `/dns-query` **不同**。
     * 写错路径会拿到 HTML 404 页面，解析出 0 个 IP —— 静默失效。
     */
    const val DOH_BACKUP_HOST = "dns.alidns.com"
    const val DOH_BACKUP_PATH = "/resolve"

    /** DoH 备用服务器的硬编码引导 IP。 */
    val DOH_BACKUP_IPS: List<String> = listOf("223.5.5.5")

    /** 解析结果缓存时长。 */
    const val TTL_MS = 300_000L

    /** aicu 真实 IP 所在的 Cloudflare 网段前缀。 */
    private val CLOUDFLARE_PREFIXES = listOf("104.26.", "172.67.")

    /**
     * 已知的 DNS 投毒网段（实测出现在本机解析结果里）。
     *
     * 这些 IP 分属 Dropbox / Facebook / Twitter / Verio ——
     * 与 aicu 毫无关系，是投毒污染的特征。
     */
    private val POISONED_PREFIXES = listOf(
        "75.126.",
        "69.63.",
        "199.59.",
    )

    @Volatile
    private var cached: List<InetAddress>? = null

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * DoH 引导专用客户端。
     *
     * 它自己的 `Dns` 只把 DoH 服务器域名映射到硬编码 IP，
     * 其余域名交回系统 —— 这样 DoH 请求的 **SNI / Host 仍是 `doh.pub`**，
     * 证书校验正常，只是不再问本地 DNS 要 doh.pub 的地址。
     */
    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // ⚠️ 无 CookieJar：这是第三方站点的引导请求，
            // 绝不能带上任何 B 站凭据（见 AicuApi 的安全红线说明）。
            //
            // 用匿名对象而不是 lambda：`Dns` 在 OkHttp 4 里是 **Kotlin 接口**，
            // 不支持 SAM 转换（写 lambda 会报 "Argument type mismatch"）。
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
                    DOH_PRIMARY_HOST -> DOH_PRIMARY_IPS.toInetAddresses()
                    DOH_BACKUP_HOST -> DOH_BACKUP_IPS.toInetAddresses()
                    else -> Dns.SYSTEM.lookup(hostname)
                }
            })
            .build()
    }

    // ---------------- Dns 接口 ----------------

    override fun lookup(hostname: String): List<InetAddress> {
        // 非 aicu 域名不插手 —— 接管全部域名会把 B 站请求也拖进 DoH，
        // 徒增延迟且无收益。
        if (hostname !in AICU_HOSTS) return Dns.SYSTEM.lookup(hostname)

        cached?.takeIf { System.currentTimeMillis() - cachedAt < TTL_MS }?.let { return it }

        val resolved = resolve(hostname)
        cached = resolved
        cachedAt = System.currentTimeMillis()
        return resolved
    }

    /**
     * 按「主 DoH → 备 DoH → 硬编码兜底」的顺序解析，**每一步都做结果校验**。
     *
     * 校验不可省：备用的阿里 DoH 实测**会返回污染 IP**，
     * 不校验就会拿着 `199.59.150.12` 去连，表现为连接超时 ——
     * 报错完全不指向"DNS 被投毒"。
     */
    internal fun resolve(hostname: String): List<InetAddress> {
        val primary = runCatching { queryDoh(DOH_PRIMARY_HOST, DOH_PRIMARY_PATH, hostname) }
            .getOrDefault("")
        validAicuIps(parseDohIps(primary)).takeIf { it.isNotEmpty() }?.let {
            return it.toInetAddresses()
        }

        val backup = runCatching { queryDoh(DOH_BACKUP_HOST, DOH_BACKUP_PATH, hostname) }
            .getOrDefault("")
        validAicuIps(parseDohIps(backup)).takeIf { it.isNotEmpty() }?.let {
            return it.toInetAddresses()
        }

        return KNOWN_GOOD.toInetAddresses()
    }

    /** 发一次 DoH 查询，返回响应体原文。 */
    private fun queryDoh(host: String, path: String, name: String): String {
        val url = "https://$host$path?name=$name&type=A"
        val request = Request.Builder()
            .url(url)
            // ⚠️ 必须带这个 accept，否则部分 DoH 服务返回 HTML 而不是 JSON
            .header("accept", "application/dns-json")
            .header("User-Agent", BiliHeaders.DESKTOP_UA)
            .build()

        return bootstrapClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use ""
            resp.body?.string().orEmpty()
        }
    }

    /** 清空缓存（调试 / 排错用）。 */
    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    // ---------------- 纯函数（可单测）----------------

    /**
     * 解析 DoH JSON 响应，提取 A 记录 IP。
     *
     * ## 为什么手写而不用 `org.json`
     *
     * `org.json` 是 Android 平台类，在 JVM 单测里是 **stub（抛 not mocked）**，
     * 用它会让这段逻辑无法单测。本项目的既有单测全部避开 `org.json`
     * （见 `WbiTest` / `ProtoTest`），这里保持一致 ——
     * 用最小扫描器只取需要的两个字段，够用且完全可测。
     *
     * ## 兼容两种字段名
     *
     * 标准 DoH JSON（RFC 8484 的 JSON 变体，腾讯/Google 都遵循）用 `Answer`：
     * ```json
     * {"Status":0,"Answer":[{"name":"api.aicu.cc","type":1,"TTL":300,"data":"104.26.11.4"}]}
     * ```
     * 但部分实现写成 `data` 数组。这里**不认数组名**，
     * 只扫所有 `{...}` 对象里的 `type` + `data` 组合 ——
     * 两种字段名都能命中，且不会把 `Question` 段误当答案（它没有 `data`）。
     */
    internal fun parseDohIps(json: String): List<String> {
        if (json.isBlank()) return emptyList()

        val out = LinkedHashSet<String>()
        for (m in OBJECT_REGEX.findAll(json)) {
            val obj = m.value
            // 只取 A 记录（type=1）；AAAA(28) / CNAME(5) 一律跳过
            val type = TYPE_FIELD.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (type != 1) continue

            val data = DATA_FIELD.find(obj)?.groupValues?.get(1) ?: continue
            if (data.isNotEmpty()) out.add(data)
        }
        return out.toList()
    }

    /** 是否是 aicu 真实所在的 Cloudflare 网段。 */
    internal fun isCloudflareAicuIp(ip: String): Boolean =
        CLOUDFLARE_PREFIXES.any { ip.startsWith(it) }

    /** 是否是已知的投毒 IP。 */
    internal fun isPoisonedIp(ip: String): Boolean =
        POISONED_PREFIXES.any { ip.startsWith(it) }

    /**
     * 校验并去重候选 IP。
     *
     * 两步过滤：先必须落在 Cloudflare 段（白名单语义），
     * 再排除已知投毒段（显式黑名单，用于在测试里把"投毒"这件事钉死）。
     */
    internal fun validAicuIps(candidates: List<String>): List<String> =
        candidates
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { isCloudflareAicuIp(it) && !isPoisonedIp(it) }
            .distinct()

    // ---------------- 内部工具 ----------------
    private fun List<String>.toInetAddresses(): List<InetAddress> =
        mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }

    /** 最简对象扫描：DoH 的 Answer 元素都是扁平对象，不含嵌套花括号。 */
    private val OBJECT_REGEX = Regex("\\{[^{}]*}")

    /** `"type": 1` —— A 记录。 */
    private val TYPE_FIELD = Regex("\"type\"\\s*:\\s*(\\d+)")

    /** `"data": "1.2.3.4"` —— DoH 的答案值。 */
    private val DATA_FIELD = Regex("\"data\"\\s*:\\s*\"([^\"]*)\"")
}
