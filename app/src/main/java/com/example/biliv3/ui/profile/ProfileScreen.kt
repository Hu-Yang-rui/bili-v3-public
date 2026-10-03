package com.example.biliv3.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.biliv3.data.auth.UserInfo
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant

/**
 * 「我的」页。
 *
 * ## 两种形态
 *
 * - **未登录**：引导卡片 + 登录按钮（点进扫码页）
 * - **已登录**：头像/昵称/等级 + 功能入口（历史/收藏/稍后再看/设置）
 *
 * ## 为什么未登录也要有内容
 *
 * 不能因为没登录就显示空白页。用户点进"我的"是想知道"这里有什么"，
 * 引导登录 + 列出功能入口，比一片空白有信息量。
 */
@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    onLoginClick: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
    viewModel: ProfileViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = BiliTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        when (val s = state) {
            is ProfileUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.brandPrimary,
                    strokeWidth = Space.trackHeight,
                    modifier = Modifier.size(Sizes.iconXl),
                )
            }

            is ProfileUiState.Guest -> GuestPanel(
                onLoginClick = onLoginClick,
                onNavigate = onNavigate,
            )

            is ProfileUiState.LoggedIn -> LoggedInPanel(
                user = s.user,
                onLogout = viewModel::logout,
                onNavigate = onNavigate,
            )
        }
    }
}

/** 未登录：引导登录。 */
@Composable
private fun GuestPanel(
    onLoginClick: () -> Unit,
    onNavigate: (String) -> Unit = {},
) {
    val colors = BiliTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 引导卡片（C 方案：独立卡片）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.x3, vertical = Space.x1)
                .biliCard(shape = RoundedCornerShape(Radius.card))
                .padding(Space.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Sizes.upAvatar + Space.x8)
                    .clip(CircleShape)
                    .background(colors.bgHover),
            )
            Spacer(Modifier.width(Space.x3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "未登录",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = FontSize.titleMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                )
                Spacer(Modifier.height(Space.micro))
                Text(
                    text = "登录后可同步历史与收藏",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                )
            }
            Spacer(Modifier.width(Space.x3))
            BrandButton(
                label = "登录",
                onClick = onLoginClick,
                variant = BrandButtonVariant.Filled,
            )
        }

        // 需要登录的功能：**置灰展示**而不是隐藏。
        //
        // 让未登录用户知道"登录后能做什么"，是有意的产品选择。
        // route 传 null 且 enabled = false —— 视觉上就是禁用态，
        // 不存在"看着可点却没反应"的问题（这正是「设置」那条的 bug）。
        EntryGroup(
            entries = listOf(
                Entry("历史记录", Icons.Filled.History, null),
                Entry("我的收藏", Icons.Filled.Star, null),
                Entry("稍后再看", Icons.Outlined.Schedule, null),
                // ⚠️ 这里**刻意不放**「我的消息」。
                // 消息入口统一在首页右上角铃铛（那里还有未读红点），
                // 两处入口是重复的 —— 重复入口会让用户以为"两个地方不一样"。
            ),
            enabled = false,
        )

        Spacer(Modifier.height(Space.x3))

        // 离线缓存**不依赖登录态**（本地文件），所以未登录也要能进 ——
        // 与上面那组"需要登录"的入口分开是刻意的。
        //
        // 「查成分」也在这里：它走 aicu 第三方聚合，**不依赖 B 站登录态**，
        // 未登录也应该能用（aicu 自己维护数据，与我们的 Cookie 无关）。
        EntryGroup(
            entries = listOf(
                Entry("离线缓存", Icons.Outlined.DownloadDone, "downloads"),
                Entry("查成分", Icons.AutoMirrored.Outlined.ManageSearch, "aicu"),
                // 竖屏模式同样不依赖登录态：推荐流匿名可读，
                // 探测竖屏要的详情/取流接口也都能匿名访问。
                Entry("竖屏模式", Icons.Outlined.SmartDisplay, "vertical"),
            ),
            enabled = true,
            onNavigate = onNavigate,
        )

        Spacer(Modifier.height(Space.x3))

        EntryGroup(
            entries = listOf(
                // ⚠️ 必须带 route。之前这里传 null，而 enabled = true ——
                // 表现出来就是「设置」看着可点，点了没反应（死入口）。
                // 设置页不依赖登录态（播放/隐私/通知都是本地偏好），
                // 所以未登录也必须能进。
                Entry("设置", Icons.Outlined.Settings, "settings"),
                // 「切换账号」未登录时**同样要能进** ——
                // 它不只是"切换"，也是"添加新账号"的入口。
                // 未登录时点进去会看到空列表 + 「添加新账号」，
                // 而不是"点了没反应"（需求明确要求未登录要引导登录）。
                Entry("切换账号", Icons.Outlined.SwitchAccount, "accounts"),
            ),
            enabled = true,
            onNavigate = onNavigate,
        )

        Spacer(Modifier.height(Space.x6))
        Text(
            text = "第三方客户端 · 仅供个人学习自用",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = colors.textTertiary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.x4),
        )
    }
}

