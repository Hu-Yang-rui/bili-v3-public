package com.example.biliv3.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 直播间弹幕输入条（v1.6.5）。
 *
 * ## 布局
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ [ 😀 ] [ 说点什么…                     ] [发送] │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * ## 为什么"发送"在未登录时**不渲染**
 *
 * 与用户菜单、烂梗库一致：不显示灰按钮。未登录时显示的是
 * 「登录后可以发送弹幕」这句说明 —— 用户知道下一步做什么，
 * 而不是对着一个点不动的按钮猜。
 *
 * ## 为什么输入框在发送中**不清空**
 *
 * 项目既有规则（§7.15-86）：**输入框不能在请求前清空**，
 * 清空必须由"发送成功"决定。否则发送失败时用户的内容就没了 ——
 * 而失败恰恰是最需要保留内容重试的时候。
 */
@Composable
fun LiveChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    /** 能否发送（已登录）。false 时整个输入区换成说明。 */
    canSend: Boolean,
    /** 发送在途（禁用按钮 + 显示状态）。 */
    sending: Boolean,
    /** 上次失败原因（null = 无）。显示在输入框下方。 */
    error: String?,
    onSend: () -> Unit,
    onOpenMemes: () -> Unit,
    onLoginRequired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        // ---- 失败原因（如实显示，不吞）----
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    lineHeight = FontSize.labelLine,
                    color = colors.stateError,
                ),
                modifier = Modifier.padding(
                    start = Space.x3,
                    end = Space.x3,
                    bottom = Space.micro,
                ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.x2, vertical = Space.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!canSend) {
                // 未登录：不渲染输入框与发送按钮，直接给出口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.interactive))
                        .background(colors.bgHover)
                        .clickable(onClick = onLoginRequired)
                        .padding(horizontal = Space.x3, vertical = Space.x3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "登录后可以发送弹幕",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.textBrandSafe,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "去登录",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            fontWeight = FontWeight.Medium,
                            color = colors.textBrandSafe,
                        ),
                    )
                }
                return@Row
            }

            // ---- 烂梗库入口 ----
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(RoundedCornerShape(Radius.interactive))
                    .clickable(onClick = onOpenMemes),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEmotions,
                    contentDescription = "烂梗库",
                    tint = colors.textSecondarySafe,
                    modifier = Modifier.size(Sizes.iconLg),
                )
            }

            // ---- 输入框 ----
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.interactive))
                    .background(colors.bgHover)
                    .padding(horizontal = Space.x3, vertical = Space.x2),
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "说点什么…",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.textTertiary,
                        ),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.bodySm,
                        color = colors.textPrimary,
                    ),
                    cursorBrush = SolidColor(colors.brandPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.width(Space.x2))

            // ---- 发送 ----
            val enabled = !sending && text.isNotBlank()
            Text(
                text = if (sending) "发送中" else "发送",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) colors.textBrandSafe else colors.textTertiary,
                ),
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.interactive))
                    .clickable(enabled = enabled, onClick = onSend)
                    .padding(horizontal = Space.x3, vertical = Space.x2),
            )
        }

        Spacer(Modifier.height(Space.micro))
    }
}
