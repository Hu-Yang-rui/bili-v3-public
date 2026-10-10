package com.example.biliv3.ui.video

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Type
import com.example.biliv3.design.v3.GlassDialog

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
    val colors = BiliV3.colors

    // 🔴 v3：改用设计系统的 [GlassDialog]，不再自己拼 Dialog + 圆角容器。
    //
    // ## 改之前是什么样（以及为什么不对）
    //
    // 手写 `Dialog` + `Column(clip(lg) + background(bgSecondaryElevated))` ——
    // 一个**实心深灰圆角盒**，看起来像"另一种卡片"。
    //
    // ## 为什么弹层该用玻璃（而内容页不该）
    //
    // 判据（§7.37 坑 219）：**模糊玻璃适合「大面积、静态、内容之上」的浮层**。
    // 弹层三条全中：
    // - **大面积**：占了屏幕中央一大块，模糊半径 3.6dp 的成本摊得开
    // - **静态**：弹出后不动，不需要每帧重抓背景
    // - **内容之上**：它确实压在页面内容上面 —— 玻璃"透出底下的内容"
    //   才有物理意义（这也正是 `GlassDialog` 用 `UltraThin` 的原因）
    //
    // ⚠️ 与播放器控件的区别：那些是"小面积 + 压在**动态**视频画面上"，
    // 所以走扁平（`controlFlat`）。**同一个 App 里两种材质并存是对的**，
    // 判据是场景而不是"统一用某一种"。
    GlassDialog(
        onDismiss = onDismiss,
        title = "大会员专享",
        confirmText = "知道了",
        onConfirm = onDismiss,
        body = {
            // 具体能力名（小面积信息，不抢主视觉）
            if (featureName.isNotEmpty()) {
                Text(
                    text = featureName,
                    style = V3Type.caption2,
                    color = colors.accentCoin,
                )
                Spacer(Modifier.height(V3Space.xs))
            }

            Text(
                text = "当前功能需要 Bilibili 大会员权限。",
                style = V3Type.footnote,
                color = colors.labelSecondary,
            )
            Spacer(Modifier.height(V3Space.xxs))
            Text(
                text = "当前账号无法使用此功能。",
                style = V3Type.footnote,
                color = colors.labelSecondary,
            )
        },
    )
}
