package com.example.biliv3.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * 通用选择器 —— **行内展开**，不是弹窗。
 *
 * ## 交互（用户明确要求）
 *
 * ```
 * ┌──────────────────────────────────┐
 * │ 清晰度          高清 720P    ▾   │  ← 选择框（显示当前选中项）
 * ├──────────────────────────────────┤  ← 点击后在**正下方紧挨着**展开
 * │  自动                            │
 * │  高清 1080P                      │
 * │  高清 720P                    ✓  │  ← 选中项打勾
 * │  清晰 480P                       │
 * └──────────────────────────────────┘
 * ```
 *
 * ## ⚠️ 为什么不用 Dialog / 底部面板（这是被否掉的两版）
 *
 * | 版本 | 问题 |
 * |---|---|
 * | 底部弹出面板 | 悬浮窗割裂感强，与触发点脱离，用户要"视线跳一下" |
 * | Material3 `Slider`/`ChipRow` 等 | 曾出现被压变形、黑框穿模 |
 *
 * 行内展开的好处：
 * - **位置确定**：就在选择框正下方，视线不需要移动
 * - **无遮罩**：不压暗背景，不存在"黑框"观感
 * - **无穿模**：不跨 window，不参与父级约束传递
 *
 * ## 展开区样式
 *
 * - 背景用主题的 `bgHover`（浅一档的卡片色），与页面同色系
 * - 圆角 `V3Radius.xs`（4dp），与其它交互元素一致
 * - **不用纯黑、不用半透明遮罩**
 *
 * ## 收起时机（三种都支持）
 *
 * 1. 选择某项 → 立即收起并生效
 * 2. 再次点击选择框 → 收起
 * 3. 点击**其他区域** → 收起（由调用方通过 [expanded] 受控）
 *
 * ## 为什么是受控组件（`expanded` 由外部传入）
 *
 * 需求 3 要求"点击其他区域收起"。行内展开不是 Dialog，
 * 没有天然的"外部点击"通知 —— 只能由**页面级**记录"当前展开的是哪一个"
 * （同一时刻只允许一个展开），点别处时置空。
 *
 * @param label 左侧标签，如「清晰度」
 * @param currentLabel 当前选中项的显示文本
 * @param options 可选项
 * @param selectedKey 当前选中的 key
 * @param expanded 是否展开（受控）
 * @param onToggle 点击选择框，请求切换展开状态
 * @param onSelect 选中某项（调用方负责收起）
 */
@Composable
fun <K> InlinePicker(
    label: String,
    currentLabel: String,
    options: List<PickerOption<K>>,
    selectedKey: K?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors

    Column(modifier = modifier.fillMaxWidth()) {
        // ================= 选择框本体 =================
        //
        // ⚠️ 选择框**不再是卡片** —— 与设置页的其它行（开关/信息/动作）统一。
        //
        // 之前只有选择框套卡、其它行扁平，同一屏出现**两种行型**：
        // 开关行是裸的、下拉行带框，视觉上像"只有这几项被特别标注"，
        // 反而更乱。现在全部走扁平行，靠留白分组。
        //
        // 展开区（下方）仍然自带卡片 —— 它是"临时展开的内容"，
        // 需要与常规行区分开。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 整行可点（触摸目标大）
                .clickable(onClick = onToggle)
                .padding(horizontal = V3Space.md, vertical = V3Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = V3Type.callout.copy(
                    color = colors.labelPrimary,
                ),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = currentLabel,
                style = V3Type.caption1.copy(
                    // 选中值用品牌色，一眼看出"这是当前值"。
                    // ⚠️ 用 textBrandSafe 而非 brandPrimary —— 浅色下
                    // brandPrimary(#E8578A) 做**文字**只有 3.4:1，不达 AA 4.5:1。
                    color = colors.brandBiliText,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(V3Space.xxs))
            PickerArrow(expanded = expanded)
        }

        // ================= 展开区（正下方、紧挨着）=================
        AnimatedVisibility(
            visible = expanded,
            // 纵向展开/收起：位置固定在选择框下方，不做位移，
            // 避免"滑入滑出"造成的穿模错觉
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = V3Space.sm,
                        end = V3Space.sm,
                        bottom = V3Space.xxs,
                    )
                    .clip(RoundedCornerShape(V3Radius.xs))
                    // ⚠️ 用主题的 bgHover，**不用黑色、不用半透明遮罩** ——
                    // 这是"无黑框、不穿模"的关键
                    .background(colors.bgTertiary)
                    .padding(vertical = V3Space.xxs),
            ) {
                options.forEach { opt ->
                    PickerRow(
                        label = opt.label,
                        description = opt.description,
                        badge = opt.badge,
                        locked = opt.locked,
                        selected = opt.key == selectedKey,
                        onClick = { onSelect(opt.key) },
                    )
                }
            }
        }
    }
}

