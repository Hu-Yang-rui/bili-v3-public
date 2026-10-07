package com.example.biliv3.design.v3

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **BiliV3 组件库 v3 —— 非玻璃内容原语**。
 *
 * ---
 *
 * # 🔴 设计立场：内容不用容器
 *
 * 全量重构的核心约束是「**不要卡片海洋**」。实现方式不是"把卡片改成无边框"，
 * 而是**改变内容的组织手段**：
 *
 * | 旧做法 | 新做法 |
 * |---|---|
 * | 每条内容套一个圆角矩形 | 内容直接排，靠 [V3Spacing] 分组 |
 * | 分组靠"容器边界" | 分组靠**间距 + 分隔线 + 明度带** |
 * | 层级靠"阴影/描边" | 层级靠**字号 + 字重 + 明度** |
 *
 * ## 唯一的例外：设置类页面用「分组块」
 *
 * iOS 的 inset-grouped 列表（设置页那种）**确实**是一个圆角容器装多行。
 * 这不是"卡片海洋"，因为：
 * - **一个分组只有一个容器**，不是"每行一个容器"
 * - 容器的目的是表达"这几行属于同一组"，而不是"给每行加个框"
 *
 * 所以本文件提供两套：
 *
 * | 组件 | 用途 | 有容器？ |
 * |---|---|---|
 * | [V3Section] | 设置类页面的**分组块** | ✅ 一个分组一个 |
 * | [V3Block] | 内容页面的**无容器区块** | ❌ |
 *
 * ⚠️ **内容页（首页/评论/动态/收藏）一律用 [V3Block]** ——
 * 那些页面用 [V3Section] 就会立刻变成卡片海洋。
 */

// ---------------------------------------------------------------------------
// 一、无容器区块（内容页用）
// ---------------------------------------------------------------------------

/**
 * **内容区块**（无容器）。
 *
 * 只负责两件事：给内容一个**章节边界**（靠间距），以及可选的**明度带**。
 *
 * ## 什么时候用明度带
 *
 * | 场景 | 用带？ |
 * |---|---|
 * | 同一页面里两块**性质不同**的内容（如"视频信息"与"推荐列表"） | ✅ |
 * | 连续同类内容（评论列表、视频列表） | ❌ 靠间距就够 |
 *
 * ⚠️ **不要给每个区块都加带** —— 那等于把卡片换成了通栏色块，
 * 视觉噪音一样大。带的价值在于"稀少"。
 */
@Composable
fun V3Block(
    modifier: Modifier = Modifier,
    /** 是否铺明度带（默认不铺 —— 见上面的判断表）。 */
    band: Boolean = false,
    /** 区块上方间距。默认 [V3Space.xxl]（32dp）。 */
    topSpace: Dp = V3Space.xxl,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = V3Space.contentMargin),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BiliV3.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topSpace)
            .then(if (band) Modifier.background(colors.bgSecondary) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * **章节标题**。
 *
 * ## 为什么不用旧系统的 `SectionMark`（`01 ── 推荐`）
 *
 * 旧系统的章节标记带一个等宽序号 `01`，那是"极客点缀"。
 * 新设计语言是 iOS 风格 —— iOS 的章节标题就是**一行小号大写标签**，
 * 没有序号、没有装饰线。
 *
 * ⚠️ 序号还有个实际问题：**页面里加删章节就要重排序号**，
 * 而旧项目已经因此出过两次 bug（`…9, 10, 10, 11` 两个 10）。
 * 去掉序号顺带消掉了这个 bug 类别。
 *
 * @param prominent `true` = 大标题（22dp Bold，用于页面主章节）；
 *   `false` = 小标签（13dp，用于列表分组）
 */
@Composable
fun V3SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    /** 右侧的次要动作（如"查看全部"）。 */
    trailing: (@Composable () -> Unit)? = null,
    prominent: Boolean = false,
    /**
     * 章节**上方**间距。
     *
     * ⚠️ 只给上边，不给下边 —— 这是全站的系统性规则：
     * 间距只由"下方区块的上边"提供，否则两块之间会翻倍
     * （旧项目实测过 28+28=56dp 的突兀空白带）。
     */
    topSpace: Dp = V3Space.xxl,
) {
    val colors = BiliV3.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topSpace)
            .padding(
                horizontal = V3Space.contentMargin,
                vertical = if (prominent) V3Space.xs else V3Space.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = if (prominent) V3Type.title2 else V3Type.footnote,
            color = if (prominent) colors.labelPrimary else colors.labelSecondary,
            fontWeight = if (prominent) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailing != null) {
            Spacer(Modifier.width(V3Space.xs))
            trailing()
        }
    }
}

