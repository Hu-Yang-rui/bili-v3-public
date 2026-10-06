package com.example.biliv3.ui.video

/**
 * 投币动画的**相位机**（纯 Kotlin，可单测）。
 *
 * ## 为什么抽成纯函数
 *
 * 这段动画有一条硬约束：**不允许瞬移**（需求原文）。
 * 而"瞬移"正是这类代码最容易出的错 —— 相位切换时位置算错一帧，
 * 硬币就会从手上"跳"到半空中。它不报错、不崩，只是看起来廉价，
 * 而且很难在人工点测里稳定复现（取决于帧率与调度）。
 *
 * 抽出来之后 `CoinThrowTest` 可以直接验证：
 * - 相位边界正确（不多一帧、不少一帧）
 * - 每个相位内的进度都从 0 走到 1
 * - **位置在整个时间轴上是连续的**（相邻采样点的位移不超过阈值）
 *
 * ## 完整流程（对应需求）
 *
 * ```
 * Hovering  小人抬手，硬币悬浮在手的上方（静止态，等用户选择）
 *    ↓ 用户点「确认投币」
 * Reaching  小人抬手去取浮空的硬币
 * Holding   小人拿住硬币
 * Throwing  小人把硬币向视频方向投出（硬币产生运动轨迹）
 * Done      投币完成 → 触发 onConfirm
 * ```
 *
 * ## 时间长度为什么这么定
 *
 * 全部落在 [com.example.biliv3.design.tokens.Motion] 的时长阶梯内
 * （PRESS 100 / FADE 160 / PAGE 220 / LONG 320），不引入第五档：
 * - [REACH_MS] 260 ≈ PAGE 档 —— "抬手取币"是一次中等幅度的位移
 * - [HOLD_MS] 180 ≈ IMAGE_FADE 档 —— 停顿要**能被感知**但不能拖
 * - [THROW_MS] 420 = LONG 档再放长 —— 抛物线的飞行需要足够时间才读得出轨迹，
 *   太快就变成"闪一下不见了"（那正是要避免的廉价感）
 */
object CoinThrow {

    /** 动画相位。 */
    enum class Phase {
        /** 静止态：手抬起、硬币悬在手上方。 */
        Hovering,

        /** 抬手去取浮空的硬币。 */
        Reaching,

        /** 拿住硬币（短暂停顿，让"拿到了"这件事被看见）。 */
        Holding,

        /** 向视频方向投出。 */
        Throwing,

        /** 完成（调用方据此触发 onConfirm）。 */
        Done,
    }

    /** 抬手取币时长。 */
    const val REACH_MS = 260L

    /** 拿住硬币的停顿。 */
    const val HOLD_MS = 180L

    /** 投掷飞行时长。 */
    const val THROW_MS = 420L

    /** 整个动画的总时长（Hovering 不计入 —— 它是等待用户的静止态）。 */
    const val TOTAL_MS = REACH_MS + HOLD_MS + THROW_MS

    /**
     * 按已流逝时间判定当前相位。
     *
     * ⚠️ 边界语义是**左闭右开**：`elapsed == REACH_MS` 时已经进入 [Phase.Holding]。
     * 用闭区间会让两个相位在同一毫秒同时成立，调用方取哪个都可能，
     * 表现就是"偶尔抖一下"。
     */
    fun phaseAt(elapsedMs: Long): Phase = when {
        elapsedMs < 0L -> Phase.Hovering
        elapsedMs < REACH_MS -> Phase.Reaching
        elapsedMs < REACH_MS + HOLD_MS -> Phase.Holding
        elapsedMs < TOTAL_MS -> Phase.Throwing
        else -> Phase.Done
    }

    /** 相位内进度 0..1（[Phase.Hovering] / [Phase.Done] 恒为 1）。 */
    fun progressAt(elapsedMs: Long): Float = when (phaseAt(elapsedMs)) {
        Phase.Hovering -> 1f
        Phase.Done -> 1f
        Phase.Reaching -> (elapsedMs.toFloat() / REACH_MS).coerceIn(0f, 1f)
        Phase.Holding -> ((elapsedMs - REACH_MS).toFloat() / HOLD_MS).coerceIn(0f, 1f)
        Phase.Throwing ->
            ((elapsedMs - REACH_MS - HOLD_MS).toFloat() / THROW_MS).coerceIn(0f, 1f)
    }

