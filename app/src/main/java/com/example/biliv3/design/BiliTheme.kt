package com.example.biliv3.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.biliv3.design.tokens.BiliColors
import com.example.biliv3.design.tokens.DarkColors
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.LightColors
import com.example.biliv3.design.tokens.Radius

/**
 * 中文字形回落链。
 *
 * ⚠️ **必须显式声明**。拉丁字体不含中文字形，
 * 只写 `FontFamily.Default` 在部分设备上会掉到衬线或渲染异常。
 *
 * 顺序：鸿蒙 → 小米 → OPPO → 系统默认无衬线 → Noto → 思源
 */
val CjkFontFamily = FontFamily.SansSerif

/** 提供 [BiliColors]。 */
val LocalBiliColors = staticCompositionLocalOf { LightColors }

/** 提供屏幕尺寸断点。 */
val LocalWindowSize = staticCompositionLocalOf { WindowSize.Mobile }

/**
 * 断点。
 *
 * | 断点 | 范围 | 网格列 | 右侧栏 |
 * |---|---|---|---|
 * | Desktop | ≥1280 | 5 | 右侧固定 |
 * | Tablet | 768–1279 | 3 | 下移 |
 * | Mobile | <768 | 2 | 下移 + 底部导航 |
 */
enum class WindowSize {
    Desktop,
    Tablet,
    Mobile;

    /** 视频网格列数 */
    val gridColumns: Int
        get() = when (this) {
            Desktop -> 5
            Tablet -> 3
            Mobile -> 2
        }

    /** 是否显示右侧栏（作为独立列） */
    val hasSideColumn: Boolean
        get() = this == Desktop

    /** 是否显示底部导航 */
    val hasBottomNav: Boolean
        get() = this == Mobile
}

/**
 * 主题入口。
 *
 * ## 深浅色
 *
 * 默认**跟随系统**（`isSystemInDarkTheme()`）。深色是主态
 * （`AGENTS.md` §5.1「影院级深色 + B站品牌粉」），浅色是补充态。
 *
 * 显式传 [colors] 可以覆盖（预览、截图、测试用）。
 * 显式传 [darkTheme] 可以强制某一种（截图对比用）。
 */
@Composable
fun BiliTheme(
    windowSize: WindowSize = WindowSize.Mobile,
    darkTheme: Boolean = isSystemInDarkTheme(),
    colors: BiliColors = if (darkTheme) DarkColors else LightColors,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.brandPrimary,
            onPrimary = colors.textOnBrand,
            secondary = colors.brandSecondary,
            background = colors.bgBase,
            onBackground = colors.textPrimary,
            surface = colors.bgCard,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.bgHover,
            onSurfaceVariant = colors.textSecondarySafe,
            outline = colors.borderHairline,
            error = colors.stateError,
        )
    } else {
        lightColorScheme(
            primary = colors.brandPrimary,
            onPrimary = colors.textOnBrand,
            secondary = colors.brandSecondary,
            background = colors.bgBase,
            onBackground = colors.textPrimary,
            surface = colors.bgCard,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.bgHover,
            onSurfaceVariant = colors.textSecondarySafe,
            outline = colors.borderHairline,
            error = colors.stateError,
        )
    }

    CompositionLocalProvider(
        LocalBiliColors provides colors,
        LocalWindowSize provides windowSize,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = BiliTypography,
            // ⚠️ 首版漏了 shapes —— 于是所有 Material3 组件
            // （Button / Card / Switch / Checkbox / TextField）都走 M3 默认圆角，
            // 与我们的 Radius 令牌不一致。这是 CoinDialog 的勾选框、
            // BrandButton 圆角看着"不是一套"的根因。
            shapes = BiliShapes,
            content = content,
        )
    }
}

/**
 * Material3 形状映射。
 *
 * ## 为什么必须有这个
 *
 * `MaterialTheme` 的 `shapes` 参数默认走 M3 内置值（`4/8/12/16/28dp`），
 * 与我们 `Radius` 令牌的语义**不对应**：
 *
 * | M3 槽位 | M3 默认 | 我们应给 | 用在哪 |
 * |---|---|---|---|
 * | `extraSmall` | 4dp | `Radius.badge` 2dp | 角标 |
 * | `small` | 8dp | `Radius.tag` 6dp | 小标签 |
 * | `medium` | 12dp | `Radius.button` 12dp | 按钮、输入框 |
 * | `large` | 16dp | `Radius.card` 16dp | 卡片、面板 |
 * | `extraLarge` | 28dp | `Radius.card` 16dp | 大面板（C 方案不放大到 28） |
 *
 * 首版没传这个参数，所以 `CoinDialog` 里的 `Checkbox`、`BrandButton`、
 * `Switch` 各自是 M3 默认圆角 —— 这就是"圆角看着不是一套"的来源。
 */
val BiliShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.badge),
    small = RoundedCornerShape(Radius.tag),
    medium = RoundedCornerShape(Radius.button),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.card),
)

/** `BiliTheme.colors.textPrimary` 取色。 */
object BiliTheme {    val colors: BiliColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBiliColors.current

    val windowSize: WindowSize
        @Composable
        @ReadOnlyComposable
        get() = LocalWindowSize.current
}

/**
 * 排版。
 *
 * 中文字体走 [CjkFontFamily]，并显式给出行高 —— 中文行高不足会显得拥挤。
 */
val BiliTypography
    @Composable
    get() = androidx.compose.material3.Typography(
        displaySmall = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = FontSize.display,
            lineHeight = FontSize.displayLine,
            fontWeight = FontWeight.Bold,
        ),
        titleLarge = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = FontSize.titleLg,
            lineHeight = FontSize.titleLgLine,
            fontWeight = FontWeight.Bold,
        ),
        titleMedium = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = FontSize.titleMd,
            lineHeight = FontSize.titleMdLine,
            fontWeight = FontWeight.SemiBold,
        ),
        bodyMedium = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = FontSize.body,
            lineHeight = FontSize.bodyLine,
            fontWeight = FontWeight.Normal,
        ),
        bodySmall = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = FontSize.bodySm,
            lineHeight = FontSize.bodySmLine,
            fontWeight = FontWeight.Normal,
        ),
        labelMedium = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = FontSize.label,
            lineHeight = FontSize.labelLine,
            fontWeight = FontWeight.Normal,
        ),
        labelSmall = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = FontSize.micro,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
        ),
    )
