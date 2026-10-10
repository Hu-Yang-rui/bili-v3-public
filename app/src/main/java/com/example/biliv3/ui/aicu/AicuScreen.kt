package com.example.biliv3.ui.aicu

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.biliv3.data.AicuLiveDanmaku
import com.example.biliv3.data.AicuRepository
import com.example.biliv3.data.AicuReply
import com.example.biliv3.data.AicuUserCard
import com.example.biliv3.data.AicuUserMark
import com.example.biliv3.data.AicuVideoDanmaku
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.data.model.formatRelativeTime
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.ruleTop
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * aicu.cc「查成分」页。
 *
 * ## 这个页面解决什么问题
 *
 * B 站移动端**不提供**"查某人在全站发过哪些评论 / 弹幕"的能力 ——
 * 官方只能在自己的动态里翻。aicu 提供这个聚合查询，
 * 本项目把它接进来（数据来源标注在页面上，见列表底部）。
 *
 * ## 三个 Tab
 *
 * 评论 / 视频弹幕 / 直播弹幕。
 *
 * ⚠️ **没有「按 UP 查直播」Tab** —— aicu 的 `getlivebyup` 接口
 * 实测返回 `-403 暂时关闭`，挂上去就是死入口。
 *
 * ## 三态
 *
 * | 场景 | 显示 |
 * |---|---|
 * | 未查询 | 引导态（不是空态 —— 空态会让人以为"查过了但没数据"） |
 * | 加载中 | 转圈 |
 * | 查到为空 | 空态（文案具体到"该 UID 没有公开的评论"） |
 * | 失败 | 错误 + 重试 |
 *
 * ## ⚠️ 连通性说明
 *
 * 中国大陆直连 aicu.cc 不通的根因是**纯 DNS 投毒**，本项目用
 * `AicuDns`（DoH + 结果校验 + 硬编码兜底）接管解析来绕开，
 * 零代理、零 VPN、零改 hosts。详见 `AicuDns` 的类注释。
 */
