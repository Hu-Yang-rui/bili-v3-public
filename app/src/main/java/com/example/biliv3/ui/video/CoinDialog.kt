package com.example.biliv3.ui.video

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.semantics
import com.example.biliv3.design.tokens.Motion
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
                    // 🔴 v1.5.3 重做：**透明轻量浮层**（用户要求"保留视频背景"）
                    //
                    // ## 上一版的问题
                    //
                    // 用 `biliCard(color = surfaceElevated)` 铺了一块**不透明面板** ——
                    // 投币发生在视频播放中，一整块 #232A35 会把画面糊掉，
                    // 用户看不到自己正在投币的那个视频。
                    //
                    // ## 现在
                    //
                    // - **不铺底**：只有内容本身浮在画面上（遮罩已足够压暗背景）
                    // - 内容靠**间距 + 字重**分组，不靠容器
                    // - 保持 `scrimPanel` 遮罩（否则文字在亮画面上读不清）
                    //
                    // 这与 §5.1「无卡片」一致：投币面板是**操作**不是内容容器。
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

                // ---- 🔴 v1.5.3：小人提硬币 + 明确投出数量 ----
                //
                // 用户要求："中间设计一个简洁的小人提着用户所选硬币的视觉元素，
                // 并根据所选硬币数量或类型及时更新状态"。
                //
                // 这里用**自绘几何**（不引第三方图 / 不用 emoji）：
                // 一个圆头 + 躯干的小人，手臂垂下提着 `selected` 枚硬币。
                // 硬币数量**跟着选择实时变**（不是静态装饰）。
                CoinCarrier(count = selected)

                Spacer(Modifier.height(Space.x2))

                Text(
                    text = "将投出 $selected 枚硬币",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        fontWeight = FontWeight.Medium,
                        color = colors.accentCoinBright,
                    ),
                )

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
 * 「小人提硬币」——投币数量指示图形（v1.5.3）。
 *
 * ## 为什么自绘而不是用图标/emoji
 *
 * 1. **数量要动态**：`count` 枚硬币要真的画 `count` 个，
 *    Material 图标是固定字形，做不到"跟着选择变"。
 * 2. §5.1 明确禁止 emoji 当结构图标。
 * 3. 自绘只有几行 Canvas，比引一张 PNG 更轻、且天然适配深色主题。
 *
 * ## 形状（简洁几何，不用细节）
 *
 * ```
 *    ●        ← 头（圆）
 *   ─┼─       ← 躯干 + 双臂
 *    │
 *   ╱ ╲       ← 腿
 *    ◉◉       ← 手里提着的硬币（数量 = count）
 * ```
 *
 * 硬币带**外描边**，在深色遮罩上也看得清；数量变化时有缩放动画
 * （只服务反馈，不装饰 —— §5.1 原则 4）。
 */
@Composable
private fun CoinCarrier(count: Int) {
    val colors = BiliTheme.colors
    // 数量变化时的轻微缩放：给"我改了选择"一个即时反馈
    val scale by animateFloatAsState(
        targetValue = if (count > 1) 1.06f else 1f,
        animationSpec = tween(Motion.FADE_MS, easing = Motion.standard),
        label = "coinCarrierScale",
    )

    val figure = colors.textSecondarySafe
    val coin = colors.accentCoinBright

    Row(
        modifier = Modifier
            .height(CARRIER_H)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // 小人
        Canvas(
            modifier = Modifier
                .width(CARRIER_FIGURE_W)
                .height(CARRIER_H),
        ) {
            val w = size.width
            val h = size.height
            val stroke = CARRIER_STROKE.toPx()
            val cx = w / 2f
            val headR = h * 0.16f

            // 头
            drawCircle(
                color = figure,
                radius = headR,
                center = Offset(cx, headR),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            )
            // 躯干（竖线）
            val torsoTop = headR * 2f + stroke
            val torsoBottom = h * 0.62f
            drawLine(
                color = figure,
                start = Offset(cx, torsoTop),
                end = Offset(cx, torsoBottom),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            // 双臂（横线）
            drawLine(
                color = figure,
                start = Offset(cx - w * 0.22f, torsoTop + h * 0.10f),
                end = Offset(cx + w * 0.22f, torsoTop + h * 0.10f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            // 双腿
            val legY = torsoBottom
            drawLine(
                color = figure,
                start = Offset(cx, legY),
                end = Offset(cx - w * 0.20f, h),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = figure,
                start = Offset(cx, legY),
                end = Offset(cx + w * 0.20f, h),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        Spacer(Modifier.width(Space.x3))

        // 手里提着的硬币：数量 = 用户当前选择
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(count) { i ->
                Canvas(
                    modifier = Modifier
                        .size(CARRIER_COIN)
                        // ⚠️ 用 index 作 key 是安全的：列表长度只会是 1 或 2，
                        // 且顺序固定（不是动态列表）。
                        .semantics { },
                ) {
                    val r = size.minDimension / 2f - CARRIER_STROKE.toPx()
                    drawCircle(color = coin, radius = r)
                    drawCircle(
                        color = colors.onAccentCoin.copy(alpha = 0.55f),
                        radius = r * 0.45f,
                    )
                }
                // i 只用于让编译器知道是不同实例；视觉上不做差异
                if (i == 0 && count > 1) Spacer(Modifier.width(Space.micro))
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

/** 小人提硬币图形的尺寸（v1.5.3）。 */
private val CARRIER_H = 44.dp
private val CARRIER_FIGURE_W = 26.dp
private val CARRIER_COIN = 12.dp
private val CARRIER_STROKE = 1.5.dp

private val CARD_W = 84.dp
private val CARD_H = 92.dp
private val CARD_SELECTED_W = 104.dp
private val CARD_SELECTED_H = 112.dp
private val CLOSE_BUTTON = 44.dp
private val CHECKBOX_SIZE = 16.dp

/** 确认按钮高度。48dp = 标准触摸目标。 */
private val CONFIRM_BUTTON_H = 44.dp
