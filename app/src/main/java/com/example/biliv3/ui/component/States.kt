package com.example.biliv3.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.NotLoggedInException
import com.example.biliv3.design.tokens.FontFamilies
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * 空态。
 *
 * **文案必须具体** —— 「还没有推荐内容」而不是「暂无数据」。
 * 空态不是错误，不出现红色或警告语义。
 *
 * ## 视觉
 *
 * 图标 + 标题 + 可选说明 + 可选动作。图标用 `textTertiary`（弱化），
 * 因为空态是"这里暂时没东西"，不该抢视觉。
 */
@Composable
fun EmptyState(
    title: String,
    description: String? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /**
     * 是否用极客风的**提示符行**替代图标。
     *
     * 开启后显示 `$ 这里还没有内容` 而不是图标 + 大标题 ——
     * 更轻、更"系统在说话"。适合**列表为空**这类次要空态。
     *
     * ⚠️ 主页面空态（如"未登录"）建议保持默认（图标版）：
     * 那种场景需要更明确的视觉重量。
     */
    terminalStyle: Boolean = false,
) {
    val colors = BiliV3.colors

    if (terminalStyle) {
        TerminalEmptyState(
            title = title,
            description = description,
            actionLabel = actionLabel,
            onAction = onAction,
            modifier = modifier,
            compact = compact,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = V3Space.xxl,
                vertical = if (compact) V3Space.lg else V3Space.huge,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(if (compact) V3Size.iconLg else 48.dp),
        )
        Spacer(Modifier.height(if (compact) V3Space.sm else V3Space.md))
        Text(
            text = title,
            style = V3Type.callout.copy(
                color = colors.labelSecondary,
            ),
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Spacer(Modifier.height(V3Space.xs))
            Text(
                text = description,
                style = V3Type.footnote.copy(
                    color = colors.labelSecondary,
                ),
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(V3Space.lg))
            BrandButton(
                label = actionLabel,
                onClick = onAction,
                variant = BrandButtonVariant.Outline,
            )
        }
    }
}

/**
 * 错误态。
 *
 * **必须给出可操作的原因** —— 「网络不可用，请检查连接」而不是「出错了」。
 */
