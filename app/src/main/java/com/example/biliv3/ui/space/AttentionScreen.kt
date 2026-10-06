package com.example.biliv3.ui.space

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.RemoveCircleOutline
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
import coil.compose.AsyncImage
import com.example.biliv3.data.AttendedUser
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.SectionMark
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.EmptyState

/**
 * 「特别关注」列表页。
 *
 * ## 🔴 页面上必须写清楚它**不是** B 站关注
 *
 * 需求原文：「必须明确：它不是 B 站真实关注」。
 *
 * 所以这一页顶部有一条**常驻说明**（不是可关闭的提示条）：
 * 关掉之后用户就再也看不到"这是本地的"这件事了。
 *
 * ## 为什么不做成"关注列表"的一个 Tab
 *
 * 项目**没有**关注列表页（B 站关注列表接口本项目未接入 ——
 * 见 `Endpoints` 里没有 `relation/followings`）。
 * 与其新造一个假的"关注列表"再把特别关注塞进去，
 * 不如给它一个**诚实的位置**：独立页面 + 明确说明。
 *
 * 这与需求「如果项目已有关注列表就优先复用」并不冲突 ——
 * **项目确实没有**，所以这里如实新建，而不是伪造一个混合列表。
 */
@Composable
fun AttentionScreen(
    users: List<AttendedUser>,
    onBack: () -> Unit,
    onOpenUser: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ---- 顶栏（与全站二级页同构）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
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
                text = "特别关注",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
        }

        // ---- 常驻说明：它不是 B 站关注 ----
        //
        // 用 `SectionMark` 同族的排版语言（左侧竖线 + 等宽小字），
        // 与空态/状态行一致 —— 不新造一种"提示条"样式。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Space.x4,
                    end = Space.x4,
                    top = Rhythm.between,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(14.dp)
                        .background(colors.accentTerminal),
                )
                Spacer(Modifier.width(Space.x2))
                Text(
                    text = "仅本机可见，不会关注对方的 B 站账号",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                )
            }
            Spacer(Modifier.height(Space.x2))
            Text(
                text = "「特别关注」是本应用的本地书签：加入后不会向 B 站发送任何关注请求，" +
                    "对方的粉丝数不会变化，你在 B 站网页端也看不到这条记录。",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    lineHeight = FontSize.bodySmLine,
                    color = colors.textTertiary,
                ),
            )
        }

        RuleLine(
            color = Rule.subtle,
            modifier = Modifier.padding(top = Rhythm.between),
        )

        if (users.isEmpty()) {
            EmptyState(
                title = "还没有特别关注的用户",
                description = "在用户主页点「特别关注」，就会出现在这里",
                icon = Icons.Outlined.BookmarkBorder,
                modifier = Modifier.fillMaxSize(),
                terminalStyle = true,
            )
            return
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = Space.x8),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "count") {
                SectionMark(
                    index = users.size,
                    title = "特别关注",
                    modifier = Modifier.padding(
                        start = Space.x4,
                        end = Space.x4,
                        top = Rhythm.between,
                    ),
                )
            }

            items(users, key = { it.mid }) { u ->
                AttendedRow(
                    user = u,
                    onOpen = { onOpenUser(u.mid) },
                    onRemove = { onRemove(u.mid) },
                )
            }
        }
    }
}

/**
 * 一行特别关注。
 *
 * ## 标识怎么表达（不只靠颜色）
 *
 * 三重表达，色弱用户也能分辨：
 * 1. **书签图标**（`BookmarkBorder`）—— 与"关注"的心形/加号完全不同
 * 2. **文字标签**「特别关注」—— 直接写出来，不靠用户猜
 * 3. **描边胶囊**—— 与真实关注的实心按钮形状不同
 */
@Composable
private fun AttendedRow(
    user: AttendedUser,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = BiliTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Space.x4, vertical = Space.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = user.faceUrl(96),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(Sizes.upAvatar + Space.x8)
                .clip(CircleShape)
                .background(colors.avatarPlaceholder),
        )

        Spacer(Modifier.width(Space.x3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.name.ifEmpty { "UID ${user.mid}" },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.micro))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.x2),
            ) {
                // 标识①：图标
                Icon(
                    imageVector = Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    tint = colors.accentTerminal,
                    modifier = Modifier.size(Sizes.iconSm),
                )
                // 标识②：文字标签 + 描边胶囊（标识③：形状）
                Text(
                    text = "特别关注",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        fontWeight = FontWeight.Medium,
                        color = colors.accentTerminal,
                    ),
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.badge))
                        .background(colors.accentTerminalDim)
                        .padding(
                            horizontal = Space.tagHorizontal,
                            vertical = Space.tagVertical,
                        ),
                )
                Text(
                    text = "UID ${user.mid}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.textTertiary,
                    ),
                    maxLines = 1,
                )
            }
        }

        // ---- 移除（这是本地操作，不涉及任何网络）----
        Box(
            modifier = Modifier
                .size(Space.minTouchTarget)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.RemoveCircleOutline,
                contentDescription = "取消特别关注",
                tint = colors.textSecondarySafe,
                modifier = Modifier.size(Sizes.iconLg),
            )
        }
    }
}
