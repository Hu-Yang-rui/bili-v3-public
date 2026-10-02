package com.example.biliv3.ui.vertical

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.example.biliv3.data.danmaku.DanmakuItem
import com.example.biliv3.data.model.PlayInfo
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.data.subtitle.SubtitleBody
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.player.PlayerHolder
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.video.DanmakuLayer
import com.example.biliv3.ui.video.SubtitleOverlay
import com.example.biliv3.ui.video.VideoPlayerSurface
import kotlinx.coroutines.delay

/**
 * 竖屏沉浸式观看模式。
 *
 * ## 布局（全屏，无顶栏无底栏）
 *
 * ```
 * ┌─────────────────────────┐
 * │ [←]                     │  ← 悬浮返回，2.5s 后淡出（点空白可再唤出）
 * │                         │
 * │      视频画面            │  ← 占满整屏
 * │                         │
 * │              ♡ 1.2万    │  ← 右侧竖排互动栏
 * │              ◎ 342      │
 * │              ★ 89       │
 * │              ↗ 分享     │
 * │              ⊕ 关注     │
 * │                         │
 * │ @UP名                   │  ← 左下角信息（渐变兜底可读）
 * │ 标题两行…               │
 * │ ━━━━━━━━━━━━━━━━━━━━    │  ← 底部细进度条
 * └─────────────────────────┘
 * ```
 *
 * ## 关键设计取舍
 *
 * ### 1. 复用 Activity 级 [PlayerHolder]，不自己建播放器
 *
 * 与详情页共用同一实例（`container.playerHolder`），切到竖屏不中断播放。
 * 代价是必须正确 `acquire/release`，否则回详情页会拿到已释放的播放器。
 *
 * ### 2. 只有当前页绑定媒体
 *
 * `VerticalPager` 会预组合相邻页。若每页都挂 [VideoPlayerSurface]，
 * 会有**两个 `AndroidView(PlayerView)` 同时 attach 到同一个 ExoPlayer** ——
 * 表现为画面闪烁或黑屏。所以非当前页只显示封面。
 *
 * ### 3. 互动栏用图标 + 数字
 *
 * 竖屏画面是主体，互动必须"看得见但不抢戏"。图标 + 计数是短视频平台的
 * 通用语言，零学习成本。
 */
