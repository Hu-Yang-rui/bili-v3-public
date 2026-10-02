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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.NotLoggedInException
import com.example.biliv3.design.tokens.FontFamilies
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

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
    val colors = BiliTheme.colors

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
                horizontal = Space.x8,
                vertical = if (compact) Space.x5 else Space.x12,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(if (compact) Sizes.iconXl else 48.dp),
        )
        Spacer(Modifier.height(if (compact) Space.x3 else Space.x4))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = colors.textSecondarySafe,
            ),
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Spacer(Modifier.height(Space.x2))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.textSecondary,
                ),
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.x5))
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
    val colors = BiliTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = Space.x8,
                vertical = if (compact) Space.x5 else Space.x12,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(if (compact) Sizes.iconXl else 48.dp),
        )
        Spacer(Modifier.height(if (compact) Space.x3 else Space.x4))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = colors.textSecondarySafe,
            ),
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Spacer(Modifier.height(Space.x2))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.textSecondary,
                ),
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            Spacer(Modifier.height(Space.x5))
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
    val colors = BiliTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = Space.x4,
                vertical = if (compact) Space.x5 else Space.x8,
            ),
        verticalArrangement = Arrangement.Center,
    ) {
        PromptLine(
            text = title,
            symbol = "$",
            textColor = colors.textSecondarySafe,
        )
        if (description != null) {
            Spacer(Modifier.height(Space.x2))
            // 说明行缩进对齐提示符之后的文字（不重复画 $）
            Row {
                Spacer(Modifier.width(Space.x4 + Space.x2))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamilies.mono,
                        fontSize = FontSize.label,
                        lineHeight = FontSize.labelLine,
                        color = colors.textTertiary,
                    ),
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.x4))
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
    val colors = BiliTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = Space.x4,
                vertical = if (compact) Space.x4 else Space.x8,
            ),
        contentAlignment = if (compact) Alignment.CenterStart else Alignment.Center,
    ) {
        StatusLine(
            text = text,
            symbol = "$",
            showCursor = true,
            textColor = colors.textSecondarySafe,
        )
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
    val colors = BiliTheme.colors

    val bg = when (variant) {
        BrandButtonVariant.Filled -> colors.brandPrimary
        BrandButtonVariant.Outline, BrandButtonVariant.Text -> colors.bgCard
    }
    val fg = when (variant) {
        BrandButtonVariant.Filled -> colors.textOnBrand
        BrandButtonVariant.Outline, BrandButtonVariant.Text -> colors.textBrandSafe
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Radius.button),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContainerColor = colors.skeletonBase,
            disabledContentColor = colors.textTertiary,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
        ),
        border = if (variant == BrandButtonVariant.Outline) {
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = colors.brandPrimary,
            )
        } else {
            null
        },
        contentPadding = contentPadding ?: PaddingValues(
            horizontal = Space.x4,
            vertical = Space.x2 + 2.dp,
        ),
        modifier = modifier,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(Sizes.iconMd),
            )
            Spacer(Modifier.width(Space.x2))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = FontSize.titleMd,
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
    val colors = BiliTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 粉色竖条装饰
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.brandPrimary),
        )
        Spacer(Modifier.width(Space.x2))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.textSecondarySafe,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.badge))
                    .clickable(onClick = onAction)
                    .padding(horizontal = Space.x2, vertical = Space.x1),
            )
        }
    }
}
