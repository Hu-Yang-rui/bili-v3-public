package com.example.biliv3.ui.component

import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Motion
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 视频卡片 —— 主页最核心组件。
 *
 * ## 结构（C 方案 · 社区感）
 *
 * ```
 * ┌──────────────────────┐
 * │  ┌────────────────┐  │  ← 卡片容器（16dp 圆角 + 分层）
 * │  │   封面 16:10   │  │     浅色：3dp 投影
 * │  │           12:34│  │     深色：1dp 10%白描边
 * │  └────────────────┘  │
 * │   标题两行截断         │
 * │   ◉ UP名              │
 * └──────────────────────┘
 * ```
 *
 * ## ⚠️ C 方案相对首版的两处**方向性改动**
 *
 * ### 1. 从「无卡片容器」改回「有卡片容器」
 *
 * 首版专门论证过**不要**卡片底（见下方旧注释与 git 历史）：
 * 白底浅灰页面上再浮一层白卡 = 卡片套卡片，"儿童风"最重的来源。
 *
 * **那个论证在「封面 4dp + 无投影」的前提下成立。**
 * C 方案改了两个前提，结论随之改变：
 * - 封面圆角 4 → 12（大圆角需要容器才不"飘"）
 * - 新增 3dp 投影（浅色下真正制造层次）
 *
 * 关键是**投影**：首版"卡片套卡片"难看是因为白卡与页面底只差 3% 亮度，
 * 加个白块等于没加。现在有投影把卡片从底上"抬起来"，
 * 分层来自**光影**而不是**色差**，观感完全不同。
 *
 * ### 2. 封面圆角 4 → 12
 *
 * 与容器圆角（16）保持同族曲率。单独改一个会"打架"，
 * 详见 [com.example.biliv3.design.tokens.Radius] 的说明。
 *
 * ## hover / 按压反馈
 *
 * | 元素 | 变化 |
 * |---|---|
 * | 封面 | 放大 1.05（父级 clip 裁切） |
 * | 标题 | 变 [BiliTheme.colors.textBrandSafe] |
 * | 整卡按压 | 缩放 0.97（**只做 transform，不改布局边界**） |
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
            // C 方案：卡片表面。圆角 + 背景 + **按主题自动选投影/描边**。
            // 深色下投影不可见，biliCard() 内部会改用描边（见 BiliCard.kt）。
            .biliCard()
            .graphicsLayer {
                // 只做 transform，不触发重排，周围元素不会跟着抖
                val s = if (pressed) 0.97f else 1f
                scaleX = s
                scaleY = s
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            // 卡片内边距：封面与卡片边缘的呼吸空间。
            // ⚠️ 间距来源变了 —— 首版靠"卡片底部留白 8dp + 网格行距 12dp"
            // 分组；C 方案卡片自带容器，分组由**容器边缘 + 投影**完成，
            // 所以这里只留内边距，卡片之间交给网格间距。
            .padding(CARD_INNER_PADDING),
    ) {
        // ---- 封面（官方 16:10）----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Sizes.coverAspectRatio)
                .clip(RoundedCornerShape(Radius.cover))
                // ⚠️ 占位底色用 coverPlaceholder 而不是 skeletonBase。
                //
                // 浅色主题下 skeletonBase = #EDEEF0、页面底 bgBase = #F4F5F7 ——
                // 两者几乎同色，封面区在图片加载前后**看起来就是一块白底**，
                // 这就是用户报告的「搜索列表闪烁后变白底」的视觉来源。
                //
                // coverPlaceholder 在两个主题下都比页面底**略深**，
                // 图片出现时有明确的"内容填充"感，而不是"白块淡入"。
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                // ⚠️ Coil 2.x 的 AsyncImage **没有** transition 参数 ——
                // 交叉淡入是在 ImageRequest 上配的（coil.request.crossfade）。
                // 见下方 videoCoverRequest()。
                model = videoCoverRequest(context, video.coverUrl(), video.title),
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(Sizes.coverAspectRatio)
                    .graphicsLayer {
                        val s = if (hovered) 1.05f else 1f
                        scaleX = s
                        scaleY = s
                    },
            )

            // 底部渐变遮罩：保证压在封面上的白色播放量与时长角标
            // 在任意亮度的封面上都可读（只覆盖下半部分，不遮挡画面主体）
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
                Spacer(Modifier.width(2.dp))
                Text(
                    text = formatCount(video.playCount),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.onOverlay,
                    ),
                    maxLines = 1,
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

        // 封面 -> 标题：6dp（原来 8dp）
        Spacer(Modifier.height(6.dp))

        // ---- 标题（两行截断，预留两行高度防 CLS）----
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

        // 标题 -> UP 名：4dp（原来 6dp）
        Spacer(Modifier.height(Space.x1))

        // ---- 元信息行：UP 名（播放量已移到封面左下）----
        //
        // ## ⚠️ 头像只在「有 face 时」渲染会掩盖上游字段 bug
        //
        // 之前写成 `if (video.authorFace.isNotEmpty())` 才画头像。
        // 当时搜索接口的 `upic` 字段没被解析（`authorFace` 恒空），
        // 于是搜索结果**整行没有头像**，但因为这是"条件渲染"，
        // 代码看起来完全合理、也不报错 —— bug 被藏住了。
        //
        // 现在：只要 `authorName` 非空就渲染头像位。face 为空时是灰色圆底
        // 占位（与骨架屏同色），视觉上仍是"头像 + 昵称"的一致结构，
        // 且一旦上游字段再次写错，会**肉眼可见**为整片灰圆，而不是静默消失。
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

/**
 * UP 头像尺寸。
 *
 * 16dp：与官方卡片的紧凑元信息行一致，不抢标题的视觉权重。
 */
private val UP_AVATAR_SIZE = 16.dp

/**
 * 卡片内边距。
 *
 * C 方案的卡片有实体容器，内容不能贴着边缘 —— 8dp 是实测值：
 * 再小封面像"出血"到卡片边，再大封面被挤小（2 列网格本身宽度有限）。
 */
private val CARD_INNER_PADDING = Space.x2

/**
 * 封面图片请求。
 *
 * ## 为什么要显式构造 request 而不是直接传 URL 字符串
 *
 * Coil 2.x 的**交叉淡入**只能在 `ImageRequest` 上配置
 * （`AsyncImage` 本身没有 `transition` 参数）：
 *
 * - **crossfade**：图片"啪"地出现会造成闪烁观感，淡入 180ms 消除跳变
 * - **memoryCacheKey / diskCacheKey**：显式指定缓存键 = 归一化后的 URL，
 *   保证同一张图（不同 shard / 不同尺寸后缀）命中同一份缓存 ——
 *   与 `CoverUrls` 的归一化配套，避免缓存分裂
 *
 * ## `precision`
 *
 * `Precision.INEXACT` 允许 Coil 用比请求尺寸略大的缓存图，
 * 减少"尺寸不匹配就重新下载"的次数。
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
 * 按 `AGENTS.md` §5.2 的官方值固定：
 * - 底色 `#66000000`（alpha **40%**，令牌 [BiliTheme.colors.overlayCover]）
 * - 圆角 **2dp**（`Radius.badge`）
 * - 字号 **10sp**（`FontSize.badge`，官方 `T10`）
 *
 * 早期实现是 alpha 55% + 圆角 4dp + 12sp，三项都偏大偏重。
 */
@Composable
private fun DurationBadge(text: String, modifier: Modifier = Modifier) {
    val colors = BiliTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.badge))
            .background(colors.overlayCover)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.badge,
                color = colors.onOverlay,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
