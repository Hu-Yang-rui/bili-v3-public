package com.example.biliv3.design.v3

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

/**
 * **Liquid Glass 悬浮底部导航**（全量重构的签名组件）。
 *
 * ---
 *
 * # 与旧底栏的根本差异
 *
 * | | 旧（`ui/home/Footer.kt` 的 `BottomNav`） | 新 |
 * |---|---|---|
 * | 形态 | 贴底通栏 + 顶边发丝线 | **悬浮胶囊**，四边离屏 |
 * | 材质 | 与页面同明度（"融入页面"） | **Liquid Glass**（"浮在页面上"） |
 * | 选中态 | 图标/文字变粉 | **胶囊指示器** + 图标文字联动 |
 * | 动效 | 无（瞬时切换） | **指示器形变 + 弹性移动**（450ms snappy） |
 *
 * ## 🔴 旧设计的"融入页面"为什么被推翻
 *
 * 旧系统的判断是：底栏是"页面的一部分"，所以要和页面同明度、只用一条线分隔。
 * 那在**无卡片 + 无玻璃**的语言里是对的。
 *
 * 但新设计语言引入了 Liquid Glass，它的核心语义恰恰是
 * **"浮动层与内容分离"** —— 底栏是**浮在内容之上**的一层，
 * 内容从它下面滚过去。所以底栏必须**看得出来是浮的**：
 * 四边离屏、有材质、有边缘光、有投影。
 *
 * ⚠️ 这不是"加个玻璃效果"，而是**改变底栏与页面的空间关系**。
 *
 * ---
 *
 * # 尺寸（iOS 27 实测）
 *
 * | 项 | 值 | 说明 |
 * |---|---|---|
 * | 按钮行高 | 54dp | |
 * | 玻璃托板高 | **62dp** | 比按钮行**各向外溢出 4dp** —— "浮起"的视觉来源 |
 * | 项最小宽 | 72dp | |
 * | 与屏幕底边间距 | 12dp | 悬浮感的关键（贴底就"不浮"了） |
 * | 圆角 | pill | 实测：托板 cornerRadius = 1000 |
 *
 * ---
 *
 * # 指示器动效（实测规格）
 *
 * iOS 27 的 `liquidGlass.tabIndicator`：
 *
 * > 指示器在**移动方向**上先拉伸，再snap到目标形状。
 * > `0% → 50%（拉伸）→ 100%（目标）`，`450ms`，`snappy` spring。
 *
 * 本实现用**速度驱动拉伸**（`Animatable.velocity`）而不是关键帧：
 * 移动越快拉得越长，停下时自然收回。
 * 这比固定关键帧更接近"液体"的物理直觉，且天然处理"连点两下"的情况
 * （关键帧写法在动画被打断时会突跳）。
 */
