package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 播放器占位：加载中 / 失败。
 *
 * ## 为什么失败态必须有内容
 *
 * 播放失败时如果只留一块黑屏，用户无法区分"正在缓冲"和"已经坏了"。
 * 黑屏是这类 App 最容易被误判为崩溃的状态，所以这里必须给出
 * **可读的原因 + 可点的重试**。
 */
@Composable
fun PlayerPlaceholder(
    message: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val colors = BiliTheme.colors

    Box(
        modifier = modifier.background(colors.playerBackground),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = colors.brandPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(Sizes.iconXl + Sizes.iconMd),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(Space.x6),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = FontSize.bodySm,
                        color = colors.onOverlay,
                    ),
                    textAlign = TextAlign.Center,
                )
                if (onRetry != null) {
                    Spacer(Modifier.height(Space.x3))
                    Text(
                        text = "点击重试",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = FontSize.bodySm,
                            color = colors.onOverlay,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.button))
                            .background(colors.brandPrimary)
                            .clickable(onClick = onRetry)
                            .padding(horizontal = Space.x4, vertical = Space.x2),
                    )
                }
            }
        }
    }
}
