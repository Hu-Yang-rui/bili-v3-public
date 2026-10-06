package com.example.biliv3.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.biliv3.data.live.LiveDanmakuClient
import com.example.biliv3.data.live.LiveMessage
import com.example.biliv3.data.live.LiveRole
import com.example.biliv3.data.model.CoverUrls
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import kotlinx.coroutines.launch

/**
 * 直播间聊天区（v1.6.4）。
 *
 * ---
 *
 * # 滚动策略（需求原文）
 *
 * > 自动跟随最新消息；用户手动查看历史消息时**暂时停止**自动滚动；
 * > 回到底部后**恢复**自动滚动。
 *
 * ## 怎么判断"用户在看历史"
 *
 * 用 `derivedStateOf` 算"最后一项是否可见"，而不是维护一个
 * "用户是否手动滚动过"的布尔：
 *
 * - 布尔标志会在"用户往回滑一点又滑回来"时**忘记恢复**（没有可靠的恢复时机）
 * - "最后一项可见吗"是一个**可以直接算出来的事实**，不需要记状态
 *
 * ⚠️ 用 `derivedStateOf` 包一层：`layoutInfo` 每次滚动都会变，
 * 直接在组合里读会让**整棵列表每次滚动都重组**（本项目在
 * `DanmakuLayer` 上踩过同类性能坑）。
 *
 * ## 为什么"回到底部"要有一个按钮
 *
 * 用户往回翻了几百条之后，靠手动滑回底部很痛苦。
 * 按钮是**唯一的**恢复路径 —— 不能只靠"滑到底部自动恢复"。
 */
@Composable
fun LiveChatPanel(
    messages: List<LiveMessage>,
    connState: LiveDanmakuClient.ConnectionState,
    /** 当前登录用户是否可以对某人操作（决定能不能点开菜单）。 */
    canModerate: Boolean,
    isSelf: (Long) -> Boolean,
    isKnownAdmin: (Long) -> Boolean,
    onUserClick: (LiveMessage) -> Unit,
    onRetryChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    val listState = rememberLazyListState()

    // 最后一项是否可见 = "是否在底部"。可算，不用记。
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf true
            last.index >= messages.lastIndex
        }
    }

    // 新消息到达且用户在底部 → 自动跟随
    LaunchedEffect(messages.size, atBottom) {
        if (messages.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 连接状态条（只在异常时出现）----
            ConnectionBanner(connState = connState, onRetry = onRetryChat)

            if (messages.isEmpty()) {
                // 空态：区分"还没连上"与"连上了但没人说话"
                //
                // 这两者长得一样（都是空的），但**原因完全不同** ——
                // 不区分的话用户会以为功能坏了。
                Text(
                    text = when (connState) {
                        LiveDanmakuClient.ConnectionState.CONNECTED -> "还没有人说话"
                        LiveDanmakuClient.ConnectionState.CONNECTING -> "正在连接聊天…"
                        LiveDanmakuClient.ConnectionState.RECONNECTING -> "连接断开，正在重连…"
                        LiveDanmakuClient.ConnectionState.FAILED -> "聊天连接失败"
                        LiveDanmakuClient.ConnectionState.IDLE -> "聊天未连接"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = FontSize.bodySm,
                        color = colors.textTertiary,
                    ),
                    modifier = Modifier.padding(Space.x3),
                )
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // 顶部淡出，暗示"上面还有"
                    .padding(horizontal = Space.x2),
            ) {
                items(
                    count = messages.size,
                    // ⚠️ key 必须**稳定且唯一** —— 用序号而不是内容：
                    //    同一个人连发两条一样的弹幕时，内容相同会撞 key，
                    //    LazyColumn 会把第二条当成第一条（表现为"少一条"）。
                    key = { i -> messages[i].stableKey(i.toLong()) },
                ) { i ->
                    val m = messages[i]
                    LiveChatRow(
                        msg = m,
                        self = isSelf(m.uid),
                        knownAdmin = isKnownAdmin(m.uid),
                        clickable = canModerate && m.hasUser,
                        onClick = { onUserClick(m) },
                    )
                }
            }
        }

        // ---- "回到底部"按钮 ----
        //
        // 只在**不在底部**时出现。
        //
        // ⚠️ 这里用条件渲染而不是 `AnimatedVisibility(visible = false)`：
        //    后者会把组件**移出组合**（§7.12-66 的坑）。而"在底部时不存在"
        //    是刻意的 —— 那时它本来就不该占位。
        if (!atBottom) {
            val scope = rememberCoroutineScope()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Space.x3)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.surfaceElevated)
                    // ⚠️ 必须有真实动作。只显示不给行为就是死入口（§1.6）。
                    .clickable {
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(messages.lastIndex)
                            }
                        }
                    }
                    .padding(horizontal = Space.x3, vertical = Space.compactHorizontal),
            ) {
                Text(
                    text = "回到最新",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.textSecondarySafe,
                    ),
                )
            }
        }
    }
}

