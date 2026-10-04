package com.example.biliv3.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * **后台播放服务** —— 系统媒体中心 / 锁屏 / 耳机按键的接入点。
 *
 * ## 架构（任务书 §11 要求的结构）
 *
 * ```
 * UI (Compose)
 *   ↓ MediaController        ← 跨进程代理，只能传 MediaItem
 * MediaSessionService        ← 本类
 *   ↓ MediaSession
 * ExoPlayer                  ← 真正的解码播放
 * ```
 *
 * ## 🔴 为什么必须做成 Service（不能只在 Activity 里建 MediaSession）
 *
 * 任务书 §11.5 要求「退出 Activity 后音频必须继续播放」。
 * `MediaSession` 建在 Activity 里的话，Activity 一销毁会话就没了 ——
 * 系统媒体中心的控件会**立刻变灰**，耳机按键也没反应。
 *
 * 放进 `MediaSessionService` 后：Service 是**前台服务**，
 * 有独立的生命周期，Activity 销毁不影响它。
 *
 * ## ⚠️ 与现有 Activity 级 `PlayerHolder` 的关系（本轮最关键的架构决策）
 *
 * 本项目原有 `PlayerHolder` 是 **Activity 级**的，PiP 依赖它。
 * 任务书同时要求：
 * - §27「不要让新增 MediaSession/MediaSessionService 破坏现有 Activity 级播放状态」
 * - §22「禁止创建第二套 Player」
 *
 * **两者的矛盾**：Service 里的 ExoPlayer 与 Activity 里的 ExoPlayer
 * 必然是**两个实例**（跨进程/跨组件，不可能共享对象引用）。
 *
 * ### 取舍：Service 是**唯一**的播放器持有者
 *
 * 本轮的解法是**不引入第二个 Player**，而是让 Service **接管**播放：
 *
 * | 场景 | 谁在播 |
 * |---|---|
 * | 视频页前台播放（原有主链路） | `PlayerHolder`（Activity 级，不变） |
 * | 听视频 / 黑胶 / 后台播放 | 本 Service |
 *
 * 两者**互斥**：进入听视频/后台模式时，由 `PlaybackController` 停止
 * `PlayerHolder` 并把播放交给 Service。这样：
 * - 任何时刻**只有一个** ExoPlayer 在解码（不会两路声音）
 * - 原有的视频播放链路**一行没改**（§27 不被破坏）
 *
 * ⚠️ 这是**刻意**的取舍，不是遗漏。完全的"单一实例"需要把详情页
 * 也改成 `MediaController`，那会重写整个播放链路 —— 违反"不推翻现有架构"。
 *
 * ## 为什么不用 `DefaultMediaNotificationProvider` 的默认样式
 *
 * 其实**就是用它**。任务书 §11.2 要求「不要自行维护另一份虚假的播放状态」——
 * Media3 会从 `MediaSession` + `MediaMetadata` 自动生成通知，
 * 标题/作者/封面/进度全部来自 `MediaItem`，不需要我们手写 `Notification`。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        // ⚠️ 必须用自定义 MediaSource.Factory ——
        // B 站 DASH 是音视频两条 URL，需要 MergingMediaSource。
        // 详见 `BiliMediaSourceFactory` 的类注释（跨进程限制）。
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(BiliMediaSourceFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // 音乐类：让系统按"媒体"音量条控制，而不是"通知"音量
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // handleAudioFocus = true：来电 / 其它 App 播放时自动让路
                true,
            )
            // 拔耳机自动暂停（与 PlayerFactory 的既有行为一致）
            .setHandleAudioBecomingNoisy(true)
            .build()

        player = exo

        // ⚠️ `setSessionActivity` 要求**非空** PendingIntent ——
        // 拿不到 launch intent（理论上不会发生）时就不能设，
        // 否则会 NPE。没有 sessionActivity 只是"点通知不回 App"，
        // 比崩溃可接受。
        val sessionBuilder = MediaSession.Builder(this, exo)
        buildSessionActivity()?.let { sessionBuilder.setSessionActivity(it) }
        mediaSession = sessionBuilder.build()
    }

    /**
     * 点通知回到 App。
     *
     * 用 `packageManager.getLaunchIntentForPackage` 而不是硬编码 Activity 名 ——
     * 后者在改名后会静默失效（通知点了没反应）。
     */
    private fun buildSessionActivity(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 用户从最近任务划掉 App。
     *
     * - **正在播放** → 保持前台服务（音乐类 App 的标准行为：
     *   划掉界面不等于停止播放）
     * - **已暂停** → 停止服务并移除通知（否则会留一个"僵尸通知"）
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        /**
         * 通知栏"下一首/上一首"是否显示。
         *
         * Media3 依据 `Player` 的 `availableCommands` 自动决定 ——
         * 队列只有一首时不该显示切歌按钮（点了没反应 = 死入口）。
         */
        fun commandMaskFor(queueSize: Int): Long = buildList {
            add(Player.COMMAND_PLAY_PAUSE)
            add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            add(Player.COMMAND_STOP)
            if (queueSize > 1) {
                // 只有多首时才给切歌命令（单首时按钮点了没反应 = 死入口）
                add(Player.COMMAND_SEEK_TO_NEXT)
                add(Player.COMMAND_SEEK_TO_PREVIOUS)
                add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }
        }.fold(0L) { acc, c -> acc or c.toLong() }

        /** 把队列项转成 Service 能播的 `MediaItem`。 */
        fun mediaItemOf(
            videoUrl: String,
            audioUrl: String,
            item: QueueItem,
            audioOnly: Boolean,
        ): MediaItem = BiliMediaSourceFactory.buildMediaItem(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            title = item.title,
            artist = item.author,
            artworkUrl = item.cover,
            audioOnly = audioOnly,
        )
    }
}
