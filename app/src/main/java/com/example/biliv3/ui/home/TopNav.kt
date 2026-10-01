package com.example.biliv3.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 顶部导航。
 *
 * ## 结构（移动端，对齐 `AGENTS.md` §5.2）
 * ```
 * [🔍 搜索框（撑满剩余宽度）] [消息] [头像]
 * ```
 * 官方首页顶栏就是「头像 + 搜索框 + 消息」三件套，**没有 Logo 位**。
 * 应用名出现在启动图标上已经足够，顶栏再占一块是浪费最宝贵的第一屏宽度。
 *
 * ## 三端差异
 *
 * | 端 | 高度 | 导航项 | 搜索框 |
 * |---|---|---|---|
 * | 桌面 | 64 | 文字导航 | 固定 320 |
 * | 平板 | 56 | 收纳"会员购" | 弹性 |
 * | 移动 | 52 | 无 | **撑满剩余宽度** |
 *
 * ## 关于"投稿"
 *
 * 第三方客户端**无投稿能力**，所以按钮保留但为禁用态 ——
 * 不能给一个点了没反应的入口。移动端该位置让给底部导航。
 */
@Composable
fun TopNav(
    windowSize: WindowSize,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 消息铃铛点击。
     *
     * ⚠️ 之前这里是 `clickable { }` **空 lambda** ——
     * 点了完全没反应，正是「反模式 #1 死入口」的实例。
     * 现在接到私信会话列表路由（`Routes.MESSAGES`）。
     */
    onMessageClick: () -> Unit = {},
    /** 头像点击：进「我的」Tab。 */
    onProfileClick: () -> Unit = {},
    /** 是否有未读（驱动铃铛红点）。 */
    hasUnread: Boolean = false,
    /**
     * 顶栏导航项点击（仅桌面 / 平板可见）。
     *
     * ⚠️ 之前这 6 个导航项（首页/番剧/直播/游戏中心/会员购/动态）
     * **只改选中态、不跳转** —— 也是死入口。现在按标签派发到真实路由。
     */
    onNavItemClick: (String) -> Unit = {},
) {
    val colors = BiliTheme.colors

    val height = when (windowSize) {
        WindowSize.Desktop -> Sizes.topBarDesktop
        WindowSize.Tablet -> Sizes.topBarTablet
        WindowSize.Mobile -> Sizes.topBarMobile
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // C 方案：顶栏是通栏卡片（不浮起），圆角只留下方两角 ——
            // 它贴着屏幕顶端，上方圆角会被状态栏切掉，画了也看不见。
            .biliCard(
                elevation = 0.dp,
                shape = RoundedCornerShape(
                    bottomStart = Radius.card,
                    bottomEnd = Radius.card,
                ),
            )
            .height(height),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(horizontal = pagePaddingFor(windowSize)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ---- 导航项（桌面 / 平板；移动端让位给搜索框）----
            if (windowSize != WindowSize.Mobile) {
                NavLinks(windowSize = windowSize, onNavItemClick = onNavItemClick)
                Spacer(Modifier.width(Space.x5))
            }

            // ---- 搜索框 ----
            // ⚠️ 移动端/平板这里**只挂一个 weight(1f)**，吃掉剩余的全部宽度。
            // 原来还有 Logo + `Spacer(weight(1f))`，三者里两个 weight 会平分
            // 剩余空间 —— 搜索框实际只拿到一半，这就是它偏窄的原因。
            Box(
                modifier = if (windowSize == WindowSize.Desktop) {
                    Modifier.width(Sizes.searchWidthDesktop)
                } else {
                    Modifier.weight(1f)
                },
            ) {
                SearchBox(windowSize = windowSize, onClick = onSearchClick)
            }

            // 只有桌面端需要这个弹性 spacer：搜索框是固定宽度，
            // 靠它把右侧动作推到最右。移动/平板搜索框已占 weight(1f)，
            // 再加一个 weight 就会重新变成平分（搜索框又变窄）。
            if (windowSize == WindowSize.Desktop) {
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.width(Space.x4))

            // ---- 右侧动作 ----
            if (windowSize != WindowSize.Mobile) {
                UploadButton(enabled = false)
                Spacer(Modifier.width(Space.x3))
            }
            NavIconButton(
                icon = Icons.Outlined.NotificationsNone,
                label = "消息",
                hasDot = hasUnread,
                onClick = onMessageClick,
            )
            Spacer(Modifier.width(Space.x3))
            NavIconButton(
                icon = Icons.Outlined.Person,
                label = "头像",
                hasDot = false,
                onClick = onProfileClick,
            )
        }
    }
}

/** 页面水平内边距按断点取值。 */
fun pagePaddingFor(windowSize: WindowSize) = when (windowSize) {
    WindowSize.Desktop -> Space.pageDesktop
    WindowSize.Tablet -> Space.pageTablet
    WindowSize.Mobile -> Space.pageMobile
}

