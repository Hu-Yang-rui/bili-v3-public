package com.example.biliv3.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.tokens.BiliColors
import com.example.biliv3.design.tokens.Elevation
import com.example.biliv3.design.tokens.Radius

/**
 * C 方案（社区感）的**卡片表面**。
 *
 * ## 为什么必须抽成一个函数
 *
 * C 方案的核心是"卡片分层"：内容浮在白卡上，白卡浮在页面底上。
 * 但分层的**实现手段在两个主题下完全不同**：
 *
 * | 主题 | 手段 | 原因 |
 * |---|---|---|
 * | 浅色 | `Modifier.shadow(3dp)` | 白卡 + 浅灰底，投影清晰可见 |
 * | 深色 | `Modifier.border(1dp, 10%白)` | **黑底黑影，投影渲染出来几乎为零** |
 *
 * 如果每个调用点自己写这个分支，会：
 * 1. 漏掉几处（深色下那几处就没有分层，看着像"平铺"）
 * 2. 分支写反（深色下用 shadow = 白写）
 * 3. 圆角/描边色各写一份，改一次要改十几处
 *
 * 所以收敛到这里。**页面里不要再自己拼 `shadow + background + clip`**。
 *
 * ## 用法
 *
 * ```kotlin
 * Column(
 *     modifier = Modifier
 *         .fillMaxWidth()
 *         .biliCard()          // ← 圆角 + 背景 + 分层（按主题自动选）
 *         .padding(Space.x4)
 * )
 * ```
 *
 * ## 为什么不直接用 Material3 `Card`
 *
 * M3 `Card` 带自己的 `containerColor` / `elevation` / `shape` 三套参数，
 * 且会跟随 `MaterialTheme` 的 `colorScheme.surface` —— 而我们的
 * `bgCard` 与 `colorScheme.surface` 语义不完全一致（前者是"卡片"，
 * 后者是"表面"，M3 内部还会按 elevation 自动加 tint）。
 * 自己拼更可控，也便于 C 方案在深浅两侧走不同手段。
 *
 * @param elevation 浮起高度。默认 [Elevation.card]（3dp）。
 *   传 `0.dp` 表示"不浮起但仍要卡片底"（如顶栏、工具条这类通栏元素）。
 * @param shape 圆角。默认 [Radius.card]（16dp）。
 */
@Composable
fun Modifier.biliCard(
    elevation: Dp = Elevation.card,
    shape: RoundedCornerShape = RoundedCornerShape(Radius.card),
    color: Color = BiliTheme.colors.bgCard,
): Modifier {
    val colors = BiliTheme.colors
    val isDark = colors.bgBase.luminance() < 0.5f

    return this
        .then(
            // ⚠️ shadow 必须在 clip 之前 —— shadow 会按 shape 绘制投影，
            // 顺序反了投影会被裁掉，表现为"只有白块没有浮起"。
            if (!isDark && elevation > 0.dp) {
                Modifier.shadow(elevation = elevation, shape = shape, clip = false)
            } else {
                Modifier
            },
        )
        .clip(shape)
        .background(color)
        .then(
            // 深色下用描边替代投影（投影不可见，见上方表格）
            if (isDark) {
                Modifier.border(width = 1.dp, color = colors.borderHairline, shape = shape)
            } else {
                Modifier
            },
        )
}

/**
 * 当前主题是否为深色。
 *
 * 判断依据是 `bgBase` 的**相对亮度**，而不是 `isSystemInDarkTheme()` ——
 * 因为主题可以被显式覆盖（截图对比、`@Preview` 里传 `darkTheme = true`），
 * 此时系统值会给出错误答案。
 */
val BiliColors.isDark: Boolean
    @Composable
    @ReadOnlyComposable
    get() = bgBase.luminance() < 0.5f
