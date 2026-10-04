package com.example.biliv3.ui.plugin

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.plugin.PluginPackagePreview
import com.example.biliv3.plugin.RiskLevel
import com.example.biliv3.plugin.SignatureStatus

/**
 * 插件包预览确认框（v1.3.0）。
 *
 * ## 这是「安全识别」的最后一道闸
 *
 * 任务书 §13 要求的链路是：
 * **扫描 → 解析 → 展示权限 → 兼容性 → 用户确认 → 安装**。
 *
 * 前四步在 `PluginPackageParser` 里做完了（而且**不解压落盘**，
 * 天然免疫 Zip Slip），但**没有这一步的话前四步全是白做的** ——
 * 用户看不到权限清单就被装了插件。
 *
 * ## 三条硬规则
 *
 * 1. **不可信的东西必须显式标出来** —— 未签名、含原生代码、
 *    请求高风险权限，各占一行且用醒目色，不能折叠在详情里。
 * 2. **未知权限名要显示**（不是忽略）—— 忽略等于给了插件一个
 *    我们没审查过的能力。`PluginPermission.from` 返回 null 时
 *    解析器会记进 `ruleErrors`，这里必须展示。
 * 3. **兼容性要提前拦** —— 插件声明 `appVersion` 与本机不符时
 *    警告，但不阻止（用户可能有理由继续）。
 */
@Composable
fun PluginPreviewDialog(
    preview: PluginPackagePreview,
    source: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = BiliTheme.colors
    val meta = preview.metadata

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
                    .padding(horizontal = Space.x4)
                    .fillMaxWidth()
                    .biliCard(
                        shape = RoundedCornerShape(Radius.panel),
                        color = colors.surfaceElevated,
                    )
                    // 弹层本体不穿透到遮罩
                    .clickable(enabled = false) {}
                    .padding(vertical = Space.x4),
            ) {
                // ---- 标题 ----
                Text(
                    text = if (preview.valid) "安装插件？" else "无法安装",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = FontSize.titleMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                    modifier = Modifier.padding(horizontal = Space.x5),
                )
                Spacer(Modifier.height(Space.x1))
                Text(
                    text = source,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.bodySm,
                        color = colors.textTertiary,
                    ),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = Space.x5),
                )

                Spacer(Modifier.height(Space.x3))

                // ---- 正文（可滚动：权限可能十几条）----
                Column(
                    modifier = Modifier
                        .heightIn(max = Space.x12 * 9)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Space.x5),
                ) {
                    if (!preview.valid) {
                        // 解析失败：只显示原因，不给安装按钮
                        PreviewRow("失败原因", preview.error ?: "未知错误", warn = true)
                    } else {
                        if (meta != null) {
                            PreviewRow("名称", meta.name)
                            PreviewRow("标识", meta.id)
                            PreviewRow("版本", meta.version)
                            if (meta.author.isNotEmpty()) PreviewRow("作者", meta.author)
                            if (meta.description.isNotEmpty()) {
                                PreviewRow("说明", meta.description)
                            }
                            PreviewRow("类型", typeLabel(meta.type))
                        }

                        if (preview.ruleCount > 0) {
                            PreviewRow("规则条数", "${preview.ruleCount}")
                        }

                        // ---- 兼容性 ----
                        val compat = meta?.compatibleAppVersion.orEmpty()
                        if (compat.isNotEmpty()) {
                            PreviewRow(
                                label = "要求 App 版本",
                                value = compat,
                                warn = !compatibleWith(compat),
                            )
                        }

                        // ---- 权限清单（核心）----
                        Spacer(Modifier.height(Space.x3))
                        SectionTitle("请求的权限")
                        val perms = meta?.permissions?.toList().orEmpty()
                        if (perms.isEmpty()) {
                            Text(
                                text = "未请求任何权限",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = FontSize.bodySm,
                                    color = colors.textSecondarySafe,
                                ),
                            )
                        } else {
                            perms.sortedByDescending { it.level.ordinal }.forEach { p ->
                                Column(Modifier.padding(vertical = Space.x1)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = p.displayName,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = FontSize.bodySm,
                                                fontWeight = FontWeight.Medium,
                                                color = colors.textPrimary,
                                            ),
                                        )
                                        Spacer(Modifier.width(Space.x2))
                                        Text(
                                            text = levelLabel(p.level),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = FontSize.label,
                                                color = levelColor(p.level, colors),
                                            ),
                                        )
                                    }
                                    Text(
                                        text = p.description,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = FontSize.label,
                                            color = colors.textTertiary,
                                        ),
                                    )
                                }
                            }
                        }

                        // ---- 风险 ----
                        Spacer(Modifier.height(Space.x3))
                        SectionTitle("风险")
                        PreviewRow(
                            label = "等级",
                            value = riskLabel(preview.riskLevel),
                            warn = preview.riskLevel != RiskLevel.LOW,
                        )
                        preview.riskReasons.forEach { r ->
                            BulletLine(r, warn = true)
                        }

                        // ---- 规则/权限解析问题 ----
                        if (preview.ruleErrors.isNotEmpty()) {
                            Spacer(Modifier.height(Space.x3))
                            SectionTitle("解析问题")
                            preview.ruleErrors.forEach { e -> BulletLine(e, warn = true) }
                        }

                        // ---- 包内文件 ----
                        if (preview.entries.isNotEmpty()) {
                            Spacer(Modifier.height(Space.x3))
                            SectionTitle("包内文件（${preview.entries.size}）")
                            preview.entries.take(20).forEach { e ->
                                BulletLine("${e.path}  ${formatSize(e.sizeBytes)}", warn = false)
                            }
                            if (preview.entries.size > 20) {
                                Text(
                                    text = "…还有 ${preview.entries.size - 20} 个",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = FontSize.label,
                                        color = colors.textTertiary,
                                    ),
                                )
                            }
                        }

                        // ---- 签名 ----
                        if (preview.signature == SignatureStatus.NONE) {
                            Spacer(Modifier.height(Space.x3))
                            Text(
                                text = "⚠️ 此包未签名，无法验证来源与完整性。只安装你信任来源的插件。",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = FontSize.label,
                                    color = colors.brandPrimary,
                                ),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Space.x3))

                // ---- 操作 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = colors.textSecondary)
                    }
                    // 解析失败时**不给**安装按钮 —— 装了也跑不起来
                    if (preview.valid) {
                        TextButton(onClick = onConfirm) {
                            Text(
                                text = "安装",
                                color = colors.brandPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = BiliTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.label,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .ruleBottom(color = Rule.color)
            .padding(vertical = Space.x1),
    )
}

@Composable
private fun PreviewRow(label: String, value: String, warn: Boolean = false) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.x1),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.bodySm,
                color = colors.textTertiary,
            ),
            modifier = Modifier.width(Space.x12 * 2),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.bodySm,
                color = if (warn) colors.brandPrimary else colors.textPrimary,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BulletLine(text: String, warn: Boolean) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier.padding(vertical = Space.x1),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = FontSize.label,
                color = colors.textTertiary,
            ),
            modifier = Modifier.width(Space.x3),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = FontSize.label,
                color = if (warn) colors.textSecondarySafe else colors.textTertiary,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun levelColor(
    level: RiskLevel,
    colors: com.example.biliv3.design.tokens.BiliColors,
): androidx.compose.ui.graphics.Color = when (level) {
    RiskLevel.HIGH -> colors.brandPrimary
    RiskLevel.MEDIUM -> colors.accentCoin
    RiskLevel.LOW -> colors.textTertiary
}

