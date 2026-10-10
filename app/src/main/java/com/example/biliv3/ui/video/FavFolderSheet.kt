package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.TerminalLoadingState
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type
import com.example.biliv3.design.v3.v3GlassSurface
import com.example.biliv3.design.v3.V3Glass

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
    val colors = BiliV3.colors

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
                .background(colors.scrim)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 🔴 v3：面板材质从**实心深灰**改为 Liquid Glass。
                    //
                    // ⚠️ 用 `Modifier.v3GlassSurface` 而不是 `GlassSurface` 容器 ——
                    //    前者是**一个表达式替换**，**不动任何花括号**。
                    //    容器形式要在几百行深的树里配一对括号，实测改坏过两次。
                    //
                    // 判据（§7.37 坑 219）：**模糊玻璃适合「大面积、静态、
                    // 内容之上」的浮层** —— 弹层三条全中。
                    // ⚠️ 只圆上两角（底部贴屏幕边），与 `GlassSheet` 一致。
                    .v3GlassSurface(
                        shape = RoundedCornerShape(
                            topStart = V3Radius.sheet,
                            topEnd = V3Radius.sheet,
                        ),
                        level = V3Glass.Level.UltraThin,
                    )
                    .clickable(enabled = false) {}
                    .navigationBarsPadding()
                    .heightIn(max = SHEET_MAX_H),
            ) {
                // ---- 标题 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = V3Space.md, vertical = V3Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "收藏到收藏夹",
                        style = V3Type.subheadline.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.labelPrimary,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "完成",
                        style = V3Type.caption1.copy(
                            fontWeight = FontWeight.Medium,
                            color = colors.brandBiliText,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(V3Radius.xs))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
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
                            .padding(V3Space.md),
                    ) {
                        Text(
                            text = state.error,
                            style = V3Type.callout.copy(
                                color = colors.labelSecondary,
                            ),
                        )
                    }

                    state.folders.isEmpty() -> Text(
                        text = "还没有收藏夹。可以先在「我的 → 收藏」里创建一个。",
                        style = V3Type.footnote.copy(
                            color = colors.labelTertiary,
                        ),
                        modifier = Modifier.padding(V3Space.md),
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
                                    style = V3Type.caption2.copy(lineHeight = V3Type.caption1.lineHeight,
                                        color = colors.accentCoin,
                                    ),
                                    modifier = Modifier.padding(
                                        start = V3Space.md,
                                        end = V3Space.md,
                                        top = V3Space.sm,
                                    ),
                                )
                            }
                        }

                        // ---- 逐项失败提示（写失败时显示，不吞）----
                        state.error?.let { err ->
                            item(key = "err") {
                                Text(
                                    text = err,
                                    style = V3Type.caption2.copy(lineHeight = V3Type.caption1.lineHeight,
                                        color = colors.stateError,
                                    ),
                                    modifier = Modifier.padding(
                                        start = V3Space.md,
                                        end = V3Space.md,
                                        top = V3Space.sm,
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

                Spacer(Modifier.height(V3Space.xs))
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
    val colors = BiliV3.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = V3Size.touchMin)
            // 写入进行中禁用点击（防连点导致 add/del 乱序）
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = V3Space.md, vertical = V3Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---- 封面 ----
        AsyncImage(
            model = CoverUrls.normalize(folder.cover),
            contentDescription = null,
            modifier = Modifier
                .size(FOLDER_COVER)
                .clip(RoundedCornerShape(V3Radius.xs))
                .background(colors.bgTertiary),
        )
        Spacer(Modifier.width(V3Space.sm))

        // ---- 名称 + 数量 ----
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 私密夹标记：它是**真实的**状态差异，不是装饰
                if (folder.isPrivate) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "私密收藏夹",
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(V3Size.iconXs),
                    )
                    Spacer(Modifier.width(V3Space.xxs))
                }
                Text(
                    text = folder.title.ifEmpty { "未命名收藏夹" },
                    style = V3Type.callout.copy(
                        fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
                        color = colors.labelPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(1.dp))
            Text(
                text = if (busy) "正在保存…" else "${folder.mediaCount} 个内容",
                style = V3Type.caption2.copy(
                    color = if (busy) colors.brandBiliText else colors.labelTertiary,
                ),
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(V3Space.xs))

        // ---- 勾选圈 ----
        Box(
            modifier = Modifier
                .size(V3Size.iconLg)
                .clip(CircleShape)
                .then(
                    if (checked) {
                        Modifier.background(colors.brand)
                    } else {
                        Modifier.border(1.5.dp, colors.separatorOpaque, CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已收藏",
                    tint = colors.labelOnBrand,
                    modifier = Modifier.size(V3Size.iconXs),
                )
            }
        }
    }
}

/** 封面缩略图尺寸。 */
private val FOLDER_COVER = 44.dp

/** 面板高度上限（避免长列表顶出屏幕）。 */
private val SHEET_MAX_H = 520.dp
