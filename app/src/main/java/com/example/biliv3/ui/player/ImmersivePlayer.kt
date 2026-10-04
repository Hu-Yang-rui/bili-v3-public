package com.example.biliv3.ui.player

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.biliv3.data.lyrics.LyricsUiState
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Motion
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.player.PlaybackMode
import com.example.biliv3.player.QueueItem
import kotlinx.coroutines.delay

/**
 * **沉浸式播放器** —— 听视频 / 黑胶 / 歌词的统一宿主。
 *
 * ## 🔴 本文件最重要的一条：控件**不能**用 `AnimatedVisibility` 控制可点性
 *
 * 项目历史上踩过这个坑（`AGENTS.md` §11.0.1）：
 *
 * > 「退出键时灵时不灵」的根因：`FloatingBackButton` 曾被
 * > `AnimatedVisibility(visible = chromeVisible)` 包着 —— 而
 * > `AnimatedVisibility(visible=false)` 会把子树**移出组合**（不只是透明）。
 * > 于是默认状态下返回键**根本不存在**，点左上角是点空气。
 *
 * 所以本文件的控件层：
 * - **始终在组合里**（没有 `if (visible)`、没有 `AnimatedVisibility`）
 * - 用 `Modifier.alpha()` 控制**视觉**淡出
 * - 用 `Modifier.pointerInput` 的 `enabled` 控制**是否吃掉点击**
 *
 * 这样"控件隐藏时点屏幕"能被正确识别为"唤出控件"，
 * 而不是"点到了看不见的按钮"或"点空气"。
 *
 * ## 自动隐藏
 *
 * 播放中 3 秒无操作 → 淡出。暂停时**不隐藏**（用户正在看控制）。
 */
@Composable
fun ImmersivePlayer(
    mode: PlaybackMode,
    item: QueueItem?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    lyricsState: LyricsUiState,
    /** 歌词是否展开（覆盖式面板）。 */
    lyricsExpanded: Boolean,
    onToggleLyrics: () -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onRetryLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    val context = LocalContext.current

    // 控件可见性：**只影响视觉与点击穿透**，不影响组合
    var chromeVisible by remember { mutableStateOf(true) }

    // 自动隐藏：播放中 3 秒无操作；暂停时不隐藏
    LaunchedEffect(chromeVisible, isPlaying, positionMs) {
        if (chromeVisible && isPlaying) {
            delay(3000L)
            chromeVisible = false
        }
    }

    // 沉浸式：隐藏系统栏。离开时恢复 —— 否则返回后状态栏一直不见
    LaunchedEffect(Unit) {
        val activity = context as? Activity
        @Suppress("DEPRECATION")
        activity?.window?.decorView?.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val activity = context as? Activity
            @Suppress("DEPRECATION")
            activity?.window?.decorView?.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    // 返回键：先收起歌词，再退出沉浸式
    BackHandler {
        if (lyricsExpanded) onToggleLyrics() else onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.playerBackground)
            // 点屏幕任意处：切换控件可见性
            .pointerInput(Unit) {
                detectTapGestures(onTap = { chromeVisible = !chromeVisible })
            },
    ) {
        // ---- 主体：三种模式 ----
        when (mode) {
            PlaybackMode.VIDEO -> VideoStage(
                item = item,
                positionMs = positionMs,
                durationMs = durationMs,
            )

            PlaybackMode.AUDIO -> AudioStage(
                item = item,
                positionMs = positionMs,
                durationMs = durationMs,
            )

            PlaybackMode.VINYL -> VinylPlayer(
                item = item,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
            )
        }

        // ---- 歌词覆盖层 ----
        // ⚠️ 这一层用 `if` 是**可以**的：它不是"可点性"控件，
        // 展开/收起本身就是显式用户动作，不存在"点空气"问题。
        if (lyricsExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.playerBackground.copy(alpha = 0.96f)),
            ) {
                LyricsView(
                    state = lyricsState,
                    positionMs = positionMs,
                    onRetry = onRetryLyrics,
                    onSeekTo = onSeek,
                    modifier = Modifier
                        .fillMaxSize()
                        // 上下留白避开顶栏与底部控件（间距令牌最大到 x12）
                        .padding(top = Space.x12, bottom = Space.x12),
                )
            }
        }

        // ---- 控件层：**始终在组合里**，只改 alpha 与是否吃点击 ----
        ChromeLayer(
            visible = chromeVisible,
            item = item,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            lyricsExpanded = lyricsExpanded,
            onBack = onBack,
            onTogglePlay = onTogglePlay,
            onNext = onNext,
            onPrevious = onPrevious,
            onSeek = onSeek,
            onOpenQueue = onOpenQueue,
            onToggleLyrics = onToggleLyrics,
        )
    }
}

/**
 * 控件层。
 *
 * ## ⚠️ 两个 Modifier 的分工（这是本文件的核心）
 *
 * - `Modifier.alpha(...)` —— 视觉淡出。**组件仍在组合里**
 * - `Modifier.pointerInput(enabled = visible)` —— 隐藏时**不吃点击**，
 *   让点击落到下层的"唤出控件"手势上
 *
 * 若用 `AnimatedVisibility(visible)` 包住，隐藏时子树**被移出组合**，
 * 上面的"唤出"手势就永远收不到点击（点在空气上）。
 */
