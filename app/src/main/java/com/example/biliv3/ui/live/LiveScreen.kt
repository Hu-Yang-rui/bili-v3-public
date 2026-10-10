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
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.home.gridGutterFor
import com.example.biliv3.ui.home.gridRowSpacingFor
import com.example.biliv3.ui.home.pagePaddingFor
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * 直播列表页。
 *
 * ## 从"完全没有"到有列表
 *
 * 首版 `Endpoints.LIVE_LIST` 常量定义了却从未调用，
 * 首页右侧栏的「正在直播」模块恒为空。这里把它接上。
 *
 * ## 点击行为：进**应用内**直播间播放（v1.6.3 变更）
 *
 * 此前点击打开系统浏览器（当时缺 HLS/FLV 依赖，硬做会黑屏）。
 * v1.6.3 补上 `media3-exoplayer-hls` 后改为站内播放 ——
 * 实测接口确实返回可播的 HLS 清单，见 `LiveViewModel` 的范围说明。
 *
 * 因此卡片右上角**不再**标注"官方页"角标（那个角标是
 * "点进去会离开应用"的提示，现在不成立了）。
 */
@Composable
fun LiveScreen(
    windowSize: WindowSize,
    onBack: () -> Unit,
    onOpenRoom: (LiveRoom) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveViewModel,
) {
    val colors = BiliV3.colors
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val loadError by viewModel.error.collectAsStateWithLifecycle()

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
            .background(colors.bgPrimary),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不再是卡片：与页面同明度，只留底边一条发丝线
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(V3Size.topBar)
                .padding(horizontal = V3Space.topBarMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(V3Size.touchMin)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.labelPrimary,
                    modifier = Modifier.size(V3Size.iconLg),
                )
            }
            Spacer(Modifier.width(V3Space.xxs))
            Text(
                text = "直播",
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
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

            loadError != null -> ErrorState(
                title = "直播列表加载失败",
                description = loadError,
                onRetry = { viewModel.retry() },
                modifier = Modifier.fillMaxSize(),
            )

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
                    top = V3Space.xs,
                    bottom = V3Space.xxl,
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
                            .padding(vertical = V3Space.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            loadingMore -> CircularProgressIndicator(
                                color = colors.brand,
                                strokeWidth = V3Space.progressTrack,
                                modifier = Modifier.size(V3Size.iconLg),
                            )
                            !hasMore -> Text(
                                text = "没有更多了",
                                style = V3Type.caption1.copy(
                                    color = colors.labelTertiary,
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
    val colors = BiliV3.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
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
                    .padding(V3Space.xs)
                    .clip(RoundedCornerShape(V3Radius.xs))
                    .background(colors.stateLive)
                    // ⚠️ 用令牌而不是 `5.dp` / `1.dp`（v1.2.4）：
                    // 同一种「直播中」角标在 `SidePanel` 用的是
                    // `tagHorizontal`(4) + `tagVertical`(1)，这里却是 5+1 ——
                    // 两处差 1dp，肉眼看不出来但属于**同一语义两套值**。
                    // 统一到令牌，右侧是唯一定义处。
                    .padding(horizontal = V3Space.tagHorizontal, vertical = V3Space.tagVertical),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "直播中",
                    style = V3Type.caption2.copy(
                        color = colors.labelOnMedia,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            // 人气：右下角
            if (room.online > 0) {
                Text(
                    text = "${formatCount(room.online)} 人气",
                    style = V3Type.caption2.copy(
                        color = colors.labelOnMedia,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(V3Space.xs)
                        .clip(RoundedCornerShape(V3Radius.xs))
                        .background(colors.overlay)
                        .padding(horizontal = V3Space.tagHorizontal, vertical = V3Space.tagVertical),
                )
            }
        }

        Spacer(Modifier.height(V3Space.xs))

        Text(
            text = room.title,
            style = V3Type.footnote.copy(
                color = colors.labelPrimary,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(V3Space.hairline))

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
            Spacer(Modifier.width(V3Space.xxs))
            Text(
                text = room.uname,
                style = V3Type.caption2.copy(
                    color = colors.labelSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
