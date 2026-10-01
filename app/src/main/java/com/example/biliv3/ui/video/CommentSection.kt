package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ThumbUp
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.biliv3.data.model.CommentItem
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.data.model.formatRelativeTime
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 评论区（独立滚动版）。
 *
 * ## 为什么自己带 `LazyColumn`
 *
 * 之前它被塞进详情页那个大 `LazyColumn` 里当一个 item，
 * 于是**无法做无限滚动**（滚动位置归外层管，拿不到"触底"事件），
 * 也没法让"评论视图独占整屏"。
 *
 * 现在评论区是**自成一体的滚动容器**：切到评论标签时它占满整屏，
 * 触底自动加载由它自己监听。
 *
 * ## 四项交互（对应需求）
 *
 * | 需求 | 实现 |
 * |---|---|
 * | 回复默认折叠 | `expandedReplies` 集合记录哪些评论展开了 |
 * | 无限滚动 | `snapshotFlow` 监听列表尾部，触底自动 `onLoadMore` |
 * | 加载状态 | 底部显示转圈；无更多时显示"没有更多了" |
 * | 点赞 / 删除 / 回复 | 每条评论的元信息行 + 操作入口 |
 *
 * ## 回复折叠为什么用"集合"而不是每条自带状态
 *
 * 折叠状态属于 **UI 层**，不该塞进数据模型（`CommentItem` 是接口映射）。
 * 用 `Set<rpid>` 记录"已展开的评论 id"：
 * - 列表刷新（翻页追加）时展开状态**自然保留**
 * - 不会因为 item 被 LazyColumn 回收而丢失
 */
