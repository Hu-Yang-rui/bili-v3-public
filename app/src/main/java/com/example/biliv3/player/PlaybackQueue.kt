package com.example.biliv3.player

import com.example.biliv3.data.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放队列中的一项。
 *
 * ## 为什么不用 `VideoItem` 直接当队列项
 *
 * `VideoItem` 是**接口返回的展示模型**，字段随接口变化；
 * 队列项需要的是**稳定标识 + 可离线展示的最小集**：
 *
 * - `bvid` + `cid` 才是**唯一播放标识**（多 P 视频同一个 bvid 有多个 cid）
 * - `title` / `author` / `cover` 是给队列 UI 与媒体通知用的**冗余快照** ——
 *   队列可能在断网时打开，不能依赖再拉一次详情
 *
 * 所以队列项是**独立模型**，从 `VideoItem` 转换而来，不是它的别名。
 */
data class QueueItem(
    /** 视频标识（BV 号）。 */
    val bvid: String,
    /**
     * 分P 的 cid。0 = 尚未确定（加入队列时可能还没拉详情）。
     *
     * ⚠️ 队列项以 `bvid` 去重，但**播放进度以 `bvid:cid` 记录** ——
     * 两者粒度不同，不要混用。
     */
    val cid: Long = 0L,
    val title: String,
    val author: String = "",
    val cover: String = "",
    val durationSeconds: Int = 0,
    /** 番剧用 `ep_id`，与 UGC 的 bvid 是两套体系（见 AGENTS.md §3.2）。 */
    val epId: Long = 0L,
) {
    /** 稳定键：番剧用 ep，UGC 用 bvid。 */
    val key: String get() = if (epId > 0L) "ep$epId" else bvid

    /** 是否为番剧条目。 */
    val isBangumi: Boolean get() = epId > 0L

    companion object {
        /** 从列表项转换（收藏 / 历史 / 搜索 / 推荐等来源共用）。 */
        fun from(item: VideoItem): QueueItem = QueueItem(
            bvid = item.bvid,
            title = item.title,
            author = item.authorName,
            cover = item.cover,
            durationSeconds = item.durationSeconds,
        )
    }
}

/** 循环模式。 */
enum class RepeatMode {
    /** 列表播完即停。 */
    OFF,

    /** 列表循环。 */
    ALL,

    /** 单曲循环。 */
    ONE,
}

/**
 * 播放队列（**独立于 Player 与 UI**）。
 *
 * ## 为什么必须独立
 *
 * 任务书要求「队列必须与播放器解耦，播放器只消费 Queue」。
 * 具体收益：
 *
 * 1. **UI 不持有队列** —— 队列页、播放器、媒体通知读的是**同一个** StateFlow，
 *    不会出现"播放器切了歌但队列页还显示旧的"
 * 2. **可测试** —— 纯 Kotlin 数据操作，不需要 ExoPlayer 实例。
 *    排序 / 去重 / 随机 / 循环推进这些**最容易写错**的逻辑可以单测覆盖
 * 3. **PlayerHolder 只做"装配流"** —— 它不需要知道队列里有什么，
 *    只负责把当前项变成 MediaSource
 *
 * ## 随机播放为什么用"打乱的顺序表"而不是"每次随机取"
 *
 * 每次随机取会**重复播同一首**、也可能永远播不到某些项。
 * 这里维护一个 `shuffleOrder`（当前列表索引的乱序排列），
 * 随机播放时沿它前进 —— 保证**一轮之内每首都播到**。
 * 用户切换列表内容时重新洗牌。
 */
class PlaybackQueue {

    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffled = MutableStateFlow(false)
    val shuffled: StateFlow<Boolean> = _shuffled.asStateFlow()

    /** 洗牌顺序：`items` 的下标排列。`shuffled == false` 时无意义。 */
    private var shuffleOrder: List<Int> = emptyList()

    /** 当前播放项。队列空返回 null。 */
    val current: QueueItem?
        get() = _items.value.getOrNull(_currentIndex.value)

    /** 队列长度。 */
    val size: Int get() = _items.value.size

    val isEmpty: Boolean get() = _items.value.isEmpty()

    // ---------------- 增 ----------------

