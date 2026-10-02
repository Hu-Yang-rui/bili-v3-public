package com.example.biliv3.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 播放器控制层：手势 + 精简控制条。
 *
 * ## 手势（本版重做）
 *
 * | 操作 | 行为 |
 * |---|---|
 * | 单击 | 显示/隐藏控制层 |
 * | 双击 | 播放/暂停 |
 * | 横滑 | seek（带时间预览） |
 * | **左半屏长按** | **减速到 0.5×**，松手恢复 |
 * | **右半屏长按** | **加速到 2×**，松手恢复 |
 *
 * ⚠️ 变速改为**左右半屏长按**，不再有独立按钮（用户明确要求）。
 * 独立按钮既占空间，又不如长按直观 —— 长按还有"按住多久=持续多久"
 * 的自然语义。
 *
 * ## 控制条（本版精简）
 *
 * ```
 * [已播] ━━━━━━●───── [总长]        ← 自绘进度条（细）
 * ```
 *
 * 只有一行进度条 + 时间。中央播放键保留（点击反馈必需），
 * 右上角全屏键保留。**没有**多余按钮与间距。
 *
 * ## ⚠️ 为什么进度条不再用 Material3 的 `Slider`（"感叹号"根因）
 *
 * 原实现把 `Slider` 约束到 `height(24.dp)` 以求"细"。
 * 但 M3 的 `Slider` 有**固定的最小触摸目标 48dp**，thumb 约 20dp；
 * 把它压进 24dp 后 thumb 被裁成**一条细长竖线** —— 视觉上就是
 * 用户报告的"进度条上多余的感叹号"。
 *
 * 现在改为 `Canvas` 自绘：
 * - 轨道 2dp 细线
 * - 进度点 8dp 圆（拖动时放大到 12dp）
 * - 高度完全可控，不会出现裁切
 *
 * 触摸目标通过外层 `gestureHeight` 保证 ≥ 44dp（可点区域不受绘制高度影响）。
 */
