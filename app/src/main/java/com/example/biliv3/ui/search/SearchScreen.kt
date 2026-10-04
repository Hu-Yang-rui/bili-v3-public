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
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.component.HideKeyboardOnDispose
import com.example.biliv3.ui.component.ProvideShimmer
import com.example.biliv3.ui.component.SkeletonVideoCard
import com.example.biliv3.ui.component.VideoCard
import com.example.biliv3.ui.component.hideImeNow

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
    val colors = BiliTheme.colors

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

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ---- 搜索栏 ----
        // 通栏：不再是卡片，内容直接排。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(Sizes.topBarMobile)
                .padding(horizontal = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
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
                    tint = colors.textPrimary,
                    modifier = Modifier.size(Sizes.iconXl),
                )
            }
            Spacer(Modifier.width(Space.x1))

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
            Spacer(Modifier.width(Space.x2))
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
                onVideoClick = onVideoClick,
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
    val colors = BiliTheme.colors

    Row(
        modifier = modifier
            .height(Sizes.searchHeight)
            // 🔴 与首页 `TopNav` 的搜索框**必须同构**（v1.2.4 统一）。
            //
            // 上一版这里是 `clip(pill) + background(bgHover)` —— 一个完整的
            // 圆角胶囊盒子。而首页 `TopNav.SearchBox` 在无卡片重构里已经改成
            // **底线输入框**（无底色 / 无圆角 / 无四边框，只有一条底边线）。
            //
            // 于是同一个 App 里出现两种搜索框：首页是底线，点进来变胶囊。
            // **这正是「重构漏改」的典型**：改了一处，忘了另一处。
            //
            // 实测证据（Pixel 7 / 1080px）：
            // | | 首页 TopNav | 本页（改前） |
            // |---|---|---|
            // | 形态 | 1dp 底线，y=254..256 | 实心块，y=152..256（高 105px = 40dp） |
            // | 取色 | maxV 37..46（细线） | medianV **48** = `bgHover #1F2530` 精确吻合 |
            //
            // 改法：抄 `TopNav` 的写法 —— `ruleBottom` 承担边界，
            // 去掉 `clip` / `background`。⚠️ 注意搜索页没有 hover 态
            // （移动端无指针），所以用 `Rule.color` 固定值。
            .ruleBottom(color = Rule.color)
            .padding(horizontal = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(Sizes.iconMd),
        )
        Spacer(Modifier.width(Space.x2))

        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "搜索视频、UP主",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.textSecondary,
                    ),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
                cursorBrush = SolidColor(colors.brandPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }

        if (value.isNotEmpty()) {
            Spacer(Modifier.width(Space.x2))
            Box(
                modifier = Modifier
                    .size(Sizes.iconLg)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "清空",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(Sizes.iconMd),
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
    val colors = BiliTheme.colors

    LazyColumn(
        contentPadding = PaddingValues(vertical = Space.x4),
        verticalArrangement = Arrangement.spacedBy(Space.x1),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (history.isNotEmpty()) {
            item(key = "history-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4, vertical = Space.x2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "搜索历史",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.body,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            color = colors.textSecondarySafe,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.interactive))
                            .clickable(onClick = onClearHistory)
                            .padding(horizontal = Space.x2, vertical = Space.x1),
                    )
                }
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
                Text(
                    text = "热搜",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                    modifier = Modifier.padding(
                        start = Space.x4,
                        end = Space.x4,
                        top = if (history.isEmpty()) Space.x2 else Space.x5,
                        bottom = Space.x2,
                    ),
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
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x4, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(Sizes.iconSm),
        )
        Spacer(Modifier.width(Space.x3))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                color = colors.textPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .size(Sizes.iconXl)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "删除",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(Sizes.iconSm),
                )
            }
        }
    }
}

/** 联想词面板。 */
@Composable
private fun SuggestPanel(
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
    val colors = BiliTheme.colors

    if (suggestions.isEmpty()) {
        // 联想为空不是错误，只是没有建议 —— 静默留白比报错合理
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(vertical = Space.x2),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(suggestions, key = { "s-$it" }) { word ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(word) }
                    .padding(horizontal = Space.x4, vertical = Space.x3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(Sizes.iconSm),
                )
                Spacer(Modifier.width(Space.x3))
                Text(
                    text = word,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.textPrimary,
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
    val colors = BiliTheme.colors
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
            start = Space.x4,
            end = Space.x4,
            top = Space.x3,
            bottom = Space.x8,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.x3),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items = state.items, key = { it.bvid }) { video ->
            VideoCard(video = video, onClick = { onVideoClick(video.bvid) })
        }

        item(key = "load-more") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.x4),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loadingMore -> CircularProgressIndicator(
                        color = colors.brandPrimary,
                        strokeWidth = Space.trackHeight,
                        modifier = Modifier.size(Sizes.iconXl),
                    )
                    !state.hasMore -> Text(
                        text = "没有更多了",
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
                start = Space.x4,
                end = Space.x4,
                top = Space.x3,
            ),
        verticalArrangement = Arrangement.spacedBy(Space.x3),
    ) {
        repeat(SKELETON_CARDS) {
            SkeletonVideoCard()
        }
    }
}

/** 首屏骨架卡片数。够铺满一屏即可，多了纯浪费。 */
private const val SKELETON_CARDS = 4
