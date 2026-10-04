package com.example.biliv3.data.auth

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.api.Endpoints

/**
 * 扫码登录。
 *
 * ## 四态状态机
 *
 * ```
 * 待扫码(86101) → 待确认(86102) → 成功(0)
 *                      ↓
 *                  已过期(86038)
 * ```
 *
 * ⚠️ 注意有两层 code：
 * - **外层** `code` 是接口调用是否成功（0 成功）
 * - **内层** `data.code` 才是扫码状态
 *
 * 只看外层会把"未扫码"误判成成功。
 */
class AuthRepository(
    private val api: BiliApi,
    private val store: AuthStore,
) {

    /**
     * 本地是否**存有登录凭据**（cookie 非空）。
     *
     * ⚠️ 这是**纯本地判断，不发请求** —— 只回答"这台设备上曾经登录过"，
     * 不代表 cookie 在服务端仍有效。
     *
     * 用途：把 [fetchUserInfo] 的 null 拆成两种情况 ——
     * | 本地凭据 | fetchUserInfo | 结论 |
     * |---|---|---|
     * | 有 | null | **网络/服务端故障**（不是登出） |
     * | 无 | null | 真的未登录 |
     */
    fun hasLocalCredential(): Boolean = store.isLoggedIn

    /**
     * 生成二维码。
     *
     * @return 二维码内容（一个 URL，需自行渲染成图）+ 轮询用的 key
     */
    suspend fun generateQrCode(): QrCode {
        val json = api.getRaw(
            path = Endpoints.QRCODE_GENERATE,
            query = emptyMap(),
            signed = false,
            host = Endpoints.PASSPORT_HOST,
        )
        if (json.optInt("code", -1) != 0) {
            throw BiliException(json.optInt("code", -1), json.optString("message", "生成二维码失败"))
        }
        val data = json.optJSONObject("data")
            ?: throw BiliException(-1, "二维码响应缺少 data")

        val url = data.optString("url")
        val key = data.optString("qrcode_key")
        if (url.isEmpty() || key.isEmpty()) {
            throw BiliException(-1, "二维码响应字段不完整")
        }
        return QrCode(content = url, key = key)
    }

    /**
     * 轮询扫码状态。
     *
     * @return 当前状态。成功时**已自动落库**（cookie + 用户信息）。
     */
    suspend fun poll(qrKey: String): QrPollResult {
        // ⚠️ 用 getRawWithCookies 而不是 getRaw：
        // 成功时凭据在 **响应头的 Set-Cookie** 里，必须显式拿到，
        // 不能只依赖 CookieJar 的隐式落库（见下方 QR_SUCCESS 分支）。
        val (json, setCookies) = api.getRawWithCookies(
            path = Endpoints.QRCODE_POLL,
            query = mapOf("qrcode_key" to qrKey),
            signed = false,
            host = Endpoints.PASSPORT_HOST,
        )

        // 外层 code 非 0：接口本身失败
        if (json.optInt("code", -1) != 0) {
            throw BiliException(json.optInt("code", -1), json.optString("message", "轮询失败"))
        }

        val data = json.optJSONObject("data")
            ?: throw BiliException(-1, "轮询响应缺少 data")

        // 内层 code 才是扫码状态
        val innerCode = data.optInt("code", -1)
        // 诊断日志：扫码状态机每一步都打出来。
        // 这类"用户看到的现象与预期不符"的问题，没有日志就只能靠猜。
        android.util.Log.i(
            TAG,
            "poll innerCode=$innerCode setCookies=${setCookies.size} " +
                "url=${data.optString("url").take(80)}",
        )
        if (innerCode != QR_WAITING) {
            // 非"等待扫码"时把完整 data 打出来（含成功/过期/已扫）
            android.util.Log.i(TAG, "poll data=$data")
        }
        return when (innerCode) {
            QR_WAITING -> QrPollResult.Waiting
            QR_SCANNED -> QrPollResult.Scanned
            QR_EXPIRED -> QrPollResult.Expired
            QR_SUCCESS -> completeLogin(data, setCookies)
            // ⚠️ 不再把未知 code 当成「已过期」。
            //
            // 这是一处真实的误导：原实现 `else -> Expired` 会把
            // 「成功但取凭据失败」也报成"二维码已过期"，
            // 用户看到的是"扫码成功 → 已过期"，完全找不到北。
            // 现在区分开：未知状态单独报，并带上 code 便于排查。
            else -> QrPollResult.Failed("未知扫码状态（code=$innerCode），请重试")
        }
    }

    /**
     * 扫码成功：从**所有可用来源**汇总凭据并落库。
     *
     * ## ⚠️ 这是「扫码成功却提示已过期」的根因修复
     *
     * 原实现只做了一件事：
     *
     * ```kotlin
     * val user = fetchUserInfo()   // 依赖 CookieJar 已经把凭据落库
     * QrPollResult.Success(user)
     * ```
     *
     * 注释里写着"用 refreshUrl 里的参数兜底"，但**兜底代码从未实现**。
     * 于是只要 CookieJar 因任何原因没把 `SESSDATA` 存下来，
     * `fetchUserInfo()` 就返回 null，用户看到的就是登录失败。
     *
     * 现在三条路都取，取并集：
     *
     * | 来源 | 说明 |
     * |---|---|
     * | ① 响应头 `Set-Cookie` | 最权威，但依赖 OkHttp 认为 cookie 合法 |
     * | ② `data.url` 的 query 参数 | 官方给的跳转地址，**同样携带全套凭据** |
     * | ③ 已有 store | 兜底（例如上次已登录） |
     *
     * ## 为什么 ② 很重要
     *
     * 实测 `data.url` 形如
     * `https://passport.biligame.com/crossDomain?...&SESSDATA=xxx&bili_jct=yyy&DedeUserID=zzz`，
     * 它**不经过 CookieJar**，只要解析 query 就能拿到 ——
     * 这是一条不受 cookie 域匹配规则影响的可靠路径。
     */
    private suspend fun completeLogin(
        data: org.json.JSONObject,
        setCookies: List<String>,
    ): QrPollResult {
        val merged = LinkedHashMap<String, String>()

        // ③ 先放已有 store（最低优先级，会被后面覆盖）
        parseCookieString(store.cookie).forEach { (k, v) -> merged[k] = v }

        // ② 再从 data.url 的 query 参数取（官方跳转地址，携带全套凭据）
        //
        // ⚠️ 顺序很重要：这一步必须在 ① 之前。
        // 响应头才是**权威来源**（服务端直接下发的 Set-Cookie），
        // data.url 只是同一批凭据的另一条载体。
        // 若两者都有，应以响应头为准 —— 所以让它最后写入。
        val jumpUrl = data.optString("url")
        if (jumpUrl.isNotEmpty()) {
            runCatching {
                val query = jumpUrl.substringAfter('?', "")
                if (query.isNotEmpty()) {
                    for (pair in query.split('&')) {
                        val idx = pair.indexOf('=')
                        if (idx <= 0) continue
                        val k = pair.substring(0, idx)
                        val v = android.net.Uri.decode(pair.substring(idx + 1))
                        if (k in LOGIN_COOKIE_KEYS && v.isNotEmpty()) merged[k] = v
                    }
                }
            }
        }

        // ① 最后写响应头 Set-Cookie（权威，覆盖上面两者）
        for (raw in setCookies) {
            val first = raw.substringBefore(';')
            val idx = first.indexOf('=')
            if (idx > 0) {
                merged[first.substring(0, idx).trim()] = first.substring(idx + 1).trim()
            }
        }

        // 必须有 SESSDATA 才算拿到凭据
        val sessdata = merged["SESSDATA"]
        android.util.Log.i(
            TAG,
            "completeLogin: merged keys=${merged.keys} " +
                "hasSess=${!sessdata.isNullOrEmpty()}",
        )
        if (sessdata.isNullOrEmpty()) {
            // 成功状态但没有任何凭据 —— 单独报错，不要伪装成"过期"
            return QrPollResult.Failed("登录凭据获取失败，请重试")
        }

        // 落库：AuthCookieJar 后续请求会带上这些
        val cookieStr = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val mid = merged["DedeUserID"]?.toLongOrNull() ?: 0L
        store.save(
            cookie = cookieStr,
            mid = mid,
            name = store.userName,
            face = store.userFace,
        )

        // 再用 nav 补齐昵称 / 头像 / 硬币
        val user = fetchUserInfo()
        if (user != null) {
            saveUser(user)
            return QrPollResult.Success(user)
        }

        // cookie 拿到了但 nav 查不到：凭据可能不全（缺 bili_jct 等）。
        // 这种情况给明确提示，而不是"已过期"。
        return if (merged.containsKey("bili_jct")) {
            // 凭据齐全但 nav 失败 —— 多为瞬时网络问题，让 UI 提示可重试
            QrPollResult.Failed("登录信息校验失败，请重试")
        } else {
            QrPollResult.Failed("登录凭据不完整（缺少 bili_jct），请重试")
        }
    }

    /** 把 `k=v; k=v` 解析成 map。 */
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

    /**
     * 查询当前登录态并补齐用户信息。
     *
     * 未登录时返回 null（`nav` 会返回 `code=-101`）。
     *
     * ## ⚠️ null 有两种含义，调用方需自行区分
     *
     * 返回 null 可能是「**确实没登录**」，也可能是「**网络挂了**」——
     * 本方法把两者都折叠成 null（第 241 行的 catch 与第 246 行的 `!isLogin`）。
     *
     * 对「我的」页这是致命的：断网时已登录用户会看到**「未登录」+ 登录按钮**，
     * 等于凭空"被登出"。
     *
     * 需要区分时用 [hasLocalCredential]：本地有 cookie + 这里返回 null
     * = 网络/服务端问题；本地无 cookie = 真的没登录。
     * （见 [com.example.biliv3.ui.profile.ProfileViewModel.refresh]）
     */
    suspend fun fetchUserInfo(): UserInfo? {
        val json = try {
            api.getRaw(
                path = Endpoints.NAV_LOGIN,
                query = emptyMap(),
                signed = false,
            )
        } catch (_: Exception) {
            return null
        }
        // ⚠️ 未登录时 code=-101，但 data 里仍有 wbi_img —— 不能因 code!=0 直接抛错
        val data = json.optJSONObject("data") ?: return null
        if (!data.optBoolean("isLogin", false)) return null

        return UserInfo(
            mid = data.optLong("mid", 0L),
            name = data.optString("uname"),
            face = data.optString("face"),
            coins = data.optDouble("money", 0.0).toInt(),
            level = data.optInt("level_info", 0),
        )
    }

    /**
     * 拉取设备指纹 `buvid3` / `buvid4`。
     *
     * 首次启动调用一次即可。不带 buvid 的请求在 B 站看来是"全新设备"，
     * 风控命中率显著更高（`Endpoints.FINGER_SPI` 的注释已说明，
     * 但此前代码从未调用）。
     */
    suspend fun fetchBuvid(): Pair<String, String>? {
        if (store.hasBuvid) return store.buvid3 to store.buvid4
        return try {
            val json = api.getRaw(
                path = Endpoints.FINGER_SPI,
                query = emptyMap(),
                signed = false,
            )
            val data = json.optJSONObject("data") ?: return null
            val b3 = data.optString("b_3")
            val b4 = data.optString("b_4")
            if (b3.isEmpty()) return null
            store.saveBuvid(b3, b4)
            b3 to b4
        } catch (_: Exception) {
            null
        }
    }

    /** 退出登录。 */
    fun logout() = store.clear()

    // ---------------- WebView 登录 ----------------

    /**
     * 采纳 WebView 里拿到的 cookie。
     *
     * ## 为什么需要这一步
     *
     * WebView 的 cookie 存在系统级 `CookieManager`，与本应用 OkHttp 的
     * `CookieJar` 是**两套独立存储**。不显式转存的话，表现就是
     * "在 WebView 里登录成功了，但 App 里还是未登录"。
     *
     * 转存后所有 API 请求都会带上登录态。
     */
    fun adoptCookie(cookie: String) {
        store.cookie = cookie
    }

    /** 保存用户展示信息（昵称/头像/mid）。 */
    fun saveUser(user: UserInfo) {
        store.save(
            cookie = store.cookie,
            mid = user.mid,
            name = user.name,
            face = user.face,
        )
    }

    // ================= 手机号 / 短信登录：当前不可用，勿调用 =================
    //
    // 下面三个方法（sendSmsCode / loginBySms / loginByPassword）的参数与
    // cookie 落库链路都是对的，但**实测当前无法工作**：
    //
    //   x/passport-login/captcha        ->  geetest = true
    //   x/passport-login/web/login/sms  ->  -400 请求错误
    //
    // 即 B 站对短信 / 密码登录**强制要求极验图形验证**。极验 token 由
    // 极验服务端签发，本地算不出来 —— 想绕过只能复现其加密逻辑
    // （每次改版失效）或接打码平台（付费 + 违反 ToS）。
    //
    // 因此手机号 / 密码登录**改由 `WebViewLoginScreen` 加载官方登录页**
    // 实现（覆盖手机号 / 密码 / 扫码 / 第三方全部方式）。
    //
    // 这三个方法目前**无任何调用点**，保留是为了将来 B 站放宽风控时
    // 能快速恢复。**不要接入 UI** —— 会直接失败。
    // ========================================================================

    /**
     * 发送短信验证码。
     *
     * ## ⚠️ 实测状态（2026-09-28）：服务端要求先过极验
     *
     * 实测 `x/passport-login/captcha` 返回 **`geetest = true`**，
     * 即 B 站当前对短信登录**强制要求图形验证**。
     *
     * 本方法参数本身是对的（实测返回 `-105 验证码错误`，说明请求已被
     * 受理并走到风控校验），但没有极验 token 时**后续登录仍会失败**。
     *
     * @param cid 国家码，中国大陆为 86
     */
    suspend fun sendSmsCode(phone: String, cid: Int = 86): Result<Unit> {
        if (phone.isBlank()) return Result.failure(IllegalArgumentException("请输入手机号"))
        return runCatching {
            val json = api.postPassportForm(
                path = Endpoints.SMS_SEND,
                form = mapOf(
                    "cid" to cid.toString(),
                    "tel" to phone,
                    "source" to "main-fe-header",
                    "csrf" to store.biliJct,
                ),
            )
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw BiliException(code, json.optString("message", "验证码发送失败"))
            }
        }
    }

    /**
     * 短信验证码登录。
     *
     * ## ⚠️ 已知限制：当前 B 站要求极验，此路径大概率失败
     *
     * 实测 `login/sms` 在无完整网页会话时返回 `-400 请求错误`，
     * 且 `captcha` 接口返回 `geetest = true`。
     *
     * 也就是说：**光有手机号 + 验证码不足以登录**，服务端还要求一个
     * 由极验（geetest）SDK 生成的 `captcha_key`。要补齐需要内嵌
     * WebView 加载极验页面，属于独立工作量。
     *
     * 当前保留此实现（参数、cookie 落库链路都是对的），
     * 一旦补齐极验即可直接生效。UI 已明确引导用户优先用扫码。
     */
    suspend fun loginBySms(
        phone: String,
        code: String,
        cid: Int = 86,
    ): Result<UserInfo?> {
        if (phone.isBlank()) return Result.failure(IllegalArgumentException("请输入手机号"))
        if (code.isBlank()) return Result.failure(IllegalArgumentException("请输入验证码"))

        return runCatching {
            val json = api.postPassportForm(
                path = Endpoints.SMS_LOGIN,
                form = mapOf(
                    "cid" to cid.toString(),
                    "tel" to phone,
                    "code" to code,
                    "source" to "main-fe-header",
                    "csrf" to store.biliJct,
                ),
            )
            val codeVal = json.optInt("code", -1)
            if (codeVal != 0) {
                throw BiliException(codeVal, json.optString("message", "登录失败"))
            }
            finishLogin()
        }
    }

    /**
     * 账号密码登录。
     *
     * ⚠️ B 站要求密码用 RSA 公钥加密后提交，不能发明文。
     * 公钥从 [Endpoints.LOGIN_KEY] 现取（服务端会轮换，不能缓存）。
     *
     * ## 已知限制
     *
     * 若账号触发**极验/图形验证码**（`-105` 且响应带 `geetest` 字段），
     * 当前实现无法完成 —— 需要内嵌 WebView 走极验流程。
     * 这种情况会明确提示改用扫码登录。
     */
    suspend fun loginByPassword(
        username: String,
        password: String,
    ): Result<UserInfo?> {
        if (username.isBlank()) return Result.failure(IllegalArgumentException("请输入账号"))
        if (password.isBlank()) return Result.failure(IllegalArgumentException("请输入密码"))

        return runCatching {
            val keyJson = api.getRaw(
                path = Endpoints.LOGIN_KEY,
                query = emptyMap(),
                signed = false,
                host = Endpoints.PASSPORT_HOST,
            )
            val data = keyJson.optJSONObject("data")
            val hash = data?.optString("hash").orEmpty()
            val key = data?.optString("key").orEmpty()
            if (hash.isEmpty() || key.isEmpty()) {
                throw BiliException(-1, "无法获取登录公钥")
            }

            val encrypted = RsaCrypto.encrypt(password, key)
                ?: throw BiliException(-1, "密码加密失败")

            val json = api.postPassportForm(
                path = Endpoints.PWD_LOGIN,
                form = mapOf(
                    "username" to username,
                    "password" to encrypted,
                    "keep" to "0",
                    "source" to "main-fe-header",
                    "token" to hash,
                    "go_url" to "https://www.bilibili.com",
                    "csrf" to store.biliJct,
                ),
            )

            val codeVal = json.optInt("code", -1)
            if (codeVal != 0) {
                val needsCaptcha = json.optJSONObject("data")
                    ?.optJSONObject("geetest") != null
                throw BiliException(
                    codeVal,
                    if (needsCaptcha) {
                        "该账号需要图形验证，请改用扫码登录"
                    } else {
                        json.optString("message", "登录失败")
                    },
                )
            }
            finishLogin()
        }
    }

    /**
     * 登录成功后的收尾：补用户信息并落库。
     *
     * cookie 本身已由 `AuthCookieJar` 在响应阶段写入，
     * 这里补的是昵称/头像等展示字段。
     */
    private suspend fun finishLogin(): UserInfo? {
        val user = fetchUserInfo()
        if (user != null) {
            store.save(
                cookie = store.cookie,
                mid = user.mid,
                name = user.name,
                face = user.face,
            )
        }
        return user
    }

    companion object {
        /** 未扫码。 */
        const val QR_WAITING = 86101

        /** 已扫码待确认。 */
        const val QR_SCANNED = 86102

        /** 已过期。 */
        const val QR_EXPIRED = 86038

        /** 登录成功。 */
        const val QR_SUCCESS = 0

        /**
         * 从 `data.url` 里提取的登录凭据字段白名单。
         *
         * 跳转 URL 里还带很多非凭据参数（`gourl`、`crossDomain` 等），
         * 只取这几个必需项，避免把噪声写进 cookie。
         */
        private val LOGIN_COOKIE_KEYS = setOf(
            "SESSDATA",
            "bili_jct",
            "DedeUserID",
            "DedeUserID__ckMd5",
            "sid",
        )

        /**
         * 日志 tag。
         *
         * 扫码登录是"现象与预期不符"的高发区（用户看到"已过期"但实际
         * 可能是别的原因）。没有日志就只能靠猜，所以关键节点都留痕：
         * 每次 poll 的 innerCode / Set-Cookie 数量 / data.url，
         * 以及 completeLogin 汇总到哪些字段。
         *
         * 查看：`adb logcat -s BiliAuth`
         */
        private const val TAG = "BiliAuth"
    }
}