/**
 * 连接状态提示条。
 *
 * ## 只在**异常**时出现
 *
 * 已连接时不显示任何东西 —— 一个常驻的"已连接"标签是纯噪音
 * （§1.1：没有信息量的产出不做）。
 */
@Composable
private fun ConnectionBanner(
    connState: LiveDanmakuClient.ConnectionState,
    onRetry: () -> Unit,
) {
    val colors = BiliTheme.colors

    val (text, showRetry) = when (connState) {
        LiveDanmakuClient.ConnectionState.CONNECTED -> return
        LiveDanmakuClient.ConnectionState.IDLE -> return
        LiveDanmakuClient.ConnectionState.CONNECTING -> "正在连接聊天…" to false
        LiveDanmakuClient.ConnectionState.RECONNECTING -> "连接断开，正在重连…" to false
        LiveDanmakuClient.ConnectionState.FAILED -> "聊天连接失败" to true
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgHover)
            .padding(horizontal = Space.x3, vertical = Space.compactVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.badge,
                color = if (connState == LiveDanmakuClient.ConnectionState.FAILED) {
                    colors.stateError
                } else {
                    colors.textSecondarySafe
                },
            ),
            modifier = Modifier.weight(1f),
        )
        if (showRetry) {
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    fontWeight = FontWeight.Medium,
                    color = colors.brandPrimary,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.badge))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = Space.x2, vertical = Space.tagVertical),
            )
        }
    }
}

/**
 * 一条聊天消息。
 *
 * ## 三种形态
 *
 * | 类型 | 渲染 |
 * |---|---|
 * | 系统消息 | 居中细字（连接状态、操作结果） |
 * | 进入 / 关注 / 点赞 | 无头像，`用户名 来了` 这类一行 |
 * | 弹幕 | 头像 + 徽章 + 用户名 + 正文 |
 *
 * ## 身份徽章（需求第 2 条）
 *
 * 主播 / 房管 用**描边胶囊**，与用户名同行。
 * 「自己」用**整行底色**区分（不占徽章位）——
 * 因为它与"主播/房管"是**不同维度**的（自己也可能是房管）。
 *
 * ⚠️ 不只用颜色：徽章有**文字**，色弱用户也能分辨。
 */
