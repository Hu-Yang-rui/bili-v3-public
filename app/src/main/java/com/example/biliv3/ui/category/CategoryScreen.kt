package com.example.biliv3.ui.category

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.VideoCard
import com.example.biliv3.ui.home.gridGutterFor
import com.example.biliv3.ui.home.gridRowSpacingFor
import com.example.biliv3.ui.home.pagePaddingFor

/**
 * 分区页。
 *
 * ## 从占位页到真实页面
 *
 * 首版这里是 `PlaceholderScreen(title = name, detail = "rid = $rid\n\n
 * 分区列表将在后续阶段接入")` —— 首页 12 个分区入口点进去**全是这张图**。
 *
 * ## 结构
 *
 * ```
 * [← 动画]
 * [最新] [热门]                    ← 两档排序
 * ────────────────────────────────
 * 视频网格（2 列 / 3 列 / 5 列按断点）
 * ```
 *
 * 两档排序都是真实数据源（见 `CategoryViewModel` 的说明），
 * 不做"看起来能筛但点了没反应"的下拉。
 */
@Composable
fun CategoryScreen(
    title: String,
    windowSize: WindowSize,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoryViewModel,
) {
    val colors = BiliTheme.colors
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val sortLatest by viewModel.sortLatest.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState()

    val atBottom by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last.index >= total - 6
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { atBottom }.collect { if (it) viewModel.loadMore() }
    }

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
                text = title.ifEmpty { "分区" },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ---- 排序切换 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.x3, vertical = Space.x2),
            horizontalArrangement = Arrangement.spacedBy(Space.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortChip("最新", sortLatest) { viewModel.setSortLatest(true) }
            SortChip("热门", !sortLatest) { viewModel.setSortLatest(false) }
        }

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // 终端风加载态：`$ 正在加载分区…  ▌`
                //
                // 比转圈更好的一点：**光标承担真实语义** ——
                // 慢网络下静态文字让人怀疑卡死，跳动的方块能消除疑虑。
                // 且不占用大面积（转圈在空页面上很"空"）。
                com.example.biliv3.ui.component.TerminalLoadingState(
                    text = "正在加载分区…",
                )
            }

            videos.isEmpty() -> EmptyState(
                title = "这个分区暂时没有内容",
                // 终端风：列表为空的次要状态，轻量提示符行
                terminalStyle = true,
                description = "换个档位或稍后再试",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(windowSize.gridColumns),
                contentPadding = PaddingValues(
                    start = pagePaddingFor(windowSize),
                    end = pagePaddingFor(windowSize),
                    top = Space.x1,
                    bottom = Space.x8,
                ),
                horizontalArrangement = Arrangement.spacedBy(gridGutterFor(windowSize)),
                verticalArrangement = Arrangement.spacedBy(gridRowSpacingFor(windowSize)),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(videos, key = { it.bvid }, contentType = { "video" }) { v ->
                    VideoCard(video = v, onClick = { onVideoClick(v.bvid) })
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
                                text = if (sortLatest) "没有更多了" else "热门榜只有这一页",
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

/** 排序胶囊。选中态用底色 + 加粗双重表达。 */
@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.textBrandSafe else colors.textSecondarySafe,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (selected) colors.brandPrimaryDim else colors.bgHover)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x4, vertical = Space.x2),
    )
}
