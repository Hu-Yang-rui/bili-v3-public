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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.component.VideoCard
import com.example.biliv3.ui.home.gridGutterFor
import com.example.biliv3.ui.home.gridRowSpacingFor
import com.example.biliv3.ui.home.pagePaddingFor
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

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
    val colors = BiliV3.colors
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val sortLatest by viewModel.sortLatest.collectAsStateWithLifecycle()
    val loadError by viewModel.error.collectAsStateWithLifecycle()

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
            .background(colors.bgPrimary),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不再是卡片：与页面同明度，只留底边一条发丝线
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(V3Size.topBar)
                .padding(horizontal = V3Space.xs),
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
                text = title.ifEmpty { "分区" },
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ---- 排序切换 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
            horizontalArrangement = Arrangement.spacedBy(V3Space.sm),
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

            loadError != null -> ErrorState(
                title = "分区内容加载失败",
                description = loadError,
                onRetry = { viewModel.retry() },
                modifier = Modifier.fillMaxSize(),
            )

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
                    top = V3Space.xxs,
                    bottom = V3Space.xxl,
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
                                text = if (sortLatest) "没有更多了" else "热门榜只有这一页",
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

/** 排序胶囊。选中态用底色 + 加粗双重表达。 */
@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    Text(
        text = label,
        style = V3Type.caption1.copy(
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.brandBiliText else colors.labelSecondary,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(V3Radius.pill))
            .background(if (selected) colors.brandDim else colors.bgTertiary)
            .clickable(onClick = onClick)
            .padding(horizontal = V3Space.md, vertical = V3Space.xs),
    )
}
