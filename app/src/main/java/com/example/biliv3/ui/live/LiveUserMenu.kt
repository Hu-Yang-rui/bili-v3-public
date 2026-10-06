package com.example.biliv3.ui.live

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.biliv3.data.live.LiveMessage
import com.example.biliv3.data.live.LivePermissions
import com.example.biliv3.data.model.CoverUrls
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.ruleTop
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 直播间用户操作菜单（v1.6.4）。
 *
 * ---
 *
 * # 🔴 菜单项**完全由权限决定**
 *
 * 需求原文：
 * > 不能把所有按钮全部显示给所有用户。权限必须由实际账号身份决定。
 *
 * 所以菜单项是**算出来的**，不是写死的列表：
 *
 * ```
 * 普通用户   → 查看主页 / 复制用户名
 * 房管       → 上面 + 禁言 / 踢出 / 加黑名单
 * 主播       → 上面 + （房管管理：接口未找到，不显示）
 * 未登录     → 只有 查看主页 / 复制用户名
 * ```
 *
 * ## 为什么不显示"灰掉的"管理项
 *
 * 灰掉的按钮会让人以为"再满足某个条件就能用" —— 而普通用户
 * 永远不可能满足。**直接不显示**才是诚实的（§1.6 死入口的同类问题）。
 *
 * ## 为什么用 Dialog 而不是行内浮层
 *
 * 与 `PlayerSettingsSheet` 同一理由：Dialog 自带独立 window 与
 * `OnBackPressedDispatcher`，系统返回手势**优先交给最上层 Dialog**，
 * 由 `onDismissRequest` 消费 —— 否则返回会穿透到 NavHost 把整页弹掉。
 *
 * ## UI 风格
 *
 * 完全复用现有语言：`Dialog` + `surfaceElevated` + `Radius.panel` +
 * 发丝线分隔 + **无卡片**（每一项是一行，不是一个小卡片）。
 */
