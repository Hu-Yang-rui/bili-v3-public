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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.biliv3.data.live.LiveRole
import com.example.biliv3.data.model.CoverUrls
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.SectionMark
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.TerminalLoadingState

/**
 * 「查看弹幕发送者」浮层（v1.6.5）。
 *
 * ---
 *
 * # 🔴 两级信息来源（这是本浮层的核心设计）
 *
 * | 信息 | 来源 | 可靠性 |
 * |---|---|---|
 * | 用户名 / 头像 / 粉丝牌 / 大航海 / 用户等级 | **弹幕消息本身** | 确定（不需要请求） |
 * | 粉丝数 / 签名 / 账号等级 | `x/web-interface/card` 接口 | 需请求，可能失败 |
 *
 * 需求原文：
 * > 不要为了这个功能重复请求已经存在的数据。
 *
 * 所以第一级**直接用消息里的值**，只有第二级才发请求。
 *
 * ## 🔴 失败时**不显示虚假资料**
 *
 * 需求原文：
 * > 用户信息查询失败时显示明确错误，不要显示虚假的用户资料。
 *
 * 失败时：
 * - **保留**第一级信息（它是确定的，不因接口失败而失真）
 * - 第二级区域显示**错误 + 重试**，而不是"粉丝 0 / 等级 0"
 *   （那些数字看起来像真的，会误导）
 *
 * ## 🔴 接口没有的项**直接隐藏**
 *
 * 需求原文：
 * > 如果 API 无法提供某项信息，就隐藏该项，不要自行猜测。
 *
 * 例如 `SpaceProfile` 没有"账号等级"字段 → 不显示该项
 * （而不是显示 0 或"未知"）。
 */
@Composable
fun LiveSenderSheet(
    state: LiveRoomViewModel.SenderProfileState,
    /** 该用户是否已知是房管（从弹幕被动收集）。 */
    isKnownAdmin: Boolean,
    /** 是否是当前登录用户自己。 */
    isSelf: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    onCopyName: (String) -> Unit,
    /** 把该用户的名字 @ 进输入框（如果项目支持）。 */
    onMention: (String) -> Unit,
    onModerate: (LiveMessage) -> Unit,
    /** 是否有管理权限（决定是否显示"管理"入口）。 */
    canModerate: Boolean,
) {
    val colors = BiliTheme.colors
    val base = state.base

    // 身份：主播 > 已知房管 > 普通（与聊天区同一判定）
    val role = when {
        base.role == LiveRole.ANCHOR -> LiveRole.ANCHOR
        isKnownAdmin -> LiveRole.ADMIN
        else -> LiveRole.NORMAL
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
                    .background(colors.surfaceElevated)
                    .clickable(enabled = false) {}
                    // v1.6.6：底部弹层补导航栏避让（否则最后一行被手势条压住）
                    .navigationBarsPadding()
                    .heightIn(max = SHEET_MAX_H)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Space.x3),
            ) {
                // ---- 标题栏 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4, vertical = Space.x3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "弹幕发送者",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = FontSize.titleMd,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(Sizes.iconXl + Space.x2)
                            .clip(RoundedCornerShape(Radius.interactive))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = colors.textSecondarySafe,
                            modifier = Modifier.size(Sizes.iconLg),
                        )
                    }
                }

                // ---- 头像 + 用户名 + 身份（第一级：消息自带）----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = CoverUrls.normalize(base.face),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(Sizes.upAvatar + Space.x8)
                            .clip(CircleShape)
                            .background(colors.avatarPlaceholder),
                    )
                    Spacer(Modifier.width(Space.x3))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = base.uname.ifEmpty { "用户${base.uid}" },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = FontSize.body,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isSelf) {
                                Spacer(Modifier.width(Space.x1))
                                Tag(text = "我", tint = colors.textBrandSafe)
                            }
                            if (role != LiveRole.NORMAL) {
                                Spacer(Modifier.width(Space.x1))
                                OutlineTag(text = role.label, tint = roleTint(role))
                            }
                        }
                        Spacer(Modifier.height(Space.micro))
                        Text(
                            text = "UID ${base.uid}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.badge,
                                color = colors.textTertiary,
                            ),
                        )
                    }
                }

                // ---- 弹幕消息带来的信息（**确定**，无请求）----
                val fromMessage = buildList {
                    if (base.medalName.isNotEmpty() && base.medalLevel > 0) {
                        add("粉丝牌" to "${base.medalName} ${base.medalLevel}")
                    }
                    if (base.guardLevel > 0) {
                        add("大航海" to guardLabel(base.guardLevel))
                    }
                    if (base.userLevel > 0) {
                        add("用户等级" to "Lv${base.userLevel}")
                    }
                }
                if (fromMessage.isNotEmpty()) {
                    SectionMark(
                        index = 1,
                        title = "弹幕信息",
                        modifier = Modifier.padding(top = Rhythm.between),
                    )
                    Spacer(Modifier.height(Space.x2))
                    fromMessage.forEach { (k, v) -> InfoLine(label = k, value = v) }
                }

                // ---- 接口补充信息（第二级）----
                SectionMark(
                    index = if (fromMessage.isEmpty()) 1 else 2,
                    title = "账号资料",
                    modifier = Modifier.padding(top = Rhythm.between),
                )
                Spacer(Modifier.height(Space.x2))

                when (state) {
                    is LiveRoomViewModel.SenderProfileState.Loading ->
                        TerminalLoadingState(
                            text = "正在查询…",
                            modifier = Modifier.fillMaxWidth(),
                        )

                    is LiveRoomViewModel.SenderProfileState.Failed -> {
                        // 🔴 明确错误 + 重试；**不显示编造的 0**
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = FontSize.bodySm,
                                lineHeight = FontSize.bodySmLine,
                                color = colors.textSecondarySafe,
                            ),
                            modifier = Modifier.padding(horizontal = Space.x4),
                        )
                        Spacer(Modifier.height(Space.x2))
                        Text(
                            text = "重试",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.label,
                                fontWeight = FontWeight.Medium,
                                color = colors.textBrandSafe,
                            ),
                            modifier = Modifier
                                .padding(horizontal = Space.x4)
                                .clip(RoundedCornerShape(Radius.interactive))
                                .clickable(onClick = onRetry)
                                .padding(horizontal = Space.x3, vertical = Space.x1),
                        )
                    }

                    is LiveRoomViewModel.SenderProfileState.Loaded -> {
                        val p = state.profile
                        // ⚠️ 只显示接口**确实给了**的字段。
                        //    SpaceProfile 没有的项（如账号等级）直接不显示。
                        InfoLine(label = "昵称", value = p.name.ifEmpty { "—" })
                        InfoLine(label = "粉丝数", value = formatCount(p.fans))
                        InfoLine(label = "关注数", value = formatCount(p.following))
                        if (p.level > 0) {
                            InfoLine(label = "等级", value = "Lv${p.level}")
                        }
                        if (p.sign.isNotBlank()) {
                            InfoLine(label = "签名", value = p.sign)
                        }
                    }
                }

                RuleLine(
                    color = Rule.subtle,
                    modifier = Modifier.padding(top = Rhythm.between),
                )

                // ---- 动作 ----
                MenuLine(
                    label = "进入个人主页",
                    enabled = base.uid > 0L,
                    onClick = { onOpenProfile(base.uid) },
                )
                MenuLine(
                    label = "复制用户名",
                    enabled = base.uname.isNotEmpty(),
                    onClick = { onCopyName(base.uname) },
                )
                MenuLine(
                    label = "填入输入框",
                    enabled = base.uname.isNotEmpty(),
                    onClick = { onMention(base.uname) },
                )
                // 管理入口：只有有权限时才出现（复用现有菜单，不重复实现）
                if (canModerate && base.hasUser) {
                    MenuLine(
                        label = "管理该用户…",
                        enabled = true,
                        onClick = { onModerate(base) },
                    )
                }

                Spacer(Modifier.height(Space.x2))
                MenuLine(label = "关闭", enabled = true, onClick = onDismiss, center = true)
            }
        }
    }
}

