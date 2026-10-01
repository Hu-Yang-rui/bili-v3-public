package com.example.biliv3.ui.download

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.biliv3.data.download.DownloadState
import com.example.biliv3.data.download.DownloadTask
import com.example.biliv3.data.download.DownloadedItem
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState

/**
 * 离线缓存管理页。
 *
 * ## 结构
 *
 * ```
 * [← 离线缓存]                          已用 128.4 MB
 * ────────────────────────────────────────────────
 * 下载中 / 失败（有任务时才渲染这一块）
 *   ┌──────────────────────────────┐
 *   │ 标题                62%       │
 *   │ ████████░░░░░░░  取消         │
 *   └──────────────────────────────┘
 * 已缓存（N）
 *   ┌──────────────────────────────┐
 *   │ [封面] 标题                    │
 *   │        UP · 720P · 12:34 · 24MB│
 *   │                          [删]  │
 *   └──────────────────────────────┘
 * ```
 *
 * ## 为什么分「下载中」和「已缓存」两块而不是两个 Tab
 *
 * 下载中的任务通常只有一两个，用 Tab 切换会让"正在下载"这条信息
 * 在另一个 Tab 里看不见 —— 用户会以为下载没开始。
 * 两块同屏，进行中的永远在顶部，符合"最关心的事放最上面"。
 *
 * ## 三态齐全
 *
 * 未缓存 → 空态（说明怎么产生缓存）；有缓存 → 列表；
 * 任务失败 → 失败块带重试提示。
 */
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onPlay: (DownloadedItem) -> Unit,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadViewModel,
) {
    val colors = BiliTheme.colors
    val items by viewModel.items.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    val running = tasks.values.filter { it.state is DownloadState.Downloading }
    val failed = tasks.values.filter { it.state is DownloadState.Failed }

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
                text = "离线缓存",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "已用 ${com.example.biliv3.util.ImageCache.formatBytes(items.sumOf { it.totalBytes })}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textSecondarySafe,
                ),
                modifier = Modifier.padding(end = Space.x3),
            )
        }

        if (items.isEmpty() && running.isEmpty() && failed.isEmpty()) {
            EmptyState(
                title = "还没有缓存任何视频",
                description = "在视频详情页点「更多 ⋮」→「缓存」即可离线观看",
                icon = Icons.Outlined.DownloadDone,
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(vertical = Space.x2),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ---- 进行中 ----
            if (running.isNotEmpty()) {
                item(key = "header-running") { GroupHeader("下载中") }
                items(running, key = { "run-${it.bvid}:${it.cid}" }) { task ->
                    RunningRow(task = task, onCancel = { viewModel.cancel(task.bvid, task.cid) })
                }
            }

            // ---- 失败 ----
            if (failed.isNotEmpty()) {
                item(key = "header-failed") { GroupHeader("下载失败") }
                items(failed, key = { "fail-${it.bvid}:${it.cid}" }) { task ->
                    FailedRow(task = task)
                }
            }

            // ---- 已缓存 ----
            if (items.isNotEmpty()) {
                item(key = "header-done") { GroupHeader("已缓存（${items.size}）") }
                items(items, key = { "${it.bvid}:${it.cid}" }) { item ->
                    DownloadedRow(
                        item = item,
                        onClick = { onPlay(item) },
                        onOpenDetail = { onVideoClick(item.bvid) },
                        onDelete = { viewModel.delete(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.label,
            fontWeight = FontWeight.SemiBold,
            color = BiliTheme.colors.textTertiary,
        ),
        modifier = Modifier.padding(
            start = Space.x4,
            end = Space.x4,
            top = Space.x3,
            bottom = Space.x2,
        ),
    )
}

/** 进行中的任务：标题 + 进度条 + 取消。 */
@Composable
private fun RunningRow(task: DownloadTask, onCancel: () -> Unit) {
    val colors = BiliTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x1)
            .biliCard(shape = RoundedCornerShape(Radius.card))
            .padding(horizontal = Space.x4, vertical = Space.x3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.x2))
            Text(
                text = "${(task.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.brandPrimary,
                ),
            )
        }
        Spacer(Modifier.height(Space.x2))
        LinearProgressIndicator(
            progress = { task.progress.coerceIn(0f, 1f) },
            color = colors.brandPrimary,
            trackColor = colors.bgHover,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(Space.x2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = com.example.biliv3.util.ImageCache.formatBytes(task.downloadedBytes) +
                    " / " + com.example.biliv3.util.ImageCache.formatBytes(task.totalBytes),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    color = colors.textTertiary,
                ),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "取消",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textBrandSafe,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.badge))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = Space.x2, vertical = Space.x1),
            )
        }
    }
}

/** 失败的任务：说明原因 + 提示。 */
@Composable
private fun FailedRow(task: DownloadTask) {
    val colors = BiliTheme.colors
    val msg = (task.state as? DownloadState.Failed)?.message ?: "下载失败"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x1)
            .biliCard(shape = RoundedCornerShape(Radius.card))
            .padding(horizontal = Space.x4, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // 给出**可操作的原因**，不是"出错了"
                text = "$msg（已下载的部分保留，可重试续传）",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    color = colors.stateError,
                ),
            )
        }
    }
}

/** 已缓存条目。 */
@Composable
private fun DownloadedRow(
    item: DownloadedItem,
    onClick: () -> Unit,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x1)
            .biliCard(shape = RoundedCornerShape(Radius.card))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 112.dp, height = 63.dp)
                .clip(RoundedCornerShape(Radius.cover))
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = com.example.biliv3.data.model.CoverUrls.cover(item.cover, 320),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(Space.x3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onOpenDetail),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(item.authorName)
                    if (item.qualityLabel.isNotEmpty()) {
                        append(" · ")
                        append(item.qualityLabel)
                    }
                    if (item.pageLabel.isNotEmpty()) {
                        append(" · ")
                        append(item.pageLabel)
                    }
                    append(" · ")
                    append(com.example.biliv3.util.ImageCache.formatBytes(item.totalBytes))
                },
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(Space.x2))

        Box(
            modifier = Modifier
                .size(Space.minTouchTarget)
                .clip(CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "删除缓存",
                tint = colors.textTertiary,
                modifier = Modifier.size(Sizes.iconLg),
            )
        }
    }
}