@Composable
fun LiveUserMenu(
    target: LiveMessage,
    permissions: LivePermissions,
    isKnownAdmin: Boolean,
    /** 正在执行的管理操作名；非空时禁用全部管理项（防连点）。 */
    moderating: String?,
    onDismiss: () -> Unit,
    onAction: (LivePermissions.Action) -> Unit,
    onOpenProfile: (Long) -> Unit,
    onCopyName: (String) -> Unit,
    /** 查看发送者资料（v1.6.5）。 */
    onViewSender: (LiveMessage) -> Unit = {},
    /** 复制弹幕内容（v1.6.5）。 */
    onCopyText: (String) -> Unit = {},
    /** 把文本填入输入框（v1.6.5）。 */
    onFillInput: (String) -> Unit = {},
    /** 打开禁言时长选择。 */
    onRequestMute: () -> Unit,
) {
    val colors = BiliTheme.colors

    // 这个菜单能对目标做什么（数据层判定，UI 只消费）
    val available = remember(target.uid, permissions) {
        LivePermissions.Action.entries.filter { permissions.canActOn(target.uid, it) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
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
                    // 弹层用 surfaceElevated（比卡片亮一档）——
                    // 深色下投影不可见，分层只能靠提亮
                    .background(colors.surfaceElevated)
                    .clickable(enabled = false) {}
                    .heightIn(max = MENU_MAX_H)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Space.x3),
            ) {
                // ---- 头部：头像 + 用户名 + 身份 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4, vertical = Space.x3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = CoverUrls.normalize(target.face),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(Sizes.upAvatar + Space.x6)
                            .clip(CircleShape)
                            .background(colors.avatarPlaceholder),
                    )
                    Spacer(Modifier.width(Space.x3))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = target.uname.ifEmpty { "用户${target.uid}" },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = FontSize.body,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Space.micro))
                        Text(
                            text = buildString {
                                append("UID ").append(target.uid)
                                if (target.medalName.isNotEmpty() && target.medalLevel > 0) {
                                    append(" · ").append(target.medalName)
                                    append(' ').append(target.medalLevel)
                                }
                                if (isKnownAdmin) append(" · 房管")
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.badge,
                                color = colors.textTertiary,
                            ),
                            maxLines = 1,
                        )
                    }
                }

                RuleLine(color = Rule.subtle)

                // ---- 通用项（所有人可见）----
                MenuRow(
                    label = "查看发送者",
                    enabled = target.uid > 0L,
                    onClick = { onViewSender(target) },
                )
                MenuRow(
                    label = "进入个人主页",
                    enabled = target.uid > 0L,
                    onClick = { onOpenProfile(target.uid) },
                )
                MenuRow(
                    label = "复制弹幕内容",
                    enabled = target.text.isNotEmpty(),
                    onClick = { onCopyText(target.text) },
                )
                MenuRow(
                    label = "复制用户名",
                    enabled = target.uname.isNotEmpty(),
                    onClick = { onCopyName(target.uname) },
                )
                MenuRow(
                    label = "填入输入框",
                    enabled = target.uname.isNotEmpty(),
                    onClick = { onFillInput("${target.uname} ") },
                )

                // ---- 管理项（**只有有权限时才渲染**）----
                if (available.isNotEmpty()) {
                    RuleLine(color = Rule.subtle)
                    Text(
                        text = "管理操作",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            color = colors.textTertiary,
                        ),
                        modifier = Modifier.padding(
                            start = Space.x4,
                            end = Space.x4,
                            top = Space.x2,
                            bottom = Space.compactVertical,
                        ),
                    )

                    // 固定顺序：先"解除"后"施加"会让用户困惑，
                    // 所以按"动作强度"递增排列
                    val ordered = listOf(
                        LivePermissions.Action.UNMUTE,
                        LivePermissions.Action.UNBLOCK,
                        LivePermissions.Action.MUTE,
                        LivePermissions.Action.BLOCK,
                        LivePermissions.Action.KICK,
                    ).filter { it in available }

                    ordered.forEach { action ->
                        val label = LiveRoomViewModel.actionLabel(action)
                        val dangerous = action == LivePermissions.Action.KICK ||
                            action == LivePermissions.Action.BLOCK
                        MenuRow(
                            label = label,
                            // 有操作在途时禁用全部 —— 防连点（需求第 11 条）
                            enabled = moderating == null,
                            dangerous = dangerous,
                            onClick = {
                                if (action == LivePermissions.Action.MUTE) {
                                    // 禁言要先选时长
                                    onRequestMute()
                                } else {
                                    onAction(action)
                                }
                            },
                        )
                    }
                }

                // ---- 无权限时的说明（**如实告知，不显示灰按钮**）----
                if (available.isEmpty() && target.uid > 0L) {
                    RuleLine(color = Rule.subtle)
                    Text(
                        text = if (!permissions.loggedIn) {
                            "登录后，如果你是主播或房管，可以在这里管理用户"
                        } else {
                            "只有主播和房管可以管理直播间用户"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            lineHeight = FontSize.labelLine,
                            color = colors.textTertiary,
                        ),
                        modifier = Modifier.padding(
                            start = Space.x4,
                            end = Space.x4,
                            top = Space.x2,
                        ),
                    )
                }

                Spacer(Modifier.height(Space.x2))
                MenuRow(label = "取消", enabled = true, onClick = onDismiss, center = true)
            }
        }
    }
}

/**
 * 禁言时长选择（v1.6.4）。
 *
 * ## ⚠️ 时长范围是**未验证**的
 *
 * 本项目**无法在未登录状态探测**服务端接受的时长范围
 * （需要主播/房管账号）。所以：
 * - 只给**常用档位**（1/5/10/30 分钟、1 小时）
 * - 额外给一个"自定义"输入框，让用户自己填
 * - **不写**"支持 1 分钟到 N 天"这类断言
 *
 * 服务端越界会返回错误码，UI 如实显示 —— 这是诚实做法，
 * 比编一个"范围"然后在用户填了合法值时反而被拒要好。
 */
