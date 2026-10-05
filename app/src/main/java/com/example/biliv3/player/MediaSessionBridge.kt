package com.example.biliv3.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * **UI 与后台播放服务之间的桥**。
 *
 * ## 职责
 *
 * 1. 建立 / 持有 `MediaController`（连接 `PlaybackService`）
 * 2. 把队列项推给 Service（Service 侧还原成 `MergingMediaSource`）
 * 3. 检查通知权限（**缺权限时 Media3 静默不显示通知**）
 *
 * ## ⚠️ 为什么需要"接管/交还"两个动作
 *
 * 本项目有**两个** ExoPlayer 可能实例（见 `PlaybackService` 的架构说明）：
 * - `PlayerHolder`（Activity 级，视频页用）
 * - `PlaybackService`（后台播放用）
 *
 * 两者必须**互斥**，否则会出现两路声音。
 * 所以进入听视频/黑屏后台模式时调用 [takeOver]，
 * 回到视频页时调用 [releaseToActivity]。
 *
 * 这是刻意的取舍 —— 完全单一实例需要把详情页也改成 `MediaController`，
 * 会重写整条播放链路（违反"不推翻现有架构"）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaSessionBridge(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** 连接状态回调（UI 可据此显示"后台播放未连接"）。 */
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    val isConnected: Boolean get() = controller != null

    /**
     * 通知权限是否已授予。
     *
     * ## ⚠️ 为什么必须显式检查
     *
     * Android 13+ 上未授予 `POST_NOTIFICATIONS` 时，
     * **Media3 不会抛异常、不会报错 —— 只是不显示通知**。
     * 用户看到的是"锁屏什么都没有"，而日志一片干净。
     *
     * 这类"静默失效"是本项目反复记录的最难排查的一类问题
     * （见 `AGENTS-P2.md` 坑 13）。
     */
    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 连接到后台播放服务。
     *
     * 幂等：已连接时直接回调 [onConnected]。
     */
    fun connect() {
        if (controller != null) {
            onConnected?.invoke()
            return
        }
        if (controllerFuture != null) return   // 正在连接中

        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                try {
                    controller = future.get()
                    onConnected?.invoke()
                } catch (e: Exception) {
                    // 连接失败不能崩 —— 后台播放是增强能力，
                    // 失败时前台播放仍应正常工作
                    Log.w(TAG, "MediaController 连接失败", e)
                    controllerFuture = null
                    onDisconnected?.invoke()
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * **接管播放**：把队列交给 Service，由它继续播。
     *
     * @param items 队列（第一项为当前项）
     * @param playInfos 每项的 (videoUrl, audioUrl)
     * @param audioOnly 听视频模式
     * @param startPositionMs 从哪个位置接着播（**切换模式必须传**，
     *        否则会从 0 重播）
     */
    fun takeOver(
        items: List<QueueItem>,
        playInfos: Map<String, Pair<String, String>>,
        audioOnly: Boolean,
        startPositionMs: Long,
    ): Boolean {
        val c = controller ?: return false
        if (items.isEmpty()) return false

        val mediaItems = items.mapNotNull { item ->
            val urls = playInfos[item.key] ?: return@mapNotNull null
            PlaybackService.mediaItemOf(
                videoUrl = urls.first,
                audioUrl = urls.second,
                item = item,
                audioOnly = audioOnly,
            )
        }
        if (mediaItems.isEmpty()) return false

        c.setMediaItems(mediaItems, 0, startPositionMs)
        c.prepare()
        c.play()
        return true
    }

    /** 切换"只听音频"（重建 MediaItem 以改变视频轨有无）。 */
    fun setAudioOnly(
        items: List<QueueItem>,
        playInfos: Map<String, Pair<String, String>>,
        audioOnly: Boolean,
        positionMs: Long,
        wasPlaying: Boolean,
    ): Boolean {
        val c = controller ?: return false
        val idx = c.currentMediaItemIndex.coerceAtLeast(0)
        val mediaItems = items.mapNotNull { item ->
            val urls = playInfos[item.key] ?: return@mapNotNull null
            PlaybackService.mediaItemOf(urls.first, urls.second, item, audioOnly)
        }
        if (mediaItems.isEmpty()) return false

        c.setMediaItems(mediaItems, idx.coerceIn(0, mediaItems.size - 1), positionMs)
        c.prepare()
        if (wasPlaying) c.play()
        return true
    }

    /** 播放控制（供 UI 调用；Service 侧会同步到通知与锁屏）。 */
    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun next() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem()
    }

    /** 当前播放位置（毫秒）。 */
    fun positionMs(): Long = controller?.currentPosition ?: 0L

    fun durationMs(): Long = controller?.duration?.coerceAtLeast(0L) ?: 0L

    fun isPlaying(): Boolean = controller?.isPlaying == true

    /**
     * 把队列同步给 Service（不改变当前播放项）。
     *
     * 用于：用户在队列页拖动排序 / 删除后，让通知栏的"下一首"跟着变。
     */
    fun syncQueue(
        items: List<QueueItem>,
        playInfos: Map<String, Pair<String, String>>,
        audioOnly: Boolean,
        currentIndex: Int,
        positionMs: Long,
        wasPlaying: Boolean,
    ): Boolean {
        val c = controller ?: return false
        val mediaItems = items.mapNotNull { item ->
            val urls = playInfos[item.key] ?: return@mapNotNull null
            PlaybackService.mediaItemOf(urls.first, urls.second, item, audioOnly)
        }
        if (mediaItems.isEmpty()) return false
        c.setMediaItems(mediaItems, currentIndex.coerceIn(0, mediaItems.size - 1), positionMs)
        if (wasPlaying) c.play()
        return true
    }

    /**
     * 当前 Service 侧**实际可用**的命令。
     *
     * ⚠️ 这里**只读不可写** —— `MediaController.availableCommands` 是
     * 连接建立时协商好的，客户端无法改（那是 `MediaSession` 的职责）。
     *
     * 早期版本这里有个 `applyCommandMask()` 试图写它，编译不过 ——
     * 说明设计上就不该由客户端决定。通知栏按钮的显示与否，
     * 由 **Service 侧 `Player` 的实际能力**（有无下一首）自动决定，
     * 这正是任务书 §11.2 要的"不要自行维护另一份状态"。
     */
    fun availableCommands(): Long = controller?.availableCommands?.let { cmds ->
        // 转成位掩码便于日志/调试；实际 UI 判断用 controller 的 hasNext 等方法
        listOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        ).fold(0L) { acc, c -> if (cmds.contains(c)) acc or c.toLong() else acc }
    } ?: 0L

    /** Service 侧是否有下一首（决定通知栏按钮是否该可用）。 */
    fun hasNext(): Boolean = controller?.hasNextMediaItem() == true

    fun hasPrevious(): Boolean = controller?.hasPreviousMediaItem() == true

    /**
     * **交还播放**：停止 Service，把播放权还给 Activity 的 `PlayerHolder`。
     *
     * 回到视频页时调用 —— 否则会出现"两路声音同时响"。
     */
    fun releaseToActivity() {
        controller?.runCatching {
            stop()
            clearMediaItems()
        }
    }

    /** 完全释放（Activity 销毁）。 */
    fun release() {
        controllerFuture?.let {
            runCatching { MediaController.releaseFuture(it) }
        }
        controllerFuture = null
        controller = null
    }

    /** 当前队列长度（Service 侧）。 */
    fun mediaItemCount(): Int = controller?.mediaItemCount ?: 0

    /** 监听 Service 侧状态（如耳机按键触发的播放/暂停）。 */
    fun addListener(listener: Player.Listener) {
        controller?.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        controller?.removeListener(listener)
    }

    private companion object {
        const val TAG = "BiliMediaSession"
    }
}
