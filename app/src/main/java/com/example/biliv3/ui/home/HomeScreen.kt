package com.example.biliv3.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.biliv3.data.model.CategoryEntry
import com.example.biliv3.data.model.HomeData
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.component.ProvideShimmer
import com.example.biliv3.ui.component.SkeletonBox
import com.example.biliv3.ui.component.SkeletonGrid
import com.example.biliv3.ui.component.VideoCard

/**
 * 主页。
 *
 * ## 桌面布局（≥1280）
 *
 * ```
 * ┌─ 顶栏 64 ─────────────────────────────────────────┐
 * │ ┌── 主列（弹性）──────────┐ ┌── 右侧栏 288 ────┐ │
 * │ │ Banner 轮播             │ │ 热门榜单         │ │
 * │ │ 分区入口                │ │ 正在直播         │ │
 * │ │ 推荐 [换一换]           │ │ 话题活动         │ │
 * │ │ 视频网格 5 列           │ │ 公告             │ │
 * │ └────────────────────────┘ └─────────────────┘ │
 * ├─ 页脚 ────────────────────────────────────────────┤
 * └───────────────────────────────────────────────────┘
 * ```
 *
 * ## 平板 / 移动
 *
 * 右侧栏**整体下移**，作为网格内的全宽 item 插入。
 * 移动端额外有底部导航。
 *
 * ## 触底加载
 *
 * 距底 **800px** 触发（不是到底才触发），避免用户看到"卡住不动"。
 */
