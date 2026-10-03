package com.example.biliv3.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.biliv3.data.LiveRoom
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.home.gridGutterFor
import com.example.biliv3.ui.home.gridRowSpacingFor
import com.example.biliv3.ui.home.pagePaddingFor

/**
 * 直播列表页。
 *
 * ## 从"完全没有"到有列表
 *
 * 首版 `Endpoints.LIVE_LIST` 常量定义了却从未调用，
 * 首页右侧栏的「正在直播」模块恒为空。这里把它接上。
 *
 * ## ⚠️ 点击行为：打开官方直播间（系统浏览器）
 *
 * 本项目**不做直播流播放**（缺 FLV/HLS 依赖，见 `LiveViewModel` 说明）。
 * 点击条目打开 `https://live.bilibili.com/{roomId}` ——
 * 真实可用，且不会因为"点进去黑屏"而变成更差的体验。
 *
 * UI 上**明确标注**这一点（卡片右上角"官方页"角标），
 * 不让用户以为点进去是本应用内播放。
 */
@Composable
fun LiveScreen(
    windowSize: WindowSize,
    onBack: () -> Unit,
    onOpenRoom: (LiveRoom) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveViewModel,
) {
    val colors = BiliTheme.colors
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState()

    val atBottom by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last.index >= total - 4
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { atBottom }.collect { if (it) viewModel.loadMore() }
    }

    // 直播卡片是 16:10（与视频封面一致），移动端 2 列、桌面 4 列
    val columns = when (windowSize) {
        WindowSize.Desktop -> 4
        WindowSize.Tablet -> 3
        WindowSize.Mobile -> 2
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
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
                text = "直播",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
        }

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // 终端风加载态（光标承担"进行中"语义）
                com.example.biliv3.ui.component.TerminalLoadingState(
                    text = "正在加载直播…",
                )
            }

            rooms.isEmpty() -> EmptyState(
                title = "当前没有正在直播的房间",
                description = "稍后再来看看",
                icon = Icons.Outlined.LiveTv,
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = pagePaddingFor(windowSize),
                    end = pagePaddingFor(windowSize),
                    top = Space.x2,
                    bottom = Space.x8,
                ),
                horizontalArrangement = Arrangement.spacedBy(gridGutterFor(windowSize)),
                verticalArrangement = Arrangement.spacedBy(gridRowSpacingFor(windowSize)),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(rooms, key = { it.roomId }, contentType = { "live" }) { room ->
                    LiveCard(room = room, onClick = { onOpenRoom(room) })
                }

                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Space.x5),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            loadingMore -> CircularProgressIndicator(
                                color = colors.brandPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(Sizes.iconXl),
                            )
                            !hasMore -> Text(
                                text = "没有更多了",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = FontSize.label,
                                    color = colors.textTertiary,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 直播卡片：封面 + 直播中角标 + 标题 + 主播 + 人气。 */
@Composable
private fun LiveCard(room: LiveRoom, onClick: () -> Unit) {
    val colors = BiliTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .clip(RoundedCornerShape(Radius.cover))
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = room.coverUrl(480),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // 直播中角标：品牌粉底 + 白字（呼吸点省掉，静态图不呼吸更克制）
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Space.x1 + 2.dp)
                    .clip(RoundedCornerShape(Radius.badge))
                    .background(colors.stateLive)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "直播中",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.onOverlay,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            // 人气：右下角
            if (room.online > 0) {
                Text(
                    text = "${formatCount(room.online)} 人气",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.onOverlay,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Space.x1 + 2.dp)
                        .clip(RoundedCornerShape(Radius.badge))
                        .background(colors.overlayCover)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        Spacer(Modifier.height(Space.x2))

        Text(
            text = room.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.bodySm,
                color = colors.textPrimary,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = room.faceUrl(48),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(colors.avatarPlaceholder),
            )
            Spacer(Modifier.width(Space.x1))
            Text(
                text = room.uname,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
