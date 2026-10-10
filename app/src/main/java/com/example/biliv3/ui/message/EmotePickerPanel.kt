package com.example.biliv3.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.biliv3.data.emote.Emote
import com.example.biliv3.data.emote.EmotePackage
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Type

/**
 * 表情选择面板（**未发版**）。
 *
 * ---
 *
 * # 🔴 选中后写入的是**官方 token**，不是表情名
 *
 * 实测：表情的 `text` 字段就是发送用的内联 token（如 `[doge_金箍]`）。
 * 选中后把它**原样插进输入框** —— 服务端靠这个 token 识别表情。
 *
 * 任务书明确禁止「只把表情名称插入文本」（那发出去就是普通文字）。
 * 本面板的 `onPick` 传的就是 `emote.token`。
 *
 * # 三类表情（实测）
 *
 * | type | 数量 | 渲染 |
 * |---|---|---|
 * | `1` 图片 | 359 | 加载 PNG |
 * | `4` 颜文字 | 52 | **直接显示文字**（它的 url 就是文字本身，加载会 404）|
 * | `3` 收藏集 | 20 | 加载 PNG，但**未解锁**（`no_access`）→ 标灰不可点 |
 *
 * # 设计语言
 *
 * 与 `MemeLibrarySheet` / `FavFolderSheet` 同一套：底部面板 +
 * `surfaceElevated` + **无卡片** + 发丝线分隔 + 分类用 `SectionMark` 式小标题。
 */
@Composable
fun EmotePickerPanel(
    packages: List<EmotePackage>,
    /** 加载中（显示骨架提示而不是空面板）。 */
    loading: Boolean,
    /** 失败/降级原因（null = 正常）。**不影响已有列表可用**。 */
    error: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors
    var query by remember { mutableStateOf("") }

    // 搜索：跨全部包（用户不记得在哪个包里）
    val filtered = remember(packages, query) {
        if (query.isBlank()) {
            packages
        } else {
            packages.mapNotNull { p ->
                val hit = p.emotes.filter { it.matches(query) }
                if (hit.isEmpty()) null else p.copy(emotes = hit)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = PANEL_MAX_H)
            .clip(RoundedCornerShape(topStart = V3Radius.lg, topEnd = V3Radius.lg))
            .background(colors.bgSecondaryElevated)
            .navigationBarsPadding(),
    ) {
        // ---- 标题 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V3Space.md, vertical = V3Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "表情",
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "收起",
                style = V3Type.caption1.copy(
                    color = colors.labelSecondary,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(V3Radius.xs))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
            )
        }

        // ---- 搜索 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V3Space.md)
                .clip(RoundedCornerShape(V3Radius.xs))
                .background(colors.bgTertiary)
                .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索表情（支持别名与包名）",
                    style = V3Type.footnote.copy(
                        color = colors.labelTertiary,
                    ),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = V3Type.footnote.copy(
                    color = colors.labelPrimary,
                ),
                cursorBrush = SolidColor(colors.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- 失败/降级提示（不阻断使用已有列表）----
        error?.let {
            Text(
                text = it,
                style = V3Type.caption2.copy(lineHeight = V3Type.caption1.lineHeight,
                    color = colors.accentCoin,
                ),
                modifier = Modifier.padding(
                    start = V3Space.md,
                    end = V3Space.md,
                    top = V3Space.xs,
                ),
            )
        }

        RuleLine(
            color = Rule.subtle,
            modifier = Modifier.padding(top = V3Space.xs),
        )

        when {
            loading && packages.isEmpty() -> Text(
                text = "正在加载表情…",
                style = V3Type.footnote.copy(
                    color = colors.labelTertiary,
                ),
                modifier = Modifier.padding(V3Space.md),
            )

            filtered.isEmpty() -> Text(
                text = if (query.isBlank()) "没有可用表情" else "没有匹配的表情",
                style = V3Type.footnote.copy(
                    color = colors.labelTertiary,
                ),
                modifier = Modifier.padding(V3Space.md),
            )

            else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                filtered.forEach { pkg ->
                    item(key = "pkg-${pkg.id}") {
                        Text(
                            text = "${pkg.name}（${pkg.emotes.size}）",
                            style = V3Type.caption2.copy(
                                fontWeight = FontWeight.Medium,
                                color = colors.labelTertiary,
                            ),
                            modifier = Modifier.padding(
                                start = V3Space.md,
                                end = V3Space.md,
                                top = V3Space.sm,
                                bottom = V3Space.hairline,
                            ),
                        )
                    }
                    item(key = "grid-${pkg.id}") {
                        EmoteGrid(pkg.emotes, onPick)
                    }
                }
            }
        }

        Spacer(Modifier.height(V3Space.xs))
    }
}

