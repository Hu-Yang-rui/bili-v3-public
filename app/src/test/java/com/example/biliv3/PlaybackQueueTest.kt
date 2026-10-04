package com.example.biliv3

import com.example.biliv3.player.PlaybackQueue
import com.example.biliv3.player.QueueItem
import com.example.biliv3.player.RepeatMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 播放队列单测。
 *
 * ## 为什么队列必须有单测
 *
 * 队列是**纯数据逻辑**，但最容易写错的恰恰是它的下标运算：
 * - 拖动排序后 `currentIndex` 不修正 → "切歌后标题与实际内容不符"
 * - 移除当前项之前/之后的项，下标该不该前移是两回事
 * - 单曲循环下"自然播完"与"用户点下一首"行为**不同**
 *
 * 这些都不会崩溃，只是"看起来不对" —— 属于项目记录过的最难自查的一类。
 * 而且队列不需要 ExoPlayer，可以直接钉死。
 */
class PlaybackQueueTest {

    private fun item(n: Int, cid: Long = 0L) = QueueItem(
        bvid = "BV$n",
        cid = cid,
        title = "视频 $n",
    )

    private fun PlaybackQueue.fill(vararg n: Int): PlaybackQueue {
        addAll(n.map { item(it) })
        return this
    }

    // ---------------- 增 ----------------

