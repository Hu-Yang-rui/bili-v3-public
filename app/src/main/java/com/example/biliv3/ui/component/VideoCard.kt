package com.example.biliv3.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Motion
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 视频项 —— 主页最核心组件。
 *
 * ## 结构（乙·质感 · 无缝网格项）
 *
 * ```
 * ┌────────────────┐
 * │   封面 16:10    │  ← 直角，无容器，无圆角
 * │          12:34 │     仅底部渐变兜底可读性
 * ├────────────────┤
 *    标题两行截断       ← 直接排在页面底上
 *    ◉ UP 名
 * ```
 *
 * ## 🔴 相对上一版的三处结构性改动
 *
 * ### 1. 删掉卡片容器
 *
 * 上一版是 `biliCard()`（圆角 + 描边 + 8dp 内边距）。
 * 那是"盒子"：四边断开 + 圆角 → 卡片套卡片 → 臃肿。
 *
 * 现在封面**直角**、**无内边距**、**无描边**，文字直接排在页面底上。
 * 分组交给网格间距与明度带，不再由容器承担。
 *
 * ### 2. 封面圆角 12 → 0
 *
 * 圆角是"卡片"的语言，直角是"色块"的语言。
 * 保留圆角会让它看起来仍是一张卡。
 *
 * ### 3. 删掉整卡缩放反馈
 *
 * 上一版按压时整卡 `scale 0.97`。无容器后缩放会让封面与文字
 * 一起缩小，**在网格里造成相邻项错位感**。
 * 改为只做封面 hover 放大（桌面端）与标题变色，不再动整体。
 *
 * ## 保留的工艺
 *
 * - 底部渐变兜底（保证白字压任意封面可读）
 * - 等宽时长角标 + 等宽播放量
 * - `coverPlaceholder` 占位底色（防"白块淡入"）
 * - 头像**条件渲染的反面**：只要 UP 名非空就画头像位（防字段 bug 被藏住）
 */
@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    val context = LocalContext.current

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        // ---- 封面（官方 16:10，直角无容器）----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Sizes.coverAspectRatio)
                // 占位底色比页面底略深 —— 图片出现时有"内容填充"感，
                // 而不是"白块淡入"（这是搜索列表闪烁的根因之一）。
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = videoCoverRequest(context, video.coverUrl(), video.title),
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(Sizes.coverAspectRatio)
                    .graphicsLayer {
                        // 只有桌面端 hover 才放大；移动端无 hover，恒为 1
                        val s = if (hovered) 1.04f else 1f
                        scaleX = s
                        scaleY = s
                    },
            )

            // 底部渐变：保证白色播放量与时长角标在任意封面上可读
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .fillMaxHeight(0.42f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                colors.gradientMediaEnd,
                            ),
                        ),
                    ),
            )

            // ---- 播放量（左下，压在封面上）----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = Space.x2, bottom = Space.x2),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = colors.onOverlay,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(Space.micro))
                MonoReadout(
                    text = formatCount(video.playCount),
                    color = colors.onOverlay,
                    fontSize = FontSize.label,
                    weight = FontWeight.Normal,
                )
            }

            // ---- 时长角标（右下）----
            if (video.durationLabel.isNotEmpty()) {
                DurationBadge(
                    text = video.durationLabel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Space.x2),
                )
            }
        }

        // 封面 → 标题：走 Rhythm.inGroup（组内紧贴，与"组间大间距"形成对比）
        Spacer(Modifier.height(Space.x2))

        // ---- 标题（两行截断，预留高度防 CLS）----
        Text(
            text = video.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                lineHeight = FontSize.bodyLine,
                fontWeight = FontWeight.Medium,
                color = if (hovered) colors.textBrandSafe else colors.textPrimary,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
        )

        Spacer(Modifier.height(Space.x1))

        // ---- 元信息行 ----
        //
        // ⚠️ 头像**不做条件渲染**：只要 UP 名非空就画头像位。
        // 上游字段写错时会是整片灰圆（肉眼可见），而不是静默消失。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (video.authorName.isNotEmpty()) {
                AsyncImage(
                    model = video.faceUrl(48),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(UP_AVATAR_SIZE)
                        .clip(CircleShape)
                        .background(colors.skeletonBase),
                )
                Spacer(Modifier.width(Space.x1))
            }

            Text(
                text = video.authorName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** UP 头像尺寸。16dp：不抢标题的视觉权重。 */
private val UP_AVATAR_SIZE = 16.dp

/**
 * 封面图片请求。
 *
 * Coil 2.x 的交叉淡入只能在 `ImageRequest` 上配（`AsyncImage` 没有该参数）。
 * 显式指定缓存键 = 归一化 URL，避免同图不同 shard 造成缓存分裂。
 */
private fun videoCoverRequest(
    context: android.content.Context,
    url: String,
    title: String,
) = ImageRequest.Builder(context)
    .data(url)
    .crossfade(Motion.IMAGE_FADE_MS)
    .memoryCacheKey(url)
    .diskCacheKey(url)
    .precision(coil.size.Precision.INEXACT)
    .build()

/**
 * 时长角标。
 *
 * 直角（`Radius.badge` 已随卡片架构删除）。
 * 压在封面上的小标签不需要圆角 —— 圆角在这里反而显"软"。
 */
@Composable
private fun DurationBadge(text: String, modifier: Modifier = Modifier) {
    val colors = BiliTheme.colors
    Box(
        modifier = modifier
            .background(colors.overlayCover)
            .padding(horizontal = Space.tagHorizontal, vertical = Space.tagVertical),
    ) {
        // 等宽：网格里多个时长会上下对齐
        MonoReadout(
            text = text,
            color = colors.onOverlay,
            fontSize = FontSize.badge,
            weight = FontWeight.Medium,
        )
    }
}