    /**
     * 追加到末尾。
     *
     * @return 实际加入的数量（已存在的会被跳过）
     */
    fun addAll(newItems: List<QueueItem>, dedupe: Boolean = true): Int {
        if (newItems.isEmpty()) return 0
        val existing = _items.value
        val keys = if (dedupe) existing.mapTo(HashSet()) { it.key } else emptySet()
        val toAdd = if (dedupe) newItems.filter { it.key !in keys } else newItems
        if (toAdd.isEmpty()) return 0

        _items.value = existing + toAdd
        // 首次加入时自动定位到第一项（否则队列有内容但"当前"为空）
        if (_currentIndex.value < 0) _currentIndex.value = 0
        if (_shuffled.value) reshuffle()
        return toAdd.size
    }

    /** 追加单个。 */
    fun add(item: QueueItem): Int = addAll(listOf(item))

    /**
     * **替换**为单曲队列（详情页登记当前视频用）。
     *
     * ## 与 [add] 的区别（很重要）
     *
     * 详情页是"从某处点进来的**一个**视频"，不是"往队列里再加一个"。
     * 用 `add` 的话，用户浏览 10 个视频后队列会堆 10 条无关记录，
     * 黑胶页的"下一首"就会播到很早以前点开过的视频 —— 那不是用户预期。
     *
     * 所以详情页用**覆盖式**登记：队列 = 当前这一个视频。
     * 用户显式点「添加到队列」时才走 [add] / [playNext]。
     *
     * @return true = 队列内容确实变了
     */
    fun setSingle(item: QueueItem): Boolean {
        val same = _items.value.size == 1 && _items.value[0].key == item.key
        if (same) return false
        _items.value = listOf(item)
        _currentIndex.value = 0
        if (_shuffled.value) shuffleOrder = listOf(0)
        return true
    }

    /**
     * 若队列里没有这一项则追加（**不改变**当前播放项）。
     *
     * 用于「添加到队列」这类**非覆盖**语义。
     */
    fun ensurePresent(item: QueueItem): Boolean {
        if (_items.value.any { it.key == item.key }) return false
        return addAll(listOf(item)) > 0
    }

    /**
     * 插入到**当前项之后**（"下一首播放"）。
     *
     * 注意：插入位置是 `currentIndex + 1`，**不是**末尾 ——
     * 这正是"下一首播放"与"添加到队列"的区别。
     */
    fun playNext(item: QueueItem): Boolean {
        val list = _items.value.toMutableList()
        val at = _currentIndex.value
        if (at < 0) {
            // 队列为空：直接成为第一项
            list.add(item)
            _items.value = list
            _currentIndex.value = 0
            if (_shuffled.value) reshuffle()
            return true
        }
        // 已存在则先移除旧位置，避免同一首出现两次
        val oldIdx = list.indexOfFirst { it.key == item.key }
        if (oldIdx >= 0) {
            list.removeAt(oldIdx)
            // 移除位置在当前项之前时，当前下标要跟着前移
            if (oldIdx <= at) _currentIndex.value = (at - 1).coerceAtLeast(0)
        }
        val insertAt = (_currentIndex.value + 1).coerceIn(0, list.size)
        list.add(insertAt, item)
        _items.value = list
        if (_shuffled.value) reshuffle()
        return true
    }

    // ---------------- 删 ----------------

    /** 按 key 移除。移除的是当前项时，下标收敛到合法范围。 */
    fun remove(key: String): Boolean {
        val list = _items.value.toMutableList()
        val idx = list.indexOfFirst { it.key == key }
        if (idx < 0) return false
        list.removeAt(idx)
        _items.value = list
        val cur = _currentIndex.value
        _currentIndex.value = when {
            list.isEmpty() -> -1
            idx < cur -> cur - 1
            else -> cur.coerceAtMost(list.size - 1)
        }
        if (_shuffled.value) reshuffle()
        return true
    }

    /** 清空队列。 */
    fun clear() {
        _items.value = emptyList()
        _currentIndex.value = -1
        shuffleOrder = emptyList()
    }

    // ---------------- 排序 ----------------

    /**
     * 拖动排序：把 [from] 位置的项移到 [to]。
     *
     * ⚠️ 移动后必须**修正 `currentIndex`**，否则"当前播放项"会指向错误的条目
     * （表现为切歌后标题与实际播放内容不符）。
     */
    fun move(from: Int, to: Int): Boolean {
        val list = _items.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return false

        val item = list.removeAt(from)
        list.add(to, item)
        _items.value = list

        // 修正当前下标：跟随被移动的那一项
        val cur = _currentIndex.value
        _currentIndex.value = when {
            cur == from -> to
            from < cur && to >= cur -> cur - 1
            from > cur && to <= cur -> cur + 1
            else -> cur
        }
        if (_shuffled.value) reshuffle()
        return true
    }

