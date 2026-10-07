package com.example.biliv3.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.biliv3.data.api.BiliHeaders
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 登录凭据存储。
 *
 * ## 为什么必须加密
 *
 * B 站登录态的载体是 Cookie 里的 `SESSDATA` —— **它就是账号密码的等价物**，
 * 拿到即可冒充登录。明文落在 `/data/data/.../shared_prefs/` 下，
 * root 设备或备份提取都能直接读走。
 *
 * `AGENTS.md` §4.3 合规边界明确要求「SESSDATA 走 Android Keystore 加密」，
 * 所以这里用 [EncryptedSharedPreferences]，密钥由 Android Keystore 托管，
 * 应用无法导出，卸载即销毁。
 *
 * ## 为什么不用 DataStore
 *
 * `EncryptedSharedPreferences` 是官方现成的加密 KV 方案；
 * DataStore 没有等价的加密封装，自己实现要手写 KeyStore + AES-GCM，
 * 容易出错。这里优先选成熟方案。
 */
class AuthStore(context: Context) : AuthState {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * 当前 Cookie 串（`k=v; k=v`）。
     *
     * 读公开、写仅限模块内 —— 只有 [AuthCookieJar] 应该写入，
     * 其他调用方误写会破坏登录态。
     */
    override var cookie: String
        get() = prefs.getString(KEY_COOKIE, "").orEmpty()
        internal set(value) {
            prefs.edit().putString(KEY_COOKIE, value).apply()
        }

    /**
     * 登录用户的 mid。0 表示未登录。
     *
     * ## 🔴 v1.5.2 修：`mid` 缺失时必须从 Cookie 的 `DedeUserID` 兜底
     *
     * ### 根因（用户报告「我的收藏」显示「还没有收藏夹」）
     *
     * `importCookie()` 会**主动删除** `KEY_MID` —— 那是 v1.5.0 为了修
     * 「显示 A 的头像昵称、请求带 B 的凭据」而加的正确防护。
     *
     * 但 `mid` 原来**只从 `prefs` 读** —— 导入 Cookie 后 `KEY_MID` 被清，
     * `mid` 恒为 0，而 Cookie 里明明有 `DedeUserID`。
     *
     * 后果（`LibraryRepository.favoriteFolders` 第 219 行）：
     * ```
     * if (!isLoggedIn || mid <= 0) return emptyList()   // ← 静默返回空
     * ```
     * → 「我的收藏」永远显示「还没有收藏夹」，
     * 而账号实际有 6 个收藏夹（脚本直连验证）。
     *
     * ### 修法：`DedeUserID` 是**凭据本身**，不是缓存
     *
     * `DedeUserID` 与 `SESSDATA` 同在 Cookie 里、同一份导入来源 ——
     * 它属于"凭据"，不属于"身份缓存"（`userName` / `userFace` 才是缓存，
     * 那两个必须靠 `nav` 重新拉）。
     *
     * 所以这里按**凭据优先级**读：prefs 有就用（扫码登录写入的），
     * 没有就从 Cookie 解析。两者都不行才是 0。
     *
     * ⚠️ 不从 Cookie 写回 prefs —— 保持"prefs 只存扫码登录结果"的语义，
     * 避免"删了 Cookie 但 mid 还在"的不一致。
     */
    override var mid: Long
        get() {
            val stored = prefs.getLong(KEY_MID, 0L)
            if (stored > 0L) return stored
            return midFromCookie()
        }
        private set(value) {
            prefs.edit().putLong(KEY_MID, value).apply()
        }

    /**
     * 从当前 Cookie 的 `DedeUserID` 解析 mid。
     *
     * 找不到 / 解析失败返回 0（**不抛错** —— 未登录是正常状态）。
     *
     * ⚠️ 只做字符串切分，不打印 Cookie 内容（§7.16-94 凭据红线）。
     */
    private fun midFromCookie(): Long {
        val raw = prefs.getString(KEY_COOKIE, "").orEmpty()
        if (raw.isEmpty()) return 0L
        for (part in raw.split(';')) {
            val p = part.trim()
            if (!p.startsWith("DedeUserID=")) continue
            val v = p.substringAfter('=').trim()
            // DedeUserID 一定是纯数字；带后缀的 DedeUserID__ckMd5 不会被命中
            return v.toLongOrNull() ?: 0L
        }
        return 0L
    }

