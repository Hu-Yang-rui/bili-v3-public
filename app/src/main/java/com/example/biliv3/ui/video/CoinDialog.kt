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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.BiliTheme
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 投币确认弹窗。
 *
 * ## 为什么必须确认
 *
 * 硬币是**不可撤销的消耗品**（投出去不能退回）。
 * 直接点一下「投币」就扣掉，误触代价很高 ——
 * 而互动栏里投币与点赞/收藏相邻，误触概率不低。
 *
 * ## ⚠️ 交互修正：选数量 ≠ 立即投币（第一版的 bug）
 *
 * 第一版是「点卡片直接投该数量」（模仿官方）。但那导致两个问题：
 *
 * 1. **用户无法"切换"数量** —— 点第一张就投出去了，
 *    根本没有"先选 1 再改成 2"的机会。用户报告"无法切换投币数量"。
 * 2. **「同时点赞」形同虚设** —— 勾选发生在点击之后，
 *    而点击已经提交了，勾选永远赶不上。
 *
 * 现在改为标准的「选择 + 确认」两步：
 *
 * ```
 * [1 硬币]  [2 硬币]     ← 点这里是"选中"，不提交
 * ☑ 同时点赞内容          ← 可反复切换
 *      ( 确认投币 )       ← 只有这里才真正提交
 * ```
 *
 * ## 布局
 *
 * ```
 *        ┌──────────────────────────┐
 *        │  [1 硬币]      [2 硬币]   │  ← 选中态放大 + 高亮描边
 *        │  ☑ 同时点赞内容            │
 *        │   硬币余额：3593.6         │
 *        │   [    确认投币    ]       │  ← 主操作按钮
 *        │        ( ✕ )             │  ← 取消
 *        └──────────────────────────┘
 * ```
 *
 * @param coinBalance 硬币余额；null 表示未知（未登录或接口失败），
 *                    此时不显示余额行而不是显示 `0`
 * @param onConfirm 确认投币。(数量, 是否同时点赞)
 */
@Composable
fun CoinDialog(
    coinBalance: Double?,
    onDismiss: () -> Unit,
    onConfirm: (count: Int, alsoLike: Boolean) -> Unit,
) {
    val colors = BiliTheme.colors
    // 默认 2 枚（与官方一致）
    var selected by remember { mutableIntStateOf(DEFAULT_COIN_COUNT) }
    var alsoLike by remember { mutableStateOf(false) }

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
                .background(colors.scrimPanel)
                // 点遮罩 = 取消
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Space.x6)
                    .fillMaxWidth()
                    // ⚠️ 弹层必须用 `surfaceElevated`，不能用 `bgCard`。
                    //
                    // `bgCard` 是**普通卡片**的色（#171B22）。弹层压在半透明遮罩上，
                    // 若与背景里的卡片同色，就"浮不起来" —— 看起来像
                    // 页面里本来就有的一个卡片，而不是盖在上面的一层。
                    //
                    // 深色下的分层手段是**提亮**（投影不可见），
                    // 所以弹层要比卡片再亮一档（#232A35）。
                    .biliCard(
                        shape = RoundedCornerShape(Radius.panel),
                        color = colors.surfaceElevated,
                    )
                    // 弹层本体不穿透到遮罩
                    .clickable(enabled = false) {}
                    .padding(vertical = Space.x5),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ---- 两枚可选卡片：点 = 选中，不提交 ----
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.x4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoinOptionCard(
                        count = 1,
                        selected = selected == 1,
                        onClick = { selected = 1 },
                    )
                    CoinOptionCard(
                        count = 2,
                        selected = selected == 2,
                        onClick = { selected = 2 },
                    )
                }

                Spacer(Modifier.height(Space.x4))

                // ---- 同时点赞 ----
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.interactive))
                        .clickable { alsoLike = !alsoLike }
                        .padding(horizontal = Space.x2, vertical = Space.x1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBoxGlyph(checked = alsoLike)
                    Spacer(Modifier.width(Space.x2))
                    Text(
                        text = "同时点赞内容",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            color = colors.textPrimary,
                        ),
                    )
                }

                // ---- 余额：未知时整行不渲染（显示 0 会误导）----
                if (coinBalance != null) {
                    Spacer(Modifier.height(Space.x2))
                    Text(
                        text = "硬币余额：${formatCoinBalance(coinBalance)}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            color = colors.textSecondarySafe,
                        ),
                    )
                }

                Spacer(Modifier.height(Space.x4))

                // ---- 主操作：确认投币 ----
                // 只有这里才真正提交
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4)
                        .height(CONFIRM_BUTTON_H)
                        .clip(RoundedCornerShape(Radius.interactive))
                        .background(colors.accentCoinBright)
                        .clickable { onConfirm(selected, alsoLike) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "确认投币",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.body,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onAccentCoin,
                        ),
                    )
                }

                Spacer(Modifier.height(Space.x3))

                // ---- 取消（✕）----
                Box(
                    modifier = Modifier
                        .size(CLOSE_BUTTON)
                        .clip(CircleShape)
                        .background(colors.bgHover)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "取消投币",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(Sizes.iconXl),
                    )
                }
            }
        }
    }
}