    @Test
    fun `加入队列后当前项自动落在第一项`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        assertThat(q.size).isEqualTo(3)
        assertThat(q.current?.bvid).isEqualTo("BV1")
        assertThat(q.currentIndex.value).isEqualTo(0)
    }

    @Test
    fun `重复加入同一视频会被去重`() {
        val q = PlaybackQueue()
        assertThat(q.addAll(listOf(item(1), item(2)))).isEqualTo(2)
        // 再插一次 1、2 以及新的 3
        assertThat(q.addAll(listOf(item(1), item(2), item(3)))).isEqualTo(1)
        assertThat(q.size).isEqualTo(3)
    }

    @Test
    fun `关掉去重时可以加入重复项`() {
        val q = PlaybackQueue()
        q.addAll(listOf(item(1)), dedupe = false)
        q.addAll(listOf(item(1)), dedupe = false)
        assertThat(q.size).isEqualTo(2)
    }

    @Test
    fun `下一首播放是插到当前项之后而不是末尾`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        q.playNext(item(9))
        assertThat(q.items.value.map { it.bvid })
            .containsExactly("BV1", "BV9", "BV2", "BV3").inOrder()
    }

    @Test
    fun `下一首播放已存在的项会先移走再插入`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        // 把末尾的 3 提到当前(1)之后
        q.playNext(item(3))
        assertThat(q.items.value.map { it.bvid })
            .containsExactly("BV1", "BV3", "BV2").inOrder()
        assertThat(q.size).isEqualTo(3)
    }

    @Test
    fun `空队列下一首播放会成为第一项`() {
        val q = PlaybackQueue()
        q.playNext(item(5))
        assertThat(q.current?.bvid).isEqualTo("BV5")
        assertThat(q.currentIndex.value).isEqualTo(0)
    }

    // ---------------- 删 ----------------

    @Test
    fun `移除当前项之前的项当前下标要前移`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        q.select(2)                      // 播 BV3
        q.remove("BV1")                  // 移除前面的
        assertThat(q.current?.bvid).isEqualTo("BV3")
        assertThat(q.currentIndex.value).isEqualTo(1)
    }

    @Test
    fun `移除当前项之后的项当前下标不变`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        q.select(0)
        q.remove("BV3")
        assertThat(q.current?.bvid).isEqualTo("BV1")
        assertThat(q.currentIndex.value).isEqualTo(0)
    }

    @Test
    fun `移除当前项本身时下标收敛到合法范围`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        q.select(2)                      // 最后一项
        q.remove("BV3")
        assertThat(q.currentIndex.value).isEqualTo(1)
        assertThat(q.current?.bvid).isEqualTo("BV2")
    }

    @Test
    fun `清空后当前项为 null 且下标为 -1`() {
        val q = PlaybackQueue().fill(1, 2)
        q.clear()
        assertThat(q.isEmpty).isTrue()
        assertThat(q.current).isNull()
        assertThat(q.currentIndex.value).isEqualTo(-1)
    }

    // ---------------- 排序 ----------------

    @Test
    fun `拖动排序后当前项跟随被移动的项`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        q.select(0)                      // 当前是 BV1
        q.move(from = 0, to = 2)         // 把 BV1 拖到最后
        assertThat(q.items.value.map { it.bvid })
            .containsExactly("BV2", "BV3", "BV1").inOrder()
        // 关键：当前播放的仍然是 BV1
        assertThat(q.current?.bvid).isEqualTo("BV1")
        assertThat(q.currentIndex.value).isEqualTo(2)
    }

    @Test
    fun `拖动排序时当前项下标按位移修正`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        q.select(2)                      // 当前 BV3
        q.move(from = 0, to = 1)         // 把前面的项往后挪
        assertThat(q.current?.bvid).isEqualTo("BV3")
        assertThat(q.currentIndex.value).isEqualTo(2)
    }

    @Test
    fun `非法拖动参数直接返回 false`() {
        val q = PlaybackQueue().fill(1, 2)
        assertThat(q.move(0, 5)).isFalse()
        assertThat(q.move(-1, 0)).isFalse()
        assertThat(q.move(0, 0)).isFalse()
    }

    // ---------------- 推进 ----------------

    @Test
    fun `顺序播放在末尾停止`() {
        val q = PlaybackQueue().fill(1, 2)
        q.select(0)
        assertThat(q.next()?.bvid).isEqualTo("BV2")
        // 到底且非循环 → null（调用方应停止播放）
        assertThat(q.next()).isNull()
    }

    @Test
    fun `列表循环在末尾回到第一项`() {
        val q = PlaybackQueue().fill(1, 2)
        q.setRepeatMode(RepeatMode.ALL)
        q.select(1)
        assertThat(q.next()?.bvid).isEqualTo("BV1")
    }

    @Test
    fun `单曲循环下自然播完重播本首`() {
        val q = PlaybackQueue().fill(1, 2)
        q.setRepeatMode(RepeatMode.ONE)
        q.select(0)
        assertThat(q.next(userInitiated = false)?.bvid).isEqualTo("BV1")
    }

    @Test
    fun `单曲循环下用户主动点下一首仍然换歌`() {
        // 这是两者行为的分界点：自然播完 vs 主动切歌
        val q = PlaybackQueue().fill(1, 2)
        q.setRepeatMode(RepeatMode.ONE)
        q.select(0)
        assertThat(q.next(userInitiated = true)?.bvid).isEqualTo("BV2")
    }

    @Test
    fun `上一首在开头返回 null`() {
        val q = PlaybackQueue().fill(1, 2)
        q.select(0)
        assertThat(q.previous()).isNull()
    }

    @Test
    fun `上一首可以回退`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        q.select(2)
        assertThat(q.previous()?.bvid).isEqualTo("BV2")
        assertThat(q.previous()?.bvid).isEqualTo("BV1")
    }

    // ---------------- 模式 ----------------

    @Test
    fun `循环模式按 关闭-列表-单曲 轮转`() {
        val q = PlaybackQueue()
        assertThat(q.repeatMode.value).isEqualTo(RepeatMode.OFF)
        assertThat(q.cycleRepeatMode()).isEqualTo(RepeatMode.ALL)
        assertThat(q.cycleRepeatMode()).isEqualTo(RepeatMode.ONE)
        assertThat(q.cycleRepeatMode()).isEqualTo(RepeatMode.OFF)
    }

    @Test
    fun `开启随机播放不会跳走当前项`() {
        // 回归：早期实现开启随机后当前歌立刻变了，看起来像 bug
        val q = PlaybackQueue().fill(1, 2, 3, 4, 5)
        q.select(2)
        q.setShuffled(true)
        assertThat(q.current?.bvid).isEqualTo("BV3")
        assertThat(q.currentIndex.value).isEqualTo(2)
    }

    @Test
    fun `随机播放一轮之内每首都播到且不重复`() {
        val q = PlaybackQueue().fill(1, 2, 3, 4, 5)
        q.select(0)
        q.setShuffled(true)

        val seen = mutableListOf(q.current!!.bvid)
        while (true) {
            val n = q.next() ?: break
            seen.add(n.bvid)
        }
        // 一轮之内应恰好覆盖全部 5 首
        assertThat(seen).hasSize(5)
        assertThat(seen.toSet()).hasSize(5)
    }

    @Test
    fun `随机播放配合列表循环不会在末尾停住`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        q.select(0)
        q.setShuffled(true)
        q.setRepeatMode(RepeatMode.ALL)
        // 连推 10 次都不该返回 null
        repeat(10) {
            assertThat(q.next()).isNotNull()
        }
    }

    @Test
    fun `关掉随机后回到顺序推进`() {
        val q = PlaybackQueue().fill(1, 2, 3)
        q.select(0)
        q.setShuffled(true)
        q.setShuffled(false)
        assertThat(q.next()?.bvid).isEqualTo("BV2")
    }

    // ---------------- 队列项 ----------------

    @Test
    fun `番剧条目用 ep 作为唯一键`() {
        val ep = QueueItem(bvid = "", epId = 12345L, title = "第一话")
        assertThat(ep.key).isEqualTo("ep12345")
        assertThat(ep.isBangumi).isTrue()
    }

    @Test
    fun `同一个 bvid 的不同分P 是两个不同的播放标识`() {
        // 队列以 bvid 去重，但进度以 bvid:cid 记录 —— 两者粒度不同
        val p1 = item(1, cid = 100L)
        val p2 = item(1, cid = 200L)
        assertThat(p1.key).isEqualTo(p2.key)       // 队列层面视为同一个视频
        assertThat(p1.cid).isNotEqualTo(p2.cid)    // 但分P 不同
    }
}
