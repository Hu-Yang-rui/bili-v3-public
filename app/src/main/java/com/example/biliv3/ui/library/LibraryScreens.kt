package com.example.biliv3.ui.library

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.biliv3.data.FavFolder
import com.example.biliv3.data.FavoriteEntry
import com.example.biliv3.data.HistoryEntry
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.data.model.formatRelativeTime
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ItemMoreMenu
import com.example.biliv3.ui.component.MoreMenuAction

/**
 * 历史记录页。
 *
 * ## 继续播放
 *
 * 每条显示上次看到的位置（进度条 + "看到 03:24"），点击进入详情页。
 * `progressSeconds` 来自历史接口的 `history.progress`（毫秒转秒）。
 */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onVideoClick: (String) -> Unit,
    onLoginRequired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryScaffold(
        title = "历史记录",
        onBack = onBack,
        modifier = modifier,
    ) {
        when {
            !isLoggedIn -> LoginRequiredPanel(onLoginRequired)

            loading -> LoadingPanel()

            entries.isEmpty() -> EmptyState(
                title = "还没有观看记录",
                      terminalStyle = true,
                description = "看过的视频会出现在这里",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LoadMoreList(
                items = entries,
                keyOf = { it.video.bvid + it.viewAt },
                loadingMore = loadingMore,
                hasMore = hasMore,
                onLoadMore = onLoadMore,
            ) { entry ->
                HistoryRow(entry = entry, onClick = { onVideoClick(entry.video.bvid) })
            }
        }
    }
}

/** 稍后再看页。 */
@Composable
fun ToViewScreen(
    videos: List<VideoItem>,
    loading: Boolean,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onLoginRequired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryScaffold(
        title = "稍后再看",
        onBack = onBack,
        modifier = modifier,
    ) {
        when {
            !isLoggedIn -> LoginRequiredPanel(onLoginRequired)
            loading -> LoadingPanel()
            videos.isEmpty() -> EmptyState(
                title = "稍后再看是空的",
                      terminalStyle = true,
                description = "在视频页点「稍后再看」加入",
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = Space.x2),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(videos, key = { it.bvid }, contentType = { "video" }) { v ->
                    VideoRow(video = v, onClick = { onVideoClick(v.bvid) })
                }
            }
        }
    }
}

/**
 * 我的收藏 —— **总览页**（收藏夹分组网格）。
 *
 * ## 架构（对照官方收藏页）
 *
 * ```
 * [← 我的收藏]                     ← 顶栏
 * [收藏夹] [全部] [视频] [图文]      ← 分类 Tab
 * ───────────────────────────────
 * 默认收藏夹              · 2856个内容 ▸   ← 分组标题（可点进详情）
 * [封面][封面][封面]                       ← 3 列网格
 *  标题   标题   标题
 * ───────────────────────────────
 * 动画 2                    · 48个内容 ▸
 * [封面][封面][封面]
 * ```
 *
 * ## 为什么是「分组 + 网格」而不是「一个扁平列表」
 *
 * 第一版是：顶部横向 Chip 选一个夹 → 下面一个纵向大卡片列表。
 * 问题是**看不到全局** —— 用户不知道一共有几个夹、每个夹有多少内容，
 * 必须逐个点开才知道。官方用"分组 + 每夹预览 3 张"一眼给全貌。
 *
 * 网格用 3 列（不是 2 列）：收藏夹预览图是**缩略图性质**，
 * 3 列能在一屏内塞下更多信息，且与官方一致。
 *
 * ## 为什么每组只显示 3 张
 *
 * 这是**预览**不是完整列表。一屏内能同时看到 4~5 个夹的分组，
 * 比"一个夹铺满整屏"有用得多。想看全部就点进详情页。
 */
@Composable
fun FavoriteScreen(
    folders: List<FavFolder>,
    /** 每个收藏夹的前几张预览。key = folderId。 */
    previews: Map<Long, List<FavoriteEntry>>,
    loading: Boolean,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onOpenFolder: (FavFolder) -> Unit,
    onVideoClick: (String) -> Unit,
    onLoginRequired: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryScaffold(
        title = "我的收藏",
        onBack = onBack,
        modifier = modifier,
    ) {
        when {
            !isLoggedIn -> LoginRequiredPanel(onLoginRequired)
            loading && folders.isEmpty() -> LoadingPanel()
            folders.isEmpty() -> EmptyState(
                title = "还没有收藏夹",
                      terminalStyle = true,
                description = "在视频页点「收藏」加入",
                actionLabel = "刷新",
                onAction = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = Space.x8),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(folders, key = { it.id }) { folder ->
                    FolderGroup(
                        folder = folder,
                        previews = previews[folder.id].orEmpty(),
                        onOpen = { onOpenFolder(folder) },
                        onVideoClick = onVideoClick,
                    )
                }
            }
        }
    }
}