    /**
     * 手的高度系数：`0` = 垂下，`1` = 抬到最高（硬币悬浮处）。
     *
     * ## 为什么静止态就是 1（抬起）
     *
     * 需求要求"小人保持抬手动作，手的上方悬浮一枚硬币" ——
     * 抬手是**常态**，不是动画的一帧。所以 Hovering 直接取 1。
     *
     * ## 为什么 Reaching 从 1 开始而不是 0
     *
     * 手已经在最高处（硬币就在它上方），"取币"是**手向硬币再伸一点**
     * 再收回来的小动作，不是"从垂手抬起来"。
     * 从 0 开始会让手先掉下去再抬起来 —— 那是一次无意义的、突兀的大位移。
     */
    fun handLift(elapsedMs: Long): Float = when (phaseAt(elapsedMs)) {
        Phase.Hovering -> 1f
        // 先向上再回到 1：多伸出去 18% 的幅度，够读出"够到了"
        Phase.Reaching -> 1f + 0.18f * kotlin.math.sin(progressAt(elapsedMs) * Math.PI).toFloat()
        Phase.Holding -> 1f
        // 投出时手随之后摆再回落（投掷的自然跟随动作）
        Phase.Throwing -> 1f + 0.10f * kotlin.math.sin(progressAt(elapsedMs) * Math.PI).toFloat()
        Phase.Done -> 1f
    }

    /**
     * 硬币相对"悬浮位"的纵向偏移（单位：悬浮高度的倍数）。
     *
     * `0` = 停在悬浮位；`> 0` = 向上；`< 0` = 向下（被拿下来）。
     *
     * ## 这是"取币"动作的核心
     *
     * 需求写的是"小人**从空中取下**浮空的硬币" —— 硬币必须真的
     * 从空中落到手里，而不是原地消失、再在手里出现（那就是瞬移）。
     */
    fun coinLift(elapsedMs: Long): Float = when (phaseAt(elapsedMs)) {
        Phase.Hovering -> 0f
        // 取币：硬币从悬浮位**平滑落到手心**（-1 表示落到手的位置）
        Phase.Reaching -> -ease(progressAt(elapsedMs))
        // 拿住：停在手里
        Phase.Holding -> -1f
        // 投出：硬币离开手，飞向视频（向上并加速）
        Phase.Throwing -> -1f + 2.2f * ease(progressAt(elapsedMs))
        Phase.Done -> 1.2f
    }

    /**
     * 硬币相对中心点的横向偏移（单位：画面宽度的倍数）。
     *
     * 静止与取币阶段恒为 0（硬币就在正中）；投出后向右上方飞走 ——
     * "向视频方向"在竖屏详情页里就是**向播放器画面**，
     * 而播放器在正上方，所以这里主要靠 [coinLift] 表达，
     * 横向只给一点偏移让它看起来不是直上直下。
     */
    fun coinDrift(elapsedMs: Long): Float = when (phaseAt(elapsedMs)) {
        Phase.Throwing -> 0.55f * ease(progressAt(elapsedMs))
        Phase.Done -> 0.55f
        else -> 0f
    }

    /** 硬币的不透明度：投出后段淡出（"飞远了"），其余恒为 1。 */
    fun coinAlpha(elapsedMs: Long): Float = when (phaseAt(elapsedMs)) {
        Phase.Throwing -> {
            val p = progressAt(elapsedMs)
            // 前 60% 保持不透明（看得清轨迹），之后淡出
            if (p <= 0.6f) 1f else (1f - (p - 0.6f) / 0.4f).coerceIn(0f, 1f)
        }
        Phase.Done -> 0f
        else -> 1f
    }

    /**
     * 悬浮特效的强度（0..1）—— 光晕 / 波纹 / 粒子的总强度。
     *
     * 取币开始后**逐渐减弱**：硬币都被拿走了，还留着悬浮光晕是错的。
     * 这与"特效必须跟着状态变"是同一条要求（不能是纯装饰）。
     */
    fun hoverFxIntensity(elapsedMs: Long): Float = when (phaseAt(elapsedMs)) {
        Phase.Hovering -> 1f
        Phase.Reaching -> 1f - ease(progressAt(elapsedMs))
        else -> 0f
    }

    /** 缓动：前段快、后段缓，与 `Motion.standard` 同族的手感。 */
    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x)
    }
}