@Composable
fun LiveMuteDurationDialog(
    targetName: String,
    /** 是否有操作在途（禁用确认）。 */
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int) -> Unit,
) {
    val colors = BiliTheme.colors
    var custom by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.panel))
                .background(colors.surfaceElevated)
                .padding(Space.x4),
        ) {
            Text(
                text = "禁言 ${targetName.ifEmpty { "该用户" }}",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
            Spacer(Modifier.height(Space.x3))

            // ---- 常用档位 ----
            val presets = listOf(
                1 to "1 分钟",
                5 to "5 分钟",
                10 to "10 分钟",
                30 to "30 分钟",
                60 to "1 小时",
            )
            presets.chunked(3).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.x2),
                    modifier = Modifier.padding(bottom = Space.x2),
                ) {
                    row.forEach { (min, label) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.label,
                                color = colors.textPrimary,
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(colors.bgHover)
                                .clickable(enabled = !busy) { onConfirm(min) }
                                .padding(
                                    horizontal = Space.x3,
                                    vertical = Space.compactHorizontal,
                                ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(Space.x2))

            // ---- 自定义 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radius.interactive))
                        .background(colors.bgHover)
                        .padding(horizontal = Space.x3, vertical = Space.x2),
                ) {
                    if (custom.isEmpty()) {
                        Text(
                            text = "自定义分钟数",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = FontSize.bodySm,
                                color = colors.textTertiary,
                            ),
                        )
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = custom,
                        onValueChange = { custom = it.filter { ch -> ch.isDigit() }.take(5) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.textPrimary,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.brandPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(Space.x2))
                val minutes = custom.toIntOrNull()
                Text(
                    text = "确定",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        fontWeight = FontWeight.Medium,
                        // 输入非法 / 有操作在途 → 视觉上也明确不可用
                        color = if (minutes != null && minutes > 0 && !busy) {
                            colors.brandPrimary
                        } else {
                            colors.textTertiary
                        },
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.interactive))
                        .clickable(enabled = minutes != null && minutes > 0 && !busy) {
                            onConfirm(minutes!!)
                        }
                        .padding(horizontal = Space.x3, vertical = Space.x2),
                )
            }

            Spacer(Modifier.height(Space.x2))
            Text(
                text = "可选的时长范围由 B 站服务端决定，超出范围会返回错误。",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    lineHeight = FontSize.labelLine,
                    color = colors.textTertiary,
                ),
            )

            Spacer(Modifier.height(Space.x3))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.interactive))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = Space.x3, vertical = Space.x2),
                )
            }
        }
    }
}

/**
 * 高风险操作的二次确认（需求第 3 条）。
 *
 * > 执行前必须确认。不能点击一次就直接执行高风险操作。
 */
@Composable
fun LiveConfirmDialog(
    actionLabel: String,
    targetName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = BiliTheme.colors

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.panel))
                .background(colors.surfaceElevated)
                .padding(Space.x4),
        ) {
            Text(
                text = "确定要${actionLabel}吗？",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
            Spacer(Modifier.height(Space.x2))
            Text(
                text = "将对「${targetName.ifEmpty { "该用户" }}」执行「$actionLabel」。",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    lineHeight = FontSize.bodySmLine,
                    color = colors.textSecondarySafe,
                ),
            )
            Spacer(Modifier.height(Space.x4))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.interactive))
                        .clickable(enabled = !busy, onClick = onDismiss)
                        .padding(horizontal = Space.x3, vertical = Space.x2),
                )
                Spacer(Modifier.width(Space.x2))
                Text(
                    text = "确定",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        fontWeight = FontWeight.Medium,
                        // 危险操作用 stateError —— 与"取消"形成明确对比
                        color = if (busy) colors.textTertiary else colors.stateError,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.interactive))
                        .clickable(enabled = !busy, onClick = onConfirm)
                        .padding(horizontal = Space.x3, vertical = Space.x2),
                )
            }
        }
    }
}

/**
 * 菜单里的一行。
 *
 * ## 尺寸
 *
 * `heightIn(min = Space.minTouchTarget)` —— 48dp 最小触摸目标。
 * 菜单项是最容易做成"点不中"的地方（§11 明确要求避免点击区域太小）。
 */
@Composable
private fun MenuRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    dangerous: Boolean = false,
    center: Boolean = false,
) {
    val colors = BiliTheme.colors
    val tint = when {
        !enabled -> colors.textTertiary
        dangerous -> colors.stateError
        else -> colors.textPrimary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Space.minTouchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.x4),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                color = tint,
            ),
            maxLines = 1,
        )
    }
}

/** 菜单高度上限（避免长菜单顶出屏幕）。 */
private val MENU_MAX_H = 460.dp
