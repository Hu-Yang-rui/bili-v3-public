package com.example.biliv3.data


/**
 * 跳过区间在**进度条上的几何**（纯 Kotlin，可单测）。
 *
 * ## 为什么抽成纯函数
 *
 * 把「秒」映射成「轨道上的像素区间」看起来是两行乘法，但真正会错的地方是
 * **边界**：区间起点早于 0、终点晚于总时长、总时长未知（还没取到流）、
 * 区间重叠、区间短到不足一个像素。这些错了都不会崩，只会让标记
 * **画错位置或干脆看不见** —— 属于必须靠单测钉死的类型
 * （与 `SponsorBlockLogic` 同一理由，见 `AGENTS.md` §7.20 的判据）。
 *
 * ## 与进度条的关系
 *
 * [com.example.biliv3.ui.video.ProgressBar] 的自绘轨道宽 `W`、时长 `D`：
 * ```
 * 像素 x = (秒 t / D) * W
 * ```
 * 这里返回的是**归一化到 0..1** 的比例（不是像素），因为轨道宽度只有
 * 绘制期才知道；调用方拿比例乘宽度即可。
 *
 * ## 视觉约束
 *
 * 轨道只有 [V3Space.trackHeight]（2dp）高。
 * 一个 3 秒的片段在 30 分钟视频里只占 1/600 宽 —— 大约 0.6px，
 * 画出来等于没有。所以：
 * - 区间**不裁剪**到不足一像素（那样等于隐藏），而是给一个最小宽度
 * - 但最小宽度只用在"本来就有这一段"的情况，**不凭空造区间**
 */
object SkipBarGeometry {

    /**
     * 一个跳过区间在轨道上的位置（归一化比例）。
     *
     * @param index 该区间在**入参列表**里的下标。
     *   ⚠️ 必须带上它 —— [spans] 会丢弃越界区间，于是"输出下标"与
     *   "输入下标"不再一一对应。调用方要用它回查原始 [SkipSegment]
     *   （取类别、判断当前位置是否落在里面），对不上就会标错颜色/高亮错段。
     * @param startFraction 起点比例 0..1
     * @param endFraction 终点比例 0..1（**含**，即已向右扩展了最小宽度）
     */
    data class Span(
        val index: Int,
        val startFraction: Float,
        val endFraction: Float,
    ) {
        val widthFraction: Float get() = (endFraction - startFraction).coerceAtLeast(0f)
    }

    /**
     * 计算所有跳过区间在轨道上的位置。
     *
     * ## 规则（每条都有理由）
     *
     * 1. **总时长未知 → 返回空列表**。此时进度条本身也没法画进度，
     *    标一堆区间只会是错的（`duration = 0` 时 `t / D` 是 `Infinity`）。
     * 2. **完全落在 [0, duration] 之外 → 丢弃**。社区标注可能与实际视频
     *    版本不一致（转码前的旧时间点），硬画会标在无关位置。
     * 3. **部分越界 → 裁剪到边界**，而不是整段丢弃 ——
     *    越界一点点（浮点误差、片尾多标 0.5 秒）是常态。
     * 4. **不足 [minWidthFraction] → 向右补足**（不改变起点）。
     *    向左补会让标记跑到区间**之前**，那是不实的位置。
     * 5. **超出右边界时**向左收缩（保证不越过轨道末端）。
     *
     * @param segments 已合并、升序的片段（`SponsorBlockLogic.merge` 的输出）
     * @param durationSeconds 视频总时长（秒）；`<= 0` 时返回空
     * @param minWidthFraction 最小可见宽度（占轨道比例）
     */
    fun spans(
        segments: List<SkipSegment>,
        durationSeconds: Double,
        minWidthFraction: Float = MIN_WIDTH_FRACTION,
    ): List<Span> {
        if (segments.isEmpty()) return emptyList()
        if (durationSeconds <= 0.0 || !durationSeconds.isFinite()) return emptyList()

        val out = ArrayList<Span>(segments.size)
        segments.forEachIndexed { index, s ->
            // 2. 完全越界
            if (s.endSeconds <= 0.0 || s.startSeconds >= durationSeconds) return@forEachIndexed

            // 3. 裁剪到 [0, duration]
            val start = s.startSeconds.coerceIn(0.0, durationSeconds)
            val end = s.endSeconds.coerceIn(0.0, durationSeconds)
            if (end <= start) return@forEachIndexed

            var a = (start / durationSeconds).toFloat().coerceIn(0f, 1f)
            var b = (end / durationSeconds).toFloat().coerceIn(0f, 1f)

            // 4. 补足最小宽度（向右）
            val minW = minWidthFraction.coerceIn(0f, 1f)
            if (b - a < minW) {
                b = (a + minW).coerceAtMost(1f)
                // 5. 右端顶到头时向左收缩，保证不越过轨道末端
                if (b - a < minW) a = (b - minW).coerceAtLeast(0f)
            }

            out.add(Span(index, a, b))
        }
        return out
    }

    /**
     * 某个播放位置是否落在任意跳过区间内。
     *
     * 与 [SponsorBlockLogic.findSegmentAt] 的区别：那个带"是否已跳过 / 尾部余量"
     * 的**行为**判定；这个只是"位置属于跳过内容吗"的**事实**判定，
     * 用于给进度条上的标记加高亮（`当前就在被跳过的内容里`）。
     *
     * ⚠️ 语义不同，不要合并 —— 合并后"已跳过的区间"会在进度条上停止高亮，
     * 而那恰恰是最该被高亮的时刻。
     */
    fun contains(
        segments: List<SkipSegment>,
        positionSeconds: Double,
    ): Boolean = segments.any {
        positionSeconds >= it.startSeconds && positionSeconds < it.endSeconds
    }

    /**
     * 最小可见宽度（占轨道比例）。
     *
     * 0.004 ≈ 一条 400px 轨道上的 1.6px。低于这个值标记会与轨道混在一起
     * （轨道本身 2dp 高、圆头，一个像素的色块会被圆角吃掉）。
     * 取"能被看见"的下限，而不是"数学上准确"的宽度 ——
     * 一个标错位置的标记比一个略宽的标记更糟。
     */
    const val MIN_WIDTH_FRACTION = 0.004f
}
