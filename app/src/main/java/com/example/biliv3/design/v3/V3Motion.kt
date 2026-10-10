package com.example.biliv3.design.v3

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * **BiliV3 设计系统 v3 —— 动效令牌**。
 *
 * ---
 *
 * # 来源
 *
 * 数值取自 iOS 27 设计系统的 `animations.json`（Apple HIG + WWDC25）。
 *
 * ## 🔴 与旧系统（`Motion`）的根本差异
 *
 * | | 旧 | 新 |
 * |---|---|---|
 * | 主手段 | `tween` + cubic-bezier | **spring**（响应/阻尼比），tween 只做淡入淡出 |
 * | 时长档 | 4 档（100/160/220/320） | **语义化时长**（tabTransition/sheetPresent/…） |
 * | 缓动 | 一条 `cubic-bezier(0.16,1,0.3,1)` | 分场景：snappy / bouncy / gentle / stiff |
 *
 * 旧系统用统一 cubic-bezier 的问题是：**它无法表达"过冲"**。
 * iOS 手感的核心恰恰是"到位时轻轻弹一下"（overshoot）——
 * 那必须用 spring，cubic-bezier 做不到（除非手写负的控制点，
 * 而那样会**两端都过冲**，不是 iOS 的行为 —— 见下）。
 *
 * ## 🔴 关于过冲的重要事实
 *
 * iOS 27 的设计系统明确记录：
 *
 * > Liquid Glass / 启动动效**只在末端过冲**；
 * > 两端都过冲（第一个控制点为负）**不是** UIKit/SwiftUI spring 的行为。
 *
 * 所以本文件**不使用** `cubic-bezier(0.34, 1.56, …)` 这类"两端过冲"的
 * CSS 近似（设计系统给它们只是因为 CSS 没有真 spring）。
 * Compose **有**真 spring，直接用。
 */
object V3Motion {

    // =====================================================================
    // 一、Spring 预设（iOS 17+ 的 spring(duration:bounce:) 语义）
    // =====================================================================

    /**
     * Spring 参数换算说明。
     *
     * iOS 用 `(response, dampingRatio)`，Compose 用 `(stiffness, dampingRatio)`。
     * 近似关系：`stiffness ≈ (2π / response)²`。
     *
     * | iOS response | Compose stiffness |
     * |---|---|
     * | 0.25 | ~630 |
     * | 0.30 | ~440 |
     * | 0.50 | ~158 |
     * | 0.55 | ~130 |
     *
     * 阻尼比**语义相同**，直接搬。
     */
    private const val STIFF_STIFFNESS = 630f
    private const val SNAPPY_STIFFNESS = 440f
    private const val BOUNCY_STIFFNESS = 158f
    private const val GENTLE_STIFFNESS = 130f

    /**
     * **snappy** —— 响应快、几乎不过冲。
     *
     * 实测（iOS 27）：`response 0.3 / dampingRatio 0.8 / bounce 0.15`。
     * 用途：**Tab 指示器**、按钮、图标状态切换。
     */
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(
        stiffness = SNAPPY_STIFFNESS,
        dampingRatio = 0.8f,
    )

    /**
     * **bouncy** —— 明显过冲，用于"有重量的物体"。
     *
     * 实测：`response 0.5 / dampingRatio 0.65 / bounce 0.3`。
     * 用途：通知出现、App 图标启动。
     *
     * ⚠️ **不要用在文字上** —— 文字回弹会显得廉价（且影响阅读）。
     */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(
        stiffness = BOUNCY_STIFFNESS,
        dampingRatio = 0.65f,
    )

    /**
     * **gentle** —— 不过冲、平稳到位。
     *
     * 实测：`response 0.55 / dampingRatio 0.825 / bounce 0`。
     * 用途：**Sheet 出现**、页面转场、大面积位移。
     */
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(
        stiffness = GENTLE_STIFFNESS,
        dampingRatio = 0.825f,
    )

    /**
     * **stiff** —— 无过冲、快速收敛。
     *
     * 实测：`response 0.25 / dampingRatio 1.0 / bounce 0`。
     * 用途：Alert、菜单、任何"必须立刻停住"的浮层。
     */
    fun <T> stiff(): FiniteAnimationSpec<T> = spring(
        stiffness = STIFF_STIFFNESS,
        dampingRatio = 1f,
    )

