package com.example.biliv3.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * **B 站 DASH 的 `MediaSource.Factory`** —— 让 `MediaSessionService` 能播 B 站流。
 *
 * ## 🔴 为什么必须有这个类（本轮的架构难点）
 *
 * 任务书要求「UI 用 `MediaController`、Service 用 `MediaSessionService`」。
 * 但这里有个**真实冲突**：
 *
 * | | 能力 |
 * |---|---|
 * | `MediaController`（跨进程代理） | **只能传 `MediaItem`**（URI + 元数据） |
 * | B 站 DASH | 音视频是**两条独立 URL**，需要 `MergingMediaSource` 合并 |
 *
 * 也就是说：**UI 无法把 `MergingMediaSource` 交给 Service** ——
 * `MediaSource` 不是可跨进程传递的类型。
 *
 * ## 解法：把"两条 URL"编码进 `MediaItem`，由 Service 侧还原
 *
 * `MediaItem.Builder().setTag(...)` 可以携带任意 `Bundle`，
 * 且**能跨进程传递**。于是：
 *
 * ```
 * UI 侧：MediaItem(uri = 视频URL, tag = Bundle{audioUrl = 音频URL})
 *            ↓ MediaController（跨进程）
 * Service 侧：本类读 tag → 组装 MergingMediaSource
 * ```
 *
 * ## ⚠️ 为什么不用 `setMediaSource` 直传
 *
 * `Player.setMediaSource()` 只存在于 `ExoPlayer` 接口上，
 * 而 Service 侧通过 `MediaSession` 接收的是 `MediaItem` ——
 * 走的是 `setMediaItem()` 路径。所以**必须**在 Service 侧有工厂来还原。
 *
 * 这也是为什么本项目**不能**直接把 `PlayerHolder` 换成 `MediaController` 就完事：
 * 详情页仍在用 `MergingMediaSource`（见 `PlayerFactory`）。
 * 两条路径**共用同一个** [PlayerFactory.mediaDataSource]，避免请求头不一致。
 */
@UnstableApi
class BiliMediaSourceFactory(
    context: Context,
) : MediaSource.Factory {

    private val dataSourceFactory: DataSource.Factory = PlayerFactory.mediaDataSource()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val videoUrl = mediaItem.localConfiguration?.uri?.toString().orEmpty()
        val audioUrl = mediaItem.mediaMetadata.extras
            ?.getString(EXTRA_AUDIO_URL)
            .orEmpty()
        // 听视频模式：UI 侧标记后，Service 侧只装配音轨
        val audioOnly = mediaItem.mediaMetadata.extras
            ?.getBoolean(EXTRA_AUDIO_ONLY, false) == true

        val videoSource = if (videoUrl.isNotEmpty()) {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(videoUrl)
                        .setMimeType(MimeTypes.VIDEO_MP4)
                        .build(),
                )
        } else {
            null
        }

        val audioSource = if (audioUrl.isNotEmpty()) {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(audioUrl)
                        .setMimeType(MimeTypes.AUDIO_MP4)
                        .build(),
                )
        } else {
            null
        }

        // 听视频：只要有音轨就够（不创建视频解码器）
        if (audioOnly && audioSource != null) return audioSource

        return when {
            videoSource != null && audioSource != null ->
                MergingMediaSource(videoSource, audioSource)

            videoSource != null -> videoSource
            audioSource != null -> audioSource
            // 两条 URL 都空：交给渐进式源去报错（会走 onPlayerError，
            // 而不是在这里抛异常 —— 抛异常会让整个 Service 崩）
            else -> ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(
        androidx.media3.common.C.CONTENT_TYPE_OTHER,
    )

    /**
     * 不需要 DRM（B 站普通视频无 DRM；大会员内容本项目不解析，见 §4.3）。
     *
     * `MediaSource.Factory` 要求实现这个方法，返回 null 表示"不提供"。
     */
    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory = this

    /** 不需要自定义错误处理策略，用 Media3 默认值。 */
    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = this

    companion object {
        /** `MediaItem` extras 里音频轨 URL 的键。 */
        const val EXTRA_AUDIO_URL = "biliv3.audioUrl"

        /** `MediaItem` extras 里"只听音频"标记的键。 */
        const val EXTRA_AUDIO_ONLY = "biliv3.audioOnly"

        /** 构造带音频轨信息的 `MediaItem`（UI 侧用）。 */
        fun buildMediaItem(
            videoUrl: String,
            audioUrl: String,
            title: String,
            artist: String,
            artworkUrl: String,
            audioOnly: Boolean,
        ): MediaItem {
            val extras = android.os.Bundle().apply {
                putString(EXTRA_AUDIO_URL, audioUrl)
                putBoolean(EXTRA_AUDIO_ONLY, audioOnly)
            }
            return MediaItem.Builder()
                .setUri(videoUrl)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setArtworkUri(artworkUrl.takeIf { it.isNotEmpty() }?.toUri())
                        .setExtras(extras)
                        .build(),
                )
                .build()
        }

        private fun String.toUri(): android.net.Uri = android.net.Uri.parse(this)
    }
}
