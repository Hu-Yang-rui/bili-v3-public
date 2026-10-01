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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

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
    // 最后一个可见 Tab 会被硬裁切（实测「舞蹈」只剩半个字），
    // 看起来像渲染坏了。渐隐既遮住切口，也暗示"还能往右滑"。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.categoryTabBar),
    ) {
        Row(
            modifier = Modifier
                .height(Sizes.categoryTabBar)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.x5),
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
            Spacer(Modifier.width(Space.x6))
        }

        // 右侧渐隐（透明 -> 页面底色）
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(Space.x6)
                .height(Sizes.categoryTabBar)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            BiliTheme.colors.bgBase,
                        ),
                    ),
                ),
        )
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
    val colors = BiliTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.button))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = Space.x1),
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                // 选中态用主文字色 + 下划线表达，不用粉色文字
                // （粉字在浅色下只有 2.6:1，做文字不达标；下划线是图形，可用品牌粉）
                color = when {
                    selected -> colors.textPrimary
                    hovered -> colors.textBrandSafe
                    else -> colors.textSecondarySafe
                },
            ),
            maxLines = 1,
        )

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .width(20.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(Radius.badge))
                .background(if (selected) colors.brandPrimary else Color.Transparent),
        )
    }
}