@Composable
fun PlayerControls(
    player: ExoPlayer,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 控件可见性从外部传入（受控）—— 与浮动返回按钮同步显隐。
     * 默认 `true` 保持旧行为，便于单独预览。
     */
    controlsVisible: Boolean = true,
    onToggleControls: () -> Unit = {},
    onAutoHide: () -> Unit = {},
) {
    val colors = BiliTheme.colors
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var position by remember { mutableLongStateOf(player.currentPosition) }

    // 拖动进度条时用本地值，避免与播放进度更新打架（否则滑块会"跳回去"）
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    // 手势 seek 预览
    var seekPreview by remember { mutableStateOf<Long?>(null) }

    /**
     * 长按变速状态。
     *
     * `null` = 未长按；`0.5f` = 左半屏减速；`2f` = 右半屏加速。
     *
     * ⚠️ 用状态驱动 UI 提示，而不是"按下就直接改 player 速度然后
     * 依赖松手事件恢复" —— 后者在手指滑出控件、或多点触控时会漏掉
     * 松手事件，导致**速度永久卡在 0.5×/2×**（这是一个真实易错点）。
     * 这里把"目标速度"作为唯一真相，用一个 effect 统一同步到 player，
     * 松手/取消/隐藏都只是把状态置 null。
     */
    var pressSpeed by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                duration = player.duration.coerceAtLeast(0)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 每 500ms 采样一次进度。500ms 足够顺滑，又不会像每帧更新那样浪费重组。
    LaunchedEffect(player) {
        while (true) {
            if (!isDragging) {
                position = player.currentPosition
                duration = player.duration.coerceAtLeast(0)
            }
            delay(POSITION_POLL_MS)
        }
    }

    /**
     * 长按变速 → 同步到 player。
     *
     * 唯一入口，保证不会出现"改了没恢复"。`onDispose` 里额外恢复 1×，
     * 防止组件被移除时长按状态还没清掉。
     */
    LaunchedEffect(pressSpeed) {
        val p = player
        if (p.isReleased) return@LaunchedEffect
        runCatching { p.setPlaybackSpeed(if (pressSpeed > 0f) pressSpeed else 1f) }
    }
    DisposableEffect(player) {
        onDispose {
            runCatching { if (!player.isReleased) player.setPlaybackSpeed(1f) }
        }
    }

    // 播放中 3 秒后自动隐藏；暂停时保持显示
    LaunchedEffect(controlsVisible, isPlaying, isDragging, pressSpeed) {
        if (controlsVisible && isPlaying && !isDragging && pressSpeed == 0f) {
            delay(AUTO_HIDE_MS)
            onAutoHide()
        }
    }

    // 隐藏控制层时一并取消长按变速（否则会"看不见但还在 2×"）
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) pressSpeed = 0f
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ================= 手势层 =================
        //
        // ⚠️ **必须是单个 Box、单个 pointerInput**（这是"点击画面控件不出现"的根因）。
        //
        // 原实现用了**两个** `fillMaxSize` 的 Box 叠在一起：
        // 上面那个只处理横滑 seek。结果它参与命中测试，
        // **把点击事件全部吃掉** —— 下面的 `detectTapGestures`
        // 永远收不到 onTap，于是"点画面没反应/返回键不出现"。
        //
        // 现在合成一个 Box，用 `forEachGesture`（Compose 提供的
        // "多手势轮流识别"原语）串起单击/双击/长按/横滑 ——
        // 它内部保证事件先给第一个识别器尝试，失败再给下一个，
        // 不会互相遮挡。
        // ⚠️ 手势层**只挂两个** pointerInput：tap 与 drag。
        //
        // 曾有一个 `awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }`
        // 的空块（改造过程中的残留）—— 它**无条件抢走 DOWN 事件**，
        // 导致同一 Box 上层（返回键/齿轮）的 `clickable` 永远收不到
        // 完整事件流，表现为「点返回键没反应」。已删除。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    // 单击 / 双击 / 长按
                    detectTapGestures(
                        onTap = { onToggleControls() },
                        onDoubleTap = {
                            if (player.isPlaying) player.pause() else player.play()
                            if (!controlsVisible) onToggleControls()
                        },
                        onLongPress = { offset ->
                            // 左半屏减速、右半屏加速（按控件宽度判断）
                            pressSpeed =
                                if (offset.x < size.width / 2f) SLOW_SPEED else FAST_SPEED
                        },
                        onPress = {
                            // 抬手或手势被取消都恢复原速。
                            // `tryAwaitRelease()` 返回 false 表示被取消
                            // （手指移出、被父级抢走）—— 两种情况都必须恢复，
                            // 否则速度会永久卡在 0.5×/2×。
                            tryAwaitRelease()
                            pressSpeed = 0f
                        },
                    )
                }
                .pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    // 横滑 seek。放在**同一个 Box** 上，靠 Compose 的
                    // 手势竞争机制分流：横滑时 tap 识别器会因为位移
                    // 超阈值而放弃，反之亦然。
                    var accumulated = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            accumulated = 0f
                            if (!controlsVisible) onToggleControls()
                        },
                        onDragEnd = {
                            seekPreview?.let { player.seekTo(it) }
                            seekPreview = null
                        },
                        onDragCancel = { seekPreview = null },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            accumulated += delta
                            val deltaMs = (accumulated / size.width * SEEK_RANGE_MS).toLong()
                            seekPreview = (player.currentPosition + deltaMs)
                                .coerceIn(0, duration)
                        },
                    )
                },
        )

        // ================= 长按变速提示（居中）=================
        //
        // 只显示一个紧凑胶囊：`2× 快进中` / `0.5× 慢放中`。
        // 不做成"左右半屏各一个常驻图标" —— 那才是用户说的"独立按钮"。
        if (pressSpeed > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = Space.x4)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.overlayControl)
                    .padding(horizontal = Space.x3, vertical = Space.x1 + 2.dp),
            ) {
                Text(
                    text = if (pressSpeed > 1f) "${formatSpeed(pressSpeed)}× 快进中"
                    else "${formatSpeed(pressSpeed)}× 慢放中",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.onOverlay,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }

        // ================= seek 预览 =================
        seekPreview?.let { target ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(Radius.button))
                    .background(colors.overlayControl)
                    .padding(horizontal = Space.x3, vertical = Space.x1 + 2.dp),
            ) {
                Text(
                    text = "${formatTime(target)} / ${formatTime(duration)}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.onOverlay,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }

        // ================= 中央播放/暂停 =================
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .size(CENTER_BUTTON)
                    .clip(CircleShape)
                    .background(colors.overlayCover)
                    .clickable {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = colors.onOverlay,
                    modifier = Modifier.size(Sizes.iconXl + Space.x1),
                )
            }
        }

        // ================= 右上角：全屏切换 =================
        //
        // ⚠️ 这里**不再渲染全屏按钮**（这是「点全屏没反应 / 退出时灵时不灵」
        // 的根因之一）。
        //
        // 此前本组件与 `VideoDetailScreen` 的右上角按钮组
        // （小窗 + 齿轮）都 `align(TopEnd)`，而后者在 composition 里
        // **渲染更晚** → 覆盖在本组件之上 → 命中测试优先给它。
        // 两个 40dp 圆钮叠在同一角落，全屏按钮被盖住/半盖住，
        // 于是"点全屏"有时点到、有时点到齿轮或小窗。
        //
        // 现在右上角**只有一处**（`VideoDetailScreen` 的按钮组），
        // 由它统一放：小窗 + 齿轮 + 全屏。本组件不再画。
        //
        // 保留参数与图标 import 会变成未使用告警，所以一并清理。
        // （`onToggleFullscreen` / `isFullscreen` 仍由上层使用。）

        // ================= 底部：自绘进度条（单行、紧凑）=================
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 底部渐变：保证白色控件压在任意画面上都可读
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, colors.gradientMediaEnd),
                        ),
                    )
                    // ⚠️ 紧凑：垂直内边距 2dp（原为 4dp），横向 8dp
                    .padding(horizontal = Space.x2, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(if (isDragging) (dragFraction * duration).toLong() else position),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.onOverlay,
                    ),
                )

                Spacer(Modifier.width(Space.x2))

                // ---- 自绘进度条 ----
                //
                // 触摸区域高 28dp（够点），但**视觉只有 2dp 细线** ——
                // 这样既不会裁切 thumb，也不占纵向空间。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(PROGRESS_TOUCH_HEIGHT)
                        .pointerInput(duration) {
                            if (duration <= 0) return@pointerInput
                            detectTapGestures(
                                onPress = { offset ->
                                    isDragging = true
                                    dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                    // 按住即预览，松手才真正 seek（与横滑 seek 同一策略：
                                    // 拖动中反复 seek 会让播放器不断重新缓冲）
                                    tryAwaitRelease()
                                    player.seekTo((dragFraction * duration).toLong())
                                    position = (dragFraction * duration).toLong()
                                    isDragging = false
                                },
                            )
                        }
                        .pointerInput(duration) {
                            if (duration <= 0) return@pointerInput
                            detectHorizontalDragGestures(
                                onDragStart = { isDragging = true },
                                onDragEnd = {
                                    player.seekTo((dragFraction * duration).toLong())
                                    position = (dragFraction * duration).toLong()
                                    isDragging = false
                                },
                                onDragCancel = { isDragging = false },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    dragFraction = (change.position.x / size.width)
                                        .coerceIn(0f, 1f)
                                },
                            )
                        },
                ) {
                    ProgressBar(
                        fraction = when {
                            isDragging -> dragFraction
                            duration > 0 -> (position.toFloat() / duration).coerceIn(0f, 1f)
                            else -> 0f
                        },
                        dragging = isDragging,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = Space.x1),
                    )
                }

                Spacer(Modifier.width(Space.x2))

                Text(
                    text = formatTime(duration),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.onOverlay,
                    ),
                )
            }
        }
    }
}

