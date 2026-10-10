package com.example.biliv3.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.biliv3.data.lyrics.LyricLine
import com.example.biliv3.data.lyrics.Lyrics
import com.example.biliv3.data.lyrics.LyricsUiState
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Type
import com.example.biliv3.design.v3.V3Size

/**
 * 歌词视图（播放器内嵌）。
 *
 * ## 为什么不用 `biliCard` / 玻璃
 *
 * 歌词是**内容**，不是浮层容器。按 §5.1 无卡片架构，
 * 内容直接排，分组靠间距与明度 —— 这里连分隔线都不需要
 * （歌词行本身就是节奏）。
 *
 * ## 四态
 *
 * [LyricsUiState] 有四种，UI 必须**全部**处理：
 * - Loading → 骨架行（不是转圈：转圈在歌词区太占视觉重量）
 * - Ready → 歌词列表
 * - NoLyrics → 「暂无歌词」+ 说明（**不给重试按钮** —— 没有就是没有）
 * - Failed → 错误 + **重试按钮**（这是真失败，值得重试）
 *
 * ⚠️ 把后两者合并是项目记录过的坑（坑 44：把失败显示成空 = 对用户说假话）。
 *
 * ## 当前行高亮 + 自动滚动
 *
 * 自动滚动与手动滚动会**打架**：用户手动滑到后面看歌词时，
 * 自动滚动会把他拽回来。所以这里维护 `userScrolling` 标记 ——
 * 用户手动操作后暂停自动滚动若干秒。
 */
@Composable
fun LyricsView(
    state: LyricsUiState,
    positionMs: Long,
    onRetry: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 是否允许点击行跳转（沉浸式下可能禁用，避免误触）。 */
    clickable: Boolean = true,
) {
    val colors = BiliV3.colors

    when (state) {
        is LyricsUiState.Idle, is LyricsUiState.Loading -> {
            LyricsSkeleton(modifier)
        }

        is LyricsUiState.NoLyrics -> {
            LyricsHint(
                title = "暂无歌词",
                description = "这首歌没有字幕或歌词源",
                modifier = modifier,
            )
        }

        is LyricsUiState.Failed -> {
            LyricsHint(
                title = "歌词加载失败",
                description = state.message,
                modifier = modifier,
                action = if (state.retryable) {
                    { BrandButton("重试", onRetry, variant = BrandButtonVariant.Outline) }
                } else {
                    null
                },
            )
        }

        is LyricsUiState.Ready -> {
            LyricsList(
                lyrics = state.lyrics,
                source = state.source,
                positionMs = positionMs,
                onSeekTo = onSeekTo,
                clickable = clickable,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun LyricsList(
    lyrics: Lyrics,
    source: String,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
    clickable: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors
    val listState = rememberLazyListState()
    val currentIndex = lyrics.indexAt(positionMs)

    // 用户手动滚动后暂停自动跟随（否则会被"拽回来"，很烦）
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) userScrolling = true
    }
    LaunchedEffect(userScrolling) {
        if (userScrolling) {
            kotlinx.coroutines.delay(3000L)
            userScrolling = false
        }
    }

    // 自动滚动到当前行（留出上方两行，让用户有"下一句"的预期）
    LaunchedEffect(currentIndex, userScrolling) {
        if (currentIndex >= 0 && !userScrolling) {
            listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(V3Space.xs),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                vertical = V3Space.xxxl,
            ),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                LyricRow(
                    line = line,
                    active = index == currentIndex,
                    clickable = clickable,
                    onClick = { onSeekTo(line.timeMs + lyrics.offsetMs) },
                )
            }
        }

        // 来源标注：歌词可能来自第三方，不能冒充官方（§4.3 同类原则）
        Text(
            text = "来源：$source",
            style = V3Type.caption2.copy(
                color = colors.labelTertiary,
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = V3Space.sm, bottom = V3Space.xs),
        )
    }
}

@Composable
private fun LyricRow(
    line: LyricLine,
    active: Boolean,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    Text(
        text = line.text,
        style = V3Type.callout.copy(
            // 当前行放大 + 提亮；非当前行次要色 ——
            // 靠**明度与字重**区分而不是靠颜色（颜色对比度不够时也读得清）
            //
            // ⚠️ 字号取自令牌阶梯（14 → 15），不写 15.sp 字面量。
            // 阶梯只有 13/14/15/17，没有"14.5"这种中间值。
            fontSize = if (active) V3Type.subheadline.fontSize else V3Type.callout.fontSize,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) colors.labelPrimary else colors.labelSecondary,
            textAlign = TextAlign.Center,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clickable) {
                    Modifier
                        .clip(RoundedCornerShape(V3Radius.xs))
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = V3Space.md, vertical = V3Space.xxs),
    )
}

/** 加载态：骨架行（不用转圈 —— 歌词区用骨架更贴内容形状）。 */
@Composable
private fun LyricsSkeleton(modifier: Modifier = Modifier) {
    val colors = BiliV3.colors
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(V3Space.sm, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(5) { i ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f + (i % 3) * 0.15f)
                    .height(14.dp)
                    .background(colors.skeletonBase),
            )
        }
    }
}

/** 空态 / 错误态。 */
@Composable
private fun LyricsHint(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = BiliV3.colors
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Lyrics,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(V3Size.iconEmpty),
        )
        Spacer(Modifier.height(V3Space.md))
        Text(
            text = title,
            style = V3Type.callout.copy(
                color = colors.labelSecondary,
            ),
        )
        Spacer(Modifier.height(V3Space.xxs))
        Text(
            text = description,
            style = V3Type.footnote.copy(
                color = colors.labelTertiary,
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = V3Space.xxl),
        )
        if (action != null) {
            Spacer(Modifier.height(V3Space.lg))
            action()
        }
    }
}
