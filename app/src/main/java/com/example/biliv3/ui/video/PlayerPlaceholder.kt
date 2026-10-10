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
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

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
    val colors = BiliV3.colors

    Box(
        modifier = modifier.background(colors.playerBackground),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = colors.brand,
                strokeWidth = V3Space.progressTrack,
                modifier = Modifier.size(V3Size.iconLg + V3Size.iconMd),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(V3Space.xl),
            ) {
                Text(
                    text = message,
                    style = V3Type.footnote.copy(
                        color = colors.labelOnMedia,
                    ),
                    textAlign = TextAlign.Center,
                )
                if (onRetry != null) {
                    Spacer(Modifier.height(V3Space.sm))
                    Text(
                        text = "点击重试",
                        style = V3Type.footnote.copy(
                            color = colors.labelOnMedia,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(V3Radius.xs))
                            .background(colors.brand)
                            .clickable(onClick = onRetry)
                            .padding(horizontal = V3Space.md, vertical = V3Space.xs),
                    )
                }
            }
        }
    }
}
