package com.example.biliv3

import com.example.biliv3.data.PlaybackProgressStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 播放进度**语义**单测（纯逻辑部分）。
 *
 * ## 为什么多 P 隔离必须单独测
 *
 * 任务书 §4.1 明确点名：「项目历史上已经出现过『切换分 P 后把进度写错』的问题，
 * 因此这一点必须单独测试」。
 *
 * 进度键是 `bvid:cid`，而 **cid 才是分P 的唯一标识** ——
 * 同一个 bvid 下 P1/P2 的 cid 不同。若键里漏掉 cid（只用 bvid），
 * 表现就是"P1 看到 10 分钟，切到 P2 也显示 10 分钟"，
 * 而且**不会报错**，属于最难自查的一类。
 *
 * 本测试不依赖 Android（DataStore 需要 Context），
 * 因此只钉死**键的构造**与**阈值语义**这两处纯逻辑。
 */
class PlaybackProgressTest {

    // ---------------- 多 P 隔离 ----------------

    @Test
    fun `同一个 bvid 的不同分P 生成不同的进度键`() {
        val p1 = PlaybackProgressStore.keyOf("BV1xx", 100L)
        val p2 = PlaybackProgressStore.keyOf("BV1xx", 200L)
        assertThat(p1).isNotEqualTo(p2)
    }

    @Test
    fun `不同 bvid 相同 cid 也生成不同键`() {
        val a = PlaybackProgressStore.keyOf("BV1", 100L)
        val b = PlaybackProgressStore.keyOf("BV2", 100L)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `进度键包含 bvid 与 cid 两部分`() {
        val k = PlaybackProgressStore.keyOf("BV1xx411c7mD", 12345L)
        assertThat(k).isEqualTo("BV1xx411c7mD:12345")
    }

    @Test
    fun `同一个分P 反复取键结果稳定`() {
        // 键不稳定会导致"写进去了但读不出来"
        val a = PlaybackProgressStore.keyOf("BV1", 100L)
        val b = PlaybackProgressStore.keyOf("BV1", 100L)
        assertThat(a).isEqualTo(b)
    }

    // ---------------- 续播提示阈值 ----------------

    @Test
    fun `低于 5% 不给续播提示`() {
        // 用户只是点了一下封面，不该提示"继续播放"
        assertThat(PlaybackProgressStore.shouldResume(1000L, 100_000L)).isFalse()
        assertThat(PlaybackProgressStore.shouldResume(4999L, 100_000L)).isFalse()
    }

    @Test
    fun `5% 到 95% 之间给续播提示`() {
        assertThat(PlaybackProgressStore.shouldResume(5000L, 100_000L)).isTrue()
        assertThat(PlaybackProgressStore.shouldResume(50_000L, 100_000L)).isTrue()
        assertThat(PlaybackProgressStore.shouldResume(94_999L, 100_000L)).isTrue()
    }

    @Test
    fun `超过 95% 不再给续播提示`() {
        // 快看完了还提示"继续播放 59:30"很怪
        assertThat(PlaybackProgressStore.shouldResume(95_001L, 100_000L)).isFalse()
        assertThat(PlaybackProgressStore.shouldResume(100_000L, 100_000L)).isFalse()
    }

    @Test
    fun `正好 95% 仍然给提示（区间是闭区间）`() {
        // ⚠️ 边界语义：`ratio in MIN_RATIO..MAX_RATIO` 是**闭区间**，
        // 所以 95% 整仍在区间内。文档写的是「**>95%** 视为看完了」——
        // 两者一致（严格大于才排除）。
        // 这个用例是写测试时被真跑出来的差异，特意保留钉死。
        assertThat(PlaybackProgressStore.shouldResume(95_000L, 100_000L)).isTrue()
    }

    @Test
    fun `时长为 0 时不给续播提示`() {
        // 详情未加载完时 duration 可能是 0 —— 不能拿它做除法
        assertThat(PlaybackProgressStore.shouldResume(1000L, 0L)).isFalse()
    }

    @Test
    fun `位置为 0 时不给续播提示`() {
        assertThat(PlaybackProgressStore.shouldResume(0L, 100_000L)).isFalse()
    }

    // ---------------- 完成阈值（v1.3.0 新令牌） ----------------

    @Test
    fun `达到 90% 视为已看完`() {
        assertThat(PlaybackProgressStore.isCompleted(90_000L, 100_000L)).isTrue()
    }

    @Test
    fun `略低于 90% 不算看完`() {
        assertThat(PlaybackProgressStore.isCompleted(89_900L, 100_000L)).isFalse()
    }

    @Test
    fun `超过 90% 当然算看完`() {
        assertThat(PlaybackProgressStore.isCompleted(95_000L, 100_000L)).isTrue()
        assertThat(PlaybackProgressStore.isCompleted(100_000L, 100_000L)).isTrue()
    }

    @Test
    fun `时长为 0 时不算看完`() {
        // 否则任何 duration=0 的异常项都会被标记成"已看完"
        assertThat(PlaybackProgressStore.isCompleted(0L, 0L)).isFalse()
        assertThat(PlaybackProgressStore.isCompleted(1000L, 0L)).isFalse()
    }

    @Test
    fun `完成阈值与续播阈值是两个独立语义`() {
        // 90%~95% 区间：已看完，但仍会给续播提示 —— 这是刻意的
        // （用户可能还想拖回去看点片尾，给提示无害）
        val pos = 92_000L
        val dur = 100_000L
        assertThat(PlaybackProgressStore.isCompleted(pos, dur)).isTrue()
        assertThat(PlaybackProgressStore.shouldResume(pos, dur)).isTrue()
    }

    @Test
    fun `完成阈值常量是 90 不是 95`() {
        // 防止有人"顺手统一"成 MAX_RATIO
        assertThat(PlaybackProgressStore.COMPLETE_RATIO).isEqualTo(0.90f)
        assertThat(PlaybackProgressStore.COMPLETE_RATIO)
            .isNotEqualTo(PlaybackProgressStore.MAX_RATIO)
    }
}
