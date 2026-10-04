package com.example.biliv3.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放模式的三种视觉形态。
 *
 * 它们**不是互斥的播放器**，而是同一个 `ExoPlayer` 的三种呈现方式 ——
 * 任务书要求「禁止创建第二套 Player」。
 */
enum class PlaybackMode {
    /** 看视频（默认）。画面 + 声音。 */
    VIDEO,

    /**
     * 听视频（纯音频）。
     *
     * ⚠️ 不是"把画面藏起来" —— 而是**不装配视频轨**
     * （见 [com.example.biliv3.player.PlayerHolder.bindMedia] 的 `audioOnly`）。
     * 这样 GPU / 视频解码器完全不参与，才是真正的省电。
     */
    AUDIO,

    /** 黑胶唱片模式（音频 + 唱片视觉）。 */
    VINYL,
}

/**
 * 统一播放状态。
 *
 * ## 为什么必须统一
 *
 * 任务书 §24 明确要求避免「UI / Player / Notification / MediaSession
 * 各自维护一套状态」。本项目此前确实存在这类隐患：
 * 详情页从 `ExoPlayer` 直接读 `isPlaying`、`currentPosition`，
 * 而通知（若有）会另读一份 —— 两处刷新时机不同就会不一致。
 *
 * 现在改为：**`PlayerHolder` 是唯一写入方，其它所有地方只读**。
 *
 * ## 为什么 position 是"快照"而不是 StateFlow
 *
 * `currentPosition` 每 200ms 变一次，做成 StateFlow 会导致
 * 全 App 每秒重组 5 次。这里**只在需要时读**（`snapshot()`），
 * 而 UI 用 `LaunchedEffect` 按自己的节奏轮询（歌词页 100ms、
 * 进度条 500ms）—— 各取所需，不做全局广播。
 */
data class PlaybackState(
    /** 当前播放项。null = 队列空。 */
    val currentItem: QueueItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 播放模式（看 / 听 / 黑胶）。 */
    val mode: PlaybackMode = PlaybackMode.VIDEO,
    /** 沉浸式（隐藏系统 UI + 控件自动隐藏）。 */
    val immersive: Boolean = false,
    /** 播放倍速。1.0 = 正常。 */
    val speed: Float = 1.0f,
    /** 播放器是否已创建（未创建时 UI 应显示占位而不是空黑）。 */
    val hasPlayer: Boolean = false,
    /** 最近一次错误（展示后应清除）。 */
    val error: String? = null,
)

/**
 * 睡眠定时器。
 *
 * ## 为什么状态放这里而不是 Composable
 *
 * 任务书要求「Activity 重建后计时状态不能丢失」。
 * Composable 的 `remember` 会随 Activity 重建清空 —— 定时器就废了。
 *
 * 放在 `PlayerHolder`（Activity 级单例）里：
 * 配置变更（转屏）时 Activity 重建但 holder 存活（它由 Application 级容器持有），
 * 计时不中断。这也是"为什么不用 Service"的答案 ——
 * 睡眠定时是**用户会话内**的概念，不需要跨进程存活。
 *
 * ## 计时实现
 *
 * 用**截止时间戳**（`deadlineMs`）而不是倒计时累加：
 * 累加在系统休眠 / 进程被调度挂起时会走慢（挂起期间协程不执行），
 * 截止时间戳则天然正确 —— 醒来一比对就知道过没过期。
 */
class SleepTimer {

    private val _remainingMs = MutableStateFlow(0L)
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /**
     * 触发模式。
     */
    enum class Mode {
        /** 到时间就暂停。 */
        TIME,

        /** 当前视频播完再停（不打断正在看的这一集）。 */
        END_OF_ITEM,
    }

    private var deadlineMs: Long = 0L

    var mode: Mode = Mode.TIME
        private set

    /** 是否"当前视频结束"模式。 */
    val isEndOfItemMode: Boolean get() = _active.value && mode == Mode.END_OF_ITEM

    /**
     * 设定定时（分钟）。
     *
     * @param minutes 0 或负数视为取消
     */
    fun start(minutes: Int) {
        if (minutes <= 0) {
            cancel()
            return
        }
        mode = Mode.TIME
        deadlineMs = System.currentTimeMillis() + minutes * 60_000L
        _remainingMs.value = minutes * 60_000L
        _active.value = true
    }

    /** 设定"当前视频结束"模式。 */
    fun startEndOfItem() {
        mode = Mode.END_OF_ITEM
        deadlineMs = 0L
        _remainingMs.value = 0L
        _active.value = true
    }

    fun cancel() {
        deadlineMs = 0L
        _remainingMs.value = 0L
        _active.value = false
    }

    /**
     * 刷新剩余时间。
     *
     * @return true = 已到期，调用方应暂停播放并 [cancel]
     */
    fun tick(): Boolean {
        if (!_active.value) return false
        if (mode == Mode.END_OF_ITEM) return false   // 由"播放结束"事件触发，不靠时间
        val left = deadlineMs - System.currentTimeMillis()
        _remainingMs.value = left.coerceAtLeast(0L)
        return left <= 0L
    }

    /** 剩余时间文本（`MM:SS`；未启用返回空串）。 */
    fun label(): String {
        if (!_active.value) return ""
        if (mode == Mode.END_OF_ITEM) return "本集结束"
        val total = (_remainingMs.value / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        /** 预设档位（分钟）。0 在 UI 上表示"当前视频结束"，由调用方区分。 */
        val PRESETS = listOf(10, 20, 30, 45, 60)
    }
}