/**
 * 一个收藏夹分组：标题行 + 3 列预览网格。
 *
 * 标题行整行可点（进收藏夹详情），右侧有 `▸` 与"· N个内容"，
 * 与官方一致 —— 把可点区域做成整行而不是只点小箭头。
 */
@Composable
private fun FolderGroup(
    folder: FavFolder,
    previews: List<FavoriteEntry>,
    onOpen: () -> Unit,
    onVideoClick: (String) -> Unit,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.x3, bottom = Space.x2),
    ) {
        // ---- 分组标题 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = Space.x4, vertical = Space.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠️ 标题用 weight(1f) 独占剩余空间，数量与箭头才会**贴齐右边缘**。
            //
            // 第一版写成 `weight(1f, fill = false)` + 再加一个 `Spacer(weight(1f))`
            // ——两个 weight 争夺同一段剩余空间，数量文字就被挤到中间偏右，
            // 而不同标题长度会让它**左右浮动**，看起来"没有固定在最右边"。
            Text(
                text = folder.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // ---- 公开 / 私密标识 ----
            //
            // 紧跟标题右侧（不是贴最右）—— 它是**标题的属性**，
            // 与标题同属一组信息；贴最右会和"内容数 + 箭头"混在一起，
            // 看起来像独立的第三列。
            //
            // 视觉刻意低调：小字号 + 弱底色胶囊，不抢标题。
            // 用文字而不是图标：图标需要额外学习成本，且"公开/私密"
            // 只有两个状态，文字最直白。
            Spacer(Modifier.width(Space.x2))
            VisibilityBadge(isPrivate = folder.isPrivate)

            Spacer(Modifier.width(Space.x2))
            Text(
                text = "· ${folder.mediaCount}个内容",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入收藏夹",
                tint = colors.textTertiary,
                modifier = Modifier.size(Sizes.iconLg),
            )
        }

        // ---- 3 列预览 ----
        if (previews.isEmpty()) {
            // 空夹也要占位，否则分组标题下面突然没了内容会以为是 bug
            Text(
                text = "这个收藏夹是空的",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textTertiary,
                ),
                modifier = Modifier.padding(
                    start = Space.x4,
                    end = Space.x4,
                    bottom = Space.x2,
                ),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.x4),
                horizontalArrangement = Arrangement.spacedBy(Space.x2),
            ) {
                // 固定 3 列：不足 3 张时用占位撑住列宽，
                // 避免 1 张图被拉成整行宽（比例失真）
                repeat(FAV_PREVIEW_COLUMNS) { i ->
                    val entry = previews.getOrNull(i)
                    Box(modifier = Modifier.weight(1f)) {
                        if (entry != null) {
                            FavPreviewCell(
                                entry = entry,
                                onClick = {
                                    // 失效视频不可跳转（没有有效详情页）
                                    if (!entry.isInvalid) onVideoClick(entry.video.bvid)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 收藏夹的「公开 / 私密」标识。
 *
 * ## 为什么用文字而不是图标
 *
 * 只有两个状态，文字最直白、零学习成本；图标（锁 / 地球）需要用户
 * 自己建立映射，反而慢。官方也是文字。
 *
 * ## 视觉为什么这么弱
 *
 * 它只是标题的**附属属性**，不该和标题抢视觉。所以：
 * - 字号用最小的 `FontSize.badge`
 * - 私密用中性底色 + 次文字色，**不用品牌粉** —— 粉色在这套主题里
 *   表示"可交互/激活"，拿来标记"私密"会让用户误以为可点
 * - 圆角用 `Radius.badge`(2dp) 的小标签规格，不是胶囊
 */
@Composable
private fun VisibilityBadge(isPrivate: Boolean) {
    val colors = BiliTheme.colors
    Text(
        text = if (isPrivate) "私密" else "公开",
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.badge,
            color = colors.textTertiary,
        ),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.badge))
            .background(colors.bgHover)
            .padding(horizontal = Space.tagHorizontal, vertical = Space.tagVertical),
    )
}

/** 收藏夹预览格：封面 + 标题一行。 */
@Composable
private fun FavPreviewCell(    entry: FavoriteEntry,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    val v = entry.video

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // C 方案：预览格也是卡片。
            // ⚠️ 用 elevation = 0（不浮起）—— 预览格是 3 列并排，
            // 每格都投影会在窄间距下互相压住，显得脏。
            .biliCard(elevation = 0.dp, shape = RoundedCornerShape(Radius.cover))
            .clickable(enabled = !entry.isInvalid, onClick = onClick)
            .padding(Space.x1),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Sizes.coverAspectRatio)
                .clip(RoundedCornerShape(Radius.tag))
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = v.coverUrl(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (entry.isInvalid) 0.35f else 1f },
            )
        }
        Spacer(Modifier.height(Space.x1))
        Text(
            text = if (entry.isInvalid) "已失效视频" else v.title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                lineHeight = FontSize.labelLine,
                color = if (entry.isInvalid) colors.textTertiary else colors.textPrimary,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Space.x1, vertical = Space.x1),
        )
    }
}