@Composable
fun HomeScreen(
    windowSize: WindowSize,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
    onVideoClick: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onCategoryClick: (CategoryEntry) -> Unit = {},
    onSeeRanking: () -> Unit = {},
    /** 顶栏铃铛 → 消息页。 */
    onMessageClick: () -> Unit = {},
    /** 顶栏头像 → 我的页。 */
    onProfileClick: () -> Unit = {},
    /** 顶栏导航项（桌面/平板）→ 对应路由。 */
    onNavItemClick: (String) -> Unit = {},
    /** 铃铛红点：是否有未读。 */
    hasUnread: Boolean = false,
    /** 页脚外链点击（用系统浏览器打开）。 */
    onOpenLink: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = BiliTheme.colors
    val snackbar = remember { SnackbarHostState() }

    // 当前选中的分区 Tab。默认「推荐」，与官方首页一致。
    var selectedCategory by remember { mutableStateOf(RECOMMEND_KEY) }

    // Tab 点击：先切选中态（视觉立刻响应），再把事件抛给调用方。
    // 分区页尚未实现，调用方默认什么都不做 —— 但**选中态会变**，
    // 不会出现"点了毫无反应"。接分区页时只需在 MainActivity 传
    // onCategoryClick，此处无需改动。
    val handleCategoryClick: (CategoryEntry) -> Unit = { entry ->
        selectedCategory = entry.key
        onCategoryClick(entry)
    }

    Scaffold(
        modifier = modifier,
        containerColor = colors.bgBase,
        // ⚠️ contentWindowInsets 置空：inset 由我们自己按栏位精确消费。
        // 否则 Scaffold 会把 inset 同时算进 innerPadding 和 topBar，
        // 出现双重留白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // MainActivity 调了 enableEdgeToEdge()，窗口内容会**铺到状态栏底下**。
            // Scaffold 的 innerPadding 只作用于内容区，topBar 自身不会自动避让 ——
            // 不显式消费 statusBars inset 的话，顶栏内容会和系统时钟/信号图标重叠。
            TopNav(
                windowSize = windowSize,
                onSearchClick = onSearchClick,
                onMessageClick = onMessageClick,
                onProfileClick = onProfileClick,
                onNavItemClick = onNavItemClick,
                hasUnread = hasUnread,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            )
        },
        bottomBar = {
            // ⚠️ 底栏**不在这里**。
            //
            // 底部导航要在首页/动态/我的三个 Tab 之间持续存在，所以它归
            // `MainShell`（外壳）持有。留在这里的话，切到"动态"底栏就没了。
            //
            // 这里曾经用 `onDisabledTap` 弹"不支持投稿"的 snackbar，
            // 但"投稿"项在上一轮已按 AGENTS.md §3.3 砍掉，该回调随之失效。
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val s = state) {
                // 骨架屏整体包一层 ProvideShimmer：整块只建一个微光动画，
                // 而不是每个 SkeletonBox 各建一个（见 Skeleton.kt 说明）
                is HomeUiState.Loading -> ProvideShimmer { HomeSkeleton(windowSize) }

                is HomeUiState.Empty -> EmptyState(
                    title = "还没有推荐内容",
                    description = "下拉刷新试试",
                    actionLabel = "重新加载",
                    onAction = viewModel::load,
                    modifier = Modifier.fillMaxSize(),
                )

                is HomeUiState.Error -> ErrorState(
                    title = "推荐流加载失败",
                    description = s.message,
                    onRetry = viewModel::load,
                    modifier = Modifier.fillMaxSize(),
                )

                is HomeUiState.Content -> if (windowSize.hasSideColumn) {
                    // 桌面：主列 + 右侧栏并排
                    DesktopLayout(
                        data = s.data,
                        loadingMore = s.loadingMore,
                        hasMore = s.hasMore,
                        windowSize = windowSize,
                        onVideoClick = onVideoClick,
                        onShuffle = viewModel::shuffle,
                        onLoadMore = viewModel::loadMore,
                        selectedCategory = selectedCategory,
                        onCategoryClick = handleCategoryClick,
                        onSeeRanking = onSeeRanking,
                        onOpenLink = onOpenLink,
                    )
                } else {
                    // 平板 / 移动：右侧栏下移
                    StackedLayout(
                        data = s.data,
                        loadingMore = s.loadingMore,
                        hasMore = s.hasMore,
                        windowSize = windowSize,
                        onVideoClick = onVideoClick,
                        onShuffle = viewModel::shuffle,
                        onLoadMore = viewModel::loadMore,
                        selectedCategory = selectedCategory,
                        onCategoryClick = handleCategoryClick,
                        onSeeRanking = onSeeRanking,
                        onOpenLink = onOpenLink,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 桌面：主列 + 右侧栏
// ---------------------------------------------------------------------------

@Composable
private fun DesktopLayout(
    data: HomeData,
    loadingMore: Boolean,
    hasMore: Boolean,
    windowSize: WindowSize,
    onVideoClick: (String) -> Unit,
    onShuffle: () -> Unit,
    onLoadMore: () -> Unit,
    selectedCategory: String,
    onCategoryClick: (CategoryEntry) -> Unit,
    onSeeRanking: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    TriggerLoadMore(gridState, onLoadMore)

    val pagePadding = pagePaddingFor(windowSize)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = pagePadding),
    ) {
        // ---- 主列 ----
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(windowSize.gridColumns),
            contentPadding = PaddingValues(top = Space.x2, bottom = Space.x8),
            horizontalArrangement = Arrangement.spacedBy(gridGutterFor(windowSize)),
            verticalArrangement = Arrangement.spacedBy(gridRowSpacingFor(windowSize)),
            modifier = Modifier.weight(1f),
        ) {
            MainColumnItems(
                data = data,
                loadingMore = loadingMore,
                hasMore = hasMore,
                windowSize = windowSize,
                onVideoClick = onVideoClick,
                onShuffle = onShuffle,
                includeSidePanel = false,
                selectedCategory = selectedCategory,
                onCategoryClick = onCategoryClick,
                onSeeRanking = onSeeRanking,
                onOpenLink = onOpenLink,
            )
        }

        Spacer(Modifier.width(Space.x6))

        // ---- 右侧栏（独立滚动，sticky 效果）----
        Column(
            modifier = Modifier
                .width(Sizes.sidePanel)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(top = Space.x5, bottom = Space.x8),
        ) {
            SidePanel(
                ranks = data.ranks,
                lives = data.lives,
                topics = data.topics,
                notices = data.notices,
                onVideoClick = onVideoClick,
                onSeeRanking = onSeeRanking,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 平板 / 移动：单列堆叠，右侧栏下移
// ---------------------------------------------------------------------------

@Composable
private fun StackedLayout(
    data: HomeData,
    loadingMore: Boolean,
    hasMore: Boolean,
    windowSize: WindowSize,
    onVideoClick: (String) -> Unit,
    onShuffle: () -> Unit,
    onLoadMore: () -> Unit,
    selectedCategory: String,
    onCategoryClick: (CategoryEntry) -> Unit,
    onSeeRanking: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    TriggerLoadMore(gridState, onLoadMore)

    val pagePadding = pagePaddingFor(windowSize)

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(windowSize.gridColumns),
        contentPadding = PaddingValues(
            start = pagePadding,
            end = pagePadding,
            // 顶栏本身已有底色分隔，这里不需要 20dp —— 收到 8dp
            top = Space.x2,
            bottom = Space.x8,
        ),
        horizontalArrangement = Arrangement.spacedBy(gridGutterFor(windowSize)),
        verticalArrangement = Arrangement.spacedBy(gridRowSpacingFor(windowSize)),
        modifier = Modifier.fillMaxSize(),
    ) {
        MainColumnItems(
            data = data,
            loadingMore = loadingMore,
            hasMore = hasMore,
            windowSize = windowSize,
            onVideoClick = onVideoClick,
            onShuffle = onShuffle,
            includeSidePanel = true,
            selectedCategory = selectedCategory,
            onCategoryClick = onCategoryClick,
            onSeeRanking = onSeeRanking,
            onOpenLink = onOpenLink,
        )
    }
}

// ---------------------------------------------------------------------------
// 主列内容（桌面与堆叠布局共用，靠 includeSidePanel 区分）
//
// ⚠️ 这里**不能**标 @Composable：LazyVerticalGrid 的 content lambda 是
// `LazyGridScope.() -> Unit`，不是 composable 上下文。
// 但 `item { }` 的 content **是** composable，所以在 item 内部可以正常调用组件。
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.MainColumnItems(
    data: HomeData,
    loadingMore: Boolean,
    hasMore: Boolean,
    windowSize: WindowSize,
    onVideoClick: (String) -> Unit,
    onShuffle: () -> Unit,
    includeSidePanel: Boolean,
    selectedCategory: String,
    onCategoryClick: (CategoryEntry) -> Unit,
    onSeeRanking: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val sectionSpacing = sectionSpacingFor(windowSize)

    // ---- Banner（空则不渲染，不留空白）----
    if (data.banners.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "banner") {
            Column {
                BannerCarousel(banners = data.banners, windowSize = windowSize)
                Spacer(Modifier.height(sectionSpacing))
            }
        }
    }

    // ---- 分区 Tab 条（44dp，官方结构）+ 换一换 ----
    // ⚠️ 这里**不再**另起一个「推荐」区块标题。
    // Tab 条首位已经是「推荐」，下面再来一个带粉色竖条的「推荐」是重复信息。
    // 「换一换」是有用的动作，不能跟着标题一起删 —— 移到 Tab 条右侧固定位，
    // 不随 Tab 横向滚动。
    item(span = { GridItemSpan(maxLineSpan) }, key = "categories") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryTabBar(
                categories = data.categories,
                selectedKey = selectedCategory,
                onCategoryClick = onCategoryClick,
                modifier = Modifier.weight(1f),
            )
            // ⚠️ 间距不能太小：Tab 条是横向可滚动的，最后一个可见 Tab
            // （实测「舞蹈」）会紧贴这里。用 x4(16dp) 把动作与 Tab 拉开，
            // 避免"换一换"看起来像又一个分区 Tab。
            Spacer(Modifier.width(Space.x4))
            ShuffleAction(onShuffle)
        }
    }

    // ---- 视频网格 ----
    // contentType 让 LazyGrid 复用同一类 item 的组合实例：
    // 没有它时，滚动过程中每个 item 都按"未知类型"处理，无法复用，
    // 进出屏幕会反复重新组合。列表越长、滚动越久，差异越明显。
    items(
        items = data.videos,
        key = { it.bvid },
        contentType = { "video" },
    ) { video ->
        VideoCard(video = video, onClick = { onVideoClick(video.bvid) })
    }

    // ---- 右侧栏下移（仅堆叠布局）----
    if (includeSidePanel && hasSideContent(data)) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "side-panel-inline") {
            Column {
                Spacer(Modifier.height(sectionSpacing))
                SidePanel(
                    ranks = data.ranks,
                    lives = data.lives,
                    topics = data.topics,
                    notices = data.notices,
                    onVideoClick = onVideoClick,
                    onSeeRanking = onSeeRanking,
                )
            }
        }
    }

    // ---- 加载更多 / 没有更多 ----
    item(span = { GridItemSpan(maxLineSpan) }, key = "load-more") {
        val colors = BiliTheme.colors
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
                    modifier = Modifier.size(24.dp),
                )
                !hasMore -> Text(
                    text = "没有更多了",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = colors.textSecondary,
                    ),
                )
            }
        }
    }

    // ---- 页脚 ----
    item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
        Footer(windowSize = windowSize, onOpenLink = onOpenLink)
    }
}

