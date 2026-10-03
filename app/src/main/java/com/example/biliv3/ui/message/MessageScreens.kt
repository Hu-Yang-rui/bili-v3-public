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
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
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
                .biliCard(
                    elevation = 0.dp,
                    shape = RoundedCornerShape(
                        bottomStart = Radius.card,
                        bottomEnd = Radius.card,
                    ),
                )
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
                        .padding(horizontal = Space.x1 + 2.dp, vertical = 1.dp),
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
                        .padding(horizontal = Space.x1 + 2.dp, vertical = 1.dp),
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

    // 错误用 snackbar 太重，这里直接清掉并靠 toast 通路提示
    LaunchedEffect(state.error) {
        state.error?.let { viewModel.consumeError() }
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
                .biliCard(
                    elevation = 0.dp,
                    shape = RoundedCornerShape(
                        bottomStart = Radius.card,
                        bottomEnd = Radius.card,
                    ),
                )
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
                    .background(colors.bgCard)
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
                Box(
                    modifier = Modifier
                        .size(Sizes.iconXl + Space.x2)
                        .clip(CircleShape)
                        .background(
                            if (draft.isNotBlank()) colors.brandPrimary else colors.bgHover,
                        )
                        .clickable(enabled = draft.isNotBlank()) {
                            viewModel.send(draft.trim())
                            draft = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (draft.isNotBlank()) colors.textOnBrand else colors.textTertiary,
                        modifier = Modifier.size(Sizes.iconLg),
                    )
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
    val bubbleShape = RoundedCornerShape(
        topStart = Radius.card,
        topEnd = Radius.card,
        bottomStart = if (message.isMine) Radius.card else Radius.badge,
        bottomEnd = if (message.isMine) Radius.badge else Radius.card,
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