@Composable
fun AicuScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AicuViewModel,
    /**
     * 「查看评论」——在**站内**打开该条评论所在的视频详情页（问题 8）。
     *
     * ## 为什么需要这个入口
     *
     * 查成分的价值是"这个人发过什么评论"，但看到一条评论后，
     * 用户真正想做的是**回到上下文里看原视频**。此前只提供
     * 「在原站查看」→ 跳系统浏览器，等于把用户踢出 App。
     *
     * 参数是 **av 号（纯数字字符串）**，由调用方负责转成 bvid 再导航 ——
     * 这里不引 `VideoRepository`，保持 aicu 页面只依赖它自己的 ViewModel。
     * 非视频类评论（专栏 / 动态）没有站内页，传 null 时不显示该入口。
     *
     * 同时把 `rpid` 与 `dynType` 一起传出：
     * - `rpid` 让详情页能**定位到那条评论**（否则只是打开视频）
     * - `dynType` 让调用方判断能否用视频页承载（只有 1=视频 可以）
     */
    onOpenCommentInApp: (avId: String, rpid: String, dynType: Int) -> Unit = { _, _, _ -> },
) {
    val colors = BiliV3.colors

    val uidInput by viewModel.uidInput.collectAsStateWithLifecycle()
    val queryUid by viewModel.queryUid.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()

    val replies by viewModel.replies.collectAsStateWithLifecycle()
    val videoDanmaku by viewModel.videoDanmaku.collectAsStateWithLifecycle()
    val liveDanmaku by viewModel.liveDanmaku.collectAsStateWithLifecycle()
    val replyTotal by viewModel.replyTotal.collectAsStateWithLifecycle()
    val userMark by viewModel.userMark.collectAsStateWithLifecycle()
    val userCard by viewModel.userCard.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    // 触底加载：剩余不足 3 条时预取
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last.index >= total - 3
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { atBottom }.collect { if (it) viewModel.loadMore() }
    }

    // 切 Tab 时重置滚动位置（否则从长列表切到短列表会停在半空）
    LaunchedEffect(tab) { listState.scrollToItem(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPrimary),
    ) {
        // ---- 顶栏 ----
        // 通栏：不再是卡片，内容直接排。
        //
        // ⚠️ 补底边线：与其它二级页的标题栏一致。
        // 本页下方是 UID 输入区（只有 8dp 内边距、无线），
        // 原先标题栏与输入区之间没有任何硬边界。
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                text = "查成分",
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
            )
        }

        // ---- 搜索区（UID 输入 + 查询按钮）----
        UidSearchBar(
            value = uidInput,
            onValueChange = viewModel::onUidInputChange,
            onSubmit = viewModel::submit,
            loading = loading,
        )

        // ---- Tab 条 ----
        TabRow(
            selected = tab,
            onSelect = viewModel::selectTab,
        )

        // ---- 内容区 ----
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                // 1) 还没查询：引导态。
                //    ⚠️ 不能显示空态 —— "还没查"和"查了没有"是两件事，
                //    混在一起用户会以为"这个 UID 真的没评论"。
                queryUid == null && error == null -> GuideState()

                // 2) 首屏加载中
                loading && isEmpty(tab, replies, videoDanmaku, liveDanmaku) -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = colors.brand,
                        strokeWidth = V3Space.progressTrack,
                        modifier = Modifier.size(V3Size.iconLg),
                    )
                }

                // 3) 失败
                error != null -> ErrorState(
                    title = "查询失败",
                    description = error,
                    onRetry = viewModel::retry,
                    modifier = Modifier.fillMaxSize(),
                )

                // 4) 内容列表
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = V3Space.xxl),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 用户资料卡 + 成分标记（有内容才渲染，不留空壳）
                    if (userCard != null || userMark?.hasContent == true) {
                        item(key = "profile") {
                            UserCard(
                                card = userCard,
                                mark = userMark,
                                uid = queryUid ?: 0L,
                                onOpenSpace = { mid ->
                                    onOpenUrl("https://space.bilibili.com/$mid")
                                },
                            )
                        }
                    }

                    // 评论 Tab 顶部显示总数（-1 = 未请求真实总数，不显示）
                    if (tab == AicuTab.REPLIES && replyTotal >= 0) {
                        item(key = "total") {
                            TotalHint(
                                text = if (replyTotal > 0) {
                                    "共 ${formatCount(replyTotal)} 条评论"
                                } else {
                                    ""
                                },
                            )
                        }
                    }

                    when (tab) {
                        AicuTab.REPLIES -> {
                            if (replies.isEmpty() && !loading) {
                                item(key = "empty") {
                                    EmptyState(
                                        title = "该 UID 没有公开的评论",
                                        // 终端风：这是"列表为空"的次要状态，
                                        // 一屏可能反复出现，用轻量提示符行而非大图标
                                        terminalStyle = true,
                                        description = "可能从未发过评论，或评论已被删除",
                                        compact = true,
                                    )
                                }
                            } else {
                                items(replies, key = { "r-${it.rpid}" }) { r ->
                                    ReplyCard(
                                        reply = r,
                                        onOpen = onOpenUrl,
                                        // 站内跳转：仅当该评论挂在**视频**上时可用
                                        // （dyn.type == 1 才有 bvid 可导航）。
                                        // 专栏/动态没有对应的站内页，只能走外链。
                                        onOpenInApp = onOpenCommentInApp,
                                    )
                                }
                            }
                        }

                        AicuTab.VIDEO_DANMAKU -> {
                            if (videoDanmaku.isEmpty() && !loading) {
                                item(key = "empty") {
                                    EmptyState(
                                        title = "该 UID 没有公开的视频弹幕",
                                        terminalStyle = true,
                                        description = "弹幕数据来自 aicu 的聚合，可能不完整",
                                        compact = true,
                                    )
                                }
                            } else {
                                items(videoDanmaku, key = { "vd-${it.id}" }) { d ->
                                    VideoDanmakuCard(item = d, onOpen = onOpenUrl)
                                }
                            }
                        }

                        AicuTab.LIVE_DANMAKU -> {
                            if (liveDanmaku.isEmpty() && !loading) {
                                item(key = "empty") {
                                    EmptyState(
                                        title = "该 UID 没有公开的直播弹幕",
                                        terminalStyle = true,
                                        description = "仅统计被 aicu 收录过的直播间",
                                        compact = true,
                                    )
                                }
                            } else {
                                items(liveDanmaku, key = { "ld-${it.roomId}" }) { d ->
                                    LiveDanmakuCard(item = d, onOpen = onOpenUrl)
                                }
                            }
                        }
                    }

                    // 底部：加载更多 / 到底提示
                    item(key = "footer") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = V3Space.lg),
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

                    // 数据来源标注（合规要求：必须写明来源）
                    item(key = "source") {
                        Text(
                            text = "数据来源：aicu.cc（第三方聚合站点，数据取自 B 站公开数据）",
                            style = V3Type.caption2.copy(
                                color = colors.labelTertiary,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = V3Space.md, vertical = V3Space.sm),
                        )
                    }
                }
            }
        }
    }
}

