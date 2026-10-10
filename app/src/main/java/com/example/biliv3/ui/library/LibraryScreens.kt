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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.example.biliv3.design.ruleBottom
// ---- v1.3.0 批量整理新增 ----
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ItemMoreMenu
import com.example.biliv3.ui.component.MoreMenuAction
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

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
                contentPadding = PaddingValues(vertical = V3Space.xs),
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
    /**
     * 加载失败原因（null = 没有失败）。v1.5.2 新增。
     *
     * ⚠️ 必须与"真的没有收藏夹"分开渲染 —— 否则任何失败
     * （未登录 / mid 无效 / 网络断 / 风控）都会显示成
     * 「还没有收藏夹」这句**假的事实断言**（§7.8-44 同类错误）。
     */
    error: String? = null,
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
            // 🔴 v1.5.2：错误态**优先于空态** —— 失败不能显示成「还没有收藏夹」
            error != null && folders.isEmpty() -> EmptyState(
                title = "收藏夹加载失败",
                terminalStyle = true,
                description = error,
                actionLabel = "重试",
                onAction = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
            folders.isEmpty() -> EmptyState(
                title = "还没有收藏夹",
                terminalStyle = true,
                description = "在视频页点「收藏」加入",
                actionLabel = "刷新",
                onAction = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = V3Space.xxl),
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
    val colors = BiliV3.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = V3Space.sm, bottom = V3Space.xs),
    ) {
        // ---- 分组标题 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = V3Space.md, vertical = V3Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠️ 标题用 weight(1f) 独占剩余空间，数量与箭头才会**贴齐右边缘**。
            //
            // 第一版写成 `weight(1f, fill = false)` + 再加一个 `Spacer(weight(1f))`
            // ——两个 weight 争夺同一段剩余空间，数量文字就被挤到中间偏右，
            // 而不同标题长度会让它**左右浮动**，看起来"没有固定在最右边"。
            Text(
                text = folder.title,
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
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
            Spacer(Modifier.width(V3Space.xs))
            VisibilityBadge(isPrivate = folder.isPrivate)

            Spacer(Modifier.width(V3Space.xs))
            Text(
                text = "· ${folder.mediaCount}个内容",
                style = V3Type.caption1.copy(
                    color = colors.labelSecondary,
                ),
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入收藏夹",
                tint = colors.labelTertiary,
                modifier = Modifier.size(V3Size.iconMd),
            )
        }

        // ---- 3 列预览 ----
        if (previews.isEmpty()) {
            // 空夹也要占位，否则分组标题下面突然没了内容会以为是 bug
            Text(
                text = "这个收藏夹是空的",
                style = V3Type.caption1.copy(
                    color = colors.labelTertiary,
                ),
                modifier = Modifier.padding(
                    start = V3Space.md,
                    end = V3Space.md,
                    bottom = V3Space.xs,
                ),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = V3Space.md),
                horizontalArrangement = Arrangement.spacedBy(V3Space.xs),
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
 * - 字号用最小的 `V3Type.caption2.fontSize`
 * - 私密用中性底色 + 次文字色，**不用品牌粉** —— 粉色在这套主题里
 *   表示"可交互/激活"，拿来标记"私密"会让用户误以为可点
 * - 圆角用 `V3Radius.xs`(2dp) 的小标签规格，不是胶囊
 */
@Composable
private fun VisibilityBadge(isPrivate: Boolean) {
    val colors = BiliV3.colors
    Text(
        text = if (isPrivate) "私密" else "公开",
        style = V3Type.caption2.copy(
            color = colors.labelTertiary,
        ),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(V3Radius.xs))
            .background(colors.bgTertiary)
            .padding(horizontal = V3Space.tagHorizontal, vertical = V3Space.tagVertical),
    )
}

/** 收藏夹预览格：封面 + 标题一行。 */
@Composable
private fun FavPreviewCell(    entry: FavoriteEntry,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    val v = entry.video

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 无卡片：预览格直接排在网格里，分组靠列间距
            .clickable(enabled = !entry.isInvalid, onClick = onClick)
            .padding(V3Space.xxs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(V3Size.coverAspect)
                // 封面直角 —— 圆角只留给交互元素
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
        Spacer(Modifier.height(V3Space.xxs))
        Text(
            text = if (entry.isInvalid) "已失效视频" else v.title,
            style = V3Type.caption1.copy(
                color = if (entry.isInvalid) colors.labelTertiary else colors.labelPrimary,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = V3Space.xxs, vertical = V3Space.xxs),
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
    // ---- v1.3.0 批量整理 ----
    /** 其他收藏夹（批量移动的目标，不含当前夹）。 */
    otherFolders: List<FavFolder> = emptyList(),
    selection: BatchSelection = remember { BatchSelection() },
    batchRunning: Boolean = false,
    batchProgress: Pair<Int, Int>? = null,
    onToggleSelect: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onSelectPage: () -> Unit = {},
    onBatchMove: (Long) -> Unit = {},
    onBatchRemove: () -> Unit = {},
    onBatchAddToView: () -> Unit = {},
    onBatchAddToQueue: () -> Unit = {},
    /** v1.3.0：进入快速整理页（规则筛候选）。 */
    onOrganize: () -> Unit = {},
) {
    // 当前打开 ⋮ 菜单的条目（null = 未打开）
    var menuEntry by remember { mutableStateOf<FavoriteEntry?>(null) }

    // 多选模式（进入后顶栏与行行为都变）
    var selectMode by rememberSaveable { mutableStateOf(false) }

    // 批量移动：选择目标收藏夹的浮层
    var movePicker by remember { mutableStateOf(false) }

    // 破坏性操作确认框
    var confirmRemove by remember { mutableStateOf(false) }

    // 退出多选时清空选择（否则下次进入会带着上次的勾选）
    LaunchedEffect(selectMode) {
        if (!selectMode) onClearSelection()
    }

    // 返回键：多选模式下先退出多选（而不是直接退页面）
    BackHandler(enabled = selectMode) { selectMode = false }

    LibraryScaffold(
        title = if (selectMode) "已选 ${selection.count}" else folder.title,
        onBack = { if (selectMode) selectMode = false else onBack() },
        modifier = modifier,
        // 非多选模式显示「整理」+「选择」入口
        actions = {
            if (!selectMode && isLoggedIn && entries.isNotEmpty()) {
                // 快速整理：规则筛出候选 → 人工确认
                TextButton(onClick = onOrganize) {
                    Text("整理", color = BiliV3.colors.labelSecondary)
                }
                TextButton(onClick = { selectMode = true }) {
                    Text("选择", color = BiliV3.colors.labelPrimary)
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- 批量操作条（多选模式）----
            if (selectMode) {
                BatchActionBar(
                    selection = selection,
                    batchRunning = batchRunning,
                    progress = batchProgress,
                    onSelectAll = onSelectAll,
                    onClear = onClearSelection,
                    onInvert = onInvertSelection,
                    onSelectPage = onSelectPage,
                )
            }

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
                    val key = e.favItemId.toString()
                    FavoriteRow(
                        entry = e,
                        onClick = { onVideoClick(e.video.bvid) },
                        onMore = { menuEntry = e },
                        selectMode = selectMode,
                        selected = selection.contains(key),
                        onToggleSelect = { onToggleSelect(key) },
                    )
                }
            }

            // ---- 底部操作条（多选模式 + 有选中）----
            if (selectMode && selection.isNotEmpty) {
                BatchBottomBar(
                    count = selection.count,
                    running = batchRunning,
                    hasOtherFolders = otherFolders.isNotEmpty(),
                    onMove = { movePicker = true },
                    onRemove = { confirmRemove = true },
                    onAddToView = onBatchAddToView,
                    onAddToQueue = onBatchAddToQueue,
                )
            }
        }
    }

    // ---- 批量移动：选目标收藏夹 ----
    if (movePicker) {
        BatchMovePicker(
            folders = otherFolders,
            count = selection.count,
            onDismiss = { movePicker = false },
            onPick = { id ->
                movePicker = false
                onBatchMove(id)
            },
        )
    }

    // ---- 破坏性操作确认 ----
    //
    // ⚠️ 取消收藏**必须确认**：批量操作一次可能影响几十条，
    // 且服务端没有"撤销"。文案里带上数量，让用户知道影响面。
    if (confirmRemove) {
        ConfirmDialog(
            title = "取消收藏 ${selection.count} 个视频？",
            message = "这些视频会从「${folder.title}」移除，无法批量撤销。",
            confirmText = "取消收藏",
            destructive = true,
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                onBatchRemove()
            },
        )
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
    /**
     * v1.3.0：多选模式。
     *
     * `selectMode = false` 时完全不渲染复选框（连占位都不留）——
     * 否则正常浏览时每行左边会空出一块，看起来像排版错误。
     */
    selectMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    val colors = BiliV3.colors
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
            //
            // ⚠️ 多选模式下**整行点击 = 勾选**（不是进详情）——
            // 这是列表多选的通用肌肉记忆，也避免误进详情页丢选择。
            .clickable(
                enabled = selectMode || !entry.isInvalid,
                onClick = { if (selectMode) onToggleSelect() else onClick() },
            )
            .padding(horizontal = V3Space.md, vertical = V3Space.xs),
        // ⚠️ Bottom：⋮ 要贴**右下角**，不是垂直居中。
        //
        // 居中时它会浮在行高中间，与右侧文字列"对齐但不贴合"，
        // 看起来像悬空的一颗点。贴右下角后与行的右下角对齐，
        // 视觉上有明确的落点（官方也是右下）。
        verticalAlignment = Alignment.Bottom,
    ) {
        // v1.3.0：多选复选框。
        //
        // ⚠️ 只在 selectMode 时渲染 —— 正常浏览时留空位会让整列右移，
        // 看起来像排版错位。
        //
        // 用 Material 的 Checkbox 而不是自绘：它自带无障碍语义
        // （TalkBack 会读"已选中/未选中"），自绘要额外补一堆语义。
        if (selectMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.brand,
                    uncheckedColor = colors.labelTertiary,
                    checkmarkColor = colors.labelOnBrand,
                ),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(V3Size.iconLg),
            )
            Spacer(Modifier.width(V3Space.xs))
        }

        Box(
            modifier = Modifier
                .width(HISTORY_THUMB_WIDTH)
                .height(HISTORY_THUMB_HEIGHT)
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
                    style = V3Type.caption2.copy(
                        color = colors.labelOnMedia,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(V3Radius.xs))
                        .background(colors.overlay)
                        .padding(horizontal = V3Space.tagHorizontal, vertical = V3Space.tagVertical),
                )
            }
        }
        Spacer(Modifier.width(V3Space.sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (entry.isInvalid) "已失效视频" else v.title,
                style = V3Type.callout.copy(
                    color = if (entry.isInvalid) colors.labelTertiary else colors.labelPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(V3Space.xs))

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
                    Spacer(Modifier.width(V3Space.xxs))
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
                    style = V3Type.caption1.copy(
                        color = colors.labelSecondary,
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
                .size(V3Size.touchMin)
                .clip(CircleShape)
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多操作",
                tint = colors.labelTertiary,
                modifier = Modifier.size(V3Size.iconMd),
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
    /**
     * v1.3.0：顶栏右侧动作区（收藏夹的「选择」入口）。
     *
     * 默认空实现 —— 其余 4 个用本 scaffold 的页面（历史/稍后再看/下载/收藏总览）
     * 不需要右侧动作，加参数而不是各写一遍顶栏。
     */
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = BiliV3.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPrimary),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不再是卡片：与页面同明度，只留底边一条发丝线
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(V3Size.topBar)
                .padding(horizontal = V3Space.topBarMargin),
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
                text = title,
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // ⚠️ weight(1f)：让标题占满剩余宽度，把 actions 推到最右。
                // 不加的话标题短时 actions 会紧贴标题（而不是贴右边）。
                modifier = Modifier.weight(1f),
            )
            actions()
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
    val colors = BiliV3.colors
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
        contentPadding = PaddingValues(vertical = V3Space.xs),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = keyOf, contentType = { "row" }) { item -> row(item) }

        item(key = "load-more") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = V3Space.md),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loadingMore -> CircularProgressIndicator(
                        color = colors.brand,
                        strokeWidth = V3Space.progressTrack,
                        modifier = Modifier.size(V3Size.iconLg),
                    )
                    !hasMore -> Text(
                        text = "没有更多了",
                        style = V3Type.caption1.copy(
                            color = colors.labelTertiary,
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
    val colors = BiliV3.colors
    val v = entry.video

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 列表行**不再是卡片** —— 与收藏行同一原则：
            // 列表用留白分组，不用卡片分组。
            .clickable(onClick = onClick)
            .padding(horizontal = V3Space.md, vertical = V3Space.xs),
        verticalAlignment = Alignment.Top,
    ) {
        // 封面 + 底部进度条
        Box(
            modifier = Modifier
                .width(HISTORY_THUMB_WIDTH)
                .height(HISTORY_THUMB_HEIGHT)
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
                        .height(V3Space.progressTrack)
                        .background(colors.overlay),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(entry.progressRatio)
                            .height(V3Space.progressTrack)
                            .background(colors.brand),
                    )
                }
            }
        }
        Spacer(Modifier.width(V3Space.sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = v.title,
                style = V3Type.callout.copy(
                    color = colors.labelPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(V3Space.xxs))
            Text(
                text = buildString {
                    append(v.authorName)
                    append(" · ")
                    append(formatRelativeTime(entry.viewAt))
                },
                style = V3Type.caption1.copy(
                    color = colors.labelSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 继续播放提示
            if (entry.progressSeconds > 0 && !entry.isFinished) {
                Spacer(Modifier.height(V3Space.hairline))
                Text(
                    text = "看到 ${formatDuration(entry.progressSeconds)}",
                    style = V3Type.caption2.copy(
                        color = colors.brand,
                    ),
                )
            }
        }
    }
}

/** 通用视频行（稍后再看 / 收藏夹共用）。 */
@Composable
private fun VideoRow(video: VideoItem, onClick: () -> Unit) {
    val colors = BiliV3.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 列表行**不再是卡片** —— 与收藏行同一原则：
            // 列表用留白分组，不用卡片分组。
            .clickable(onClick = onClick)
            .padding(horizontal = V3Space.md, vertical = V3Space.xs),
        // 🔴 内容**垂直居中**，不是 `Alignment.Top`（v1.4.2 修 #2）。
        //
        // 首版是 Top：缩略图 80dp 高，而文字块只有 2 行标题 + 1 行元信息
        // （约 56dp），于是文字贴着顶部、缩略图下方留 24dp 空白 ——
        // 视觉上就是用户反馈的「文本偏左下、信息堆在左下角」。
        //
        // 改居中后文字与缩略图形成一条稳定的中轴线：
        // ┌────────────┐  标题
        // │            │  UP · 播放
        // │  thumbnail │
        // └────────────┘
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(HISTORY_THUMB_WIDTH)
                .height(HISTORY_THUMB_HEIGHT)
                .background(colors.coverPlaceholder),
        ) {
            AsyncImage(
                model = video.coverUrl(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(V3Space.sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = V3Type.callout.copy(
                    color = colors.labelPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // 🔴 预留两行高度（v1.4.2 修 #2）。
                //
                // 与 `VideoCard` 同一做法：标题一行时不再让元信息上移，
                // 标题两行时也不会把行撑高 —— 不同视频的行高因此一致，
                // 滚动时不会有"忽高忽低"的跳动。
                modifier = Modifier.heightIn(min = ROW_TITLE_MIN_HEIGHT),
            )
            Spacer(Modifier.height(V3Space.xxs))
            Text(
                text = "${video.authorName} · ${formatCount(video.playCount)} 播放",
                style = V3Type.caption1.copy(
                    color = colors.labelSecondary,
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
    val colors = BiliV3.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(V3Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "登录后查看",
            style = V3Type.subheadline.copy(
                fontWeight = FontWeight.SemiBold,
                color = colors.labelPrimary,
            ),
        )
        Spacer(Modifier.height(V3Space.xs))
        Text(
            text = "该功能需要登录 B 站账号",
            style = V3Type.footnote.copy(
                color = colors.labelSecondary,
            ),
        )
        Spacer(Modifier.height(V3Space.lg))
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
            color = BiliV3.colors.brand,
            strokeWidth = V3Space.progressTrack,
            modifier = Modifier.size(V3Size.spinnerPage),
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

/**
 * 列表行标题的最小高度（两行）。
 *
 * ## 为什么必须预留
 *
 * 不预留时，标题一行 / 两行会得到不同的文字块高度，而
 * [VideoRow] 的内容是垂直居中的 —— 于是**元信息行的位置随标题长度浮动**，
 * 同一屏里不同视频的 UP 名/播放量不在同一条基线上。
 *
 * `VideoCard` 用 `heightIn(min = 40.dp)` 解决同一问题。
 * 这里取 42dp：`V3Type.callout.lineHeight` 是 21sp，两行即 42 ——
 * 字面量在这里可接受，因为 `sp → dp` 需要 `LocalDensity`，
 * 而本常量在 Composable 之外（注释记下这个换算关系即可）。
 */
private val ROW_TITLE_MIN_HEIGHT = 42.dp