/**
 * 「换一换」动作。
 *
 * 原先是「推荐」区块标题的右侧动作，标题删掉后移到这里 ——
 * Tab 条右侧固定位（不随 Tab 横向滚动）。
 *
 * ⚠️ 加了**弱化底 + 图标**，明确区分于分区 Tab：
 * Tab 是纯文字，这个是一个可点的胶囊按钮。不加区分的话，
 * 它紧跟在「舞蹈」后面看起来就像第 7 个分区。
 */
@Composable
private fun ShuffleAction(onClick: () -> Unit) {
    val colors = BiliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.bgHover)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.x1 + 2.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            tint = colors.textSecondarySafe,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Space.x1))
        Text(
            text = "换一换",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = FontSize.bodySm,
                color = colors.textSecondarySafe,
            ),
            maxLines = 1,
        )
    }
}

/**
 * 触底加载触发器。
 *
 * 距底 **800px** 触发。用 6 个 item 的余量近似 800px
 * （单卡高度约 200–260px，6 张 ≈ 1200px，留出足够提前量）。
 */
@Composable
private fun TriggerLoadMore(
    gridState: LazyGridState,
    onLoadMore: () -> Unit,
) {
    val shouldLoad by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val total = info.totalItemsCount
            total > 0 && last.index >= total - 6
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { shouldLoad }.collect { if (it) onLoadMore() }
    }
}

