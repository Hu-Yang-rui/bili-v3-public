package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.BiliTheme
import androidx.media3.exoplayer.ExoPlayer
import com.example.biliv3.data.subtitle.SubtitleBody
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Space
import kotlinx.coroutines.delay

/**
 * 字幕渲染层。
 *
 * ## 为什么不用 Media3 的 `PlayerView` 内置字幕
 *
 * B 站的字幕是**外挂 JSON**（`subtitle_url` 指向一个 JSON 文件），
 * 不是内嵌在流里的 WebVTT/SRT。Media3 只识别容器内的字幕轨，
 * 所以必须自己渲染。
 *
 * ## 为什么单独轮询进度而不是用 Player.Listener
 *
 * `Player.Listener` 只在**状态变化**时回调，而字幕需要**连续**跟随播放进度。
 * Media3 没有"每帧回调"的公开 API，所以按固定间隔采样。
 *
 * 采样间隔 200ms 是权衡：字幕切换本身以 0.5~2 秒为粒度，200ms 足够跟手；
 * 再密就是浪费重组。
 *
 * ## 位置
 *
 * 贴在画面底部、留出控制条高度 —— 控制条弹出时字幕不会被盖住。
 */
@Composable
fun SubtitleOverlay(
    player: ExoPlayer?,
    body: SubtitleBody?,
    modifier: Modifier = Modifier,
    /** 双语模式下额外显示的第二条字幕（通常是原文）。 */
    secondary: SubtitleBody? = null,
) {
    if (player == null || body == null) return

    var current by remember { mutableStateOf("") }
    var currentSecondary by remember { mutableStateOf("") }

    LaunchedEffect(player, body) {
        while (true) {
            // ⚠️ 必须防御：播放器可能已释放。
            //
            // 离开页面时 DisposableEffect 会 release() 播放器，而本协程的取消
            // 与它没有严格先后顺序 —— 存在"协程还没停、播放器已释放"的窗口。
            // 对已释放的 ExoPlayer 调 currentPosition 会抛 IllegalStateException。
            val posMs = runCatching { player.currentPosition }.getOrNull()
                ?: return@LaunchedEffect
            val posSec = posMs / 1000.0

            val cue = body.cueAt(posSec)
            val next = cue?.content.orEmpty()
            if (next != current) current = next

            if (secondary != null) {
                val s = secondary.cueAt(posSec)?.content.orEmpty()
                if (s != currentSecondary) currentSecondary = s
            }

            delay(SAMPLE_INTERVAL_MS)
        }
    }

    if (current.isEmpty() && currentSecondary.isEmpty()) return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Space.x4,
                    end = Space.x4,
                    // 留出控制条高度：控制条是单行约 40dp + 渐变
                    bottom = SUBTITLE_BOTTOM_MARGIN,
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 主字幕（翻译结果 / 单语）
                if (current.isNotEmpty()) {
                    SubtitleLine(text = current, emphasized = true)
                }
                // 次字幕（原文）
                if (currentSecondary.isNotEmpty() && currentSecondary != current) {
                    androidx.compose.foundation.layout.Spacer(
                        Modifier.padding(top = 2.dp),
                    )
                    SubtitleLine(text = currentSecondary, emphasized = false)
                }
            }
        }
    }
}

/**
 * 单行字幕。
 *
 * 用**半透明黑底 + 白字**而不是纯白字：
 * 字幕压在任意画面上（可能有白色背景），纯白字会完全看不见。
 * 官方也是这个做法。
 */
@Composable
private fun SubtitleLine(text: String, emphasized: Boolean) {
    val colors = BiliTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.badge + 2.dp))
            .background(colors.subtitleScrim)
            .padding(horizontal = Space.x2, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = if (emphasized) FontSize.titleMd else FontSize.bodySm,
                lineHeight = if (emphasized) FontSize.titleMdLine else FontSize.bodySmLine,
                fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
                color = colors.onOverlay,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/** 进度采样间隔。见文件头说明。 */
private const val SAMPLE_INTERVAL_MS = 200L

/** 字幕距底边距离。留出控制条空间。 */
private val SUBTITLE_BOTTOM_MARGIN = 56.dp
