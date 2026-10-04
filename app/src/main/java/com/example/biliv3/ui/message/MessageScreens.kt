package com.example.biliv3.ui.message

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
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.band
import com.example.biliv3.design.BandLevel
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

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
    val colors = BiliTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不再是卡片：与页面同明度，靠底边一条发丝线分隔
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
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
                text = "消息",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
            if (state.unread > 0) {
                Spacer(Modifier.width(Space.x2))
                Text(
                    text = "${state.unread}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.textOnBrand,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.badge))
                        .background(colors.brandPrimary)
                        .padding(horizontal = Space.compactHorizontal, vertical = Space.tagVertical),
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
                    color = colors.brandPrimary,
                    strokeWidth = Space.trackHeight,
                    modifier = Modifier.size(Sizes.iconXl),
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
                contentPadding = PaddingValues(bottom = Space.x6),
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
    val colors = BiliTheme.colors
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
            .padding(horizontal = Space.x4, vertical = Space.x3),
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
        Spacer(Modifier.width(Space.x3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.talkerName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.lastMessage.isNotEmpty()) {
                Spacer(Modifier.height(Space.micro))
                Text(
                    text = session.lastMessage,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(Space.x2))

        Column(horizontalAlignment = Alignment.End) {
            if (session.lastTime > 0) {
                Text(
                    text = formatRelativeTime(session.lastTime),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.textTertiary,
                    ),
                )
            }
            // 未读红点：数字比圆点信息量大（能看出积压多少）
            if (session.unreadCount > 0) {
                Spacer(Modifier.height(Space.x1))
                Text(
                    text = if (session.unreadCount > 99) "99+" else "${session.unreadCount}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.textOnBrand,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.badge))
                        .background(colors.brandPrimary)
                        .padding(horizontal = Space.compactHorizontal, vertical = Space.tagVertical),
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
    val colors = BiliTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

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
            .background(colors.bgBase),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不再是卡片：与页面同明度，靠底边一条发丝线分隔
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
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
                text = talkerName,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
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
                    color = colors.brandPrimary,
                    strokeWidth = Space.trackHeight,
                    modifier = Modifier.size(Sizes.iconXl),
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
                    horizontal = Space.x4,
                    vertical = Space.x3,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.x2),
            ) {
                items(state.messages, key = { it.msgKey }) { m ->
                    MessageBubble(message = m)
                }
            }
        }

        // ---- 输入区（仅登录后显示）----
        if (state.loggedIn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // ⚠️ 用 `band()` 而不是 `.background(colors.bgCard)`（v1.2.4 统一）。
                    // 输入条是全宽直角的分区带（与上方消息列表同宽），不是卡片。
                    .band(BandLevel.Raised)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = Space.x4, vertical = Space.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(CHAT_INPUT_HEIGHT)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(colors.bgHover)
                        .padding(horizontal = Space.x3),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = "发消息…",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = FontSize.body,
                                color = colors.textTertiary,
                            ),
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.body,
                            color = colors.textPrimary,
                        ),
                        cursorBrush = SolidColor(colors.brandPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(Space.x2))
                // 发送按钮：发送中禁用 + 显示进度（v1.4.2 修）。
                //
                // 首版完全没有用 `state.sending` —— ViewModel 一直在维护它
                // （MessageViewModel 里 4 处赋值），UI 一次都没读过。
                // 结果是发送期间按钮仍可点、没有转圈，用户以为没发出去而重复点。
                val canSend = draft.isNotBlank() && !state.sending
                Box(
                    modifier = Modifier
                        .size(Sizes.iconXl + Space.x2)
                        .clip(CircleShape)
                        .background(
                            if (canSend) colors.brandPrimary else colors.bgHover,
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
                            color = colors.textOnBrand,
                            strokeWidth = Space.trackHeight,
                            modifier = Modifier.size(Sizes.iconLg),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (canSend) colors.textOnBrand else colors.textTertiary,
                            modifier = Modifier.size(Sizes.iconLg),
                        )
                    }
                }
            }
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
private fun MessageBubble(message: PmMessage) {
    val colors = BiliTheme.colors
    // ⚠️ 气泡**不是玻璃/浮层**，所以不该借 `Radius.card`（§5.2 已限定它只服务
    // `biliCard`/`Glass` 两个原语）。它是一块有方向的**内容面**，走 `panel`(16dp)。
    //
    // "尾巴角"用 `badge`(4dp)：四角里最贴近说话人的那个收小，
    // 是消息气泡的通用语言（位置 + 形状双重表达归属）。
    val bubbleShape = RoundedCornerShape(
        topStart = Radius.panel,
        topEnd = Radius.panel,
        bottomStart = if (message.isMine) Radius.panel else Radius.badge,
        bottomEnd = if (message.isMine) Radius.badge else Radius.panel,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                // 气泡最宽占 78%：留出对比空间，避免"满屏都是自己的话"
                .fillMaxWidth(0.78f)
                .wrapContentWidth(if (message.isMine) Alignment.End else Alignment.Start)
                .clip(bubbleShape)
                .background(if (message.isMine) colors.brandPrimary else colors.bgCard)
                .padding(horizontal = Space.x3, vertical = Space.x2),
        ) {
            Text(
                text = if (message.isUnsupported) "[暂不支持的消息类型]" else message.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    lineHeight = FontSize.bodyLine,
                    color = if (message.isMine) colors.textOnBrand else colors.textPrimary,
                ),
            )
        }
    }
}

/** 空态 / 错误态提示。 */
@Composable
private fun EmptyHint(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val colors = BiliTheme.colors
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.MailOutline,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(Sizes.iconXl * 2),
            )
            Spacer(Modifier.height(Space.x3))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textSecondarySafe,
                ),
            )
            if (actionLabel != null) {
                Spacer(Modifier.height(Space.x3))
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        fontWeight = FontWeight.Medium,
                        color = colors.textOnBrand,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(colors.brandPrimary)
                        .clickable(onClick = onAction)
                        .padding(horizontal = Space.x5, vertical = Space.x2),
                )
            }
        }
    }
}

/** 会话头像尺寸。 */
private val SESSION_AVATAR = 44.dp

/** 聊天输入框高度。 */
private val CHAT_INPUT_HEIGHT = 40.dp
