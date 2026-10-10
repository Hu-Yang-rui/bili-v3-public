package com.example.biliv3.ui.video

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import androidx.compose.runtime.mutableIntStateOf
import coil.transform.Transformation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.biliv3.data.SkipBarGeometry
import com.example.biliv3.data.SkipSegment
import com.example.biliv3.data.Videoshot
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.biliv3.ui.component.MonoReadout
import com.example.biliv3.design.tokens.Motion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.example.biliv3.design.BiliTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type
import com.example.biliv3.design.v3.GlassSurface
import com.example.biliv3.design.v3.V3Glass

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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    /**
     * 进度条拖动状态变化。`true` = 用户正在拖动。
     *
     * ⚠️ 空降助手需要它：拖动中**不能**自动跳片段，
     * 否则会和用户的 seek 打架（表现为"拖不动 / 位置乱跳"）。
     */
    onSeekingChanged: (Boolean) -> Unit = {},
    /**
     * 进度条拖动预览用的精灵图（v1.5.3）。
     *
     * `null` = 该视频**没有**预览资源 → 拖动时只显示时间文字（优雅降级）。
     * 实测并非所有视频都有（未生成预览的视频返回空 `image`）。
     */
    videoshot: Videoshot? = null,
    /**
     * 播放位置变化回调（**整秒**，秒）。v1.5.3 新增。
     *
     * ## 为什么从这里上报，而不是外部自己轮询
     *
     * `PlayerControls` 本来就在轮询 `player.currentPosition` 刷新时间轴 ——
     * 复用它已有的循环**零额外成本**。若外部再起一条协程读同一个
     * `player`，就是重复轮询同一个值。
     *
     * ⚠️ 只在**整秒变化**时回调：章节高亮的精度需求是秒级，
     * 每 200ms 回调一次会让详情页（子组件很多）无谓重组。
     */
    onPositionTick: (Int) -> Unit = {},
    /**
     * 空降助手的可跳过片段（v1.6.3）。
     *
     * ## 为什么画在进度条上，而不是只留提示条
     *
     * `SkippedBanner` 只在**跳过发生的那一瞬间**出现（5 秒后自动消失）。
     * 用户拖动进度条时看不到"哪些区域会被跳过" —— 拖过去才发现
     * 播放位置被自动拽走，主观上像"进度条不听话"。
     *
     * 画在轨道上后，**拖动前就能看见**整段广告/片头在哪。
     *
     * ⚠️ 传入的是**已按类别过滤并合并**的片段（见 `SponsorBlockRepository`），
     * 这里只负责画，不再做任何过滤/合并 —— 否则会出现
     * "设置里关了 intro，进度条上却还有 intro 色块"的不一致。
     *
     * 空列表 = 该视频没有片段（常态），此时**一个像素都不画**。
     */
    skipSegments: List<SkipSegment> = emptyList(),
) {
    val colors = BiliV3.colors
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var position by remember { mutableLongStateOf(player.currentPosition) }

    // 拖动进度条时用本地值，避免与播放进度更新打架（否则滑块会"跳回去"）
    var isDragging by remember { mutableStateOf(false) }
    // 上次上报过的整秒位置（v1.5.3）—— 避免同一秒重复回调
    var lastReportedSecond by remember { mutableIntStateOf(-1) }
    // 单一入口：赋值的同时上报，避免多处赋值漏上报（空降助手依赖它）
    val setDragging: (Boolean) -> Unit = { v ->
        if (isDragging != v) {
            isDragging = v
            onSeekingChanged(v)
        }
    }
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

    /**
     * 是否正在缓冲（`STATE_BUFFERING`）。
     *
     * ## 🔴 为什么必须补这个（这是一个真实的功能缺口）
     *
     * 排查时发现：**全项目此前没有任何缓冲指示** ——
     * 没有任何地方读 `STATE_BUFFERING`。
     *
     * 后果：网络慢时画面静止、进度条不走，用户**无法区分**
     * 「正在加载」和「卡死了 / 播放器崩了」。
     * 这是最影响体感的一类缺失 —— 它不报错，只是让人以为坏了。
     *
     * ## 为什么不用 `CircularProgressIndicator`
     *
     * 播放器上的指示器有两个额外约束：
     * 1. **必须在纯黑背景上也看得见** → 用 `onOverlay`（白）
     * 2. **不能有跳动感** → Material 的转圈默认尺寸偏大且有色带，
     *    这里用细线 + 小尺寸，与"控制层克制"一致
     *
     * ## 与"中央播放按钮"的关系
     *
     * 缓冲时**不显示**中央播放/暂停按钮（那会让人以为可以点），
     * 只显示缓冲环。两者互斥，由 [buffering] 决定。
     */
    var buffering by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                duration = player.duration.coerceAtLeast(0)
                // 缓冲状态必须单独跟踪（见下方 buffering 的 KDoc）
                buffering = state == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)
        // 首帧就可能已经处于缓冲态（进页面立刻卡住），补一次同步
        buffering = player.playbackState == Player.STATE_BUFFERING
        onDispose { player.removeListener(listener) }
    }

    // 每 500ms 采样一次进度。500ms 足够顺滑，又不会像每帧更新那样浪费重组。
    LaunchedEffect(player) {
        while (true) {
            if (!isDragging) {
                position = player.currentPosition
                duration = player.duration.coerceAtLeast(0)

                // 上报**整秒**位置（章节高亮用，v1.5.3）。
                // 只在秒数真的变了才回调 —— 轮询是 200ms 一次，
                // 每次都回调会让详情页无谓重组 5 倍次数。
                val sec = (position / 1000L).toInt()
                if (sec != lastReportedSecond) {
                    lastReportedSecond = sec
                    onPositionTick(sec)
                }
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
        //
        // ⚠️ 读数用**等宽**：`0.5×` / `2×` 宽度不同，比例字体下
        // 胶囊宽度会随速度变化而跳动（同一屏内）。
        if (pressSpeed > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = V3Space.md),
            ) {
                // 🔴 v3：临时读数胶囊 = **扁平半透明**（不是模糊玻璃）。
                //
                // 旧版 `background(colors.controlOverlay)` 是 **70% 不透明黑** ——
                // 太重，一颗黑饼压在画面上。
                //
                // ## 为什么不用模糊玻璃（实测两轮后放弃）
                //
                // 播放器控件是**标点**，不是内容。给它加 6dp 模糊 =
                // 在已经很花的画面上再铺一层"有纹理的玻璃"。
                // 而且读数胶囊是**临时浮出**的，用户只想扫一眼数字，
                // 不需要"材质感"。
                //
                // 扁平方案：均匀压暗 35%，零 GPU 成本，读数始终清晰。
                // 详见 `V3Materials.controlFlat` 的判据。
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(V3Radius.pill))
                        .background(colors.materials.controlFlat)
                        .border(
                            1.dp,
                            colors.materials.controlFlatBorder,
                            RoundedCornerShape(V3Radius.pill),
                        )
                        .padding(
                            horizontal = V3Space.sm,
                            vertical = V3Space.xxs + V3Space.hairline,
                        ),
                ) {
                    MonoReadout(
                        text = if (pressSpeed > 1f) "${formatSpeed(pressSpeed)}× 快进中"
                        else "${formatSpeed(pressSpeed)}× 慢放中",
                        color = colors.labelOnMedia,
                        fontSize = V3Type.caption1.fontSize,
                        weight = FontWeight.Medium,
                    )
                }
            }
        }

        // ================= seek 预览 =================
        //
        // ⚠️ 用等宽（与底部时间轴同族）。
        // 拖动时读数每帧都在变，比例字体下**整个胶囊会左右抖**
        // —— 这是"拖动进度条时中间那块东西在晃"的来源。
        seekPreview?.let { target ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    // seek 预览是**读数浮层**，不是交互元素 ——
                    // 用 pill 才读得出"这是个临时读数胶囊"（与底部时间轴同族）。
                    //
                    // 🔴 v3：同变速胶囊，用**扁平半透明**（不是模糊玻璃）。
                    .clip(RoundedCornerShape(V3Radius.pill))
                    .background(colors.materials.controlFlat)
                    .border(
                        1.dp,
                        colors.materials.controlFlatBorder,
                        RoundedCornerShape(V3Radius.pill),
                    )
                    .padding(
                        horizontal = V3Space.sm,
                        vertical = V3Space.xxs + V3Space.hairline,
                    ),
            ) {
                MonoReadout(
                    text = "${formatTime(target)} / ${formatTime(duration)}",
                    color = colors.labelOnMedia,
                    fontSize = V3Type.caption1.fontSize,
                    weight = FontWeight.Medium,
                )
            }
        }

        // ================= 中央：缓冲环 / 播放暂停（互斥）=================
        //
        // 缓冲时**只显示环**，不显示播放键 —— 后者会让人以为可以点，
        // 而这时点了也没用（ExoPlayer 在 BUFFERING 下的 play/pause 无视觉反馈）。
        if (buffering) {
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    // 纯黑播放器底上必须用高对比的白
                    color = colors.labelOnMedia,
                    strokeWidth = V3Space.progressTrack,
                    modifier = Modifier.size(V3Size.iconLg),
                )
            }
        } else {
            // ================= 中央：播放 / 暂停 =================
            //
            // 🔴 这里**必须用 `alpha`，不能用 `AnimatedVisibility`**（v1.4.1 修）。
            //
            // `AnimatedVisibility(visible = false)` 会把子树**移出组合树** ——
            // 不只是变透明，而是**组件根本不存在**。后果：
            // 控件自动隐藏后，用户在画面中央点一下想"唤出控件 + 继续播"，
            // 这个按钮**不存在**，点击落到下面的手势层，只唤出控件，
            // 播放状态没变。表现就是「点了中间没反应，要点两次」。
            //
            // 这与 §11.0.1「浮层按钮：可点性不能用 AnimatedVisibility 控制」
            // 是**同一类错误** —— 那次踩的是返回键，这次是播放键。
            //
            // 正确做法：始终在组合里、始终可点，只用 alpha 控制视觉。
            // 隐藏时按钮不可见但**命中区仍在**，点一下立刻继续播。
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(
                        animateFloatAsState(
                            targetValue = if (controlsVisible) 1f else 0f,
                            animationSpec = tween(Motion.FADE_MS, easing = Motion.standard),
                            label = "centerButtonAlpha",
                        ).value,
                    )
                    .size(CENTER_BUTTON),
                contentAlignment = Alignment.Center,
            ) {
                // 🔴 v3：中央播放/暂停钮 = **扁平半透明**（不是模糊玻璃）。
                //
                // ## 为什么不用模糊玻璃（实测两轮后放弃）
                //
                // **第一轮** `Level.Clear + clearScrim`：控件变成**实心黑饼** ——
                // `Clear.tintScale = 1.6` 会放大传入 alpha，`0.50 × 1.6 = 0.80`，
                // 比改之前的纯色（54%）**更黑**。
                //
                // **第二轮** `Thin + 0.25`（有效 0.24）：能透出背景了，
                // 但**观感仍不对** —— 播放器控件是**标点**不是内容，
                // 加 6dp 模糊等于在已经很花的画面上再铺一层有纹理的玻璃。
                //
                // ## 扁平才是对的
                //
                // 均匀压暗 35%，零 GPU 成本（一次 `drawRect`，不抓帧不模糊），
                // 白图标在任何画面上都清晰。播放键只是个"能点的圆"，
                // 它不需要材质，只需要**可读 + 不抢戏**。
                //
                // ⚠️ 可点性由**外层 Box** 提供 —— 保持 v1.4.1 的教训：
                //    控件始终在组合里、始终可点，只用 alpha 控制视觉
                //    （不能用 AnimatedVisibility 移出组合）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(colors.materials.controlFlat)
                        .border(1.dp, colors.materials.controlFlatBorder, CircleShape)
                        .clickable {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(V3Size.iconLg + V3Space.xxs),
                    )
                }
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

        // ================= 底部：进度条（单行、紧凑）=================
        //
        // ## 结构（新设计语言）
        //
        // ```
        // [渐变]                      ← 保证白字压任意画面可读
        //   ┌──────────────────────┐
        //   │ 00:15 ▬▬▬▬▬░░░░ 16:28 │  ← 等宽读数 + 细轨道
        //   └──────────────────────┘
        // ```
        //
        // ## 为什么两侧时间都用等宽
        //
        // 只把左边改等宽是不够的 —— 右侧总时长虽不变，但两处字体不一致
        // 会让这一行看起来"两段拼接"。两边同族才是一个整体。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(animationSpec = tween(Motion.FADE_MS, easing = Motion.standard)),
            exit = fadeOut(animationSpec = tween(Motion.FADE_MS, easing = Motion.standard)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 底部渐变：保证白色控件压在任意画面上都可读。
                    // 这是**可读性兜底**，不是装饰 —— 不能去掉。
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, colors.gradientMediaEnd),
                        ),
                    )
                    // 紧凑：垂直 2dp、水平 8dp（播放器每多一像素都是从画面里抢的）
                    .padding(horizontal = V3Space.xs, vertical = V3Space.hairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 当前进度（等宽）
                MonoReadout(
                    text = formatTime(
                        if (isDragging) (dragFraction * duration).toLong() else position,
                    ),
                    color = colors.labelOnMedia,
                    fontSize = V3Type.caption2.fontSize,
                    weight = FontWeight.Medium,
                )

                Spacer(Modifier.width(V3Space.xs))

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
                                    setDragging(true)
                                    dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                    // 按住即预览，松手才真正 seek（与横滑 seek 同一策略：
                                    // 拖动中反复 seek 会让播放器不断重新缓冲）
                                    tryAwaitRelease()
                                    player.seekTo((dragFraction * duration).toLong())
                                    position = (dragFraction * duration).toLong()
                                    setDragging(false)
                                },
                            )
                        }
                        .pointerInput(duration) {
                            if (duration <= 0) return@pointerInput
                            detectHorizontalDragGestures(
                                onDragStart = { setDragging(true) },
                                onDragEnd = {
                                    player.seekTo((dragFraction * duration).toLong())
                                    position = (dragFraction * duration).toLong()
                                    setDragging(false)
                                },
                                onDragCancel = { setDragging(false) },
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
                        // 空降助手：把可跳过区间画在轨道上（v1.6.3）
                        skipSegments = skipSegments,
                        durationMs = duration,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = V3Space.xxs),
                    )

                    // ---- 拖动时的画面预览（v1.5.3）----
                    //
                    // ⚠️ 预览**只在拖动中出现** —— 平时不占任何空间、
                    // 也不加载图片（`videoshot` 是懒拉的，见 VM）。
                    //
                    // 位置：气泡左边缘跟随拖动点，但**夹在轨道范围内** ——
                    // 否则拖到两端时气泡会超出屏幕被裁掉。
                    if (isDragging && videoshot != null && duration > 0) {
                        val tSec = (dragFraction * duration / 1000f).toInt()
                        SeekPreview(
                            videoshot = videoshot,
                            positionSeconds = tSec,
                            fraction = dragFraction,
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }
                }

                Spacer(Modifier.width(V3Space.xs))

                // 总时长（同样等宽，与左侧读数同族）
                MonoReadout(
                    text = formatTime(duration),
                    color = colors.labelOnMedia,
                    fontSize = V3Type.caption2.fontSize,
                    weight = FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 拖动进度条时的**画面预览**气泡（v1.6.1 重做，修卡顿）。
 *
 * ## 🔴 上一版为什么卡（根因）
 *
 * 上一版每个拖动事件都新建一个 `ImageRequest` + 一个 `CropToFrame`
 * 交给 `AsyncImage`，而 `CropToFrame.transform()` 里是
 * `Bitmap.createBitmap(input, x, y, w, h)` —— **这个方法会拷贝像素**：
 * 每帧 480×270×4B ≈ 518KB。拖动时指针事件 60~120Hz，
 * 等于**每秒分配几十 MB**，GC 疯狂触发 → 掉帧。
 *
 * 雪上加霜：`CropToFrame` 的 `cacheKey` 含裁剪坐标，
 * 每换一帧就是一个**新的缓存条目** → Coil 内存缓存被 68 个 key 打散；
 * 而且 `AsyncImage` 是**异步**的，拖动中会先显示旧帧再跳新帧（闪）。
 *
 * ## 现在的做法（零分配 + 同步绘制）
 *
 * 1. **精灵图只解码一次**（`produceState` + Coil `execute`），
 *    并按预览实际需要的尺寸**降采样** —— 案例视频的精灵图是
 *    4800×2700（ARGB 约 51MB），而我们只画 324px 宽的预览，
 *    没必要为它占 51MB。
 * 2. 用 `BitmapPainter(srcOffset, srcSize)` 在**绘制期**裁剪 ——
 *    这是 GPU 侧的源矩形取样，**不拷贝任何像素**。
 * 3. `remember(sheet, index)` 缓存 painter：**只有帧下标变化时才重建**，
 *    同一帧内移动指针完全不重建。
 *
 * ## 位置夹取
 *
 * 气泡左边缘 = `fraction * 轨道宽 - 气泡宽/2`，夹在
 * `[0, 轨道宽 - 气泡宽]` —— 否则拖到两端会超出屏幕被裁掉。
 */
@Composable
private fun SeekPreview(
    videoshot: Videoshot,
    positionSeconds: Int,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors
    val density = LocalDensity.current
    val context = LocalContext.current

    // 预览尺寸：屏宽 30%
    val config = LocalConfiguration.current
    val previewW = (config.screenWidthDp * 0.30f).dp
    val previewH = previewW *
        (videoshot.frameHeight.toFloat() / videoshot.frameWidth.toFloat())

    val previewWPx = with(density) { previewW.roundToPx() }

    // ---- 1. 精灵图：只解码一次，且降采样到"预览够用"的尺寸 ----
    //
    // 目标尺寸 = 单帧宽度要够 previewWPx → 整图宽 = previewWPx * cols
    val wantW = (previewWPx * videoshot.cols).coerceAtLeast(1)
    val wantH = (wantW * videoshot.rows * videoshot.frameHeight / videoshot.frameWidth)
        .coerceAtLeast(1)

    // ⚠️ 用 `remember` + `LaunchedEffect` 而不是 `produceState` ——
    // lint 的 `ProduceStateDoesNotAssignValue` 检测器要求 producer lambda 里
    // **语法上直接**出现 `value = ...`；用 `runCatching` 包一层它就判定"没赋值"
    // （即使运行时确实赋了）。这个写法同样只解码一次，且意图更直白。
    var sheet by remember(videoshot.sheetUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(videoshot.sheetUrl, wantW, wantH) {
        sheet = runCatching {
            val req = ImageRequest.Builder(context)
                .data(videoshot.sheetUrl)
                .size(wantW, wantH)
                // ⚠️ 必须关硬件位图：硬件位图不能参与 `toBitmap()` 取像素
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(req)
            (result as? coil.request.SuccessResult)
                ?.drawable
                ?.toBitmap()
                ?.asImageBitmap()
        }.getOrNull()
    }

    val bmp = sheet ?: return

    // ---- 2. 帧下标 → 绘制期裁剪 ----
    val index = videoshot.frameIndexAt(positionSeconds)
    if (index < 0) return

    // ⚠️ Coil 的实际解码尺寸未必正好等于 wantW/wantH（它按采样率取整），
    // 所以缩放系数必须**按实际位图算**，不能拿 wantW 除。
    val scale = bmp.width.toFloat() / (videoshot.cols * videoshot.frameWidth).toFloat()

    // 只有帧下标变化时才重建 painter（同一帧内移动指针不重建）
    val painter = remember(bmp, index, scale) {
        val (ox, oy) = videoshot.frameOrigin(index)
        BitmapPainter(
            image = bmp,
            srcOffset = IntOffset(
                (ox * scale).toInt().coerceIn(0, (bmp.width - 1).coerceAtLeast(0)),
                (oy * scale).toInt().coerceIn(0, (bmp.height - 1).coerceAtLeast(0)),
            ),
            srcSize = IntSize(
                (videoshot.frameWidth * scale).toInt().coerceAtLeast(1),
                (videoshot.frameHeight * scale).toInt().coerceAtLeast(1),
            ),
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val trackWPx = with(density) { maxWidth.toPx() }
        val rawX = fraction * trackWPx - previewWPx / 2f
        val clampedX = rawX.coerceIn(0f, (trackWPx - previewWPx).coerceAtLeast(0f))

        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset(
                    x = with(density) { clampedX.toDp() },
                    // 向上偏移一个气泡高 + 8dp，浮在进度条上方
                    y = -(previewH + V3Space.xs),
                )
                .width(previewW)
                .height(previewH)
                .clip(RoundedCornerShape(V3Radius.xs))
                .background(colors.bgTertiary)
                .border(1.dp, colors.separator, RoundedCornerShape(V3Radius.xs)),
        )
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
    skipSegments: List<SkipSegment> = emptyList(),
    durationMs: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors
    val active = colors.brand
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

        // ---- 空降助手：可跳过区间（v1.6.3）----
        //
        // ## 绘制顺序：在"已播轨道"**之前**
        //
        // 已播部分（品牌粉）必须始终是**最上层**的读数 ——
        // 它表达"我播到哪了"，比"这段会被跳过"更重要。
        // 反过来画会让色块盖住进度，用户就分不清播到哪了。
        //
        // ## 为什么比轨道高
        //
        // 轨道只有 2dp，同高画上去几乎看不见（尤其在大屏上）。
        // 这里用 SKIP_MARKER_H（5dp）做成"旗标"，两侧带圆角 ——
        // 既醒目，又不至于把细轨道变成一条粗彩带。
        //
        // ## 几何全部交给 [SkipBarGeometry]（纯函数，有单测）
        //
        // 越界裁剪、最小宽度、时长未知这些**边界**在这里手写极易出错，
        // 且错了不报错、只是标记画在错的位置。抽出去后用
        // `SkipBarGeometryTest` 钉死，生产与测试调的是同一份实现。
        if (durationMs > 0L && skipSegments.isNotEmpty()) {
            val w = size.width
            val markH = SKIP_MARKER_H.toPx()

            for (span in SkipBarGeometry.spans(skipSegments, durationMs / 1000.0)) {
                val seg = skipSegments[span.index]
                val left = span.startFraction * w
                val drawW = (span.widthFraction * w).coerceAtMost(w - left)
                if (drawW <= 0f) continue

                drawRoundRect(
                    color = skipSegmentColor(colors, seg.category),
                    topLeft = Offset(left, centerY - markH / 2f),
                    size = androidx.compose.ui.geometry.Size(width = drawW, height = markH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(markH / 2f),
                )
            }
        }

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
            color = colors.labelOnMedia,
            radius = dotR,
            center = Offset(playedX.coerceIn(dotR, size.width - dotR), centerY),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
        )
        // radius 变量用于消除未使用警告外的可读性；实际画线用 strokeWidth
        check(radius >= 0f)
    }
}

/**
 * 跳过片段的标记色（v1.6.3）。
 *
 * ## 🔴 全部取自现有令牌，不新增色值
 *
 * §5.2 要求"页面内零硬编码"，且**禁止第二套配色**。
 * 所以这里只做**类别 → 已有令牌**的映射，不定义任何新颜色。
 *
 * ## 为什么按类别分色（需求："不同跳过区间需要能够清楚区分"）
 *
 * 全部用同一个颜色时，相邻的两个片段（如 `片头` 紧跟 `恰饭广告`）
 * 会连成一片，用户无法判断这是"一段"还是"两段"。
 *
 * ## 与 `stateError` 的取舍（这是刻意的，不是随手挑的）
 *
 * `stateError`（`#F87171` 红）语义是"出错了"。这里用它标恰饭广告，
 * 理由是：它是**用户最想避开**的一类，需要最强的视觉排斥感；
 * 而进度条上的红色不会被误读成"播放失败"—— 那个位置没有任何
 * 错误态的既有约定。其余类别一律走非警示色，避免整条轨道"全红"。
 *
 * ⚠️ 不用 `brandPrimary`：那是**已播进度**的专用色，
 * 复用会让"已播"与"会被跳过"混成一个意思。
 */
private fun skipSegmentColor(
    colors: com.example.biliv3.design.v3.V3Colors,
    category: String,
): androidx.compose.ui.graphics.Color = when (category) {
    "sponsor" -> colors.stateError
    "selfpromo" -> colors.accentCoin
    "intro", "outro", "preview" -> colors.accentTerminal
    else -> colors.categoryAccent
}

/** 右上角小图标按钮。比中央按钮小一圈，降低视觉占用。 */
@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    Box(
        modifier = Modifier
            .size(SMALL_BUTTON)
            .clip(CircleShape)
            .background(colors.controlOverlay)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.labelOnMedia,
            modifier = Modifier.size(V3Size.iconMd),
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

/**
 * 跳过片段标记的高度（v1.6.3）。
 *
 * 比轨道（[TRACK_HEIGHT] 2dp）高，否则在 2dp 细线上几乎看不见。
 * 5dp 是"看得清"与"不把细轨道变成粗彩带"之间的取值。
 *
 * ⚠️ **最小宽度不在这里** —— 它属于"秒 → 轨道位置"的几何换算，
 * 放在 [com.example.biliv3.data.SkipBarGeometry.MIN_WIDTH_FRACTION]，
 * 那样才能被单测覆盖（`SkipBarGeometryTest`）。
 */
private val SKIP_MARKER_H = 5.dp