/**
 * 单枚投币卡片。
 *
 * 选中态用**尺寸 + 描边 + 亮度**三重表达，不只靠颜色 ——
 * 色弱用户也要能看出选了哪张（§4.6「颜色不是唯一载体」）。
 */
@Composable
private fun CoinOptionCard(
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    // 官方配色：暖棕底 + 白字。选中态更亮、更大、带金色描边。
    val base = if (selected) colors.accentCoinBright else colors.accentCoin

    Box(
        modifier = Modifier
            .width(if (selected) CARD_SELECTED_W else CARD_W)
            .height(if (selected) CARD_SELECTED_H else CARD_H)
            // 币数是**可点选的交互元素**（§5.1 硬规则 2：交互元素 4dp）。
            // 原来写 `Radius.button + 2.dp` = 14dp，是卡片时代的残留。
            .clip(RoundedCornerShape(Radius.interactive))
            .background(
                Brush.verticalGradient(
                    listOf(base, base.copy(alpha = 0.82f)),
                ),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.MonetizationOn,
                contentDescription = null,
                tint = colors.onAccentCoin,
                modifier = Modifier.size(
                    if (selected) Sizes.iconXl + Space.x2 else Sizes.iconXl,
                ),
            )
            Spacer(Modifier.height(Space.x1))
            Text(
                text = "$count 硬币",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = if (selected) FontSize.body else FontSize.label,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onAccentCoin,
                ),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/**
 * 复选框图形（自绘）。
 *
 * 不引 Material 的 `Checkbox`：它的默认尺寸 48dp 会把这行撑得过高，
 * 而这里需要紧凑。自绘 16dp 方块 + 勾。
 */
@Composable
private fun CheckBoxGlyph(checked: Boolean) {
    val colors = BiliTheme.colors
    val boxModifier = Modifier
        .size(CHECKBOX_SIZE)
        .clip(RoundedCornerShape(Radius.control))
        .background(if (checked) colors.accentCoinBright else colors.borderStrong)
    Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
        if (checked) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onAccentCoin,
                ),
            )
        }
    }
}

/** 硬币余额显示：整数不带小数点，小数保留 1 位。 */
private fun formatCoinBalance(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

/** 默认投币数（官方默认 2 枚）。 */
private const val DEFAULT_COIN_COUNT = 2

/**
 * 弹窗遮罩。
 *
 * 比通用的 `scrim` 略深：投币是有代价的操作，需要更强的聚焦。
 * 用固定值而不是主题令牌 —— 这个弹窗的视觉是**刻意独立于主题**的
 * （暖棕卡片 + 白字，与深浅色都协调），遮罩也同样固定。
 */

/** 弹窗内主文字色。 */

/** 弹窗内次要文字色（余额、提示）。 */

private val CARD_W = 84.dp
private val CARD_H = 92.dp
private val CARD_SELECTED_W = 104.dp
private val CARD_SELECTED_H = 112.dp
private val CLOSE_BUTTON = 44.dp
private val CHECKBOX_SIZE = 16.dp

/** 确认按钮高度。48dp = 标准触摸目标。 */
private val CONFIRM_BUTTON_H = 44.dp
