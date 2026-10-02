package com.example.biliv3.design

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 设备性能分级。
 *
 * ---
 *
 * ## 为什么需要它
 *
 * 本次重构引入了毛玻璃、阴影、微光动画等**有真实开销**的视觉手段。
 * 一刀切的结果是：低端机上要么卡、要么为了不卡而全部砍掉（高端机也跟着朴素）。
 *
 * 分级后可以**按档位给不同强度的效果**：高端满配、低端自动简化，
 * 而不是"要么全有要么全无"。
 *
 * ## 判定依据
 *
 * | 档 | 条件 |
 * |---|---|
 * | [Low] | `isLowRamDevice` 或 CPU 核心 ≤ 4 |
 * | [Mid] | CPU 核心 ≤ 6 |
 * | [High] | 其余 |
 *
 * 只用这两个指标，因为它们**在所有 Android 版本上都能可靠读取**。
 * 不用 GPU 型号 / 帧率历史（需要额外权限或采样本机才有意义）。
 */
enum class DeviceTier {
    /** 低端：关掉模糊与微光，降级为静态底。 */
    Low,

    /** 中端：保留模糊但降低半径。 */
    Mid,

    /** 高端：完整效果。 */
    High;

    /** 是否允许毛玻璃。 */
    val canBlur: Boolean get() = this != Low

    /**
     * 模糊半径缩放系数。
     *
     * 低端 0（不模糊）、中端 0.6、高端 1.0。
     */
    val blurScale: Float
        get() = when (this) {
            Low -> 0f
            Mid -> 0.6f
            High -> 1f
        }

    /** 是否允许骨架屏微光动画（低端用静态灰块）。 */
    val canShimmer: Boolean get() = this != Low

    /** 是否允许图片淡入。 */
    val canFadeImage: Boolean get() = this != Low

    /** 竖屏预加载页数（低端不预加载，省内存与流量）。 */
    val verticalPreloadRadius: Int
        get() = when (this) {
            Low -> 0
            Mid -> 1
            High -> 1
        }
}

/**
 * 当前设备档位。
 *
 * 由 `MainActivity` 在启动时探测一次后注入，全程不变
 * （性能档位不会在运行中变化，无需观察）。
 */
val LocalDeviceTier = staticCompositionLocalOf { DeviceTier.High }

/**
 * 探测设备档位。
 *
 * ⚠️ 只在启动时调用一次 —— 它读系统服务，不适合放进 Composable 里反复执行。
 */
fun detectDeviceTier(context: Context): DeviceTier {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val lowRam = am?.isLowRamDevice == true
    val cores = Runtime.getRuntime().availableProcessors()

    return when {
        lowRam || cores <= 4 -> DeviceTier.Low
        cores <= 6 -> DeviceTier.Mid
        else -> DeviceTier.High
    }
}
