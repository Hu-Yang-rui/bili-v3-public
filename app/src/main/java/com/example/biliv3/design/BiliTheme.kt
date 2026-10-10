package com.example.biliv3.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
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
import com.example.biliv3.design.v3.BiliV3Theme
import com.example.biliv3.design.v3.V3DarkColors
import com.example.biliv3.design.v3.V3WindowSize
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Type
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import com.example.biliv3.design.tokens.LightColors
import com.example.biliv3.design.v3.V3LightColors

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
 * 旧断点 → 新断点的映射。
 *
 * ⚠️ 两套断点的**阈值不同**（旧：768/1280；新：600/840），
 * 所以这不是恒等映射，但迁移期必须能互转。
 * 新代码请直接用 [V3WindowSize]。
 */
fun WindowSize.toV3(): V3WindowSize = when (this) {
    WindowSize.Desktop -> V3WindowSize.Expanded
    WindowSize.Tablet -> V3WindowSize.Medium
    WindowSize.Mobile -> V3WindowSize.Compact
}

/**
 * 主题入口。
 *
 * ## 🔴 浅色为默认（v1.6.9 起）
 *
 * v1.1.3 曾移除浅色主题，理由是「玻璃拟态的前提是背后有东西可模糊，
 * 而静态页背后是纯色底」。**该理由已被推翻**：
 *
 * 它针对的是「把玻璃用在**页面背景**上」—— 而本项目从未那样用。
 * 玻璃只用于**浮动层**（底部导航 / Sheet / Dialog / 播放器控件），
 * 底下**始终有内容在滚**（列表、封面、画面），
 * 所以浅色下同样有东西可模糊。
 *
 * 现在两套表并存：
 * - [LightColors]（**默认**）
 * - [DarkColors]（保留，供预览 / 显式覆盖）
 *
 * ⚠️ 两套表**都必须维护**：`V3Colors` 是 data class，新增令牌时
 * 构造参数缺一不可，所以漏一套会直接编译失败（有意的设计）。
 *
 * 显式传 [colors] 可以覆盖（预览 / 截图 / 测试用）。
 */
@Composable
fun BiliTheme(
    windowSize: WindowSize = WindowSize.Mobile,
    colors: BiliColors = LightColors,
    content: @Composable () -> Unit,
) {
    // ⚠️ 按主题明暗选 scheme —— 浅色主题配 `darkColorScheme` 会让
    //    Material 组件（水波纹、默认分割线）与页面底色相反。
    //    判据用 `bgBase` 的亮度，不另存布尔标志（避免"改了底色忘了改标志"）。
    val scheme = if (colors.bgBase.luminance() > 0.5f) lightColorScheme(
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
    ) else darkColorScheme(
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

    // 🔴 v3 全量 UI 重构：本主题现在**委托**给新主题 `BiliV3Theme`。
    //
    // 这样做的原因：迁移是逐页进行的，中间态必须能同时提供
    // 旧令牌（`BiliTheme.colors`）与新令牌（`BiliV3.colors`）。
    // 如果只提供其中一个，未迁移的页面会立刻编译不过。
    //
    // ⚠️ **迁移完成后**：删掉本文件，全部改用 `BiliV3Theme`。
    //    新代码请直接用 `BiliV3.colors`，不要再往旧表加字段。
    //
    // 🔴 传入的 `colors` 必须**映射到对应的 v3 表**：
    //    原实现写死 `V3DarkColors` —— 那样浅色主题下，
    //    页面底是浅色（旧令牌）而所有 `BiliV3.colors` 取色仍是深色，
    //    结果就是"白底 + 深色文字块"的半成品观感。
    BiliV3Theme(
        windowSize = windowSize.toV3(),
        colors = if (colors.bgBase.luminance() > 0.5f) V3LightColors else V3DarkColors,
    ) {
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
 * | `extraSmall` | 4dp | `V3Radius.xs` 4dp | 角标 |
 * | `small` | 8dp | `V3Radius.xs` 4dp | 小标签 |
 * | `medium` | 12dp | `V3Radius.xs` 4dp | 按钮、输入框（交互元素） |
 * | `large` | 16dp | `V3Radius.lg` 16dp | 底部面板、浮层 |
 * | `extraLarge` | 28dp | `V3Radius.lg` 16dp | 大面板（不放大到 28） |
 *
 * 首版没传这个参数，所以 `CoinDialog` 里的 `Checkbox`、`BrandButton`、
 * `Switch` 各自是 M3 默认圆角 —— 这就是"圆角看着不是一套"的来源。
 *
 * ⚠️ v1.2.1 起 `medium` 由 12dp 收敛到 4dp（§5.2：圆角只给交互元素 4dp）。
 */
val BiliShapes = Shapes(
    extraSmall = RoundedCornerShape(V3Radius.xs),
    small = RoundedCornerShape(V3Radius.xs),
    medium = RoundedCornerShape(V3Radius.xs),
    large = RoundedCornerShape(V3Radius.lg),
    extraLarge = RoundedCornerShape(V3Radius.lg),
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
            fontSize = V3Type.title3.fontSize,
            lineHeight = V3Type.title3.lineHeight,
            fontWeight = FontWeight.Bold,
        ),
        titleLarge = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = V3Type.headline.fontSize,
            lineHeight = V3Type.headline.lineHeight,
            fontWeight = FontWeight.Bold,
        ),
        titleMedium = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = V3Type.subheadline.fontSize,
            lineHeight = V3Type.subheadline.lineHeight,
            fontWeight = FontWeight.SemiBold,
        ),
        bodyMedium = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = V3Type.callout.fontSize,
            lineHeight = V3Type.callout.lineHeight,
            fontWeight = FontWeight.Normal,
        ),
        bodySmall = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = V3Type.footnote.fontSize,
            lineHeight = V3Type.footnote.lineHeight,
            fontWeight = FontWeight.Normal,
        ),
        labelMedium = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = V3Type.caption1.fontSize,
            lineHeight = V3Type.caption1.lineHeight,
            fontWeight = FontWeight.Normal,
        ),
        labelSmall = TextStyle(
            fontFamily = CjkFontFamily,
            fontSize = V3Type.caption2.fontSize,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
        ),
    )