/**
 * 身份徽章色（与聊天区共用同一套令牌）。
 *
 * ⚠️ 必须标 `@Composable` —— 它读 `BiliTheme.colors`（Composable 作用域）。
 * 顶层普通函数读不到主题，这是本项目 `SHEET_SCRIM` 那条坑的同类问题
 * （见 `PlayerSettingsSheet` 的说明）。
 */
@Composable
private fun roleTint(role: LiveRole) = when (role) {
    LiveRole.ANCHOR -> BiliTheme.colors.brandPrimary
    LiveRole.ADMIN -> BiliTheme.colors.accentTerminal
    LiveRole.NORMAL -> BiliTheme.colors.textTertiary
}

/** 大航海等级 → 文案（1=总督 2=提督 3=舰长，实测值）。 */
private fun guardLabel(level: Int): String = when (level) {
    1 -> "总督"
    2 -> "提督"
    3 -> "舰长"
    else -> "大航海 $level"
}

/** 标签（填充底）。 */
@Composable
private fun Tag(text: String, tint: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.badge,
            fontWeight = FontWeight.Medium,
            color = tint,
        ),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.badge))
            .background(BiliTheme.colors.bgHover)
            .padding(horizontal = Space.tagHorizontal, vertical = Space.tagVertical),
    )
}

/** 标签（描边）。 */
@Composable
private fun OutlineTag(text: String, tint: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.badge,
            fontWeight = FontWeight.Medium,
            color = tint,
        ),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.badge))
            .border(1.dp, tint, RoundedCornerShape(Radius.badge))
            .padding(horizontal = Space.tagHorizontal),
    )
}

/** 信息行：左标签右值（与设置页的 InfoRow 同构）。 */
@Composable
private fun InfoLine(label: String, value: String) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x4, vertical = Space.x1),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.badge,
                color = colors.textTertiary,
            ),
            modifier = Modifier.width(64.dp),
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = FontSize.bodySm,
                lineHeight = FontSize.bodySmLine,
                color = colors.textSecondarySafe,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

/** 菜单行（与 LiveUserMenu 的 MenuRow 同构）。 */
@Composable
private fun MenuLine(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    center: Boolean = false,
) {
    val colors = BiliTheme.colors
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
                color = if (enabled) colors.textPrimary else colors.textTertiary,
            ),
            maxLines = 1,
        )
    }
}

private val SHEET_MAX_H = 520.dp
