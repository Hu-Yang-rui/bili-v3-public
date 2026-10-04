package com.example.biliv3.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.biliv3.data.model.LiveItem
import com.example.biliv3.data.model.NoticeItem
import com.example.biliv3.data.model.RankItem
import com.example.biliv3.data.model.TopicItem
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.design.ruleTop
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 右侧辅助栏。
 *
 * ## 四块内容
 *
 * 1. 热门榜单
 * 2. 正在直播
 * 3. 话题活动
 * 4. 公告
 *
 * ## ⚠️ 空数据规则
 *
 * **任一模块为空 → 该白卡整块不渲染**（不留空壳、不显示"暂无数据"占位）。
 * 这是"不做假数据"原则的具体落地：让"没接上"和"真的没有"可区分。
 *
 * 桌面端右侧固定 288dp；平板/移动端由调用方把这块整体下移。
 */
@Composable
fun SidePanel(
    ranks: List<RankItem>,
    lives: List<LiveItem>,
    topics: List<TopicItem>,
    notices: List<NoticeItem>,
    modifier: Modifier = Modifier,
    onVideoClick: (String) -> Unit = {},
    onSeeRanking: (() -> Unit)? = null,
    onLiveClick: (LiveItem) -> Unit = {},
    onTopicClick: (TopicItem) -> Unit = {},
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Space.x4),
    ) {
        if (ranks.isNotEmpty()) {
            RankPanel(ranks = ranks, onVideoClick = onVideoClick, onSeeAll = onSeeRanking)
        }
        if (lives.isNotEmpty()) {
            LivePanel(lives = lives, onLiveClick = onLiveClick)
        }
        if (topics.isNotEmpty()) {
            TopicPanel(topics = topics, onTopicClick = onTopicClick)
        }
        if (notices.isNotEmpty()) {
            NoticePanel(notices = notices)
        }
    }
}

/** 白卡外壳：统一圆角、分层、内边距。 */
@Composable
private fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 🔴 乙·质感：侧栏区块**不再是卡片**。
            // 靠上边一条发丝线与上方区块分隔，内容直接排。
            .ruleTop(color = Rule.subtle)
            .padding(top = Rhythm.between),
    ) {
        content()
    }
}

/** 白卡标题行：图标 + 标题 + 右侧动作。 */
@Composable
private fun PanelHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = BiliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Space.x3),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.brandPrimary,
                modifier = Modifier.size(Sizes.iconLg),
            )
            Spacer(Modifier.width(Space.compactHorizontal))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = FontSize.titleLg,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            ),
        )
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textSecondarySafe,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.badge))
                    .clickable(onClick = onAction)
                    .padding(horizontal = Space.x1, vertical = Space.compactVertical),
            )
        }
    }
}

/**
 * 热门榜单。
 *
 * 前三名序号用品牌色系（粉/橙/黄），第 4 名起用中性灰 ——
 * 这是"颜色不是唯一信息载体"的正确用法：名次本身还有数字。
 */
@Composable
private fun RankPanel(
    ranks: List<RankItem>,
    onVideoClick: (String) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    val colors = BiliTheme.colors

    PanelCard {
        Column {
            PanelHeader(
                title = "热门榜单",
                icon = Icons.Filled.LocalFireDepartment,
                // 侧栏只显示前 10 条；给一个进入完整排行榜页的入口，
                // 否则"更多榜单"没有可达路径。
                actionLabel = if (onSeeAll != null) "完整榜单" else null,
                onAction = onSeeAll,
            )
            ranks.take(10).forEach { item ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.badge))
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onVideoClick(item.bvid) }
                        .background(if (hovered) colors.bgHover else Color.Transparent)
                        .padding(horizontal = Space.x1, vertical = Space.rowVertical),
                ) {
                    // 序号
                    Text(
                        text = item.rank.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = FontSize.titleMd,
                            fontWeight = FontWeight.Bold,
                            color = when (item.rank) {
                                1 -> colors.rankFirst
                                2 -> colors.rankSecond
                                3 -> colors.rankThird
                                else -> colors.textSecondary
                            },
                        ),
                        modifier = Modifier.width(22.dp),
                    )
                    Spacer(Modifier.width(Space.x2))
                    // 标题
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = if (hovered) colors.textBrandSafe else colors.textPrimary,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Space.x2))
                    // 热度
                    Text(
                        text = formatCount(item.hotScore.toInt()),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            color = colors.textSecondary,
                        ),
                    )
                }
            }
        }
    }
}

/** 正在直播。 */
@Composable
private fun LivePanel(
    lives: List<LiveItem>,
    onLiveClick: (LiveItem) -> Unit,
) {
    val colors = BiliTheme.colors

    PanelCard {
        Column {
            PanelHeader(title = "正在直播", actionLabel = "更多 ›")
            lives.take(3).forEach { item ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 列表项 + 封面：一律直角（§5.1 硬规则 2）。
                        // `Radius.thumb` 是卡片时代的兼容别名，已随重构废弃。
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onLiveClick(item) }
                        .background(if (hovered) colors.bgHover else Color.Transparent)
                        .padding(Space.x1),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = Sizes.liveThumbWidth, height = Sizes.liveThumbHeight)
                            .background(colors.skeletonBase),
                    ) {
                        AsyncImage(
                            model = item.coverUrl(),
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        )
                        // 直播中角标：品牌粉底 + 白字 + 呼吸圆点
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(Space.x1)
                                .clip(RoundedCornerShape(Radius.badge))
                                .background(colors.stateLive)
                                .padding(horizontal = Space.tagHorizontal, vertical = Space.tagVertical),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(Sizes.dotSm)
                                    .clip(CircleShape)
                                    .background(colors.onOverlay),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "直播中",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    color = colors.onOverlay,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.width(Space.x2))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.anchorName,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = FontSize.bodySm,
                                color = if (hovered) colors.textBrandSafe else colors.textPrimary,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Space.x1))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Visibility,
                                contentDescription = "观看人数",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(Sizes.iconSm - 2.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = formatCount(item.online),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = FontSize.label,
                                    color = colors.textSecondary,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 话题活动。2 列小卡。 */
@Composable
private fun TopicPanel(
    topics: List<TopicItem>,
    onTopicClick: (TopicItem) -> Unit,
) {
    val colors = BiliTheme.colors

    PanelCard {
        Column {
            PanelHeader(
                title = "话题活动",
                icon = Icons.Filled.Campaign,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Space.x3)) {
                topics.take(4).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
                        row.forEach { item ->
                            TopicCell(
                                item = item,
                                onClick = { onTopicClick(item) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicCell(
    item: TopicItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Column(
        modifier = modifier
            // 封面是图片 → 直角（§5.1 硬规则 2）
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(colors.skeletonBase),
        ) {
            AsyncImage(
                model = item.coverUrl(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        }
        Spacer(Modifier.height(Space.compactHorizontal))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = if (hovered) colors.textBrandSafe else colors.textPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${formatCount(item.joinCount)}人参与",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                color = colors.textSecondary,
            ),
            maxLines = 1,
        )
    }
}

/** 公告。 */
@Composable
private fun NoticePanel(notices: List<NoticeItem>) {
    val colors = BiliTheme.colors

    PanelCard {
        Column {
            PanelHeader(title = "公告")
            notices.take(5).forEach { item ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Space.compactHorizontal),
                ) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = colors.textSecondary,
                        ),
                    )
                    Spacer(Modifier.width(Space.x2))
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.textSecondarySafe,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