/**
 * 首屏骨架。
 *
 * 与真实布局**同构**：Banner → 分区 → 网格。
 * 顶栏由 Scaffold 渲染，这里只补内容区。
 */
@Composable
private fun HomeSkeleton(windowSize: WindowSize) {
    val pagePadding = pagePaddingFor(windowSize)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = pagePadding, vertical = Space.x5),
        verticalArrangement = Arrangement.spacedBy(sectionSpacingFor(windowSize)),
    ) {
        SkeletonBox(
            modifier = Modifier.fillMaxWidth(),
            height = when (windowSize) {
                WindowSize.Desktop -> Sizes.bannerDesktop
                WindowSize.Tablet -> Sizes.bannerTablet
                WindowSize.Mobile -> Sizes.bannerMobile
            },
            shape = RoundedCornerShape(Radius.card),
        )
        SkeletonBox(
            modifier = Modifier.fillMaxWidth(),
            height = 104.dp,
            shape = RoundedCornerShape(Radius.card),
        )
        SkeletonGrid(
            columns = windowSize.gridColumns,
            gutter = gridGutterFor(windowSize),
            rowSpacing = gridRowSpacingFor(windowSize),
            pagePadding = 0.dp,
            rows = 2,
        )
    }
}

/** 右侧栏是否有任何内容。全空时整块不渲染。 */
private fun hasSideContent(data: HomeData): Boolean =
    data.ranks.isNotEmpty() ||
        data.lives.isNotEmpty() ||
        data.topics.isNotEmpty() ||
        data.notices.isNotEmpty()

/**
 * 「推荐」Tab 的 key。
 *
 * 官方首页第一个 Tab 是「推荐」（对应推荐流），后面才是各分区。
 * 必须与 [CategoryTabBar] 的 `RECOMMEND_TAB_KEY` 一致。
 */
private const val RECOMMEND_KEY = RECOMMEND_TAB_KEY
