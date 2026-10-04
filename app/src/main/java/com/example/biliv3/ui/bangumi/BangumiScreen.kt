package com.example.biliv3.ui.bangumi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.biliv3.data.BangumiItem
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.band
import com.example.biliv3.design.BandLevel
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState

/**
 * 番剧 / 影视索引页。
 *
 * ## 布局
 *
 * ```
 * [←] 番剧
 * ─────────────────────────────
 * 番剧  国创  电影  电视剧  纪录片  综艺   ← 类型 Tab
 * ─────────────────────────────
 * ┌────────┐ ┌────────┐ ┌────────┐
 * │ 封面   │ │ 封面   │ │ 封面   │      ← 3 列网格
 * │ 9.7   │ │        │ │        │      ← 评分角标
 * └────────┘ └────────┘ └────────┘
 *  标题两行     标题两行     标题两行
 *  全8话       全13话      全11话
 * ```
 *
 * ## 为什么用网格而不是列表
 *
 * 番剧是"封面 + 标题"为主的浏览型内容，没有时长/播放量等需要横向展开的信息。
 * 网格能在同屏展示更多条目。
 */
@Composable
fun BangumiScreen(
    tabs: List<BangumiTab>,
    selectedType: Int,
    items: List<BangumiItem>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onItemClick: (BangumiItem) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ---- 顶栏 ----
        // 通栏顶栏：不再是卡片，内容直接排。
        // ⚠️ 底线不可省（v1.2.4 补）：`AGENTS.md` §7.4-32 要求
        // 「二级页标题栏一律 ruleBottom」。此处原先漏了，标题会"浮"在
        // 下方 Tab 条上 —— 与 RankingScreen 的同一结构不一致。
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
                text = "番剧",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
        }

        // ---- 类型 Tab ----
        // ⚠️ 用 `band()` 而不是 `.background(colors.bgCard)`（v1.2.4 统一）。
        // 与 RankingScreen 的同一结构保持一致 —— 这是全宽直角的分区带。
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .band(BandLevel.Raised),
            contentPadding = PaddingValues(horizontal = Space.x4),
            horizontalArrangement = Arrangement.spacedBy(Space.x5),
        ) {
            items(tabs, key = { it.seasonType }) { tab ->
                val selected = tab.seasonType == selectedType
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelectTab(tab.seasonType) }
                        .padding(vertical = Space.x3),
                ) {
                    Text(
                        text = tab.name,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.body,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) colors.textPrimary else colors.textSecondarySafe,
                        ),
                    )
                    Spacer(Modifier.height(Space.x1))
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(Space.tabIndicator)
                            .clip(RoundedCornerShape(Radius.badge))
                            .background(if (selected) colors.brandPrimary else Color.Transparent),
                    )
                }
            }
        }

        // ---- 内容 ----
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // 终端风加载态（同 CategoryScreen：光标承担"进行中"语义）
                com.example.biliv3.ui.component.TerminalLoadingState(
                    text = "正在加载番剧…",
                )
            }

            error != null -> ErrorState(
                title = "加载失败",
                description = error,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )

            items.isEmpty() -> EmptyState(
                title = "这个分类暂时没有内容",
                // 终端风：列表为空的次要状态，轻量提示符行
                terminalStyle = true,
                description = "换个分类看看",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = Space.x4,
                    end = Space.x4,
                    top = Space.x3,
                    bottom = Space.x8,
                ),
                horizontalArrangement = Arrangement.spacedBy(Space.x3),
                verticalArrangement = Arrangement.spacedBy(Space.x4),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.seasonId }, contentType = { "bangumi" }) { item ->
                    BangumiCard(item = item, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

/** 番剧卡片：竖版封面 + 评分角标 + 标题 + 更新状态。 */
@Composable
private fun BangumiCard(item: BangumiItem, onClick: () -> Unit) {
    val colors = BiliTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 网格项不再是卡片：封面直接排，网格自身的行列间距负责分组。
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 番剧封面是 3:4 竖版（直角，无卡片后不再需要圆角）
                .aspectRatio(3f / 4f)
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = item.coverUrl(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // 评分角标：只在有评分时显示（索引接口的 score 可能是 0）
            if (item.score > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Space.compactHorizontal)
                        // 压在封面上的小标签用直角（同 DurationBadge）
                        .background(colors.overlayCover)
                        .padding(horizontal = Space.tagHorizontal, vertical = Space.tagVertical),
                ) {
                    Text(
                        text = "%.1f".format(item.score),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            color = colors.rankThird,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.x2))

        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = FontSize.bodySm,
                lineHeight = FontSize.bodySmLine,
                color = colors.textPrimary,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(TITLE_TWO_LINES),
        )

        // 更新状态（如「全8话」）
        if (item.indexShow.isNotEmpty()) {
            Spacer(Modifier.height(Space.micro))
            Text(
                text = item.indexShow,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 番剧类型 Tab。 */
data class BangumiTab(val seasonType: Int, val name: String) {
    companion object {
        fun defaults(): List<BangumiTab> = listOf(
            BangumiTab(1, "番剧"),
            BangumiTab(4, "国创"),
            BangumiTab(2, "电影"),
            BangumiTab(5, "电视剧"),
            BangumiTab(3, "纪录片"),
            BangumiTab(7, "综艺"),
        )
    }
}

/** 标题固定两行高度，避免不同标题长度导致网格参差。 */
private val TITLE_TWO_LINES = 40.dp
