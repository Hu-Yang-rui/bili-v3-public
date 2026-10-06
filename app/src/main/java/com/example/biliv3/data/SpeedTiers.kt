package com.example.biliv3.data

/**
 * 播放倍速档位（v1.6.5）。
 *
 * ---
 *
 * # 为什么抽成共享定义
 *
 * 倍速档位此前**写死在 `PlayerSettingsSheet` 里**
 * （`private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)`）。
 * 本轮要扩到 9 档，并且**直播也要用** —— 两处各写一份必然漂移
 * （本项目已多次发生：封面宽度六个值、令牌改名来回做）。
 *
 * 所以档位收敛到这一处，视频与直播**读同一个列表**。
 *
 * # 🔴 为什么必须区分「档位」与「实际可用」
 *
 * `ExoPlayer.setPlaybackSpeed()` 对**超出范围**的值不会抛异常 ——
 * 它**静默接受**（把音频时间拉伸到失真）或**静默回退**，
 * 取决于设备解码器与 `AudioProcessor`。
 *
 * 也就是说：**"我设了 3.0×" ≠ "它在以 3.0× 播放"**。
 *
 * 所以 [SpeedTier.verified] 区分两类档位：
 * - `true` —— 官方文档明确支持、且本项目在视频上实测过
 * - `false` —— **可能可用但不保证**（高档位 / 直播场景）
 *
 * UI 对 `verified = false` 的档位加一句说明，而不是假装它们一定行。
 * 这比"全部列出来假装都支持"诚实，也比"干脆不给"有用。
 *
 * ## 为什么上限是 3.0×
 *
 * ExoPlayer 的 `PlaybackParameters` 官方建议区间是 **0.1× ~ 8×**，
 * 但**超过 2× 之后音频会明显失真**（时间拉伸算法的极限），
 * 且高倍速下解码器丢帧会累积成音画不同步。
 * 3.0× 是本项目给出的实用上限；再高不提供。
 */
object SpeedTiers {

    /**
     * 一档倍速。
     *
     * @param value 倍速值
     * @param verified 是否**已确认**可用（见类文档）
     */
    data class SpeedTier(val value: Float, val verified: Boolean)

    /**
     * 全部档位（**顺序即 UI 顺序**，从小到大）。
     *
     * | 值 | 已确认 | 说明 |
     * |---|---|---|
     * | 0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0 | ✅ | 原有 6 档，长期使用 |
     * | 1.75 | ✅ | 常用档，ExoPlayer 常规区间 |
     * | 2.5 / 3.0 | ⚠️ | 超出常见建议，音频可能失真 —— 标注而不隐藏 |
     */
    val ALL: List<SpeedTier> = listOf(
        SpeedTier(0.5f, verified = true),
        SpeedTier(0.75f, verified = true),
        SpeedTier(1.0f, verified = true),
        SpeedTier(1.25f, verified = true),
        SpeedTier(1.5f, verified = true),
        SpeedTier(1.75f, verified = true),
        SpeedTier(2.0f, verified = true),
        SpeedTier(2.5f, verified = false),
        SpeedTier(3.0f, verified = false),
    )

    /** 仅数值列表（设置页/旧调用点用）。 */
    val VALUES: List<Float> = ALL.map { it.value }

    /** 默认倍速。 */
    const val DEFAULT = 1.0f

    /**
     * 直播可用的档位。
     *
     * ## 为什么直播**不给高倍速**
     *
     * 直播与点播有两条根本差异：
     *
     * 1. **缓冲模型不同**：直播在播"最新的分片"，加速会让播放位置
     *    持续逼近缓冲边界 → 频繁 rebuffer（表现为"加速一会儿就卡"）
     * 2. **没有"快进到底"这回事**：点播加速是为了跳过已知内容；
     *    直播加速只会让你更接近实时边缘，然后被追上并卡住
     *
     * 所以直播只给 **0.5× ~ 2.0×**，并且**不含 `verified = false` 的档位**
     * —— 直播链路本来就更脆弱，再叠加失真档位只会更容易出问题。
     *
     * ⚠️ 这不是"ExoPlayer 做不到"，是**本项目主动限制**。
     * 文案上如实说明，不假装是技术限制。
     */
    val LIVE: List<SpeedTier> = ALL.filter { it.value <= 2.0f && it.verified }

    /**
     * 把任意浮点对齐到最近的档位。
     *
     * ## 为什么需要它
     *
     * 倍速可能来自三处：设置页的默认值、播放器当前值、用户点击。
     * 它们的精度可能不同（`0.75f` vs `0.7500001f`），
     * 直接 `==` 比较会让"当前档位"匹配不上任何选项 →
     * **UI 上没有任何档位被高亮**（用户看不出当前是几倍速）。
     *
     * @return 最近的档位；`tiers` 为空时返回 [DEFAULT]
     */
    fun nearest(value: Float, tiers: List<SpeedTier> = ALL): Float {
        if (tiers.isEmpty()) return DEFAULT
        if (!value.isFinite()) return DEFAULT
        var best = tiers[0]
        var bestDiff = Float.MAX_VALUE
        for (t in tiers) {
            val d = kotlin.math.abs(t.value - value)
            if (d < bestDiff) {
                bestDiff = d
                best = t
            }
        }
        // 差异过大（例如来自旧版本的 1.1×）时不假装匹配 —— 返回默认值，
        // 让 UI 显示"1.0×"而不是错误地高亮一个差很远的档位。
        return if (bestDiff <= MATCH_TOLERANCE) best.value else DEFAULT
    }

    /** 判定容差。0.75 与 0.7 的差是 0.05 —— 取 0.01 足够严且能吸收浮点误差。 */
    private const val MATCH_TOLERANCE = 0.01f

    /** 档位的显示文案：`2.0` → `2`，`0.75` → `0.75`。 */
    fun label(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
}
