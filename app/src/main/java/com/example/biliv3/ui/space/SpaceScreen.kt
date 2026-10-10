package com.example.biliv3.ui.space

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.biliv3.data.DynamicItem
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.data.model.formatRelativeTime
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.component.VideoCard
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type
import androidx.compose.foundation.layout.aspectRatio

/**
 * 用户主页（UP 主空间）。
 *
 * ## 结构
 *
 * ```
 * [←]
 * ┌────────────────────────────────┐
 * │ (头像)  昵称            [关注]  │
 * │         UID 123456             │
 * │         个性签名…               │
 * │  粉丝 1.2万 · 关注 234          │
 * └────────────────────────────────┘
 * [主页] [动态]                      ← Tab
 * ────────────────────────────────
 * 视频网格 / 动态卡片
 * ```
 *
 * ## 为什么两个 Tab 而不是四个
 *
 * 官方有「主页 / 动态 / 投稿 / 合集」四个 Tab。本项目只做两个：
 * - **投稿**：接口可用（`space/wbi/arc/search`）
 * - **动态**：接口可用（`polymer/web-dynamic/v1/feed/space`）
 *
 * 「合集」需要另一个接口且风控敏感；宁缺勿假 —— 不做一个点了
 * 永远空的 Tab（那是死入口）。
 *
 * ## 关注按钮的三态
 *
 * 自己的主页 → 不显示按钮（显示"这是你"）；未登录 → 按钮显示"关注"，
 * 点击引导登录；已登录 → 关注/已关注，点击切换。
 */
@Composable
fun SpaceScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onLoginRequired: () -> Unit,
    onChat: (Long, String) -> Unit,
    onAicu: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpaceViewModel,
) {
    val colors = BiliV3.colors
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val dynamics by viewModel.dynamics.collectAsStateWithLifecycle()
    val dynamicLoading by viewModel.dynamicLoading.collectAsStateWithLifecycle()
    val following by viewModel.following.collectAsStateWithLifecycle()
    val fans by viewModel.fans.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    // 特别关注（本地书签，v1.6.3）—— 与真实关注态完全独立
    val attended by viewModel.attended.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }

    // ⚠️ ViewModel 里的 toast 必须有人渲染（问题 9 的根因）。
    //
    // 此前 SpaceViewModel 认真地在未登录时写 `_toast = "请先登录"`，
    // 但 SpaceScreen **完全没有消费这个 flow** —— 没有 SnackbarHost、
    // 没有 LaunchedEffect。于是"未登录点关注"从用户视角就是**毫无反应**：
    // 事件发出去了，UI 层没有接收者，静默丢弃。
    //
    // 这是"状态有、渲染无"的典型缺口，与详情页 UP 头像不可点同类。
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    // 切到动态 Tab 才拉动态（避免进页面就打三个接口）
    LaunchedEffect(tab) {
        if (tab == 1) viewModel.loadDynamics()
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgPrimary),
    ) {
        // ---- 顶栏 ----
        // 通栏：不再是卡片，内容直接排。
        //
        // ⚠️ 补底边线：其它二级页的标题栏都有（设置 / 排行 / 收藏 …），
        // 本页原先没有 —— 头部信息块只给了 28dp 上间距、没有上边线，
        // 于是标题栏与内容之间**没有任何硬边界**，标题像是浮在头像上。
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
                text = profile?.name ?: "用户主页",
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            loading && profile == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.brand,
                    strokeWidth = V3Space.progressTrack,
                    modifier = Modifier.size(V3Size.iconLg),
                )
            }

            error != null && profile == null -> ErrorState(
                title = "用户主页加载失败",
                description = error,
                onRetry = viewModel::retry,
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = V3Space.xxl),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    ProfileHeader(
                        name = profile?.name.orEmpty(),
                        faceUrl = profile?.faceUrl(240).orEmpty(),
                        mid = profile?.mid ?: 0L,
                        sign = profile?.sign.orEmpty(),
                        fans = fans,
                        followingCount = profile?.following ?: 0,
                        level = profile?.level ?: 0,
                        isSelf = viewModel.isSelf,
                        isFollowing = following,
                        showFollowButton = !viewModel.isSelf,
                        // 特别关注（本地书签）：与真实关注是两个独立的量
                        attended = attended,
                        showAttentionButton = viewModel.attentionAvailable,
                        onAttentionClick = { viewModel.toggleAttention() },
                        onFollowClick = {
                            if (viewModel.isLoggedIn) {
                                viewModel.toggleFollow()
                            } else {
                                onLoginRequired()
                            }
                        },
                        onChatClick = {
                            if (viewModel.isLoggedIn) {
                                onChat(profile?.mid ?: 0L, profile?.name.orEmpty())
                            } else {
                                onLoginRequired()
                            }
                        },
                        // 「查成分」走 aicu 第三方聚合，**不依赖 B 站登录态**
                        // （aicu 自己维护数据），所以未登录也放行。
                        onAicuClick = {
                            val mid = profile?.mid ?: 0L
                            if (mid > 0L) onAicu(mid)
                        },
                    )
                }

                item(key = "tabs") {
                    TabRow(
                        selected = tab,
                        onSelect = { tab = it },
                        labels = listOf("投稿", "动态"),
                    )
                }

                if (tab == 0) {
                    if (videos.isEmpty()) {
                        item(key = "empty-videos") {
                            EmptyState(
                                title = "还没有投稿",
                                description = "该 UP 主还没有公开的视频",
                                compact = true,
                            )
                        }
                    } else {
                        // 用 2 列网格展示投稿（与首页一致）
                        items(
                            items = videos.chunked(2),
                            key = { row -> "v-" + row.joinToString("-") { it.bvid } },
                        ) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = V3Space.sm, vertical = V3Space.xxs),
                                horizontalArrangement = Arrangement.spacedBy(V3Space.sm),
                            ) {
                                row.forEach { v ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        VideoCard(
                                            video = v,
                                            onClick = { onVideoClick(v.bvid) },
                                        )
                                    }
                                }
                                // 奇数个时补一个空位，避免最后一张被拉伸成半宽
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    if (dynamicLoading && dynamics.isEmpty()) {
                        item(key = "dyn-loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = V3Space.xl),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = colors.brand,
                                    strokeWidth = V3Space.progressTrack,
                                    modifier = Modifier.size(V3Size.iconLg),
                                )
                            }
                        }
                    } else if (dynamics.isEmpty()) {
                        item(key = "empty-dyn") {
                            EmptyState(
                                title = "还没有动态",
                                description = "该用户还没有发布动态，或动态不可见",
                                compact = true,
                            )
                        }
                    } else {
                        items(dynamics, key = { "d-${it.id}" }) { d ->
                            DynamicCard(item = d, onVideoClick = onVideoClick)
                        }
                    }
                }
            }
        }
    }

        // ---- 一次性提示（关注成功 / 未登录拦截）----
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = V3Space.huge),
        )
    }
}