@Composable
fun VerticalScreen(
    state: VerticalUiState,
    holder: PlayerHolder,
    danmaku: List<DanmakuItem>,
    danmakuEnabled: Boolean,
    danmakuAlpha: Float,
    danmakuFontScale: Float,
    activeSubtitle: SubtitleBody?,
    onBack: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onPreload: (Int) -> Unit,
    onLike: () -> Unit,
    onCoin: () -> Unit,
    onFavorite: () -> Unit,
    onFollow: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onPlayerError: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    // 全屏纯黑：竖屏模式没有"页面底色"概念，画面即页面
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.playerBackground),
    ) {
        when {
            state.error != null && state.items.isEmpty() -> ErrorState(
                title = "竖屏内容加载失败",
                description = state.error,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )

            state.loading && state.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.brandPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(Sizes.iconXl * 1.5f),
                )
            }

            state.items.isEmpty() -> ErrorState(
                title = "暂时没有竖屏视频",
                description = "推荐流里竖屏内容较少，稍后再试",
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )

            else -> {
                val pagerState = rememberPagerState(
                    initialPage = state.currentIndex.coerceIn(0, state.items.lastIndex),
                    pageCount = { state.items.size },
                )

                // 页码变化 → 通知 ViewModel 切项 + 预加载后一项
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }.collect { page ->
                        onPageChanged(page)
                        if (page + 1 <= state.items.lastIndex) onPreload(page + 1)
                    }
                }

                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val item = state.items.getOrNull(page) ?: return@VerticalPager
                    val isCurrent = page == state.currentIndex

                    VerticalPage(
                        coverUrl = item.coverUrl(720),
                        title = item.title,
                        playInfo = if (isCurrent) state.playInfo else null,
                        player = holder.player,
                        holder = holder,
                        danmaku = if (isCurrent) danmaku else emptyList(),
                        danmakuEnabled = danmakuEnabled,
                        danmakuAlpha = danmakuAlpha,
                        danmakuFontScale = danmakuFontScale,
                        activeSubtitle = if (isCurrent) activeSubtitle else null,
                        isCurrent = isCurrent,
                        onPlayerError = onPlayerError,
                    )
                }

                // ---- 右侧互动栏 ----
                RightActionBar(
                    state = state,
                    onLike = onLike,
                    onCoin = onCoin,
                    onFavorite = onFavorite,
                    onFollow = onFollow,
                    onShare = onShare,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = Space.x2),
                )

                // ---- 左下角信息 + 底部进度 ----
                BottomInfo(
                    state = state,
                    holder = holder,
                    modifier = Modifier.align(Alignment.BottomStart),
                )

                // ---- 顶部返回（2.5s 后淡出；点空白唤回）----
                var showBack by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(BACK_AUTO_HIDE_MS)
                    showBack = false
                }

                // 点空白切换返回键显隐。放在最上层但只消费"轻点"，
                // 不影响 VerticalPager 的纵向拖拽（拖拽走 onDrag 不是 onTap）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { showBack = !showBack })
                        },
                )

                AnimatedVisibility(
                    visible = showBack,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(Space.x2)
                            .size(Sizes.iconXl + Space.x3)
                            .clip(CircleShape)
                            .background(colors.overlayCover)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                            modifier = Modifier.size(Sizes.iconXl),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单页内容：封面打底 + 当前页才挂播放器画面。
 *
 * ## 为什么非当前页只显示封面
 *
 * `VerticalPager` 会预组合相邻页。若每页都挂 [VideoPlayerSurface]，
 * 就会有两个 `AndroidView(PlayerView)` 同时 attach 到同一个 ExoPlayer，
 * 表现为画面在两个 View 之间闪烁、或干脆黑屏。
 *
 * 所以非当前页只显示封面，滑到它时再挂画面。
 */
@Composable
private fun VerticalPage(
    coverUrl: String,
    title: String,
    playInfo: PlayInfo?,
    player: ExoPlayer?,
    holder: PlayerHolder,
    danmaku: List<DanmakuItem>,
    danmakuEnabled: Boolean,
    danmakuAlpha: Float,
    danmakuFontScale: Float,
    activeSubtitle: SubtitleBody?,
    isCurrent: Boolean,
    onPlayerError: (String) -> Unit,
) {
    val colors = BiliTheme.colors
    Box(modifier = Modifier.fillMaxSize()) {
        // 封面铺满：竖屏内容封面本身是 9:16，直接裁切填充
        AsyncImage(
            model = coverUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // 当前页才挂真实画面
        if (isCurrent && playInfo != null && player != null) {
            VideoPlayerSurface(
                info = playInfo,
                player = player,
                // 传 holder：由它判断"是否需要重新装配"。
                // 不传的话每次重组都会 setMediaSource，把已缓冲的进度扔掉。
                holder = holder,
                onError = onPlayerError,
                modifier = Modifier.fillMaxSize(),
            )
            DanmakuLayer(
                player = player,
                danmaku = danmaku,
                enabled = danmakuEnabled,
                alpha = danmakuAlpha,
                fontScale = danmakuFontScale,
                modifier = Modifier.fillMaxSize(),
            )
            // 字幕叠在弹幕之上（字幕优先可读）
            SubtitleOverlay(
                player = player,
                body = activeSubtitle,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 当前页但取流还没回来 → 转圈
        if (isCurrent && playInfo == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(Sizes.iconXl * 1.5f),
                )
            }
        }

        // 非当前页压暗一档，突出"当前项"
        if (!isCurrent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.scrim),
            )
        }
    }
}

/**
 * 右侧竖排互动栏。
 *
 * 顺序按使用频率：点赞 > 投币 > 收藏 > 分享 > 关注。
 * 关注放最后 —— 它作用于 **UP** 而非当前视频，语义不同。
 */