/**
 * 收藏夹**详情页**（单个收藏夹的完整列表）。
 *
 * ## 结构（对照官方收藏夹内页）
 *
 * ```
 * [← 默认收藏夹]              [🔍] [⋮]
 * 创建者：xxx
 * 2856个内容
 * ───────────────────────────────
 * [封面] 标题两行…                    ⋮
 *  00:24  UP名 · 2130 · 1
 * ───────────────────────────────
 * ```
 *
 * ## 为什么这里用「大图列表」而不是网格
 *
 * 详情页是"逐条看/逐条管理"的场景，需要看清标题全文与元信息；
 * 而总览页是"扫一眼"。两个页面信息密度需求不同，
 * 用同一套布局反而两头不讨好 —— 官方也是这个分工。
 */
@Composable
fun FavoriteFolderScreen(
    folder: FavFolder,
    entries: List<FavoriteEntry>,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onRemove: (FavoriteEntry) -> Unit,
    onVideoClick: (String) -> Unit,
    onLoginRequired: () -> Unit,
    onShare: (FavoriteEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 当前打开 ⋮ 菜单的条目（null = 未打开）
    var menuEntry by remember { mutableStateOf<FavoriteEntry?>(null) }

    LibraryScaffold(
        title = folder.title,
        onBack = onBack,
        modifier = modifier,
    ) {
        when {
            !isLoggedIn -> LoginRequiredPanel(onLoginRequired)
            loading && entries.isEmpty() -> LoadingPanel()
            entries.isEmpty() -> EmptyState(
                title = "这个收藏夹是空的",
                      terminalStyle = true,
                description = "在视频页点「收藏」加入",
                modifier = Modifier.fillMaxSize(),
            )
            else -> LoadMoreList(
                items = entries,
                keyOf = { it.favItemId.toString() },
                loadingMore = loadingMore,
                hasMore = hasMore,
                onLoadMore = onLoadMore,
            ) { e ->
                FavoriteRow(
                    entry = e,
                    onClick = { onVideoClick(e.video.bvid) },
                    onMore = { menuEntry = e },
                )
            }
        }
    }

    // ---- ⋮ 更多菜单 ----
    //
    // ⚠️ 放在 LibraryScaffold **外层**而不是每行内部：
    // 在行的闭包里渲染 Dialog，行被回收（LazyColumn 复用）时
    // 菜单会被一起销毁。挂在外层由页面级别持有，生命周期稳定。
    menuEntry?.let { e ->
        ItemMoreMenu(
            title = e.video.title,
            isFavorited = true,
            onDismiss = { menuEntry = null },
            onShareChannel = {
                menuEntry = null
                onShare(e)
            },
            onToggleFavorite = {
                menuEntry = null
                onRemove(e)
            },
            extraActions = listOf(
                MoreMenuAction(
                    label = "查看 UP 主",
                    icon = Icons.Outlined.Person,
                    onClick = {
                        menuEntry = null
                        onVideoClick(e.video.bvid)
                    },
                ),
                MoreMenuAction(
                    label = "复制标题",
                    icon = Icons.Outlined.ContentCopy,
                    onClick = {
                        menuEntry = null
                        onShare(e)
                    },
                ),
            ),
        )
    }
}

/**
 * 收藏行：封面（带时长角标）+ 标题 + UP + 更多菜单。
 *
 * ## 为什么右侧是 ⋮ 而不是删除按钮（用户明确要求）
 *
 * 原先每行右侧放一个红色的删除图标，问题是：
 * 1. **误删风险高** —— 删除是破坏性操作，直接暴露在列表上太危险
 * 2. 一个图标只能表达一个动作，而用户对该条目的意图有多种
 *    （分享 / 查看 UP / 取消收藏）
 *
 * 改为 ⋮ 更多菜单（对照官方）：点开才展开所有操作，
 * 破坏性操作用**文字**表达（「取消收藏」）而不是裸图标。
 *
 * 失效视频**仍然展示**并标注，让用户能看见并清理。
 * 直接隐藏会让收藏数对不上，用户以为丢数据。
 */
@Composable
private fun FavoriteRow(
    entry: FavoriteEntry,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = BiliTheme.colors
    val v = entry.video

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 收藏行**不再是卡片**（原为每行一张 `biliCard`）。
            //
            // 收藏夹里通常几十上百条，每行一张卡 = 一屏 5~6 个框。
            // 框多了以后内容反而退到次要位置，是"臃肿"的主要来源。
            //
            // 现在：行与行靠留白分隔。**列表用留白分组，不用卡片分组。**
            //
            // 失效视频没有可跳转的详情页，禁用点击而不是跳过去报错
            .clickable(enabled = !entry.isInvalid, onClick = onClick)
            .padding(horizontal = Space.x4, vertical = Space.x2),
        // ⚠️ Bottom：⋮ 要贴**右下角**，不是垂直居中。
        //
        // 居中时它会浮在行高中间，与右侧文字列"对齐但不贴合"，
        // 看起来像悬空的一颗点。贴右下角后与行的右下角对齐，
        // 视觉上有明确的落点（官方也是右下）。
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .width(HISTORY_THUMB_WIDTH)
                .height(HISTORY_THUMB_HEIGHT)
                .clip(RoundedCornerShape(Radius.cover))
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = v.coverUrl(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (entry.isInvalid) 0.35f else 1f },
            )
            // 时长角标（官方在缩略图右下角）
            if (v.durationLabel.isNotEmpty()) {
                Text(
                    text = v.durationLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.onOverlay,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(Radius.badge))
                        .background(colors.overlayCover)
                        .padding(horizontal = Space.tagHorizontal, vertical = Space.tagVertical),
                )
            }
        }
        Spacer(Modifier.width(Space.x3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (entry.isInvalid) "已失效视频" else v.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = if (entry.isInvalid) colors.textTertiary else colors.textPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.x2))

            // UP 头像 + 昵称 + 播放量 + 弹幕数（一行，紧凑）
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (v.authorName.isNotEmpty()) {
                    AsyncImage(
                        model = v.faceUrl(48),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(colors.coverPlaceholder),
                    )
                    Spacer(Modifier.width(Space.x1))
                }
                Text(
                    text = buildString {
                        if (v.authorName.isNotEmpty()) append(v.authorName)
                        if (v.playCount > 0) {
                            if (isNotEmpty()) append("  ")
                            append(formatCount(v.playCount))
                        }
                        if (v.danmakuCount > 0) {
                            append("  ")
                            append(formatCount(v.danmakuCount))
                        }
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ---- 更多菜单（⋮）----
        // 用 ⋮ 而不是删除图标：删除是破坏性操作，不适合裸暴露在列表上；
        // 且 ⋮ 可承载多个动作（分享 / 查看 UP / 取消收藏）
        //
        // 视觉 18dp（比原先的 iconLg 更小）—— 它只是次要操作入口，
        // 不该在列表里抢视觉。命中区仍是 48dp（外层透明 Box 撑开），
        // 所以"看着小、点着准"。
        Box(
            modifier = Modifier
                .size(Space.minTouchTarget)
                .clip(CircleShape)
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多操作",
                tint = colors.textTertiary,
                modifier = Modifier.size(Sizes.iconMd),
            )
        }
    }
}

