package com.example.biliv3.ui.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.band
import com.example.biliv3.design.BandLevel
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.ruleTop
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.plugin.PluginPermission
import com.example.biliv3.plugin.PluginRuntime
import com.example.biliv3.plugin.PluginType
import com.example.biliv3.plugin.RiskLevel
import com.example.biliv3.plugin.RuleAction
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.example.biliv3.ui.component.EmptyState

/**
 * 插件中心。
 *
 * ## 页面结构（任务书 §21）
 *
 * - **已安装**：内置 / 规则 / 原生，含启用开关
 * - **导入**：选 `.bvplugin` / `.json`
 * - **详情**：描述、版本、作者、权限、来源、兼容性、风险、日志、统计
 *
 * ## 视觉（无卡片架构）
 *
 * 插件项是**列表行**：
 * - 直角（只有开关与按钮是交互元素）
 * - 行间发丝线
 * - 权限用 `band()` 明度带分组，不用卡片
 */
@Composable
fun PluginCenterScreen(
    plugins: List<PluginRuntime>,
    onBack: () -> Unit,
    onToggle: (PluginRuntime, Boolean) -> Unit,
    onReload: (PluginRuntime) -> Unit,
    onUninstall: (PluginRuntime) -> Unit,
    onImport: () -> Unit,
    onShowDetail: (PluginRuntime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ---- 标题栏 ----
        // ⚠️ 必须自己消费 statusBars：MainShell 的 contentWindowInsets 是 0，
        // inset 由各页面自行处理（见 MainShell 的注释）。
        // 漏了它的表现是：标题栏画到状态栏底下，**「导入」按钮点不动**
        // —— 点击落在系统状态栏上，日志里连 `START u0` 都不会出现。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(Sizes.topBarMobile)
                .padding(horizontal = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(RoundedCornerShape(Radius.pill))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(Sizes.iconXl),
                )
            }
            Spacer(Modifier.width(Space.x1))
            Text(
                text = "插件中心",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
            Spacer(Modifier.weight(1f))
            // 导入入口
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.interactive))
                    .clickable(onClick = onImport)
                    .padding(horizontal = Space.x3, vertical = Space.x2),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.FileOpen,
                        contentDescription = null,
                        tint = colors.brandPrimary,
                        modifier = Modifier.size(Sizes.iconMd),
                    )
                    Spacer(Modifier.width(Space.x1))
                    Text(
                        text = "导入",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            color = colors.brandPrimary,
                        ),
                    )
                }
            }
        }

        if (plugins.isEmpty()) {
            EmptyState(
                title = "还没有插件",
                description = "点右上角「导入」选择 .bvplugin 或 .json 文件",
                icon = Icons.Filled.Extension,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Space.x8),
            ) {
                items(plugins, key = { it.metadata.id }) { p ->
                    PluginRow(
                        runtime = p,
                        onToggle = { onToggle(p, it) },
                        onReload = { onReload(p) },
                        onUninstall = { onUninstall(p) },
                        onShowDetail = { onShowDetail(p) },
                    )
                }
            }
        }
    }
}

/** 插件行。 */
@Composable
private fun PluginRow(
    runtime: PluginRuntime,
    onToggle: (Boolean) -> Unit,
    onReload: () -> Unit,
    onUninstall: () -> Unit,
    onShowDetail: () -> Unit,
) {
    val colors = BiliTheme.colors
    val meta = runtime.metadata

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .ruleBottom(color = Rule.subtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onShowDetail)
                .padding(horizontal = Space.x3, vertical = Space.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = meta.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = FontSize.body,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(Space.x2))
                    TypeTag(meta.type)
                    if (runtime.autoDisabled) {
                        Spacer(Modifier.width(Space.x1))
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "因异常被自动禁用",
                            tint = colors.accentCoin,
                            modifier = Modifier.size(Sizes.iconSm),
                        )
                    }
                }
                Spacer(Modifier.height(Space.x1))
                Text(
                    text = "v${meta.version} · ${runtime.source}" +
                        if (meta.author.isNotEmpty()) " · ${meta.author}" else "",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.description.isNotEmpty()) {
                    Spacer(Modifier.height(Space.x1))
                    Text(
                        text = meta.description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.textTertiary,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(Space.x3))

            Switch(
                checked = runtime.enabled,
                onCheckedChange = onToggle,
            )
        }

        // 权限概览（有权限才显示）
        if (meta.permissions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Space.x3, end = Space.x3, bottom = Space.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RiskTag(runtime.riskLevel)
                Spacer(Modifier.width(Space.x2))
                Text(
                    text = meta.permissions.joinToString("、") { it.displayName },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = FontSize.badge,
                        color = colors.textTertiary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 统计信息（执行次数 / 耗时 / 错误）
        if (runtime.runCount > 0 || runtime.lastError != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Space.x3, end = Space.x3, bottom = Space.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "执行 ${runtime.runCount} 次 · 最近 ${runtime.lastDurationMs}ms",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = FontSize.badge,
                        color = colors.textTertiary,
                        fontFamily = com.example.biliv3.design.tokens.FontFamilies.mono,
                    ),
                )
                if (runtime.lastError != null) {
                    Spacer(Modifier.width(Space.x2))
                    Text(
                        text = "有错误",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = FontSize.badge,
                            color = colors.accentCoin,
                        ),
                    )
                }
            }
        }
    }
}

