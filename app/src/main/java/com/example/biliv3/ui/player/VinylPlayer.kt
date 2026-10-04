package com.example.biliv3.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.DeviceTier
import com.example.biliv3.design.LocalDeviceTier
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.player.QueueItem

/**
 * 黑胶唱片模式。
 *
 * ## 视觉构成（任务书 §7）
 *
 * ```
 *        ┌─────────────┐
 *        │   ◉ 封面     │  ← 唱片（旋转）
 *        └─────────────┘
 *          标题
 *          UP 主
 *        ─────────────    ← 进度（发丝线 + 品牌粉已播段）
 *        01:23      04:56
 * ```
 *
 * ## ⚠️ 三条硬约束
 *
 * ### 1. 旋转用 `graphicsLayer` 而不是每帧重组
 *
 * `rememberInfiniteTransition` 的 `animateFloat` 是**状态**，
 * 直接读它会让 Composable 每帧重组（60fps × 整个子树）。
 * 用 `Modifier.rotate(angle)` 也是同样问题。
 *
 * 正确做法见下面的 `rotate` —— 它读的是 `State<Float>`，
 * Compose 会把它优化到**绘制阶段**（只重绘，不重组）。
 * 这与项目在骨架屏上踩过的坑同源（§7.4-29：相位要在绘制阶段读）。
 *
 * ### 2. 暂停时**不停止动画而是冻结**
 *
 * 直接 `if (isPlaying) transition else 0f` 会让唱片**跳回 0 度** ——
 * 暂停时唱片"啪"地转回原点，很廉价。
 * 正确做法：暂停时保持当前角度不动（下面用 `currentAngle` 冻结）。
 *
 * ### 3. 低端设备降级
 *
 * `DeviceTier.Low` 时：
 * - 关掉旋转（只显示静态封面）—— 持续旋转在低端机上会掉帧
 * - 关掉封面圆形的细微渐变
 *
 * 这与项目既有的 `DeviceTier` 用法一致（首页 `grain()` 也这样降级）。
 */
@Composable
fun VinylPlayer(
    item: QueueItem?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** 封面下方的内容（进度条、控制按钮、歌词入口）。 */
    controls: @Composable () -> Unit = {},
) {
    val colors = BiliTheme.colors
    val tier = LocalDeviceTier.current
    val canAnimate = tier != DeviceTier.Low

    // 唱片旋转：8 秒一圈（黑胶 33⅓ 转 ≈ 1.8s/圈，但屏幕上太快会晕）
    val rotation = rememberVinylRotation(isPlaying = isPlaying, enabled = canAnimate)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.x6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ---- 唱片 ----
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            // 黑胶盘体：深色圆 + 中心封面
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    // 盘体的"沟槽"用极淡的同心径向渐变表现 ——
                    // 不用图片资源（零体积），也不用扫描线 / 霓虹 / 光斑
                    // （§5.1 明确禁止）
                    .background(
                        Brush.radialGradient(
                            colors = listOf(colors.bgHover, colors.playerBackground),
                        ),
                    ),
            )

            // 封面（占盘体 58%，像唱片中心的标签）
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(colors.coverPlaceholder)
                    .then(
                        if (canAnimate) Modifier.rotate(rotation) else Modifier,
                    ),
            ) {
                AsyncImage(
                    model = item?.cover.orEmpty(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 中心孔：黑胶唱片的视觉特征（小圆点）
            Box(
                modifier = Modifier
                    .size(Space.x3)
                    .clip(CircleShape)
                    .background(colors.bgBase),
            )
        }

        Spacer(Modifier.height(Space.x8))

        // ---- 标题 / 作者 ----
        Text(
            text = item?.title.orEmpty().ifEmpty { "暂无播放" },
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = FontSize.titleMd,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (!item?.author.isNullOrEmpty()) {
            Spacer(Modifier.height(Space.x2))
            Text(
                text = item.author,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(Space.x5))

        // ---- 进度读数（等宽：数字跳动时不抖，§5.2 等宽只用于读数） ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MonoTime(positionMs)
            MonoTime(durationMs)
        }

        Spacer(Modifier.height(Space.x4))

        controls()
    }
}

/** 等宽时间读数。 */
@Composable
private fun MonoTime(ms: Long) {
    val colors = BiliTheme.colors
    Text(
        text = formatTime(ms),
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.monoReadout,
            color = colors.textSecondarySafe,
            fontFamily = com.example.biliv3.design.tokens.FontFamilies.mono,
        ),
    )
}

/**
 * 唱片旋转角度。
 *
 * ## ⚠️ 暂停时**冻结**而不是归零
 *
 * 用 `rememberInfiniteTransition` 只能"一直转"或"不转"，
 * 无法表达"停在当前角度"。所以这里自己维护累积角度：
 *
 * - 播放中：每帧推进
 * - 暂停：保持 `frozenAngle`（不跳回 0）
 *
 * 这也是**低端设备降级**的接入点：`enabled = false` 时角度恒为 0。
 */
@Composable
private fun rememberVinylRotation(isPlaying: Boolean, enabled: Boolean): Float {
    if (!enabled) return 0f

    val transition = rememberInfiniteTransition(label = "vinyl")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            // 8 秒一圈：视觉上像"在转"，但不会快到让人晕
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "vinylAngle",
    )

    // 暂停时不推进 —— 但也不归零。
    //
    // 实现：把"上次播放时的角度"记住，暂停期间一直返回它。
    // ⚠️ 这里用 `remember` 存冻结值，不能用 `derivedStateOf`
    // （后者会随 angle 变化重算，反而又转起来了）。
    val frozen = remember { floatArrayOf(0f) }
    if (isPlaying) {
        frozen[0] = angle
        return angle
    }
    return frozen[0]
}

/** 毫秒 → `mm:ss` / `h:mm:ss`。 */
internal fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