/** 已登录：用户信息 + 功能入口。 */
@Composable
private fun LoggedInPanel(
    user: UserInfo,
    onLogout: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 用户信息卡（C 方案：独立卡片）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.x3, vertical = Space.x1)
                .biliCard(shape = RoundedCornerShape(Radius.card))
                .padding(Space.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = user.faceUrl(),
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
                    text = user.name.ifEmpty { "已登录" },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = FontSize.titleMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space.micro))
                Text(
                    text = "UID ${user.mid}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                )
            }
        }

        EntryGroup(
            entries = listOf(
                Entry("历史记录", Icons.Filled.History, "history"),
                Entry("我的收藏", Icons.Filled.Star, "favorites"),
                Entry("稍后再看", Icons.Outlined.Schedule, "toView"),
                // 竖屏沉浸式观看模式。不依赖登录态（推荐流匿名可读），
                // 所以两处入口都放。
                Entry("竖屏模式", Icons.Outlined.SmartDisplay, "vertical"),
                // ⚠️ 离线缓存入口此前**完全不存在** ——
                // `VideoDownloader`（315 行）写好了却没有任何页面能进。
                // 它不依赖登录态（本地文件），所以未登录也该能进。
                Entry("离线缓存", Icons.Outlined.DownloadDone, "downloads"),
                // ⚠️ 这里**刻意不放**「我的消息」。
                //
                // 消息入口统一到**首页右上角铃铛**：那里还带未读红点，
                // 是唯一能提示"有新消息"的地方。在「我的」再放一个是
                // 重复入口 —— 用户会疑惑两处是否不同，且红点只在一处出现，
                // 从「我的」进去看不到任何未读提示。
                // 「查成分」同样不依赖登录态（第三方 aicu 聚合），
                // 但已登录用户更可能用它查别人，所以两处都放。
                Entry("查成分", Icons.AutoMirrored.Outlined.ManageSearch, "aicu"),
            ),
            enabled = true,
            onNavigate = onNavigate,
        )

        Spacer(Modifier.height(Space.x3))

        EntryGroup(
            entries = listOf(
                Entry("设置", Icons.Outlined.Settings, "settings"),
                // 「切换账号」放在设置同级：它是账号级操作，
                // 与历史/收藏这类"内容入口"性质不同，不该混在一起。
                // 未登录时也可见（进去能添加新账号），所以 enabled = true。
                Entry("切换账号", Icons.Outlined.SwitchAccount, "accounts"),
            ),
            enabled = true,
            onNavigate = onNavigate,
        )

        Spacer(Modifier.height(Space.x6))
        Box(modifier = Modifier.padding(horizontal = Space.x4)) {
            BrandButton(
                label = "退出登录",
                onClick = onLogout,
                variant = BrandButtonVariant.Outline,
            )
        }
        Spacer(Modifier.height(Space.x6))
    }
}

/** 入口行数据。 */
private data class Entry(
    val label: String,
    val icon: ImageVector,
    val route: String?,
)

/**
 * 功能入口分组。
 *
 * @param enabled false 时整体弱化且不可点 —— 未登录状态下这些功能
 *   确实不可用，做成"看着能点但点了没反应"是错误做法
 *   （`AGENTS.md` §5.1 反模式）。
 */
@Composable
private fun EntryGroup(
    entries: List<Entry>,
    enabled: Boolean,
    onNavigate: (String) -> Unit = {},
) {
    val colors = BiliTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x1)
            .biliCard(shape = RoundedCornerShape(Radius.card)),
    ) {
        entries.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled && entry.route != null) {
                        entry.route?.let(onNavigate)
                    }
                    .padding(horizontal = Space.x4, vertical = Space.x4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = if (enabled) colors.textSecondarySafe else colors.textTertiary,
                    modifier = Modifier.size(Sizes.iconLg),
                )
                Spacer(Modifier.width(Space.x3))
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = if (enabled) colors.textPrimary else colors.textTertiary,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (!enabled) {
                    Text(
                        text = "登录后可用",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            color = colors.textTertiary,
                        ),
                    )
                }
                Spacer(Modifier.width(Space.x2))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(Sizes.iconLg),
                )
            }

            // 分隔线（最后一项不加）
            if (index != entries.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Space.x4)
                        .height(1.dp)
                        .background(colors.borderHairline),
                )
            }
        }
    }
}
