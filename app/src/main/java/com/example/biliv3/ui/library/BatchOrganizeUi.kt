package com.example.biliv3.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.biliv3.data.FavFolder
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.ruleTop

/**
 * 收藏批量整理的 UI 组件（v1.3.0）。
 *
 * ## 为什么单独一个文件
 *
 * `LibraryScreens.kt` 已经 1000+ 行，且批量整理是**自成一体**的一组
 * 组件（操作条 + 底部条 + 两个浮层），放进大文件里会淹没。
 *
 * ## 三条设计约束（沿用 §5.1 无卡片架构）
 *
 * 1. **操作条用 `band`（明度带）而不是卡片** —— 它是"当前模式"的提示条，
 *    不是内容容器。全宽、直角、无描边。
 * 2. **圆角只给交互元素** —— 操作条本身直角，里面的按钮 4dp。
 * 3. **底部条必须消费 `navigationBars`** —— 否则按钮压在系统导航栏上，
 *    点"取消收藏"会先触发手势导航（这是本项目踩过的同一个坑，见 AGENTS §7.10-53）。
 */

/**
 * 顶部批量操作条：选择范围控制。
 *
 * 放在列表**上方**而不是底部 —— 因为"全选/反选"是**范围**操作，
 * 与"移动/删除"这类**执行**操作分开。混在一条里会让用户在
 * 想改选择范围时误触删除。
 */
@Composable
fun BatchActionBar(
    selection: BatchSelection,
    batchRunning: Boolean,
    progress: Pair<Int, Int>?,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onInvert: () -> Unit,
    onSelectPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 明度带：提示"当前处于多选模式"，不是内容容器
            .background(colors.bgHover)
            .ruleBottom(color = Rule.color)
            .padding(horizontal = Space.x4, vertical = Space.x2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.x1),
        ) {
            // ⚠️ 全选按钮文案随状态变 —— 已全选时显示「取消全选」，
            // 否则用户点同一个位置会得到相反的结果，很困惑。
            if (selection.isAllSelected()) {
                BarTextButton("取消全选", enabled = !batchRunning, onClick = onClear)
            } else {
                BarTextButton("全选", enabled = !batchRunning, onClick = onSelectAll)
            }

            BarTextButton("反选", enabled = !batchRunning, onClick = onInvert)
            BarTextButton("本页全选", enabled = !batchRunning, onClick = onSelectPage)

            Spacer(Modifier.weight(1f))

            Text(
                text = "${selection.count} 项",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.textSecondary,
                ),
            )
        }

        // 进度：只在批量操作进行中显示
        if (progress != null && batchRunning) {
            val (done, total) = progress
            Spacer(Modifier.height(Space.x1))
            Text(
                text = "处理中 $done / $total",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.accentTerminal,
                ),
            )
        }
    }
}

/** 操作条里的文字按钮（比 TextButton 更紧凑，且禁用态可见）。 */
@Composable
private fun BarTextButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Text(
        text = text,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
            fontSize = FontSize.bodySm,
            fontWeight = FontWeight.Medium,
            color = if (enabled) colors.textPrimary else colors.textTertiary,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.interactive))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.x2, vertical = Space.x1),
    )
}

/**
 * 底部批量执行条。
 *
 * ⚠️ 必须消费 `navigationBars` —— 见文件头第 3 条。
 * ⚠️ 「取消收藏」用**强调色（粉）**而不是红色：本项目没有语义红令牌
 * （深色下纯红会"发光"，很廉价），破坏性靠**确认框**表达而不是靠颜色。
 */
@Composable
fun BatchBottomBar(
    count: Int,
    running: Boolean,
    hasOtherFolders: Boolean,
    onMove: () -> Unit,
    onRemove: () -> Unit,
    onAddToView: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgHover)
            .ruleTop(color = Rule.color)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = Space.x2, vertical = Space.x2),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 没有其他收藏夹时不显示「移动」—— 点了也只能看到空列表
        if (hasOtherFolders) {
            BatchAction(
                icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                label = "移动",
                enabled = !running,
                onClick = onMove,
            )
        }
        BatchAction(icon = Icons.Outlined.PlaylistAdd, label = "加入队列", enabled = !running, onClick = onAddToQueue)
        BatchAction(icon = Icons.Outlined.Add, label = "稍后再看", enabled = !running, onClick = onAddToView)
        BatchAction(
            icon = Icons.Outlined.DeleteOutline,
            label = "取消收藏",
            enabled = !running,
            onClick = onRemove,
        )
    }
}

/** 底部条里的一个动作（图标 + 文字，纵向排列）。 */
@Composable
private fun BatchAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    val tint = if (enabled) colors.textPrimary else colors.textTertiary

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.interactive))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.x1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(Sizes.iconLg),
        )
        Spacer(Modifier.height(Space.x1))
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.bodySm,
                color = tint,
            ),
            maxLines = 1,
        )
    }
}


/**
 * 批量移动：选目标收藏夹。
 *
 * ⚠️ 用 `Dialog` 而不是普通 Composable 浮层 —— 普通内容拦截不到系统返回键，
 * 会穿透把整页弹掉（AGENTS §3.2「浮层」）。
 */
@Composable
fun BatchMovePicker(
    folders: List<FavFolder>,
    count: Int,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val colors = BiliTheme.colors

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
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Space.x6)
                    .fillMaxWidth()
                    .biliCard(
                        shape = RoundedCornerShape(Radius.panel),
                        color = colors.surfaceElevated,
                    )
                    // 弹层本体不穿透到遮罩
                    .clickable(enabled = false) {}
                    .padding(vertical = Space.x4),
            ) {
                Text(
                    text = "移动 $count 个视频到",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(
                        fontSize = FontSize.titleMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                    modifier = Modifier.padding(horizontal = Space.x5),
                )

                Spacer(Modifier.height(Space.x2))

                LazyColumn(
                    // ⚠️ 限高：收藏夹可能几十个，不限高会撑满整屏
                    // 把标题和取消按钮挤出可视区。
                    modifier = Modifier.heightIn(max = Space.x12 * 8),
                ) {
                    items(folders, key = { it.id }) { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(f.id) }
                                .padding(horizontal = Space.x5, vertical = Space.x3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = f.title,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = FontSize.body,
                                    color = colors.textPrimary,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${f.mediaCount}",
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = FontSize.bodySm,
                                    color = colors.textTertiary,
                                ),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Space.x2))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

/**
 * 破坏性操作确认框。
 *
 * ⚠️ 文案里必须带**数量** —— 批量操作一次可能影响几十条，
 * 且服务端没有"撤销"。不写数量的确认框用户会盲点。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = BiliTheme.colors

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
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Space.x6)
                    .fillMaxWidth()
                    .biliCard(
                        shape = RoundedCornerShape(Radius.panel),
                        color = colors.surfaceElevated,
                    )
                    .clickable(enabled = false) {}
                    .padding(Space.x5),
            ) {
                Text(
                    text = title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(
                        fontSize = FontSize.titleMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                )
                Spacer(Modifier.height(Space.x2))
                Text(
                    text = message,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.bodySm,
                        color = colors.textSecondarySafe,
                    ),
                )
                Spacer(Modifier.height(Space.x4))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = colors.textSecondary)
                    }
                    TextButton(onClick = onConfirm) {
                        Text(
                            text = confirmText,
                            color = if (destructive) colors.brandPrimary else colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