private fun levelLabel(level: RiskLevel): String = when (level) {
    RiskLevel.HIGH -> "高风险"
    RiskLevel.MEDIUM -> "中风险"
    RiskLevel.LOW -> "低风险"
}

private fun riskLabel(level: RiskLevel): String = when (level) {
    RiskLevel.HIGH -> "高"
    RiskLevel.MEDIUM -> "中"
    RiskLevel.LOW -> "低"
}

private fun typeLabel(type: com.example.biliv3.plugin.PluginType): String = when (type) {
    com.example.biliv3.plugin.PluginType.BUILTIN -> "内置"
    com.example.biliv3.plugin.PluginType.RULE -> "规则"
    com.example.biliv3.plugin.PluginType.NATIVE -> "原生"
    com.example.biliv3.plugin.PluginType.EXTERNAL -> "外部包"
}

/**
 * 兼容性判定。
 *
 * ⚠️ 只做**主版本**比较：`1.3.0` 的插件要求 `1.3.0` 时，
 * 本机 `1.3.0` 通过、`1.4.0` 也通过（向后兼容）；
 * 但本机 `1.2.x` 不通过。
 *
 * 拿不准时**不阻止安装**，只显示警告 —— 阻止的代价是
 * "明明能用的插件装不上"，而警告的代价只是用户多点一次。
 */
private fun compatibleWith(required: String): Boolean {
    val cur = com.example.biliv3.BuildConfig.VERSION_NAME
    val r = required.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
    val c = cur.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
    if (r.isEmpty() || c.isEmpty()) return true
    // 主版本相同即视为兼容
    return c[0] == r[0] && (c.size < 2 || r.size < 2 || c[1] >= r[1])
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
