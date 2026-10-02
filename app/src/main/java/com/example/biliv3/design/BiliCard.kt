package com.example.biliv3.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * 全应用统一的**玻璃表面**（glassmorphism）。
 *
 * ---
 *
 * ## 🔴 全量替换：所有卡片都是玻璃
 *
 * 本函数是**唯一**的卡片原语（全项目 65 处调用）。
 * 把它改成玻璃 = **整个 App 的表面语言一次性换成毛玻璃**，
 * 不需要去 65 个调用点各加一遍。
 *
 * ---
 *
 * ## 两种形态（同一份代码，自动适配）
 *
 * | 场景 | 表现 |
 * |---|---|
 * | **有视频**（详情页 / 竖屏） | 真毛玻璃：糊的是**当前视频画面** |
 * | **无视频**（首页/搜索/我的/消息） | 半透明底 + 高光边（标准玻璃拟态） |
 *
 * 第二种不是"降级" —— 静态页背后是**纯色底**，
 * 模糊纯色仍然是纯色。玻璃拟态在纯色背景上的正确形态就是
 * "半透明 + 高光边"，硬做模糊只会白费性能。
 *
 * 判定方式：读 [LocalGlassBackdrop]（由有视频的页面用
 * [ProvideGlassBackdrop] 注入）。没注入就画半透明底。
 *
 * ---
 *
 * ## 为什么用 `drawBehind` 而不是 `graphicsLayer{renderEffect}`
 *
 * `renderEffect` 的作用域是**"该层及其子树"** —— 挂在卡片上会把
 * 卡片里的**文字一起糊掉**（实测过：玻璃完美、文字消失）。
 *
 * 所以这里的做法是：**模糊在抓帧时就做完了**（见 `Glass.kt` 的 `boxBlur`），
 * 卡片只负责把那张已经模糊的小图**放大画出来**。
 *
 * 好处：
 * 1. 纯 `drawBehind` → 永不碰子树，文字永远清晰
 * 2. 模糊只算一次（1/8 小图上），65 个卡片共用同一张
 * 3. 不依赖 API 31
 *
 * ---
 *
 * ## 层次（从下到上，顺序不能错）
 *
 * ```
 * ① 模糊视频帧（仅视频页有）
 * ② 半透明玻璃色（保证文字可读）
 * ③ 高光边（1dp 白，玻璃"厚度感"的来源）
 * ④ 内容（文字/图标 —— 绝不参与任何模糊）
 * ```
 *
 * @param elevation 保留参数以兼容既有调用点。
 *   **玻璃态下忽略** —— 玻璃靠"模糊 + 高光边"分层，不靠投影。
 * @param shape 圆角。
 * @param color 玻璃染色。默认按主题取（深色 55% 黑 / 浅色 40% 白）。
 *   传值可覆盖（如需要更透明的顶栏）。
 */
@Composable
fun Modifier.biliCard(
    elevation: Dp = Elevation.card,
    shape: RoundedCornerShape = RoundedCornerShape(Radius.card),
    color: Color = Color.Unspecified,
): Modifier {
    val colors = BiliTheme.colors
    val isDark = colors.bgBase.luminance() < 0.5f
    val backdrop = LocalGlassBackdrop.current
    val frame = backdrop?.frame

    /**
     * 玻璃配方：**压在视频上** 与 **静态页面** 是两套相反的取值。
     *
     * | 场景 | 深色底 | 为什么 |
     * |---|---|---|
     * | 压在视频上 | 55% 黑 | 视频可能是亮的（白 T 恤 / 雪景），必须压暗才能保证白字可读 |
     * | 静态页面 | 12% 白 | 页面底已是 `#121114`，必须**更亮**才能浮起来 |
     *
     * ## 实测教训
     *
     * 两处都用 55% 黑时，静态页的卡片比页面底还暗 ——
     * 看起来像"在深色页面上挖了几个黑洞"，只剩一圈描边，比改之前更糟。
     *
     * 根因：**静态页背后没有东西可模糊**（纯色底）。
     * 玻璃拟态在纯色背景上的立体感只能靠"比底色亮"，
     * 这与"压在视频上"（靠压暗保证可读）是**相反**的策略。
     */
    val glassTint = when {
        color != Color.Unspecified -> color
        frame != null -> if (isDark) GlassTokens.tintDark else GlassTokens.tintLight
        else -> if (isDark) GlassTokens.surfaceTintDark else GlassTokens.surfaceTintLight
    }

    return this
        .clip(shape)
        .then(
            // ---- ① 模糊视频帧（只有视频页才有）----
            if (frame != null) {
                Modifier.drawBehind { drawGlassBackdrop(frame) }
            } else {
                Modifier
            },
        )
        // ---- ② 玻璃色 ----
        .background(glassTint)
        // ---- ③ 高光边 ----
        //
        // ⚠️ 玻璃必须**永远**有边，深浅色都是。
        // 首版在深色下用 `borderHairline`（10% 白）—— 压在视频上几乎看不见，
        // 玻璃就"塌"成了半透明色块，完全没有实体感。
        // 这里统一用更亮的 20% 白（`GlassTokens.border*`）。
        .border(
            width = 1.dp,
            color = if (isDark) GlassTokens.borderDark else GlassTokens.borderLight,
            shape = shape,
        )
        // ---- ④ 内容由调用方在此之后添加，不参与任何模糊 ----
}

/**
 * 把已模糊的视频帧按 **cover** 语义铺满当前绘制区域。
 *
 * ## 为什么不用 `drawImage(dstSize = size)`
 *
 * 那是拉伸到指定尺寸（会变形）。卡片比例各异（顶栏很扁、卡片接近方形），
 * 拉伸会把画面压成"横向拉丝" —— 一种很显眼的廉价感。
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
        // Low = 双线性 + 无 mipmap → 放大时继续柔和
        filterQuality = FilterQuality.Low,
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
