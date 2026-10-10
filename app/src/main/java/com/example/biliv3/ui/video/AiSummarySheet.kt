package com.example.biliv3.ui.video

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
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
import com.example.biliv3.data.ai.SummarySection
import com.example.biliv3.data.ai.SummarySource
import com.example.biliv3.data.ai.VideoSummary
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.example.biliv3.ui.component.TerminalLoadingState
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type
import com.example.biliv3.design.v3.v3GlassSurface
import com.example.biliv3.design.v3.V3Glass
import com.example.biliv3.design.v3.V3SectionTitle

/**
 * AI 总结弹层（v1.6.3）。
 *
 * ## UI 完全复用现有语言（需求硬要求）
 *
 * | 需求 | 本实现 |
 * |---|---|---|
 * | 复用弹窗 | `Dialog` + `V3Radius.lg` + `surfaceElevated`（同 `PlayerSettingsSheet`） |
 * | 无卡片 | 分组靠 `SectionMark` + `Rhythm` 间距，**不套容器** |
 * | 当前字体/颜色 | 全部走 `FontSize` / `BiliV3.colors`，零硬编码 |
 * | 状态反馈 | `TerminalLoadingState` / `BrandButton`（与全站一致） |
 *
 * **没有为 AI 总结新造任何视觉体系** —— 它看起来就是本项目的一个普通弹层。
 *
 * ## 🔴 来源必须显式标注
 *
 * 官方总结与第三方总结的可信度完全不同。第三方是**用户自己配的模型
 * 生成的**，可能出现幻觉；若与官方长得一样，会被误当成"B 站官方说的"。
 *
 * 所以标题右侧常驻一个来源标签：
 * - 官方 → 「B 站官方 AI」
 * - 第三方 → 模型名（如 `gpt-4o-mini`）
 */
@Composable
fun AiSummarySheet(
    state: AiSummaryUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * 点击分段的时间戳 → 跳转播放位置（秒）（v1.6.7）。
     *
     * null = 播放器不可用（此时不显示跳转图标，整行不可点）。
     */
    onSeek: ((Int) -> Unit)? = null,
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
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = V3Space.md)
                    .fillMaxWidth()
                    // 🔴 v3：面板材质从**实心深灰**改为 Liquid Glass。
                    //
                    // ⚠️ 用 `Modifier.v3GlassSurface` 而不是 `GlassSurface` 容器 ——
                    //    前者是**一个表达式替换**（`.clip + .background` 换成
                    //    `.v3GlassSurface`），**不动任何花括号**。
                    //    容器形式要在几百行深的树里配一对括号，实测改坏过两次。
                    //
                    // 判据（§7.37 坑 219）：**模糊玻璃适合「大面积、静态、
                    // 内容之上」的浮层** —— 弹层三条全中。
                    .v3GlassSurface(
                        shape = RoundedCornerShape(V3Radius.sheet),
                        level = V3Glass.Level.UltraThin,
                    )
                    .clickable(enabled = false) {}
                    .heightIn(max = MAX_SHEET_HEIGHT)
                    .padding(bottom = V3Space.sm),
            ) {
                // ---- 标题栏 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = V3Space.md, vertical = V3Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "AI 总结",
                        style = V3Type.subheadline.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.labelPrimary,
                        ),
                    )
                    Spacer(Modifier.width(V3Space.xs))
                    // 来源标签（拿到结果后才显示）
                    (state as? AiSummaryUiState.Done)?.summary?.let { s ->
                        SourceTag(source = s.source, model = s.model)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(V3Size.iconLg + V3Space.xs)
                            .clip(RoundedCornerShape(V3Radius.xs))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = colors.labelSecondary,
                            modifier = Modifier.size(V3Size.iconMd),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (state) {
                        is AiSummaryUiState.Idle,
                        is AiSummaryUiState.Loading,
                        -> TerminalLoadingState(
                            text = state.hint(),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // 🔴 v1.6.7：确定性的"走不通" —— 与 Error 分开渲染。
                        //
                        // 关键差别：**不给重试按钮**（除非 action = REFRESH）。
                        // 给一个永远不会成功的按钮是在误导用户。
                        is AiSummaryUiState.Blocked -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = V3Space.md),
                        ) {
                            Text(
                                text = state.title,
                                style = V3Type.subheadline.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = colors.labelPrimary,
                                ),
                            )
                            Spacer(Modifier.height(V3Space.xs))
                            Text(
                                text = state.detail,
                                style = V3Type.footnote.copy(
                                    color = colors.labelSecondary,
                                ),
                            )
                            Spacer(Modifier.height(V3Space.md))
                            Row(horizontalArrangement = Arrangement.spacedBy(V3Space.xs)) {
                                when (state.action) {
                                    AiSummaryUiState.BlockedAction.LOGIN ->
                                        BrandButton(
                                            label = "去登录",
                                            onClick = onOpenSettings,
                                            variant = BrandButtonVariant.Filled,
                                        )

                                    // 生成中给「刷新」—— 语义是"稍后再看"，
                                    // 不是"重试"（重试暗示刚才那次失败了）
                                    AiSummaryUiState.BlockedAction.REFRESH ->
                                        BrandButton(
                                            label = "刷新",
                                            onClick = onRetry,
                                            variant = BrandButtonVariant.Outline,
                                        )

                                    AiSummaryUiState.BlockedAction.NONE -> Unit
                                }
                            }
                        }

                        is AiSummaryUiState.Error -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = V3Space.md),
                        ) {
                            Text(
                                text = state.message,
                                style = V3Type.callout.copy(
                                    color = colors.labelSecondary,
                                ),
                            )
                            Spacer(Modifier.height(V3Space.md))
                            Row(horizontalArrangement = Arrangement.spacedBy(V3Space.xs)) {
                                BrandButton(
                                    label = "重试",
                                    onClick = onRetry,
                                    variant = BrandButtonVariant.Outline,
                                )
                                // 官方拿不到且第三方没配 → 直接给"去配置"的出口
                                if (state.needsConfig) {
                                    BrandButton(
                                        label = "配置第三方 AI",
                                        onClick = onOpenSettings,
                                        variant = BrandButtonVariant.Filled,
                                    )
                                }
                            }
                        }

                        is AiSummaryUiState.Done -> SummaryBody(state.summary, onSeek)
                    }
                    Spacer(Modifier.height(V3Space.md))
                }
            }
        }
    }
}

