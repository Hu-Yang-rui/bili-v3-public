package com.example.biliv3.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.biliv3.data.lyrics.LyricsRepository
import com.example.biliv3.data.model.PlayInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * **统一播放控制器** —— 全 App 唯一的播放状态源。
 *
 * ## 它解决什么问题
 *
 * 任务书 §23 要求「避免 UI / Player / Notification / MediaSession
 * 各自维护独立状态」。此前 `PlayerHolder` 只负责"装配流"，
 * 播放模式（听视频/黑胶）、队列、定时器、歌词**散落在各个页面里** ——
 * 于是：
 *
 * - 详情页与竖屏页各写一套"切歌"逻辑 → 行为不一致
 * - 定时器放在详情页 → 退出详情页就丢了
 * - 听视频模式只有详情页知道 → 竖屏页不知道当前该不该出画面
 *
 * 现在这些全部收敛到这里。**UI 只读不写**（除用户操作外）。
 *
 * ## 与 PlayerHolder 的分工
 *
 * | | 职责 |
 * |---|---|
 * | `PlayerHolder` | 持有 `ExoPlayer` 实例、装配 MediaSource、释放 |
 * | `PlaybackController` | 队列推进、模式切换、定时器、歌词、统一状态 |
 *
 * 为什么不合并：`PlayerHolder` 是"设备资源持有者"（谁都不能有两个），
 * `PlaybackController` 是"播放业务逻辑"。分开后业务逻辑可以单测。
 *
 * ## ⚠️ 单一播放核心
 *
 * 本类**不创建 ExoPlayer**。所有播放都经过 `holder`，
 * 满足任务书「禁止创建第二套 Player」。
 */
