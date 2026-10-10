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
import androidx.compose.foundation.layout.Row
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
import com.example.biliv3.ui.component.MonoReadout
import com.example.biliv3.ui.component.TechTag
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.player.PlayerHolder
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.video.DanmakuLayer
import com.example.biliv3.ui.video.SubtitleOverlay
import com.example.biliv3.ui.video.VideoPlayerSurface
import kotlinx.coroutines.delay
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.GlassSurface
import com.example.biliv3.design.v3.ProvideGlassBackdrop
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type
import com.example.biliv3.design.v3.v3GlassSurface
import com.example.biliv3.design.v3.V3Glass

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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    val colors = BiliV3.colors

    // 全屏纯黑：竖屏模式没有"页面底色"概念，画面即页面
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.playerBackground),
    ) {
        // ---- 全页玻璃化 ----
        //
        // 注入后本页所有 `biliCard()` / `GlassSurface` 都拿到真实视频帧，
        // 自动变成真毛玻璃。不需要逐个组件传 backdrop。
        com.example.biliv3.design.ProvideGlassBackdrop(
            backdrop = holder.backdrop,
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
                    color = colors.brand,
                    strokeWidth = V3Space.progressTrack,
                    modifier = Modifier.size(V3Size.iconLg * 1.5f),
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

                // ---- 毛玻璃帧源 ----
                //
                // 从共享 TextureView 持续抓帧，供底部信息区做玻璃底。
                // 与详情页共用同一个 backdrop（holder 持有），
                // 所以两边来回切时玻璃不会"空一拍"。
                com.example.biliv3.design.VideoBackdropEffect(
                    backdrop = holder.backdrop,
                    textureProvider = { holder.textureView },
                )

                // 页码变化 → 通知 ViewModel 切项 + 预加载相邻项
                //
                // ⚠️ 预加载从"仅 +1"扩到 **±1**：
                // 用户往上滑回来时，上一项若没预加载会重新取流 → 明显卡顿。
                // 上下各一项是"跟手"的最低要求（TikTok 也是这个范围）。
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }.collect { page ->
                        onPageChanged(page)
                        if (page + 1 <= state.items.lastIndex) onPreload(page + 1)
                        if (page - 1 >= 0) onPreload(page - 1)
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
                        .padding(end = V3Space.xs),
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
                            .padding(V3Space.xs)
                            .size(V3Size.iconLg + V3Space.sm)
                            .clip(CircleShape)
                            .background(colors.overlay)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = colors.labelOnMedia,
                            modifier = Modifier.size(V3Size.iconLg),
                        )
                    }
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
    val colors = BiliV3.colors
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
                    color = colors.labelOnMedia,
                    strokeWidth = V3Space.progressTrack,
                    modifier = Modifier.size(V3Size.iconLg * 1.5f),
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
    val colors = BiliV3.colors
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
        verticalArrangement = Arrangement.spacedBy(V3Space.lg),
    ) {
        ActionItem(
            icon = if (inter.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            tint = if (inter.liked) colors.brand else colors.labelOnMedia,
            label = count(detail?.likeCount),
            contentDescription = "点赞",
            onClick = onLike,
        )
        ActionItem(
            icon = if (inter.coined) Icons.Filled.MonetizationOn else Icons.Outlined.MonetizationOn,
            tint = if (inter.coined) colors.accentCoin else colors.labelOnMedia,
            label = count(detail?.coinCount),
            contentDescription = "投币",
            onClick = onCoin,
        )
        ActionItem(
            icon = if (inter.favored) Icons.Filled.Star else Icons.Outlined.StarBorder,
            tint = if (inter.favored) colors.accentFavorite else colors.labelOnMedia,
            label = count(detail?.favoriteCount),
            contentDescription = "收藏",
            onClick = onFavorite,
        )
        ActionItem(
            icon = Icons.Filled.Share,
            tint = colors.labelOnMedia,
            label = count(detail?.shareCount),
            contentDescription = "分享",
            onClick = onShare,
        )
        ActionItem(
            icon = Icons.Filled.PersonAdd,
            tint = if (state.following) colors.brand else colors.labelOnMedia,
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
    val colors = BiliV3.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // 交互元素 → 4dp（§5.2 圆角规则）。原来是 `V3Radius.md`(12dp)，
            // 那是卡片时代的"主体圆角"，对按钮偏大、且与全站交互元素不一致。
            .clip(RoundedCornerShape(V3Radius.xs))
            .clickable(onClick = onClick)
            .padding(horizontal = V3Space.xxs, vertical = V3Space.xxs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(ACTION_ICON),
        )
        Spacer(Modifier.height(V3Space.hairline))
        // 计数用等宽：数字每秒/每次互动都在变，比例字体下整列会左右抖动。
        // 竖屏互动栏是纵向排列的多个读数，抖动尤其明显。
        MonoReadout(
            text = label,
            color = colors.labelOnMedia,
            fontSize = V3Type.caption2.fontSize,
            weight = FontWeight.Medium,
        )
    }
}

/**
 * 左下角信息区：UP 名 + 标题 + 底部进度条。
 *
 * 底部用**渐变兜底**而不是纯色条 —— 纯色条会切掉画面，
 * 渐变能让画面自然过渡到文字区。
 */
/**
 * 底部信息区（毛玻璃）。
 *
 * ## 为什么用毛玻璃而不是纯渐变
 *
 * 纯渐变只能"压暗"，无法表达"这层玻璃浮在画面上"。
 * 毛玻璃让底下的画面**隐约透出来**，层次立刻立体 ——
 * 这是 TikTok 观感的核心之一，也是"不廉价"的关键：
 * 廉价感来自"一块死黑的色块盖住画面"。
 *
 * ## 实现方式
 *
 * 用 `GlassSurface`（`design/Glass.kt`）：
 * - 底：共享 TextureView 抓来的帧，缩小 8 倍再放大 → 天然模糊
 * - 上：半透明染色 + 1dp 高光边
 * - 内容（文字）**不被模糊**
 *
 * ## ⚠️ 兜底
 *
 * 抓不到帧时（未起播 / 无视频）退化为纯半透明底 ——
 * 玻璃的质感主要来自"半透明 + 高光边"，模糊只是锦上添花。
 * 所以不会出现"一块死黑"。
 */
@Composable
private fun BottomInfo(
    state: VerticalUiState,
    holder: PlayerHolder,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors
    val detail = state.detail

    // 底部信息区：走 v3 的玻璃原语。
    //
    // 🔴 这是**唯一"真玻璃"的场景**（背后有视频画面可糊）——
    // 外层 `ProvideGlassBackdrop` 已注入 backdrop，
    // `v3GlassSurface` 会自动读到它（含旧 `VideoBackdrop` 回退）。
    //
    // ## 为什么从 `biliCard` 换成 `v3GlassSurface`
    //
    // `biliCard` 是**旧设计系统**的卡片原语（`design/BiliCard.kt`），
    // 它的 KDoc 写着"圆角是卡片的语言"。这里虽然传了 `shape = 0.dp` 去掉圆角，
    // 但**用的是卡片原语** —— 而 v3 的玻璃是独立原语（`V3Glass`）。
    //
    // 两者不是同一个东西：`biliCard` 只做"背景采样 + 半透明 + 高光边"，
    // v3 的 `GlassSurface` 多了**两条路径**（Haze 真折射 / 内置四层合成）
    // 与**四层级**（UltraThin/Thin/Regular/Clear）。
    //
    // ⚠️ 圆角保持 0（直角）：这里是压在视频上的浮层，属"色块"语言，
    //    不是"卡片"。判据仍然成立，只是换了实现。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .v3GlassSurface(
                shape = RoundedCornerShape(0.dp),
                level = V3Glass.Level.UltraThin,
            )
            .navigationBarsPadding()
            .padding(
                start = V3Space.md,
                // 右侧留出互动栏宽度，避免文字压在图标下
                end = ACTION_BAR_RESERVED,
                top = V3Space.sm,
                bottom = V3Space.sm,
            ),
    ) {
        Text(
            text = "@${detail?.ownerName.orEmpty()}",
            style = V3Type.footnote.copy(
                // 15sp（原 12sp）：底部信息区是竖屏唯一的文字区，
                // 12sp 在 6 寸屏上明显偏小，与"大图标"的视觉重量不匹配。
                fontSize = V3Type.subheadline.fontSize,
                color = colors.labelOnMedia,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(V3Space.xxs))
        Text(
            text = detail?.title.orEmpty(),
            style = V3Type.callout.copy(
                color = colors.labelOnMedia,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // ---- 技术参数行（极客点缀，克制）----
        //
        // 只在**数据已就绪**时显示，且只放**技术属性**
        // （播放量 / 弹幕数），不放"分类名"这类语义标签 ——
        // 语义标签该用普通胶囊，方角微标签表达的是"参数"。
        //
        // ⚠️ 这一行是可选的：数据没到就整行不渲染，
        // 不留空位（否则会在加载完成瞬间"跳一下"）。
        val views = detail?.viewCount ?: 0
        val danmakuCount = detail?.danmakuCount ?: 0
        if (views > 0 || danmakuCount > 0) {
            Spacer(Modifier.height(V3Space.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(V3Space.xs)) {
                if (views > 0) {
                    TechTag(text = "▶ ${com.example.biliv3.data.model.formatCount(views)}")
                }
                if (danmakuCount > 0) {
                    TechTag(text = "◈ ${com.example.biliv3.data.model.formatCount(danmakuCount)}")
                }
            }
        }

        Spacer(Modifier.height(V3Space.xs))
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
    val colors = BiliV3.colors
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
            .clip(RoundedCornerShape(V3Radius.xs))
            // 未播轨道：走令牌而不是裸色值（`trackInactive` 就是为它定义的）
            .background(colors.trackInactive),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(PROGRESS_HEIGHT)
                .background(colors.labelOnMedia),
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
