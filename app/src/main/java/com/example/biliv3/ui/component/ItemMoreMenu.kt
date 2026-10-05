package com.example.biliv3.ui.component

import androidx.compose.material.icons.outlined.Email
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.DarkColors
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 「更多」底部菜单（对照官方收藏项的 ⋮ 弹出层）。
 *
 * ## 结构
 *
 * ```
 * ┌──────────────────────────────┐
 * │ 分享                           │
 * │  (◉)   (◉)   (◉)   (◉)        │  ← 横排圆形图标 + 名称
 * │ 微信  朋友圈  下载  复制链接     │
 * ├──────────────────────────────┤
 * │  ☆  取消收藏                   │  ← 操作行（左对齐）
 * ├──────────────────────────────┤
 * │            取消                │  ← 居中，关闭菜单
 * └──────────────────────────────┘
 * ```
 *
 * ## 为什么用 Dialog 而不是 ModalBottomSheet
 *
 * 与其它弹层一致（返回手势优先给最上层 Dialog）。
 * 且这里需要**自定义分区 + 分隔线**，Material 的 BottomSheet 反而更绕。
 *
 * ## 为什么分享渠道是"图标 + 名称"横排
 *
 * 官方就是这个形态：一屏内平铺所有渠道，比纵向列表省一半高度，
 * 且圆形彩底图标辨识度高。
 *
 * @param title 顶部标题（通常是视频标题，让用户确认操作对象）
 * @param onShareChannel 点击某个分享渠道。(渠道名)
 * @param onRemoveFavorite 取消收藏
 * @param extraActions 额外操作（如图片、稍后再看），追加在取消收藏之前
 */
@Composable
fun ItemMoreMenu(
    title: String,
    isFavorited: Boolean,
    onDismiss: () -> Unit,
    onShareChannel: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    extraActions: List<MoreMenuAction> = emptyList(),
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
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = Radius.panel, topEnd = Radius.panel))
                    // ⚠️ 弹层用 `surfaceElevated`（比卡片亮一档），不用 `bgCard`。
                    //
                    // 深色下投影不可见，分层**只能靠提亮**。弹层若与背景卡片
                    // 同色就"浮不起来"，看起来像页面里本来就有的一块。
                    .background(colors.surfaceElevated)
                    .clickable(enabled = false) {}
                    .navigationBarsPadding(),
            ) {
                // ---- 标题（截断，避免长标题撑高）----
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                    maxLines = 1,
                    modifier = Modifier.padding(
                        start = Space.x4,
                        end = Space.x4,
                        top = Space.x3,
                        bottom = Space.x2,
                    ),
                )

                // ---- 分享渠道横排 ----
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Space.x4),
                    horizontalArrangement = Arrangement.spacedBy(Space.x4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SHARE_ROW_HEIGHT),
                ) {
                    items(SHARE_CHANNELS, key = { it.label }) { ch ->
                        ShareChannelItem(
                            label = ch.label,
                            icon = ch.icon,
                            color = ch.color,
                            onClick = { onShareChannel(ch.label) },
                        )
                    }
                }

                Divider()

                // ---- 额外操作 ----
                extraActions.forEach { a ->
                    ActionRow(
                        icon = a.icon,
                        label = a.label,
                        tint = colors.textPrimary,
                        onClick = a.onClick,
                    )
                }

                // ---- 收藏 / 取消收藏 ----
                //
                // 用**文字**表达破坏性操作，而不是裸图标 ——
                // 「取消收藏」写清楚，避免误点。
                ActionRow(
                    // 已收藏时显示「取消收藏」，未收藏时显示「收藏」。
                    // 图标统一用轮廓星（填充星留给"已选中"的语义，
                    // 这里是"可执行的动作"，用轮廓更贴切）。
                    icon = Icons.Outlined.StarBorder,
                    label = if (isFavorited) "取消收藏" else "收藏",
                    tint = colors.textSecondarySafe,
                    onClick = onToggleFavorite,
                )

                Divider()

                // ---- 取消（关闭菜单）----
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(vertical = Space.x4),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.body,
                            color = colors.textPrimary,
                        ),
                    )
                }
            }
        }
    }
}

/** 额外操作项。 */
data class MoreMenuAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/** 分享渠道。 */
private data class ShareChannel(
    val label: String,
    val icon: ImageVector,
    val color: Color,
)

/**
 * 渠道图标用**统一的轮廓图标 + 各自品牌色圆底**。
 *
 * ⚠️ 不使用微信 / QQ 的官方 Logo —— 那些是注册商标，
 * 本项目「不使用 B 站及第三方专有素材」的合规边界要求自绘/通用图标。
 * 用通用图形 + 渠道名文字表达，功能等价且不侵权。
 *
 * ## 颜色来自令牌（v1.2.4）
 *
 * 这 4 个色值原先写死在本文件，`AGENTS.md` §11.1 记为待办：
 * 「可论证豁免，但需在代码里写明豁免理由」。
 * 现已上移到 `ColorTokens` 并附完整豁免说明 —— 它们是他方品牌真值，
 * 不随我们的主题变化，但仍集中定义以免散落。
 */
private val SHARE_CHANNELS = listOf(
    // 🔴 v1.5.3：新增「B站好友」并置首 ——
    //
    // 它是**唯一一个不需要第三方 App、不需要 SDK 就能真正完成分享**的渠道：
    // 复制链接 + 打开站内私信，用户自己选好友粘贴。
    //
    // 放在第一位是因为它**一定能用**；下面几个依赖对端 App 是否安装。
    ShareChannel("B站好友", Icons.Outlined.Email, DarkColors.channelCopyLink),
    ShareChannel("微信", Icons.Outlined.FavoriteBorder, DarkColors.channelWechat),
    ShareChannel("朋友圈", Icons.Outlined.QrCode, DarkColors.channelMoments),
    // ⚠️ 「下载分享」这个标签名容易被理解成"把视频下载下来"，
    // 实际是"把直链分享出去"。改名风险：老用户会找不到。
    // 折中：保留标签，但在渠道处理里明确它是"分享直链"（见 VideoDetailScreen）。
    ShareChannel("下载分享", Icons.Outlined.Download, DarkColors.channelDownload),
    ShareChannel("复制链接", Icons.Outlined.Share, DarkColors.channelCopyLink),
)

@Composable
private fun ShareChannelItem(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.interactive))
            .clickable(onClick = onClick)
            .padding(vertical = Space.x1),
    ) {
        Box(
            modifier = Modifier
                .size(SHARE_ICON_SIZE)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onOverlay,
                modifier = Modifier.size(Sizes.iconXl),
            )
        }
        Spacer(Modifier.height(Space.x1))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.badge,
                color = colors.textSecondarySafe,
            ),
            maxLines = 1,
        )
    }
}

/** 左对齐的操作行：图标 + 文字。 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x4, vertical = Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(Sizes.iconXl),
        )
        Spacer(Modifier.width(Space.x3))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                color = BiliTheme.colors.textPrimary,
            ),
        )
    }
}

/** 细分隔线。与主题的 borderHairline 一致。 */
@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BiliTheme.colors.borderHairline),
    )
}

private val SHARE_ICON_SIZE = 48.dp
private val SHARE_ROW_HEIGHT = 78.dp
