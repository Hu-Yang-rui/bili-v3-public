package com.example.biliv3.ui.message

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.biliv3.data.model.PmMessage
import com.example.biliv3.data.model.PmSession
import com.example.biliv3.data.model.formatRelativeTime
import com.example.biliv3.design.band
import com.example.biliv3.design.BandLevel
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * 私信会话列表页。
 *
 * ## 未登录态
 *
 * 私信**必须登录**（实测未登录返回 `-101`）。
 * 未登录时不给空列表 + 报错，而是显示明确的"请先登录"引导 ——
 * 空列表会让用户以为"没人给我发消息"。
 */
@Composable
fun MessageListScreen(
    onBack: () -> Unit,
    onOpenChat: (PmSession) -> Unit,
    onLogin: () -> Unit,
    viewModel: MessageListViewModel,
    modifier: Modifier = Modifier,
    /** 点头像 → 对方主页。 */
    onOpenSpace: (Long) -> Unit = {},
) {
    val colors = BiliV3.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPrimary),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不再是卡片：与页面同明度，靠底边一条发丝线分隔
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
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
                text = "消息",
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
            )
            if (state.unread > 0) {
                Spacer(Modifier.width(V3Space.xs))
                Text(
                    text = "${state.unread}",
                    style = V3Type.caption2.copy(
                        color = colors.labelOnBrand,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(V3Radius.xs))
                        .background(colors.brand)
                        .padding(horizontal = V3Space.xs, vertical = V3Space.tagVertical),
                )
            }
        }

        when {
            !state.loggedIn -> EmptyHint(
                text = "登录后可查看私信",
                actionLabel = "去登录",
                onAction = onLogin,
            )

            state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.brand,
                    strokeWidth = V3Space.progressTrack,
                    modifier = Modifier.size(V3Size.iconLg),
                )
            }

            state.error != null -> EmptyHint(
                text = state.error.orEmpty(),
                actionLabel = "重试",
                onAction = viewModel::refresh,
            )

            state.sessions.isEmpty() -> EmptyHint(
                text = "还没有私信",
                actionLabel = null,
                onAction = {},
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = V3Space.xl),
            ) {
                items(state.sessions, key = { it.talkerId }) { s ->
                    SessionRow(
                        session = s,
                        onClick = { onOpenChat(s) },
                        onAvatarClick = { onOpenSpace(s.talkerId) },
                    )
                }
            }
        }
    }
}