/**
 * 一个包的表情网格。
 *
 * ## 为什么用网格而不是列表
 *
 * 表情是**视觉**选择 —— 一行一个会让人翻很久（431 个！）。
 * 网格一屏能看到几十个，符合"看图选"的心智。
 */
@Composable
private fun EmoteGrid(emotes: List<Emote>, onPick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 网格在 LazyColumn 里必须给定高度 ——
            //    否则嵌套滚动测量会拿到无限高约束（§7.24-150 同类问题）。
            //    按行数算一个确定高度。
            .height(gridHeight(emotes.size))
            .padding(horizontal = V3Space.xs),
    ) {
        items(emotes, key = { it.token }) { e ->
            EmoteCell(e, onPick)
        }
    }
}

/** 网格高度 = 行数 × 单元高（留一点余量避免最后一行被裁）。 */
private fun gridHeight(count: Int) =
    (CELL + V3Space.xxs) * ((count + GRID_COLUMNS - 1) / GRID_COLUMNS) + V3Space.xs

/**
 * 单个表情格。
 *
 * ## 三类渲染
 *
 * - **图片**：`AsyncImage`
 * - **颜文字**：直接显示文字（它的 `url` 就是文字，当图片加载会 404）
 * - **未解锁收藏集**：标灰 + **不可点**（点了服务端会拒，不如不给入口）
 */
@Composable
private fun EmoteCell(e: Emote, onPick: (String) -> Unit) {
    val colors = BiliV3.colors

    Box(
        modifier = Modifier
            .size(CELL)
            .clip(RoundedCornerShape(V3Radius.xs))
            // 未解锁：不挂 clickable（**死入口**问题）
            .then(
                if (e.usable) {
                    Modifier.clickable { onPick(e.token) }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 颜文字：直接显示文字
            e.type == Emote.TYPE_KAOMOJI || !e.isImage -> Text(
                text = e.token,
                style = V3Type.caption2.copy(
                    color = if (e.usable) colors.labelSecondary else colors.labelTertiary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = V3Space.hairline),
            )

            else -> AsyncImage(
                model = e.url,
                contentDescription = e.alias.ifEmpty { e.token },
                contentScale = ContentScale.Fit,
                // 未解锁：降透明度表示"看得到但用不了"
                alpha = if (e.usable) 1f else 0.35f,
                modifier = Modifier
                    .size(EMOTE_IMG)
                    .clip(RoundedCornerShape(V3Radius.xs)),
            )
        }

        // 未解锁角标：一小段描边，表明"这个不可用"
        if (!e.usable) {
            Box(
                modifier = Modifier
                    .size(CELL)
                    .border(
                        1.dp,
                        colors.labelTertiary.copy(alpha = 0.4f),
                        RoundedCornerShape(V3Radius.xs),
                    ),
            )
        }
    }
}

/** 网格列数。 */
private const val GRID_COLUMNS = 7

/** 单元格尺寸。 */
private val CELL = 46.dp

/** 表情图片尺寸（略小于格子，留出点击余量）。 */
private val EMOTE_IMG = 34.dp

/** 面板高度上限。 */
private val PANEL_MAX_H = 300.dp