@Composable
private fun RightActionBar(
    state: VerticalUiState,
    onLike: () -> Unit,
    onCoin: () -> Unit,
    onFavorite: () -> Unit,
    onFollow: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    val detail = state.detail
    val inter = state.interaction

    /**
     * 计数文案。
     *
     * ⚠️ 详情未加载时显示**空串**而不是 `0` ——
     * 显示 0 会让人以为"这个视频没人点赞"，是错误信息。
     * 加载完再显示真实数字。
     */
    fun count(n: Int?): String = if (detail == null || n == null) "" else formatCount(n)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.x5),
    ) {
        ActionItem(
            icon = if (inter.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            tint = if (inter.liked) colors.brandPrimary else Color.White,
            label = count(detail?.likeCount),
            contentDescription = "点赞",
            onClick = onLike,
        )
        ActionItem(
            icon = if (inter.coined) Icons.Filled.MonetizationOn else Icons.Outlined.MonetizationOn,
            tint = if (inter.coined) colors.accentCoin else Color.White,
            label = count(detail?.coinCount),
            contentDescription = "投币",
            onClick = onCoin,
        )
        ActionItem(
            icon = if (inter.favored) Icons.Filled.Star else Icons.Outlined.StarBorder,
            tint = if (inter.favored) colors.accentFavorite else Color.White,
            label = count(detail?.favoriteCount),
            contentDescription = "收藏",
            onClick = onFavorite,
        )
        ActionItem(
            icon = Icons.Filled.Share,
            tint = Color.White,
            label = count(detail?.shareCount),
            contentDescription = "分享",
            onClick = onShare,
        )
        ActionItem(
            icon = Icons.Filled.PersonAdd,
            tint = if (state.following) colors.brandPrimary else Color.White,
            label = if (state.following) "已关注" else "关注",
            contentDescription = "关注",
            onClick = onFollow,
        )
    }
}

/** 单个互动项：图标 + 计数。 */
@Composable
private fun ActionItem(
    icon: ImageVector,
    tint: Color,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.button))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x1, vertical = Space.x1),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(ACTION_ICON),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.badge,
                color = Color.White,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 左下角信息区：UP 名 + 标题 + 底部进度条。
 *
 * 底部用**渐变兜底**而不是纯色条 —— 纯色条会切掉画面，
 * 渐变能让画面自然过渡到文字区。
 */
@Composable
private fun BottomInfo(
    state: VerticalUiState,
    holder: PlayerHolder,
    modifier: Modifier = Modifier,
) {
    val detail = state.detail

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xCC000000)),
                ),
            )
            .navigationBarsPadding()
            .padding(
                start = Space.x4,
                // 右侧留出互动栏宽度，避免文字压在图标下
                end = ACTION_BAR_RESERVED,
                top = Space.x8,
                bottom = Space.x3,
            ),
    ) {
        Text(
            text = "@${detail?.ownerName.orEmpty()}",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Space.x1))
        Text(
            text = detail?.title.orEmpty(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                lineHeight = FontSize.bodyLine,
                color = Color.White,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Space.x2))
        VerticalProgress(holder = holder)
    }
}

/**
 * 底部播放进度条。
 *
 * 竖屏模式没有控制条（画面优先），但完全不给进度反馈会让人
 * 不知道"播到哪了 / 是不是卡了"，所以保留一条细线。
 */
@Composable
private fun VerticalProgress(holder: PlayerHolder) {
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(holder) {
        while (true) {
            val dur = holder.duration
            val pos = holder.currentPosition
            progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            delay(PROGRESS_POLL_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PROGRESS_HEIGHT)
            .clip(RoundedCornerShape(Radius.badge))
            .background(Color(0x4DFFFFFF)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(PROGRESS_HEIGHT)
                .background(Color.White),
        )
    }
}

/** 互动图标尺寸。 */
private val ACTION_ICON = 30.dp

/** 底部信息区右侧为互动栏预留的宽度。 */
private val ACTION_BAR_RESERVED = 64.dp

/** 进度条高度。 */
private val PROGRESS_HEIGHT = 2.dp

/** 返回键自动隐藏延时。 */
private const val BACK_AUTO_HIDE_MS = 2500L

/** 进度轮询间隔。 */
private const val PROGRESS_POLL_MS = 500L
