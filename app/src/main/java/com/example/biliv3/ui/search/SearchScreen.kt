package com.example.biliv3.ui.search

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.component.HideKeyboardOnDispose
import com.example.biliv3.ui.component.ProvideShimmer
import com.example.biliv3.ui.component.SkeletonVideoCard
import com.example.biliv3.ui.component.VideoCard
import com.example.biliv3.ui.component.hideImeNow
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type
import com.example.biliv3.design.v3.V3ContentRow
import com.example.biliv3.design.v3.V3SectionTitle

/**
 * 搜索页。
 *
 * ## 结构
 *
 * ```
 * [←] [ 搜索框            ✕ ]      ← 吸顶，自动聚焦
 * ─────────────────────────────
 * 未输入：搜索历史 + 热搜
 * 输入中：联想词列表
 * 已搜索：结果列表（触底加载）
 * ```
 *
 * ## 自动聚焦
 *
 * 进入页面立刻弹键盘。用户点搜索框的目的就是输入，
 * 让他再点一次输入框是多余的一步（`AGENTS.md` §3.3）。
 */
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onVideoClick: (String) -> Unit = {},
    viewModel: SearchViewModel = viewModel(
        factory = SearchViewModelFactory(LocalContext.current),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val hotSearch by viewModel.hotSearch.collectAsStateWithLifecycle()
    val colors = BiliV3.colors

    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    /**
     * 收键盘的统一动作。
     *
     * ## 为什么不是只调 `keyboard?.hide()`
     *
     * 之前「返回」只做了 `navController.popBackStack()`，没有任何收键盘动作，
     * 于是键盘跟着退场动画一起消失 —— 实测残留约 1 秒。
     *
     * 现在三步一起做（见 `ui/component/Keyboard.kt` 的说明）：
     * 1. **清焦点**（关键）—— 焦点还在时 IME 认为仍有人在输入，
     *    不急着收；清掉焦点后 IME 连接自然断开。
     * 2. `keyboard.hide()` —— Compose 通道，兼容性兜底。
     * 3. 立即执行，**不等导航动画** —— 在 `popBackStack()` 之前调用，
     *    保证收起动作先于页面退场。
     */
    val dismissKeyboard: () -> Unit = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        hideImeNow(context)
    }

    // 兜底：万一走了系统返回手势/其他路径没经过 dismissKeyboard，
    // 页面离开组合时也收一次，保证不残留。
    HideKeyboardOnDispose()

    /**
     * 自动聚焦输入框 —— **只在首次进入时做一次**（v1.4.2 修）。
     *
     * ## 根因：`LaunchedEffect(Unit)` 会在每次回到本页时重跑
     *
     * 首版是 `LaunchedEffect(Unit) { focusRequester.requestFocus() }`。
     * `Unit` 作 key 只在**本 Composable 首次进入组合**时执行一次 ——
     * 但「搜索 → 点视频 → 返回」时，搜索页会被 NavHost **重新组合**
     * （返回栈里它仍在，但组合被销毁重建），于是这段又跑了一遍，
     * 键盘**自动弹回来**。用户的感受是「返回搜索页莫名弹出键盘」。
     *
     * ## 为什么用 ViewModel 存标记而不是 `remember`
     *
     * `remember` 与组合同生命周期 —— 组合被重建时它一起没了，
     * 挡不住这个问题。标记必须活在**比组合更长**的地方，
     * 也就是 ViewModel（它跨返回栈存活）。
     *
     * 语义上这也是对的：**"用户已经见过这个页面了"是页面级状态，
     * 不是某一次组合的状态**。
     */
    LaunchedEffect(Unit) {
        if (!viewModel.autoFocusConsumed) {
            viewModel.autoFocusConsumed = true
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPrimary),
    ) {
        // ---- 搜索栏 ----
        // 通栏：不再是卡片，内容直接排。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(V3Size.topBar)
                .padding(horizontal = V3Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(V3Size.touchMin)
                    .clip(CircleShape)
                    .clickable {
                        // ⚠️ 顺序很重要：先收键盘，再退出页面。
                        // 反过来的话键盘会跟着退场动画一起走，留下约 1 秒残留。
                        dismissKeyboard()
                        onBack()
                    },
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

            SearchField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                onSearch = {
                    dismissKeyboard()
                    viewModel.search(query)
                },
                onClear = { viewModel.clearQuery() },
                focusRequester = focusRequester,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(V3Space.xs))
        }

        when (val s = state) {
            is SearchUiState.Idle -> IdlePanel(
                history = history,
                hotSearch = hotSearch,
                onPick = { keyword ->
                    dismissKeyboard()
                    viewModel.search(keyword)
                },
                onRemoveHistory = viewModel::removeHistory,
                onClearHistory = viewModel::clearHistory,
            )

            is SearchUiState.Suggesting -> SuggestPanel(
                suggestions = s.suggestions,
                onPick = { keyword ->
                    dismissKeyboard()
                    viewModel.search(keyword)
                },
            )

            is SearchUiState.Loading -> ProvideShimmer { SearchSkeleton() }

            is SearchUiState.Empty -> EmptyState(
                title = "没有找到「${s.keyword}」相关的视频",
                description = "换个关键词试试",
                modifier = Modifier.fillMaxSize(),
            )

            is SearchUiState.Error -> ErrorState(
                title = "搜索失败",
                description = s.message,
                onRetry = viewModel::retry,
                modifier = Modifier.fillMaxSize(),
            )

            is SearchUiState.Results -> ResultList(
                state = s,
                // ⚠️ 跳转前先收键盘（v1.4.2 修）。
                //
                // 搜索框通常仍有焦点，此时点视频 → 进详情页。
                // 若不收键盘：IME 会在详情页短暂残留，返回搜索页时
                // 由于焦点仍在，键盘会**立刻重新弹出** ——
                // 用户看到的是「返回搜索页莫名弹出键盘」。
                //
                // 这里在导航**之前**清焦点 + 收 IME，
                // 让"离开搜索页"这个动作顺带把输入态也结束掉。
                onVideoClick = { bvid ->
                    dismissKeyboard()
                    onVideoClick(bvid)
                },
                onLoadMore = viewModel::loadMore,
            )
        }
    }
}

