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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.biliv3.data.BangumiEpisode
import com.example.biliv3.data.model.CoverUrls
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.ruleTop
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * 番剧详情页。
 *
 * ## 结构
 *
 * ```
 * [← 番剧名]
 * ┌──────────────────────────┐
 * │  [竖版海报]  评分 9.6      │
 * │              全 12 话      │
 * │              简介…         │
 * └──────────────────────────┘
 * 选集（12）
 * [1] [2] [3] [4] [5] [6]
 * ...
 * ```
 *
 * ## 每集的可播性
 *
 * 番剧走 `pgc/player/web/playurl?ep_id=`，与 UGC 的 bvid 是**两套体系**。
 * 部分 PGC 内容同时有 UGC 稿件（`episodes[].bvid` 非空），
 * 那部分可以直接跳视频详情页播；没有 bvid 的集**明确标注不可在本应用播放**，
 * 而不是给一个点了报错的入口。
 */
@Composable
fun BangumiDetailScreen(
    onBack: () -> Unit,
    onEpisodePlayable: (String) -> Unit,
    onEpisodeUnavailable: (BangumiEpisode) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BangumiDetailViewModel,
) {
    val colors = BiliV3.colors
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val following by viewModel.following.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPrimary),
    ) {
        // 通栏顶栏：不再是卡片，内容直接排。
        // ⚠️ 底线不可省（v1.2.4 补）：§7.4-32 要求「二级页标题栏一律
        // ruleBottom(color = Rule.color)」。此处原先漏了 ——
        // 番剧详情是长页面，标题"浮"在滚动内容上尤其明显。
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                text = detail?.title ?: "番剧",
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // 终端风加载态（光标承担"进行中"语义）
                com.example.biliv3.ui.component.TerminalLoadingState(
                    text = "正在加载详情…",
                )
            }

            detail == null -> ErrorState(
                title = "番剧详情加载失败",
                description = error ?: "请稍后重试",
                onRetry = viewModel::retry,
                modifier = Modifier.fillMaxSize(),
            )

            else -> {
                val d = detail!!
                LazyVerticalGrid(
                    columns = GridCells.Fixed(EPISODE_COLUMNS),
                    contentPadding = PaddingValues(
                        start = V3Space.sm,
                        end = V3Space.sm,
                        top = V3Space.xs,
                        bottom = V3Space.xxl,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(V3Space.xs),
                    verticalArrangement = Arrangement.spacedBy(V3Space.xs),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // ---- 头部信息卡（占满整行）----
                    item(span = {
                        androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                    }) {
                        BangumiHeader(
                            title = d.title,
                            cover = d.cover,
                            score = d.score,
                            evaluate = d.evaluate,
                            totalEpisodes = d.totalEpisodes,
                            followCount = d.followCount,
                            isFollowing = following,
                            onToggleFollow = viewModel::toggleFollow,
                        )
                    }

                    item(span = {
                        androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                    }) {
                        Text(
                            text = "选集（${d.totalEpisodes}）",
                            style = V3Type.caption1.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = colors.labelTertiary,
                            ),
                            modifier = Modifier.padding(
                                // 章节标题自带组间距（头部区块不再提供 bottom）
                                top = Rhythm.between,
                                bottom = V3Space.xs,
                            ),
                        )
                    }

                    items(d.episodes, key = { it.epId }) { ep ->
                        EpisodeCell(
                            ep = ep,
                            onClick = {
                                if (ep.playableInApp()) {
                                    onEpisodePlayable(ep.bvid)
                                } else {
                                    onEpisodeUnavailable(ep)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BangumiHeader(
    title: String,
    cover: String,
    score: Double,
    evaluate: String,
    totalEpisodes: Int,
    followCount: Int,
    isFollowing: Boolean,
    onToggleFollow: () -> Unit,
) {
    val colors = BiliV3.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 头部信息区通栏：去掉卡片，改用上边发丝线分隔。
            // 间距只加在 top（下方区块各自负责自己的 top，避免翻倍）。
            .ruleTop(color = Rule.subtle)
            .padding(start = V3Space.md, end = V3Space.md, top = Rhythm.between),
    ) {
        // 竖版海报 3:4（番剧海报比例，不是 16:10 的横版封面）
        AsyncImage(
            model = CoverUrls.cover(cover, 320, CoverUrls.BANGUMI_ASPECT_RATIO),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(96.dp)
                .height(132.dp)
                // 海报是封面 → 直角
                .background(colors.coverPlaceholder),
        )
        Spacer(Modifier.width(V3Space.sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(V3Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (score > 0) {
                    Text(
                        // 评分用"数值 + 文案"表达，不使用 B 站的评分图标素材
                        text = "评分 ${score}",
                        style = V3Type.caption1.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.brandBiliText,
                        ),
                    )
                    Spacer(Modifier.width(V3Space.sm))
                }
                Text(
                    text = "全 $totalEpisodes 话",
                    style = V3Type.caption1.copy(
                        color = colors.labelSecondary,
                    ),
                )
                if (followCount > 0) {
                    Spacer(Modifier.width(V3Space.sm))
                    Text(
                        text = "${com.example.biliv3.data.model.formatCount(followCount)} 人追",
                        style = V3Type.caption1.copy(
                            color = colors.labelSecondary,
                        ),
                    )
                }
            }
            if (evaluate.isNotEmpty()) {
                Spacer(Modifier.height(V3Space.xs))
                Text(
                    text = evaluate,
                    style = V3Type.footnote.copy(
                        color = colors.labelSecondary,
                    ),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(V3Space.sm))

            // ---- 追番按钮（此前「追番」功能完全不存在）----
            BrandButton(
                label = if (isFollowing) "已追番" else "追番",
                onClick = onToggleFollow,
                variant = if (isFollowing) BrandButtonVariant.Outline
                else BrandButtonVariant.Filled,
            )
        }
    }
}

/**
 * 单集格子。
 *
 * ## 可播与不可播的视觉区分
 *
 * 可播（有 bvid）：正常字色 + 可点
 * 不可播：字色弱化 + 右上角一个小圆点提示"官方 App"
 *
 * ⚠️ 但**仍然可点** —— 点了给出明确说明，而不是完全无响应。
 * 不可点又没有任何反馈，就是死入口。
 */
@Composable
private fun EpisodeCell(ep: BangumiEpisode, onClick: () -> Unit) {
    val colors = BiliV3.colors
    val playable = ep.playableInApp()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 集数格子：直角（列表项一律直角），只有交互态保留可点。
            .background(colors.bgSecondary)
            .clickable(onClick = onClick)
            .padding(vertical = V3Space.sm, horizontal = V3Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ep.index.ifEmpty { "?" },
            style = V3Type.callout.copy(
                fontWeight = FontWeight.Medium,
                color = if (playable) colors.labelPrimary else colors.labelSecondary,
            ),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        if (ep.title.isNotEmpty()) {
            Spacer(Modifier.height(V3Space.hairline))
            Text(
                text = ep.title,
                style = V3Type.caption2.copy(
                    color = colors.labelTertiary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        if (!playable) {
            Spacer(Modifier.height(V3Space.hairline))
            Text(
                text = "官方 App",
                // ⚠️ 原为 `fontSize = 9.sp` —— **不在类型阶梯上**。
                // v3 最小档是 caption2（11sp）；9sp 低于 CJK 字形的实用下限
                // （汉字在 9sp 下笔画粘连）。归到 caption2。
                style = V3Type.caption2,
                color = colors.labelTertiary,
                maxLines = 1,
            )
        }
    }
}

/** 选集列数。4 列在手机上每格约 80dp，够放「第 12 话」+ 标题。 */
private const val EPISODE_COLUMNS = 4
