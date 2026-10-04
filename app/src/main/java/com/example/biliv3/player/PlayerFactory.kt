package com.example.biliv3.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.biliv3.data.api.BiliHeaders
import com.example.biliv3.data.model.PlayInfo

/**
 * 播放器构建与取流装配。
 *
 * ## 为什么用 `ProgressiveMediaSource` + `MergingMediaSource`
 *
 * B 站 DASH 的 `baseUrl` **不是 `.mpd` 清单**，而是直接指向 fMP4 分片
 * （`AGENTS.md` §7 已实测确认）。所以：
 *
 * - ❌ 不能用 `DashMediaSource` —— 它要解析 `.mpd`，喂 fMP4 会失败
 * - ✅ 用 `ProgressiveMediaSource` 各包一条流，再用 `MergingMediaSource`
 *   把音视频合轨。这是 Media3 官方支持的用法，不需要 ffmpeg。
 *
 * ## ⚠️ 必须带 Referer
 *
 * 媒体请求不带 `Referer: https://www.bilibili.com` 会被 CDN **直接 403**。
 * 且这个错误的报错信息完全不指向 Referer，是播放黑屏最常见的原因
 * （`AGENTS.md` §7）。
 *
 * 注意：这里的 DataSource 是**媒体专用**的，与 API 请求的 OkHttp 客户端无关。
 */
object PlayerFactory {

    /**
     * 创建带 B 站请求头的媒体数据源。
     *
     * 只加 `Referer` / `User-Agent` / `Origin` 三个头 —— 与 `BiliHeaders.media()`
     * 保持同一份定义，避免两处各写一套后不同步。
     */
    fun mediaDataSource(): DataSource.Factory {
        val headers = BiliHeaders.media()
        return DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"])
            .setDefaultRequestProperties(headers)
            // 取流 URL 约 2h 过期；播放中途 403 时允许 Media3 重连一次
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
    }

    /**
     * 创建 ExoPlayer。
     *
     * `handleAudioBecomingNoisy = true`：拔耳机自动暂停。
     * 不加的话拔耳机后外放突然出声，是个很常见的体验事故。
     *
     * ## 为什么必须挂错误监听
     *
     * 播放失败时 ExoPlayer **默认只静默停在黑屏**，不打印任何东西。
     * 没有监听的话，「黑屏」这个现象完全无法定位是
     * 403 / 解码器不支持 / 网络超时 / 流格式错误 中的哪一种。
     *
     * 这里把错误打到 logcat（tag `BiliPlayer`），并回调给调用方用于 UI 展示。
     */
    fun createPlayer(
        context: Context,
        onError: ((PlaybackException) -> Unit)? = null,
    ): ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setHandleAudioBecomingNoisy(true)
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "播放失败: ${error.errorCodeName}", error)
                onError?.invoke(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "state=${stateName(playbackState)}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "isPlaying=$isPlaying")
            }
        })
    }

    private const val TAG = "BiliPlayer"

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    /**
     * 把 [PlayInfo] 装配成 MediaItem。
     *
     * 音视频分离时返回合并后的 MediaSource；只有单条流时走普通路径。
     *
     * @return `null` 表示没有可播放的流（调用方应显示错误态而不是静默黑屏）。
     */
    fun buildMediaSource(
        info: PlayInfo,
        dataSourceFactory: DataSource.Factory = mediaDataSource(),
        /**
         * **听视频模式**：true 时**不装配视频轨**。
         *
         * ## ⚠️ 为什么不是"把画面藏起来"
         *
         * 任务书 §6 明确要求「不是简单把视频画面透明掉」。区别是实打实的：
         *
         * | 做法 | 视频解码器 | GPU 合成 | 省电 |
         * |---|---|---|---|
         * | 画面透明 / 尺寸 0 | **仍在解码** | 仍在合成 | ❌ 几乎不省 |
         * | 不装配视频轨（本实现） | **完全不创建** | 不参与 | ✅ 真省 |
         *
         * ExoPlayer 只有在 MediaSource 里存在视频轨时才会创建视频解码器；
         * 只喂音频源，解码器与 `MediaCodec` 视频实例都不会出现。
         *
         * ## 代价与前提
         *
         * 切换模式**必须重建 MediaSource** → 会重新 prepare。
         * 所以调用方要在切换前后**保存并恢复播放位置**
         * （见 `PlayerHolder.bindMedia` 的 `resumePositionMs`）——
         * 这正是任务书要求「切换过程中播放位置必须保持」的实现点。
         */
        audioOnly: Boolean = false,
    ): androidx.media3.exoplayer.source.MediaSource? {
        if (info.videoUrl.isEmpty() && info.audioUrl.isEmpty()) return null

        // 音频源：两种模式都需要
        val audioSource = if (info.audioUrl.isNotEmpty()) {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(info.audioUrl)
                        .setMimeType(MimeTypes.AUDIO_MP4)
                        .build(),
                )
        } else {
            null
        }

        // ---- 听视频：只要有音轨就够 ----
        if (audioOnly) {
            // 音轨缺失时**退化为视频**（总比什么都不播强），
            // 但不能静默 —— 调用方通过 BindResult 判断实际装配了什么
            return audioSource ?: run {
                if (info.videoUrl.isEmpty()) return null
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(
                        MediaItem.Builder()
                            .setUri(info.videoUrl)
                            .setMimeType(MimeTypes.VIDEO_MP4)
                            .build(),
                    )
            }
        }

        // ---- 看视频：视频轨必需 ----
        if (info.videoUrl.isEmpty()) {
            // 只有音频（部分接口在特定清晰度下如此）—— 直接播音频，不报错
            return audioSource
        }

        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(info.videoUrl)
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build(),
            )

        // 无音轨（退化路径 / 音频流缺失）时只播视频，不合并。
        // MergingMediaSource 传 null 会抛异常，所以必须分支。
        if (audioSource == null) return videoSource

        return MergingMediaSource(videoSource, audioSource)
    }
}