/** 二维码内容与轮询 key。 */
data class QrCode(
    /** 需要渲染成二维码的 URL。 */
    val content: String,
    /** 轮询用。 */
    val key: String,
)

/** 轮询结果。 */
sealed interface QrPollResult {
    /** 还没扫码。 */
    data object Waiting : QrPollResult

    /** 扫了，等用户在手机上确认。 */
    data object Scanned : QrPollResult

    /** 二维码过期，需要重新生成。 */
    data object Expired : QrPollResult

    /**
     * 拿到「成功」状态但登录没走完（凭据缺失 / 校验失败 / 未知状态）。
     *
     * ⚠️ 必须与 [Expired] 区分开：原实现把这类情况归为 Expired，
     * 导致用户看到"扫码成功却提示已过期"，完全误导。
     */
    data class Failed(val message: String) : QrPollResult

    /** 登录成功。 */
    data class Success(val user: UserInfo?) : QrPollResult
}

/** 登录用户信息。 */
data class UserInfo(
    val mid: Long,
    val name: String,
    val face: String,
    val coins: Int,
    val level: Int,
) {
    /** 头像地址。走统一构造（含 http→https + 剥离已有后缀防双后缀）。 */
    fun faceUrl(size: Int = 120): String =
        com.example.biliv3.data.model.CoverUrls.avatar(face, size)
}
