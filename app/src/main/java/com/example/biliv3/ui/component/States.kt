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

/**
 * 把异常翻译成用户看得懂的文案。
 *
 * UI 层只认这几种，具体 code 在这里统一翻译 —— 页面里不再写 when(code)。
 */
fun userMessageFor(error: Throwable): String = when (error) {
    is BiliException -> error.userMessage
    else -> {
        val s = error.message.orEmpty()
        when {
            s.contains("Unable to resolve host") || s.contains("timeout", true) ->
                "网络不可用，请检查连接后重试"
            s.contains("Failed to connect") -> "连接服务器失败，请稍后重试"
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
