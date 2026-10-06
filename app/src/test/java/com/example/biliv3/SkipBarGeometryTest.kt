package com.example.biliv3

import com.example.biliv3.data.SkipBarGeometry
import com.example.biliv3.data.SkipSegment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 跳过区间在进度条上的几何测试。
 *
 * ## 为什么这组测试重要
 *
 * 这里的错误**全部是静默的**：不会崩、不会报错、日志里也没有痕迹，
 * 只表现为"进度条上的色块位置不对"或"某段根本没画出来"。
 * 而它恰恰是用户**唯一**能提前看见"哪一段会被跳过"的地方 ——
 * 标错了比不标更糟（用户会按错误的位置去拖）。
 *
 * 生产逻辑在 `SkipBarGeometry`（纯 Kotlin，无 Android 依赖），
 * 测试调的是**同一份实现**，不存在"测的是副本"的漂移。
 */
class SkipBarGeometryTest {

    private fun seg(start: Double, end: Double, cat: String = "sponsor") =
        SkipSegment(startSeconds = start, endSeconds = end, category = cat)

    // ---------------- 基本换算 ----------------

    /** 100 秒视频里的 20~40 秒 → 轨道 20%~40%。 */
    @Test
    fun `比例按总时长换算`() {
        val spans = SkipBarGeometry.spans(listOf(seg(20.0, 40.0)), 100.0)
        assertThat(spans).hasSize(1)
        assertThat(spans[0].startFraction).isWithin(0.001f).of(0.20f)
        assertThat(spans[0].endFraction).isWithin(0.001f).of(0.40f)
    }

    /** 多个区间各自独立换算，且保留原始下标。 */
    @Test
    fun `多个区间分别换算并带回原始下标`() {
        val segs = listOf(seg(0.0, 10.0), seg(50.0, 60.0), seg(90.0, 100.0))
        val spans = SkipBarGeometry.spans(segs, 100.0)

        assertThat(spans).hasSize(3)
        assertThat(spans.map { it.index }).containsExactly(0, 1, 2).inOrder()
        assertThat(spans[1].startFraction).isWithin(0.001f).of(0.50f)
    }

    // ---------------- 边界：时长未知 ----------------

    /**
     * ⚠️ 时长未知（还没取到流 / 直播）时必须返回空。
     *
     * 否则 `t / 0` 会得到 `Infinity`，`toFloat()` 后变成 `Inf`，
     * 画出来是一整条铺满的色带 —— 看起来像"整个视频都会被跳过"。
     */
    @Test
    fun `总时长为零时不画任何区间`() {
        assertThat(SkipBarGeometry.spans(listOf(seg(0.0, 10.0)), 0.0)).isEmpty()
        assertThat(SkipBarGeometry.spans(listOf(seg(0.0, 10.0)), -1.0)).isEmpty()
        assertThat(SkipBarGeometry.spans(listOf(seg(0.0, 10.0)), Double.NaN)).isEmpty()
    }

    /** 没有片段时返回空（常态，不是错误）。 */
    @Test
    fun `没有片段时返回空`() {
        assertThat(SkipBarGeometry.spans(emptyList(), 100.0)).isEmpty()
    }

    // ---------------- 边界：越界处理 ----------------

    /**
     * 完全落在视频范围之外的区间必须**丢弃**。
     *
     * 社区标注可能来自重新转码前的旧版本，时间点与新版本对不上。
     * 硬画会把标记放到完全无关的位置。
     */
    @Test
    fun `完全越界的区间被丢弃`() {
        val segs = listOf(seg(200.0, 300.0)) // 视频只有 100 秒
        assertThat(SkipBarGeometry.spans(segs, 100.0)).isEmpty()
    }

    /**
     * 部分越界 → **裁剪**，不是丢弃。
     *
     * 越界一点点是常态（浮点误差、片尾多标 0.5 秒）。
     * 整段丢弃会让"明明有标注却看不见"。
     */
    @Test
    fun `部分越界的区间被裁剪到边界`() {
        val spans = SkipBarGeometry.spans(listOf(seg(90.0, 120.0)), 100.0)
        assertThat(spans).hasSize(1)
        assertThat(spans[0].endFraction).isEqualTo(1f)
        assertThat(spans[0].startFraction).isWithin(0.001f).of(0.90f)
    }

    /** 起点为负（脏数据）时裁剪到 0，而不是产生负坐标。 */
    @Test
    fun `负起点被裁剪到零`() {
        val spans = SkipBarGeometry.spans(listOf(seg(-5.0, 10.0)), 100.0)
        assertThat(spans).hasSize(1)
        assertThat(spans[0].startFraction).isEqualTo(0f)
    }