/**
 * 展开区里的一行选项。
 *
 * 选中态用**颜色 + 勾选图标**双重表达（不只靠颜色）——
 * 色弱用户也要能看出选的是哪个（§4.6「颜色不是唯一载体」）。
 */
@Composable
private fun PickerRow(
    label: String,
    description: String?,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
    /**
     * 是否因权限受限（未发版）。
     *
     * ⚠️ **受限项仍然可点** —— 点了由调用方弹「需要大会员」提示。
     * 做成禁用的话用户不知道原因（需求第四条要求区分状态）。
     */
    locked: Boolean = false,
) {
    val colors = BiliV3.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = V3Space.sm, vertical = V3Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 🔒 锁形图标：**小面积信息，不抢主视觉**（需求第五条）
                if (locked) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "需要大会员",
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(V3Size.iconXs),
                    )
                    Spacer(Modifier.width(V3Space.xxs))
                }
                Text(
                    text = label,
                    style = V3Type.callout.copy(
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        // 受限项用次级色：表达"看得到但用不了"，
                        // 但**不是** disabled 灰（它仍可点）
                        color = when {
                            selected -> colors.brand
                            locked -> colors.labelSecondary
                            else -> colors.labelPrimary
                        },
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(V3Space.xs))
                    Text(
                        text = badge,
                        style = V3Type.caption2.copy(
                            color = colors.labelOnBrand,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(V3Radius.xs))
                            .background(colors.brand)
                            .padding(horizontal = V3Space.xs, vertical = V3Space.tagVertical),
                    )
                }
                // 「大会员」标记：纯文字小角标（不用品牌色块，避免抢视觉）
                if (locked) {
                    Spacer(Modifier.width(V3Space.xs))
                    Text(
                        text = "大会员",
                        style = V3Type.caption2.copy(
                            color = colors.accentCoin,
                        ),
                    )
                }
            }
            if (description != null) {
                Spacer(Modifier.height(V3Space.hairline))
                Text(
                    text = description,
                    style = V3Type.caption1.copy(
                        color = colors.labelSecondary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 选中打勾；未选中用等宽占位保持行高一致（避免切换时跳动）
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选中",
                tint = colors.brand,
                modifier = Modifier.size(V3Size.iconMd),
            )
        } else {
            Spacer(Modifier.width(V3Size.iconMd))
        }
    }
}

/**
 * 展开箭头。
 *
 * 展开时旋转 180° —— 一个图标表达两态，不额外引资源。
 * 抽出成组件是为了让所有选择器的箭头行为一致。
 */
@Composable
fun PickerArrow(expanded: Boolean) {
    Icon(
        imageVector = Icons.Filled.KeyboardArrowDown,
        contentDescription = if (expanded) "收起" else "展开",
        tint = BiliV3.colors.labelTertiary,
        modifier = Modifier
            .size(V3Size.iconMd)
            .rotate(if (expanded) 180f else 0f),
    )
}

/**
 * 选择器选项。
 *
 * @param key 业务键（清晰度码 / 倍速浮点 / 档位浮点等）
 * @param label 主文本
 * @param description 次要说明（可空）
 * @param badge 角标，如「AI 翻译」
 * @param locked 是否**因权限受限**（未发版）
 */
data class PickerOption<K>(
    val key: K,
    val label: String,
    val description: String? = null,
    val badge: String? = null,
    /**
     * 是否**因权限受限**（未发版）。
     *
     * ## 为什么是"受限"而不是"禁用"
     *
     * 会员清晰度（4K / 1080P60）**保留在列表里并可点击** ——
     * 点了弹「需要大会员」提示（需求第三条）。
     *
     * 如果做成 `enabled = false`，用户只会看到一个点不动的灰项，
     * **不知道原因**（"是网络问题？还是不支持？"）——
     * 那正是需求第四条要求区分的状态。
     *
     * 所以 UI 上：显示 🔒 + **保持可点**。
     */
    val locked: Boolean = false,
)