/**
 * 总结正文。
 *
 * ## 结构（严格用现有分组语言，不套卡片）
 *
 * ```
 * 01 ── 视频概述
 *   正文…
 * 02 ── 核心内容
 *   02:00  分段标题
 *     · 要点
 * 03 ── 简短总结
 *   正文…
 * ```
 *
 * 章节标记用 `SectionMark`（替代"卡片组标题"），与设置页/其它页一致。
 */
@Composable
private fun SummaryBody(
    summary: VideoSummary,
    onSeek: ((Int) -> Unit)? = null,
) {
    val colors = BiliV3.colors

    // ---- 概述 ----
    if (summary.overview.isNotBlank()) {
        // 🔴 v3：`SectionMark(index=…, title=…)` → `V3SectionTitle(title=…)`。
        //
        // ## 为什么去掉序号（§5.4.3 明确推翻）
        //
        // 旧 `SectionMark` 渲染成 `01 ── 视频概述`。序号是"极客点缀"，
        // 但它有两个实际问题：
        //
        // 1. **序号要人工维护** —— 本文件原来就写着
        //    `index = if (summary.outline.isEmpty()) 2 else 3`：
        //    章节增删时序号会错位。本项目已因此出过**两次**
        //    重复编号 bug（`…9, 10, 10, 11`）。
        // 2. **iOS 的章节标题就是一行小号标签**，没有序号也没有装饰线。
        //
        // ⚠️ `topSpace` 语义与旧 `modifier.padding(top=…)` 一致：
        //    间距**只由下方区块提供**（§5.1），不要两边都加。
        V3SectionTitle(
            title = "视频概述",
            topSpace = V3Space.xxs,
        )
        Spacer(Modifier.height(V3Space.xs))
        Text(
            text = summary.overview,
            style = V3Type.callout,
            color = colors.labelPrimary,
            modifier = Modifier.padding(horizontal = V3Space.md),
        )
    }

    // ---- 核心内容 ----
    if (summary.outline.isNotEmpty()) {
        V3SectionTitle(
            title = "核心内容",
            topSpace = Rhythm.between,
        )
        summary.outline.forEach { sec ->
            SummarySectionBlock(sec, onSeek)
        }
    }

    // ---- 简短总结 ----
    if (summary.conclusion.isNotBlank()) {
        V3SectionTitle(
            title = "简短总结",
            topSpace = Rhythm.between,
        )
        Spacer(Modifier.height(V3Space.xs))
        Text(
            text = summary.conclusion,
            style = V3Type.callout,
            color = colors.labelPrimary,
            modifier = Modifier.padding(horizontal = V3Space.md),
        )
    }

    // ---- 截断告知（必须显示，否则用户以为覆盖了全片）----
    if (summary.truncated) {
        Spacer(Modifier.height(Rhythm.between))
        RuleLine(color = Rule.subtle)
        Text(
            text = "⚠️ 字幕过长已截断，本总结仅基于视频前一部分内容。",
            style = V3Type.caption1.copy(
                color = colors.accentCoin,
            ),
            modifier = Modifier.padding(
                start = V3Space.md,
                end = V3Space.md,
                top = V3Space.xs,
            ),
        )
    }

    // ---- 第三方来源的责任说明 ----
    if (summary.source == SummarySource.THIRD_PARTY) {
        Spacer(Modifier.height(Rhythm.between))
        RuleLine(color = Rule.subtle)
        Text(
            text = "以上内容由你自己配置的第三方 AI（${summary.model}）根据字幕生成，" +
                "**不是 B 站官方总结**，可能有误，请自行判断。",
            style = V3Type.caption1.copy(
                color = colors.labelTertiary,
            ),
            modifier = Modifier.padding(
                start = V3Space.md,
                end = V3Space.md,
                top = V3Space.xs,
            ),
        )
    }
}

