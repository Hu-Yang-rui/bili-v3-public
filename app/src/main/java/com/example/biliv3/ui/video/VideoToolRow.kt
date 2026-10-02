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
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

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
) {
    val colors = BiliTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            // C 方案：工具条是**卡片**，与下方 UP 信息卡 / 互动栏同列堆叠。
            //
            // ⚠️ 圆角用 `Radius.card`(16dp)，与相邻卡片一致。
            // 此前是 `Radius.button`(12dp)，与邻居差 4dp —— 弧度对不上。
            //
            // 纵向内边距从 `Space.x2`(8dp) 收到 `Space.x1`(4dp)：
            // 这条工具条紧贴播放器下方，每多 4dp 都是从画面里抢的。
            .biliCard(elevation = 0.dp, shape = RoundedCornerShape(Radius.card))
            .padding(horizontal = Space.x4, vertical = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ================= 左栏：视图切换 =================
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.x5),
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

        // ================= 右栏：发弹幕 + 开关 =================
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 发弹幕入口：胶囊形，一眼看出可点
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.bgHover)
                    .clickable(onClick = onSendDanmaku)
                    .padding(horizontal = Space.x3, vertical = Space.x1 + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = colors.textSecondarySafe,
                    modifier = Modifier.size(Sizes.iconSm),
                )
                Spacer(Modifier.width(Space.x1))
                Text(
                    text = "点我发弹幕",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.textSecondarySafe,
                    ),
                    maxLines = 1,
                )
            }

            // 弹幕总开关：图标按钮，开启时高亮
            Box(
                modifier = Modifier
                    .size(Sizes.iconXl + Space.x2)
                    .clip(RoundedCornerShape(Radius.button))
                    .clickable(onClick = onToggleDanmaku),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = if (danmakuEnabled) "关闭弹幕" else "开启弹幕",
                    tint = if (danmakuEnabled) colors.brandPrimary else colors.textTertiary,
                    modifier = Modifier.size(Sizes.iconLg),
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
    val colors = BiliTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.button))
            .clickable(onClick = onClick)
            .padding(vertical = Space.x1),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.textPrimary else colors.textSecondarySafe,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        // 下划线：选中时才显示（高度固定，避免切换时行高跳动）
        Box(
            modifier = Modifier
                .width(TAB_UNDERLINE)
                .height(2.dp)
                .background(
                    if (selected) colors.brandPrimary else androidx.compose.ui.graphics.Color.Transparent,
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
 */
@Composable
fun DescToggleButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    Box(
        modifier = modifier
            .size(Sizes.iconXl + Space.x2)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起简介" else "展开简介",
            tint = colors.textSecondarySafe,
            modifier = Modifier
                .size(Sizes.iconXl)
                // 展开时翻转成向上箭头
                .rotate(if (expanded) 180f else 0f),
        )
    }
}

/** 标签下划线宽度。固定值避免切换时宽度跳动。 */
private val TAB_UNDERLINE = 20.dp
