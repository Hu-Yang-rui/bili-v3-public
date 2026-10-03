package com.example.biliv3.ui.dynamic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.space.DynamicCard

/**
 * 动态页（底部导航第 2 个 Tab）。
 *
 * ## 从占位页到真实页面
 *
 * 首版这里一直是 `PlaceholderScreen("动态页依赖登录态，将在后续阶段接入")` ——
 * **一个底部 Tab 点进去是占位图**，是很显眼的缺口。
 *
 * ## 三态严格区分
 *
 * | 场景 | 显示 | 文案 |
 * |---|---|---|
 * | 未登录 | 登录引导 | 「登录后查看关注动态」+ 去登录按钮 |
 * | 已登录、无关注 | 空态 | 「还没有动态，去关注几个 UP 主吧」 |
 * | 网络失败 | 错误态 | 具体原因 + 重试 |
 *
 * 三者混成"暂无数据"会让用户完全不知道发生了什么。
 */
@Composable
fun DynamicScreen(
    onLoginRequired: () -> Unit,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DynamicViewModel,
) {
    val colors = BiliTheme.colors
    val items by viewModel.items.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    // 触底加载
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last.index >= total - 3
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { atBottom }.collect { if (it) viewModel.loadMore() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ---- 标题栏（Tab 页也需要一个标题，否则内容直接顶到状态栏）----
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // 通栏标题栏：不再是卡片，内容直接排。
            //
            // ⚠️ 这里**必须**画底边线。原先依赖"内容自己有上边线"来分隔，
            // 但本页内容（空态 / 列表）顶部没有线 → 标题栏与内容**完全无边**，
            // 标题看起来是"浮"在内容上的。
            //
            // 其它 12 个二级页（设置 / 排行 / 直播 / 缓存 / 收藏 …）的标题栏
            // 都画了 `ruleBottom`，这里补齐后全站标题栏语义一致。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .ruleBottom(color = Rule.color)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(Sizes.topBarMobile),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "动态",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = FontSize.titleMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                    modifier = Modifier.padding(start = Space.x4),
                )
            }

            when {
                !viewModel.isLoggedIn -> EmptyState(
                    title = "登录后查看关注动态",
                    description = "动态流来自你关注的 UP 主，需要登录才能获取",
                    icon = Icons.Outlined.Forum,
                    actionLabel = "去登录",
                    onAction = onLoginRequired,
                    modifier = Modifier.fillMaxSize(),
                )

                loading && items.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = colors.brandPrimary,
                        strokeWidth = Space.trackHeight,
                        modifier = Modifier.size(Sizes.iconXl),
                    )
                }

                error != null && items.isEmpty() -> ErrorState(
                    title = "动态加载失败",
                    description = error,
                    onRetry = viewModel::retry,
                    modifier = Modifier.fillMaxSize(),
                )

                items.isEmpty() -> EmptyState(
                    title = "还没有动态",
                    description = "去关注几个 UP 主吧，他们的投稿与动态会出现在这里",
                    icon = Icons.Outlined.Forum,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = Space.x2,
                        bottom = Space.x8,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }, contentType = { "dynamic" }) { d ->
                        DynamicCard(item = d, onVideoClick = onVideoClick)
                    }

                    item(key = "load-more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Space.x4),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                loadingMore -> CircularProgressIndicator(
                                    color = colors.brandPrimary,
                                    strokeWidth = Space.trackHeight,
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
}