@Composable
private fun ProfileHeader(
    name: String,
    faceUrl: String,
    mid: Long,
    sign: String,
    fans: Int,
    followingCount: Int,
    level: Int,
    isSelf: Boolean,
    isFollowing: Boolean,
    showFollowButton: Boolean,
    /** 是否已加入「特别关注」（本地书签，与 [isFollowing] 无关）。 */
    attended: Boolean = false,
    /** 是否渲染「特别关注」入口（存储未注入时不渲染）。 */
    showAttentionButton: Boolean = false,
    onAttentionClick: () -> Unit = {},
    onFollowClick: () -> Unit,
    onChatClick: () -> Unit,
    onAicuClick: () -> Unit,
) {
    val colors = BiliV3.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 头部信息区通栏：去掉卡片，靠组间距与下方 Tab 分开。
            .padding(start = V3Space.md, end = V3Space.md, top = Rhythm.between),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = faceUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.avatarPlaceholder),
            )
            Spacer(Modifier.width(V3Space.sm))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name.ifEmpty { "未知用户" },
                        style = V3Type.subheadline,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // ⚠️ `weight(1f)` 而不是 `fill = false`：
                        // 长昵称要能占满并把 LV 徽标推到右边；
                        // 短昵称时徽标紧跟其右（weight 只影响"能吃多少"，
                        // 不改变"靠左"这一点）。见 V3SectionTitle 里的同类说明。
                        modifier = Modifier.weight(1f),
                    )
                    if (level > 0) {
                        Spacer(Modifier.width(V3Space.xs))
                        Text(
                            // 等级用 LV{n} 表达（本项目不使用 B 站的等级图标素材）
                            text = "LV$level",
                            style = V3Type.caption2,
                            color = colors.labelOnBrand,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(V3Radius.xs))
                                .background(colors.brand)
                                .padding(
                                    horizontal = V3Space.tagHorizontal,
                                    vertical = V3Space.tagVertical,
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(V3Space.hairline))
                Text(
                    text = "UID $mid",
                    style = V3Type.caption1,
                    color = colors.labelSecondary,
                )
                Spacer(Modifier.height(V3Space.xxs))
                Text(
                    text = "${formatCount(fans)} 粉丝 · ${formatCount(followingCount)} 关注",
                    style = V3Type.caption1,
                    color = colors.labelSecondary,
                )
            }
        }

        if (sign.isNotEmpty()) {
            Spacer(Modifier.height(V3Space.sm))
            Text(
                text = sign,
                style = V3Type.footnote.copy(
                    color = colors.labelSecondary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(V3Space.sm))

        // ---- 操作行：关注 · 发私信 · 查成分（同一行）----
        //
        // ⚠️ 布局修正（问题 10）：此前「查成分」被单独放在**第二行**，
        // 理由是"上面那行窄屏会挤"。但用户明确要求它在**发私信右方**，
        // 且实测这一行放得下 —— 挤的根源其实是内边距过大（`V3Space.md`
        // 横向 + 图标），不是按钮数量。
        //
        // 修法：
        // 1. 三个入口收进同一行，横向内边距 `x4 → x3`，按钮间距 `x3 → x2`
        // 2. 整行 `horizontalScroll` 兜底 —— 极窄屏/大字号下不会把
        //    任一入口挤出屏幕（比换行更符合"并排"的语义）
        // 3. 「数据来自 aicu.cc」降为第二行的极小字注脚，
        //    既不丢来源标注（合规要求），也不再占据操作行宽度
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(V3Space.xs),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            if (showFollowButton) {
                BrandButton(
                    label = if (isFollowing) "已关注" else "关注",
                    onClick = onFollowClick,
                    variant = if (isFollowing) BrandButtonVariant.Outline
                    else BrandButtonVariant.Filled,
                )
            } else {
                Text(
                    text = "这是你自己",
                    style = V3Type.caption1.copy(
                        color = colors.labelTertiary,
                    ),
                    maxLines = 1,
                )
            }

            // 发私信：自己的主页上不显示（不能给自己发私信）
            if (!isSelf) {
                HeaderActionChip(
                    icon = Icons.Outlined.MailOutline,
                    label = "发私信",
                    onClick = onChatClick,
                )
            }

            // ---- 特别关注（本地书签，v1.6.3）----
            //
            // ⚠️ 它**不是**真实关注：点击只写本地 DataStore，
            // 不发任何请求（`LocalAttentionStore` 拿不到 BiliApi）。
            //
            // 放在这里而不是塞进"关注"按钮的长按菜单：隐藏入口
            // 会让这个功能等于不存在（§1.6 死入口的同类问题）。
            if (showAttentionButton && !isSelf) {
                AttentionChip(
                    attended = attended,
                    onClick = onAttentionClick,
                )
            }

            // 查成分：第三方数据查询，不依赖 B 站登录态
            HeaderActionChip(
                icon = Icons.AutoMirrored.Outlined.ManageSearch,
                label = "查成分",
                onClick = onAicuClick,
            )
        }

        // 来源标注（合规要求：第三方数据必须标明出处）
        Spacer(Modifier.height(V3Space.xs))
        Text(
            text = "查成分数据来自 aicu.cc（第三方）",
            style = V3Type.caption2.copy(
                color = colors.labelTertiary,
            ),
        )
    }
}