@Composable
fun ErrorState(
    title: String,
    description: String? = null,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "重试",
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = BiliV3.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = V3Space.xxl,
                vertical = if (compact) V3Space.lg else V3Space.huge,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(if (compact) V3Size.iconLg else 48.dp),
        )
        Spacer(Modifier.height(if (compact) V3Space.sm else V3Space.md))
        Text(
            text = title,
            style = V3Type.callout.copy(
                color = colors.labelSecondary,
            ),
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Spacer(Modifier.height(V3Space.xs))
            Text(
                text = description,
                style = V3Type.footnote.copy(
                    color = colors.labelSecondary,
                ),
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            Spacer(Modifier.height(V3Space.lg))
            BrandButton(
                label = retryLabel,
                onClick = onRetry,
                variant = BrandButtonVariant.Outline,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 终端风状态（极客点缀，克制版）
// ---------------------------------------------------------------------------

/**
 * 终端风空态。
 *
 * ```
 * $ 这个收藏夹是空的
 *   换个收藏夹看看，或先收藏几个视频
 * ```
 *
 * ## 为什么用提示符而不是图标
 *
 * 图标空态有"视觉重量"—— 适合主页面（未登录、加载失败）。
 * 但**列表为空**是次要状态，一屏里可能出现多次，用大图标会喧宾夺主。
 * 提示符行更轻，且天然表达"系统在跟你说话"。
 *
 * ## 约束
 *
 * - 提示符**只有一行**，不做多行"命令行输出"
 * - 不加闪烁光标（空态不是"进行中"）
 * - 不铺满屏（`compact` 默认行为就是小面积）
 */
@Composable
private fun TerminalEmptyState(
    title: String,
    description: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = BiliV3.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = V3Space.md,
                vertical = if (compact) V3Space.lg else V3Space.xxl,
            ),
        // 🔴 深度重构：补 `horizontalAlignment`（原来只有 verticalArrangement）。
        //
        // ## 原来为什么看着"偏在左下角"
        //
        // 调用方（离线缓存 / 收藏 / 历史 …）传的是 `Modifier.fillMaxSize()`，
        // 于是这个 Column 占满剩余高度、`verticalArrangement = Center` 生效 ——
        // **垂直方向是居中的**。但**水平方向没有对齐约束**，
        // Column 的默认 `horizontalAlignment = Start` 让它贴左边。
        //
        // 实测（1080×2400，离线缓存空态）：标题行 y≈1080（垂直居中没错），
        // 但 x 从 40 开始贴左 —— 上面一大片空白、文字挤在左侧，
        // 视觉重心明显偏左下，不像"页面中心"。
        //
        // ⚠️ 这与 `EmptyState`（非终端风）不一致：那个显式写了
        //    `horizontalAlignment = CenterHorizontally`。
        //    同一个 App 的两种空态**对齐方式不同**，属于任务书说的
        //    「视觉不一致」。
        //
        // ⚠️ 保持左对齐的是**标题行内部的竖线 + 文字**（终端输出的视觉语言），
        //    这里居中的是**整块内容**在页面里的位置 —— 两者不冲突。
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 🔴 v1.5.1：去掉 `$` 提示符（用户反馈"多条文字前面出现不该有的 $ 符号"）。
        //
        // 原来这里调 `PromptLine(symbol = "$")`，12 个页面（收藏 / 历史 /
        // 稍后再看 / 下载 / 番剧 / 分区 / 搜索 / 整理 / 查成分 …）的空态
        // 都渲染成 `$ 这里还没有内容`。
        //
        // AGENTS.md §5.1 曾把 `$` 列为"允许的极客点缀"，但**用户判断优先** ——
        // 它出现在**每个空列表**上，频率太高，读起来像数据损坏而非设计。
        // 空态已由 `terminalStyle` 的等宽小字表达"系统在说话"，不需要符号。
        //
        // 现在改用**左侧竖线**：保留终端输出的视觉语言，
        // 但不插入任何可能被误读为内容的字符。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(14.dp)
                    .background(colors.accentTerminal),
            )
            Spacer(Modifier.width(V3Space.xs))
            Text(
                text = title,
                style = V3Type.footnote.copy(
                    fontFamily = FontFamilies.mono,
                    fontSize = V3Type.caption1.fontSize,
                    lineHeight = V3Type.caption1.lineHeight,
                    color = colors.labelSecondary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (description != null) {
            Spacer(Modifier.height(V3Space.xs))
            // 说明行与标题左对齐（标题已无符号，缩进随之取消）
            Row {
                Spacer(Modifier.width(V3Space.xs + 2.dp))
                Text(
                    text = description,
                    style = V3Type.subheadline.copy(
                        fontFamily = FontFamilies.mono,
                        fontSize = V3Type.caption1.fontSize,
                        lineHeight = V3Type.caption1.lineHeight,
                        color = colors.labelTertiary,
                    ),
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(V3Space.md))
            BrandButton(
                label = actionLabel,
                onClick = onAction,
                variant = BrandButtonVariant.Outline,
            )
        }
    }
}

/**
 * 终端风加载态。
 *
 * ```
 * $ 正在加载推荐…  ▌
 * ```
 *
 * ## 为什么这个场景值得用光标
 *
 * 光标在这里**承担真实语义**：告诉用户"还在跑"。
 * 静态的"正在加载…"文字在慢网络下会让人怀疑是不是卡死了，
 * 一个跳动的方块能消除这个疑虑。
 *
 * ## 降级
 *
 * 低端设备光标**常亮不闪**（见 [BlockCursor]）—— 省一次每帧重组，
 * 语义不变（仍是"这里在进行中"）。
 */
@Composable
fun TerminalLoadingState(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = BiliV3.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = V3Space.md,
                vertical = if (compact) V3Space.md else V3Space.xxl,
            ),
        contentAlignment = if (compact) Alignment.CenterStart else Alignment.Center,
    ) {
        // 🔴 v1.5.1：与空态一致，去掉 `$`（用户反馈"文字前面不该有 $ 符号"）。
        // 加载态改用**竖线 + 闪烁光标** —— 保留"进行中"的语义，
        // 但不再用会被误读成内容的符号。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(14.dp)
                    .background(colors.accentTerminal),
            )
            Spacer(Modifier.width(V3Space.xs))
            Text(
                text = text,
                style = V3Type.footnote.copy(
                    fontFamily = FontFamilies.mono,
                    fontSize = V3Type.caption1.fontSize,
                    lineHeight = V3Type.caption1.lineHeight,
                    color = colors.labelSecondary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(V3Space.xxs))
            BlockCursor(color = colors.accentTerminal)
        }
    }
}