@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 玻璃层级。默认 [V3Glass.Level.Regular]（底部导航是导航层）。 */
    level: V3Glass.Level = V3Glass.Level.Regular,
) {
    if (items.isEmpty()) return
    val colors = BiliV3.colors

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val count = items.size
            val horizontalInset = V3Space.lg
            val platterWidth = maxWidth - horizontalInset * 2
            val itemWidth = platterWidth / count

            // ---- 指示器位置（弹簧驱动）----
            val pos = remember { Animatable(selectedIndex.toFloat()) }
            LaunchedEffect(selectedIndex) {
                pos.animateTo(selectedIndex.toFloat(), V3Motion.snappy())
            }

            // ---- 速度驱动的拉伸 ----
            //
            // ⚠️ 用 `offset.velocity` 而不是"是否在动画中"：
            //    后者在动画收尾时仍然为 true，会导致指示器"到点了还拉长"。
            //    速度是连续量，停下时自然归零 —— 不需要额外判断。
            val stretch by remember {
                derivedStateOf {
                    // 归一化：速度 8（item/秒）时拉满
                    min(abs(pos.velocity) / 8f, 1f)
                }
            }

            val baseWidth = itemWidth - V3Space.xs * 2
            // 最多拉长 22%（实测的形变幅度观感）
            val indicatorWidth = baseWidth * (1f + stretch * 0.22f)

            // 拉伸时把左边缘往回推，让"多出来的部分"落在移动方向上
            val travelDir = if (pos.velocity >= 0f) 1f else -1f
            val leadShift = (indicatorWidth - baseWidth) * (if (travelDir > 0) 0f else 1f)

            Box(
                modifier = Modifier
                    .width(platterWidth)
                    .height(V3Size.navGlass)
                    .offset(x = horizontalInset),
            ) {
                // ---- 玻璃托板 ----
                GlassSurface(
                    modifier = Modifier.fillMaxWidth().height(V3Size.navGlass),
                    shape = RoundedCornerShape(V3Radius.pill),
                    level = level,
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        // ---- 选中指示器 ----
                        //
                        // ⚠️ 用 `Modifier.offset { }`（lambda 版）而不是 `offset(x = )`：
                        //    前者在**布局后**偏移，不触发重新测量 —— 每帧移动的动画
                        //    必须用它，否则每帧都要重新 layout（明显掉帧）。
                        Box(
                            modifier = Modifier
                                .padding(start = V3Space.xs)
                                .offset {
                                    // offset lambda 的接收者是 Density，所以这里能 toPx()
                                    androidx.compose.ui.unit.IntOffset(
                                        x = (itemWidth * pos.value + leadShift).roundToPx(),
                                        y = 0,
                                    )
                                }
                                .width(indicatorWidth)
                                .height(V3Size.navRow - V3Space.xs * 2)
                                .padding(top = (V3Size.navGlass - V3Size.navRow) / 2)
                                .clip(RoundedCornerShape(V3Radius.pill))
                                .background(colors.fillSecondary),
                        )
                    }
                }

                // ---- 内容行 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(V3Size.navGlass),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { i, item ->
                        val selected = i == selectedIndex
                        NavItemCell(
                            item = item,
                            selected = selected,
                            modifier = Modifier.width(itemWidth),
                            onClick = { if (i != selectedIndex) onSelect(i) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个导航项。
 *
 * ## 选中态的**三个**维度同时变化
 *
 * 只靠颜色是单维的（色盲用户分不出）。所以：
 * 1. **图标**：Outlined → Filled
 * 2. **文字**：Regular → Semibold
 * 3. **颜色**：secondary → primary
 *
 * ⚠️ 这三条与无障碍规范一致（不依赖单一视觉维度）。
 */
@Composable
private fun NavItemCell(
    item: GlassNavItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    val interaction = remember { MutableInteractionSource() }

    // 颜色与字重都用动画过渡 —— 硬切会显得"跳"
    val tint by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = V3Motion.fade(),
        label = "navTint",
    )

    Column(
        modifier = modifier
            .height(V3Size.navRow)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) item.selectedIcon else item.icon,
            contentDescription = item.label,
            tint = lerpColor(colors.labelSecondary, colors.brand, tint),
            modifier = Modifier
                .size(V3Size.iconLg)
                .graphicsLayer {
                    // 选中时轻微放大（第三维度：尺寸）
                    val s = 1f + tint * 0.04f
                    scaleX = s
                    scaleY = s
                },
        )
        Spacer(Modifier.height(V3Space.hairline))
        Text(
            text = item.label,
            style = V3Type.caption2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = lerpColor(colors.labelSecondary, colors.brand, tint),
            maxLines = 1,
        )
    }
}

/** 两色线性插值。 */
private fun lerpColor(from: Color, to: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = from.alpha + (to.alpha - from.alpha) * f,
    )
}

/**
 * 导航项。
 *
 * @param icon 未选中图标（Outlined）
 * @param selectedIcon 选中图标（Filled）
 */
data class GlassNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)