/**
 * 「特别关注」入口胶囊（v1.6.3）。
 *
 * ## 🔴 为什么它必须与「关注」按钮长得**不一样**
 *
 * 需求要求"用户必须有明显的特殊标识"，而更根本的理由是：
 * 如果它看起来与真实关注一样，用户会**以为已经关注了对方** ——
 * 去 B 站网页端一看根本没关注，这是一个会持续误导人的假象。
 *
 * 所以：
 * - 真实关注 = `BrandButton`（品牌粉实心 / 描边，加号语义）
 * - 特别关注 = **书签图标 + 青色描边胶囊**（与"收藏/书签"同族语义）
 *
 * 两种形态在形状、图标、颜色三个维度都不同，不靠文字也能区分。
 *
 * ## 已加入 / 未加入
 *
 * - 未加入：描边 + 次要文字色（"可点"但不抢视觉）
 * - 已加入：青色底 + 深色字 + 填充图标（明确"已选中"）
 */
@Composable
private fun AttentionChip(
    attended: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(V3Radius.xs))
            .background(
                if (attended) colors.accentTerminalDim else colors.bgTertiary,
            )
            .then(
                if (attended) {
                    Modifier.border(
                        width = 1.dp,
                        color = colors.accentTerminal,
                        shape = RoundedCornerShape(V3Radius.xs),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            // 填充 = 已选中，轮廓 = 未选中（与全站"激活态"约定一致）
            imageVector = if (attended) {
                Icons.Filled.Bookmark
            } else {
                Icons.Outlined.BookmarkBorder
            },
            contentDescription = null,
            tint = if (attended) colors.accentTerminal else colors.labelSecondary,
            modifier = Modifier.size(V3Size.iconMd),
        )
        Spacer(Modifier.width(V3Space.xxs))
        Text(
            text = if (attended) "已特别关注" else "特别关注",
            style = V3Type.callout.copy(
                fontWeight = if (attended) FontWeight.Medium else FontWeight.Normal,
                color = if (attended) colors.accentTerminal else colors.labelSecondary,
            ),
            maxLines = 1,
        )
    }
}