@Composable
private fun ChromeLayer(
    visible: Boolean,
    item: QueueItem?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    lyricsExpanded: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onToggleLyrics: () -> Unit,
) {
    val colors = BiliTheme.colors
    val alpha = if (visible) 1f else 0f

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 顶栏：返回 + 标题 ----
        // ⚠️ 必须消费 statusBars：MainShell 的 contentWindowInsets 是 0，
        // inset 由各页自理。漏了它的表现是**图标画到状态栏底下、点不动**
        // （点击被系统状态栏吃掉）—— 与插件中心「导入」是同一个坑（§7.10-53）。
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .alpha(alpha)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.x2, vertical = Space.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ImmersiveIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                description = "退出沉浸式",
                enabled = visible,
                onClick = onBack,
            )
            Spacer(Modifier.width(Space.x2))
            Text(
                text = item?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ImmersiveIconButton(
                icon = Icons.Filled.Lyrics,
                description = "歌词",
                enabled = visible,
                tint = if (lyricsExpanded) colors.brandPrimary else colors.textSecondarySafe,
                onClick = onToggleLyrics,
            )
            ImmersiveIconButton(
                icon = Icons.Filled.QueueMusic,
                description = "播放队列",
                enabled = visible,
                onClick = onOpenQueue,
            )
        }

        // ---- 底部：进度 + 控制 ----
        // ⚠️ 同样要消费 navigationBars：否则控制按钮压在系统导航栏上，
        // 点"播放/暂停"会先触发手势导航（返回桌面）。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .alpha(alpha)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Space.x6, vertical = Space.x8),
        ) {
            ImmersiveProgressBar(
                positionMs = positionMs,
                durationMs = durationMs,
                enabled = visible,
                onSeek = onSeek,
            )

            Spacer(Modifier.height(Space.x6))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ImmersiveIconButton(
                    icon = Icons.Filled.SkipPrevious,
                    description = "上一首",
                    size = 32.dp,
                    enabled = visible,
                    onClick = onPrevious,
                )
                ImmersiveIconButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    description = if (isPlaying) "暂停" else "播放",
                    size = 48.dp,
                    enabled = visible,
                    onClick = onTogglePlay,
                )
                ImmersiveIconButton(
                    icon = Icons.Filled.SkipNext,
                    description = "下一首",
                    size = 32.dp,
                    enabled = visible,
                    onClick = onNext,
                )
            }
        }
    }
}

/**
 * 沉浸式图标按钮。
 *
 * ⚠️ `enabled = false` 时**依然在组合里**，只是不吃点击 ——
 * 这是与 `AnimatedVisibility` 的关键区别。
 */
@Composable
private fun ImmersiveIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 24.dp,
    tint: Color = Color.Unspecified,
) {
    val colors = BiliTheme.colors
    Box(
        modifier = Modifier
            .size(if (size > 24.dp) size + Space.x4 else Space.minTouchTarget)
            .clip(CircleShape)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures { onClick() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (tint == Color.Unspecified) colors.onOverlay else tint,
            modifier = Modifier.size(size),
        )
    }
}

/** 沉浸式进度条（可拖动 seek）。 */
@Composable
private fun ImmersiveProgressBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    val colors = BiliTheme.colors
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Space.minTouchTarget)
                .pointerInput(enabled, durationMs) {
                    if (enabled && durationMs > 0L) {
                        detectTapGestures { offset ->
                            val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeek((durationMs * ratio).toLong())
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // 轨道
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Space.trackHeight)
                    .background(colors.trackInactive),
            )
            // 已播段（品牌粉）
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(Space.trackHeight)
                    .background(colors.brandPrimary),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(positionMs),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = FontSize.monoReadout,
                    color = colors.textSecondarySafe,
                    fontFamily = com.example.biliv3.design.tokens.FontFamilies.mono,
                ),
            )
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = FontSize.monoReadout,
                    color = colors.textSecondarySafe,
                    fontFamily = com.example.biliv3.design.tokens.FontFamilies.mono,
                ),
            )
        }
    }
}

/** 听视频模式的主舞台：封面 + 提示"正在听音频"。 */
@Composable
private fun AudioStage(item: QueueItem?, positionMs: Long, durationMs: Long) {
    val colors = BiliTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.x8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .height(180.dp)
                .background(colors.coverPlaceholder),
            contentAlignment = Alignment.Center,
        ) {
            coil.compose.AsyncImage(
                model = item?.cover.orEmpty(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(Space.x8))
        Text(
            text = item?.title.orEmpty().ifEmpty { "暂无播放" },
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = FontSize.titleMd,
                color = colors.textPrimary,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!item?.author.isNullOrEmpty()) {
            Spacer(Modifier.height(Space.x2))
            Text(
                text = item.author,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.textSecondarySafe,
                ),
            )
        }
        Spacer(Modifier.height(Space.x5))
        // 明确告诉用户当前是"只听不看" —— 否则会以为画面坏了
        Text(
            text = "正在听音频（不消耗视频解码）",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = colors.textTertiary,
            ),
        )
    }
}

/** 视频模式占位（真正的画面由 `VideoPlayerSurface` 挂载到共享 TextureView）。 */
@Composable
private fun VideoStage(item: QueueItem?, positionMs: Long, durationMs: Long) {
    // 沉浸式视频模式的画面由外层的 VideoPlayerSurface 提供，
    // 这里只留一个深色底，避免"白屏"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}
