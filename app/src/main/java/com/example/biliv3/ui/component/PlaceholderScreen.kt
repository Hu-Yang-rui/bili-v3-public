package com.example.biliv3.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 未实现页面的占位。
 *
 * ## 为什么不直接留空 lambda
 *
 * `AGENTS.md` §1 的验收线之一是「**空入口数量 = 0**」，
 * §3.1 也把「点了没反应的死入口」列为反模式。
 *
 * 但功能要分阶段做，中间态必然存在"路由已通、页面未建"。
 * 此时**跳到占位页**远好于"点了毫无反应"：
 *
 * - 用户知道"这个入口是有的，只是还没做"，而不是以为 App 卡了
 * - 开发时能立刻验证路由与参数传递是否正确
 * - 后续只需替换目的地 composable，路由层零改动
 *
 * ## 顶栏与返回
 *
 * 二级页必须**有可见的返回入口**。只依赖系统返回键/返回手势不够 ——
 * 部分设备是三大金刚键、部分用户不知道有手势返回，
 * 没有返回按钮会让人以为"进死胡同了"。
 */
@Composable
fun PlaceholderScreen(
    title: String,
    detail: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 顶栏（仅在有返回时渲染）----
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .biliCard(
                        elevation = 0.dp,
                        shape = RoundedCornerShape(
                            bottomStart = Radius.card,
                            bottomEnd = Radius.card,
                        ),
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(Sizes.topBarMobile)
                    .padding(horizontal = Space.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(Space.minTouchTarget)
                        .clip(CircleShape)
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
                Spacer(Modifier.size(Space.x1))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = FontSize.titleMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                    maxLines = 1,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Space.x8),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Construction,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(Sizes.iconXl * 2),
            )
            Spacer(Modifier.height(Space.x4))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.x2))
            Text(
                text = detail ?: "该功能尚未实现",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FontSize.bodySm,
                    color = colors.textSecondarySafe,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}