    override var userName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        private set(value) {
            prefs.edit().putString(KEY_NAME, value).apply()
        }

    override var userFace: String
        get() = prefs.getString(KEY_FACE, "").orEmpty()
        private set(value) {
            prefs.edit().putString(KEY_FACE, value).apply()
        }

    /**
     * 是否已登录。
     *
     * ## ⚠️ 判据是 `SESSDATA`，不是"cookie 非空"
     *
     * 首版写的是 `cookie.isNotEmpty()` —— **这是错的**。
     * `AuthCookieJar.saveFromResponse` 会把**任何**响应的 cookie 都存下来
     * （包括未登录时 B 站下发的 `buvid3` / `buvid4` / `b_nut` 等设备标识），
     * 所以**一个从未登录过的用户，cookie 也是非空的**。
     *
     * 后果：`hasLocalCredential()` 对游客返回 true →
     * 「我的」页断网时会显示「无法获取账号信息」而不是「未登录」，
     * 把游客当成"被登出的已登录用户"。
     *
     * **只有 `SESSDATA` 是登录凭据**（服务端用它认身份）。
     * 判据与 `biliJct` 同源（都从 cookie 串现取），避免两处不同步。
     */
    val isLoggedIn: Boolean
        get() = cookie
            .split(';')
            .any { part ->
                val idx = part.indexOf('=')
                idx > 0 && part.substring(0, idx).trim() == "SESSDATA" &&
                    part.substring(idx + 1).trim().isNotEmpty()
            }

    /**
     * CSRF token（cookie 里的 `bili_jct`）。
     *
     * 所有写操作（点赞 / 投币 / 收藏 / 分享）都必须带这个值，
     * 否则服务端一律拒绝。它随登录 cookie 一起下发。
     *
     * 从 cookie 串里**现取**而不是单独存一份 ——
     * 避免两处不同步（CookieJar 更新了 cookie 但没更新单独字段）。
     */
    val biliJct: String
        get() = cookie
            .split(';')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
            .firstOrNull { it.first == "bili_jct" }
            ?.second
            .orEmpty()

    /** 保存登录结果。 */
    override fun save(cookie: String, mid: Long, name: String, face: String) {
        prefs.edit()
            .putString(KEY_COOKIE, cookie)
            .putLong(KEY_MID, mid)
            .putString(KEY_NAME, name)
            .putString(KEY_FACE, face)
            .apply()
    }

    /**
     * 退出登录。
     *
     * 只清登录态，**保留 buvid**（设备指纹）——
     * 清掉会让下次请求看起来像全新设备，反而更容易触发风控。
     */
    override fun clear() {
        prefs.edit()
            .remove(KEY_COOKIE)
            .remove(KEY_MID)
            .remove(KEY_NAME)
            .remove(KEY_FACE)
            .apply()
    }

    /**
     * 设备指纹 `buvid3` / `buvid4`。
     *
     * `Endpoints.FINGER_SPI` 的注释里早就写了「首次启动拿 buvid3/buvid4，
     * 显著降低风控命中率」，但此前**代码从未调用过**，OkHttp 也没有 CookieJar。
     * 这里补上。
     */
    var buvid3: String
        get() = prefs.getString(KEY_BUVID3, "").orEmpty()
        private set(value) {
            prefs.edit().putString(KEY_BUVID3, value).apply()
        }

    var buvid4: String
        get() = prefs.getString(KEY_BUVID4, "").orEmpty()
        private set(value) {
            prefs.edit().putString(KEY_BUVID4, value).apply()
        }

    /** 记录设备指纹。 */
    fun saveBuvid(b3: String, b4: String) {
        prefs.edit()
            .putString(KEY_BUVID3, b3)
            .putString(KEY_BUVID4, b4)
            .apply()
    }

    /** 是否有设备指纹。 */
    val hasBuvid: Boolean get() = buvid3.isNotEmpty()

