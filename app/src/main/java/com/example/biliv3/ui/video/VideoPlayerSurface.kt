package com.example.biliv3.ui.video

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.biliv3.data.model.PlayInfo
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.player.PlayerFactory

/**
 * 播放器视图。
 *
 * ## 生命周期
 *
 * ExoPlayer 由调用方用 `remember` 建、`DisposableEffect` 释放。
 * 漏掉释放会导致**每次进出详情页泄漏一个解码器**，连续看十几个视频
 * 必然 OOM（`AGENTS.md` §1 验收线「连续播放 2h 不崩、无泄漏」）。
 *
 * ## 为什么用 AndroidView 而不是 Media3 的 Compose 封装
 *
 * Media3 1.4.1 的 `media3-ui-compose` 仍是 alpha，API 不稳定；
 * `PlayerView` 是成熟方案，自带画面比例与字幕处理。
 * 控制条我们自己画（§4.3 要求自研 `PlayerControlBar`），
 * 所以这里 `useController = false` 关掉内置控制条，避免两套 UI 打架。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerSurface(
    info: PlayInfo,
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
    /**
     * 播放器持有者。传入时由它负责"是否需要重新装配"的判断。
     *
     * ⚠️ 这很关键：本组件会在 PiP 进出、页面重建时**重新进入组合**。
     * 若无条件 `setMediaSource + prepare`，每次重建都会把已缓冲的进度
     * 扔掉重来（表现为"进小窗后从头开始缓冲"）。
     * [PlayerHolder.bindMedia] 会比对 URL，同一条流时不重复装配。
     */
    holder: com.example.biliv3.player.PlayerHolder? = null,
) {
    // 取流变化（切分P / 切清晰度）时重新装配 MediaSource。
    // ⚠️ 绝不缓存 MediaSource —— 取流 URL 约 2h 过期。
    LaunchedEffect(info.videoUrl, info.audioUrl, info.currentQuality) {
        // 防御：播放器可能已被释放（如快速退出页面、或生命周期竞态）。
        // 对已释放的 player 调 setMediaSource 会抛 IllegalStateException
        // 并直接崩溃；这里转成可见的错误而不是崩。
        if (player.isReleased) return@LaunchedEffect

        if (holder != null) {
            // 走 holder：同一条流不重复装配（保住缓冲与播放进度）
            when (val r = holder.bindMedia(info, playWhenReady = true)) {
                is com.example.biliv3.player.PlayerHolder.BindResult.NoSource ->
                    onError("无法构建播放源（取流地址为空）")
                is com.example.biliv3.player.PlayerHolder.BindResult.Failed ->
                    onError("播放器初始化失败：${r.message}")
                // Bound / Reused / NoPlayer / Released 都是正常路径，无需报错
                else -> Unit
            }
            return@LaunchedEffect
        }

        val source = PlayerFactory.buildMediaSource(info)
        if (source == null) {
            onError("无法构建播放源（取流地址为空）")
            return@LaunchedEffect
        }

        runCatching {
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
        }.onFailure { e ->
            // 常见于：player 已释放 / 媒体源格式异常 / 解码器初始化失败
            onError("播放器初始化失败：${e.message ?: e::class.simpleName}")
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                // FIT：保持原始比例，不做裁切。竖屏视频也不会被拉变形。
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                this.player = player
            }
        },
        update = { it.player = player },
    )
}
