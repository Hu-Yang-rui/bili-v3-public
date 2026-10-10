package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * 播放器正下方的**左右两栏工具条**。
 *
 * ## 布局（对照官方）
 *
 * ```
 * ┌───────────────────────────┬──────────────────────┐
 * │ 简介        评论 8459      │  点我发弹幕   (弹)   │
 * └───────────────────────────┴──────────────────────┘
 *    ↑ 左栏：视图切换控件           ↑ 右栏：发弹幕 + 弹幕开关
 * ```
 *
 * ## ⚠️ 左栏那个**不是简介内容**，是切换控件
 *
 * 用户特别强调过这个区别。左栏的「简介 / 评论」是**标签页**：
 * 点击切换下方显示哪一块内容，它本身不展示简介正文。
 *
 * 真正的简介正文在**标题右侧的倒 V 按钮**展开处（见 [VideoMetaSection]）。
 *
 * ## 两栏为什么并排
 *
 * 官方就是这个结构：左边是"看什么"（评论/简介切换），
 * 右边是"我要说什么"（发弹幕）。两者是**不同性质**的操作，
 * 并排放置比上下堆叠更省纵向空间，也让右手拇指更容易够到发弹幕。
 *
 * @param commentTabSelected 当前选中的标签（true=评论，false=简介）
 * @param commentCount 评论数，显示在标签上（`评论 8459`）
 * @param onSelectTab 切换标签
 * @param danmakuEnabled 弹幕总开关状态
 * @param onToggleDanmaku 切换弹幕开关
 * @param onSendDanmaku 点"发弹幕"（当前弹提示，完整输入后续接入）
 */
@Composable
fun VideoToolRow(
    commentTabSelected: Boolean,
    commentCount: Int,
    onSelectTab: (Boolean) -> Unit,
    danmakuEnabled: Boolean,
    onToggleDanmaku: () -> Unit,
    onSendDanmaku: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * AI 总结入口（v1.6.3）。
     *
     * `null` = 该功能不可用（未注入仓库），此时**不渲染**入口 ——
     * 而不是渲染一个点了没反应的按钮（§1.6 死入口）。
     *
     * 放在工具条右栏（发弹幕旁边）的理由：它是"对这个视频做点什么"
     * 这一类动作，与发弹幕同族；放左栏会与"简介/评论"的**视图切换**
     * 语义混淆（那两个是切换，这个是动作）。
     */
    onOpenSummary: (() -> Unit)? = null,
) {
    val colors = BiliV3.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            // ⚠️ 工具条**不是卡片**，是紧贴播放器下方的一条标签行。
            //
            // 它只有两个标签（简介 / 评论）+ 两个动作（发弹幕 / 弹幕开关），
            // 给它套卡会立刻多一个框 —— 加上下方的 UP 卡，
            // 一屏里就是"播放器 + 框 + 框"，这正是臃肿的来源。
            //
            // 现在直接落在页面底上，靠留白与下方卡片分开。
            // 官方客户端此处也是通栏、无容器。
            .padding(horizontal = V3Space.md, vertical = V3Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ================= 左栏：视图切换 =================
        Row(
            horizontalArrangement = Arrangement.spacedBy(V3Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            TabItem(
                label = "简介",
                selected = !commentTabSelected,
                onClick = { onSelectTab(false) },
            )
            TabItem(
                label = if (commentCount > 0) "评论 $commentCount" else "评论",
                selected = commentTabSelected,
                onClick = { onSelectTab(true) },
            )
        }

        // ================= 右栏：AI 总结 + 发弹幕 + 开关 =================
        Row(
            horizontalArrangement = Arrangement.spacedBy(V3Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ---- AI 总结入口（v1.6.3）----
            //
            // 用**品牌蓝**（`textLinkSafe`）而不是粉色：粉色在本项目里是
            // "主行动/已选中"的语义，而 AI 总结是一个次级入口。
            // 蓝色图标与全站"功能入口"的用色一致（§5.2：品牌蓝只做图标）。
            if (onOpenSummary != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(V3Radius.pill))
                        .background(colors.bgTertiary)
                        .clickable(onClick = onOpenSummary)
                        .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = colors.brandText,
                        modifier = Modifier.size(V3Size.iconXs),
                    )
                    Spacer(Modifier.width(V3Space.xxs))
                    Text(
                        text = "AI 总结",
                        style = V3Type.caption2.copy(
                            color = colors.labelSecondary,
                        ),
                        maxLines = 1,
                    )
                }
            }

            // 发弹幕入口：胶囊形，一眼看出可点
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(V3Radius.pill))
                    .background(colors.bgTertiary)
                    .clickable(onClick = onSendDanmaku)
                    .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(V3Size.iconXs),
                )
                Spacer(Modifier.width(V3Space.xxs))
                Text(
                    text = "点我发弹幕",
                    style = V3Type.caption2.copy(
                        color = colors.labelSecondary,
                    ),
                    maxLines = 1,
                )
            }

            // 弹幕总开关：图标按钮，开启时高亮
            Box(
                modifier = Modifier
                    .size(V3Size.iconLg + V3Space.xs)
                    .clip(RoundedCornerShape(V3Radius.xs))
                    .clickable(onClick = onToggleDanmaku),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = if (danmakuEnabled) "关闭弹幕" else "开启弹幕",
                    tint = if (danmakuEnabled) colors.brand else colors.labelTertiary,
                    modifier = Modifier.size(V3Size.iconMd),
                )
            }
        }
    }
}