    // ---------------------------------------------------------------------
    // 开发者工具：Cookie 导入 / 导出
    // ---------------------------------------------------------------------

    /**
     * 导入 cookie（覆盖现有登录态）。
     *
     * ## 🔴 为什么必须**先清空再写**，不能只覆盖 `cookie` 字段
     *
     * `mid` / `userName` / `userFace` 是**上一次登录**的缓存。只改 cookie
     * 会造成「界面显示 A 的头像昵称，请求却带着 B 的凭据」——
     * 这正是任务书点名的"界面显示已登录但实际请求仍使用旧 Cookie"的镜像问题。
     *
     * 所以导入时把身份缓存一并清掉，由调用方随后调 `nav` 重新拉真实身份。
     *
     * ## 为什么保留 buvid
     *
     * `buvid3` / `buvid4` 是**设备**指纹，不是账号凭据。清掉会让下次请求
     * 看起来像全新设备，反而更容易触发风控（与 [clear] 同理）。
     *
     * @param cookie 已校验的 cookie 串（调用方先用 [CookieCodec.parse] 校验）
     */
    fun importCookie(cookie: String) {
        prefs.edit()
            .putString(KEY_COOKIE, cookie)
            // 身份缓存必须清 —— 否则会显示上一个账号的头像昵称
            .remove(KEY_MID)
            .remove(KEY_NAME)
            .remove(KEY_FACE)
            .apply()
    }

    /**
     * 导出当前 cookie 的**结构化字段**。
     *
     * 返回已解析的键值对（不是裸串）—— 调用方要么 [CookieCodec.serialize]
     * 成文件，要么只拿 [CookieCodec.maskedSummary] 显示摘要。
     * **不要让裸串在 UI 层流转**，那会很容易被顺手打进日志。
     */
    fun exportCookieFields(): LinkedHashMap<String, String> {
        val r = CookieCodec.parse(cookie)
        return if (r is CookieCodec.Result.Ok) r.pairs else LinkedHashMap()
    }

    /** 当前是否已导入过 cookie（用于 UI 显示"可导出"）。 */
    val hasCookie: Boolean get() = cookie.isNotBlank()

    private companion object {
        const val PREFS_NAME = "biliv3_auth"
        const val KEY_COOKIE = "cookie"
        const val KEY_MID = "mid"
        const val KEY_NAME = "user_name"
        const val KEY_FACE = "user_face"
        const val KEY_BUVID3 = "buvid3"
        const val KEY_BUVID4 = "buvid4"
    }
}

/**
 * OkHttp CookieJar：把 Cookie 持久化到 [AuthStore]。
 *
 * ## 为什么必须有
 *
 * 此前 OkHttp **完全没有 CookieJar** —— 意味着：
 * 1. 登录成功后拿到的 Set-Cookie 被直接丢弃，下一个请求又是未登录状态
 * 2. 没有 `buvid3`，每个请求都像全新设备，风控命中率显著更高
 *
 * ## 为什么不用 OkHttp 的 `JavaNetCookieJar`
 *
 * 那个是内存态（或依赖 `CookieManager`），进程重启就丢，
 * 而登录态必须**跨重启保持**（`AGENTS.md` §3.2
 * 验收标准：「重启 App 仍是登录态」）。
 *
 * ## 🔴 为什么收的是"提供者"而不是 `AuthStore` 本身（v1.6.2 启动优化）
 *
 * 构造 `AuthStore` 要访问 Android Keystore 并解密整个偏好文件，**实测 193ms**。
 * 若 CookieJar 在构造时就持有 `AuthStore` 实例，就会在 `AppContainer`
 * 初始化时**强制求值**那个 lazy —— lazy 白做，启动照样慢 193ms。
 *
 * 收 `() -> AuthStore` 后，CookieJar 的构造变成零成本；
 * 真正的 `AuthStore` 只在**首次请求**时才被求值（`invoke()` 落在
 * [loadForRequest] / [saveFromResponse] 里，两者都由 OkHttp 在
 * **IO 线程**调用，不在主线程）。
 *
 * ⚠️ **不要改回直接持有 `AuthStore`** —— 那会静默抵消 `AppContainer`
 * 里 `authStore` 的 `by lazy`。
 */
