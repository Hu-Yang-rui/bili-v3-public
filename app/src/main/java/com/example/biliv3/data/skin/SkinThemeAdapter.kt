package com.example.biliv3.data.skin

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 装扮 → 主题的**适配层**（未发版）。
 *
 * ---
 *
 * # 🔴 为什么必须有这一层（任务书第二十条）
 *
 * > 不允许直接让某套装扮覆盖所有 UI 颜色。必须经过 SkinThemeAdapter。
 *
 * 一套装扮的 `color` / `color_second_page` 是**它自己的设计**，
 * 与本项目的「乙·质感·无卡片」体系无关。如果直接把 `color`
 * 当成"全局主色"，会立刻出问题：
 *
 * - 装扮 `color` 可能是**浅色**（实测 `一猫人` 是 `#ffffff`），
 *   而本项目是**深色底** → 白字变白底，**文字全看不见**
 * - 装扮可能只给一两个颜色 → 其它颜色落空 → 界面花掉
 *
 * 所以本层只做**受控映射**：
 *
 * ```
 * FakeSkin → SkinThemeAdapter → 少量 Token 覆盖 → AppTheme
 * ```
 *
 * # 只覆盖「强调色」与「装饰」，不覆盖「结构色」
 *
 * | 允许覆盖 | 不允许覆盖 |
 * |---|---|
 * | 品牌强调色（按钮/选中态）| 背景明度分层（`bgBase`/`bgCard`）|
 * | 底部导航选中/未选中色 | 正文文字色（`textPrimary`）|
 * | 背景**图**（首页顶部/我的页）| 发丝线颜色（`Rule.color`）|
 *
 * 理由：**结构色决定可读性**，装扮色只决定"个性"。
 * 让装扮改结构色，等于让每套装扮都重写一遍设计规范。
 *
 * # 对比度兜底
 *
 * 装扮色若与背景对比度不足（如浅色主题的 `#ffffff` 用在深底上），
 * 会**回退到默认强调色** —— 宁可少一点个性，也不要看不清。
 */
@Immutable
data class SkinThemeAdapter(
    /** 是否启用了装扮（false = 完全默认）。 */
    val active: Boolean = false,
    /** 品牌强调色（覆盖 `brandPrimary`）。 */
    val brandPrimary: Color? = null,
    /** 底部导航未选中色。 */
    val navUnselected: Color? = null,
    /** 底部导航选中色。 */
    val navSelected: Color? = null,
    /** 侧栏背景色（仅当装扮提供时）。 */
    val sideBackground: Color? = null,
) {
    companion object {

        /** 默认（无装扮）适配器。 */
        val DEFAULT = SkinThemeAdapter()

        /**
         * 最低对比度阈值。
         *
         * ## 为什么是 2.0
         *
         * WCAG 对**大号文字/图形**的建议是 ≥ 3.0，正文 ≥ 4.5。
         * 但这里比的是"装扮强调色 vs 页面背景"，
         * 强调色多用于**按钮底/选中态**（大色块），不是正文。
         *
         * 取 **2.0** 是**保守下限**：低于它的颜色在深底上几乎不可辨
         * （实测装扮 `color=#ffffff` 与深底对比度约 19，远超阈值；
         * 而一个 `#1a1a1a` 这类近底色会低于 2.0 被拦下）。
         */
        const val MIN_CONTRAST = 2.0

        /**
         * 从装扮构建适配器。
         *
         * @param skin 当前装扮；null 或默认 → 返回 [DEFAULT]
         * @param background 当前页面的**实际背景色**（用于对比度校验）
         */
        fun from(skin: FakeSkin?, background: Color): SkinThemeAdapter {
            if (skin == null || skin.isDefault) return DEFAULT

            // 🔴 受控映射：只取这几个，其余装扮字段**刻意忽略**
            val brand = skin.primary?.takeIf { contrast(it, background) >= MIN_CONTRAST }
            val nav = skin.navColor?.takeIf { contrast(it, background) >= MIN_CONTRAST }
            val navSel = skin.navColorSelected?.takeIf { contrast(it, background) >= MIN_CONTRAST }
            val side = skin.sideColor?.takeIf { contrast(it, background) >= MIN_CONTRAST }

            // 一个都没通过校验 → 当作未启用（而不是启用一个"全默认"的空壳）
            if (brand == null && nav == null && navSel == null && side == null) {
                return DEFAULT
            }
            return SkinThemeAdapter(
                active = true,
                brandPrimary = brand,
                navUnselected = nav,
                navSelected = navSel,
                sideBackground = side,
            )
        }

        /**
         * 相对亮度对比度（WCAG 公式）。
         *
         * `(L1 + 0.05) / (L2 + 0.05)`，L 为相对亮度。
         */
        fun contrast(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            val hi = maxOf(la, lb)
            val lo = minOf(la, lb)
            return (hi + 0.05) / (lo + 0.05)
        }

        /** 相对亮度（WCAG 2.x）。 */
        private fun luminance(c: Color): Double {
            fun ch(v: Float): Double {
                val d = v.toDouble()
                return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
    }

    /**
     * 把装扮的强调色**应用**到默认色板，产出一份新的色板。
     *
     * ## 🔴 只改这几个字段，其余**原样保留**
     *
     * 这是"受控映射"的落地点。被改的只有：
     *
     * | 字段 | 为什么可以改 |
     * |---|---|
     * | `brandPrimary` 及其 hover/active/dim 派生 | 按钮/选中态用色，**不影响可读性** |
     *
     * **不改**：`bgBase` / `bgCard` / `surfaceElevated`（明度分层）、
     * `textPrimary` / `textSecondary*`（文字可读性）、`borderHairline`（发丝线）。
     *
     * 原因见类文档：结构色决定可读性，装扮色只决定个性。
     */
    fun applyTo(base: com.example.biliv3.design.tokens.BiliColors):
        com.example.biliv3.design.tokens.BiliColors {
        val brand = brandPrimary ?: return base
        return base.copy(
            brandPrimary = brand,
            // 派生色跟着主色走，保持 hover/active 的层级关系
            brandPrimaryHover = brand.copy(alpha = (brand.alpha * 0.85f).coerceAtLeast(0.3f)),
            brandPrimaryActive = brand.copy(alpha = (brand.alpha * 0.7f).coerceAtLeast(0.3f)),
            brandPrimaryDim = brand.copy(alpha = (brand.alpha * 0.5f).coerceAtLeast(0.2f)),
        )
    }
}
