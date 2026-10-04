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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.example.biliv3.design.tokens.Motion
import kotlinx.coroutines.delay
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.biliv3.data.model.CommentItem
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.data.model.formatRelativeTime
import com.example.biliv3.design.ruler
import com.example.biliv3.design.ruleTop
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.BiliTheme
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
    /**
     * 要定位并高亮的评论 rpid（null = 不定位）。
     *
     * 由「AI 查成分 → 在 APP 内查看」传入。命中时：
     * 1. 滚动到该条（居中）
     * 2. 短暂高亮底色
     * 3. 通知上层清空定位目标（避免用户之后手动滚动又被拉回）
     */
    focusRpid: String? = null,
    /** 完成定位（滚动+高亮）后回调，上层据此清空 focusRpid。 */
    onFocusHandled: () -> Unit = {},
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

    /**
     * 滚动到定位评论，并短暂高亮。
     *
     * ## 为什么要 `snapshotFlow` 等待列表出现
     *
     * `focusRpid` 可能在评论**还没加载完**时就设好了（详情页刚进、
     * 评论还在拉第一页）。此时 `comments` 里还没有那一条，
     * 直接 `scrollToItem` 会因索引越界而静默失败 —— 用户看不到任何效果。
     *
     * 所以等 `comments` 里真的出现该 rpid 再滚。
     */
    LaunchedEffect(focusRpid, comments) {
        val target = focusRpid ?: return@LaunchedEffect
        val index = comments.indexOfFirst { it.rpid.toString() == target }
        if (index < 0) return@LaunchedEffect

        listState.animateScrollToItem(index)
        // 高亮持续 2s 后自动淡出：够用户看到，又不会一直"亮着"显得脏
        delay(2000)
        onFocusHandled()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 🔴 乙·质感：评论区**不再是卡片**。
            //
            // 上一版 `biliCard()` 给整块评论套了一个圆角容器。
            // 评论本来就是"一长串内容"，容器对长列表毫无分组价值 ——
            // 它只是把评论和页面底切开，制造了一条多余的边界。
            //
            // 现在：通栏，靠**上边一条发丝线**与简介区分隔，
            // 评论之间靠间距分组。
            .ruleTop(color = Rule.color),
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
                    strokeWidth = Space.trackHeight,
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
                        // 定位目标：短暂高亮，帮用户一眼找到
                        highlighted = focusRpid != null && c.rpid.toString() == focusRpid,
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
                                strokeWidth = Space.trackHeight,
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
 * 排序切换项。
 *
 * ## ⚠️ 为什么从"胶囊"改成纯文字（问题 6 的根因）
 *
 * 首版选中态是 `brandPrimaryDim` 粉底 + 粉字 + 半粗体，未选中是 `bgHover` 灰底。
 * 结果是评论区顶部出现**两个带底色的胶囊**，在一片文字里非常抢眼 ——
 * 用户的反馈正是"评论热度的顶框很突出"。
 *
 * 排序只是一个**轻量辅助控件**，不该比评论内容本身还显眼。改为纯文字：
 * - 选中：`textPrimary` + Medium
 * - 未选中：`textTertiary` + Normal
 *
 * 仍然保留**颜色 + 字重**两个维度（不只靠颜色），色弱用户也能分辨。
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
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.textPrimary else colors.textTertiary,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.badge))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x2, vertical = Space.x1),
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
 *
 * ## ⚠️ 为什么要收敛尺寸与字重（问题 3 的根因）
 *
 * 首版每一行理由都是 `bodyMedium`(14sp) + 上下 `Space.x3`(12dp) 内边距，
 * 十来个理由铺满整屏，配上半粗体标题 —— 视觉上比视频页任何内容都重，
 * 用户的反馈是"举报后的 UI 太突出、太抢眼"。
 *
 * 举报是个**低频、辅助性**操作，不该喧宾夺主。改为：
 * - 理由行降到 `bodySm`(13sp) + 更紧的行距，整屏能放下更多且更轻
 * - 标题降为 `label`(12sp) + `textSecondarySafe`（不再是 SemiBold 主色）
 * - 面板限高 + 可滚动，避免理由多时撑满全屏
 * - 顶部加一个把手（grabber），视觉上明确"这是个可下拉关闭的薄面板"
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
                .clip(RoundedCornerShape(topStart = Radius.panel, topEnd = Radius.panel))
                // 弹层用 surfaceElevated（比卡片亮一档）—— 深色下投影不可见，分层靠提亮
                .background(colors.surfaceElevated)
                // 阻止点击穿透到遮罩（点面板内部不应关闭）
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(top = Space.x2, bottom = Space.x2),
        ) {
            // 把手：暗示这是可下拉关闭的薄面板，降低"大弹窗"观感
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(GRABBER_WIDTH)
                    .height(GRABBER_HEIGHT)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.borderStrong),
            )

            Spacer(Modifier.height(Space.x2))

            Text(
                text = "举报理由",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textSecondarySafe,
                ),
                modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x1),
            )

            Spacer(Modifier.height(Space.x1))

            // 理由列表：限高 + 可滚动（理由多时不会撑满整屏）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = REPORT_SHEET_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                reasons.forEach { (code, label) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.textPrimary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(code) }
                            .padding(horizontal = Space.x4, vertical = Space.x2),
                    )
                }
            }

            Spacer(Modifier.height(Space.x1))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(vertical = Space.x2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = FontSize.bodySm,
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
    /** 是否为"定位目标"评论（短暂高亮，帮用户一眼找到）。 */
    highlighted: Boolean = false,
) {
    val colors = BiliTheme.colors

    // 高亮底色的淡入淡出。用动画而不是硬切：突然出现一块色块很突兀。
    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(Motion.PAGE_FADE_MS, easing = Motion.standard),
        label = "commentHighlight",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 🔴 乙·质感：评论**不再是卡片**。
            //
            // 上一版每条顶层评论是一张浮起的小卡（`biliCard(elevation=1)`）。
            // 评论是一串**同质、等权**的条目 —— 每条都装进一个盒子
            // 会把列表切成一堆小方块，且和已删的评论区大卡形成"卡片套卡片"。
            //
            // 现在：顶层评论用**发丝线**分隔（线在条目上方），
            // 内嵌回复（isReply）用**纵向标尺**表达层级。
            //
            // ## 为什么回复用标尺而不是缩进
            //
            // 缩进在 2~3 层后就把可用宽度吃掉了，手机竖屏下第 3 层
            // 只剩半屏宽，正文被挤成"一字一行"。
            //
            // 标尺是一条竖线 + 左侧固定缩进：层级靠"线在不在"表达，
            // 不靠"缩进多少" —— 无论多少层，正文宽度都不变。
            .then(
                if (isReply) Modifier.ruler(Rule.color)
                else Modifier.ruleTop(color = Rule.subtle),
            )
            // 定位高亮：用品牌色的低透明度铺底，不改文字色 ——
            // 改文字色会破坏对比度约束（见 §5.2 的"文字安全版"）。
            //
            // ⚠️ 顺序：线在最外（横跨整个条目宽度），高亮底在内，
            // 内边距在最内 —— 否则高亮底会把分隔线也染上颜色。
            .background(
                colors.brandPrimary.copy(alpha = HIGHLIGHT_ALPHA * highlightAlpha),
            )
            .padding(
                start = Space.x4,
                end = Space.x4,
                top = if (isReply) Space.x2 else Rhythm.inGroup,
                bottom = if (isReply) Space.x2 else Rhythm.inGroup,
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
                // ---- 昵称 + UP 标记 ············· 时间 · IP属地 ----
                //
                // ⚠️ 时间与 IP 属地放在**昵称行右侧**，不再占用操作行
                // （用户反馈："X天前 有点挡住 回复栏那里了"）。
                //
                // 布局约定：
                // - 第 1 行：昵称 [UP]  ← 昵称可截断，右侧信息不被挤压
                // - 第 2 行：正文
                // - 第 3 行：只放操作（回复 / 举报 / 点赞）
                //
                // 为什么这样最稳：时间与属地是**只读辅助信息**，
                // 与"回复/举报/点赞"性质不同；混在一行时窄屏上必然互相挤。
                // 拆开后操作行只剩按钮，任何屏宽下都不会被文字顶到。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 昵称 + UP 标记：整组吃剩余宽度（可截断）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
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
                                    .padding(horizontal = Space.tagHorizontal, vertical = Space.tagVertical),
                            )
                        }
                    }

                    Spacer(Modifier.width(Space.x2))

                    // 时间（非 weight：永远完整可见）
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
                        maxLines = 1,
                    )

                    // ---- IP 属地（B 站 APP 样式：紧跟时间，小号灰字）----
                    //
                    // 无属地时**整段不渲染**（含分隔间距），不留空位。
                    // 未登录时接口不返回该字段，所以看不到属属是正常的，
                    // 不是渲染 bug（实测 `reply_control.location` 仅登录态存在）。
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

                // ---- 操作行：回复 · 举报 · 删除 · 点赞 ----
                //
                // ⚠️ 这一行**只放可点操作**，不再放时间与 IP 属地
                // （用户反馈"X天前 有点挡住回复栏"）。
                // 时间/属地已移到昵称行右侧，见上方。
                //
                // 这样任何屏宽下操作都不会被文字挤压或遮挡。
                Row(verticalAlignment = Alignment.CenterVertically) {
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

                    // 删除：只对自己发的评论显示（由 UI 层判断 owner）
                    if (comment.isOwn) {
                        Spacer(Modifier.width(Space.x3))
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "删除评论",
                            tint = colors.textSecondarySafe,
                            modifier = Modifier
                                .size(Sizes.iconSm + Space.x1)
                                .clickable { onDelete() },
                        )
                    }

                    // 点赞推到最右：回复/举报在左、点赞在右，
                    // 与官方评论区一致，也让长评论下操作区不挤在一起。
                    Spacer(Modifier.weight(1f))

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
                        Spacer(Modifier.width(Space.micro))
                        Text(
                            text = formatCount(comment.likeCount),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.badge,
                                color = if (comment.liked) colors.brandPrimary else colors.textSecondarySafe,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // ---- 内嵌回复：**默认折叠** ----
        //
        // ⚠️ 嵌套层（isReply=true）**不再渲染折叠开关**（问题 4 的根因）：
        //
        // 首版对所有层都渲染「展开 N 条回复」，但嵌套层的调用点传的是
        // `onToggleReplies = {}`（空 lambda）。于是当接口在某些评论上
        // 返回了二级 replies 时，嵌套行里会出现一个**点了完全没反应**的
        // 「展开 N 条回复」—— 就是用户看到的"假回复"。
        //
        // 根因不是数据问题，而是"开关渲染了但回调是空的"。
        // 修法：嵌套层既然已经处在展开区内，就不再套开关，其子回复
        // **直接平铺**；真正的深层分页由顶层「查看全部」进楼中楼详情页完成。
        //
        // 同时把 `repliesExpanded` 的判定收紧为「有回复 且 需要展开」，
        // 避免"没有更多回复却还显示按钮"。
        val showReplyToggle = !isReply && comment.hasReplies
        val showInlineReplies = when {
            isReply -> comment.hasReplies
            else -> comment.hasReplies && repliesExpanded
        }

        if (showReplyToggle) {
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
                    .padding(horizontal = Space.x1, vertical = Space.compactVertical),
            )
        }

        if (showInlineReplies) {
            Spacer(Modifier.height(Space.x1))
            // 层级竖线颜色：用 hairline 描边色（深浅主题各自成立，
            // 不引入新的硬编码色值 —— 对照 design/tokens）。
            val replyGuideColor = colors.borderStrong
            Column(
                modifier = Modifier
                    // 嵌套层不再额外缩进 —— 它已经在父级缩进区内，
                    // 再缩一次会形成"越缩越窄"的阶梯（问题 5）。
                    .then(
                        if (isReply) Modifier.fillMaxWidth()
                        else Modifier
                            .padding(start = COMMENT_AVATAR + Space.x2)
                            .fillMaxWidth(),
                    )
                    // ⚠️ 去掉 `bgHover` 实底（问题 5 的根因）。
                    //
                    // 首版给回复区铺了一层 `bgHover` 灰底 + 圆角，
                    // 在已是 `bgCard` 的评论卡片上又叠一块色块 ——
                    // 形成"卡中卡"，视觉上非常突兀，用户反馈"回复区域很突出"。
                    //
                    // 改为：**不铺底**，只靠左侧一条 2dp 竖线做层级提示。
                    // 这比整块色底轻得多，也符合"缩进清晰但不抢眼"的要求。
                    .drawBehind {
                        val x = 2.dp.toPx()
                        drawLine(
                            color = replyGuideColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = Space.trackHeight.toPx(),
                        )
                    }
                    .padding(start = Space.x3),
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
                // 修法：跳楼中楼详情页，由 `x/v2/reply/reply` 真正分页拉全量。
                //
                // ⚠️ 条件必须用 `replyCount > 已展示条数`：
                // 只有"确实还有没展示的回复"时才给入口，否则会出现
                // 「已全部展示却仍写着查看全部 N 条」的信息不实。
                val shownCount = if (repliesFullyExpanded) {
                    comment.replies.size
                } else {
                    minOf(MAX_INLINE_REPLIES, comment.replies.size)
                }
                if (!isReply && comment.replyCount > shownCount) {
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
                            .padding(horizontal = Space.x1, vertical = Space.compactVertical),
                    )
                }
            }
        }
    }
}

/** 评论头像尺寸。 */
private val COMMENT_AVATAR = 32.dp

/**
 * 「定位评论」高亮的底色透明度。
 *
 * 0.14f：低到"看得见但不刺眼"。再高会盖住文字影响阅读，
 * 再低则与卡片底色区分不出来（等于没高亮）。
 */
private const val HIGHLIGHT_ALPHA = 0.14f

/** 回复头像尺寸（小一号）。 */
private val REPLY_AVATAR = 24.dp

/** 内嵌回复最多显示几条（接口通常给 3 条）。 */
private const val MAX_INLINE_REPLIES = 3

/** 举报面板的把手尺寸（视觉暗示"可下拉关闭的薄面板"）。 */
private val GRABBER_WIDTH = 32.dp
private val GRABBER_HEIGHT = Space.x1

/**
 * 举报面板理由列表的最大高度。
 *
 * 举报理由是长列表（实测 10 条上下），不设上限会撑满整屏 ——
 * 一个低频辅助操作占据整屏是问题 3 观感突兀的直接来源。
 */
private val REPORT_SHEET_MAX_HEIGHT = 320.dp