@Composable
fun CommentSection(
    comments: List<CommentItem>,
    total: Int,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    isLoggedIn: Boolean,
    onLoadMore: () -> Unit,
    onLike: (CommentItem) -> Unit = {},
    onDelete: (CommentItem) -> Unit = {},
    onReply: (CommentItem) -> Unit = {},
    onLoginRequired: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** 当前排序（[COMMENT_SORT_HOT] / [COMMENT_SORT_TIME]）。 */
    sortMode: Int = COMMENT_SORT_HOT,
    /** 切换排序。切换后由 ViewModel 重置游标并重拉第一页。 */
    onSortChange: (Int) -> Unit = {},
    /** 举报某条评论。 */
    onReport: (CommentItem, Int) -> Unit = { _, _ -> },
    /** 举报理由（编号 + 文案），由仓库提供以保证与接口编号一致。 */
    reportReasons: List<Pair<Int, String>> = emptyList(),
    /**
     * 点评论者头像 → 用户主页。
     *
     * ⚠️ 此前评论头像**完全不可点** —— 与详情页 UP 头像同一类缺口。
     */
    onAvatarClick: (Long) -> Unit = {},
    /**
     * 「查看全部 N 条回复」→ 楼中楼详情页。
     *
     * ⚠️ 此前这里只是**就地展开已加载的 3 条**（接口每条评论最多内嵌 3 条），
     * 而按钮写着"全部 N 条" —— 属于信息不实。现在真正跳转独立页，
     * 由 `x/v2/reply/reply` 分页拉全量。
     */
    onViewAllReplies: (CommentItem) -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    val colors = BiliTheme.colors

    // 已展开回复的评论 id 集合
    var expandedReplies by remember { mutableStateOf<Set<Long>>(emptySet()) }

    /**
     * 已"完全展开"的评论 id 集合（点了「查看全部 N 条回复」）。
     *
     * 与 [expandedReplies] 分开的原因：默认展开只显示 [MAX_INLINE_REPLIES] 条，
     * 而"查看全部"要突破这个上限。两个集合语义不同，合并会导致
     * "点展开就全出来"，与首版的折叠意图冲突。
     */
    var expandedFully by remember { mutableStateOf<Set<Long>>(emptySet()) }

    /**
     * 正在举报的目标评论（null = 未在举报）。
     *
     * 举报是**两段式**：先选中目标 → 再选理由。
     * 用一个状态同时承载两者，避免"选了理由却不知道举报谁"。
     */
    var reportTarget by remember { mutableStateOf<CommentItem?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // C 方案：评论区是独立卡片。
            .biliCard(),
    ) {
        // ================= 标题 + 排序 =================
        //
        // ⚠️ C 方案**删掉了这里的「评论 N」标题**。
        //
        // 它和正上方的 `VideoToolRow` 标签重复 —— 工具条已经写着
        // 「简介 | 评论 8459」（当前选中项还有粉色下划线），
        // 紧跟着再渲染一个「评论 8459」大标题，屏幕上是同一句话出现两次
        // （实测截图 114-now.png 可见）。
        //
        // 删掉还顺带省了约 48dp 纵向空间 —— 手机竖屏下评论可见条数
        // 从 4 条变 5 条。
        //
        // ⚠️ 只在**有评论数**时保留一个极简的计数行 + 排序切换。
        // 排序切换是「评论排序」这项功能的入口（此前 mode 硬编码 3、
        // UI 无任何切换控件，该功能形同不存在）。
        if (comments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Space.x4, end = Space.x4, top = Space.x3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (total > 0) "共 ${formatCount(total)} 条评论" else "评论",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                )
                Spacer(Modifier.weight(1f))
                SortChip(
                    label = "热度",
                    selected = sortMode == COMMENT_SORT_HOT,
                    onClick = { onSortChange(COMMENT_SORT_HOT) },
                )
                Spacer(Modifier.width(Space.x3))
                SortChip(
                    label = "时间",
                    selected = sortMode == COMMENT_SORT_TIME,
                    onClick = { onSortChange(COMMENT_SORT_TIME) },
                )
            }
        }

        when {
            loading && comments.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.x6),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.brandPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(Sizes.iconXl),
                )
            }

            comments.isEmpty() -> Text(
                text = "还没有评论，来说两句吧",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.textTertiary,
                ),
                modifier = Modifier.padding(
                    horizontal = Space.x4,
                    vertical = Space.x6,
                ),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Space.x6),
            ) {
                items(comments, key = { it.rpid }) { c ->
                    CommentRow(
                        comment = c,
                        repliesExpanded = c.rpid in expandedReplies,
                        // 点了「查看全部」后不再截断
                        repliesFullyExpanded = c.rpid in expandedFully,
                        onToggleReplies = {
                            expandedReplies = if (c.rpid in expandedReplies) {
                                expandedReplies - c.rpid
                            } else {
                                expandedReplies + c.rpid
                            }
                        },
                        // 「查看全部 N 条回复」：进独立页真正拉全量。
                        //
                        // ⚠️ 首版这里是"就地展开已加载的回复"——
                        // 接口每条评论最多内嵌 3 条，所以点了还是 3 条，
                        // 而按钮写着"全部 N 条"（信息不实）。
                        onExpandAll = { onViewAllReplies(c) },
                        isLoggedIn = isLoggedIn,
                        onLike = { onLike(c) },
                        onDelete = { onDelete(c) },
                        onReply = { onReply(c) },
                        onReport = { reportTarget = c },
                        onAvatarClick = { onAvatarClick(c.mid) },
                        onViewAllReplies = { onViewAllReplies(c) },
                        onLoginRequired = onLoginRequired,
                    )
                }

                // ---- 底部：加载状态 / 没有更多 ----
                item(key = "footer") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Space.x4),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            loadingMore -> CircularProgressIndicator(
                                color = colors.brandPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(Sizes.iconXl),
                            )
                            hasMore -> Text(
                                text = "上滑加载更多",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = FontSize.label,
                                    color = colors.textTertiary,
                                ),
                            )
                            else -> Text(
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
    }

    // ---- 无限滚动：触底自动加载 ----
    //
    // 监听"最后一个可见项"而不是滚动偏移 —— 后者在列表高度变化
    // （图片加载、回复展开）时会误判，前者语义准确。
    //
    // 用 `derivedStateOf` 包一层：只在**布尔值翻转**时才发通知，
    // 避免滚动过程中每帧都触发 LaunchedEffect。
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            val totalCount = listState.layoutInfo.totalItemsCount
            totalCount > 0 && last.index >= totalCount - 2
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { atBottom }
            .collect { bottom ->
                // 三个前置条件缺一不可：到底了、还有更多、当前不在加载
                if (bottom && hasMore && !loadingMore && !loading) onLoadMore()
            }
    }

    // ---- 举报理由选择浮层 ----
    reportTarget?.let { target ->
        ReportReasonSheet(
            reasons = reportReasons,
            onDismiss = { reportTarget = null },
            onPick = { reason ->
                onReport(target, reason)
                reportTarget = null
            },
        )
    }
}