    /** `end <= start` 的无效区间不产生标记。 */
    @Test
    fun `无效区间不产生标记`() {
        assertThat(SkipBarGeometry.spans(listOf(seg(50.0, 50.0)), 100.0)).isEmpty()
        assertThat(SkipBarGeometry.spans(listOf(seg(60.0, 50.0)), 100.0)).isEmpty()
    }

    // ---------------- 边界：最小可见宽度 ----------------

    /**
     * ⚠️ 极短区间必须仍然可见。
     *
     * 3 秒片段在 1 小时视频里占 1/1200 宽 —— 在 400px 轨道上约 0.3px，
     * 画出来等于没画（会被圆头笔帽吃掉）。需求要求"标记必须准确对应
     * 实际时间范围"，而"存在但看不见"在体验上等于"不存在"。
     */
    @Test
    fun `极短区间被扩到最小可见宽度`() {
        val spans = SkipBarGeometry.spans(listOf(seg(0.0, 3.0)), 3600.0)
        assertThat(spans).hasSize(1)
        assertThat(spans[0].widthFraction)
            .isAtLeast(SkipBarGeometry.MIN_WIDTH_FRACTION)
    }

    /**
     * 扩宽只**向右**，起点必须保持原位。
     *
     * 向左扩会让标记出现在区间**开始之前**，那是不实的位置：
     * 用户按标记拖动，会落到一个其实不会被跳过的点。
     */
    @Test
    fun `扩宽不移动起点`() {
        val spans = SkipBarGeometry.spans(listOf(seg(0.0, 3.0)), 3600.0)
        assertThat(spans[0].startFraction).isEqualTo(0f)
    }

    /**
     * 区间紧贴轨道末端且极短时，标记**不得越出右边界**。
     *
     * 越界在 Canvas 上会画到轨道外面（盖住右侧的总时长读数），
     * 而且比例 > 1 在数学上也是错的。
     */
    @Test
    fun `末端极短区间不越过右边界`() {
        val spans = SkipBarGeometry.spans(listOf(seg(3599.0, 3600.0)), 3600.0)
        assertThat(spans).hasSize(1)
        assertThat(spans[0].endFraction).isAtMost(1f)
        assertThat(spans[0].startFraction).isAtLeast(0f)
        // 起点可以左移（为保证宽度），但整段必须仍在轨道内
        assertThat(spans[0].widthFraction).isAtLeast(SkipBarGeometry.MIN_WIDTH_FRACTION)
    }

    /** 足够长的区间**不被**额外加宽（宽度必须反映真实时长）。 */
    @Test
    fun `足够长的区间保持真实宽度`() {
        val spans = SkipBarGeometry.spans(listOf(seg(10.0, 30.0)), 100.0)
        assertThat(spans[0].widthFraction).isWithin(0.001f).of(0.20f)
    }

    /** 所有输出必须落在 0..1 内（否则会画到轨道之外）。 */
    @Test
    fun `所有比例都在零到一之间`() {
        val segs = listOf(
            seg(-10.0, 5.0),
            seg(10.0, 30.0),
            seg(99.0, 200.0),
            seg(3599.0, 3600.0),
        )
        val spans = SkipBarGeometry.spans(segs, 3600.0)
        spans.forEach { s ->
            assertThat(s.startFraction).isAtLeast(0f)
            assertThat(s.endFraction).isAtMost(1f)
            assertThat(s.endFraction).isAtLeast(s.startFraction)
        }
    }

    // ---------------- 当前位置判定 ----------------

    /**
     * `contains` 是**事实**判定（位置属于跳过内容吗），
     * 与 `SponsorBlockLogic.findSegmentAt` 的**行为**判定不同 ——
     * 后者带"已跳过就不再跳"的去重，前者不带。
     *
     * 两者不能合并：合并后"已跳过的区间"会停止高亮，
     * 而那恰恰是最该高亮的时刻（用户正在被跳过的那段里）。
     */
    @Test
    fun `contains 判定位置是否落在跳过区间内`() {
        val segs = listOf(seg(10.0, 20.0))
        assertThat(SkipBarGeometry.contains(segs, 9.9)).isFalse()
        assertThat(SkipBarGeometry.contains(segs, 10.0)).isTrue()
        assertThat(SkipBarGeometry.contains(segs, 19.9)).isTrue()
        // 右端开区间：19.999 在内，20.0 已出
        assertThat(SkipBarGeometry.contains(segs, 20.0)).isFalse()
    }

    @Test
    fun `contains 对空列表恒为 false`() {
        assertThat(SkipBarGeometry.contains(emptyList(), 5.0)).isFalse()
    }
}