/**
 * 主页操作行的次要入口胶囊（发私信 / 查成分）。
 *
 * 抽出来是因为两者样式完全一致 —— 内联两份必然会漂移
 * （此前它们就是各写一份，横向内边距也一样）。
 */
@Composable
private fun HeaderActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(V3Radius.xs))
            .background(colors.bgTertiary)
            .clickable(onClick = onClick)
            .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(V3Size.iconMd),
        )
        Spacer(Modifier.width(V3Space.xxs))
        Text(
            text = label,
            style = V3Type.callout.copy(
                color = colors.labelSecondary,
            ),
            maxLines = 1,
        )
    }
}

/** 两个 Tab 的切换行。 */
@Composable
private fun TabRow(
    selected: Int,
    onSelect: (Int) -> Unit,
    labels: List<String>,
) {
    val colors = BiliV3.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 标签条**不再是卡片** —— 它是一条切换栏，不是独立内容块。
            // 套卡会立刻多一个框。现在是一行纯文字 strip，靠上方组间距分隔。
            .padding(
                start = V3Space.md,
                end = V3Space.md,
                top = Rhythm.between,
                bottom = V3Space.xxs,
            ),
        horizontalArrangement = Arrangement.spacedBy(V3Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val isSelected = i == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onSelect(i) }
                    .padding(horizontal = V3Space.xxs, vertical = V3Space.xs),
            ) {
                Text(
                    text = label,
                    style = V3Type.callout.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) colors.labelPrimary else colors.labelSecondary,
                    ),
                )
                Spacer(Modifier.height(V3Space.hairline))
                // 下划线固定高度，切换时不抖
                // 高度用 `V3Space.tabIndicator`(3dp) —— 与其它页 Tab 一致（v1.4.2）
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(V3Space.tabIndicator)
                        .background(
                            if (isSelected) colors.brand
                            else androidx.compose.ui.graphics.Color.Transparent,
                        ),
                )
            }
        }
    }
}

/**
 * 动态卡片（用户主页与动态流共用）。
 *
 * ## 三种形态
 *
 * - [DynamicItem.Kind.Video]：正文 + 视频卡（可点进详情）
 * - [DynamicItem.Kind.Image]：正文 + 单图
 * - [DynamicItem.Kind.Text]：纯文本
 * - [DynamicItem.Kind.Unsupported]：明确标注"暂不支持的类型"
 *
 * 不支持的类型**不隐藏** —— 隐藏会让用户以为"这个人没发过这条"。
 */