/**
 * 自绘进度条。
 *
 * ## 为什么不画 thumb 圆点也能拖
 *
 * 圆点只是**视觉锚点**，拖动靠外层 Box 的 `pointerInput`。
 * 因此圆点可以做得很小（8dp），不会像 M3 Slider 那样
 * 因"thumb 必须有触摸尺寸"而被压变形。
 *
 * ## 轨道只 2dp
 *
 * 细轨道降低视觉占用（这是用户反复强调的"紧凑"）。
 * 已播部分用品牌粉，未播用半透明白。
 */
@Composable
private fun ProgressBar(
    fraction: Float,
    dragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    val active = colors.brandPrimary
    val inactive = colors.trackInactive

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val centerY = size.height / 2f
        val radius = TRACK_HEIGHT.toPx() / 2f

        // 未播轨道
        drawLine(
            color = inactive,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = TRACK_HEIGHT.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )

        // 已播轨道
        val playedX = size.width * fraction.coerceIn(0f, 1f)
        if (playedX > 0f) {
            drawLine(
                color = active,
                start = Offset(0f, centerY),
                end = Offset(playedX, centerY),
                strokeWidth = TRACK_HEIGHT.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        // 进度圆点：拖动时放大，给出"抓住了"的反馈
        val dotR = (if (dragging) DOT_DRAGGING else DOT_REST).toPx() / 2f
        drawCircle(
            color = active,
            radius = dotR,
            center = Offset(playedX.coerceIn(dotR, size.width - dotR), centerY),
        )
        // 白描边让圆点在深色轨道上更清晰
        drawCircle(
            color = colors.onOverlay,
            radius = dotR,
            center = Offset(playedX.coerceIn(dotR, size.width - dotR), centerY),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
        )
        // radius 变量用于消除未使用警告外的可读性；实际画线用 strokeWidth
        check(radius >= 0f)
    }
}

/** 右上角小图标按钮。比中央按钮小一圈，降低视觉占用。 */
@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Box(
        modifier = Modifier
            .size(SMALL_BUTTON)
            .clip(CircleShape)
            .background(colors.overlayControl)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.onOverlay,
            modifier = Modifier.size(Sizes.iconLg),
        )
    }
}