@Composable
private fun LiveChatRow(
    msg: LiveMessage,
    self: Boolean,
    knownAdmin: Boolean,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors

    // ---- 系统消息：居中细字 ----
    if (msg.kind == LiveMessage.Kind.SYSTEM) {
        Text(
            text = msg.text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.badge,
                color = colors.textTertiary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Space.micro, horizontal = Space.x1),
        )
        return
    }

    // ---- 身份：主播 > 已知房管 > 普通 ----
    //
    // ⚠️ 房管徽章用 `knownAdmin`（从弹幕里被动收集的），
    //    不是消息自带的 role —— 因为进入消息不带 admin 标记。
    val role = when {
        msg.role == LiveRole.ANCHOR -> LiveRole.ANCHOR
        knownAdmin -> LiveRole.ADMIN
        else -> LiveRole.NORMAL
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.badge))
            .then(
                if (self) {
                    Modifier.background(colors.brandPrimaryDim)
                } else {
                    Modifier
                },
            )
            .then(
                if (clickable) Modifier.clickable(onClick = onClick) else Modifier,
            )
            .padding(horizontal = Space.x1, vertical = Space.micro),
        verticalAlignment = Alignment.Top,
    ) {
        // ---- 头像（进入/关注类消息不显示头像，省空间）----
        if (msg.kind == LiveMessage.Kind.DANMAKU) {
            AsyncImage(
                model = CoverUrls.normalize(msg.face),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AVATAR)
                    .clip(CircleShape)
                    .background(colors.avatarPlaceholder),
            )
            Spacer(Modifier.width(Space.x1))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 粉丝牌（有才显示）
                if (msg.medalName.isNotEmpty() && msg.medalLevel > 0) {
                    MedalTag(name = msg.medalName, level = msg.medalLevel)
                    Spacer(Modifier.width(Space.micro))
                }

                // 身份徽章
                if (role != LiveRole.NORMAL) {
                    RoleTag(role = role)
                    Spacer(Modifier.width(Space.micro))
                }

                // 用户名
                Text(
                    text = msg.uname.ifEmpty { "用户${msg.uid}" },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        fontWeight = FontWeight.Medium,
                        // 自己用品牌色，与底色一起形成双重表达
                        color = if (self) colors.textBrandSafe else colors.textSecondarySafe,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (self) {
                    Spacer(Modifier.width(Space.micro))
                    Text(
                        text = "我",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            color = colors.textBrandSafe,
                        ),
                    )
                }
            }

            // ---- 正文 / 事件文案 ----
            Spacer(Modifier.height(1.dp))
            Text(
                text = when (msg.kind) {
                    LiveMessage.Kind.DANMAKU -> msg.text
                    LiveMessage.Kind.ENTER -> "进入了直播间"
                    LiveMessage.Kind.FOLLOW -> "关注了主播"
                    LiveMessage.Kind.LIKE -> msg.text.ifEmpty { "为主播点赞了" }
                    LiveMessage.Kind.ENTRY_EFFECT -> msg.text.ifEmpty { "来了" }
                    LiveMessage.Kind.GIFT -> msg.text
                    LiveMessage.Kind.SYSTEM -> msg.text
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    lineHeight = FontSize.bodySmLine,
                    color = if (msg.kind == LiveMessage.Kind.DANMAKU) {
                        colors.textPrimary
                    } else {
                        // 事件类消息弱化 —— 它们不是"内容"，是"状态"
                        colors.textTertiary
                    },
                ),
            )
        }
    }
}

/**
 * 身份徽章（主播 / 房管）。
 *
 * ## 颜色全部取自现有令牌
 *
 * - 主播 → `brandPrimary`（品牌粉，站内最高身份）
 * - 房管 → `accentTerminal`（终端青，与"系统标注"同族）
 *
 * 与 `LiveRole` 的 `label` 对应，**不新增色值**（§5.2）。
 */
@Composable
private fun RoleTag(role: LiveRole) {
    val colors = BiliTheme.colors
    val tint = when (role) {
        LiveRole.ANCHOR -> colors.brandPrimary
        LiveRole.ADMIN -> colors.accentTerminal
        LiveRole.NORMAL -> colors.textTertiary
    }
    if (role == LiveRole.NORMAL) return

    Text(
        text = role.label,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.badge,
            fontWeight = FontWeight.Medium,
            color = tint,
        ),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.badge))
            .border(1.dp, tint, RoundedCornerShape(Radius.badge))
            .padding(horizontal = Space.tagHorizontal, vertical = 0.dp),
    )
}

/** 粉丝牌：`牌子名 等级`。用中性色，不与身份徽章抢注意力。 */
@Composable
private fun MedalTag(name: String, level: Int) {
    val colors = BiliTheme.colors
    Text(
        text = "$name $level",
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.badge,
            color = colors.textTertiary,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.badge))
            .background(colors.bgHover)
            .padding(horizontal = Space.tagHorizontal, vertical = Space.tagVertical),
    )
}

/** 聊天头像尺寸。比视频评论的头像小 —— 聊天密度更高。 */
private val AVATAR = 18.dp