@Composable
fun DynamicCard(
    item: DynamicItem,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            // ⚠️ 投稿列表行**不再是卡片** —— 列表用留白分组。
            .padding(horizontal = V3Space.md, vertical = V3Space.sm),
    ) {
        // ---- 作者行 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.faceUrl(72),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(V3Size.avatarSm)
                    .clip(CircleShape)
                    .background(colors.avatarPlaceholder),
            )
            Spacer(Modifier.width(V3Space.xs))
            Text(
                text = item.authorName,
                style = V3Type.caption1,
                fontWeight = FontWeight.Medium,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // ⚠️ 必须是 `weight(1f)`，不能 `fill = false` ——
                // 否则时间戳紧贴作者名而不是靠到行尾（与 V3SectionTitle
                // 那个 bug 同类：叫"右侧"的东西必须靠右）。
                modifier = Modifier.weight(1f),
            )
            if (item.publishedAt > 0) {
                Spacer(Modifier.width(V3Space.xs))
                Text(
                    text = formatRelativeTime(item.publishedAt),
                    style = V3Type.caption2,
                    color = colors.labelTertiary,
                )
            }
        }

        // ---- 正文 ----
        if (item.text.isNotEmpty()) {
            Spacer(Modifier.height(V3Space.xs))
            Text(
                text = item.text,
                style = V3Type.callout,
                color = colors.labelPrimary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ---- 内容区 ----
        when (item.kind) {
            DynamicItem.Kind.Video -> {
                Spacer(Modifier.height(V3Space.sm))
                // 🔴 **不再套容器**（v3）。
                //
                // 旧版是 `clip(xs) + background(bgTertiary)` —— 一个圆角矩形装
                // 封面 + 标题。那正是「卡片海洋」的最小形态：
                // 动态流里每条带视频的动态都长出一个盒子，
                // 滚动时满屏都是矩形边界。
                //
                // v3 的做法：**封面 + 标题直接排**，靠间距与对齐建立关系。
                // 可点性由 `clickable` 提供，不需要靠底色暗示
                // （底色是"这是个区域"的语义，见 V3Colors 的三层系统）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onVideoClick(item.bvid) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            // 16:10 = B 站官方 viewAspectRatio，与全站封面一致
                            .width(112.dp)
                            .aspectRatio(V3Size.coverAspect)
                            .background(colors.coverPlaceholder),
                    ) {
                        AsyncImage(
                            model = item.coverUrl(320),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item.durationLabel.isNotEmpty()) {
                            Text(
                                text = item.durationLabel,
                                style = V3Type.caption2,
                                color = colors.labelOnMedia,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(V3Space.hairline)
                                    .background(colors.overlay)
                                    .padding(
                                        horizontal = V3Space.tagHorizontal,
                                        vertical = V3Space.tagVertical,
                                    ),
                            )
                        }
                    }
                    Spacer(Modifier.width(V3Space.sm))
                    Text(
                        text = item.videoTitle,
                        style = V3Type.footnote,
                        color = colors.labelPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            DynamicItem.Kind.Image -> {
                Spacer(Modifier.height(V3Space.sm))
                AsyncImage(
                    model = item.coverUrl(480),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(V3Size.coverAspect)
                        // 图片一律直角（内容是内容，不套圆角）
                        .background(colors.coverPlaceholder),
                )
            }

            DynamicItem.Kind.Text -> Unit

            DynamicItem.Kind.Unsupported -> {
                Spacer(Modifier.height(V3Space.xs))
                Text(
                    // 明确标注而不是静默隐藏 —— 信息完整性优先
                    text = "暂不支持显示的类型（${item.type.removePrefix("DYNAMIC_TYPE_")}）",
                    style = V3Type.caption2,
                    color = colors.labelTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(V3Radius.xs))
                        .background(colors.fillQuaternary)
                        .padding(
                            horizontal = V3Space.xs,
                            vertical = V3Space.xxs,
                        ),
                )
            }
        }

        // ---- 统计行（只读，不做假互动）----
        if (item.likeCount > 0 || item.commentCount > 0 || item.forwardCount > 0) {
            Spacer(Modifier.height(V3Space.sm))
            Text(
                text = buildString {
                    if (item.forwardCount > 0) append("转发 ${formatCount(item.forwardCount)}   ")
                    if (item.commentCount > 0) append("评论 ${formatCount(item.commentCount)}   ")
                    if (item.likeCount > 0) append("赞 ${formatCount(item.likeCount)}")
                }.trim(),
                style = V3Type.caption2,
                color = colors.labelTertiary,
            )
        }
    }
}

/** 便于外层复用的视频列表类型（避免未使用导入告警）。 */
internal typealias SpaceVideoList = List<VideoItem>
