package com.example.biliv3.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.example.biliv3.data.model.PlayInfo

/**
 * 应用级播放器持有者（**跨页面 / 跨 PiP 存活**）。
 *
 * ## ⚠️ 为什么必须提升到这一层（PiP 的硬前提）
 *
 * 原先 `ExoPlayer` 建在 `VideoDetailScreen` 的 Composable 里，
 * 用 `DisposableEffect(Unit) { onDispose { player.release() } }` 释放 ——
 * 也就是**页面销毁即释放播放器**。
 *
 * 这与以下需求直接冲突：
 *
 * | 需求 | 为什么冲突 |
 * |---|---|
 * | PiP 小窗 | 进 PiP 时 Activity 进后台，但页面 Composable 可能被销毁 → 播放器被 release |
 * | 切换页面继续播 | 离开详情页即销毁 → 声音断掉 |
 * | 锁屏播控 | 需要有稳定的 Player 实例持有 MediaSession |
 *
 * 所以播放器必须由**比页面更长命**的对象持有。
 *
 * ## 为什么不放 AppContainer（进程级）
 *
 * 进程级会导致「退出所有界面后播放器仍活着」→ 声音停不下来、泄漏解码器。
 * Activity 级是恰当的生命周期：用户离开应用（Activity 销毁）就释放。
 *
 * ## 为什么不直接上 MediaSessionService
 *
 * 那是"后台播放 + 通知栏控制"的完整方案，需要前台服务 + 通知权限。
 * 本项目的 PiP 需求（应用可见但非全屏时继续播）用 Activity 级足够。
 * 将来若要真正的后台播放，再把本类换成 Service 绑定即可 —— 调用方接口不变。
 *
 * ## 单例语义
 *
 * 同一时刻只应有一个播放器（一个视频在播）。`acquire()` 复用已有实例，
 * 只在需要时重建，避免"每个视频页各建一个"导致的多路解码器。
 */
class PlayerHolder(private val context: Context) {

    /**
     * 当前播放器。null = 尚未创建。
     *
     * 用 `mutableStateOf` 让 Compose 能观察到"播放器被创建/销毁"，
     * 页面重建（如 PiP 进出）时能重新绑定到同一个实例。
     */
    var player: ExoPlayer? by mutableStateOf(null)
        private set

    /** 播放器错误回调（页面订阅，用于展示错误态）。 */
    var onError: ((PlaybackException) -> Unit)? = null

    /**
     * 当前装配的流信息（用于判断"是否同一个流"）。
     *
     * 切清晰度 / 切分P / 换视频时 URL 变化 → 需要重新装配 MediaSource。
     */
    var currentPlayInfo: PlayInfo? = null
        private set

    /** 当前关联的视频标识（bvid 或 ep_id 的字符串形式）。 */
    var currentKey: String? = null
        private set

    /**
     * 取得播放器（不存在则创建）。
     *
     * @param key 视频标识。与 [currentKey] 不同时会**重建**播放器 ——
     *            换视频必须重建，否则会带着上一条流的缓冲状态。
     */
    fun acquire(key: String): ExoPlayer {
        val existing = player
        if (existing != null && currentKey == key && !existing.isReleased) {
            return existing
        }

        // 换视频：先释放旧的，避免解码器叠加
        releaseInternal()

        currentKey = key
        currentPlayInfo = null
        return PlayerFactory.createPlayer(context) { e ->
            onError?.invoke(e)
        }.also { player = it }
    }

    /**
     * 装配媒体源（仅在流信息变化时真正重建）。
     *
     * @return true 表示重新装配了；false 表示流未变、复用现有缓冲
     */
    fun bindMedia(info: PlayInfo, playWhenReady: Boolean = true): BindResult {
        val p = player ?: return BindResult.NoPlayer
        if (p.isReleased) return BindResult.Released

        // URL 相同视为同一条流：不要重复 setMediaSource，
        // 否则会把用户已经缓冲的进度扔掉（表现为"卡一下重头来"）
        val same = currentPlayInfo?.let {
            it.videoUrl == info.videoUrl && it.audioUrl == info.audioUrl &&
                it.currentQuality == info.currentQuality
        } == true
        if (same) return BindResult.Reused

        val source = PlayerFactory.buildMediaSource(info) ?: return BindResult.NoSource

        currentPlayInfo = info
        return runCatching {
            p.setMediaSource(source)
            p.prepare()
            p.playWhenReady = playWhenReady
            BindResult.Bound
        }.getOrElse { BindResult.Failed(it.message ?: "未知错误") }
    }

    /** 释放播放器（离开视频场景时调用）。 */
    fun release() {
        releaseInternal()
        currentKey = null
        currentPlayInfo = null
    }

    private fun releaseInternal() {
        player?.let { p ->
            runCatching {
                p.stop()
                p.release()
            }
        }
        player = null
    }

    /** 是否正在播放（PiP / 后台判断用）。 */
    val isPlaying: Boolean get() = player?.isPlaying == true

    /** 当前播放位置（毫秒）。已释放返回 0。 */
    val currentPosition: Long get() = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)

    /** 时长（毫秒）。 */
    val duration: Long get() = runCatching { player?.duration?.coerceAtLeast(0L) ?: 0L }.getOrDefault(0L)

    /** 装配结果。 */
    sealed interface BindResult {
        /** 重新装配成功。 */
        data object Bound : BindResult

        /** 流未变，复用现有缓冲（不打断播放）。 */
        data object Reused : BindResult

        /** 播放器尚未创建。 */
        data object NoPlayer : BindResult

        /** 播放器已释放（竞态）。 */
        data object Released : BindResult

        /** 无法构建播放源（取流地址为空）。 */
        data object NoSource : BindResult

        /** 装配抛异常。 */
        data class Failed(val message: String) : BindResult
    }
}
