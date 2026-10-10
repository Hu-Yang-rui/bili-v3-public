package com.example.biliv3.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.biliv3.data.model.CategoryEntry
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Type

/**
 * 分区 Tab 条 —— 官方 44dp，选中粉色下划线。
 *
 * ## ⚠️ 这是 Tab 条，不是图标面板
 *
 * 早期实现把分区做成了**一张白色大卡片，里面排 12 个 48dp 高饱和圆形图标**
 * （动画粉/番剧橙/国创黄/音乐紫…），占了近 1/4 屏高。
 *
 * 对照 `AGENTS.md` §5.2 的官方首页结构：
 * ```
 * │  推荐   热门   动画   影视   音乐 …  │ 44dp，选中粉下划线
 * ```
 * 官方是**一行文字 Tab**，不是圆形图标墙。两者的差别不只是好看：
 *
 * 1. **纵向空间**：图标面板约 150dp，Tab 条 44dp —— 一屏能多看近一行视频卡
 * 2. **视觉噪音**：12 个彩色圆是 12 个强视觉焦点，把注意力从封面抢走
 *    （违反 §3.1 原则 1「内容优先」）
 * 3. **信息密度**：Tab 条能一行放下 13 个入口，图标墙要两行或横向滚动
 *
 * ## 交互
 *
 * - 选中项：主文字色 + SemiBold + **粉色下划线**（3dp 圆角条）
 * - 未选中：次要文字色，下划线占位但透明 —— 保证切换时高度不跳
 * - 横向可滚动，触达全部 13 个分区
 */
@Composable
fun CategoryTabBar(
    categories: List<CategoryEntry>,
    selectedKey: String,
    onCategoryClick: (CategoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) return

    // 「推荐」固定排第一（官方首页结构：推荐 / 热门 / 动画 / 影视 …）。
    // 它不是真实分区（没有 rid），所以单独构造一个 key，点击时原样回传，
    // 由调用方决定「推荐」是否要拉推荐流。
    val recommend = CategoryEntry(key = RECOMMEND_TAB_KEY, name = "推荐", rid = 0)
    val tabs = listOf(recommend) + categories

    // 外层 Box 用于叠一个右侧渐隐 —— Tab 条横向可滚动，
    // 最后一个可见 Tab 会被硬裁切，渐隐既遮住切口，也暗示"还能往右滑"。
    //
    // ## 🔴 渐隐宽度必须 < 1/3 字宽（v1.2.3 修，v1.2.2 的修法无效）
    //
    // ### v1.2.2 那次修法为什么没用
    //
    // v1.2.2 把渐隐改成"只在 `canScrollForward` 时出现"，以为滑到底撤掉就没事了。
    // **装机取色证明这是错的**（AGENTS.md §7.6-41）：初始态右边确实还有分区，
    // `canScrollForward == true`，渐隐照常绘制 —— 而用户看到的初始态，
    // 恰恰就是「舞蹈」落在右缘。**只修了"滚到底"，没修"刚进来"。**
    //
    // ### 真正的根因（v1.2.3 逐列取色量出来的）
    //
    // 渐隐是 24dp(63px) 线性渐变，**不透明端正好压在 Tab 条的右边缘上**。
    // 而「舞蹈」的布局右边界（x≈846）几乎与右边缘（x≈845）重合 ——
    // 于是「蹈」(x≈818..846) 从**第一笔起**就在渐隐里：
    //
    // | x | 实测亮度 | α |
    // |---|---|---|
    // | 790 | 164 | 0.00（「舞」） |
    // | 818 | 93 | 0.50 ← **「蹈」的起点就已经掉一半** |
    // | 845 | 22 | 1.00（纯底） |
    //
    // 所以问题不是"渐隐该不该画"，而是**它太宽了**：
    // 24dp ≈ 63px ≈ **0.9 个字**，足够把一整个字吃干净。
    //
    // ### 修法：宽度 24dp → 8dp（`V3Space.xs`）
    //
    // **判据**：渐隐宽度 ≤ 1/3 字宽（字宽 ≈ 27dp），
    // 这样即使某个 Tab 正好贴在右缘，也只有它的**右端**被柔化，
    // 主体笔画仍在满亮度（184）—— 读起来是"边缘渐隐"，不是"渲染坏了"。
    //
    // > ⚠️ 右对齐的渐隐**必然**会盖住右缘那个 Tab 的尾巴 —— 这是物理必然，
    // > 不可能靠"画不画"绕开（唯一绕开办法是让右缘永远落在 Tab 之间的空隙里，
    // > 而那取决于屏宽，做不保证）。所以只能**限制它的伤害范围**。
    // >
    // > ⚠️ 想加宽渐隐前先复测：`V3Space.sm`(12dp=31px) 时「蹈」起点亮度只有 163，
    // > 已经不达标。**8dp 是这条曲线上的可用上限，不是随手挑的。**
    val scrollState = rememberScrollState()
    val canScrollForward by remember {
        derivedStateOf { scrollState.canScrollForward }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.categoryTabBar),
    ) {
        Row(
            modifier = Modifier
                .height(Sizes.categoryTabBar)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(V3Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { entry ->
                CategoryTab(
                    entry = entry,
                    selected = entry.key == selectedKey,
                    onClick = { onCategoryClick(entry) },
                )
            }
            // 尾部留白：滚动到底时最后一个 Tab 不至于贴死右边缘
            Spacer(Modifier.width(V3Space.xl))
        }

        // 右侧渐隐（透明 -> 页面底色）。
        // ⚠️ 宽度是**实测量出来的上限**，不是随手挑的 —— 见上方说明：
        // 8dp(21px) 时「蹈」起点亮度 169（合格），12dp(31px) 时只有 163（不合格）。
        // 想加宽必须先复测右缘那个 Tab 的起点亮度。
        if (canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(V3Space.xs)
                    .height(Sizes.categoryTabBar)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                BiliV3.colors.bgPrimary,
                            ),
                        ),
                    ),
            )
        }
    }
}

/** 「推荐」Tab 的 key。与 HomeScreen 的 `RECOMMEND_KEY` 必须一致。 */
internal const val RECOMMEND_TAB_KEY = "__recommend__"

/** 单个 Tab：文字 + 下划线。下划线始终占位，避免选中态切换时高度抖动。 */
@Composable
private fun CategoryTab(
    entry: CategoryEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(V3Radius.xs))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = V3Space.xxs),
    ) {
        Text(
            text = entry.name,
            style = V3Type.callout.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                // 选中态用主文字色 + 下划线表达，不用粉色文字
                // （粉字在浅色下只有 2.6:1，做文字不达标；下划线是图形，可用品牌粉）
                color = when {
                    selected -> colors.labelPrimary
                    hovered -> colors.brandBiliText
                    else -> colors.labelSecondary
                },
            ),
            maxLines = 1,
        )

        Spacer(Modifier.height(V3Space.xxs))

        Box(
            modifier = Modifier
                .width(20.dp)
                .height(V3Space.tabIndicator)
                .clip(RoundedCornerShape(V3Radius.xs))
                .background(if (selected) colors.brand else Color.Transparent),
        )
    }
}
