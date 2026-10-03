package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
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
import com.example.biliv3.data.model.CommentItem
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.data.model.formatRelativeTime
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState

/**
 * 楼中楼详情页（某条评论的全部回复）。
 *
 * ## 结构
 *
 * ```
 * [← 全部回复]
 * ┌──────────────────────────┐
 * │ (头像) 昵称               │
 * │        主评论正文…        │  ← 顶部固定展示主评论
 * │        时间 · IP属地      │
 * ├──────────────────────────┤
 * │ 共 N 条回复               │
 * │ ─────────────────────    │
 * │ (头像) 回复1              │
 * │ (头像) 回复2              │
 * └──────────────────────────┘
 * ```
 *
 * ## 为什么顶部要重复展示主评论
 *
 * 用户从评论区点"查看全部 N 条回复"进来，进来后**看不到自己在回复谁**
 * 是很常见的设计失误。顶部固定主评论，上下文始终可见。
 */
@Composable
fun ReplyDetailScreen(
    onBack: () -> Unit,
    onAvatarClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReplyDetailViewModel,
) {
    val colors = BiliTheme.colors
    val replies by viewModel.replies.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last.index >= total - 2
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { atBottom }.collect { if (it) viewModel.loadMore() }
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
                text = "全部回复",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = Space.x8),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ---- 主评论（上下文）----
            viewModel.rootComment?.let { root ->
                item(key = "root") {
                    RootCommentCard(comment = root, onAvatarClick = onAvatarClick)
                }
            }

            item(key = "count") {
                Text(
                    text = "共 ${replies.size} 条回复" + if (hasMore) "（还有更多）" else "",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textTertiary,
                    ),
                    modifier = Modifier.padding(
                        start = Space.x4,
                        top = Space.x3,
                        bottom = Space.x2,
                    ),
                )
            }

            when {
                loading -> item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Space.x6),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = colors.brandPrimary,
                            strokeWidth = Space.trackHeight,
                            modifier = Modifier.size(Sizes.iconXl),
                        )
                    }
                }

                replies.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        title = "这条评论还没有回复",
                        description = "回到评论区可以直接回复它",
                        compact = true,
                    )
                }

                else -> {
                    items(replies, key = { it.rpid }) { r ->
                        ReplyRow(
                            comment = r,
                            onLike = { viewModel.like(r) },
                            onAvatarClick = { onAvatarClick(r.mid) },
                        )
                    }

                    item(key = "footer") {
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

/** 顶部主评论卡。 */
@Composable
private fun RootCommentCard(comment: CommentItem, onAvatarClick: (Long) -> Unit) {
    val colors = BiliTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 楼中楼的主评论**不再是卡片** —— 它是列表里的一行。
            // 列表用留白分组，不用卡片分组。
            .padding(horizontal = Space.x4, vertical = Space.x3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = comment.faceUrl(72),
                contentDescription = "进入用户主页",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.avatarPlaceholder)
                    .clickable { onAvatarClick(comment.mid) },
            )
            Spacer(Modifier.width(Space.x2))
            Text(
                text = comment.userName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Space.x2))
        Text(
            text = comment.content,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                lineHeight = FontSize.bodyLine,
                color = colors.textPrimary,
            ),
        )
        Spacer(Modifier.height(Space.x1))
        Text(
            text = buildString {
                append(formatRelativeTime(comment.ctime))
                if (comment.hasIpLocation) append("  IP属地：${comment.ipLocation}")
            },
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.badge,
                color = colors.textSecondarySafe,
            ),
        )
    }
}

/** 一条回复。 */
@Composable
private fun ReplyRow(
    comment: CommentItem,
    onLike: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 楼中楼的每条回复**不再是卡片** —— 同「列表用留白分组」原则。
            // 每条回复都套卡会形成"卡片墙"，且与主评论卡重复。
            .padding(horizontal = Space.x4, vertical = Space.x2),
        verticalAlignment = Alignment.Top,
    ) {
        AsyncImage(
            model = comment.faceUrl(48),
            contentDescription = "进入用户主页",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.avatarPlaceholder)
                .clickable(onClick = onAvatarClick),
        )
        Spacer(Modifier.width(Space.x2))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = comment.userName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.micro))
            Text(
                text = comment.content,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    lineHeight = FontSize.bodyLine,
                    color = colors.textPrimary,
                ),
            )
            Spacer(Modifier.height(Space.x1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatRelativeTime(comment.ctime),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.textSecondarySafe,
                    ),
                )
                if (comment.hasIpLocation) {
                    Spacer(Modifier.width(Space.x2))
                    Text(
                        text = "IP属地：${comment.ipLocation}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            color = colors.textSecondarySafe,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (comment.liked) Icons.Filled.ThumbUp
                    else Icons.Outlined.ThumbUp,
                    contentDescription = if (comment.liked) "取消点赞" else "点赞",
                    tint = if (comment.liked) colors.brandPrimary else colors.textSecondarySafe,
                    modifier = Modifier
                        .size(Sizes.iconSm + Space.x1)
                        .clickable(onClick = onLike),
                )
                if (comment.likeCount > 0) {
                    Spacer(Modifier.width(Space.micro))
                    Text(
                        text = formatCount(comment.likeCount),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            color = if (comment.liked) colors.brandPrimary
                            else colors.textSecondarySafe,
                        ),
                    )
                }
            }
        }
    }
}