// ---------------------------------------------------------------------------
// 二、分组块（设置类页面用）
// ---------------------------------------------------------------------------

/**
 * **分组块** —— 一个圆角容器装若干行（iOS inset-grouped）。
 *
 * ## ⚠️ 使用边界（很重要）
 *
 * ✅ **该用**：设置页、账号页、关于页 —— 这些页面**本来就是**"分组配置项"，
 * 用分组块表达"这几项属于同一组"是最清晰的。
 *
 * ❌ **不该用**：首页、评论、动态、收藏、搜索结果的**内容列表** ——
 * 那些用 [V3Block] + 间距。给每条内容套容器就是卡片海洋。
 *
 * @param header 分组标题（可选）
 * @param footer 分组脚注（可选，用于说明）
 */
@Composable
fun V3Section(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    /** 分组之间的上方间距。 */
    topSpace: Dp = V3Space.xl,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BiliV3.colors
    Column(modifier = modifier.fillMaxWidth().padding(top = topSpace)) {
        if (header != null) {
            Text(
                text = header,
                style = V3Type.footnote,
                color = colors.labelSecondary,
                modifier = Modifier.padding(
                    start = V3Space.contentMargin + V3Space.xs,
                    end = V3Space.contentMargin,
                    bottom = V3Space.xs,
                ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V3Space.contentMargin)
                // ⚠️ 用 bgSecondary 而不是 fillSecondary ——
                //    这是"区域"不是"控件"（见 V3Colors 的三层说明）
                .clip(RoundedCornerShape(V3Radius.md))
                .background(colors.bgSecondary),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = V3Type.footnote,
                color = colors.labelTertiary,
                modifier = Modifier.padding(
                    start = V3Space.contentMargin + V3Space.xs,
                    end = V3Space.contentMargin,
                    top = V3Space.xs,
                ),
            )
        }
    }
}

/**
 * **分组内的一行**。
 *
 * ## 交互反馈
 *
 * 整行可点（不是只点图标/开关）—— 触摸目标大得多，与 Material 列表规范一致。
 *
 * ## 按下反馈
 *
 * ⚠️ **不能用 `Modifier.clickable` 的默认水波纹** ——
 * 在深色 + 圆角分组里，Material 的水波纹会溢出分组边界（视觉上很脏）。
 * 这里改为**整行明度变化**（iOS 的做法）。
 *
 * @param onClick 传 null = 不可点（只读信息行）
 * @param leading 左侧图标/头像
 * @param trailing 右侧内容（箭头、开关、值）
 * @param subtitle 副标题（第二行小字）
 * @param showSeparator 是否画底部分隔线（最后一行传 false）
 */
@Composable
fun V3Row(
    modifier: Modifier = Modifier,
    title: String,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    titleStyle: TextStyle = V3Type.body,
    titleColor: Color = BiliV3.colors.labelPrimary,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** 分隔线是否从文字开头起（有 leading 时通常 true）。 */
    separatorInsetStart: Boolean = true,
    showSeparator: Boolean = true,
    minHeight: Dp = V3Size.rowRegular,
) {
    val colors = BiliV3.colors
    val interaction = remember { MutableInteractionSource() }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            // ⚠️ indication = null：关掉水波纹（见上面的说明）
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(
                    start = V3Space.rowPadding,
                    end = V3Space.rowPadding,
                    top = V3Space.rowPaddingV,
                    bottom = V3Space.rowPaddingV,
                )
                // 最小高度：用 defaultMinSize 而不是 height ——
                // height 会**强制**固定高度，用户放大字号时文字会被截断
                .defaultMinSize(minHeight = minHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(V3Space.avatarGap))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = titleColor,
                    maxLines = if (subtitle == null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(V3Space.hairline))
                    Text(
                        text = subtitle,
                        style = V3Type.footnote,
                        color = colors.labelSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(V3Space.sm))
                trailing()
            }
        }
        if (showSeparator) {
            V3Divider(insetStart = if (separatorInsetStart) V3Space.rowPadding else 0.dp)
        }
    }
}

/**
 * **分隔线**。
 *
 * ⚠️ 无卡片架构下，**优先用间距分组**，分隔线是兜底手段
 * （只在必须硬边界处用 —— 如设置分组内的行之间）。
 *
 * @param insetStart 左侧缩进（列表里通常与行内边距对齐）
 */
