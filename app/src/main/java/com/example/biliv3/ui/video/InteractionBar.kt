package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.biliv3.data.api.InteractionState
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.ui.component.MonoReadout
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 互动栏：点赞 / 投币 / 收藏 / 分享。
 *
 * ## 布局取舍
 *
 * 四项**等宽平分一行**，而不是做成悬浮大按钮组 ——
 * 详情页纵向空间紧张（用户明确要求「精简、紧凑」），
 * 一行 48dp 是性价比最高的形式。
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
    val colors = BiliTheme.colors

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
            .padding(vertical = Space.x2),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionItem(
            // 线性 → 填充，配合变色，不依赖颜色单一维度
            icon = if (interaction.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            label = formatCount(likeCount),
            active = interaction.liked,
            activeColor = colors.brandPrimary,
            contentDescription = if (interaction.liked) "取消点赞" else "点赞",
            onClick = onLike,
        )
        ActionItem(
            icon = Icons.Outlined.MonetizationOn,
            label = formatCount(coinCount),
            active = interaction.coined,
            activeColor = colors.accentCoin,
            contentDescription = "投币",
            onClick = onCoin,
        )
        ActionItem(
            icon = if (interaction.favored) Icons.Filled.Star else Icons.Outlined.StarBorder,
            label = formatCount(favoriteCount),
            active = interaction.favored,
            activeColor = colors.accentFavorite,
            contentDescription = if (interaction.favored) "取消收藏" else "收藏",
            onClick = onFavorite,
        )
        ActionItem(
            icon = Icons.Outlined.Share,
            label = formatCount(shareCount),
            active = false,
            activeColor = colors.textSecondarySafe,
            contentDescription = "分享",
            onClick = onShare,
        )
    }
}

/**
 * 单个互动项。
 *
 * 触摸目标整块约 48dp 高（`Sizes.minTouchTarget` 量级），
 * 图标本身 20dp —— 视觉紧凑但点得中。
 */
@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    val tint = if (active) activeColor else colors.textSecondarySafe

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // ⚠️ 首版写的是 `RoundedCornerShape(Space.x2)` —— **用间距令牌当圆角**。
            // 值恰好都是 8dp 所以看不出问题，但语义完全错：
            // 哪天 `Space.x2` 从 8 改成 10，这里会跟着变成一个奇怪的圆角。
            .clip(RoundedCornerShape(Radius.button))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x4, vertical = Space.x2),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(Sizes.iconXl),
        )
        Spacer(Modifier.height(2.dp))
        // 计数用等宽：点赞/投币数会实时变化，比例字体下四个数字
        // 宽度不一，整栏会随交互轻微抖动。
        MonoReadout(
            text = label,
            color = tint,
            fontSize = FontSize.badge,
            weight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