    // =====================================================================
    // 二、时长（语义化）
    // =====================================================================

    /**
     * 语义化时长（毫秒）。
     *
     * ⚠️ **只在必须用 tween 时用**（淡入淡出、进度条）。
     * 位移 / 尺寸 / 缩放一律走上面的 spring —— 那是 iOS 手感的来源。
     */
    object Duration {
        /** 100ms —— 按压反馈。 */
        const val micro = 100

        /** 200ms —— 淡入、菜单关闭。 */
        const val fast = 200

        /** 250ms —— 上下文菜单打开。 */
        const val contextMenu = 250

        /** 300ms —— 页面转场、Tab 切换、Sheet 关闭。 */
        const val normal = 300

        /** 350ms —— 页面推入、玻璃形变。 */
        const val page = 350

        /** 450ms —— Tab 指示器形变（实测值）。 */
        const val tabIndicator = 450

        /** 500ms —— Sheet 出现（实测值）。 */
        const val sheetPresent = 500

        /** 1200ms —— 骨架微光。 */
        const val shimmer = 1200
    }

    // =====================================================================
    // 三、缓动（tween 用）
    // =====================================================================

    /**
     * Apple 默认缓动 `cubic-bezier(0.25, 0.46, 0.45, 0.94)`。
     *
     * 注意这与旧系统的 `(0.16, 1, 0.3, 1)` **不同** ——
     * 后者是"极快启动 + 极慢收尾"（Material 风格），
     * Apple 的曲线更**匀速**，主观上更"稳"、更少"弹射感"。
     */
    val appleDefault: Easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

    /** Apple 减速 `cubic-bezier(0.0, 0.0, 0.6, 1.0)` —— 进入用。 */
    val appleEaseOut: Easing = CubicBezierEasing(0f, 0f, 0.6f, 1f)

    /** Apple 加速 `cubic-bezier(0.42, 0, 1.0, 1.0)` —— 退出用。 */
    val appleEaseIn: Easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)

    // =====================================================================
    // 四、便捷规格
    // =====================================================================

    /** 淡入淡出。 */
    fun <T> fade(): FiniteAnimationSpec<T> =
        tween(Duration.fast, easing = appleDefault)

    /** 页面转场（tween 版，给 NavHost 用）。 */
    fun <T> page(): FiniteAnimationSpec<T> =
        tween(Duration.page, easing = appleEaseOut)

    /** 骨架微光（线性）。 */
    fun <T> shimmer(): FiniteAnimationSpec<T> = tween(Duration.shimmer)

    /**
     * 页面转场的**位移距离**（像素比例）。
     *
     * iOS 的 push 是**整屏滑入**（`translateX(100%)`）。
     * 但 Android 上整屏滑入会让人觉得"慢"（要等整屏位移完）。
     *
     * 折中：滑入 **1/4 屏**（配合 350ms + easeOut），
     * 保留方向感但主观上快得多 —— 这与旧系统"1/5 屏 + 220ms"是同一个思路，
     * 只是位移略大（更明确的方向感）、时长略长（更从容）。
     */
    const val PAGE_SLIDE_FRACTION = 4

    /** 页面转场偏移（供 `slideInHorizontally` 用）。 */
    val pageSlideOffset: (Int) -> Int = { it / PAGE_SLIDE_FRACTION }

    /**
     * 页面转场的**退出位移**。
     *
     * iOS 的 push 里，被压住的页面会**左移 30% 并淡到 80%** ——
     * 这是"深度感"的来源（后面的页面退远，不是简单淡出）。
     */
    const val PAGE_EXIT_FRACTION = 10

    /** 退场偏移。 */
    val pageExitOffset: (Int) -> Int = { it / PAGE_EXIT_FRACTION }
}

/**
 * 便捷：给 `IntOffset` 用的弹簧（`AnimatedContent` 的 `slideIn` 需要）。
 *
 * 单独一个函数是因为泛型推断在那种位置经常失败。
 */
fun offsetSpring(): FiniteAnimationSpec<IntOffset> = V3Motion.gentle()