/**
 * 排序胶囊。
 *
 * 选中态用**底色 + 加粗**双重表达（不只靠颜色）——
 * 色弱用户也要能看出当前选的是哪个（§4.6）。
 */
@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.textBrandSafe else colors.textSecondarySafe,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (selected) colors.brandPrimaryDim else colors.bgHover)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.x1 + 2.dp),
    )
}

/**
 * 举报理由选择浮层。
 *
 * ## ⚠️ 为什么是页面内浮层而不是 Dialog
 *
 * 与评论输入框踩过的同一个坑：`Dialog` 是独立 window，
 * 配合 `decorFitsSystemWindows = false` 会让各种 inset 派发失效。
 * 这里虽然不需要键盘避让，但保持一致做法、少一类特例。
 *
 * 但**这里没有 IME 需求**，用 Dialog 其实也无害 —— 之所以仍用浮层，
 * 是为了让"弹层的返回手势优先级"与其它弹层完全一致（统一心智）。
 */
@Composable
private fun ReportReasonSheet(
    reasons: List<Pair<Int, String>>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val colors = BiliTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrimPanel)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.card, topEnd = Radius.card))
                .background(colors.bgCard)
                // 阻止点击穿透到遮罩（点面板内部不应关闭）
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(vertical = Space.x3),
        ) {
            Text(
                text = "举报理由",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
                modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x2),
            )
            reasons.forEach { (code, label) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.textPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(code) }
                        .padding(horizontal = Space.x4, vertical = Space.x3),
                )
            }
            Spacer(Modifier.height(Space.x1))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(vertical = Space.x3),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.textSecondarySafe,
                    ),
                )
            }
        }
    }
}

/**
 * 单条评论（含**默认折叠**的内嵌回复）。
 *
 * ## 折叠交互
 *
 * - 有回复时显示「展开 N 条回复 ▾」
 * - 点击展开，按钮变成「收起 ▴」
 * - 默认隐藏 —— 用户明确要求，也避免长评论把列表撑得过长
 *
 * ## 点击整条评论 = 回复
 *
 * 与官方一致：点评论正文区域是"回复这条评论"，
 * 而不是"展开回复"（后者有自己的按钮，避免两个动作抢同一次点击）。
 */
