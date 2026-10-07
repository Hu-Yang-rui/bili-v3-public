package com.example.biliv3.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.rule
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import androidx.compose.ui.graphics.Color
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Type

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
        WindowSize.Desktop -> V3Size.topBar
        WindowSize.Tablet -> V3Size.topBar
        WindowSize.Mobile -> V3Size.topBar
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 🔴 v3 重构：顶栏高度统一为 **54dp**（iOS 27 实测值，旧版是 52/56/64 三档）。
            //
            // 三端不再分档的原因：顶栏里只有"搜索框 + 两个圆钮"，
            // 它们在平板上并不需要更高的栏 —— 分档只会让三端观感不一致。
            //
            // ⚠️ **仍然不画底边线**（旧版踩过的坑）：
            // 搜索框自身有边界，顶栏再加一条线会与它形成"双线"。
            .height(height),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(horizontal = V3Space.contentMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ---- 导航项（桌面 / 平板；移动端让位给搜索框）----
            if (windowSize != WindowSize.Mobile) {
                NavLinks(windowSize = windowSize, onNavItemClick = onNavItemClick)
                Spacer(Modifier.width(V3Space.xl))
            }

            // ---- 搜索框 ----
            // ⚠️ 只挂一个 weight(1f) 吃掉剩余宽度 ——
            // 再加第二个 weight 会变成平分，搜索框又变窄（旧版踩过）。
            Box(
                modifier = if (windowSize == WindowSize.Desktop) {
                    Modifier.width(320.dp)
                } else {
                    Modifier.weight(1f)
                },
            ) {
                SearchField(onClick = onSearchClick)
            }

            if (windowSize == WindowSize.Desktop) {
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.width(V3Space.sm))

            // ---- 右侧动作 ----
            NavCircleButton(
                icon = Icons.Outlined.NotificationsNone,
                label = "消息",
                hasDot = hasUnread,
                onClick = onMessageClick,
            )
            Spacer(Modifier.width(V3Space.xs))
            NavCircleButton(
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

/**
 * 网格列间距按断点取值。
 *
 * ## 🔴 从 12dp 收到 8dp（乙·质感）
 *
 * 卡片架构下，相邻两项的视觉间距 = `gutter + 卡片内边距 × 2`
 * （每张卡自己还有 8dp 内边距）→ 实际 28dp，明显过宽。
 *
 * 去掉卡片内边距后，间距**只剩 gutter 本身**。8dp 是网格的合理值：
 * 再小封面会"粘"在一起，再大就不像网格而像散落的卡片。
 */
fun gridGutterFor(windowSize: WindowSize) = when (windowSize) {
    WindowSize.Desktop -> V3Space.sm
    WindowSize.Tablet -> V3Space.sm
    WindowSize.Mobile -> V3Space.sm
}

/**
 * 网格行间距按断点取值。
 *
 * ## 🔴 从 12dp 增到 24dp（这是"分组"的来源）
 *
 * 卡片架构下，行间距只是"卡片之间的缝"。
 * 无卡片后，行间距承担**分组职责** —— 它必须明显大于列间距，
 * 否则一屏内容会糊成一片（看不出"一组"在哪）。
 *
 * 24dp = 列间距的 3 倍，符合 [Rhythm] 的"组间距 ≥ 2× 组内间距"。
 */
fun gridRowSpacingFor(windowSize: WindowSize) = when (windowSize) {
    WindowSize.Desktop -> V3Space.xl
    WindowSize.Tablet -> V3Space.xl
    WindowSize.Mobile -> V3Space.xl
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
 * **搜索入口**（顶栏里的假输入框）。
 *
 * ---
 *
 * # 🔴 v3 重构：从"底线输入框"改回"填充胶囊"
 *
 * 旧系统把搜索框做成了**只有一条底线的无框输入区**，理由是
 * "它是顶栏里最像卡片的元素，去掉容器才符合无卡片"。
 *
 * 新系统**改回填充胶囊**（iOS 的 search field 形态），因为：
 *
 * 1. **它不是卡片，是控件** —— 无卡片的约束针对"内容容器"，
 *    而搜索框是一个**输入控件**。控件用填充色是正确语义
 *    （见 `V3Colors` 的三层系统：Fill 层就是给控件用的）。
 * 2. **底线在深色下太弱** —— 纯黑底上一条 12% 白的线，几乎看不见，
 *    用户找不到搜索入口。
 * 3. **iOS 语言里搜索框就是胶囊**（`UISearchBar` / search field）。
 *
 * ⚠️ 这与"无卡片"不冲突：卡片是**装内容的容器**，胶囊是**控件**。
 * 区分标准是"它里面装的是内容，还是它本身是个可点的东西"。
 *
 * ⚠️ 这里是**假输入框**（只展示 + 点击跳转），真正的输入在搜索页。
 */
@Composable
private fun SearchField(onClick: () -> Unit) {
    val colors = BiliV3.colors
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(V3Size.searchField)
            .clip(RoundedCornerShape(V3Radius.pill))
            // Fill 层：这是控件，不是区域
            .background(colors.fillSecondary)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = V3Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(V3Size.iconSm),
        )
        Spacer(Modifier.width(V3Space.xs))
        Text(
            // ⚠️ 文案必须短。移动端这一行被两个圆钮挤得很窄，
            // 长文案会被折行把 36dp 的胶囊顶变形。
            text = "搜索",
            style = V3Type.subheadline,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 圆形图标按钮（消息 / 头像）。
 *
 * ⚠️ v3：从"实心圆底"改为**透明 + 内容**（iOS 的 bar button 形态）。
 *
 * 旧版给每个圆钮铺了 `bgCard` 底 —— 那是"每个按钮一个盒子"，
 * 正是卡片思维在控件上的残留。iOS 的顶栏按钮**没有底色**，
 * 只有图标；底色只在按下时出现。
 */
@Composable
private fun NavCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    hasDot: Boolean,
    onClick: () -> Unit = {},
) {
    val colors = BiliV3.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .size(V3Size.touchMin)
            .clip(CircleShape)
            // 底色只在按下时出现（iOS 的 bar button 反馈）
            .background(if (pressed) colors.fillTertiary else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.labelPrimary,
            modifier = Modifier.size(V3Size.iconLg),
        )
        if (hasDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = V3Space.xs, end = V3Space.xs)
                    .size(V3Size.dotSm)
                    .clip(CircleShape)
                    .background(colors.stateError),
            )
        }
    }
}
