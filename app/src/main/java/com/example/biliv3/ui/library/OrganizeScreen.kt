package com.example.biliv3.ui.library

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.biliv3.data.FavoriteEntry
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.plugin.RuleAction
import com.example.biliv3.plugin.RuleEngine
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.component.TerminalLoadingState
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * 快速整理页（v1.3.0）。
 *
 * ## 它解决什么问题
 *
 * 收藏夹用久了会有大量"当时想回头看、现在完全不记得为什么收藏"的视频。
 * 逐个点开判断太慢 —— 本页让**规则**先筛出候选，
 * 用户只对候选做"确认/跳过"。
 *
 * ## 为什么是"规则筛选 + 人工确认"而不是全自动
 *
 * 全自动整理（按规则直接删/移动）看着很爽，但规则一定会有误判，
 * 而收藏是**不可撤销**的（服务端没有回收站）。
 * 所以本页的定位是：**规则负责缩小范围，人负责做决定**。
 * 破坏性动作仍然走批量确认框。
 *
 * ## 数据流
 *
 * ```
 * 收藏列表 → RuleEngine.evaluate(Subject) → 命中的条目 + 命中原因 → 列表
 *                                                    ↓
 *                                     用户勾选 → 走批量接口（复用批量整理）
 * ```
 *
 * 规则来自已启用的**规则插件**（`PluginManager.evaluateRules`），
 * 所以用户装一个规则插件就能自定义整理策略，不需要改 App。
 */
@Composable
fun OrganizeScreen(
    folderTitle: String,
    candidates: List<OrganizeCandidate>,
    loading: Boolean,
    error: String?,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoginRequired: () -> Unit,
    modifier: Modifier = Modifier,
    selection: BatchSelection = BatchSelection(),
    batchRunning: Boolean = false,
    batchProgress: Pair<Int, Int>? = null,
    onToggleSelect: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onBatchRemove: () -> Unit = {},
    onBatchAddToView: () -> Unit = {},
) {
    val colors = BiliV3.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPrimary),
    ) {
        // ⚠️ 必须自己消费 statusBars（MainShell 的 contentWindowInsets 是 0）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(V3Size.topBar)
                .padding(horizontal = V3Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(V3Size.touchMin)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.labelPrimary,
                    modifier = Modifier.size(V3Size.iconLg),
                )
            }
            Spacer(Modifier.width(V3Space.xxs))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "快速整理",
                    style = V3Type.subheadline.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.labelPrimary,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = folderTitle,
                    style = V3Type.caption1.copy(
                        color = colors.labelTertiary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (candidates.isNotEmpty()) {
                TextButton(onClick = if (selection.isAllSelected()) onClearSelection else onSelectAll) {
                    Text(
                        text = if (selection.isAllSelected()) "取消" else "全选",
                        color = colors.brand,
                    )
                }
            }
        }

        when {
            !isLoggedIn -> OrganizeLoginPanel(onLoginRequired)
            loading -> TerminalLoadingState(
                text = "正在跑规则…",
                modifier = Modifier.fillMaxSize(),
            )
            error != null -> ErrorState(
                title = "整理失败",
                description = error,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
            candidates.isEmpty() -> EmptyState(
                title = "没有需要整理的",
                terminalStyle = true,
                description = "规则没有筛出任何条目。装一个规则插件可以自定义筛选条件。",
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = V3Space.huge * 2),
            ) {
                // 说明条：让用户知道这是"规则筛出来的候选"，不是全部
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.bgTertiary)
                            .padding(horizontal = V3Space.md, vertical = V3Space.xs),
                    ) {
                        Text(
                            text = "规则筛出 ${candidates.size} 个候选",
                            style = V3Type.footnote.copy(
                                color = colors.labelPrimary,
                            ),
                        )
                        Text(
                            text = "勾选后统一处理；确认前不会改动任何东西",
                            style = V3Type.caption1.copy(
                                color = colors.labelTertiary,
                            ),
                        )
                    }
                }

                items(candidates, key = { it.entry.favItemId.toString() }) { c ->
                    OrganizeRow(
                        candidate = c,
                        selected = selection.contains(c.entry.favItemId.toString()),
                        onToggle = { onToggleSelect(c.entry.favItemId.toString()) },
                    )
                }
            }
        }
    }

    // ---- 底部执行条 ----
    if (selection.isNotEmpty) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            BatchBottomBar(
                count = selection.count,
                running = batchRunning,
                hasOtherFolders = false,   // 整理页只做清理，不做移动
                onMove = {},
                onRemove = onBatchRemove,
                onAddToView = onBatchAddToView,
                onAddToQueue = {},
            )
        }
    }
}