/** 网格列间距按断点取值。 */
fun gridGutterFor(windowSize: WindowSize) = when (windowSize) {
    WindowSize.Desktop -> Space.gridGutterDesktop
    WindowSize.Tablet -> Space.gridGutterTablet
    WindowSize.Mobile -> Space.gridGutterMobile
}

/** 网格行间距按断点取值。 */
fun gridRowSpacingFor(windowSize: WindowSize) = when (windowSize) {
    WindowSize.Desktop -> Space.gridRowDesktop
    WindowSize.Tablet -> Space.gridRowTablet
    WindowSize.Mobile -> Space.gridRowMobile
}

/** 区块纵向间距按断点取值。 */
fun sectionSpacingFor(windowSize: WindowSize) = when (windowSize) {
    WindowSize.Desktop -> Space.sectionDesktop
    WindowSize.Tablet -> Space.sectionTablet
    WindowSize.Mobile -> Space.sectionMobile
}

/** 导航项列表。 */
@Composable
private fun NavLinks(
    windowSize: WindowSize,
    onNavItemClick: (String) -> Unit,
) {
    val colors = BiliTheme.colors

    // 平板收纳"会员购"
    val items = if (windowSize == WindowSize.Tablet) {
        listOf("首页", "番剧", "直播", "游戏中心", "动态")
    } else {
        listOf("首页", "番剧", "直播", "游戏中心", "会员购", "动态")
    }

    var selected by remember { mutableStateOf("首页") }

    Row(horizontalArrangement = Arrangement.spacedBy(Space.x5)) {
        items.forEach { label ->
            val isSelected = label == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.badge))
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) {
                        // 选中态 + **真实跳转**。
                        // 只改选中态不跳转就是死入口（见 TopNav 的 KDoc）。
                        selected = label
                        onNavItemClick(label)
                    },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        // hover / 选中都用"文字安全粉"（5.1:1），
                        // 品牌粉直接做文字不达标
                        color = if (isSelected || hovered) {
                            colors.textBrandSafe
                        } else {
                            colors.textPrimary
                        },
                        fontWeight = if (isSelected) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                    ),
                )
            }
        }
    }
}

/**
 * 搜索框。
 *
 * ⚠️ 这里是**假输入框**（只做展示 + 点击跳转），
 * 真正的输入在搜索页 —— 避免首页原地输入把整个列表顶起。
 */
@Composable
private fun SearchBox(
    windowSize: WindowSize,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val widthModifier = if (windowSize == WindowSize.Desktop) {
        Modifier.width(Sizes.searchWidthDesktop)
    } else {
        // 非桌面端搜索框占满剩余空间 —— 由外层 Row 的 weight 提供
        Modifier.fillMaxWidth()
    }

    Row(
        modifier = widthModifier
            .height(Sizes.searchHeight)
            .clip(RoundedCornerShape(Radius.button))
            .background(if (hovered) colors.bgCard else colors.bgBase)
            .border(
                width = 1.dp,
                color = if (hovered) colors.brandPrimary else colors.borderHairline,
                shape = RoundedCornerShape(Radius.button),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(Sizes.iconMd),
        )
        Spacer(Modifier.width(Space.x2))
        Text(
            // ⚠️ 文案必须短。移动端这一行被 logo + 消息 + 头像挤得很窄，
            // 原来的「搜索视频、UP主」实测会被折成两行（"搜索视"/"频、"），
            // 把 40dp 高的搜索框顶变形。配合 maxLines=1 + Ellipsis 兜底。
            text = "搜索",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                color = colors.textSecondary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 投稿按钮。
 *
 * ⚠️ 第三方客户端无投稿能力 → **禁用态**。
 * 给一个能点但没反应的按钮是错误做法。
 */
@Composable
private fun UploadButton(enabled: Boolean) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .height(Sizes.searchHeight)
            .clip(RoundedCornerShape(Radius.button))
            .background(if (enabled) colors.brandPrimary else colors.skeletonBase)
            .padding(horizontal = Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Videocam,
            contentDescription = null,
            tint = if (enabled) colors.textOnBrand else colors.textTertiary,
            modifier = Modifier.size(Sizes.iconMd),
        )
        Spacer(Modifier.width(Space.x2))
        Text(
            text = "投稿",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = FontSize.body,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) colors.textOnBrand else colors.textTertiary,
            ),
        )
    }
}

/** 圆形图标按钮（消息 / 头像）。 */
@Composable
private fun NavIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    hasDot: Boolean,
    onClick: () -> Unit = {},
) {
    val colors = BiliTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(Space.minTouchTarget)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (hovered) colors.bgBase else colors.bgCard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (hovered) colors.brandPrimary else colors.textSecondarySafe,
                modifier = Modifier.size(Sizes.iconLg),
            )
            if (hasDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.stateError),
                )
            }
        }
    }
}