class PlaybackController(
    private val context: Context,
    val holder: PlayerHolder,
    private val scope: CoroutineScope,
    /** 歌词仓库（可空：未接线时歌词功能静默不可用，不影响播放）。 */
    private val lyricsRepository: LyricsRepository? = null,
    /**
     * MediaSession 桥（v1.3.0 接线）。
     *
     * 可空：桥接不可用时（比如用户拒绝了通知权限）**不影响前台播放**，
     * 只是没有锁屏控件。
     */
    private val mediaSessionBridge: MediaSessionBridge? = null,
) {

    /** 播放队列（独立，可单测）。 */
    val queue = PlaybackQueue()

    /** 睡眠定时器。 */
    val sleepTimer = SleepTimer()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /**
     * 当前项的取流信息（由页面在拿到详情后回填）。
     *
     * 队列项只有 `bvid`，真正播放需要 `PlayInfo`（含 URL）。
     * 取流是页面的职责（它已经在拉详情），这里只缓存结果。
     */
    private var currentPlayInfo: PlayInfo? = null

    /** 当前项的 aid（歌词接口要 aid 不是 bvid）。 */
    private var currentAid: Long = 0L

    /** 定时器轮询任务。 */
    private var timerJob: Job? = null

    /** 定时到期时的回调（由 UI 层注册，用于提示用户）。 */
    var onSleepTimerFired: (() -> Unit)? = null

    // ---------------- 播放 ----------------

    /**
     * 开始播放队列中的某一项。
     *
     * @param info 取流信息；null 表示"还没拿到流"（只更新队列游标）
     */
    fun playQueueItem(item: QueueItem, info: PlayInfo?, aid: Long = 0L) {
        queue.selectByKey(item.key)
        currentPlayInfo = info
        currentAid = aid

        if (info == null) {
            // 只切游标，等页面拉到流后再 bind
            publish()
            return
        }
        bindCurrent(playWhenReady = true)
        loadLyricsFor(item)
    }

    /**
     * 页面拿到取流信息后回填并播放。
     *
     * 这是**最常走**的路径：页面拉详情 → 取流 → 调这里。
     */
    fun attachPlayInfo(info: PlayInfo, aid: Long, resumePositionMs: Long = 0L) {
        currentPlayInfo = info
        currentAid = aid

        // 队列为空时补一个当前项（单视频播放不该被队列机制挡住）
        if (queue.isEmpty) {
            publish()
        }

        holder.bindMedia(
            info = info,
            playWhenReady = true,
            audioOnly = _state.value.mode != PlaybackMode.VIDEO,
            resumePositionMs = resumePositionMs,
        )
        queue.current?.let { loadLyricsFor(it) }
        publish()
    }

    private fun bindCurrent(playWhenReady: Boolean, resumePositionMs: Long = 0L) {
        val info = currentPlayInfo ?: return
        holder.bindMedia(
            info = info,
            playWhenReady = playWhenReady,
            audioOnly = _state.value.mode != PlaybackMode.VIDEO,
            resumePositionMs = resumePositionMs,
        )
    }

    fun play() {
        holder.player?.play()
        publish()
    }

    fun pause() {
        holder.player?.pause()
        publish()
    }

    fun togglePlayPause() {
        val p = holder.player ?: return
        if (p.isPlaying) p.pause() else p.play()
        publish()
    }

    fun seekTo(positionMs: Long) {
        holder.player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setSpeed(speed: Float) {
        holder.player?.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    // ---------------- 队列推进 ----------------

    /**
     * 下一首。
     *
     * @param userInitiated true = 用户点按钮（单曲循环下也换歌）
     * @return 需要页面去取流的新项；null = 队列到头，应停止
     */
    fun next(userInitiated: Boolean = false): QueueItem? {
        val item = queue.next(userInitiated) ?: run {
            // 到头：停止播放（不是暂停 —— 暂停会留着通知栏）
            holder.player?.pause()
            publish()
            return null
        }
        // 换了项 → 旧流失效，等页面重新取流
        currentPlayInfo = null
        publish()
        return item
    }

    fun previous(): QueueItem? {
        val item = queue.previous() ?: return null
        currentPlayInfo = null
        publish()
        return item
    }

    // ---------------- 模式切换 ----------------

    /**
     * 切换播放模式（看视频 ⇄ 听视频 ⇄ 黑胶）。
     *
     * ## ⚠️ 位置保持是这个函数的**唯一难点**
     *
     * 切换模式必然重建 MediaSource（视频轨有无不同），
     * 而 `setMediaSource` 会把位置重置到 0。
     *
     * 所以顺序必须是：**先记位置 → 重建 → 再 seek 回去**。
     * 少任何一步都会"切模式就从头播"（任务书明确禁止）。
     */
    fun setMode(mode: PlaybackMode) {
        if (_state.value.mode == mode) return
        val info = currentPlayInfo

        // 1. 记位置与播放状态
        val pos = holder.currentPosition
        val wasPlaying = holder.isPlaying

        // 2. 更新模式（bindMedia 会读它决定 audioOnly）
        _state.value = _state.value.copy(mode = mode)

        // 3. 重建并恢复位置
        if (info != null) {
            bindCurrent(playWhenReady = wasPlaying, resumePositionMs = pos)
        }
        publish()
    }

    /** 听视频开关（快捷方式）。 */
    fun toggleAudioOnly() {
        setMode(
            if (_state.value.mode == PlaybackMode.VIDEO) PlaybackMode.AUDIO
            else PlaybackMode.VIDEO,
        )
    }

    /** 沉浸式开关。 */
    fun setImmersive(on: Boolean) {
        _state.value = _state.value.copy(immersive = on)
    }

    // ---------------- 定时器 ----------------

    /**
     * 启动睡眠定时。
     *
     * @param minutes 0 表示"当前视频结束"
     */
    fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) sleepTimer.startEndOfItem() else sleepTimer.start(minutes)
        ensureTimerLoop()
        publish()
    }

    fun cancelSleepTimer() {
        sleepTimer.cancel()
        timerJob?.cancel()
        timerJob = null
        publish()
    }

    private fun ensureTimerLoop() {
        if (timerJob?.isActive == true) return
        timerJob = scope.launch {
            while (isActive && sleepTimer.active.value) {
                delay(1000L)
                if (sleepTimer.tick()) {
                    // 到期：暂停 + 停止自动下一首 + 清理
                    holder.player?.pause()
                    sleepTimer.cancel()
                    onSleepTimerFired?.invoke()
                    publish()
                    break
                }
                publish()
            }
        }
    }

    /**
     * 播放结束时调用（由页面在 `STATE_ENDED` 时触发）。
     *
     * 处理两件事：
     * 1. **"当前视频结束"模式的定时器**在此触发
     * 2. 否则自动播放下一首（若队列还有）
     */
    fun onItemEnded(): QueueItem? {
        if (sleepTimer.isEndOfItemMode) {
            holder.player?.pause()
            sleepTimer.cancel()
            onSleepTimerFired?.invoke()
            publish()
            return null
        }
        return next()
    }

    // ---------------- 队列登记 ----------------

    /**
     * 把「当前在播的视频」登记进队列（详情页调用）。
     *
     * ## 为什么需要它（装机实测发现的缺口）
     *
     * 详情页此前**完全不碰队列** —— 于是黑胶 / 听视频页读到的
     * `currentItem` 是 null，界面显示「暂无播放」而音频却在响。
     *
     * ## 为什么是"覆盖"而不是"追加"
     *
     * 详情页是"点进来的**一个**视频"。用追加的话，用户浏览 10 个视频后
     * 队列会堆 10 条无关记录，「下一首」会播到很早以前点开的视频。
     * 用户显式点「添加到队列」时才走 `queue.add` / `queue.playNext`。
     */
    fun registerInQueue(
        bvid: String,
        cid: Long,
        title: String,
        author: String,
        cover: String,
        durationSeconds: Int = 0,
    ) {
        val changed = queue.setSingle(
            QueueItem(
                bvid = bvid,
                cid = cid,
                title = title,
                author = author,
                cover = cover,
                durationSeconds = durationSeconds,
            ),
        )
        if (changed) publish()
    }

    // ---------------- 后台播放（v1.3.0 接线） ----------------

    /**
     * 把播放**交给 Service**（进入听视频 / 退到后台时用）。
     *
     * ## 为什么必须"先记位置再交"
     *
     * `takeOver` 会 `setMediaItems(..., 0, startPositionMs)` —— 传 0
     * 就等于从头上重播（任务书明确禁止"切模式就从头播"）。
     *
     * ## 与 `PlayerHolder` 的关系
     *
     * 交接后**前台 holder 必须停掉**，否则两个 ExoPlayer 会同时出声。
     * 这是"Activity 级 holder + Service"这套折中方案的代价，
     * 已在 `PlaybackService` 的 KDoc 与 AGENTS §11.1 里说明。
     *
     * @return true = 交接成功；false = 条件不满足（调用方应保持前台播放）
     */
    fun handoffToService(): Boolean {
        val bridge = mediaSessionBridge ?: return false
        if (!bridge.isConnected) return false

        val item = queue.current ?: return false
        val info = currentPlayInfo ?: return false

        val position = holder.player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val wasPlaying = holder.player?.isPlaying == true

        // ⚠️ 先把当前项的位置与 URL 记下来 —— 交出去后 holder 就空了
        val urls = mapOf(item.key to (info.videoUrl to info.audioUrl))

        val ok = bridge.takeOver(
            items = queue.items.value,
            playInfos = urls,
            audioOnly = _state.value.mode != PlaybackMode.VIDEO,
            startPositionMs = position,
        )
        if (!ok) return false

        // 交接成功 → 停掉前台，避免双份出声
        runCatching { holder.player?.pause() }
        if (wasPlaying) {
            // Service 侧已经 play()，这里只更新状态
            _state.value = _state.value.copy(isPlaying = true)
        }
        return true
    }

    /**
     * 从 Service **收回**播放（回到前台）。
     *
     * @param info 当前项的取流信息（页面重新拉详情后回填）
     */
    fun takeBackFromService(info: PlayInfo, resumePositionMs: Long): Boolean {
        val bridge = mediaSessionBridge ?: return false
        if (!bridge.isConnected) return false

        val pos = if (resumePositionMs > 0L) resumePositionMs else bridge.positionMs()
        bridge.releaseToActivity()

        currentPlayInfo = info
        holder.bindMedia(
            info = info,
            playWhenReady = true,
            audioOnly = _state.value.mode != PlaybackMode.VIDEO,
            resumePositionMs = pos,
        )
        publish()
        return true
    }

    /** Service 是否在播（决定 UI 显示哪一套控制）。 */
    fun isServicePlaying(): Boolean = mediaSessionBridge?.isPlaying() == true

    // ---------------- 歌词 ----------------

    private fun loadLyricsFor(item: QueueItem) {
        val repo = lyricsRepository ?: return
        scope.launch {
            repo.load(
                bvid = item.bvid,
                cid = item.cid,
                aid = currentAid,
                title = item.title,
                durationMs = holder.duration,
            )
        }
    }

    /** 重新拉歌词（用户点重试）。 */
    fun retryLyrics() {
        val item = queue.current ?: return
        val repo = lyricsRepository ?: return
        scope.launch {
            repo.load(
                bvid = item.bvid,
                cid = item.cid,
                aid = currentAid,
                title = item.title,
                durationMs = holder.duration,
                force = true,
            )
        }
    }

    // ---------------- 状态发布 ----------------

    /**
     * 把 `ExoPlayer` 的实时状态同步进 [state]。
     *
     * ## 为什么是"拉"而不是"推"
     *
     * ExoPlayer 的回调（`onIsPlayingChanged` 等）只覆盖**状态变化**，
     * 而 `currentPosition` 是连续变化的、没有回调。
     * 所以 UI 层按自己的节奏调 [publish]（进度条 500ms、歌词 100ms），
     * 避免为位置变化做全局广播（每秒 5 次全 App 重组）。
     */
    fun publish() {
        val p = holder.player
        _state.value = _state.value.copy(
            currentItem = queue.current,
            isPlaying = p?.isPlaying == true,
            isBuffering = p?.playbackState == androidx.media3.common.Player.STATE_BUFFERING,
            hasPlayer = p != null && !p.isReleased,
        )
    }

    /** 当前播放位置（毫秒）。UI 轮询用。 */
    fun positionMs(): Long = holder.currentPosition

    /** 总时长（毫秒）。 */
    fun durationMs(): Long = holder.duration

    /** 释放（Activity 销毁时）。 */
    fun release() {
        timerJob?.cancel()
        timerJob = null
        holder.release()
        publish()
    }
}
