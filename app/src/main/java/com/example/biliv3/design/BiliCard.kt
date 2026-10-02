package com.example.biliv3.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.tokens.BiliColors
import com.example.biliv3.design.tokens.Elevation
import com.example.biliv3.design.tokens.Radius

/**
 * 全应用统一的**表面原语**。
 *
 * ---
 *
 * ## 一套风格，三种形态（自动适配，不写分支）
 *
 * | 形态 | 触发条件 | 表现 |
 * |---|---|---|
 * | **毛玻璃** | 提供 `LocalGlassBackdrop` 且有视频帧 | 模糊的视频底 + 半透明色 + 高光边 |
 * | **实心卡片** | 无视频帧（静态页） | 比页面底亮一档 + 1dp 描边/阴影 |
 * | **扁平** | 传 `flat = true` | 只有圆角裁剪，无底无边 |
 *
 * 三种形态**共用同一份调用代码** —— 页面不需要知道自己处在哪种场景。
 *
 * ---
 *
 * ## 使用规则（重要）
 *
 * | 内容 | 用什么 |
 * |---|---|
 * | 页面上**独立成块**的内容（信息卡、面板） | `biliCard()` |
 * | **列表里的行**（评论、消息、收藏项） | `flat = true`，靠留白分组 |
 * | **通栏元素**（顶栏、标签条） | `flat = true` |
 *
 * > **列表用留白分组，不用卡片分组。**
 * > 一屏内只允许一层卡片。
 *
 * ---
 *
 * ## 为什么用 `drawBehind` 而不是 `graphicsLayer{renderEffect}`
 *
 * `renderEffect` 的作用域是**"该层及其子树"** —— 挂在卡片上会把
 * 卡片里的**文字一起糊掉**。
 *
 * 所以模糊在**抓帧时**就做完了（见 `Glass.kt` 的 `boxBlur`），
 * 卡片只负责把那张已模糊的小图放大画出来。这样永不碰子树。
 *
 * @param elevation 浮起高度。深色下阴影不可见，会自动改用描边。
 * @param shape 圆角。
 * @param color 底色覆盖。默认按形态自动取。
 * @param flat 扁平模式：无底无边，只裁剪圆角（用于列表行 / 通栏元素）。
 * @param glassTint 玻璃染色覆盖（仅玻璃形态生效）。
 */
@Composable
fun Modifier.biliCard(
    elevation: Dp = Elevation.rest,
    shape: RoundedCornerShape = RoundedCornerShape(Radius.card),
    color: Color = Color.Unspecified,
    flat: Boolean = false,
    glassTint: Color = Color.Unspecified,
): Modifier {
    if (flat) {
        // 扁平：只裁剪，不画任何表面
        return this.clip(shape)
    }

    val colors = BiliTheme.colors
    val isDark = colors.bgBase.luminance() < 0.5f
    val tier = LocalDeviceTier.current
    val backdrop = LocalGlassBackdrop.current
    val frame = backdrop?.frame

    // ---- 形态判定 ----
    val useGlass = frame != null && tier.canBlur

    val bg = when {
        color != Color.Unspecified -> color
        useGlass -> if (isDark) GlassTokens.tintDark else GlassTokens.tintLight
        else -> colors.bgCard
    }

    return this
        .then(
            // 浅色下用阴影分层；深色下阴影不可见（下面改用描边）
            if (!isDark && elevation > 0.dp) {
                Modifier.shadow(elevation = elevation, shape = shape, clip = false)
            } else {
                Modifier
            },
        )
        .clip(shape)
        .then(
            // 毛玻璃底：模糊的视频帧
            if (useGlass) {
                Modifier.drawBehind { drawGlassBackdrop(frame!!) }
            } else {
                Modifier
            },
        )
        .background(bg)
        .then(
            // 描边：玻璃形态用高光边（玻璃的"厚度感"）；
            // 实心卡片用发丝线（深色下承担分层职责）
            when {
                useGlass -> Modifier.border(
                    width = 1.dp,
                    color = if (isDark) GlassTokens.borderDark else GlassTokens.borderLight,
                    shape = shape,
                )
                isDark -> Modifier.border(
                    width = 1.dp,
                    color = colors.borderHairline,
                    shape = shape,
                )
                else -> Modifier
            },
        )
}

/**
 * 把已模糊的视频帧按 **cover** 语义铺满当前绘制区域。
 *
 * ## 为什么不用 `drawImage(dstSize = size)`
 *
 * 那是拉伸到指定尺寸（会变形）。卡片比例各异，拉伸会把画面压成
 * "横向拉丝" —— 一种很显眼的廉价感。
 *
 * 所以手动算 cover：等比放大到覆盖整个区域，居中，超出部分被 `clip` 裁掉。
 */
private fun DrawScope.drawGlassBackdrop(image: ImageBitmap) {
    if (image.width <= 0 || image.height <= 0) return
    val scale = maxOf(
        size.width / image.width.toFloat(),
        size.height / image.height.toFloat(),
    )
    val dstW = image.width * scale
    val dstH = image.height * scale
    val left = (size.width - dstW) / 2f
    val top = (size.height - dstH) / 2f

    drawImage(
        image = image,
        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(image.width, image.height),
        dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(dstW.toInt(), dstH.toInt()),
        filterQuality = FilterQuality.Low,
    )
}

/**
 * 当前主题是否为深色。
 *
 * 判断依据是 `bgBase` 的**相对亮度**，而不是 `isSystemInDarkTheme()` ——
 * 因为主题可以被显式覆盖（截图对比、`@Preview`），此时系统值会给出错误答案。
 */
val BiliColors.isDark: Boolean
    @Composable
    @ReadOnlyComposable
    get() = bgBase.luminance() < 0.5f