/** 类型标签（方角微标签，极客点缀的合法用法）。 */
@Composable
private fun TypeTag(type: PluginType) {
    val colors = BiliTheme.colors
    Text(
        text = type.label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = FontSize.badge,
            color = colors.textSecondarySafe,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.interactive))
            .background(colors.bgHover)
            .padding(
                horizontal = com.example.biliv3.design.tokens.Space.tagHorizontal,
                vertical = com.example.biliv3.design.tokens.Space.tagVertical,
            ),
    )
}

/** 风险标签。 */
@Composable
private fun RiskTag(level: RiskLevel) {
    val colors = BiliTheme.colors
    val color = when (level) {
        RiskLevel.LOW -> colors.stateSuccess
        RiskLevel.MEDIUM -> colors.accentCoin
        RiskLevel.HIGH -> colors.brandPrimary
    }
    Text(
        text = "风险${level.label}",
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = FontSize.badge,
            color = color,
        ),
    )
}

/**
 * 插件详情。
 *
 * ## 为什么详情要单独一屏（而不是展开行）
 *
 * 任务书 §21 要求显示：描述 / 版本 / 作者 / 权限 / 来源 / 文件结构 /
 * 兼容性 / 风险 / 日志。这些内容**远超一行的高度**，
 * 塞进列表行会让列表变得无法浏览。
 */
