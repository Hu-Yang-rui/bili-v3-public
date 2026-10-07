package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 会员专享提示（**未发版**）。
 *
 * ---
 *
 * # 这个弹层**只解释**，不做任何解锁
 *
 * 它告诉用户：**当前功能为什么不可用、需要什么权限**。
 *
 * 🔴 **不绕过会员权限**：
 * - 不伪造 `isVip`
 * - 不调用任何"解锁"接口
 * - 不提供购买页面（需求第六条：不要自己伪造购买页面）
 *
 * 文案严格按需求第六条：
 * ```
 * 大会员专享
 *
 * 当前功能需要 Bilibili 大会员权限。
 *
 * 当前账号无法使用此功能。
 * ```
 *
 * # 为什么只有「知道了」
 *
 * 需求说"如果当前 App 已经有官方会员页面跳转能力，可以增加「查看会员」"。
 *
 * ⚠️ **本项目没有这个能力** —— 没有接官方会员页 SDK，也没有可跳的
 * 官方页面。所以**只放「知道了」**，不放一个点了没反应的
 * 「查看会员」死入口（§1.6）。
 *
 * 更不做"自动打开第三方网站"—— 需求明确禁止。
 */
@Composable
fun VipRequiredSheet(
    /** 具体是哪个能力（如「4K 超高清」），显示在标题下方。 */
    featureName: String,
    onDismiss: () -> Unit,
) {
    val colors = BiliTheme.colors

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.scrimPanel)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Space.x4)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.panel))
                    // 弹层用 surfaceElevated（比卡片亮一档）—— 深色下分层靠提亮
                    .background(colors.surfaceElevated)
                    .clickable(enabled = false) {}
                    .padding(Space.x4),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = colors.accentCoin,
                        modifier = Modifier.size(Sizes.iconLg),
                    )
                    Spacer(Modifier.width(Space.x2))
                    Text(
                        text = "大会员专享",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = FontSize.titleMd,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        ),
                    )
                }

                // 具体能力名（小面积信息，不抢主视觉）
                if (featureName.isNotEmpty()) {
                    Spacer(Modifier.height(Space.x1))
                    Text(
                        text = featureName,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            color = colors.accentCoin,
                        ),
                    )
                }

                Spacer(Modifier.height(Space.x3))

                Text(
                    text = "当前功能需要 Bilibili 大会员权限。",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.bodySm,
                        lineHeight = FontSize.bodySmLine,
                        color = colors.textSecondarySafe,
                    ),
                )
                Spacer(Modifier.height(Space.x1))
                Text(
                    text = "当前账号无法使用此功能。",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.bodySm,
                        lineHeight = FontSize.bodySmLine,
                        color = colors.textSecondarySafe,
                    ),
                )

                Spacer(Modifier.height(Space.x4))
                RuleLine(color = Rule.subtle)
                Spacer(Modifier.height(Space.x3))

                // ---- 「知道了」----
                //
                // 右对齐的文本按钮，不铺满整行 ——
                // 与项目其它弹层的次级动作一致。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = "知道了",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = FontSize.bodySm,
                            fontWeight = FontWeight.Medium,
                            color = colors.brandPrimary,
                        ),
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.interactive))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = Space.x4, vertical = Space.x2),
                    )
                }
            }
        }
    }
}
