package com.example.biliv3.ui.ranking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.biliv3.data.RankingTab
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState

/**
 * 排行榜页。
 *
 * ## 布局
 *
 * ```
 * [←] 排行榜
 * ─────────────────────────────
 * 全站  动画  音乐  游戏  知识 …   ← 横向滚动 Tab
 * ─────────────────────────────
 * 1  [封面] 标题
 *           UP名 · 123.4万播放
 * 2  ...
 * ```
 *
 * ## 名次徽标
 *
 * 前三名用主题色高亮（1=品牌粉 / 2=橙 / 3=黄），4 名以后用中性灰。
 * 颜色不是唯一信息载体 —— 数字本身就在，所以色盲用户也能读懂。
 */
@Composable
fun RankingScreen(
    tabs: List<RankingTab>,
    selectedRid: Int,
    videos: List<VideoItem>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .biliCard(
                    elevation = 0.dp,
                    shape = RoundedCornerShape(
                        bottomStart = Radius.card,
                        bottomEnd = Radius.card,
                    ),
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(Sizes.topBarMobile)
                .padding(horizontal = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(Sizes.iconXl),
                )
            }
            Spacer(Modifier.width(Space.x1))
            Text(
                text = "排行榜",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
        }

        // ---- 分区 Tab ----
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgCard),
            contentPadding = PaddingValues(horizontal = Space.x4),
            horizontalArrangement = Arrangement.spacedBy(Space.x5),
        ) {
            items(tabs, key = { it.rid }) { tab ->
                val selected = tab.rid == selectedRid
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelectTab(tab.rid) }
                        .padding(vertical = Space.x3),
                ) {
                    Text(
                        text = tab.name,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.body,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) colors.textPrimary else colors.textSecondarySafe,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(Radius.badge))
                            .background(
                                if (selected) colors.brandPrimary else androidx.compose.ui.graphics.Color.Transparent,
                            ),
                    )
                }
            }
        }

        // ---- 内容 ----
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.brandPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(Sizes.iconXl * 1.5f),
                )
            }

            error != null -> ErrorState(
                title = "排行榜加载失败",
                description = error,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )

            videos.isEmpty() -> EmptyState(
                title = "这个分区暂时没有榜单",
                description = "换个分区看看",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = Space.x2),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(videos, key = { _, v -> v.bvid }) { index, v ->
                    RankRow(rank = index + 1, video = v, onClick = { onVideoClick(v.bvid) })
                }
            }
        }
    }
}

/** 单条榜单。 */
@Composable
private fun RankRow(rank: Int, video: VideoItem, onClick: () -> Unit) {
    val colors = BiliTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // C 方案：每条榜单是独立卡片
            .padding(horizontal = Space.x3, vertical = Space.x1)
            .biliCard(shape = RoundedCornerShape(Radius.card))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 名次：前三名高亮
        Box(
            modifier = Modifier.width(RANK_BADGE_WIDTH),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = if (rank <= 3) FontSize.titleMd else FontSize.body,
                    fontWeight = if (rank <= 3) FontWeight.Bold else FontWeight.Normal,
                    color = when (rank) {
                        1 -> colors.rankFirst
                        2 -> colors.rankSecond
                        3 -> colors.rankThird
                        else -> colors.textTertiary
                    },
                ),
            )
        }
        Spacer(Modifier.width(Space.x2))

        // 封面
        Box(
            modifier = Modifier
                .width(RANK_THUMB_WIDTH)
                .height(RANK_THUMB_HEIGHT)
                .clip(RoundedCornerShape(Radius.cover))
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = video.coverUrl(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(Space.x3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.x1))
            Text(
                text = "${video.authorName} · ${formatCount(video.playCount)} 播放",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 名次列宽。 */
private val RANK_BADGE_WIDTH = 28.dp

/**
 * 榜单缩略图。
 *
 * ⚠️ C 方案：128×72（16:9）→ **128×80（16:10）**
 *
 * 与 `VideoCard` 封面比例统一。首版这里和首页封面形状不同，
 * 同一支视频在榜单和在首页看起来"不一样高"。
 */
private val RANK_THUMB_WIDTH = 128.dp
private val RANK_THUMB_HEIGHT = 80.dp