@Composable
fun PluginDetailScreen(
    runtime: PluginRuntime,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    val meta = runtime.metadata

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(Sizes.topBarMobile)
                .padding(horizontal = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(RoundedCornerShape(Radius.pill))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(Sizes.iconXl),
                )
            }
            Spacer(Modifier.width(Space.x1))
            Text(
                text = meta.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Space.x8),
        ) {
            // ---- 基本信息 ----
            item { SectionTitle("基本信息") }
            item {
                InfoRow("版本", "v${meta.version}")
                InfoRow("作者", meta.author.ifEmpty { "未声明" })
                InfoRow("类型", meta.type.label)
                InfoRow("来源", runtime.source)
                InfoRow("插件 API", "v${meta.apiVersion}（当前支持 v${com.example.biliv3.plugin.PluginApi.VERSION}）")
                if (meta.compatibleAppVersion.isNotEmpty()) {
                    InfoRow("要求 App 版本", meta.compatibleAppVersion)
                }
            }

            if (meta.description.isNotEmpty()) {
                item { SectionTitle("描述") }
                item {
                    Text(
                        text = meta.description,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.body,
                            color = colors.textSecondarySafe,
                        ),
                        modifier = Modifier.padding(
                            horizontal = Space.x3,
                            vertical = Space.x2,
                        ),
                    )
                }
            }

            // ---- 权限 ----
            item { SectionTitle("权限（${meta.permissions.size}）") }
            if (meta.permissions.isEmpty()) {
                item {
                    Text(
                        text = "未申请任何权限",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.textTertiary,
                        ),
                        modifier = Modifier.padding(horizontal = Space.x3, vertical = Space.x2),
                    )
                }
            } else {
                items(meta.permissions.toList()) { p ->
                    PermissionRow(p)
                }
            }

            // ---- 风险 ----
            item { SectionTitle("风险（${runtime.riskLevel.label}）") }
            item {
                // ⚠️ 这里**必须用 PluginRuntime.riskReasons**，
                // 不能在 UI 里重新算一遍 —— 早期版本就是重算的，
                // 结果漏掉了 MEDIUM 权限，出现「风险（中）」下面写着
                // 「未发现明显风险」的自相矛盾（装机实测发现）。
                // 判据来源只有一个：`PluginPackagePreview.riskReasons` /
                // `PluginRuntime.riskLevel`，UI 只负责展示。
                val reasons = runtime.riskReasons
                Column(modifier = Modifier.padding(horizontal = Space.x3, vertical = Space.x2)) {
                    reasons.forEach { r ->
                        Text(
                            text = "· $r",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = FontSize.bodySm,
                                color = colors.textSecondarySafe,
                            ),
                        )
                    }
                }
            }

            // ---- 运行统计 ----
            item { SectionTitle("运行统计") }
            item {
                InfoRow("状态", if (runtime.enabled) "已启用" else "已禁用")
                InfoRow("执行次数", runtime.runCount.toString())
                InfoRow("最近耗时", "${runtime.lastDurationMs}ms")
                InfoRow(
                    "最近执行",
                    if (runtime.lastRunAt > 0L) {
                        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(runtime.lastRunAt))
                    } else {
                        "从未"
                    },
                )
            }

            // ---- 规则（规则插件） ----
            if (runtime.rules.isNotEmpty()) {
                item { SectionTitle("规则（${runtime.rules.size}）") }
                items(runtime.rules) { rule ->
                    RuleRow(rule.id, rule.actions)
                }
            }

            // ---- 规则解析错误 ----
            if (runtime.ruleErrors.isNotEmpty()) {
                item { SectionTitle("规则问题（${runtime.ruleErrors.size}）") }
                items(runtime.ruleErrors) { err ->
                    Text(
                        text = "· $err",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.accentCoin,
                        ),
                        modifier = Modifier.padding(horizontal = Space.x3, vertical = Space.x1),
                    )
                }
            }

            // ---- 日志 ----
            if (runtime.logs.isNotEmpty()) {
                item { SectionTitle("日志（最近 ${runtime.logs.size} 条）") }
                items(runtime.logs) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = FontSize.badge,
                            color = colors.textTertiary,
                            fontFamily = com.example.biliv3.design.tokens.FontFamilies.mono,
                        ),
                        modifier = Modifier.padding(horizontal = Space.x3, vertical = Space.x1),
                    )
                }
            }

            // ---- 操作 ----
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .ruleTop(color = Rule.color)
                        .padding(horizontal = Space.x3, vertical = Space.x4),
                    horizontalArrangement = Arrangement.spacedBy(Space.x3),
                ) {
                    BrandButton(
                        label = "重新加载",
                        onClick = onReload,
                        variant = BrandButtonVariant.Outline,
                    )
                    BrandButton(
                        label = "卸载",
                        onClick = onUninstall,
                        variant = BrandButtonVariant.Outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    val colors = BiliTheme.colors
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = FontSize.label,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondarySafe,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .band(BandLevel.Raised)
            .padding(horizontal = Space.x3, vertical = Space.x2),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x2),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                color = colors.textSecondarySafe,
            ),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                color = colors.textPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.x4),
        )
    }
}

@Composable
private fun PermissionRow(p: PluginPermission) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x2),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (p.level == RiskLevel.LOW) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Filled.Warning
            },
            contentDescription = null,
            tint = when (p.level) {
                RiskLevel.LOW -> colors.stateSuccess
                RiskLevel.MEDIUM -> colors.accentCoin
                RiskLevel.HIGH -> colors.brandPrimary
            },
            modifier = Modifier
                .padding(top = 2.dp)
                .size(Sizes.iconSm),
        )
        Spacer(Modifier.width(Space.x2))
        Column {
            Text(
                text = p.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
            )
            Text(
                text = p.description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.textTertiary,
                ),
            )
        }
    }
}

@Composable
private fun RuleRow(id: String, actions: List<RuleAction>) {
    val colors = BiliTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x2),
    ) {
        Text(
            text = id,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = colors.textPrimary,
                fontFamily = com.example.biliv3.design.tokens.FontFamilies.mono,
            ),
        )
        Text(
            text = actions.joinToString(" → ") { actionLabel(it) },
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = FontSize.bodySm,
                color = colors.textSecondarySafe,
            ),
        )
    }
}

/** 动作的展示文案。 */
internal fun actionLabel(a: RuleAction): String = when (a) {
    is RuleAction.AddLocalTag -> "加标签「${a.tag}」"
    is RuleAction.RemoveLocalTag -> "去标签「${a.tag}」"
    RuleAction.MarkOrganized -> "标记已整理"
    is RuleAction.MoveToFolder -> "移动到收藏夹 ${a.folderId}"
    RuleAction.AddToQueue -> "加入播放队列"
}
