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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    /**
     * 播放模式（v1.3.0）。
     *
     * ⚠️ 由 `PlaybackController` 提供，**不是**本组件自己维护的状态 ——
     * 听视频/黑胶是应用级模式（切页、PiP 后仍要保持），
     * 组件内部 `remember` 会在重建时丢失。
     *
     * 非 `VIDEO` 时 `bindMedia(audioOnly = true)`：
     * **不装配视频轨**，从而不创建视频解码器（真省电，见 §7.10-59）。
     */
    mode: com.example.biliv3.player.PlaybackMode = com.example.biliv3.player.PlaybackMode.VIDEO,
) {
    // 当前 Surface 绑定（随组合存活；onRelease 里释放）
    var surfaceBinding by remember { mutableStateOf<TextureSurfaceBinder?>(null) }

    val audioOnly = mode != com.example.biliv3.player.PlaybackMode.VIDEO

    // 取流变化（切分P / 切清晰度）**或播放模式变化**时重新装配 MediaSource。
    // ⚠️ 绝不缓存 MediaSource —— 取流 URL 约 2h 过期。
    //
    // ⚠️ `audioOnly` 必须在 key 里：看/听切换时视频轨的有无不同，
    // 必须重建 —— 否则"点了听视频但画面还在解码"。
    // 重建会重置位置，所以下面把当前位置传进 `bindMedia` 恢复。
    LaunchedEffect(info.videoUrl, info.audioUrl, info.currentQuality, audioOnly) {
        // 防御：播放器可能已被释放（如快速退出页面、或生命周期竞态）。
        // 对已释放的 player 调 setMediaSource 会抛 IllegalStateException
        // 并直接崩溃；这里转成可见的错误而不是崩。
        if (player.isReleased) return@LaunchedEffect

        if (holder != null) {
            // 切换模式时保住播放位置：重建 MediaSource 会把位置重置到 0，
            // 所以先读当前位置、装配后 seek 回去。
            val resumeAt = if (audioOnly) player.currentPosition.coerceAtLeast(0L) else 0L

            // 走 holder：同一条流不重复装配（保住缓冲与播放进度）
            when (
                val r = holder.bindMedia(
                    info = info,
                    playWhenReady = true,
                    audioOnly = audioOnly,
                    resumePositionMs = resumeAt,
                )
            ) {
                is com.example.biliv3.player.PlayerHolder.BindResult.NoSource ->
                    onError("无法构建播放源（取流地址为空）")
                is com.example.biliv3.player.PlayerHolder.BindResult.Failed ->
                    onError("播放器初始化失败：${r.message}")
                // Bound / Reused / NoPlayer / Released 都是正常路径，无需报错
                else -> Unit
            }
            return@LaunchedEffect
        }

        val source = PlayerFactory.buildMediaSource(info, audioOnly = audioOnly)
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
        // ⚠️ 不再自己 new PlayerView，而是挂**共享的 TextureView**。
        //
        // 两个原因，缺一不可：
        //
        // 1. **毛玻璃要抓帧**（design/Glass.kt）
        //    `SurfaceView` 的内容在独立合成层，`getBitmap()` 只能拿到黑图。
        //    必须用 `TextureView` 才能拿到真实画面。
        //
        // 2. **切换页面不能黑一帧**
        //    若每个页面各自 new 一个 View，切页时旧 View 销毁、
        //    新 View 创建 → Surface 重建 → 必然黑一帧。
        //    共享同一个 View（从旧父级摘下、挂到新父级）就没有这个问题。
        //
        // 画面比例由外层 Box 的 `aspectRatio` 控制（TextureView 默认拉伸填满）。
        factory = { ctx ->
            android.widget.FrameLayout(ctx).also { container ->
                val tv = holder?.attachTextureViewTo(container)
                    ?: run {
                        // 无 holder（预览/测试）时退化为自建一个，保持组件可用
                        android.view.TextureView(ctx).also {
                            container.addView(
                                it,
                                android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    }
                // TextureView 不能直接挂 ExoPlayer —— 需要 Surface 中转
                surfaceBinding = TextureSurfaceBinder(player, tv)
            }
        },
        update = {
            // 重新进入组合（PiP 进出 / 页面重建）时确保仍绑在当前 player 上
            surfaceBinding?.rebind(player)
        },
        onRelease = {
            surfaceBinding?.dispose()
            surfaceBinding = null
        },
    )
}

/**
 * 把 `ExoPlayer` 的视频输出接到 `TextureView`。
 *
 * ## 为什么需要这一层
 *
 * `PlayerView` 内置了这个逻辑，但我们不能用 `PlayerView` ——
 * 它默认用 `SurfaceView`，抓不到帧。
 *
 * `TextureView` 需要一个 `Surface` 才能真正显示画面，
 * 而这个 `Surface` 在 View 尺寸变化、重新挂载时会**失效重建**，
 * 所以必须监听 `SurfaceTextureListener` 并在每次可用时重新 `setSurface`。
 *
 * ## ⚠️ 顺序陷阱
 *
 * `setSurface` 必须在 **player 已 attach 到主线程** 之后。
 * `ExoPlayer` 要求所有调用在同一线程（这里是主线程，Compose 也是主线程，OK）。
 */
@OptIn(UnstableApi::class)
private class TextureSurfaceBinder(
    private var player: ExoPlayer,
    private val view: android.view.TextureView,
) : android.view.TextureView.SurfaceTextureListener {

    private var surface: android.view.Surface? = null

    init {
        // 若 Surface 已就绪（复用 View 时常见），立即绑定
        val st = if (view.isAvailable) view.surfaceTexture else null
        if (st != null) {
            onSurfaceTextureAvailable(
                st,
                view.width.coerceAtLeast(1),
                view.height.coerceAtLeast(1),
            )
        } else {
            view.surfaceTextureListener = this
        }
    }

    override fun onSurfaceTextureAvailable(
        st: android.graphics.SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        view.surfaceTextureListener = this
        surface?.release()
        val s = android.view.Surface(st)
        surface = s
        runCatching { player.setVideoSurface(s) }
    }

    override fun onSurfaceTextureSizeChanged(
        st: android.graphics.SurfaceTexture,
        width: Int,
        height: Int,
    ) = Unit

    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
        runCatching { player.setVideoSurface(null) }
        surface?.release()
        surface = null
        // 返回 true 表示"我自己释放"，让 TextureView 重建时重新回调 available
        return true
    }

    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit

    /** 重新绑定到（可能已变化的）player 实例。 */
    fun rebind(newPlayer: ExoPlayer) {
        player = newPlayer
        surface?.let { runCatching { player.setVideoSurface(it) } }
    }

    fun dispose() {
        runCatching { player.setVideoSurface(null) }
        surface?.release()
        surface = null
        view.surfaceTextureListener = null
    }
}