/** 会话行：头像 + 昵称 + 最后一条消息 + 时间 + 未读红点。 */
@Composable
private fun SessionRow(
    session: PmSession,
    onClick: () -> Unit,
    /**
     * 点头像 → 对方主页。
     *
     * ⚠️ 此前整行只有"进聊天"一个动作，**头像不可单独点** ——
     * 而 `AGENTS.md` §11 的私信验收明确要求：
     * 「私信列表点头像进主页、点整行进聊天，两者不能混」。
     */
    onAvatarClick: () -> Unit = {},
) {
    val colors = BiliV3.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 会话行**不再是卡片**（原为每行一张 `biliCard`）。
            //
            // 一屏 6~8 个会话 = 6~8 个框，每个框都带玻璃底 + 高光边。
            // 这是"列表页臃肿"的典型形态 —— 框比内容还抢眼。
            //
            // 现在：行与行靠留白分隔，整行可点。
            // **列表用留白分组，不用卡片分组。**
            .clickable(onClick = onClick)
            .padding(horizontal = V3Space.md, vertical = V3Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = session.faceUrl(96),
            contentDescription = "进入用户主页",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(SESSION_AVATAR)
                .clip(CircleShape)
                .background(colors.avatarPlaceholder)
                // 独立点击：头像进主页、整行进聊天
                .clickable(onClick = onAvatarClick),
        )
        Spacer(Modifier.width(V3Space.sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.talkerName,
                style = V3Type.callout.copy(
                    fontWeight = FontWeight.Medium,
                    color = colors.labelPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.lastMessage.isNotEmpty()) {
                Spacer(Modifier.height(V3Space.hairline))
                Text(
                    text = session.lastMessage,
                    style = V3Type.caption1.copy(
                        color = colors.labelSecondary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(V3Space.xs))

        Column(horizontalAlignment = Alignment.End) {
            if (session.lastTime > 0) {
                Text(
                    text = formatRelativeTime(session.lastTime),
                    style = V3Type.caption2.copy(
                        color = colors.labelTertiary,
                    ),
                )
            }
            // 未读红点：数字比圆点信息量大（能看出积压多少）
            if (session.unreadCount > 0) {
                Spacer(Modifier.height(V3Space.xxs))
                Text(
                    text = if (session.unreadCount > 99) "99+" else "${session.unreadCount}",
                    style = V3Type.caption2.copy(
                        color = colors.labelOnBrand,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(V3Radius.xs))
                        .background(colors.brand)
                        .padding(horizontal = V3Space.xs, vertical = V3Space.tagVertical),
                )
            }
        }
    }
}

/** 聊天页：消息气泡 + 输入框。 */
@Composable
fun ChatScreen(
    talkerName: String,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    // ---- 表情面板状态（未发版）----
    val emotePackages by viewModel.emotePackages.collectAsStateWithLifecycle()
    val emoteLoading by viewModel.emoteLoading.collectAsStateWithLifecycle()
    val emoteError by viewModel.emoteError.collectAsStateWithLifecycle()
    val emoteOpen by viewModel.emotePanelOpen.collectAsStateWithLifecycle()
    val pendingEmote by viewModel.pendingEmote.collectAsStateWithLifecycle()

    /**
     * 表情插入（未发版）。
     *
     * ## 🔴 为什么用"一次性信号 + 消费"
     *
     * 草稿是**本 Composable 的局部状态**，ViewModel 拿不到。
     * VM 只发"用户选了哪个 token"这个事件，这里消费后追加到草稿。
     *
     * ⚠️ 消费后必须 `consumeEmoteInsert()` 清掉 ——
     *    否则每次重组都会再插一遍（点一次出现两个）。
     */
    LaunchedEffect(pendingEmote) {
        pendingEmote?.let { token ->
            // 追加而不是覆盖：用户可能已经打了半句话
            draft = draft + token
            viewModel.consumeEmoteInsert()
        }
    }

    /**
     * 表情 token → 图片地址（未发版）。
     *
     * ## 为什么需要它
     *
     * 私信里的表情在协议上就是**文本 token**（`[OK]`）。
     * 没有这个映射，**发送与接收都会显示成方括号文字**。
     *
     * ⚠️ 只收**图片**表情（颜文字的 url 就是文字本身，
     * 把它当图片加载会 404）。
     */
    val emoteUrlMap = remember(emotePackages) {
        buildMap {
            emotePackages.forEach { p ->
                p.emotes.forEach { e ->
                    if (e.isImage && e.usable) put(e.token, e.url)
                }
            }
        }
    }

    // 新消息到达时滚到底（即时通讯的基本预期）
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(state.messages.lastIndex) }
        }
    }

    /**
     * 错误提示（v1.4.2 修）。
     *
     * ## 首版这里把 error 读出来就扔了
     *
     * 原代码是：
     * ```kotlin
     * // 错误用 snackbar 太重，这里直接清掉并靠 toast 通路提示
     * LaunchedEffect(state.error) {
     *     state.error?.let { viewModel.consumeError() }
     * }
     * ```
     * 注释声称"靠 toast 通路提示"，但**本文件乃至整个 ui/message 目录
     * 都没有任何 Toast / Snackbar / Log**（grep 可验证）。也就是说：
     * `ChatViewModel` 认真把 `userMessageFor(e)` 写进了 `state.error`，
     * 这里读出来直接丢弃 —— 生产端有，消费端扔掉。
     *
     * 后果是**发消息失败时用户的消息凭空消失且毫无解释**，
     * 用户只会以为"卡了"然后反复重发。私信是写操作，不能这么处理。
     *
     * ## 为什么用 Toast 而不是 Snackbar
     *
     * 聊天页底部是输入框 + 键盘，Snackbar 会顶在输入框上方并遮挡最后
     * 几条消息；而且它需要宿主布局。Toast 是系统级、不占布局、
     * 与其它页面的失败提示（`viewModel.toast` 通路）行为一致。
     */
    val toastHost = LocalContext.current
    LaunchedEffect(state.error) {
        state.error?.let { msg ->
            android.widget.Toast
                .makeText(toastHost, msg, android.widget.Toast.LENGTH_SHORT)
                .show()
            viewModel.consumeError()
        }
    }

    /**
     * 自动发送分享内容的结果（v1.6.7）。
     *
     * ## 为什么用 Toast 与上面同一条通路
     *
     * 分享是**自动**发生的（用户没有点发送按钮），所以他需要知道
     * 到底发出去没有 —— 静默成功会让人不确定，静默失败更糟
     * （以为发了、实际没发）。
     *
     * ⚠️ 失败时提示的是**真实原因**（来自 `userMessageFor`），
     *    绝不在失败时显示"已发送"。分享时链接已复制到剪贴板，
     *    用户仍可手动发出。
     */
    LaunchedEffect(state.shareResult) {
        state.shareResult?.let { msg ->
            android.widget.Toast
                .makeText(toastHost, msg, android.widget.Toast.LENGTH_SHORT)
                .show()
            viewModel.consumeShareResult()
        }
    }

    /**
     * 发送成功后清空输入框（v1.4.2）。
     *
     * ## 为什么用"监听 sending 落回"而不是在按钮里清
     *
     * 按钮里清 = 请求还没发出去就把用户的字删了，失败即丢失。
     * 这里改成监听事实：`sending` 从 true 落回 false 且**没有 error**，
     * 才认为这次发送成功，此时清空才安全。
     *
     * 失败时 `draft` 保持原样，用户可以直接再点发送（内容还在）。
     */
    var wasSending by remember { mutableStateOf(false) }
    LaunchedEffect(state.sending, state.error) {
        if (wasSending && !state.sending && state.error == null) {
            draft = ""
        }
        wasSending = state.sending
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPrimary),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不再是卡片：与页面同明度，靠底边一条发丝线分隔
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
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
                text = talkerName,
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            !state.loggedIn -> EmptyHint(
                text = "登录后可查看私信",
                actionLabel = "去登录",
                onAction = onLogin,
            )

            state.loading && state.messages.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.brand,
                    strokeWidth = V3Space.progressTrack,
                    modifier = Modifier.size(V3Size.iconLg),
                )
            }

            state.messages.isEmpty() -> EmptyHint(
                text = "打个招呼吧",
                actionLabel = null,
                onAction = {},
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = V3Space.md,
                    vertical = V3Space.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(V3Space.xs),
            ) {
                items(state.messages, key = { it.msgKey }) { m ->
                    MessageBubble(
                        message = m,
                        // 表情 token → 图片地址（未发版）。
                        // 从已加载的表情面板建映射；拿不到时返回 null
                        // → 气泡保持显示原始 token（不显示空框）。
                        emoteUrl = { token -> emoteUrlMap[token] },
                    )
                }
            }
        }

        // ---- 输入区（仅登录后显示）----
        if (state.loggedIn) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ---- 表情面板（未发版）----
                //
                // 放在输入条**上方**：展开时把输入条顶上去，
                // 而不是盖住它（盖住的话用户看不到自己正在写什么）。
                if (emoteOpen) {
                    EmotePickerPanel(
                        packages = emotePackages,
                        loading = emoteLoading,
                        error = emoteError,
                        onPick = { token ->
                            // 传的是**官方 token**（`[doge_金箍]`），不是表情名
                            viewModel.insertEmote(token)
                        },
                        onDismiss = { viewModel.closeEmotePanel() },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // ⚠️ 用 `band()` 而不是 `.background(colors.bgSecondary)`（v1.2.4 统一）。
                        // 输入条是全宽直角的分区带（与上方消息列表同宽），不是卡片。
                        .band(BandLevel.Raised)
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = V3Space.md, vertical = V3Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ---- 表情入口（未发版）----
                    //
                    // ⚠️ 只有 `emoteRepo` 注入时才渲染 —— 没注入时
                    //    按钮点了没反应是死入口（§1.6）。
                    if (viewModel.emoteAvailable) {
                        Box(
                            modifier = Modifier
                                .size(V3Size.iconLg + V3Space.xs)
                                .clip(CircleShape)
                                .clickable { viewModel.toggleEmotePanel() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.EmojiEmotions,
                                contentDescription = if (emoteOpen) "收起表情" else "表情",
                                tint = if (emoteOpen) colors.brand else colors.labelSecondary,
                                modifier = Modifier.size(V3Size.iconMd),
                            )
                        }
                        Spacer(Modifier.width(V3Space.xxs))
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(CHAT_INPUT_HEIGHT)
                            .clip(RoundedCornerShape(V3Radius.pill))
                            .background(colors.bgTertiary)
                            .padding(horizontal = V3Space.sm),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                text = "发消息…",
                                style = V3Type.callout.copy(
                                    color = colors.labelTertiary,
                                ),
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            textStyle = V3Type.callout.copy(
                                color = colors.labelPrimary,
                            ),
                            cursorBrush = SolidColor(colors.brand),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(V3Space.xs))
                // 发送按钮：发送中禁用 + 显示进度（v1.4.2 修）。
                //
                // 首版完全没有用 `state.sending` —— ViewModel 一直在维护它
                // （MessageViewModel 里 4 处赋值），UI 一次都没读过。
                // 结果是发送期间按钮仍可点、没有转圈，用户以为没发出去而重复点。
                val canSend = draft.isNotBlank() && !state.sending
                Box(
                    modifier = Modifier
                        .size(V3Size.iconLg + V3Space.xs)
                        .clip(CircleShape)
                        .background(
                            if (canSend) colors.brand else colors.bgTertiary,
                        )
                        .clickable(enabled = canSend) {
                            // ⚠️ 不再在这里清空 draft（v1.4.2 修）。
                            //
                            // 首版是 `viewModel.send(draft.trim()); draft = ""` ——
                            // 输入框在**请求发出前**就被清空了。发送失败时
                            // ViewModel 会把乐观插入的消息移除，而用户刚打的字
                            // 已经没了 → **内容彻底丢失**，只能重打一遍。
                            //
                            // 现在清空交给"发送成功"这个事实来决定：
                            // `LaunchedEffect(state.sending)` 在 sending 由
                            // true → false 且无 error 时才清空。
                            viewModel.send(draft.trim())
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.sending) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = colors.labelOnBrand,
                            strokeWidth = V3Space.progressTrack,
                            modifier = Modifier.size(V3Size.iconMd),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (canSend) colors.labelOnBrand else colors.labelTertiary,
                            modifier = Modifier.size(V3Size.iconMd),
                        )
                    }
                }
            }
            }   // end Column（表情面板 + 输入条）
        }
    }
}

