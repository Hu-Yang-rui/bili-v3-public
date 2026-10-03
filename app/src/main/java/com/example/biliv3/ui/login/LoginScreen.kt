package com.example.biliv3.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.biliv3.data.auth.QrCode
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

/**
 * 登录页。
 *
 * ## 两种方式
 *
 * | Tab | 实现 | 覆盖范围 |
 * |---|---|---|
 * | 扫码 | API 直连（快，不受极验影响） | 仅扫码 |
 * | 账号登录 | **WebView 加载官方页面** | 手机号 / 密码 / 扫码 / 第三方 |
 *
 * ## 为什么「账号登录」走 WebView
 *
 * 实测 `x/passport-login/captcha` 返回 **`geetest = true`** ——
 * B 站对短信 / 密码登录强制要求极验图形验证。
 *
 * 极验 token 由极验服务端签发，**本地算不出来**。想"绕过"只能复现其
 * 加密逻辑（每次改版失效）或接打码平台（付费 + 违反 ToS），
 * 两条路都是"今天能用明天崩"。
 *
 * 加载官方页面是**用官方流程**：B 站改风控不受影响，用户在页面里
 * 自己过极验，我们只负责收割 cookie。
 *
 * ## 合规
 *
 * `AGENTS.md` §4.3：「登录只用小号」「SESSDATA 走 Keystore 加密」。
 * WebView 拿到的 cookie 会转存进 `AuthStore`（EncryptedSharedPreferences）。
 */
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onLoggedIn: () -> Unit = {},
    viewModel: LoginViewModel,
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 账号登录走独立页面：WebView 需要占满整屏，嵌在 Tab 内容区里高度不够
    if (mode == LoginMode.WEB) {
        WebViewLoginScreen(
            onBack = { viewModel.switchMode(LoginMode.QR) },
            onLoggedIn = { cookie -> viewModel.adoptWebViewCookie(cookie) },
        )
        // 采纳 cookie 成功后由 ViewModel 切到 Success，这里监听并回调
        LaunchedEffect(state) {
            if (state is LoginUiState.Success) {
                delay(900)
                onLoggedIn()
            }
        }
        return
    }

    QrLoginContent(
        modifier = modifier,
        onBack = onBack,
        onLoggedIn = onLoggedIn,
        viewModel = viewModel,
    )
}

/** 扫码登录内容（默认 Tab）。 */
@Composable
private fun QrLoginContent(
    modifier: Modifier,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formError by viewModel.formError.collectAsStateWithLifecycle()
    val colors = BiliTheme.colors

    LaunchedEffect(state) {
        if (state is LoginUiState.Success) {
            delay(1200)
            onLoggedIn()
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
                // 顶栏不再是卡片：与页面同明度，靠底边一条发丝线分隔
                .ruleBottom(color = Rule.color)
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
        }

        if (state is LoginUiState.Success) {
            SuccessPanel(
                name = (state as LoginUiState.Success).user?.name.orEmpty(),
                onDone = onLoggedIn,
            )
            return
        }

        // ---- 方式切换 ----
        ModeTabs(current = LoginMode.QR, onSelect = viewModel::switchMode)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.x8, vertical = Space.x6),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QrSection(
                state = state,
                onRefresh = viewModel::generateQr,
                onConfirmManually = viewModel::confirmManually,
            )

            if (formError != null) {
                Spacer(Modifier.height(Space.x4))
                Text(
                    text = formError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = FontSize.bodySm,
                        color = colors.stateError,
                    ),
                    textAlign = TextAlign.Center,
                )
                LaunchedEffect(formError) {
                    delay(4000)
                    viewModel.clearFormError()
                }
            }

            Spacer(Modifier.height(Space.x8))
            Text(
                text = "登录后可同步历史记录、收藏与关注\n" +
                    "凭据使用 Android Keystore 加密存储，仅保存在本机",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textTertiary,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 登录方式切换 Tab。 */
@Composable
private fun ModeTabs(current: LoginMode, onSelect: (LoginMode) -> Unit) {
    val colors = BiliTheme.colors
    val items = listOf(
        LoginMode.QR to "扫码登录",
        LoginMode.WEB to "账号登录",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgCard),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { (m, label) ->
            val selected = m == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(m) }
                    .padding(vertical = Space.x3),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) colors.textPrimary else colors.textSecondarySafe,
                    ),
                )
                Spacer(Modifier.height(Space.x1))
                // 下划线始终占位，切换时高度不抖
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(Radius.badge))
                        .background(if (selected) colors.brandPrimary else Color.Transparent),
                )
            }
        }
    }
}

