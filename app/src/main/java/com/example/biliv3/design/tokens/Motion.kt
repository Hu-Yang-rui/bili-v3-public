package com.example.biliv3.design.tokens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp

/**
 * 动效令牌。
 *
 * 原则：动效只表达「状态变了」，不做纯展示编排。
 * 播放器首帧速度永远优先于转场动画。
 */
object Motion {
    /** 卡片 hover 上浮、标题变色 */
    const val HOVER_MS = 160

    /** 封面放大（比卡片稍慢，产生层次） */
    const val COVER_ZOOM_MS = 240

    /** 按压反馈 */
    const val PRESS_MS = 100

    /** 轮播切换 */
    const val CAROUSEL_MS = 400

    /** 骨架屏微光一轮 */
    const val SHIMMER_MS = 1200

    /** 搜索联想下拉展开 */
    const val SUGGEST_MS = 180

    /**
     * 页面转场（NavHost）时长。
     *
     * ⚠️ 刻意压到 220ms。NavHost 默认约 400ms ——
     * 那 400ms 里页面已经在动、但内容还没到，主观上就是「迟钝」。
     * 220ms 接近"眨眼即完成"，保留方向感的同时消除等待感。
     */
    const val PAGE_TRANSITION_MS = 220

    /** 页面转场的纯淡入/淡出时长。比位移更短，避免叠加拖沓。 */
    const val PAGE_FADE_MS = 160

    /** 弹层/面板展开。 */
    const val SHEET_MS = 200

    /**
     * 图片淡入时长。
     *
     * 短到只用来**消除跳变**，不制造等待感。
     * 图片"啪"地直接出现会造成闪烁观感（尤其在快速滚动时）。
     */
    const val IMAGE_FADE_MS = 180

    /** 标准缓动 `cubic-bezier(0.16, 1, 0.3, 1)` */
    val standard: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 轮播缓动 ease-in-out */
    val carousel: Easing = FastOutSlowInEasing

    /** 线性（骨架屏） */
    val linear: Easing = LinearEasing

    /** 弹簧（底部弹窗） */
    fun <T> sheetSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** hover 上浮动画规格 */
    fun hoverSpec() = tween<Float>(durationMillis = HOVER_MS, easing = standard)

    /** 封面缩放动画规格 */
    fun coverZoomSpec() = tween<Float>(durationMillis = COVER_ZOOM_MS, easing = standard)
}

/**
 * 阴影令牌。轻量，只用两级。
 *
 * Compose 里用 `Modifier.shadow(elevation, shape)`，
 * 数值按 Material 的 dp 换算（1dp 阴影 ≈ `0 1px 2px`）。
 */
object Elevation {
    /** 静止：`0 1px 2px rgba(0,0,0,0.04)` */
    val rest = 1.dp

    /** hover 上浮：`0 8px 20px rgba(0,0,0,0.10)` */
    val hover = 8.dp

    /**
     * C 方案：卡片浮起高度。
     *
     * ## ⚠️ 浅色下可见，深色下**不可见**
     *
     * 深色底上投影是"黑底黑影"，`Modifier.shadow()` 渲染出来几乎为零。
     * 因此调用点必须按主题分支：
     *
     * ```kotlin
     * val dark = BiliTheme.colors.bgBase.luminance() < 0.5f
     * Modifier.then(
     *     if (dark) Modifier.border(1.dp, colors.borderHairline, shape)
     *     else Modifier.shadow(Elevation.card, shape)
     * )
     * ```
     *
     * 3dp 是实测值：再小看不出浮起，再大会让相邻卡片"挤在一起"
     * （2 列网格的横向间距只有 8~16dp）。
     */
    val card = 3.dp
}