/**
 * 消息气泡。
 *
 * 自己的靠右、品牌色底；对方的靠左、卡片色底 ——
 * 用**位置 + 颜色**双重区分，不只靠颜色（色弱可辨）。
 */
@Composable
private fun MessageBubble(
    message: PmMessage,
    /**
     * 表情 token → 图片地址（未发版）。
     *
     * ## 🔴 为什么气泡需要它
     *
     * 私信里的表情在协议上就是**文本 token**（`[OK]` / `[doge_金箍]`）。
     * 不做映射的话，发送与接收都会**显示成方括号文字** ——
     * 用户看到的是 `[OK]` 而不是那个表情图。
     *
     * 映射表来自 `x/emote/user/panel`（已加载的表情面板）。
     * **拿不到映射时保持原样显示 token** —— 宁可显示 `[OK]`
     * 也不要显示一个空框（那是更糟的"看起来坏了"）。
     */
    emoteUrl: (String) -> String? = { null },
) {
    val colors = BiliV3.colors
    // ⚠️ 气泡**不是玻璃/浮层**，所以不该借 `V3Radius.md`（§5.2 已限定它只服务
    // `biliCard`/`Glass` 两个原语）。它是一块有方向的**内容面**，走 `panel`(16dp)。
    //
    // "尾巴角"用 `badge`(4dp)：四角里最贴近说话人的那个收小，
    // 是消息气泡的通用语言（位置 + 形状双重表达归属）。
    val bubbleShape = RoundedCornerShape(
        topStart = V3Radius.lg,
        topEnd = V3Radius.lg,
        bottomStart = if (message.isMine) V3Radius.lg else V3Radius.xs,
        bottomEnd = if (message.isMine) V3Radius.xs else V3Radius.lg,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
    ) {
        // 🔴 v1.5.3：**图片消息单独渲染**（原实现只画文本 →
        // 图片消息的 text 为空 → 显示"[暂不支持的消息类型]"）。
        //
        // 图片气泡**不加内边距、不铺色底** —— 图片本身就是内容，
        // 再套一层色底会出现"图外面一圈粉边"，很脏。
        // 只保留圆角与最大宽度约束。
        if (message.isImage) {
            // 图片宽度固定为屏宽的 62%（留出与对方气泡对齐的空间），
            // 高度按原图比例算 —— 这样加载前就占好位，不会"图一出来布局跳一下"。
            //
            // ⚠️ 比例夹在 0.4~3.0：极端长图/宽图会把气泡拉成一条线或一堵墙。
            val maxW = (LocalConfiguration.current.screenWidthDp * 0.62f).dp
            val h = (maxW.value / message.imageAspect.coerceIn(0.4f, 3f)).dp
            AsyncImage(
                model = message.imageUrl,
                contentDescription = "图片消息",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(maxW)
                    .height(h)
                    .clip(bubbleShape)
                    .background(colors.bgTertiary),
            )
            return@Row
        }

        Box(
            modifier = Modifier
                // 气泡最宽占 78%：留出对比空间，避免"满屏都是自己的话"
                .fillMaxWidth(0.78f)
                .wrapContentWidth(if (message.isMine) Alignment.End else Alignment.Start)
                .clip(bubbleShape)
                .background(if (message.isMine) colors.brand else colors.bgSecondary)
                .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
        ) {
            // 纯表情消息（整条就是一个 token）→ 直接渲染大图，
            // 不套气泡底色（与图片消息同一处理：内容本身就是图）
            val onlyEmote = !message.isUnsupported &&
                message.text.isNotBlank() &&
                emoteUrl(message.text.trim()) != null

            if (onlyEmote) {
                AsyncImage(
                    model = emoteUrl(message.text.trim()),
                    contentDescription = message.text,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(EMOTE_BUBBLE)
                        .clip(RoundedCornerShape(V3Radius.xs)),
                )
            } else {
                Text(
                    // ⚠️ 未知类型要**说清是什么**，不是笼统的"不支持" ——
                    // 用户看到 type 号才知道该反馈什么（§11.1 的"明示"要求）。
                    text = if (message.isUnsupported) {
                        "[暂不支持的消息类型 ${message.msgType}]"
                    } else {
                        message.text
                    },
                    style = V3Type.callout.copy(
                        color = if (message.isMine) colors.labelOnBrand else colors.labelPrimary,
                    ),
                )
            }
        }
    }
}

/** 纯表情气泡里的表情尺寸。 */
private val EMOTE_BUBBLE = 92.dp

/** 空态 / 错误态提示。 */
@Composable
private fun EmptyHint(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val colors = BiliV3.colors
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.MailOutline,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(V3Size.iconLg * 2),
            )
            Spacer(Modifier.height(V3Space.sm))
            Text(
                text = text,
                style = V3Type.callout.copy(
                    color = colors.labelSecondary,
                ),
            )
            if (actionLabel != null) {
                Spacer(Modifier.height(V3Space.sm))
                Text(
                    text = actionLabel,
                    style = V3Type.caption1.copy(
                        fontWeight = FontWeight.Medium,
                        color = colors.labelOnBrand,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(V3Radius.pill))
                        .background(colors.brand)
                        .clickable(onClick = onAction)
                        .padding(horizontal = V3Space.lg, vertical = V3Space.xs),
                )
            }
        }
    }
}

/** 会话头像尺寸。 */
private val SESSION_AVATAR = 44.dp

/** 聊天输入框高度。 */
private val CHAT_INPUT_HEIGHT = 40.dp