/**
 * 标签项。
 *
 * 选中态用**文字加粗 + 下划线**双重表达（不只靠颜色）——
 * 色弱用户也要能看出当前选的是哪个（§4.6）。
 */
@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(V3Radius.xs))
            .clickable(onClick = onClick)
            .padding(vertical = V3Space.xxs),
    ) {
        Text(
            text = label,
            style = V3Type.callout.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.labelPrimary else colors.labelSecondary,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.height(V3Space.hairline))
        // 下划线：选中时才显示（高度固定，避免切换时行高跳动）
        //
        // ⚠️ 用 `V3Space.tabIndicator`(3dp) 而不是 `trackHeight`(2dp)（v1.4.2 修）。
        // 两者语义不同：`tabIndicator` 专指 Tab 下划线，`trackHeight` 是进度条。
        // 同项目里其它 Tab 都用 tabIndicator，这里用 2dp 会让切 Tab 时
        // 下划线粗细与相邻页面不一致。
        Box(
            modifier = Modifier
                .width(TAB_UNDERLINE)
                .height(V3Space.tabIndicator)
                .background(
                    if (selected) colors.brand else androidx.compose.ui.graphics.Color.Transparent,
                ),
        )
    }
}

/**
 * 标题右侧的**简介展开按钮**（倒 V）。
 *
 * ## 语义
 *
 * 展开时旋转 180° 变成正 V —— 一个图标表达"展开/收起"两个状态，
 * 不额外引资源。这是用户明确要求的形态。
 *
 * ## 为什么放在标题行最右
 *
 * 用户要求"真正的简介位于视频标题的最右侧"。
 * 这样简介的**入口**紧挨着它所属的内容（标题），
 * 而不是像原先那样单独占一整块。
 *
 * ## 🔴 v1.6.7 修：与下方「三个点」不在同一条竖线上
 *
 * ### 实测（emulator-5554 / density 420，uiautomator bounds）
 *
 * | 元素 | 图标 bounds | 中心 x |
 * |---|---|---|
 * | 展开简介（倒 V） | `[965,1239][1028,1302]` | **996** |
 * | 更多操作（三个点） | `[949,1385][1002,1438]` | **976** |
 *
 * **相差 20px**，肉眼可见地"没对齐"。
 *
 * ### 根因：**容器尺寸不一致**（不是 Row 的 alignment 问题）
 *
 * ```
 * 倒 V 容器   = iconXl + x2 = 24 + 8 = 32dp
 * 三个点容器 = minTouchTarget    = 48dp
 * ```
 *
 * 两者都是所在 Row 的**最后一个元素**，所以它们的**右边缘对齐**。
 * 但图标在容器里居中 → 容器越宽，图标中心离右边缘越远：
 *
 * ```
 * 倒 V 图标中心   = 右边缘 − 32/2 = 右边缘 − 16dp
 * 三个点图标中心 = 右边缘 − 48/2 = 右边缘 − 24dp
 * 差值 = 8dp = 21px  ← 与实测的 20px 吻合
 * ```
 *
 * ### 修法：**统一容器尺寸**（而不是加负 margin 硬凑）
 *
 * 两个按钮都用 `V3Size.touchMin`（48dp）：
 * - 图标中心都落在 `右边缘 − 24dp` → 天然对齐
 * - 顺带满足 48dp 最小触摸目标（原来的 32dp 是偏小的）
 * - **没有负 margin、没有 magic number** —— 只是让两个同类控件用同一个 token
 *
 * ⚠️ 图标本身尺寸仍不同（倒 V 24dp / 三个点 20dp）——
 * 这是**刻意的**：两个图标形状不同，视觉重量要匹配，
 * 强行同尺寸反而会让倒 V 显得比三个点小。对齐的是**中心**，不是外框。
 */
@Composable
fun DescToggleButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors
    Box(
        modifier = modifier
            // ⚠️ 必须与「更多操作」用同一个容器尺寸，否则中心对不齐
            //    （见上方长说明：8dp 容器差 = 21px 中心偏移）
            .size(V3Size.touchMin)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起简介" else "展开简介",
            tint = colors.labelSecondary,
            modifier = Modifier
                .size(V3Size.iconLg)
                // 展开时翻转成向上箭头
                .rotate(if (expanded) 180f else 0f),
        )
    }
}

/** 标签下划线宽度。固定值避免切换时宽度跳动。 */
private val TAB_UNDERLINE = 20.dp