/** 扫码区块。 */
@Composable
private fun QrSection(
    state: LoginUiState,
    onRefresh: () -> Unit,
    onConfirmManually: () -> Unit,
) {
    val colors = BiliTheme.colors

    when (state) {
        is LoginUiState.Loading -> {
            Spacer(Modifier.height(Space.x12))
            CircularProgressIndicator(
                color = colors.brandPrimary,
                strokeWidth = Space.trackHeight,
                modifier = Modifier.size(Sizes.iconXl * 1.5f),
            )
        }

        is LoginUiState.Waiting -> {
            QrPanel(qr = state.qr, hint = "请用 B 站手机客户端扫描二维码")
            ManualConfirmHint(onConfirmManually)
        }

        is LoginUiState.Scanned -> {
            QrPanel(qr = state.qr, hint = "已扫码，请在手机上确认登录", dimmed = true)
            ManualConfirmHint(onConfirmManually)
        }

        is LoginUiState.Expired -> {
            QrPanel(qr = state.qr, hint = "二维码已过期，请刷新", dimmed = true)
            Spacer(Modifier.height(Space.x5))
            BrandButton(
                label = "刷新二维码",
                onClick = onRefresh,
                variant = BrandButtonVariant.Filled,
            )
        }

        is LoginUiState.Error -> {
            Spacer(Modifier.height(Space.x8))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.stateError,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.x5))
            BrandButton(
                label = "重新生成",
                onClick = onRefresh,
                variant = BrandButtonVariant.Outline,
            )
        }

        is LoginUiState.Success -> Unit
    }
}

@Composable
private fun ManualConfirmHint(onClick: () -> Unit) {
    val colors = BiliTheme.colors
    Spacer(Modifier.height(Space.x5))
    Text(
        text = "已扫码但没反应？点这里",
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.label,
            color = colors.textLinkSafe,
        ),
        modifier = Modifier
            // 文字链是交互元素 —— 4dp
            .clip(RoundedCornerShape(Radius.interactive))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.x2),
    )
}

/** 二维码面板。 */
@Composable
private fun QrPanel(
    qr: QrCode?,
    hint: String,
    dimmed: Boolean = false,
) {
    val colors = BiliTheme.colors

    // 二维码内容不变时不重新生成位图 —— 生成是 CPU 操作，每次重组都做会卡。
    // 内容为空（如 cookie 校验失败时的占位 QrCode）则不生成，避免 zxing 抛错。
    val bitmap = remember(qr?.content) {
        qr?.takeIf { it.content.isNotEmpty() }
            ?.let { generateQrBitmap(it.content, QR_SIZE_PX) }
    }

    Box(
        modifier = Modifier
            .size(QR_BOX)
            // 二维码是图片/面板 —— 直角（圆角只给交互元素）
            .background(colors.onOverlay),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "登录二维码",
                modifier = Modifier.size(QR_SIZE),
            )
        }

        // 已扫码 / 已过期：盖一层半透明白，让二维码"变灰"但仍可辨认
        if (dimmed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.qrSurface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Space.x4),
                )
            }
        }
    }

    Spacer(Modifier.height(Space.x5))
    Text(
        text = if (dimmed) "" else hint,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = FontSize.body,
            color = colors.textSecondarySafe,
        ),
        textAlign = TextAlign.Center,
    )
}

/** 登录成功面板。 */
@Composable
private fun SuccessPanel(
    name: String,
    onDone: () -> Unit,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Space.x8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "登录成功",
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = FontSize.titleLg,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            ),
        )
        if (name.isNotEmpty()) {
            Spacer(Modifier.height(Space.x2))
            Text(
                text = "欢迎，$name",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textSecondarySafe,
                ),
            )
        }
        Spacer(Modifier.height(Space.x5))
        BrandButton(
            label = "完成",
            onClick = onDone,
            variant = BrandButtonVariant.Filled,
        )
    }
}

/**
 * 用 zxing 把内容渲染成二维码位图。
 *
 * - `EncodeHintType.MARGIN = 0`：zxing 默认留 4 模块白边，
 *   而外层 Box 已是白底，双重白边会让二维码显得很小。
 * - `CHARACTER_SET = UTF-8`：内容含中文时不指定会乱码。
 */
private fun generateQrBitmap(content: String, sizePx: Int): android.graphics.Bitmap? =
    runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints,
        )

        val bmp = android.graphics.Bitmap.createBitmap(
            sizePx,
            sizePx,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(
                    x,
                    y,
                    if (matrix[x, y]) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    },
                )
            }
        }
        bmp
    }.getOrNull()

/** 二维码白底容器边长。 */
private val QR_BOX = 240.dp

/** 二维码本体边长（比容器小，留出白边）。 */
private val QR_SIZE = 208.dp

/** 生成位图的像素尺寸。取 640 保证高分屏下不糊。 */
private const val QR_SIZE_PX = 640