    // ---------------- 选择 ----------------

    /** 直接定位到指定下标。 */
    fun select(index: Int): QueueItem? {
        if (index !in _items.value.indices) return null
        _currentIndex.value = index
        return _items.value[index]
    }

    /** 定位到指定 key。 */
    fun selectByKey(key: String): QueueItem? {
        val idx = _items.value.indexOfFirst { it.key == key }
        return if (idx >= 0) select(idx) else null
    }

    // ---------------- 推进 ----------------

    /**
     * 前进到下一项（自动播放 / "下一首"）。
     *
     * @param userInitiated 用户主动点"下一首"时为 true ——
     *        单曲循环下**用户点下一首应该换歌**，而"自然播完"才重播本首。
     *        这两者行为不同，必须区分。
     * @return 下一项；null = 到头了（应停止播放）
     */
    fun next(userInitiated: Boolean = false): QueueItem? {
        val list = _items.value
        if (list.isEmpty()) return null

        // 单曲循环：自然播完重播本首；用户主动点下一首仍然换歌
        if (_repeatMode.value == RepeatMode.ONE && !userInitiated) {
            return list.getOrNull(_currentIndex.value)
        }

        val nextIdx = nextIndex()
        if (nextIdx == null) {
            // 列表到头且非循环 → 停
            return null
        }
        _currentIndex.value = nextIdx
        return list[nextIdx]
    }

    /**
     * 后退到上一项（"上一首"）。
     *
     * @return 上一项；null = 到头（调用方通常回退到重新播放当前项）
     */
    fun previous(): QueueItem? {
        val list = _items.value
        if (list.isEmpty()) return null
        val prevIdx = prevIndex() ?: return null
        _currentIndex.value = prevIdx
        return list[prevIdx]
    }

    /**
     * 计算下一项下标。null = 到头。
     *
     * 三种模式的分支都在这里，便于单测直接覆盖（不需要真播放器）。
     */
    internal fun nextIndex(): Int? {
        val size = _items.value.size
        if (size == 0) return null
        val cur = _currentIndex.value

        if (_shuffled.value && shuffleOrder.size == size) {
            val pos = shuffleOrder.indexOf(cur).let { if (it < 0) 0 else it }
            val nextPos = pos + 1
            return if (nextPos < shuffleOrder.size) {
                shuffleOrder[nextPos]
            } else {
                // 一轮播完：循环则重新洗牌再从头
                if (_repeatMode.value == RepeatMode.ALL) {
                    reshuffle()
                    shuffleOrder.firstOrNull()
                } else {
                    null
                }
            }
        }

        return if (cur + 1 < size) {
            cur + 1
        } else {
            if (_repeatMode.value == RepeatMode.ALL) 0 else null
        }
    }

    internal fun prevIndex(): Int? {
        val size = _items.value.size
        if (size == 0) return null
        val cur = _currentIndex.value

        if (_shuffled.value && shuffleOrder.size == size) {
            val pos = shuffleOrder.indexOf(cur).let { if (it < 0) 0 else it }
            return if (pos - 1 >= 0) shuffleOrder[pos - 1] else null
        }
        return if (cur - 1 >= 0) cur - 1 else null
    }

    // ---------------- 模式 ----------------

    /** 循环模式轮转：关闭 → 列表 → 单曲 → 关闭。 */
    fun cycleRepeatMode(): RepeatMode {
        val next = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _repeatMode.value = next
        return next
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
    }

    /**
     * 切换随机播放。
     *
     * ⚠️ 开启时**必须把当前项放在洗牌序列首位** ——
     * 否则点"随机"的瞬间当前歌就跳走了，用户会以为播放器出 bug。
     */
    fun setShuffled(enabled: Boolean) {
        _shuffled.value = enabled
        if (enabled) reshuffle() else shuffleOrder = emptyList()
    }

    fun toggleShuffle(): Boolean {
        setShuffled(!_shuffled.value)
        return _shuffled.value
    }

    private fun reshuffle() {
        val size = _items.value.size
        if (size == 0) {
            shuffleOrder = emptyList()
            return
        }
        val cur = _currentIndex.value
        val rest = (0 until size).filter { it != cur }.shuffled()
        // 当前项固定在首位，保证"开随机"不跳歌
        shuffleOrder = if (cur in 0 until size) listOf(cur) + rest else rest
    }
}
