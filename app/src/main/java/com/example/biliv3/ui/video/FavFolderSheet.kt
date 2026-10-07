package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.biliv3.data.FavFolder
import com.example.biliv3.data.model.CoverUrls
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.TerminalLoadingState

/**
 * 收藏夹选择面板（v1.6.7）。
 *
 * ---
 *
 * # 为什么需要它（用户报告的真实问题）
 *
 * 原实现点「收藏」**直接收藏到默认夹**，用户没有任何选择权，
 * 也不知道收进了哪个夹。模拟器实测：
 * ```
 * 点收藏 -> POST fav/resource/deal -> code=0
 * ```
 * 成功了，但用户无从得知收去哪 —— 这是"功能可用但交互错误"。
 *
 * # 交互
 *
 * ```
 * ┌─────────────────────────────────┐
 * │ 收藏到收藏夹              [关闭] │
 * ├─────────────────────────────────┤
 * │ [封面] 默认收藏夹          12   ✓│   ← 勾选 = 已收藏
 * │ [封面] 游戏                38     │
 * │ [封面] 🔒 私密夹            3     │   ← 私密夹带锁标记
 * └─────────────────────────────────┘
 * ```
 *
 * 点一行 = 立即切换（收藏 / 取消收藏），**不需要"确定"按钮**。
 * 理由：B 站收藏夹是独立资源，逐项提交与"点一下勾一下"的心智一致；
 * 攒批反而会出现"关了面板没保存"的丢失感。
 *
 * # 设计语言
 *
 * 与 `ItemMoreMenu` / `LiveUserMenu` 同一套：
 * - 底部 `Dialog` + `surfaceElevated`（深色下靠提亮分层）
 * - **无卡片** —— 每一行是一行，不是一个小卡片
 * - 发丝线分隔（`RuleLine`）
 * - 圆角只用在封面缩略图与勾选圈
 */
@Composable
fun FavFolderSheet(
    state: VideoDetailViewModel.FavSheetState,
    onDismiss: () -> Unit,
    onToggle: (Long) -> Unit,
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
                    .background(colors.surfaceElevated)
                    .clickable(enabled = false) {}
                    .navigationBarsPadding()
                    .heightIn(max = SHEET_MAX_H),
            ) {
                // ---- 标题 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4, vertical = Space.x3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "收藏到收藏夹",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = FontSize.titleMd,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "完成",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            fontWeight = FontWeight.Medium,
                            color = colors.textBrandSafe,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.interactive))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = Space.x3, vertical = Space.x2),
                    )
                }

                RuleLine(color = Rule.subtle)

                when {
                    state.loading -> TerminalLoadingState(
                        text = "正在读取收藏夹…",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // ---- 列表拿不到 → 如实报错（不显示空列表）----
                    state.error != null && state.folders.isEmpty() -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Space.x4),
                    ) {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = FontSize.body,
                                lineHeight = FontSize.bodyLine,
                                color = colors.textSecondarySafe,
                            ),
                        )
                    }

                    state.folders.isEmpty() -> Text(
                        text = "还没有收藏夹。可以先在「我的 → 收藏」里创建一个。",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            lineHeight = FontSize.bodySmLine,
                            color = colors.textTertiary,
                        ),
                        modifier = Modifier.padding(Space.x4),
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        // ---- 勾选态不可信时的说明 ----
                        //
                        // ⚠️ `fav/resource/ids` 失败时 `selected` 是空的，
                        //    但那是"查不到"不是"确认未收藏"。
                        //    必须说明 —— 否则用户会以为自己没收藏过，
                        //    点一下反而取消/重复收藏。
                        if (state.selectionUnknown) {
                            item(key = "unknown-hint") {
                                Text(
                                    text = "⚠️ 无法读取当前收藏状态，下方勾选可能不准确",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = FontSize.badge,
                                        lineHeight = FontSize.labelLine,
                                        color = colors.accentCoin,
                                    ),
                                    modifier = Modifier.padding(
                                        start = Space.x4,
                                        end = Space.x4,
                                        top = Space.x3,
                                    ),
                                )
                            }
                        }

                        // ---- 逐项失败提示（写失败时显示，不吞）----
                        state.error?.let { err ->
                            item(key = "err") {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = FontSize.badge,
                                        lineHeight = FontSize.labelLine,
                                        color = colors.stateError,
                                    ),
                                    modifier = Modifier.padding(
                                        start = Space.x4,
                                        end = Space.x4,
                                        top = Space.x3,
                                    ),
                                )
                            }
                        }

                        items(state.folders, key = { it.id }) { folder ->
                            FavFolderRow(
                                folder = folder,
                                checked = folder.id in state.selected,
                                busy = state.busyFolderId == folder.id,
                                onClick = { onToggle(folder.id) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Space.x2))
            }
        }
    }
}

/**
 * 一行收藏夹。
 *
 * ## 为什么左侧有封面
 *
 * 收藏夹名字可能重名（"默认收藏夹"、"收藏夹 1"），封面是**最快区分**
 * 的视觉线索。没有封面时用占位底色，不留空洞。
 *
 * ## 勾选态的双重表达
 *
 * 「品牌色勾选圈 + 对勾图标」—— 不只靠颜色（色弱用户也能分辨）。
 */
@Composable
private fun FavFolderRow(
    folder: FavFolder,
    checked: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Space.minTouchTarget)
            // 写入进行中禁用点击（防连点导致 add/del 乱序）
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = Space.x4, vertical = Space.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---- 封面 ----
        AsyncImage(
            model = CoverUrls.normalize(folder.cover),
            contentDescription = null,
            modifier = Modifier
                .size(FOLDER_COVER)
                .clip(RoundedCornerShape(Radius.badge))
                .background(colors.bgHover),
        )
        Spacer(Modifier.width(Space.x3))

        // ---- 名称 + 数量 ----
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 私密夹标记：它是**真实的**状态差异，不是装饰
                if (folder.isPrivate) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "私密收藏夹",
                        tint = colors.textTertiary,
                        modifier = Modifier.size(Sizes.iconSm),
                    )
                    Spacer(Modifier.width(Space.x1))
                }
                Text(
                    text = folder.title.ifEmpty { "未命名收藏夹" },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
                        color = colors.textPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(1.dp))
            Text(
                text = if (busy) "正在保存…" else "${folder.mediaCount} 个内容",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    color = if (busy) colors.textBrandSafe else colors.textTertiary,
                ),
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(Space.x2))

        // ---- 勾选圈 ----
        Box(
            modifier = Modifier
                .size(Sizes.iconXl)
                .clip(CircleShape)
                .then(
                    if (checked) {
                        Modifier.background(colors.brandPrimary)
                    } else {
                        Modifier.border(1.5.dp, colors.borderStrong, CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已收藏",
                    tint = colors.textOnBrand,
                    modifier = Modifier.size(Sizes.iconSm),
                )
            }
        }
    }
}

/** 封面缩略图尺寸。 */
private val FOLDER_COVER = 44.dp

/** 面板高度上限（避免长列表顶出屏幕）。 */
private val SHEET_MAX_H = 520.dp
