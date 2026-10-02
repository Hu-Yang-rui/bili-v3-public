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
 * ---
 *
 * ## 原则
 *
 * 1. **动效只表达「状态变了」**，不做纯展示编排
 * 2. **播放器首帧速度永远优先于转场动画**
 * 3. 每个动画必须显式传 `animationSpec` 且来自这里（不写裸 `tween(300)`）
 *
 * ## 时长阶梯
 *
 * 收敛成四档，避免"到处都是 180/200/220/240"：
 *
 * | 档 | 时长 | 用途 |
 * |---|---|---|
 * | 即时反馈 | 100ms | 按压 |
 * | 短 | 160ms | 淡入淡出、hover |
 * | 中 | 220ms | 页面转场、面板展开 |
 * | 长 | 320ms | 大面积过渡（仅少量场景） |
 */
object Motion {
    // ---------------- 时长 ----------------

    /** 按压反馈。 */
    const val PRESS_MS = 100

    /** 淡入淡出、hover、图标切换。 */
    const val FADE_MS = 160

    /** 页面转场、面板展开。 */
    const val PAGE_MS = 220

    /** 大面积过渡（如全屏进出）。 */
    const val LONG_MS = 320

    /** 骨架屏微光一轮。 */
    const val SHIMMER_MS = 1200

    /** 图片淡入。 */
    const val IMAGE_FADE_MS = 180

    /** 轮播切换。 */
    const val CAROUSEL_MS = 400

    // ---------------- 兼容别名 ----------------
    /** @deprecated 用 [FADE_MS]。 */
    const val HOVER_MS = FADE_MS
    /** @deprecated 用 [PAGE_MS]。 */
    const val PAGE_TRANSITION_MS = PAGE_MS
    /** @deprecated 用 [FADE_MS]。 */
    const val PAGE_FADE_MS = FADE_MS
    /** @deprecated 用 [PAGE_MS]。 */
    const val SHEET_MS = PAGE_MS
    /** @deprecated 用 [FADE_MS]。 */
    const val SUGGEST_MS = FADE_MS
    /** @deprecated 用 [FADE_MS]。 */
    const val COVER_ZOOM_MS = FADE_MS

    // ---------------- 缓动 ----------------

    /**
     * 标准缓动 `cubic-bezier(0.16, 1, 0.3, 1)`。
     *
     * 前段快、后段缓 —— 主观上"响应快"，这是现代 UI 的默认手感。
     */
    val standard: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 轮播缓动。 */
    val carousel: Easing = FastOutSlowInEasing

    /** 线性（骨架屏、进度）。 */
    val linear: Easing = LinearEasing

    // ---------------- 便捷规格 ----------------

    /** 标准 tween（淡入淡出）。 */
    fun <T> fadeSpec() = tween<T>(durationMillis = FADE_MS, easing = standard)

    /** 页面级 tween。 */
    fun <T> pageSpec() = tween<T>(durationMillis = PAGE_MS, easing = standard)

    /** 弹簧（底部面板、点赞心跳）。 */
    fun <T> sheetSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 轻弹（点赞、微交互）。 */
    fun <T> popSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** @deprecated 用 [fadeSpec]。 */
    fun hoverSpec() = fadeSpec<Float>()

    /** @deprecated 用 [fadeSpec]。 */
    fun coverZoomSpec() = fadeSpec<Float>()
}

/**
 * 阴影令牌。
 *
 * ---
 *
 * ## ⚠️ 深色下阴影几乎不可见
 *
 * 黑底黑影，`Modifier.shadow()` 渲染出来接近零。
 * 所以 `biliCard()` 在深色下用**描边**分层，浅色下用**阴影**。
 *
 * ## 只保留两级
 *
 * 阴影是"重"的视觉手段，层级越多越脏。两级够表达"静止"与"浮起"。
 */
object Elevation {
    /** 静止卡片。 */
    val rest = 1.dp

    /** 浮起（hover、弹窗）。 */
    val raised = 4.dp

    /** @deprecated 用 [rest]。 */
    val card = rest
    /** @deprecated 用 [raised]。 */
    val hover = raised
}
