package com.example.biliv3.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.biliv3.design.ruleTop
import com.example.biliv3.design.rule
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 页脚。
 *
 * 三部分：友情链接 / 版权 / 备案。
 * 移动端精简为后两项（链接折叠）。
 *
 * ## 友情链接的跳转
 *
 * 这些链接指向 **B 站官方站点**（第三方客户端没有自己的官网，
 * 造假链接属于「假数据」反模式）。点击走系统浏览器，
 * 不内嵌 WebView —— 内嵌就变成"套壳浏览器"，且要处理登录态串味。
 *
 * @param onOpenLink 打开外链。默认空实现（`@Preview` 用），
 *                   `HomeScreen` 传入真实实现。
 */
@Composable
fun Footer(
    windowSize: WindowSize,
    modifier: Modifier = Modifier,
    onOpenLink: (String) -> Unit = {},
) {
    val colors = BiliTheme.colors

    val links = listOf("友情链接", "开源社区", "关于我们", "反馈建议")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgBase)
            .padding(
                horizontal = pagePaddingFor(windowSize),
                vertical = Space.x8,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- 友情链接（移动端隐藏）----
        //
        // ⚠️ 这里曾经是 `Modifier.clickable { }`（**空 lambda**）——
        // 看着可点、点了没反应，属于「反模式 #1 死入口」。
        //
        // 修法不是"删掉链接"，也不是"接一个假页面"，而是：
        // 这些链接指向的是 B 站**官方站点**，用系统浏览器打开是正确行为
        // （本项目不内嵌 WebView 浏览主站，那会变成"套壳浏览器"）。
        if (windowSize != WindowSize.Mobile) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.x4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                links.forEachIndexed { i, label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            color = colors.textSecondarySafe,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.badge))
                            .clickable { onOpenLink(label) }
                            .padding(horizontal = Space.x1, vertical = Space.compactVertical),
                    )
                    if (i != links.lastIndex) {
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = colors.textTertiary,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.x4))
        }

        // ---- 版权 ----
        Text(
            text = "© 2026 BiliV3 · 仅供个人学习自用，不公开分发",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = colors.textSecondary,
            ),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Space.x2))

        // ---- 备案 ----
        Text(
            text = "备案号 XXXXXXXXXX",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                color = colors.textTertiary,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 移动端底部导航。
 *
 * ## 3 项：首页 / 动态 / 我的
 *
 * 按 `AGENTS.md` §3.3：「底部 TabBar（**3 项 —— 官方也是 3 项，不要加**）」，
 * 并说明「『搜索』不做 Tab（从首页顶部搜索框进）」、「『发布』第三方无投稿能力，
 * **不做**」。
 *
 * 首版做成了 5 项（首页/动态/投稿/消息/我的），其中「投稿」还是**禁用态**：
 * 一个灰掉、居中、带加号的按钮，看着能点、点了弹 snackbar ——
 * 既违反 §3.1「❌ 点了没反应的死入口」，也是"儿童风"观感的来源之一。
 * 现在直接砍掉，回到文档规定的 3 项。
 */
@Composable
fun BottomNav(
    modifier: Modifier = Modifier,
    selectedIndex: Int = 0,
    onSelect: (Int) -> Unit = {},
    onDisabledTap: () -> Unit = {},
    /**
     * 装扮资源（Fake Skin，未发版）。
     *
     * ## 🔴 只影响**背景与图标**，不影响结构
     *
     * - `tailBg` 有 → 铺一张背景图；没有 → 保持原来的发丝线 + 同明度
     * - 图标有 → 用装扮图；没有 → 用原来的 vector 图标
     *
     * 即"装扮负责材质，不改变 UI 架构"（任务书第二十一条）。
     */
    skin: com.example.biliv3.data.skin.SkinAssets =
        com.example.biliv3.data.skin.SkinAssets.EMPTY,
) {
    val colors = BiliTheme.colors

    val items = listOf(
        BottomNavItem("首页", Icons.Filled.Home, enabled = true),
        BottomNavItem("动态", Icons.Outlined.ChatBubbleOutline, enabled = true),
        BottomNavItem("我的", Icons.Outlined.Person, enabled = true),
    )

    /**
     * 每个 Tab 对应的装扮图标逻辑名。
     *
     * ⚠️ 本项目底部导航只有 **3 项**（首页/动态/我的）——
     * 所以**不用** `tail_icon_channel` / `tail_icon_shop`
     * （前者没有对应页面，后者是「会员购」，本项目没有）。
     * 任务书第十八条：没有对应 Tab 就**不要硬塞入口**。
     */
    val iconNames = listOf(
        com.example.biliv3.data.skin.SkinResource.TAIL_ICON_MAIN,
        com.example.biliv3.data.skin.SkinResource.TAIL_ICON_DYNAMIC,
        com.example.biliv3.data.skin.SkinResource.TAIL_ICON_MYSELF,
    )
    val iconNamesSelected = listOf(
        com.example.biliv3.data.skin.SkinResource.TAIL_ICON_SELECTED_MAIN,
        com.example.biliv3.data.skin.SkinResource.TAIL_ICON_SELECTED_DYNAMIC,
        com.example.biliv3.data.skin.SkinResource.TAIL_ICON_SELECTED_MYSELF,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.bottomNav)
            // 🔴 乙·质感：底栏**不再是卡片**。
            //
            // 上一版是通栏卡片（上方两角圆角 + 底色），是屏幕上
            // 最后一块"盒子"。网格去掉卡片后，它是最刺眼的一个。
            //
            // 现在：与页面同明度，只靠**上边一条发丝线**分隔。
            // 底栏"融入页面"，而不是"浮在页面上"。
            .ruleTop(color = Rule.color)
            // 装扮背景图：铺在底栏内容之下。
            //
            // ⚠️ 只在有图时才画 —— 没有图就保持原来的"同明度 + 发丝线"，
            //    不会出现一块空白/黑块（任务书第十条）。
            .then(
                if (skin.tailBg != null) {
                    Modifier.drawBehind {
                        drawImage(
                            image = skin.tailBg!!,
                            dstSize = androidx.compose.ui.unit.IntSize(
                                size.width.toInt().coerceAtLeast(1),
                                size.height.toInt().coerceAtLeast(1),
                            ),
                            // 底栏是宽扁条，低质量过滤足够（省性能）
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                        )
                    }
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val selected = i == selectedIndex && item.enabled

            // 装扮图标（选中态优先用 selected 版本）
            val custom = if (selected) {
                skin.navIcon(iconNamesSelected[i])
            } else {
                skin.navIcon(iconNames[i])
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = item.enabled) {
                        if (item.enabled) onSelect(i) else onDisabledTap()
                    }
                    .padding(vertical = Space.x1),
            ) {
                if (custom != null) {
                    // 装扮图标：**原样渲染，不 tint** ——
                    // 装扮图标自带配色，tint 会把它染成单色（失去设计意图）。
                    androidx.compose.foundation.Image(
                        bitmap = custom,
                        contentDescription = item.label,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.size(Sizes.iconXl),
                    )
                } else {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        // 选中用品牌粉（图标是图形，不是文字，2.6:1 对图形可接受；
                        // 且此处有文字标签作为第二信息载体）
                        tint = when {
                            !item.enabled -> colors.textTertiary
                            selected -> colors.brandPrimary
                            else -> colors.textSecondary
                        },
                        modifier = Modifier.size(Sizes.iconXl),
                    )
                }
                Spacer(Modifier.height(Space.micro))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        color = when {
                            !item.enabled -> colors.textTertiary
                            selected -> colors.textBrandSafe
                            else -> colors.textSecondary
                        },
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
)
