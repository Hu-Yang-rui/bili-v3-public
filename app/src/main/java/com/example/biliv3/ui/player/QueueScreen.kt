package com.example.biliv3.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.player.QueueItem
import com.example.biliv3.player.RepeatMode
import com.example.biliv3.ui.component.EmptyState

/**
 * 播放队列页。
 *
 * ## 设计要点（无卡片架构）
 *
 * 队列项是**列表**，按 §5.1 硬规则：
 * - 直角（只有交互元素 4dp）
 * - 行间用发丝线，不用卡片
 * - 分组靠间距
 *
 * ## 拖动排序
 *
 * 用 `detectDragGesturesAfterLongPress`（长按才拖）——
 * 否则正常滚动会被误判成拖动，队列就滑不动了。
 *
 * ⚠️ 拖动中的视觉偏移用 `graphicsLayer` 的 `translationY`
 * （绘制阶段），不是改 `padding`（那会触发整个列表重新布局，
 * 拖一下卡一下）。
 */
@Composable
fun QueueScreen(
    items: List<QueueItem>,
    currentKey: String?,
    repeatMode: RepeatMode,
    shuffled: Boolean,
    onBack: () -> Unit,
    onSelect: (QueueItem) -> Unit,
    onRemove: (QueueItem) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    val listState = rememberLazyListState()

    // 拖动状态：正在拖的下标 + 累计偏移
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    val rowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        (Sizes.upAvatar + Space.x6).toPx()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ---- 标题栏（二级页一律 ruleBottom，§7.4-32） ----
        // ⚠️ 必须自己消费 statusBars（MainShell 的 contentWindowInsets 是 0）。
        // 漏了它 → 标题栏画到状态栏底下，返回键点不动。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(Sizes.topBarMobile)
                .padding(horizontal = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(RoundedCornerShape(Radius.pill))
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
                text = "播放队列",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) {
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.interactive))
                        .clickable(onClick = onClear)
                        .padding(horizontal = Space.x3, vertical = Space.x2),
                )
            }
        }

        // ---- 模式栏（随机 / 循环） ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ruleBottom(color = Rule.subtle)
                .padding(horizontal = Space.x3, vertical = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.x2),
        ) {
            QueueModeButton(
                icon = Icons.Filled.Shuffle,
                label = if (shuffled) "随机开" else "随机",
                active = shuffled,
                onClick = onToggleShuffle,
            )
            QueueModeButton(
                icon = when (repeatMode) {
                    RepeatMode.ONE -> Icons.Filled.RepeatOne
                    else -> Icons.Filled.Repeat
                },
                label = when (repeatMode) {
                    RepeatMode.OFF -> "不循环"
                    RepeatMode.ALL -> "列表循环"
                    RepeatMode.ONE -> "单曲循环"
                },
                active = repeatMode != RepeatMode.OFF,
                onClick = onCycleRepeat,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${items.size} 首",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textTertiary,
                ),
            )
        }

        // ---- 队列列表 ----
        if (items.isEmpty()) {
            EmptyState(
                title = "队列是空的",
                description = "在视频页用「添加到队列」把视频加进来",
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Space.x8),
            ) {
                itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
                    QueueRow(
                        item = item,
                        isCurrent = item.key == currentKey,
                        isDragging = index == draggingIndex,
                        dragOffset = if (index == draggingIndex) dragOffset else 0f,
                        onSelect = { onSelect(item) },
                        onRemove = { onRemove(item) },
                        onDragStart = {
                            draggingIndex = index
                            dragOffset = 0f
                        },
                        onDrag = { delta ->
                            dragOffset += delta
                            // 拖过一行高度就换位（并把偏移回退一行）
                            val target = index + (dragOffset / rowHeightPx).toInt()
                            if (target != index && target in items.indices) {
                                onMove(index, target)
                                draggingIndex = target
                                dragOffset -= (target - index) * rowHeightPx
                            }
                        },
                        onDragEnd = {
                            draggingIndex = -1
                            dragOffset = 0f
                        },
                    )
                }
            }
        }
    }
}

/** 队列行。 */
@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    isDragging: Boolean,
    dragOffset: Float,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val colors = BiliTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .ruleBottom(color = Rule.subtle)
            // ⚠️ 拖动偏移用 graphicsLayer（绘制阶段），
            // 不用 padding（那会触发整列重新布局，拖一下卡一下）
            .graphicsLayer {
                translationY = dragOffset
                alpha = if (isDragging) 0.85f else 1f
                scaleX = if (isDragging) 1.02f else 1f
                scaleY = if (isDragging) 1.02f else 1f
            }
            .clickable(onClick = onSelect)
            .padding(horizontal = Space.x3, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 拖动把手：长按才拖（否则会与滚动冲突）
        Box(
            modifier = Modifier
                .size(Space.minTouchTarget)
                .pointerInput(item.key) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, delta ->
                            change.consume()
                            onDrag(delta.y)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "拖动排序",
                tint = colors.textTertiary,
                modifier = Modifier.size(Sizes.iconLg),
            )
        }

        Spacer(Modifier.width(Space.x2))

        // 封面（直角，§5.1 硬规则 2）
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(54.dp)
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = item.cover,
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
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    // 当前播放项用品牌色（它是"状态"不是"装饰"）
                    color = if (isCurrent) colors.brandPrimary else colors.textPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.author.isNotEmpty()) {
                Spacer(Modifier.height(Space.x1))
                Text(
                    text = item.author,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(Space.x2))

        Box(
            modifier = Modifier
                .size(Space.minTouchTarget)
                .clip(RoundedCornerShape(Radius.interactive))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "从队列移除",
                tint = colors.textTertiary,
                modifier = Modifier.size(Sizes.iconLg),
            )
        }
    }
}

/** 模式切换小按钮（胶囊，交互元素 4dp）。 */
@Composable
private fun QueueModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.interactive))
            .background(if (active) colors.brandPrimaryDim else colors.bgHover)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) colors.brandPrimary else colors.textSecondarySafe,
            modifier = Modifier.size(Sizes.iconMd),
        )
        Spacer(Modifier.width(Space.x1))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = if (active) colors.brandPrimary else colors.textSecondarySafe,
            ),
        )
    }
}