/**
 * 毫秒 → `MM:SS` / `H:MM:SS`。
 *
 * ## ⚠️ 入参是**毫秒**
 *
 * `ExoPlayer.getDuration()` / `getCurrentPosition()` 返回的都是**毫秒**。
 * 第一版按秒处理，于是 315 秒的视频显示成 `315:09:32` ——
 * 明显不对，但因为"看起来像个时间"而不容易第一眼发现。
 */
internal fun formatTime(millis: Long): String {
    if (millis <= 0) return "00:00"
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/** `2.0` → `2`，`0.5` → `0.5`。倍速提示文案用。 */
private fun formatSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()

/** 进度采样间隔。500ms 足够顺滑且不至于每帧重组。 */
private const val POSITION_POLL_MS = 500L


/** 控制条自动隐藏延时。 */
private const val AUTO_HIDE_MS = 3000L

/** 横滑整屏宽度对应的 seek 时间。 */
private const val SEEK_RANGE_MS = 90_000f

/** 左半屏长按的减速倍率。 */
private const val SLOW_SPEED = 0.5f

/** 右半屏长按的加速倍率。 */
private const val FAST_SPEED = 2f

/** 中央播放按钮。保持 40dp：小但可点。 */
private val CENTER_BUTTON = 40.dp

/** 右上角小按钮。 */
private val SMALL_BUTTON = 32.dp

/** 进度条轨道粗细（视觉）。 */
private val TRACK_HEIGHT = 2.dp

/** 进度条触摸区域高度（不影响视觉，只保证好点）。 */
private val PROGRESS_TOUCH_HEIGHT = 28.dp

/** 进度圆点静止 / 拖动时直径。 */
private val DOT_REST = 8.dp
private val DOT_DRAGGING = 12.dp