/** 输入框。用 BasicTextField 以便完全控制视觉（Material 的 TextField 内边距太大）。 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors

    Row(
        modifier = modifier
            .height(V3Size.searchField)
            // 🔴 与首页 `TopNav.SearchField` **必须同构**（v1.2.4 立的规则，
            //    v3 全量重构时本页被漏掉，这次补齐）。
            //
            // ## 这条规则的历史
            //
            // 上一版本页是 `clip(pill) + background(bgHover)` 胶囊，而 TopNav
            // 当时是**底线输入框** —— 同一个 App 出现两种搜索框。
            // 那时把本页改成底线，是为了向 TopNav 看齐。
            //
            // ## 但 §5.4.3 随后**推翻了「底线」这个结论本身**
            //
            // v3 的判断是：**搜索框是控件，不是内容容器** ——
            // 按三层颜色系统，控件用 **Fill 层**才是正确语义；
            // 且纯黑底上一条 12% 白的线几乎看不见，用户找不到搜索入口。
            //
            // 于是 TopNav 改回了**填充胶囊**，而本页仍停在底线 ——
            // **同一条规则，两处实现又分叉了一次**。
            //
            // ⚠️ 判据：**"与某处保持同构"要连同"那处的规则是否已变"一起看**。
            //    只对齐形态、不对齐**依据**，下一次规则变更还会分叉。
            //    现在两处都走 Fill 层胶囊，依据也一致。
            .clip(RoundedCornerShape(V3Radius.pill))
            .background(colors.fillSecondary)
            .padding(horizontal = V3Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(V3Size.iconSm),
        )
        Spacer(Modifier.width(V3Space.xs))

        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "搜索视频、UP主",
                    // 用 V3Type 语义档位，不再走 MaterialTheme 槽位 + fontSize 覆盖
                    // （那种写法要同时给 fontSize/lineHeight，且槽位名读不出语义）
                    style = V3Type.subheadline,
                    color = colors.labelSecondary,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = V3Type.subheadline.fontSize,
                    color = colors.labelPrimary,
                ),
                cursorBrush = SolidColor(colors.brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }

        if (value.isNotEmpty()) {
            Spacer(Modifier.width(V3Space.xs))
            Box(
                modifier = Modifier
                    // ⚠️ 触摸目标用 touchMin（44dp）而不是图标尺寸：
                    //    清空钮是个高频误触点，44dp 才够。
                    //    图标本身仍画 iconSm，命中区与视觉尺寸解耦。
                    .size(V3Size.touchMin)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "清空",
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(V3Size.iconSm),
                )
            }
        }
    }
}

/** 未输入：搜索历史 + 热搜。 */
@Composable
private fun IdlePanel(
    history: List<String>,
    hotSearch: List<String>,
    onPick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val colors = BiliV3.colors

    LazyColumn(
        // ⚠️ 顶部不留 padding：第一个区块标题自己带 topSpace，
        //    两边都给会翻倍（§5.1「间距只由下方区块提供」）。
        contentPadding = PaddingValues(bottom = V3Space.md),
        verticalArrangement = Arrangement.spacedBy(V3Space.xxs),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (history.isNotEmpty()) {
            item(key = "history-header") {
                // 用 V3SectionTitle 而不是手写 Row —— 手写版本要自己管
                // 字号/字重/左右边距，是"每页一套"的来源。
                V3SectionTitle(
                    title = "搜索历史",
                    // 第一个区块不再额外留白
                    topSpace = V3Space.sm,
                    trailing = {
                        Text(
                            text = "清空",
                            style = V3Type.caption1,
                            color = colors.labelSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(V3Radius.xs))
                                .clickable(onClick = onClearHistory)
                                .padding(
                                    horizontal = V3Space.xs,
                                    vertical = V3Space.xxs,
                                ),
                        )
                    },
                )
            }
            items(history, key = { "h-$it" }) { word ->
                KeywordRow(
                    text = word,
                    onClick = { onPick(word) },
                    onRemove = { onRemoveHistory(word) },
                )
            }
        }

        if (hotSearch.isNotEmpty()) {
            item(key = "hot-header") {
                V3SectionTitle(
                    title = "热搜",
                    topSpace = if (history.isEmpty()) V3Space.sm else V3Space.xl,
                )
            }
            items(hotSearch, key = { "hot-$it" }) { word ->
                KeywordRow(text = word, onClick = { onPick(word) })
            }
        }

        // 两者都空：明确说明，不留白屏
        if (history.isEmpty() && hotSearch.isEmpty()) {
            item(key = "idle-empty") {
                EmptyState(
                    title = "还没有搜索记录",
                          terminalStyle = true,
                    description = "输入关键词开始搜索",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 关键词行（历史可删、热搜不可删）。 */
@Composable
private fun KeywordRow(
    text: String,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val colors = BiliV3.colors
    // 用 V3ContentRow（**无容器**内容行）而不是手写 Row：
    // 它统一了左右边距、纵向节奏与可选分隔线，是"内容页列表项"的唯一基座。
    //
    // ⚠️ `content` 必须**具名传**：本函数最后一个参数是
    //    `separatorInsetStart: Dp`，尾随 lambda 会绑定到它而不是 content
    //    （报 `No value passed for parameter 'content'` +
    //    `Argument type mismatch: () -> Unit, but Dp was expected`）。
    //    这是"参数顺序 ≠ 语义顺序"时尾随 lambda 的经典陷阱。
    V3ContentRow(
        onClick = onClick,
        verticalPadding = V3Space.sm,
        leading = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(V3Size.iconXs),
            )
        },
        actions = if (onRemove != null) {
            {
                Box(
                    modifier = Modifier
                        .size(V3Size.touchMin)
                        .clip(CircleShape)
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "删除",
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(V3Size.iconXs),
                    )
                }
            }
        } else {
            null
        },
        content = {
            Text(
                text = text,
                style = V3Type.callout,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** 联想词面板。 */
@Composable
private fun SuggestPanel(
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
    val colors = BiliV3.colors

    if (suggestions.isEmpty()) {
        // 联想为空不是错误，只是没有建议 —— 静默留白比报错合理
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(vertical = V3Space.xs),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(suggestions, key = { "s-$it" }) { word ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(word) }
                    .padding(horizontal = V3Space.md, vertical = V3Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(V3Size.iconXs),
                )
                Spacer(Modifier.width(V3Space.sm))
                Text(
                    text = word,
                    style = V3Type.callout.copy(
                        color = colors.labelPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 结果列表 + 触底加载。 */
@Composable
private fun ResultList(
    state: SearchUiState.Results,
    onVideoClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val colors = BiliV3.colors
    val listState = rememberLazyListState()

    // 距底 5 项触发加载，避免用户看到"卡住不动"
    val shouldLoad by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val total = info.totalItemsCount
            total > 0 && last.index >= total - 5
        }
    }
    LaunchedEffect(listState, state.keyword) {
        snapshotFlow { shouldLoad }.collect { if (it) onLoadMore() }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = V3Space.md,
            end = V3Space.md,
            top = V3Space.sm,
            bottom = V3Space.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(V3Space.sm),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items = state.items, key = { it.bvid }) { video ->
            VideoCard(video = video, onClick = { onVideoClick(video.bvid) })
        }

        item(key = "load-more") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = V3Space.md),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loadingMore -> CircularProgressIndicator(
                        color = colors.brand,
                        strokeWidth = V3Space.progressTrack,
                        modifier = Modifier.size(V3Size.iconLg),
                    )
                    !state.hasMore -> Text(
                        text = "没有更多了",
                        style = V3Type.caption1.copy(
                            color = colors.labelSecondary,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 搜索骨架。
 *
 * ## ⚠️ 必须与真实结果布局**同构**（这是"闪烁后白底"的成因之一）
 *
 * 第一版是 3 个 `fillMaxWidth` 的 180dp 大块，而真实结果是
 * **一行一张的大卡片列表**（封面 16:10 + 标题两行 + UP 行）。
 * 骨架与内容不一致时，数据到达会**整页跳变**，
 * 表现就是用户描述的"出现后闪一下"。
 *
 * 现在按 `VideoCard` 的真实结构排：封面比例、圆角、标题两行、
 * 元信息行 —— 数据到达时布局几乎不动。
 */
@Composable
private fun SearchSkeleton() {
    // 与 ResultList 的 contentPadding / 间距保持一致
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = V3Space.md,
                end = V3Space.md,
                top = V3Space.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(V3Space.sm),
    ) {
        repeat(SKELETON_CARDS) {
            SkeletonVideoCard()
        }
    }
}

/** 首屏骨架卡片数。够铺满一屏即可，多了纯浪费。 */
private const val SKELETON_CARDS = 4
