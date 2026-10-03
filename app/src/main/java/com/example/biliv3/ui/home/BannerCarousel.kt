package com.example.biliv3.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.biliv3.data.model.BannerItem
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Motion
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Banner 轮播。
 *
 * ## 规格
 *
 * | 项 | 桌面 | 平板 | 移动 |
 * |---|---|---|---|
 * | 高度 | 300 | 240 | 160 |
 * | 箭头 | 常驻 | 常驻 | **隐藏** |
 *
 * ## 交互
 *
 * - **5s 自动切换**，鼠标悬停**暂停**，移开恢复
 * - 左右箭头切换（循环）
 * - 指示点可点击跳转
 * - 移动端靠手势滑动
 * - 页面不可见时不消耗资源（Compose 的 LaunchedEffect 随组合离开自动取消）
 *
 * ## 空数据
 *
 * **整块不渲染**（不留空白）—— 由调用方判断 `banners.isEmpty()`。
 *
 * ## 无障碍
 *
 * - 每张图有 contentDescription
 * - 指示点有语义标签
 * - 遵循「减少动态」时关闭自动播放（这里用系统动画缩放判断）
 */
@Composable
fun BannerCarousel(
    banners: List<BannerItem>,
    windowSize: WindowSize,
    modifier: Modifier = Modifier,
    onClick: (BannerItem) -> Unit = {},
) {
    if (banners.isEmpty()) return

    val colors = BiliTheme.colors
    val height = when (windowSize) {
        WindowSize.Desktop -> Sizes.bannerDesktop
        WindowSize.Tablet -> Sizes.bannerTablet
        WindowSize.Mobile -> Sizes.bannerMobile
    }

    val pagerState = rememberPagerState(pageCount = { banners.size })
    val scope = rememberCoroutineScope()

    // 悬停暂停
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // 自动播放：悬停时不切换
    LaunchedEffect(pagerState, hovered, banners.size) {
        if (hovered || banners.size <= 1) return@LaunchedEffect
        while (true) {
            delay(AUTO_PLAY_MS)
            if (hovered) continue
            val next = (pagerState.currentPage + 1) % banners.size
            pagerState.animateScrollToPage(next)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            // 🔴 无卡片重构：Banner 是**图片**，一律直角（§5.1 硬规则 2）。
            //
            // 上一版是 `clip(card) + shadow(rest)` —— 一条横过来的圆角卡片。
            // 网格去掉卡片后，它是首页剩下的最大一个"盒子"。
            //
            // ⚠️ 同时**删掉 `Modifier.shadow()`**（§5.2 明确禁止）：
            // 深色底上投影渲染出来几乎为零，白费一次离屏合成。
            // 它与下方内容的分离靠 `sectionSpacing`（44dp）承担，不靠投影。
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick(banners[pagerState.currentPage]) },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = banners[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.skeletonBase),
            ) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title.ifEmpty { "推荐位 ${page + 1}" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                // 底部渐变遮罩：保证压在上面的标题与指示点始终可读
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    colors.gradientMediaEnd,
                                ),
                            ),
                        ),
                )

                // 标题（压左下）
                if (item.title.isNotEmpty()) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = FontSize.display,
                            fontWeight = FontWeight.Bold,
                            color = colors.onOverlay,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = Space.x5,
                                bottom = Space.x6 + Space.x2,
                                end = Space.x5,
                            ),
                    )
                }
            }
        }

        // ---- 左右箭头（移动端隐藏）----
        if (windowSize != WindowSize.Mobile && banners.size > 1) {
            CarouselArrow(
                icon = Icons.Filled.ChevronLeft,
                label = "上一张",
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Space.x4),
                onClick = {
                    val prev = (pagerState.currentPage - 1 + banners.size) % banners.size
                    scope.launch { pagerState.animateScrollToPage(prev) }
                },
            )
            CarouselArrow(
                icon = Icons.Filled.ChevronRight,
                label = "下一张",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = Space.x4),
                onClick = {
                    val next = (pagerState.currentPage + 1) % banners.size
                    scope.launch { pagerState.animateScrollToPage(next) }
                },
            )
        }

        // ---- 指示点 ----
        if (banners.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Space.x4),
                horizontalArrangement = Arrangement.spacedBy(Space.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(banners.size) { i ->
                    val isCurrent = i == pagerState.currentPage
                    // 当前项由 8dp 圆点拉长为 24×8 胶囊
                    val width by animateFloatAsState(
                        targetValue = if (isCurrent) 24f else 8f,
                        animationSpec = tween(Motion.CAROUSEL_MS, easing = Motion.carousel),
                        label = "dotWidth",
                    )
                    Box(
                        modifier = Modifier
                            .size(width = width.dp, height = 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCurrent) colors.onOverlay else colors.trackInactive,
                            )
                            .clickable(
                                // 点击指示点直接跳转到对应页
                                onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            ),
                    )
                }
            }
        }
    }
}

/** 轮播左右箭头。44×44 圆形，半透明白底。 */
@Composable
private fun CarouselArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (hovered) colors.onOverlay else colors.qrSurface,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            // ⚠️ 固定深色，**不能**用 textPrimary ——
            // 这个箭头的底是恒为浅色的胶囊（qrSurface / onOverlay），
            // 深色主题下 textPrimary 是近白色，压在白底上会看不见。
            tint = colors.onQrSurface,
            modifier = Modifier.size(Sizes.iconXl),
        )
    }
}

/** 自动播放间隔。 */
private const val AUTO_PLAY_MS = 5000L
