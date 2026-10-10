package com.example.biliv3.design.v3

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.v3.V3Type
import androidx.compose.material3.lightColorScheme

/**
 * 提供 [V3Colors]。
 *
 * ⚠️ 默认值是 [V3LightColors]（**浅色是默认主题**）。
 * 深色表仍保留（[V3DarkColors]），供主题切换/预览使用。
 */
val LocalV3Colors = staticCompositionLocalOf { V3LightColors }

/** 提供屏幕断点。 */
val LocalV3WindowSize = staticCompositionLocalOf { V3WindowSize.Compact }

/**
 * 断点。
 *
 * | 断点 | 范围 | 网格列 |
 * |---|---|---|
 * | Compact | <600 | 2 |
 * | Medium | 600–839 | 3 |
 * | Expanded | ≥840 | 4 |
 *
 * ⚠️ 阈值按 **Material 3 的 window size class** 重定（旧系统用 768/1280，
 * 那是"平板/桌面"的传统分法，但手机横屏（~800dp）会掉进 Tablet 档，
 * 拿到 3 列 —— 在 800dp 宽上 3 列太稀疏）。
 */
enum class V3WindowSize {
    Compact,
    Medium,
    Expanded;

    val gridColumns: Int
        get() = when (this) {
            Compact -> 2
            Medium -> 3
            Expanded -> 4
        }

    /** 是否显示底部导航（只有手机竖屏形态才需要）。 */
    val hasBottomNav: Boolean get() = this == Compact

    /** 内容最大宽度（超大屏时不让内容拉满）。 */
    val contentMaxWidth: Dp
        get() = when (this) {
            Compact -> Dp.Unspecified
            Medium -> 720.dp
            Expanded -> 960.dp
        }
}

/**
 * **BiliV3 主题 v3**（全量 UI 重构）。
 *
 * ---
 *
 * # 🔴 主题：浅色为默认（v1.6.9 起）
 *
 * v1.1.3 曾决定"只做深色"，理由是「静态页背后是纯色底，玻璃只能靠比底色更白
 * 来假装层次」。**该理由针对的是"把玻璃用在页面背景上"**——
 * 而本项目从未那样用：玻璃只用于**浮动层**（底部导航 / Sheet / Dialog /
 * 播放器控件），底下**始终有内容在滚**，所以浅色下同样有东西可模糊。
 *
 * 现在两套表并存（[V3LightColors] / [V3DarkColors]），默认浅色。
 * `colors` 参数可显式覆盖（预览 / 截图 / 测试用）。
 *
 * ---
 *
 * # 与旧 `BiliTheme` 的关系
 *
 * 旧主题保留（`design/BiliTheme.kt`），新主题并存 —— 这样逐页迁移时
 * 未迁移的页面仍能编译。**全部迁完后删掉旧的**（见重构说明）。
 */
@Composable
fun BiliV3Theme(
    windowSize: V3WindowSize = V3WindowSize.Compact,
    colors: V3Colors = V3LightColors,
    content: @Composable () -> Unit,
) {
    // ⚠️ Material3 的 `darkColorScheme` / `lightColorScheme` 只影响
    //    Material 组件的默认取色，而本项目的组件都走 `BiliV3.colors`。
    //    所以这里按**主题明暗**选对的那个 —— 否则 Material 的
    //    默认水波纹、分割线等会与主题相反。
    val scheme = if (colors.isLight) lightColorScheme(
        // 主色 = 交互蓝（旧系统用品牌粉，见 V3Colors.brand 的说明）
        primary = colors.brand,
        onPrimary = colors.labelOnBrand,
        secondary = colors.brandBili,
        onSecondary = colors.labelOnBrand,
        background = colors.bgPrimary,
        onBackground = colors.labelPrimary,
        surface = colors.bgSecondary,
        onSurface = colors.labelPrimary,
        surfaceVariant = colors.fillSecondary,
        onSurfaceVariant = colors.labelSecondary,
        outline = colors.separator,
        outlineVariant = colors.separator,
        error = colors.stateError,
        onError = colors.labelPrimary,
        scrim = colors.scrim,
    ) else darkColorScheme(
        primary = colors.brand,
        onPrimary = colors.labelOnBrand,
        secondary = colors.brandBili,
        onSecondary = colors.labelOnBrand,
        background = colors.bgPrimary,
        onBackground = colors.labelPrimary,
        surface = colors.bgSecondary,
        onSurface = colors.labelPrimary,
        surfaceVariant = colors.fillSecondary,
        onSurfaceVariant = colors.labelSecondary,
        outline = colors.separator,
        outlineVariant = colors.separator,
        error = colors.stateError,
        onError = colors.labelPrimary,
        scrim = colors.scrim,
    )

    CompositionLocalProvider(
        LocalV3Colors provides colors,
        LocalV3WindowSize provides windowSize,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = V3Typography,
            shapes = V3Shapes,
            content = content,
        )
    }
}

/**
 * Material3 形状映射。
 *
 * ## ⚠️ 这里与旧系统**不同**
 *
 * 旧系统把 `medium`（按钮槽位）压到 **4dp**（"圆角只给交互元素 4dp"）。
 * 新系统按 iOS 27 实测：**按钮是胶囊**（实测所有 bordered/glass 按钮
 * `cornerRadius = 1000`），无边框大按钮是 12dp。
 *
 * 所以：
 * - `extraSmall` → 8dp（小控件）
 * - `small` → 12dp（按钮）
 * - `medium` → 20dp（菜单 / 中等容器）
 * - `large` → 34dp（Sheet / Alert，实测值）
 * - `extraLarge` → 38dp（Popover，实测值）
 */
val V3Shapes = Shapes(
    extraSmall = RoundedCornerShape(V3Radius.sm),
    small = RoundedCornerShape(V3Radius.md),
    medium = RoundedCornerShape(V3Radius.xl),
    large = RoundedCornerShape(V3Radius.sheet),
    extraLarge = RoundedCornerShape(V3Radius.popover),
)

/** 取色 / 断点的入口。 */
object BiliV3 {
    val colors: V3Colors
        @Composable
        @ReadOnlyComposable
        get() = LocalV3Colors.current

    val windowSize: V3WindowSize
        @Composable
        @ReadOnlyComposable
        get() = LocalV3WindowSize.current
}

/**
 * 排版映射到 Material3 槽位。
 *
 * ⚠️ 槽位**只是给 M3 内置组件兜底**（TextField / Button 内部会用）。
 * 页面代码请直接用 `V3Type.xxx`，不要走 `MaterialTheme.typography` ——
 * 后者的槽位名（`bodyMedium`）读不出语义，正是旧系统"到处随手挑一个"的原因。
 */
val V3Typography: Typography
    @Composable
    get() = Typography(
        displayLarge = V3Type.largeTitle,
        displayMedium = V3Type.title1,
        displaySmall = V3Type.title2,
        headlineLarge = V3Type.title2,
        headlineMedium = V3Type.title3,
        headlineSmall = V3Type.headline,
        titleLarge = V3Type.title3,
        titleMedium = V3Type.headline,
        titleSmall = V3Type.subheadline,
        bodyLarge = V3Type.body,
        bodyMedium = V3Type.callout,
        bodySmall = V3Type.subheadline,
        labelLarge = V3Type.headline,
        labelMedium = V3Type.footnote,
        labelSmall = V3Type.caption1,
    )
