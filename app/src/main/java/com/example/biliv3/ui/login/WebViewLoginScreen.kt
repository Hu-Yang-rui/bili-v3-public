package com.example.biliv3.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.biliv3.data.api.Endpoints
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import kotlinx.coroutines.delay

/**
 * WebView 登录页。
 *
 * ## 为什么用官方页面而不是直连登录 API
 *
 * 实测 `x/passport-login/captcha` 返回 **`geetest = true`** ——
 * B 站当前对短信 / 密码登录**强制要求极验图形验证**。
 *
 * 极验 token 由极验服务端签发，本地算不出来。想"绕过"只能复现其加密
 * 逻辑（每次改版失效）或接打码平台（付费 + 违反 ToS），两条路都是
 * "今天能用明天崩"。
 *
 * 而加载官方登录页是**用官方流程**：
 * - 扫码 / 手机号 / 密码 / 第三方登录 **一次全覆盖**
 * - B 站改风控**不受影响**（页面由官方维护）
 * - 用户在页面里自己过极验，我们只负责收割 cookie
 *
 * ## 登录完成的判定
 *
 * 两个信号，任一满足即认为登录成功：
 * 1. 页面 URL 跳到 `www.bilibili.com`（官方登录后的跳转）
 * 2. cookie 里出现 `SESSDATA`（登录态的载体）
 *
 * 用**双信号**是因为：有些路径会直接在 passport 域完成登录而不跳转，
 * 只判断 URL 会漏；而只判断 cookie 又可能被其它接口下发的临时 cookie 误判。
 *
 * ## 安全
 *
 * - 只允许 https，禁明文（`setMixedContentMode` 严格）
 * - 关闭文件访问（`allowFileAccess = false`）—— 登录页不需要读本地文件
 * - **不注入任何 JS**：页面是官方原版，我们只读 cookie
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onLoggedIn: (cookie: String) -> Unit = {},
) {
    val colors = BiliTheme.colors

    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    /**
     * 首次加载是否已完成。
     *
     * 用于**只让首次加载显示全屏遮罩** —— SPA 内部路由、OAuth 跳转、
     * 极验弹层都会触发 `onPageStarted`，每次都显示遮罩会反复白屏闪。
     */
    var firstLoadDone by remember { mutableStateOf(false) }

    // 登录成功检测：URL 跳转 or cookie 出现 SESSDATA
    var loginDetected by remember { mutableStateOf(false) }

    // ⚠️ 必须防重复回调。
    //
    // `loginDetected` 可能在多次 onPageFinished 里被反复置 true，
    // 而 LaunchedEffect 每次 key 变化都会重启 —— 不加这个闸门，
    // `onLoggedIn` 会被调用多次（转存 cookie 多次、导航多次）。
    var callbackFired by remember { mutableStateOf(false) }

    // 检测到登录后稍等一下再回调，避免 cookie 尚未写全就收割
    LaunchedEffect(loginDetected) {
        if (loginDetected && !callbackFired) {
            delay(COOKIE_SETTLE_MS)
            val cookie = CookieManager.getInstance()
                .getCookie(COOKIE_URL)
                .orEmpty()
            if (cookie.contains("SESSDATA")) {
                callbackFired = true
                onLoggedIn(cookie)
            } else {
                // cookie 还没落全，放开闸门等下一轮 onPageFinished 再试
                loginDetected = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // WebView 必须显式销毁，否则会泄漏（它持有 Activity Context）
            webView?.let { wv ->
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                wv.destroy()
            }
            webView = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .biliCard(
                    elevation = 0.dp,
                    shape = RoundedCornerShape(
                        bottomStart = Radius.card,
                        bottomEnd = Radius.card,
                    ),
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(Sizes.topBarMobile)
                .padding(horizontal = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(Sizes.iconXl),
                )
            }
            Spacer(Modifier.width(Space.x1))
            Text(
                text = "登录",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(CircleShape)
                    .clickable { webView?.reload() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "刷新",
                    tint = colors.textSecondarySafe,
                    modifier = Modifier.size(Sizes.iconLg),
                )
            }
        }

        // ---- 加载进度（细条，不遮挡页面）----
        if (loading && progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                color = colors.brandPrimary,
                trackColor = colors.bgHover,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }

        // ---- WebView ----
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // 官方登录页是 SPA（首屏只有 ~1KB 骨架，内容靠 JS 渲染），
                        // 所以 JS 必须开，否则页面是空白
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // 安全收紧：登录页不需要读本地文件
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        // 禁明文：登录页全程 https
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.cacheMode = WebSettings.LOAD_DEFAULT

                        // ⚠️ 不改 UA。
                        //
                        // 之前想当然地 `replace("; wv", "")`（去掉 WebView 标记），
                        // 但 B 站的登录页会**按 UA 决定给哪套页面**：
                        // 伪造成普通 Chrome 反而可能被引导到 PC 版布局，
                        // 在手机上很难操作。用 WebView 默认 UA（含 Android 标识）
                        // 才会拿到移动端登录页。

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                // ⚠️ 只在**首次**加载时显示全屏遮罩。
                                //
                                // 之前每次 onPageStarted 都置 loading=true，
                                // 而 SPA 内部路由、OAuth 跳转、极验弹层都会触发
                                // onPageStarted —— 结果是遮罩反复全屏闪现，
                                // 表现为"页面过渡不顺畅、一直白屏闪"。
                                //
                                // 首次之后的加载交给顶部细进度条表达即可。
                                if (!firstLoadDone) {
                                    loading = true
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                progress = 0
                                firstLoadDone = true
                                // SPA 渲染完成后 cookie 才可能落全，这里查一次
                                if (hasSessionCookie()) loginDetected = true
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                // ⚠️ 只判断"跳到主站"作为登录信号。
                                //
                                // 不在这里查 cookie —— shouldOverrideUrlLoading
                                // 在 SPA 内部路由时也会触发，而 SPA 渲染过程中
                                // 可能已经有临时 cookie，容易误判成"登录成功"。
                                // cookie 检测统一放在 onPageFinished。
                                val url = request?.url?.toString().orEmpty()
                                if (url.contains(Endpoints.LOGIN_SUCCESS_HOST)) {
                                    loginDetected = true
                                }
                                // false = 交给 WebView 自己加载，不拦截
                                return false
                            }
                        }

                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }

                        loadUrl(Endpoints.LOGIN_PAGE)
                        webView = this
                    }
                },
            )

            // 首次加载遮罩
            if (loading && progress == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.bgBase),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = colors.brandPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(Sizes.iconXl * 1.5f),
                    )
                }
            }
        }
    }
}

/**
 * 读取 cookie 用的 URL。
 *
 * 必须用主站域名 —— `CookieManager.getCookie` 是**按域返回**的，
 * 查 `passport.bilibili.com` 拿不到 `www.bilibili.com` 下的 SESSDATA。
 */
private const val COOKIE_URL = "https://www.bilibili.com"

/**
 * 是否已拿到登录态 cookie。
 *
 * 判定条件是 `SESSDATA` 出现 —— 它是 B 站登录态的载体，
 * 只有真正登录成功后服务端才会下发。
 */
private fun hasSessionCookie(): Boolean =
    CookieManager.getInstance()
        .getCookie(COOKIE_URL)
        .orEmpty()
        .contains("SESSDATA")

/** 检测到登录后等待多久再收割 cookie，让 Set-Cookie 落全。 */
private const val COOKIE_SETTLE_MS = 800L