@Composable
fun V3Divider(
    modifier: Modifier = Modifier,
    insetStart: Dp = V3Space.contentMargin,
    insetEnd: Dp = 0.dp,
) {
    val colors = BiliV3.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = insetStart, end = insetEnd)
            .height(0.5.dp)
            .background(colors.separator),
    )
}

// ---------------------------------------------------------------------------
// 三、开关 / 选择器（分组内的控件）
// ---------------------------------------------------------------------------

/**
 * 开关行。
 *
 * ## 为什么不用 Material3 的 `Switch`
 *
 * M3 的 Switch 尺寸与配色都是 Material 语言（胶囊轨道 + 圆形滑块 + 大尺寸），
 * 与 iOS 风格的细轨道开关观感冲突。
 *
 * 但**不引入自定义开关的实现风险**（手势、无障碍、动画都要自己写）。
 * 折中：用 M3 `Switch` 但**换配色**（品牌蓝轨道 + 白色滑块），
 * 尺寸靠 `Modifier.scale` 收到 iOS 比例。
 *
 * ⚠️ 保留 `Switch` 的语义与无障碍（`contentDescription` 走 title）。
 */
@Composable
fun V3SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    showSeparator: Boolean = true,
) {
    val colors = BiliV3.colors
    V3Row(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        titleColor = if (enabled) colors.labelPrimary else colors.labelTertiary,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        trailing = {
            androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.brand,
                    uncheckedThumbColor = colors.labelSecondary,
                    uncheckedTrackColor = colors.fillSecondary,
                    uncheckedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.scale(0.86f),
            )
        },
        showSeparator = showSeparator,
    )
}

/** 让 `Modifier.scale` 可用（`graphicsLayer` 的便捷别名）。 */
private fun Modifier.scale(value: Float): Modifier =
    this.graphicsLayer(scaleX = value, scaleY = value)

// ---------------------------------------------------------------------------
// 四、内容行（无容器的列表项，内容页用）
// ---------------------------------------------------------------------------

/**
 * **无容器内容行** —— 内容页的列表项基座。
 *
 * 与 [V3Row] 的区别：**没有分组容器**，行与行之间靠间距与可选分隔线。
 * 用于评论、动态、收藏列表、搜索结果等**内容**列表。
 *
 * ⚠️ 内容页**不要**用 [V3Row]（那个是给设置类分组的），
 * 否则会被误用成"每行一个容器"。
 */
@Composable
fun V3ContentRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** 行首（头像、序号等）。 */
    leading: (@Composable () -> Unit)? = null,
    /** 行主体。 */
    content: @Composable ColumnScope.() -> Unit,
    /** 行尾操作区（点赞、更多）。 */
    actions: (@Composable RowScope.() -> Unit)? = null,
    horizontalPadding: Dp = V3Space.contentMargin,
    verticalPadding: Dp = V3Space.sm,
    showSeparator: Boolean = false,
    separatorInsetStart: Dp = V3Space.contentMargin,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(V3Space.avatarGap))
            }
            Column(modifier = Modifier.weight(1f)) { content() }
            if (actions != null) {
                Spacer(Modifier.width(V3Space.sm))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Top,
                ) {
                    // actions 是 RowScope 的扩展 —— 必须显式给一个 Row 作为接收者
                    Row(verticalAlignment = Alignment.CenterVertically) { actions() }
                }
            }
        }
        if (showSeparator) V3Divider(insetStart = separatorInsetStart)
    }
}

// ---------------------------------------------------------------------------
// 五、页面骨架
// ---------------------------------------------------------------------------

/**
 * **页面容器** —— 统一背景与内容边距。
 *
 * ⚠️ 不画顶栏（顶栏由各页自己决定是否浮动/玻璃）。
 * 新设计语言里**大多数页面不需要独立顶栏** —— 标题直接作为内容的一部分
 * （iOS 的 large title 就是这个思路），滚动时它自然离开。
 */
@Composable
fun V3Page(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BiliV3.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgPrimary),
        content = content,
    )
}

/**
 * 图标尺寸的语义别名（避免页面里写 `Modifier.size(20.dp)`）。
 */
object V3IconSize {
    val xs = V3Size.iconXs
    val sm = V3Size.iconSm
    val md = V3Size.iconMd
    val lg = V3Size.iconLg
    val xl = V3Size.iconXl
}

/** 给 `Icon` 用的标准尺寸修饰符。 */
fun Modifier.v3Icon(size: Dp = V3Size.iconMd): Modifier = this.size(size)

/** 头像圆形修饰符（统一形状）。 */
fun Modifier.v3Avatar(size: Dp): Modifier =
    this.size(size).clip(androidx.compose.foundation.shape.CircleShape)
