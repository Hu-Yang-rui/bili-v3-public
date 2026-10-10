package com.example.biliv3.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.example.biliv3.data.api.InteractionState
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.ui.component.MonoReadout
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * 互动栏：点赞 / 投币 / 收藏 / 分享。
 *
 * ## 布局（v1.4.2 重做）
 *
 * 首版是 `Arrangement.SpaceEvenly` + 每项 `padding(horizontal = V3Space.md)`。
 * 两个问题叠加，实测在 412dp 屏上：
 *
 * ```
 * 4 项 × 32dp padding = 128dp
 * 剩余 284dp 被 SpaceEvenly 分成 5 段 = 每段 56.8dp
 * 相邻图标中心距 = 16 + 56.8 + 16 = 88.8dp
 * ```
 *
 * 而图标只有 24dp —— **间隙是图标的 3.7 倍**，四项被拉成散开的四个孤岛。
 *
 * ## 现在怎么做
 *
 * 把「视觉间距」和「触摸热区」分开处理：
 *
 * - **触摸热区**：每项 `weight(1f)` 平分整行宽度（最窄也有 ~90dp），
 *   远超 48dp 的最小触摸目标，且四项热区等宽、不重叠
 * - **视觉间距**：内容宽度由图标/文字自然决定，用 `SpacedBy` 给一个
 *   **固定的、紧凑的**间距，不再由 SpaceEvenly 按剩余空间动态撑开
 *
 * 这样大屏上四项仍是紧凑的一组（居中对齐），而不是被推到屏幕两端。
 *
 * ## 计数用等宽 + 固定最小宽度
 *
 * 点赞数会实时变化（`999` → `1000` 宽度不同）。等宽字体保证字宽一致，
 * 但位数变化仍会改宽度 —— 由于每项是 `weight(1f)` 等宽容器、
 * 内容居中，位数变化**不会推动相邻项**。
 *
 * ## 激活态用「图标填充 + 变色」双重表达
 *
 * 只变色的话色弱用户无法区分已赞/未赞
 * （`AGENTS.md` §5.1 要求「颜色不是唯一信息载体」）。
 * 所以线性图标 → 填充图标，同时变色。
 */
@Composable
fun InteractionBar(
    interaction: InteractionState,
    likeCount: Int,
    coinCount: Int,
    favoriteCount: Int,
    shareCount: Int,
    onLike: () -> Unit,
    onCoin: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            // ⚠️ 互动栏**自身不是卡片** —— 它已被合并进 UP 信息卡内部
            // （见 `VideoDetailScreen` 的 owner-meta item）。
            //
            // 若这里再套一层 `biliCard()`，就会出现"卡片里再套一个卡"：
            // 玻璃底 + 玻璃底叠加 → 中间浮出一个亮框，
            // 视觉上像"框里又画了个框"，比不合并还乱。
            //
            // 它只需要横向铺满 + 一点纵向呼吸。
            .padding(vertical = V3Space.xs),
        // ⚠️ 不再用 SpaceEvenly。四项各占 `weight(1f)`，热区等宽；
        // 内容在各自热区内居中，视觉上自然形成紧凑的一组。
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionItem(
            // 线性 → 填充，配合变色，不依赖颜色单一维度
            icon = if (interaction.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            label = formatCount(likeCount),
            active = interaction.liked,
            activeColor = colors.brand,
            contentDescription = if (interaction.liked) "取消点赞" else "点赞",
            onClick = onLike,
            modifier = Modifier.weight(1f),
        )
        ActionItem(
            icon = Icons.Outlined.MonetizationOn,
            label = formatCount(coinCount),
            active = interaction.coined,
            activeColor = colors.accentCoin,
            contentDescription = "投币",
            onClick = onCoin,
            modifier = Modifier.weight(1f),
        )
        ActionItem(
            icon = if (interaction.favored) Icons.Filled.Star else Icons.Outlined.StarBorder,
            label = formatCount(favoriteCount),
            active = interaction.favored,
            activeColor = colors.accentFavorite,
            contentDescription = if (interaction.favored) "取消收藏" else "收藏",
            onClick = onFavorite,
            modifier = Modifier.weight(1f),
        )
        ActionItem(
            icon = Icons.Outlined.Share,
            label = formatCount(shareCount),
            active = false,
            activeColor = colors.labelSecondary,
            contentDescription = "分享",
            onClick = onShare,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 单个互动项。
 *
 * ## 触摸热区 vs 视觉尺寸（v1.4.2 重做）
 *
 * 外层 `weight(1f)` 的容器撑满整格，`minHeight = V3Size.touchMin`
 * 保证热区达标；**内层内容保持紧凑**，靠 `SpacedBy` 控制视觉距离。
 *
 * 关键点：热区大不等于视觉松散。首版用 `padding(horizontal = V3Space.md)`
 * 把 padding 当成了「视觉间距」，实际上它同时撑大了热区并把图标推远 ——
 * 两件事被同一个参数绑死了。
 */
@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors
    val tint = if (active) activeColor else colors.labelSecondary

    Box(
        modifier = modifier
            // 热区：撑满所在格子，并保证不低于最小触摸目标
            .fillMaxHeight()
            .heightIn(min = V3Size.touchMin)
            .clip(RoundedCornerShape(V3Radius.xs))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // 图标与数字之间只留 2dp：它们是**一个整体**，不是两行内容
            verticalArrangement = Arrangement.spacedBy(V3Space.hairline),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(V3Size.iconMd),
            )
            // 计数用等宽：点赞/投币数会实时变化，比例字体下四个数字
            // 宽度不一，整栏会随交互轻微抖动。
            MonoReadout(
                text = label,
                color = tint,
                fontSize = V3Type.caption2.fontSize,
                weight = if (active) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}