class AuthCookieJar(private val storeProvider: () -> AuthStore) : CookieJar {

    private val store: AuthStore get() = storeProvider()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return

        // 合并已有 cookie：只覆盖同名项，保留其他
        val merged = LinkedHashMap<String, String>()
        parseCookieString(store.cookie).forEach { (k, v) -> merged[k] = v }
        cookies.forEach { merged[it.name] = it.value }

        val value = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        store.cookie = value

        // 单独抽出 buvid，便于 UI 展示与调试
        val b3 = merged["buvid3"].orEmpty()
        val b4 = merged["buvid4"].orEmpty()
        if (b3.isNotEmpty() || b4.isNotEmpty()) {
            store.saveBuvid(b3.ifEmpty { store.buvid3 }, b4.ifEmpty { store.buvid4 })
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = store.cookie
        if (raw.isEmpty()) return emptyList()

        // 用 bilibili 域名构造 Cookie，保证被接受
        val host = url.host
        val parts = LinkedHashMap<String, String>()
        parseCookieString(raw).forEach { (k, v) -> parts[k] = v }

        // 🔴 v1.6.7 修：**必须补上 `buvid3`**，否则写操作全部 `-401 非法访问`。
        //
        // ## 根因（模拟器实测）
        //
        // 投币返回 `code=-401 非法访问`。用网络拦截器打印 cookie **名字**：
        // ```
        // cookies=[sid,SESSDATA,bili_jct,DedeUserID,DedeUserID__ckMd5]
        // ```
        // —— `SESSDATA` 与 `bili_jct` **都在**，唯独**缺 `buvid3`**。
        //
        // ## 为什么缺
        //
        // `buvid3` 是**设备指纹**，B 站通过 `Set-Cookie` 下发，
        // 本应用把它单独存进 `AuthStore.buvid3`（见 [saveBuvid]），
        // **但没有把它放回 `store.cookie` 串**。
        // 于是 `loadForRequest` 只回放登录 cookie，`buvid3` 永远缺席。
        //
        // ## 为什么读操作没事、写操作就挂
        //
        // 读接口（`view` / `nav`）对 `buvid3` 宽松，所以首页、余额都正常；
        // **写接口风控更严** —— 缺设备指纹会被判成"非法访问"。
        // 这解释了"能登录、能看余额，但一投币就失败"的现象。
        //
        // ## 为什么不是覆盖
        //
        // `store.cookie` 里**本来就可能**已有 `buvid3`（WebView 登录时
        // 整串转存过）。只有当它缺席时才补 —— 否则会用旧值覆盖新值。
        if (parts["buvid3"].isNullOrEmpty()) {
            val b3 = store.buvid3
            if (b3.isNotEmpty()) parts["buvid3"] = b3
        }

        return parts.mapNotNull { (k, v) ->
            Cookie.Builder()
                .name(k)
                .value(v)
                .domain(host)
                .path("/")
                .build()
        }
    }

    private fun parseCookieString(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split(';')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
            .toMap()
    }
}

/**
 * 从 `Set-Cookie` 响应头里抽出登录凭据。
 *
 * 登录成功后 B 站会在 `poll` 响应的多个 `Set-Cookie` 里给出
 * `SESSDATA` / `bili_jct` / `DedeUserID` 等。这里做一次汇总。
 */
object CookieExtractor {

    /**
     * 把一组 cookie 拼成请求头可用的字符串。
     *
     * 过滤掉 `bili_ticket` 等会话票据 —— 它们由服务端按需下发，
     * 手动携带反而可能因过期导致校验失败。
     */
    fun build(cookies: List<Cookie>): String =
        cookies
            .filter { it.name in ESSENTIAL }
            .joinToString("; ") { "${it.name}=${it.value}" }

    /**
     * 登录必需字段。
     *
     * `DedeUserID` 是账号 id，`SESSDATA` 是会话凭据，
     * `bili_jct` 是 CSRF token（写操作必须带）。
     */
    private val ESSENTIAL = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid")

    /** 媒体请求也要带 Referer，这里复用统一头定义。 */
    fun mediaHeaders(): Map<String, String> = BiliHeaders.media()
}