/**
 * 未登录提示（本页专用）。
 *
 * ⚠️ 不复用 `LibraryScreens` 里的同名组件 —— 它是 `private`，
 * 而把它改成 public 会让"仅本文件使用"的约束消失（其它页面可能乱用）。
 * 这里就几行，独立写反而更清楚。
 */
@Composable
private fun OrganizeLoginPanel(onLogin: () -> Unit) {
    val colors = BiliV3.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(V3Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "整理收藏需要登录",
            style = V3Type.subheadline.copy(
                color = colors.labelPrimary,
            ),
        )
        Spacer(Modifier.height(V3Space.xs))
        Text(
            text = "登录后才能读取收藏夹并做批量操作",
            style = V3Type.footnote.copy(
                color = colors.labelSecondary,
            ),
        )
        Spacer(Modifier.height(V3Space.md))
        TextButton(onClick = onLogin) {
            Text("去登录", color = colors.brand)
        }
    }
}

/**
 * 一条候选。
 *
 * @param reasons 命中原因（**必须显示** —— 用户要知道"为什么这条被筛出来"，
 *        否则无法判断该不该删。不显示原因的话这个页面就退化成随机列表）
 */
data class OrganizeCandidate(
    val entry: FavoriteEntry,
    val reasons: List<String>,
)

@Composable
private fun OrganizeRow(
    candidate: OrganizeCandidate,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val colors = BiliV3.colors
    val v = candidate.entry.video

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = V3Space.md, vertical = V3Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 选中标记用几何形状（不用 Material Checkbox）——
            // 这里行高比收藏列表大，Checkbox 会显得很空
            Box(
                modifier = Modifier
                    .size(V3Size.iconMd)
                    .clip(CircleShape)
                    .background(
                        if (selected) colors.brand else colors.bgTertiary,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Text(
                        text = "✓",
                        style = V3Type.caption1.copy(
                            color = colors.labelOnBrand,
                        ),
                    )
                }
            }
            Spacer(Modifier.width(V3Space.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = v.title,
                    style = V3Type.callout.copy(
                        color = colors.labelPrimary,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(V3Space.xxs))
                Text(
                    text = v.authorName,
                    style = V3Type.caption1.copy(
                        color = colors.labelTertiary,
                    ),
                    maxLines = 1,
                )
            }
        }

        // 命中原因
        if (candidate.reasons.isNotEmpty()) {
            Spacer(Modifier.height(V3Space.xxs))
            Row(
                modifier = Modifier.padding(start = V3Space.huge),
                horizontalArrangement = Arrangement.spacedBy(V3Space.xxs),
            ) {
                candidate.reasons.forEach { r ->
                    Text(
                        text = r,
                        style = V3Type.caption1.copy(
                            color = colors.accentTerminal,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(V3Radius.xs))
                            .background(colors.bgTertiary)
                            .padding(horizontal = V3Space.xs, vertical = V3Space.xxs),
                    )
                }
            }
        }
    }
}

/**
 * 把规则动作翻译成给用户看的原因文本。
 *
 * ⚠️ 用**规则自己的意图**而不是规则 id —— 用户不认识 id。
 */
fun reasonText(action: RuleAction): String = when (action) {
    is RuleAction.AddLocalTag -> "打标签：${action.tag}"
    is RuleAction.RemoveLocalTag -> "去标签：${action.tag}"
    is RuleAction.MarkOrganized -> "已整理过"
    is RuleAction.MoveToFolder -> "移动到其他夹"
    is RuleAction.AddToQueue -> "加入队列"
}

/**
 * 对收藏列表跑规则，产出候选。
 *
 * ## 为什么放在这里而不是 ViewModel
 *
 * 它是**纯函数**（输入列表 + 规则 → 输出候选），没有 IO。
 * 放纯函数便于单测，也让 ViewModel 不必持有 RuleEngine。
 *
 * @param evaluate 由调用方注入（`PluginManager.evaluateRules`），
 *        这样本文件不依赖 plugin 包的内部实现
 */
fun buildCandidates(
    entries: List<FavoriteEntry>,
    folderTitle: String,
    evaluate: (RuleEngine.Subject) -> List<RuleAction>,
): List<OrganizeCandidate> {
    val out = ArrayList<OrganizeCandidate>()
    for (e in entries) {
        val subject = RuleEngine.Subject(
            title = e.video.title,
            author = e.video.authorName,
            category = "",
            tags = emptyList(),
            folder = folderTitle,
            durationSeconds = e.video.durationSeconds,
        )
        // ⚠️ 每条都 try：单条规则炸了不能让整页失败
        val actions = runCatching { evaluate(subject) }.getOrElse { emptyList() }
        if (actions.isNotEmpty()) {
            out.add(OrganizeCandidate(e, actions.map { reasonText(it) }.distinct()))
        }
    }
    return out
}
