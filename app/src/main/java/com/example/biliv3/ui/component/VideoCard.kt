package com.example.biliv3.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import com.example.biliv3.design.tokens.Motion
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Type

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
    // v3：改用新调色板（label 四级 / fill 四级 / 背景三级）
    val colors = BiliV3.colors
    val context = LocalContext.current

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        // ---- 封面（官方 16:10）----
        //
        // 🔴 v3：封面**恢复圆角**（12dp）。
        //
        // 旧系统把封面改成了直角，理由是"圆角是卡片的语言，
        // 保留圆角会让它看起来仍是一张卡"。
        //
        // 新系统推翻它：**真正让它像卡片的不是圆角，是容器**。
        // 这个封面没有底色、没有内边距、没有描边 —— 它就是一张图，
        // 圆角只是"图片"的现代处理（iOS 27 实测：列表行内嵌图 7~11dp 圆角）。
        //
        // 反过来，直角封面在深色底上会显得**硬、廉价、像截图**，
        // 与"高级、有质感"的目标直接冲突。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(V3Size.coverAspect)
                .clip(RoundedCornerShape(V3Radius.md))
                // 占位底色比页面底略亮 —— 图片出现时有"内容填充"感，
                // 而不是"白块淡入"（这是搜索列表闪烁的根因之一）。
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = videoCoverRequest(context, video.coverUrl(), video.title),
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(V3Size.coverAspect)
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
                                // 压暗到 60%：白字压任意封面都可读，且比旧版 40% 更干净
                                Color(0x99000000),
                            ),
                        ),
                    ),
            )

            // ---- 播放量（左下，压在封面上）----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = V3Space.xs, bottom = V3Space.xs),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(V3Size.iconXs),
                )
                Spacer(Modifier.width(V3Space.hairline))
                Text(
                    text = formatCount(video.playCount),
                    style = V3Type.readout(size = 11),
                    color = Color.White,
                )
            }

            // ---- 时长角标（右下）----
            if (video.durationLabel.isNotEmpty()) {
                DurationBadge(
                    text = video.durationLabel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(V3Space.xs),
                )
            }
        }

        // 封面 → 标题
        Spacer(Modifier.height(V3Space.xs))

        // ---- 标题（两行截断，预留高度防 CLS）----
        Text(
            text = video.title,
            style = V3Type.subheadline,
            fontWeight = FontWeight.Medium,
            color = if (hovered) colors.brandText else colors.labelPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                // 预留两行高度：避免图片加载完成时列表跳动（CLS）
                .heightIn(min = 40.dp),
        )

        Spacer(Modifier.height(V3Space.xxs))

        // ---- 元信息行（UP 名 + 播放量）----
        //
        // 🔴 v3 改动：**去掉小头像**。
        //
        // 旧版在标题下画了一个 16dp 的小圆头像。16dp 的头像
        // **看不清是谁**，它不提供任何信息，只是装饰 ——
        // 而且它比旁边的 12sp 文字还高，把行高撑得不自然。
        //
        // iOS 风格的做法：元信息就是**一行小字**（UP 名 · 播放量），
        // 没有头像。头像留给"点进 UP 主页"那个场景。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = video.authorName,
                style = V3Type.caption1,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // ⚠️ 只在 UP 名确实存在时才加分隔点 ——
            // 否则会得到"· 1.2万"这种开头的孤立分隔符
            if (video.authorName.isNotEmpty()) {
                Text(
                    text = " · ",
                    style = V3Type.caption1,
                    color = colors.labelTertiary,
                )
            }
            Text(
                text = "${formatCount(video.playCount)} 播放",
                style = V3Type.caption1,
                color = colors.labelTertiary,
                maxLines = 1,
            )
        }
    }
}

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
 * 直角（`V3Radius.xs` 已随卡片架构删除）。
 * 压在封面上的小标签不需要圆角 —— 圆角在这里反而显"软"。
 */
@Composable
private fun DurationBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(V3Radius.xs))
            .background(Color(0xA6000000))
            .padding(horizontal = V3Space.xs, vertical = 3.dp),
    ) {
        // 等宽：网格里多个时长会上下对齐
        Text(
            text = text,
            style = V3Type.readout(size = 11),
            color = Color.White,
        )
    }
}
