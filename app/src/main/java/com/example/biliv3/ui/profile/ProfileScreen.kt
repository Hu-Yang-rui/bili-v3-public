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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Extension
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.biliv3.data.auth.UserInfo
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.ruleTop
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

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
    val colors = BiliV3.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPrimary)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        when (val s = state) {
            is ProfileUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.brand,
                    strokeWidth = V3Space.progressTrack,
                    modifier = Modifier.size(V3Size.iconLg),
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

            // ⚠️ 本地有凭据但查询失败 —— **不能显示 Guest**，
            // 那会让已登录用户以为被登出（见 ProfileUiState.Failed 的说明）。
            is ProfileUiState.Failed -> ErrorState(
                title = "无法获取账号信息",
                description = s.reason,
                onRetry = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
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
    val colors = BiliV3.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 未登录引导：通栏，不再套卡片
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = V3Space.md, end = V3Space.md, top = Rhythm.between),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(V3Size.avatarXs + V3Space.xxl)
                    .clip(CircleShape)
                    .background(colors.bgTertiary),
            )
            Spacer(Modifier.width(V3Space.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "未登录",
                    style = V3Type.subheadline.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.labelPrimary,
                    ),
                )
                Spacer(Modifier.height(V3Space.hairline))
                Text(
                    text = "登录后可同步历史与收藏",
                    style = V3Type.caption1.copy(
                        color = colors.labelSecondary,
                    ),
                )
            }
            Spacer(Modifier.width(V3Space.sm))
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

        // 离线缓存**不依赖登录态**（本地文件），所以未登录也要能进 ——
        // 与上面那组"需要登录"的入口分开是刻意的。
        //
        // 「查成分」也在这里：它走 aicu 第三方聚合，**不依赖 B 站登录态**，
        // 未登录也应该能用（aicu 自己维护数据，与我们的 Cookie 无关）。
        EntryGroup(
            entries = listOf(
                Entry("离线缓存", Icons.Outlined.DownloadDone, "downloads"),
                Entry("查成分", Icons.AutoMirrored.Outlined.ManageSearch, "aicu"),
                // 特别关注是**本地书签**（v1.6.3）—— 不依赖登录态，
                // 未登录也应该能看到自己标记过的人。
                Entry("特别关注", Icons.Outlined.BookmarkBorder, "attention"),
                // 竖屏模式同样不依赖登录态：推荐流匿名可读，
                // 探测竖屏要的详情/取流接口也都能匿名访问。
                Entry("竖屏模式", Icons.Outlined.SmartDisplay, "vertical"),
                // v1.3.0：播放队列与插件中心都不依赖登录态
                // （队列是本地状态；插件是本地解析）
                Entry("播放队列", Icons.AutoMirrored.Outlined.QueueMusic, "queue"),
                Entry("插件中心", Icons.Outlined.Extension, "plugins"),
            ),
            enabled = true,
            onNavigate = onNavigate,
        )

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

        Spacer(Modifier.height(V3Space.xl))
        Text(
            text = "第三方客户端 · 仅供个人学习自用",
            style = V3Type.caption1.copy(
                color = colors.labelTertiary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V3Space.md),
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
    val colors = BiliV3.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 用户信息：通栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = V3Space.md, end = V3Space.md, top = Rhythm.between),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = user.faceUrl(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(V3Size.avatarXs + V3Space.xxl)
                    .clip(CircleShape)
                    .background(colors.avatarPlaceholder),
            )
            Spacer(Modifier.width(V3Space.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name.ifEmpty { "已登录" },
                    style = V3Type.subheadline.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.labelPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(V3Space.hairline))
                Text(
                    text = "UID ${user.mid}",
                    style = V3Type.caption1.copy(
                        color = colors.labelSecondary,
                    ),
                )
            }
        }

        EntryGroup(
            entries = listOf(
                Entry("历史记录", Icons.Filled.History, "history"),
                Entry("我的收藏", Icons.Filled.Star, "favorites"),
                Entry("稍后再看", Icons.Outlined.Schedule, "toView"),
                // 特别关注：**纯本地书签**（v1.6.3）。
                // 与"关注"不是一回事 —— 它不向 B 站发任何请求。
                // 放在"我的"里是因为它属于"我标记过的东西"这一类，
                // 与历史/收藏/稍后再看同族。
                Entry("特别关注", Icons.Outlined.BookmarkBorder, "attention"),
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
                // 🔴 v3：**播放队列 / 插件中心** —— 已登录侧此前漏了两个入口。
                //
                // ## 这是「入口只加在一处」的真实事故
                //
                // 这两个入口**只写在 `GuestPanel` 里**（未登录那一份列表），
                // 而已登录走的是 `LoggedInPanel` 的**另一份列表** ——
                // 于是登录之后「播放队列」和「插件中心」**从 UI 上消失了**。
                //
                // 实测证据（模拟器 UI dump，已登录态）：
                // 可见条目依次是 历史记录 / 我的收藏 / 稍后再看 / 特别关注 /
                // 竖屏模式 / 离线缓存 / 查成分 / 设置 / 切换账号 / 退出登录 ——
                // **没有播放队列，也没有插件中心**。
                //
                // 两者都不依赖登录态（队列是本地状态、插件是本地解析），
                // 所以「已登录看不到」纯属漏加，不是有意隐藏。
                //
                // 🔴 **判据**：**同一份入口清单如果被抄成两份，
                // 就一定会漂移** —— 本项目已因同类问题出过
                // 「设置页 0.6.1 而 versionName 0.6.4」。
                // ⚠️ 真正该做的是把这份清单抽成常量（见下方 TODO），
                //    但本轮先补齐条目、不改变现有结构。
                // TODO：把 Guest/LoggedIn 两份列表合并为一个数据源。
                Entry("播放队列", Icons.AutoMirrored.Outlined.QueueMusic, "queue"),
                Entry("插件中心", Icons.Outlined.Extension, "plugins"),
            ),
            enabled = true,
            onNavigate = onNavigate,
        )

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

        Spacer(Modifier.height(V3Space.xl))
        Box(modifier = Modifier.padding(horizontal = V3Space.md)) {
            BrandButton(
                label = "退出登录",
                onClick = onLogout,
                variant = BrandButtonVariant.Outline,
            )
        }
        Spacer(Modifier.height(V3Space.xl))
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
 * 不再是卡片：入口行通栏排列，行与行之间用发丝线，分组之间靠
 * [Rhythm.between] 间距。
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
    val colors = BiliV3.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 组间距只由下方区块的 top 提供（全站规则：bottom 一律不加）
            .padding(top = Rhythm.between),
    ) {
        entries.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 第一项不画线（线在条目上方，避免顶部多一条）
                    .then(
                        if (index == 0) Modifier
                        else Modifier.ruleTop(color = Rule.subtle),
                    )
                    .clickable(enabled = enabled && entry.route != null) {
                        entry.route?.let(onNavigate)
                    }
                    // 组内间距 + 余量，保证行高 ≥ 48dp 触摸目标
                    .padding(horizontal = V3Space.md, vertical = Rhythm.inGroup + V3Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = if (enabled) colors.labelSecondary else colors.labelTertiary,
                    modifier = Modifier.size(V3Size.iconMd),
                )
                Spacer(Modifier.width(V3Space.sm))
                Text(
                    text = entry.label,
                    style = V3Type.callout.copy(
                        color = if (enabled) colors.labelPrimary else colors.labelTertiary,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (!enabled) {
                    Text(
                        text = "登录后可用",
                        style = V3Type.caption1.copy(
                            color = colors.labelTertiary,
                        ),
                    )
                }
                Spacer(Modifier.width(V3Space.xs))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(V3Size.iconMd),
                )
            }
        }
    }
}