/**
 * 把异常翻译成用户看得懂的文案。
 *
 * UI 层只认这几种，具体 code 在这里统一翻译 —— 页面里不再写 when(code)。
 *
 * ## ⚠️ 未登录必须单独判（这是一个真实文案 bug）
 *
 * `NotLoggedInException` 是 `InteractionRepository` / `SpaceRepository` /
 * `LibraryRepository` 在未登录时统一抛出的类型，它的 `message` 就是
 * "请先登录"。但此前这里只判了网络类错误，未登录会掉进 `else` 分支，
 * 被翻译成 **"加载失败，请稍后重试"** —— 语义完全错：
 * 用户需要的是"去登录"，不是"重试"（重试多少次都不会成功）。
 *
 * 所以把它提到网络判断之前，直接返回 message（即"请先登录"）。
 */
fun userMessageFor(error: Throwable): String = when (error) {
    is BiliException -> error.userMessage
    is NotLoggedInException -> "请先登录"
    else -> {
        val s = error.message.orEmpty()
        when {
            s.contains("Unable to resolve host") || s.contains("timeout", true) ->
                "网络不可用，请检查连接后重试"
            s.contains("Failed to connect") -> "连接服务器失败，请稍后重试"
            // 兜底再判一次文案：有些仓库直接 throw IllegalStateException("请先登录")
            s.contains("请先登录") -> "请先登录"
            else -> "加载失败，请稍后重试"
        }
    }
}

/** 按钮变体。 */
enum class BrandButtonVariant {
    /** 粉色实心，主行动。 */
    Filled,

    /** 描边，次行动。 */
    Outline,

    /** 纯文本。 */
    Text,
}

/**
 * 主按钮。
 *
 * ## 对比度注意
 *
 * 粉色底 + 白字 = 4.0:1，**需要字号 ≥16px 且加粗**才达标。
 * 所以 Filled 变体的文字固定用 15sp + SemiBold，不再往下调。
 */
@Composable
fun BrandButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BrandButtonVariant = BrandButtonVariant.Filled,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    contentPadding: PaddingValues? = null,
) {
    val colors = BiliV3.colors

    val bg = when (variant) {
        BrandButtonVariant.Filled -> colors.brand
        BrandButtonVariant.Outline, BrandButtonVariant.Text -> colors.bgSecondary
    }
    val fg = when (variant) {
        BrandButtonVariant.Filled -> colors.labelOnBrand
        BrandButtonVariant.Outline, BrandButtonVariant.Text -> colors.brandBiliText
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(V3Radius.xs),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContainerColor = colors.skeletonBase,
            disabledContentColor = colors.labelTertiary,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
        ),
        border = if (variant == BrandButtonVariant.Outline) {
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = colors.brand,
            )
        } else {
            null
        },
        contentPadding = contentPadding ?: PaddingValues(
            horizontal = V3Space.md,
            vertical = V3Space.sm,
        ),
        modifier = modifier,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(V3Size.iconMd),
            )
            Spacer(Modifier.width(V3Space.xs))
        }
        Text(
            text = label,
            style = V3Type.subheadline.copy(
                fontWeight = FontWeight.SemiBold,
                color = fg,
            ),
        )
    }
}

/**
 * 区块标题 + 右侧动作。
 *
 * ## ⚠️ 当前无调用点（保留作为组件库能力）
 *
 * 首页原先用它渲染「推荐」标题 + 「换一换」，但那是**重复信息** ——
 * 分区 Tab 条首位已经是「推荐」。重复标题已删除，「换一换」移到
 * Tab 条右侧固定位（见 `HomeScreen.ShuffleAction`）。
 *
 * 这个组件本身是通用能力（带粉色竖条的区块标题），后续二级页
 * （收藏夹、历史、UP 空间等）仍会用，因此保留而非删除。
 * 若确认长期不用，再连同 [Radius] / [FontSize] 的引用一起清理。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = BiliV3.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = V3Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 粉色竖条装饰
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.brand),
        )
        Spacer(Modifier.width(V3Space.xs))
        Text(
            text = title,
            style = V3Type.title3.copy(
                color = colors.labelPrimary,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = V3Type.footnote.copy(
                    color = colors.labelSecondary,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(V3Radius.xs))
                    .clickable(onClick = onAction)
                    .padding(horizontal = V3Space.xs, vertical = V3Space.xxs),
            )
        }
    }
}