/** 当前 Tab 是否还没有任何数据。 */
private fun isEmpty(
    tab: AicuTab,
    replies: List<AicuReply>,
    videoDanmaku: List<AicuVideoDanmaku>,
    liveDanmaku: List<AicuLiveDanmaku>,
): Boolean = when (tab) {
    AicuTab.REPLIES -> replies.isEmpty()
    AicuTab.VIDEO_DANMAKU -> videoDanmaku.isEmpty()
    AicuTab.LIVE_DANMAKU -> liveDanmaku.isEmpty()
}

/**
 * 首屏引导态。
 *
 * ⚠️ 与空态**必须区分**：这里还没发过任何请求。
 */
@Composable
private fun GuideState() {
    val colors = BiliV3.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = V3Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(V3Space.md))
        Text(
            text = "输入 UID 查询该用户在 B 站的评论与弹幕",
            style = V3Type.callout.copy(
                color = colors.labelSecondary,
            ),
        )
        Spacer(Modifier.height(V3Space.xs))
        Text(
            text = "数据来自第三方站点 aicu.cc，非官方接口",
            style = V3Type.footnote.copy(
                color = colors.labelTertiary,
            ),
        )
    }
}

/** UID 输入 + 查询按钮。 */
@Composable
private fun UidSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    loading: Boolean,
) {
    val colors = BiliV3.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = "UID",
                    style = V3Type.callout.copy(
                        color = colors.labelTertiary,
                    ),
                )
            },
            singleLine = true,
            // UID 是纯数字 —— 直接弹数字键盘，少一次切换
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            // 输入框：交互元素，4dp
            shape = RoundedCornerShape(V3Radius.xs),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.brand,
                unfocusedBorderColor = colors.separator,
                focusedTextColor = colors.labelPrimary,
                unfocusedTextColor = colors.labelPrimary,
                cursorColor = colors.brand,
                focusedContainerColor = colors.bgSecondary,
                unfocusedContainerColor = colors.bgSecondary,
            ),
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(V3Space.xs))

        BrandButton(
            label = if (loading) "查询中" else "查询",
            onClick = onSubmit,
            variant = BrandButtonVariant.Filled,
            // 查询中禁用，避免连点消耗 aicu 的排队额度
            enabled = !loading && value.isNotEmpty(),
        )
    }
}