/** 总览页每行的预览列数。3 列与官方一致。 */
private const val FAV_PREVIEW_COLUMNS = 3

// ---------------------------------------------------------------------------
// 共用外壳
// ---------------------------------------------------------------------------

/** 二级页外壳：顶栏 + 返回 + 内容区。 */
@Composable
private fun LibraryScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = BiliTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // C 方案：顶栏通栏卡片，只留下方圆角（贴屏幕顶）
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
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        content()
    }
}

/** 触底加载的列表封装。 */
@Composable
private fun <T> LoadMoreList(
    items: List<T>,
    keyOf: (T) -> String,
    loadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    row: @Composable (T) -> Unit,
) {
    val colors = BiliTheme.colors
    val state = rememberLazyListState()

    // 距底 3 项触发
    val shouldLoad by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last.index >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { shouldLoad }.collect { if (it) onLoadMore() }
    }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(vertical = Space.x2),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = keyOf, contentType = { "row" }) { item -> row(item) }

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

/** 历史记录行：封面 + 标题 + 进度。 */
@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    val colors = BiliTheme.colors
    val v = entry.video

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 列表行**不再是卡片** —— 与收藏行同一原则：
            // 列表用留白分组，不用卡片分组。
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x4, vertical = Space.x2),
        verticalAlignment = Alignment.Top,
    ) {
        // 封面 + 底部进度条
        Box(
            modifier = Modifier
                .width(HISTORY_THUMB_WIDTH)
                .height(HISTORY_THUMB_HEIGHT)
                .clip(RoundedCornerShape(Radius.cover))
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = v.coverUrl(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 观看进度条：只在看了但没看完时显示
            if (entry.progressRatio > 0.01f && !entry.isFinished) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(Space.trackHeight)
                        .background(colors.overlayCover),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(entry.progressRatio)
                            .height(Space.trackHeight)
                            .background(colors.brandPrimary),
                    )
                }
            }
        }
        Spacer(Modifier.width(Space.x3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = v.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.x1))
            Text(
                text = buildString {
                    append(v.authorName)
                    append(" · ")
                    append(formatRelativeTime(entry.viewAt))
                },
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 继续播放提示
            if (entry.progressSeconds > 0 && !entry.isFinished) {
                Spacer(Modifier.height(Space.micro))
                Text(
                    text = "看到 ${formatDuration(entry.progressSeconds)}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.brandPrimary,
                    ),
                )
            }
        }
    }
}