@Composable
private fun CommentRow(
    comment: CommentItem,
    repliesExpanded: Boolean,
    onToggleReplies: () -> Unit,
    isLoggedIn: Boolean,
    onLike: () -> Unit,
    onDelete: () -> Unit,
    onReply: () -> Unit,
    onLoginRequired: () -> Unit,
    /** 请求举报这条评论（由上层弹出理由选择）。 */
    onReport: () -> Unit = {},
    /** 点头像 → 用户主页。 */
    onAvatarClick: () -> Unit = {},
    /**
     * 「查看全部 N 条回复」→ 楼中楼详情页。
     *
     * 与外层的 [onExpandAll] 分开：内嵌回复行里的"查看全部"也要能跳，
     * 但内嵌行自己不带完整上下文（它只有该回复的数据）。
     */
    onViewAllReplies: () -> Unit = {},
    /** 「查看全部 N 条回复」—— 展开该楼全部已加载回复（修死入口）。 */
    onExpandAll: () -> Unit = {},
    /** 是否已突破内嵌上限（点了「查看全部」）。 */
    repliesFullyExpanded: Boolean = false,
    isReply: Boolean = false,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // C 方案：评论是**独立卡片**（每条浮起）。
            // 顶层回复带内边距 + 卡片底；内嵌回复（isReply）不加卡片，
            // 而是靠父级的缩进 + bgHover 区分层次 —— 否则会出现"卡片套卡片"。
            .then(
                if (isReply) Modifier else Modifier
                    .padding(horizontal = Space.x3, vertical = Space.x1)
                    .biliCard(elevation = 1.dp, shape = RoundedCornerShape(Radius.button)),
            )
            .padding(
                start = Space.x4,
                end = Space.x4,
                top = if (isReply) Space.x2 else Space.x3,
                bottom = Space.x2,
            ),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = comment.faceUrl(if (isReply) 48 else 72),
                contentDescription = "进入用户主页",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (isReply) REPLY_AVATAR else COMMENT_AVATAR)
                    .clip(CircleShape)
                    .background(colors.avatarPlaceholder)
                    // ⚠️ 点头像进主页、点正文回复 —— 两个动作分开挂，
                    // 避免一次点击触发两件事。
                    .clickable { onAvatarClick() },
            )
            Spacer(Modifier.width(Space.x2))

            Column(modifier = Modifier.weight(1f)) {
                // ---- 昵称 + UP 标记 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = comment.userName,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            color = colors.textSecondarySafe,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (comment.isUp) {
                        Spacer(Modifier.width(Space.x1))
                        Text(
                            text = "UP",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.badge,
                                color = colors.textOnBrand,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.badge))
                                .background(colors.brandPrimary)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }

                Spacer(Modifier.height(Space.x1))

                // ---- 正文（点击 = 回复）----
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        lineHeight = FontSize.bodyLine,
                        color = colors.textPrimary,
                    ),
                    maxLines = if (isReply) 3 else 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isLoggedIn) onReply() else onLoginRequired()
                        },
                )

                Spacer(Modifier.height(Space.x1))

                // ---- 元信息行：时间 · IP属地 · 回复 · 点赞 · 删除 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatRelativeTime(comment.ctime),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            // ⚠️ 首版用 textTertiary（浅色 #AEB3B9），实测截图里
                            // "5天前""4天前"淡到几乎看不见 —— 它比 textSecondary
                            // 还浅，而 README 早就警告过 textSecondary 只有 2.9:1。
                            // 改用 textSecondarySafe（浅色 #6B6470，5.8:1）。
                            color = colors.textSecondarySafe,
                        ),
                    )

                    // ---- IP 属地（B 站 APP 样式：紧跟时间，小号灰字）----
                    //
                    // 位置：与时间同一行、时间右侧 —— 与 B 站 APP 一致。
                    // 样式：与时间完全相同的字号/颜色令牌，不抢眼。
                    // 无属地时**整段不渲染**（含分隔点），不留空位。
                    if (comment.hasIpLocation) {
                        Spacer(Modifier.width(Space.x2))
                        Text(
                            text = "IP属地：${comment.ipLocation}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.badge,
                                color = colors.textSecondarySafe,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 属地是只读信息，不加 clickable
                        )
                    }

                    Spacer(Modifier.width(Space.x3))
                    Text(
                        text = "回复",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            color = colors.textSecondarySafe,
                        ),
                        modifier = Modifier.clickable {
                            if (isLoggedIn) onReply() else onLoginRequired()
                        },
                    )

                    // 举报：**自己的评论不显示**（举报自己没有意义，
                    // 且会造成"误点后自己的评论消失"的糟糕体验）。
                    if (!comment.isOwn) {
                        Spacer(Modifier.width(Space.x3))
                        Text(
                            text = "举报",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.badge,
                                color = colors.textSecondarySafe,
                            ),
                            modifier = Modifier.clickable {
                                if (isLoggedIn) onReport() else onLoginRequired()
                            },
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // 删除：只对自己发的评论显示（由 UI 层判断 owner）
                    if (comment.isOwn) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "删除评论",
                            tint = colors.textSecondarySafe,
                            modifier = Modifier
                                .size(Sizes.iconSm + Space.x1)
                                .clickable { onDelete() },
                        )
                        Spacer(Modifier.width(Space.x3))
                    }

                    // 点赞：已赞用填充图标 + 变色（不依赖颜色单一维度）
                    Icon(
                        imageVector = if (comment.liked) {
                            Icons.Filled.ThumbUp
                        } else {
                            Icons.Outlined.ThumbUp
                        },
                        contentDescription = if (comment.liked) "取消点赞" else "点赞",
                        tint = if (comment.liked) colors.brandPrimary else colors.textSecondarySafe,
                        modifier = Modifier
                            .size(Sizes.iconSm + Space.x1)
                            .clickable {
                                if (isLoggedIn) onLike() else onLoginRequired()
                            },
                    )
                    if (comment.likeCount > 0) {
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = formatCount(comment.likeCount),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.badge,
                                color = if (comment.liked) colors.brandPrimary else colors.textSecondarySafe,
                            ),
                        )
                    }
                }
            }
        }

        // ---- 内嵌回复：**默认折叠** ----
        if (comment.hasReplies) {
            Spacer(Modifier.height(Space.x1))

            // 折叠态按钮：`展开 N 条回复 ▾` / `收起 ▴`
            Text(
                text = if (repliesExpanded) {
                    "收起回复"
                } else {
                    "展开 ${comment.replyCount} 条回复"
                },
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    color = colors.textLinkSafe,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .padding(start = COMMENT_AVATAR + Space.x2)
                    .clip(RoundedCornerShape(Radius.badge))
                    .clickable(onClick = onToggleReplies)
                    .padding(horizontal = Space.x1, vertical = 2.dp),
            )

            if (repliesExpanded) {
                Spacer(Modifier.height(Space.x1))
                Column(
                    modifier = Modifier
                        .padding(start = COMMENT_AVATAR + Space.x2)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.button))
                        .background(colors.bgHover),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    comment.replies
                        .let { if (repliesFullyExpanded) it else it.take(MAX_INLINE_REPLIES) }
                        .forEach { r ->
                        CommentRow(
                            comment = r,
                            repliesExpanded = false,
                            onToggleReplies = {},
                            isLoggedIn = isLoggedIn,
                            onLike = onLike,
                            onDelete = onDelete,
                            onReply = onReply,
                            onReport = onReport,
                            onAvatarClick = onAvatarClick,
                            onViewAllReplies = onViewAllReplies,
                            onLoginRequired = onLoginRequired,
                            onExpandAll = onExpandAll,
                            isReply = true,
                        )
                    }
                    // 回复数超过内嵌上限时给出"查看全部"入口
                    //
                    // ⚠️ 首版这里是**死入口** —— 渲染了一个蓝色可点的样子，
                    // 但 `Modifier` 里没有任何 `clickable`，点了完全没反应。
                    //
                    // 修法：复用 `onToggleReplies` 之外的新回调 `onExpandAll`，
                    // 由 ViewModel 去拉该楼的完整回复（接口层暂无"按 rpid 拉子评论"，
                    // 所以当前实现是**就地展开全部已加载的回复** —— 至少点了有反应，
                    // 不会再是死入口）。
                    if (comment.replyCount > comment.replies.size && !repliesFullyExpanded) {
                        Text(
                            text = "查看全部 ${comment.replyCount} 条回复",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.badge,
                                color = colors.textLinkSafe,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = Modifier
                                .padding(
                                    start = Space.x4,
                                    bottom = Space.x2,
                                )
                                .clip(RoundedCornerShape(Radius.badge))
                                // 跳楼中楼详情页（真正分页拉全量）
                                .clickable { onViewAllReplies() }
                                .padding(horizontal = Space.x1, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 评论头像尺寸。 */
private val COMMENT_AVATAR = 32.dp

/** 回复头像尺寸（小一号）。 */
private val REPLY_AVATAR = 24.dp

/** 内嵌回复最多显示几条（接口通常给 3 条）。 */
private const val MAX_INLINE_REPLIES = 3