/** 三个 Tab 的切换条。 */
@Composable
private fun TabRow(
    selected: AicuTab,
    onSelect: (AicuTab) -> Unit,
) {
    val colors = BiliV3.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ Tab 条**不再是卡片** —— 它是一条切换栏，不是独立内容块。
            // 现在是一行纯文字 strip，选中态靠下划线表达。
            .padding(horizontal = V3Space.md),
        horizontalArrangement = Arrangement.spacedBy(V3Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AicuTab.entries.forEach { t ->
            val isSelected = t == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(t) }
                    .padding(vertical = V3Space.xs),
            ) {
                Text(
                    text = t.label,
                    style = V3Type.footnote.copy(
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

/** 总数提示条。 */
@Composable
private fun TotalHint(text: String) {
    if (text.isEmpty()) return
    val colors = BiliV3.colors
    Text(
        text = text,
        style = V3Type.caption1.copy(
            color = colors.labelSecondary,
        ),
        modifier = Modifier.padding(horizontal = V3Space.md, vertical = V3Space.xs),
    )
}

/**
 * 用户资料卡 + 成分标记。
 *
 * ## 为什么两块合在一张卡里
 *
 * 它们回答的是同一个问题（"这个人是谁 / 什么成分"），
 * 拆成两张卡会让首屏被卡片切碎。
 *
 * ## 缺失字段用占位而不是隐藏
 *
 * 头像/昵称拿不到时仍然渲染该位置（灰底占位）——
 * 条件渲染会把"上游字段写错"变成**静默消失**，肉眼不可见
 * （这是本项目记录过的真实教训，见 `AGENTS.md` §7.2）。
 */
@Composable
private fun UserCard(
    card: AicuUserCard?,
    mark: AicuUserMark?,
    uid: Long,
    onOpenSpace: (Long) -> Unit,
) {
    val colors = BiliV3.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 查询对象信息块**不再是卡片** —— 它是通栏的整体信息块。
            // 靠上边一条发丝线 + 组间距与上方输入区/ Tab 分开。
            .ruleTop(color = Rule.subtle)
            .padding(start = V3Space.md, end = V3Space.md, top = Rhythm.between),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = card?.faceUrl(240).orEmpty(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.avatarPlaceholder),
            )
            Spacer(Modifier.width(V3Space.sm))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card?.name.orEmpty().ifEmpty { "UID $uid" },
                    style = V3Type.subheadline.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.labelPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(V3Space.hairline))
                Text(
                    text = buildString {
                        append("UID $uid")
                        if (card != null && card.archiveCount > 0) {
                            append(" · ${formatCount(card.archiveCount)} 投稿")
                        }
                        if (card != null && card.fans > 0) {
                            append(" · ${formatCount(card.fans.toInt())} 粉丝")
                        }
                    },
                    style = V3Type.caption1.copy(
                        color = colors.labelSecondary,
                    ),
                )
            }

            // 打开 B 站主页：真实可用的出口（不是死入口）
            Box(
                modifier = Modifier
                    // 按钮：交互元素，4dp
                    .clip(RoundedCornerShape(V3Radius.xs))
                    .background(colors.bgTertiary)
                    .clickable { onOpenSpace(uid) }
                    .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "主页",
                        style = V3Type.caption1.copy(
                            color = colors.labelSecondary,
                        ),
                    )
                    Spacer(Modifier.width(V3Space.xxs))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(V3Size.iconXs),
                    )
                }
            }
        }

        if (card != null && card.sign.isNotEmpty()) {
            Spacer(Modifier.height(V3Space.xs))
            Text(
                text = card.sign,
                style = V3Type.footnote.copy(
                    color = colors.labelSecondary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ---- 成分标记 ----
        if (mark != null && mark.hasContent) {
            Spacer(Modifier.height(V3Space.sm))
            Column(verticalArrangement = Arrangement.spacedBy(V3Space.xxs)) {
                mark.gh.takeIf { it.isNotEmpty() }?.let { MarkRow("成分", it) }
                mark.gh2.takeIf { it.isNotEmpty() }?.let { MarkRow("标签", it) }
                mark.text.takeIf { it.isNotEmpty() }?.let { MarkRow("说明", it) }
                if (mark.devices.isNotEmpty()) {
                    MarkRow("设备", mark.devices.joinToString("、"))
                }
                if (mark.tags.isNotEmpty()) {
                    MarkRow("Tag", mark.tags.joinToString("、"))
                }
                if (mark.hnames.isNotEmpty()) {
                    MarkRow("曾用名", mark.hnames.joinToString("、"))
                }
            }
        }
    }
}

/** 成分标记的一行：左侧标签 + 右侧内容。 */
@Composable
private fun MarkRow(label: String, value: String) {
    val colors = BiliV3.colors
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = V3Type.caption2.copy(
                color = colors.labelTertiary,
            ),
            modifier = Modifier.width(48.dp),
        )
        Text(
            text = value,
            style = V3Type.footnote.copy(
                color = colors.labelSecondary,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 评论卡片。
 *
 * ## 楼中楼标记
 *
 * `rank = 2` 的回复会被标注「回复」—— 不标的话，
 * 用户看到一堆上下文缺失的短句会完全看不懂。
 */
@Composable
private fun ReplyCard(
    reply: AicuReply,
    onOpen: (String) -> Unit,
    onOpenInApp: (String, String, Int) -> Unit = { _, _, _ -> },
) {
    val colors = BiliV3.colors
    val canOpen = reply.targetUrl.isNotEmpty()
    // 只有视频类评论能站内跳（dyn.type == 1，oid 即 av 号）。
    // 专栏 / 动态在 App 内没有对应页面，硬跳会得到空白页。
    val canOpenInApp = reply.dynType == 1 && reply.oid.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 评论/弹幕**不再是卡片** —— 它们是列表里的一行。
            // 列表用留白分组，不用卡片分组。
            .padding(horizontal = V3Space.md, vertical = V3Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (reply.isNested) {
                Text(
                    text = "回复",
                    style = V3Type.caption2.copy(
                        color = colors.labelOnBrand,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(V3Radius.xs))
                        .background(colors.brandDim)
                        .padding(horizontal = V3Space.tagHorizontal, vertical = V3Space.tagVertical),
                )
                Spacer(Modifier.width(V3Space.xs))
            }
            Text(
                text = dynTypeLabel(reply.dynType),
                style = V3Type.caption2.copy(
                    color = colors.labelTertiary,
                ),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatRelativeTime(reply.timeSeconds),
                style = V3Type.caption2.copy(
                    color = colors.labelTertiary,
                ),
            )
        }

        Spacer(Modifier.height(V3Space.xs))

        Text(
            text = reply.message,
            style = V3Type.callout.copy(
                color = colors.labelPrimary,
            ),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )

        // ---- 操作行：站内看评论（优先） · 原站查看（兜底）----
        //
        // ⚠️ 为什么不再是"整卡片可点 + 一行提示"（问题 8）：
        //
        // 首版整张卡片 `clickable { onOpen(targetUrl) }` 直接甩到系统浏览器。
        // 用户想"看这条评论的上下文"时被踢出 App，且卡片上没有
        // 任何"我会打开浏览器"的提示，点了才知道。
        //
        // 现在改为**显式两个动作**，站内优先：
        // - 「在 App 内看评论」：只有视频类评论才有（有站内页）
        // - 「原站查看」：始终可用，但明确标注是外部打开
        if (canOpen || canOpenInApp) {
            Spacer(Modifier.height(V3Space.xs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(V3Space.md),
            ) {
                if (canOpenInApp) {
                    // 站内：带视频图标，主色，视觉上优先
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(V3Radius.xs))
                            .clickable { onOpenInApp(reply.oid, reply.rpid, reply.dynType) }
                            .padding(vertical = V3Space.hairline),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayCircleOutline,
                            contentDescription = null,
                            tint = colors.brandBiliText,
                            modifier = Modifier.size(V3Size.iconXs),
                        )
                        Spacer(Modifier.width(V3Space.xxs))
                        Text(
                            text = "在 App 内看评论",
                            style = V3Type.caption2.copy(
                                color = colors.brandBiliText,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }

                if (canOpen) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(V3Radius.xs))
                            .clickable { onOpen(reply.targetUrl) }
                            .padding(vertical = V3Space.hairline),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            tint = colors.labelTertiary,
                            modifier = Modifier.size(V3Size.iconXs),
                        )
                        Spacer(Modifier.width(V3Space.xxs))
                        Text(
                            text = "原站查看",
                            style = V3Type.caption2.copy(
                                color = colors.labelTertiary,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 视频弹幕卡片。 */
@Composable
private fun VideoDanmakuCard(item: AicuVideoDanmaku, onOpen: (String) -> Unit) {
    val colors = BiliV3.colors
    val canOpen = item.targetUrl.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 弹幕列表行**不再是卡片** —— 列表用留白分组。
            .then(
                if (canOpen) Modifier.clickable { onOpen(item.targetUrl) } else Modifier,
            )
            .padding(horizontal = V3Space.md, vertical = V3Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Subtitles,
                contentDescription = null,
                tint = colors.brand,
                modifier = Modifier.size(V3Size.iconMd),
            )
            Spacer(Modifier.width(V3Space.xxs))
            Text(
                // progress 是毫秒，这里换算成时间轴 —— 直接当秒用会显示成 20 分钟
                text = AicuRepository.formatDanmakuTime(item.progressMs),
                style = V3Type.caption1.copy(
                    fontWeight = FontWeight.Medium,
                    color = colors.labelPrimary,
                ),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatRelativeTime(item.ctimeSeconds),
                style = V3Type.caption2.copy(
                    color = colors.labelTertiary,
                ),
            )
        }

        Spacer(Modifier.height(V3Space.xs))

        Text(
            text = item.content,
            style = V3Type.callout.copy(
                color = colors.labelPrimary,
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(V3Space.xs))

        Text(
            // oid 是 av 号（不是 bvid）—— 标注出来便于用户核对
            text = "视频 av${item.oid}",
            style = V3Type.caption2.copy(
                color = colors.labelTertiary,
            ),
        )
    }
}

/** 直播弹幕卡片：一个直播间 + 其中的发言。 */
@Composable
private fun LiveDanmakuCard(item: AicuLiveDanmaku, onOpen: (String) -> Unit) {
    val colors = BiliV3.colors
    val canOpen = item.targetUrl.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 评论/弹幕**不再是卡片** —— 它们是列表里的一行。
            // 列表用留白分组，不用卡片分组。
            .padding(horizontal = V3Space.md, vertical = V3Space.sm),
    ) {
        // 房间头
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.upName.ifEmpty { "未知主播" },
                style = V3Type.footnote,
                fontWeight = FontWeight.Medium,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 🔴 这里**不要** `fill = false`。
                //
                // 同一行里下面还有一个 `Spacer(weight(1f))` 用来把右侧的
                // "进入直播间"推到行尾。两个 weight 争夺同一段剩余空间时，
                // 标题会按内容宽度收缩、而 Spacer 吃掉剩下的 ——
                // 房间号的位置就会**随主播名长度左右浮动**。
                //
                // 与 `LibraryScreens.kt` 里那段注释记的是同一个坑：
                // 「第一版写成 `weight(1f, fill = false)` + 再加一个
                //  `Spacer(weight(1f))` —— 两个 weight 争夺同一段剩余空间」。
                //
                // 判据：**一行里只能有一个"吃掉剩余空间"的元素。**
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(V3Space.xs))
            Text(
                // roomid 接口给的是字符串，仓库层已转 Long
                text = "房间 ${item.roomId}",
                style = V3Type.caption2,
                color = colors.labelTertiary,
            )
            if (canOpen) {
                Text(
                    text = "进直播间 ›",
                    style = V3Type.caption2.copy(
                        color = colors.brandBiliText,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(V3Radius.xs))
                        .clickable { onOpen(item.targetUrl) }
                        .padding(horizontal = V3Space.xxs, vertical = V3Space.hairline),
                )
            }
        }

        if (item.roomName.isNotEmpty()) {
            Spacer(Modifier.height(V3Space.xxs))
            Text(
                text = item.roomName,
                style = V3Type.caption1.copy(
                    color = colors.labelSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 发言列表
        if (item.lines.isNotEmpty()) {
            Spacer(Modifier.height(V3Space.xs))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 发言列表是面板（非交互）→ 直角
                    .background(colors.bgTertiary)
                    .padding(V3Space.xs),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(V3Space.xxs)) {
                    item.lines.take(5).forEach { line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = line.uname,
                                style = V3Type.caption2.copy(
                                    color = colors.labelSecondary,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(72.dp),
                            )
                            Spacer(Modifier.width(V3Space.xxs))
                            Text(
                                text = line.text,
                                style = V3Type.footnote.copy(
                                    color = colors.labelPrimary,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    // 多于 5 条时明确说明"还有更多" —— 不静默截断
                    if (item.lines.size > 5) {
                        Text(
                            text = "另有 ${item.lines.size - 5} 条未显示",
                            style = V3Type.caption2.copy(
                                color = colors.labelTertiary,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * `dyn.type` → 中文标签。
 *
 * 与 `AicuRepository.replyTargetUrl` 的分流表**必须一致** ——
 * 标签说"视频"而链接跳到动态页是很明显的错误。
 */
private fun dynTypeLabel(type: Int): String = when (type) {
    1 -> "视频"
    12 -> "专栏"
    17 -> "动态"
    else -> "其他"
}
