package com.example.biliv3.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import kotlinx.coroutines.delay

/**
 * 空降助手：按播放进度自动跳过片段。
 *
 * ## 为什么是"轮询"而不是监听
 *
 * Media3 没有"每帧回调"。跳过的判定需要**持续**知道当前进度，
 * 而 ExoPlayer 只在状态变化时回调（如 `onPositionDiscontinuity`），
 * 正常播放推进**不触发任何回调**。
 *
 * 所以用 [POLL_INTERVAL_MS] 轮询。200ms 足够及时（人感知不到 0.2s 的延迟），
 * 又不会像逐帧那样浪费 —— 这不是渲染，不需要跟 vsync。
 *
 * ## ⚠️ 三个必须防的坑
 *
 * 1. **拖动进度条时不能跳** —— 用户正在拖，此时 seek 会打架。
 *    由 `isUserSeeking` 参数挡住（详情页把拖动状态传进来）。
 * 2. **跳过要在"位置已进入片段"时才做** —— 用 ViewModel 的
 *    `skipTargetFor` 统一判定，包含"跳过一次不再跳"的去重。
 * 3. **不能跳到自己前面** —— 目标必须 > 当前位置，否则会形成
 *    往回跳的死循环。
 */
@Composable
fun SponsorBlockSkipper(
    player: ExoPlayer?,
    enabled: Boolean,
    /** 判定当前进度该不该跳，返回目标秒数（null = 不跳）。 */
    skipTargetFor: (Double) -> Double?,
    /** 是否正在被用户拖动进度条（拖动时不跳）。 */
    isUserSeeking: Boolean,
) {
    if (!enabled || player == null) return

    // 用 remember 持有最新回调，避免把 lambda 放进 LaunchedEffect 的 key
    // 导致每次重组都重启轮询。
    val latestTarget by androidx.compose.runtime.rememberUpdatedState(skipTargetFor)
    val latestSeeking by androidx.compose.runtime.rememberUpdatedState(isUserSeeking)

    LaunchedEffect(player, enabled) {
        while (true) {
            delay(POLL_INTERVAL_MS)

            // 拖动中不跳 —— 用户正在手动定位
            if (latestSeeking) continue

            // 已释放的 player 读取会抛，防御掉
            val posMs = runCatching { player.currentPosition }.getOrNull() ?: break
            val target = latestTarget(posMs / 1000.0) ?: continue

            // 目标必须真的在前面。否则宁可不动 ——
            // 往回跳会形成"跳过去→又满足条件→再跳回来"的死循环。
            if (target * 1000.0 > posMs) {
                // 记一条日志：排查"跳过到底有没有生效"时是唯一的一手证据
                android.util.Log.i(
                    "BiliSponsorBlock",
                    "跳过 ${posMs / 1000.0}s -> ${target}s",
                )
                runCatching { player.seekTo((target * 1000.0).toLong()) }
            }
        }
    }
}

/**
 * "已跳过"提示条。
 *
 * ## 交互取舍
 *
 * - **自动消失**（[AUTO_HIDE_MS]）：它是通知不是控件，长期占着画面很烦
 * - **可撤销**：社区标注可能出错（比如把正片标成广告）。
 *   没有撤销的话用户只能手动拖回去，还得先记住跳到哪了
 * - 位置在**播放器顶部**而不是底部：底部是控制条/字幕/弹幕密集区，
 *   放那里会和它们打架
 *
 * ## 为什么不做成 Snackbar
 *
 * 详情页的 Snackbar 在页面底部，而跳过发生在**播放器画面内**。
 * 提示出现在远离视线焦点的底部，用户很可能看不到就消失了。
 */
@Composable
fun SkippedBanner(
    label: String?,
    durationSeconds: Double,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    // 每次新提示都重新计时
    var visible by remember(label, durationSeconds) { mutableStateOf(true) }
    LaunchedEffect(label, durationSeconds) {
        delay(AUTO_HIDE_MS)
        visible = false
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = Space.x3, vertical = Space.x2)
                .clip(RoundedCornerShape(Radius.pill))
                .background(colors.overlayControl)
                .clickable(enabled = canUndo, onClick = onUndo)
                .padding(horizontal = Space.x3, vertical = Space.x1 + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(
                text = buildString {
                    append("已跳过")
                    if (!label.isNullOrEmpty()) append(label)
                    if (durationSeconds >= 1.0) {
                        append(" ")
                        append(durationSeconds.toInt())
                        append(" 秒")
                    }
                },
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.onOverlay,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canUndo) {
                Spacer(Modifier.width(Space.x2))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "撤销跳过",
                    tint = colors.brandPrimary,
                    modifier = Modifier.size(Sizes.iconSm),
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = "撤销",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.brandPrimary,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 进度轮询间隔。200ms 够及时，又不至于浪费。 */
private const val POLL_INTERVAL_MS = 200L

/** 提示条自动消失时间。 */
private const val AUTO_HIDE_MS = 5000L
