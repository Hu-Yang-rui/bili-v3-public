package com.example.biliv3.ui.live

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.biliv3.data.meme.Meme
import com.example.biliv3.data.meme.MemeSearch
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 烂梗库弹层（v1.6.5）。
 *
 * ---
 *
 * # 来源标注
 *
 * 顶部常驻一行说明：内容为**本项目整理**，不是 B 站官方内容。
 *
 * ## 每个条目两个动作
 *
 * | 动作 | 行为 |
 * |---|---|
 * | 点条目本体 | **填入输入框**（不发送） |
 * | 点「复制」 | 复制到剪贴板 |
 * | 点「发送」 | **仅在有权限时出现**，走现有发送流程 |
 *
 * ⚠️ **没有"全部发送"这类批量动作** —— 需求明确要求
 * "不要自动连续发送大量内容，避免形成刷屏行为"。
 */
@Composable
fun MemeLibrarySheet(
    memes: List<Meme>,
    /**
     * 能否直接发送（已登录 + 在直播间内）。
     *
     * `false` 时**不渲染"发送"按钮** —— 而不是渲染一个点了报错的按钮。
     */
    canSend: Boolean,
    /** 是否有发送在途（防连点）。 */
    sending: Boolean,
    onDismiss: () -> Unit,
    /** 填入输入框（不发送）。 */
    onPick: (String) -> Unit,
    /** 复制到剪贴板。 */
    onCopy: (String) -> Unit,
    /** 直接发送（走现有发送流程）。 */
    onSend: (String) -> Unit,
) {
    val colors = BiliTheme.colors
    var query by remember { mutableStateOf("") }

    // 搜索 + 分组（纯函数，已单测）
    val filtered = remember(memes, query) { MemeSearch.search(memes, query) }
    val grouped = remember(filtered) { MemeSearch.group(filtered) }

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
                    // v1.6.6：底部弹层补导航栏避让（否则最后一行被手势条压住）
                    .navigationBarsPadding()
                    .heightIn(max = SHEET_MAX_H),
            ) {
                // ---- 标题栏 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4, vertical = Space.x3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "烂梗库",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = FontSize.titleMd,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(Sizes.iconXl + Space.x2)
                            .clip(RoundedCornerShape(Radius.interactive))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = colors.textSecondarySafe,
                            modifier = Modifier.size(Sizes.iconLg),
                        )
                    }
                }

                // ---- 来源说明（常驻，不可关闭）----
                Text(
                    text = "内容为本项目整理的常用直播用语，不是 B 站官方内容",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        lineHeight = FontSize.labelLine,
                        color = colors.textTertiary,
                    ),
                    modifier = Modifier.padding(
                        start = Space.x4,
                        end = Space.x4,
                        bottom = Space.x2,
                    ),
                )

                // ---- 搜索框 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4, vertical = Space.x2)
                        .clip(RoundedCornerShape(Radius.interactive))
                        .background(colors.bgHover)
                        .padding(horizontal = Space.x3, vertical = Space.x2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(Sizes.iconSm),
                    )
                    Spacer(Modifier.width(Space.x2))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索梗或分类",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = FontSize.bodySm,
                                    color = colors.textTertiary,
                                ),
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = FontSize.bodySm,
                                color = colors.textPrimary,
                            ),
                            cursorBrush = SolidColor(colors.brandPrimary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Text(
                            text = "清空",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = FontSize.badge,
                                color = colors.textSecondarySafe,
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.badge))
                                .clickable { query = "" }
                                .padding(horizontal = Space.x2, vertical = Space.tagVertical),
                        )
                    }
                }

                RuleLine(color = Rule.subtle)

                // ---- 列表 ----
                if (grouped.isEmpty()) {
                    Text(
                        text = "没有匹配的梗",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.textTertiary,
                        ),
                        modifier = Modifier.padding(Space.x4),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        grouped.forEach { cat ->
                            item(key = "cat-${cat.name}") {
                                Text(
                                    text = "${cat.name}（${cat.count}）",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = FontSize.badge,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textTertiary,
                                    ),
                                    modifier = Modifier.padding(
                                        start = Space.x4,
                                        end = Space.x4,
                                        top = Space.x3,
                                        bottom = Space.compactVertical,
                                    ),
                                )
                            }
                            items(cat.memes, key = { it.key }) { meme ->
                                MemeRow(
                                    meme = meme,
                                    canSend = canSend,
                                    sending = sending,
                                    onPick = { onPick(meme.text) },
                                    onCopy = { onCopy(meme.text) },
                                    onSend = { onSend(meme.text) },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Space.x2))
            }
        }
    }
}

/**
 * 一行梗。
 *
 * ## 布局
 *
 * ```
 * ┌────────────────────────────────────────┐
 * │ 哈哈哈哈哈                              │
 * │ 情绪 · 备注…          [复制] [发送]     │
 * └────────────────────────────────────────┘
 * ```
 *
 * 点**行本体** = 填入输入框（最常用动作，给最大的热区）；
 * 「复制」「发送」是右侧的小按钮（次要动作）。
 *
 * ⚠️ `canSend = false` 时**不渲染**「发送」—— 不显示灰按钮，
 * 因为"再满足某个条件就能用"对未登录用户不成立。
 */
@Composable
private fun MemeRow(
    meme: Meme,
    canSend: Boolean,
    sending: Boolean,
    onPick: () -> Unit,
    onCopy: () -> Unit,
    onSend: () -> Unit,
) {
    val colors = BiliTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Space.minTouchTarget)
            .clickable(onClick = onPick)
            .padding(horizontal = Space.x4, vertical = Space.compactVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = meme.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meme.note.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = meme.note,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.textTertiary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(Space.x2))

        ActionChip(label = "复制", enabled = true, onClick = onCopy)

        // 只有能发的时候才出现 —— 不显示灰按钮
        if (canSend) {
            Spacer(Modifier.width(Space.x1))
            ActionChip(
                label = "发送",
                // 发送在途时禁用（防连点）
                enabled = !sending,
                highlight = true,
                onClick = onSend,
            )
        }
    }
}

/** 行内小动作按钮。 */
@Composable
private fun ActionChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    val colors = BiliTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.badge,
            fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal,
            color = when {
                !enabled -> colors.textTertiary
                highlight -> colors.textBrandSafe
                else -> colors.textSecondarySafe
            },
        ),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.badge))
            .background(colors.bgHover)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.x2, vertical = Space.tagVertical),
    )
}

/** 弹层高度上限（与用户菜单同一取值思路）。 */
private val SHEET_MAX_H = 520.dp
