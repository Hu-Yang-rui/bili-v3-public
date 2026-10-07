package com.example.biliv3.ui.skin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.biliv3.data.skin.FakeSkin
import com.example.biliv3.data.skin.SkinResource
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 本地装扮页（Fake Skin，**未发版**）。
 *
 * ---
 *
 * # 定位（任务书第二十七条）
 *
 * UI 上写「本地装扮 / Fake Skin」，**不声称**是官方装扮系统。
 * 它是**本地第三方客户端的装扮兼容 / 展示**功能。
 *
 * # 无卡片设计（任务书第十五条）
 *
 * 任务书明确要求"不要做成一堆巨大 Card"。
 * 这里用：
 * - **直角预览图**（任务书允许）
 * - 行与行之间用**留白**而不是卡片边框
 * - 发丝线只在分组处
 *
 * # 资源缺失时用默认（任务书第十条）
 *
 * 预览图没有就不显示占位框（不显示空白），
 * 只显示名称 —— 而不是画一个灰框。
 */
@Composable
fun SkinScreen(
    installed: List<FakeSkin>,
    currentId: String,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onApply: (String) -> Unit,
    onRestoreDefault: () -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    /** 读预览图字节（内置从 assets、导入从 files）。null = 没有。 */
    previewOf: (FakeSkin) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.x2, vertical = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(RoundedCornerShape(Radius.interactive))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(Sizes.iconLg),
                )
            }
            Spacer(Modifier.width(Space.x1))
            Text(
                text = "本地装扮",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
        }

        RuleLine(color = Rule.subtle)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ---- 说明：**明确不是官方系统** ----
            item(key = "hint") {
                Text(
                    text = "Fake Skin —— 只改变本 App 的本地外观，" +
                        "不会修改你的 Bilibili 账号装扮。",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        lineHeight = FontSize.labelLine,
                        color = colors.textTertiary,
                    ),
                    modifier = Modifier.padding(
                        start = Space.x4,
                        end = Space.x4,
                        top = Space.x3,
                        bottom = Space.x2,
                    ),
                )
            }

            // ---- 恢复默认 ----
            item(key = "default") {
                SkinRow(
                    name = "默认",
                    isCurrent = currentId.isEmpty(),
                    isDefaultRow = true,
                    preview = null,
                    onApply = onRestoreDefault,
                    onRestoreDefault = onRestoreDefault,
                    onDelete = null,
                )
            }

            // ---- 已安装 ----
            if (installed.isNotEmpty()) {
                item(key = "sep-installed") {
                    Text(
                        text = "已安装（${installed.size}）",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
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
                items(installed, key = { it.id }) { s ->
                    SkinRow(
                        name = s.name,
                        isCurrent = s.id == currentId,
                        isDefaultRow = false,
                        // 🔴 **不要**在这里 `remember(s.id) { previewOf(s) }`。
                        //
                        // 预览图是**异步**加载的（调用方在 LaunchedEffect 里读 IO），
                        // 所以首帧 `previewOf` 返回 null。用 `remember(s.id)` 会把
                        // **那个 null 缓存住** —— 等图加载好，key 没变，
                        // remember 不会重算，预览图**永远不显示**。
                        //
                        // 实测症状：列表正常、名称正常、就是没有预览图。
                        //
                        // 直接读（`previewOf` 是纯内存查表，开销可忽略）。
                        preview = previewOf(s),
                        onApply = { onApply(s.id) },
                        onRestoreDefault = onRestoreDefault,
                        // 内置的不可删（它们随 APK，删了也没意义）
                        onDelete = if (s.source ==
                            com.example.biliv3.data.skin.SkinSource.Imported
                        ) {
                            { onDelete(s.id) }
                        } else {
                            null
                        },
                    )
                }
            }

            // ---- 空态 / 加载 / 错误 ----
            if (loading) {
                item(key = "loading") {
                    Text(
                        text = "正在读取装扮…",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.textTertiary,
                        ),
                        modifier = Modifier.padding(Space.x4),
                    )
                }
            }
            if (error != null) {
                item(key = "error") {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.accentCoin,
                        ),
                        modifier = Modifier.padding(
                            horizontal = Space.x4,
                            vertical = Space.x2,
                        ),
                    )
                }
            }

            // ---- 导入 ----
            item(key = "import") {
                Text(
                    text = "导入装扮",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.brandPrimary,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onImport)
                        .padding(horizontal = Space.x4, vertical = Space.rowVertical),
                )
            }

            item(key = "bottom") {
                Spacer(
                    Modifier
                        .height(Space.x8)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

/**
 * 一行装扮。
 *
 * ## 无卡片
 *
 * 预览图是**直角**的（任务书允许），行内不加边框、
 * 不加色底 —— 靠留白与发丝线区分。
 */
@Composable
private fun SkinRow(
    name: String,
    isCurrent: Boolean,
    isDefaultRow: Boolean,
    preview: ByteArray?,
    onApply: () -> Unit,
    /**
     * 恢复默认（**只对"当前已使用"的非默认行**显示）。
     *
     * ⚠️ 与 [onApply] **必须是两个不同的动作** ——
     * 见本函数内「恢复默认」按钮处的说明。
     */
    onRestoreDefault: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = BiliTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x4, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---- 预览图（直角）----
        //
        // ⚠️ 没有预览图时**不画占位灰框**（任务书第十条：不显示空白），
        //    直接不占位 —— 行会靠左，看起来仍然整齐。
        if (preview != null && preview.isNotEmpty()) {
            val bmp = remember(preview) {
                runCatching {
                    android.graphics.BitmapFactory
                        .decodeByteArray(preview, 0, preview.size)
                }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bitmap = remember(bmp) { bmp.asImageBitmap() },
                    contentDescription = "$name 预览",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(PREVIEW_W)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(Radius.badge))
                        .background(colors.bgHover),
                )
                Spacer(Modifier.width(Space.x3))
            }
        }

        // ---- 名称 + 状态 ----
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    color = if (isCurrent) colors.brandPrimary else colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCurrent) {
                Spacer(Modifier.height(Space.micro))
                Text(
                    text = "已使用",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.badge,
                        color = colors.brandPrimary,
                    ),
                )
            }
        }

        // ---- 动作 ----
        if (!isCurrent) {
            Text(
                text = "使用",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.brandPrimary,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.interactive))
                    .clickable(onClick = onApply)
                    .padding(horizontal = Space.x3, vertical = Space.x2),
            )
        } else if (!isDefaultRow) {
            // 🔴 「恢复默认」**必须调 onRestoreDefault**，不能调 onApply。
            //
            // 实测踩过：这里原本绑的是 `onApply`（= 应用**这一套**装扮），
            // 所以对"当前已使用"的那行点「恢复默认」，等于**再应用一次同一套**
            // —— 点了没反应，看起来像按钮坏了。
            Text(
                text = "恢复默认",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    color = colors.textSecondarySafe,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.interactive))
                    .clickable(onClick = onRestoreDefault)
                    .padding(horizontal = Space.x3, vertical = Space.x2),
            )
        }

        // ---- 删除（只对导入的）----
        if (onDelete != null) {
            Spacer(Modifier.width(Space.x1))
            Text(
                text = "删除",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    color = colors.textTertiary,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.interactive))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = Space.x2, vertical = Space.x2),
            )
        }
    }
}

/** 预览图宽度（16:9 直角图）。 */
private val PREVIEW_W = 112.dp