/** 通用视频行（稍后再看 / 收藏夹共用）。 */
@Composable
private fun VideoRow(video: VideoItem, onClick: () -> Unit) {
    val colors = BiliTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 列表行**不再是卡片** —— 与收藏行同一原则：
            // 列表用留白分组，不用卡片分组。
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x4, vertical = Space.x2),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(HISTORY_THUMB_WIDTH)
                .height(HISTORY_THUMB_HEIGHT)
                .clip(RoundedCornerShape(Radius.cover))
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = video.coverUrl(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(Space.x3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.x1))
            Text(
                text = "${video.authorName} · ${formatCount(video.playCount)} 播放",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 未登录引导。 */
@Composable
private fun LoginRequiredPanel(onLogin: () -> Unit) {
    val colors = BiliTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Space.x8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "登录后查看",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = FontSize.titleMd,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            ),
        )
        Spacer(Modifier.height(Space.x2))
        Text(
            text = "该功能需要登录 B 站账号",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = FontSize.bodySm,
                color = colors.textSecondarySafe,
            ),
        )
        Spacer(Modifier.height(Space.x5))
        com.example.biliv3.ui.component.BrandButton(
            label = "去登录",
            onClick = onLogin,
            variant = com.example.biliv3.ui.component.BrandButtonVariant.Filled,
        )
    }
}

@Composable
private fun LoadingPanel() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = BiliTheme.colors.brandPrimary,
            strokeWidth = Space.trackHeight,
            modifier = Modifier.size(Sizes.iconXl * 1.5f),
        )
    }
}

/** 秒 → `MM:SS` / `H:MM:SS`。 */
private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "00:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/** 历史/收藏列表缩略图尺寸。 */
/**
 * 列表行缩略图尺寸。
 *
 * ## ⚠️ C 方案：160×90 → **128×80**
 *
 * 首版 160×90（16:9）。C 方案给行加了卡片容器后，160dp 宽的缩略图
 * 在卡片里占了近一半宽度，右侧标题区被挤到只剩 2~3 个字就折行
 * （实测截图可见「无沟世吞防雪V2好维护少材料」这种长标题严重折行）。
 *
 * 收到 128dp：仍保持 16:10（与封面一致，修「同一视频形状不同」的问题），
 * 但给标题让出约 32dp 宽度。
 */
private val HISTORY_THUMB_WIDTH = 128.dp
private val HISTORY_THUMB_HEIGHT = 80.dp