/** 一个分段：可选时间 + 标题 + 要点列表。 */
/**
 * 一个总结分段。
 *
 * ## 🔴 时间戳可点击跳转（v1.6.7）
 *
 * 官方总结的 `outline[].timestamp` 是**真实可用的定位信息** ——
 * 官方客户端点它就会跳转播放位置。
 *
 * 此前这里只把时间**当文字显示**，用户看到 `03:24` 却无法跳过去 ——
 * 等于拿到了一半的信息。现在整行可点。
 *
 * ## 为什么只有"有时间戳"时才可点
 *
 * 没有时间戳的分段（第三方总结可能没有）点了无处可去 ——
 * 给一个点了没反应的区域是死入口（§1.6）。
 * 所以 `clickable` 只在 `timestampSeconds > 0` 时挂上。
 *
 * ## 可点的视觉提示
 *
 * 时间文字本身用终端青（本来就有），加下划线会太重。
 * 用**时间文字 + 一个小的跳转图标**表达"这里能点" ——
 * 不新增色值，且图标在无障碍树里有 `contentDescription`。
 */
@Composable
private fun SummarySectionBlock(
    sec: SummarySection,
    onSeek: ((Int) -> Unit)? = null,
) {
    val colors = BiliV3.colors
    val jumpable = sec.timestampSeconds > 0 && onSeek != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 整行可点（热区足够大）—— 只让时间文字可点会很难点中
            .then(
                if (jumpable) {
                    Modifier.clickable { onSeek?.invoke(sec.timestampSeconds) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = V3Space.md, vertical = V3Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 时间用等宽（§5.1 允许的极客点缀：数字读数）
            if (sec.timeLabel.isNotEmpty()) {
                Text(
                    text = sec.timeLabel,
                    style = V3Type.footnote.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = V3Type.caption2.fontSize,
                        color = if (jumpable) colors.brand else colors.accentTerminal,
                    ),
                )
                // 可跳转时补一个图标 —— 表明"这里能点"
                if (jumpable) {
                    Spacer(Modifier.width(V3Space.xxs))
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "跳转到 ${sec.timeLabel}",
                        tint = colors.brand,
                        modifier = Modifier.size(V3Size.iconXs),
                    )
                }
                Spacer(Modifier.width(V3Space.xs))
            }
            if (sec.title.isNotEmpty()) {
                Text(
                    text = sec.title,
                    style = V3Type.footnote.copy(
                        fontWeight = FontWeight.Medium,
                        color = colors.labelPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        sec.points.forEach { point ->
            Spacer(Modifier.height(V3Space.xxs))
            Row(verticalAlignment = Alignment.Top) {
                // 要点前缀用**短横线**而不是圆点符号：
                // 与全站"左侧竖线/短横"的分组语言一致，且不引入 emoji 语义
                Text(
                    text = "—",
                    style = V3Type.caption1.copy(
                        color = colors.labelTertiary,
                    ),
                )
                Spacer(Modifier.width(V3Space.xs))
                Text(
                    text = point,
                    style = V3Type.footnote.copy(
                        color = colors.labelSecondary,
                    ),
                )
            }
        }
    }
}

/**
 * 来源标签。
 *
 * ⚠️ 官方与第三方**必须视觉可区分**：官方用品牌蓝（站内数据的语义），
 * 第三方用中性色 —— 让用户一眼看出"这段是谁说的"。
 */
@Composable
private fun SourceTag(source: SummarySource, model: String) {
    val colors = BiliV3.colors
    val (label, tint) = when (source) {
        SummarySource.OFFICIAL -> "B 站官方 AI" to colors.brandText
        SummarySource.THIRD_PARTY ->
            (model.ifEmpty { "第三方 AI" }) to colors.labelSecondary
    }

    Text(
        text = label,
        style = V3Type.caption2.copy(
            fontWeight = FontWeight.Medium,
            color = tint,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(V3Radius.xs))
            .background(colors.bgTertiary)
            .padding(horizontal = V3Space.tagHorizontal, vertical = V3Space.tagVertical),
    )
}

/** 弹层高度上限（与 `PlayerSettingsSheet` 同一取值思路：不顶到状态栏）。 */
private val MAX_SHEET_HEIGHT = 520.dp

/**
 * AI 总结的 UI 状态。
 *
 * 用密封接口而不是"三个布尔" —— 天然排除
 * "既在加载又有结果"这种非法组合（与 `PlayerSettingsSheet` 的
 * `PickerKindId` 同一思路）。
 */
sealed interface AiSummaryUiState {
    /** 还没开始（弹层刚打开、正在决定走哪条路）。 */
    data object Idle : AiSummaryUiState

    /** 正在取总结。[step] 说明当前在做哪一步（官方 / 第三方 / 取字幕）。 */
    data class Loading(val step: Step) : AiSummaryUiState

    /** 拿到结果。 */
    data class Done(val summary: VideoSummary) : AiSummaryUiState

    /** 失败。[needsConfig] = true 时给"去配置"按钮。 */
    data class Error(
        val message: String,
        val needsConfig: Boolean = false,
    ) : AiSummaryUiState

    /**
     * **确定性的"走不通"**（v1.6.7）。
     *
     * ## 🔴 为什么与 [Error] 分开
     *
     * [Error] 是"这次没成功，重试可能有用"（网络 / 服务端 / 解析）。
     * [Blocked] 是"这条路对**当前账号或这个视频**就是不通" ——
     * 重试一万次结果都一样。
     *
     * 之前两者混在一起，于是"没有权限"这个文案被用在了所有失败上，
     * 包括本该说"未登录"或"不支持"的情况。需求明确禁止这一点。
     *
     * 分开之后，UI 对 [Blocked] **不给重试按钮**（除非 action 是刷新），
     * 因为给一个永远不会成功的按钮是在误导用户。
     *
     * @param title 主标题（一句话说清是什么问题）
     * @param detail 补充说明（为什么会这样 / 下一步做什么）
     * @param action 该给用户什么出口
     */
    data class Blocked(
        val title: String,
        val detail: String,
        val action: BlockedAction,
    ) : AiSummaryUiState

    /** [Blocked] 给出的出口。 */
    enum class BlockedAction {
        /** 什么都不给（重试也没用，也没有别的路）。 */
        NONE,

        /** 引导登录。 */
        LOGIN,

        /** 给「刷新」—— 服务端在生成中，等一会儿可能有。 */
        REFRESH,
    }

    /** 加载步骤（用于给用户**具体**的进度文案，而不是一句"加载中"）。 */
    enum class Step {
        /** 正在试官方总结。 */
        OFFICIAL,

        /** 正在取字幕。 */
        SUBTITLE,

        /** 正在调第三方模型。 */
        MODEL,
    }
}

/** 加载文案：说明**当前在哪一步**，长耗时操作尤其需要。 */
private fun AiSummaryUiState.hint(): String = when (this) {
    is AiSummaryUiState.Loading -> when (step) {
        AiSummaryUiState.Step.OFFICIAL -> "正在查询 B 站官方 AI 总结…"
        AiSummaryUiState.Step.SUBTITLE -> "正在获取字幕…"
        AiSummaryUiState.Step.MODEL -> "正在调用第三方 AI 生成总结…"
    }
    else -> "正在准备…"
}
