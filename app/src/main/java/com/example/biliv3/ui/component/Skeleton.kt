package com.example.biliv3.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.Motion
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 骨架屏基元。
 *
 * ## 为什么用骨架屏而不是转圈
 *
 * 骨架屏与真实布局**同构**，内容出现时不会"跳一下"，
 * 感知速度也明显更快。全屏转圈会让用户觉得"卡住了"。
 *
 * ## ⚠️ 微光相位共享（性能关键）
 *
 * 之前**每个 `SkeletonBox` 各自建一个 `rememberInfiniteTransition`**。
 * 首页骨架有 `列数 × 3 行 × 4 个 SkeletonBox` 个盒子 ——
 * 移动端 2×3×4 = 24 个，桌面端 5×3×4 = 60 个。
 *
 * Compose 的 `InfiniteTransition` **每一帧都会写 state 并触发重组**，
 * 60 个独立动画 = 每帧 60 次状态写入 + 60 次重组，
 * 这正是「一进首页就发烫、切换页面迟钝」的主要来源之一。
 *
 * 现在改为从 [LocalShimmerPhase] 读取**同一个**共享相位：
 * 整棵树只有一个 `InfiniteTransition`，所有盒子复用它的值。
 * 相位一致性还顺带让微光扫过时各块**同步闪烁**，观感反而更整齐。
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    height: Dp? = null,
    width: Dp? = null,
    aspectRatio: Float? = null,
    shape: Shape = RectangleShape,
) {
    val colors = BiliTheme.colors
    // 读共享相位（State 而不是裸值）—— 真正的读取发生在绘制阶段，
    // 相位变化只重绘、不重组。见 LocalShimmerPhase 的说明。
    val shimmer = LocalShimmerPhase.current

    var m = modifier
    if (width != null) m = m.then(Modifier.size(width = width, height = height ?: 0.dp))
    else if (height != null) m = m.then(Modifier.height(height))
    if (aspectRatio != null) m = m.then(Modifier.aspectRatio(aspectRatio))

    Box(
        modifier = m
            .clip(shape)
            .drawBehind {
                // ⚠️ 在绘制阶段读 shimmer.value —— 这是"推迟读取"的关键。
                // 若在组合阶段读，每帧都会重组整个骨架树。
                val start = -1f + shimmer.value * 3f
                val brush = Brush.linearGradient(
                    colors = listOf(
                        colors.skeletonBase,
                        colors.skeletonHighlight,
                        colors.skeletonBase,
                    ),
                    start = Offset(start * size.width, 0f),
                    end = Offset((start + 0.6f) * size.width, 0f),
                )
                drawRect(brush)
            },
    )
}

/**
 * 共享微光相位，取值 `0f..1f` 循环。
 *
 * ## ⚠️ 为什么用 `State<Float>` 而不是裸 `Float`（这是闪烁的根因之一）
 *
 * 第一版是 `staticCompositionLocalOf { 0f }` 提供**裸 Float**，
 * 于是 `SkeletonBox` 在**组合阶段**读取该值 → 相位每帧变化
 * → **整棵骨架树每帧重组**（60fps × 24~60 个盒子）。
 *
 * 现在改成提供 `State<Float>`，`SkeletonBox` 只在
 * `Modifier.drawBehind` 的**绘制阶段**读 `.value`：
 * 相位变化只触发**重绘**，不触发重组。
 *
 * 这是 Compose 的「推迟读取」优化 —— 把状态读取点从组合推迟到绘制，
 * 上层就完全不用重组。骨架屏是纯静态布局，重组毫无意义。
 */
val LocalShimmerPhase = staticCompositionLocalOf<State<Float>> {
    mutableStateOf(0f)
}

/**
 * 为子树提供**单个**共享微光相位。
 *
 * 用法：把骨架屏整块包起来。
 *
 * ```
 * ProvideShimmer {
 *     SkeletonGrid(columns = 2)
 * }
 * ```
 *
 * 只建一个 `InfiniteTransition`，N 个 `SkeletonBox` 共用。
 */
@Composable
fun ProvideShimmer(content: @Composable () -> Unit) {
    val tier = com.example.biliv3.design.LocalDeviceTier.current

    // ⚠️ 低端设备：**不做微光动画**，直接渲染静态灰块。
    //
    // 微光即使共享相位，仍是一次持续的每帧重绘。低端机上
    // 骨架屏往往出现在"正在加载"这种本来就吃性能的时刻，
    // 再叠一个全屏动画是雪上加霜。
    //
    // 静态灰块同样传达"内容还没来"，语义不变。
    if (!tier.canShimmer) {
        CompositionLocalProvider(
            LocalShimmerPhase provides remember { mutableStateOf(0f) },
        ) {
            content()
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "skeleton-shared")
    // 注意：这里必须用 animateFloat 的 State 重载并把 State 往下传，
    // **不要**用 `by` 委托展开成 Float —— 那会在组合阶段读值，
    // 导致每帧重组整个子树（见 LocalShimmerPhase 的说明）。
    val shimmer: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.SHIMMER_MS, easing = Motion.linear),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-shared",
    )

    CompositionLocalProvider(LocalShimmerPhase provides shimmer) {
        content()
    }
}

/**
 * 首页骨架卡片。与 [VideoCard] 布局**同构**：
 * 封面 16:10（直角）→ 标题两行 → 元信息行。
 *
 * ## 🔴 这里必须跟着 [VideoCard] 走，否则骨架白做
 *
 * 骨架屏的**唯一意义**是消除"数据到达时跳一下"（CLS）。
 * 只要骨架与真实卡片有一点不同构，数据到达就会跳 —— 那还不如不显示骨架。
 *
 * ### 无卡片重构后同步修正的两处（v1.2.1）
 *
 * 1. **封面圆角 12dp → 0**：`VideoCard` 的封面已改直角（圆角是"卡片"的语言），
 *    骨架若仍留 12dp，加载完成瞬间四个角会"收方"，是可见的跳动。
 * 2. **间距与行数对齐**：改为 `封面 → 8dp → 标题两行(40dp) → 4dp → 元信息(16dp)`，
 *    与 `VideoCard` 的 `Space.x2 / heightIn(min=40.dp) / Space.x1 / 头像 16dp` 逐项对应。
 */
@Composable
fun SkeletonVideoCard(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SkeletonBox(
            modifier = Modifier.fillMaxWidth(),
            aspectRatio = Sizes.coverAspectRatio,
        )
        // 与 VideoCard 的 `Spacer(Space.x2)` 一致
        Spacer(Modifier.height(Space.x2))
        // 标题两行：VideoCard 用 `heightIn(min = 40.dp)` 预留高度，骨架同样占 40dp
        SkeletonBox(Modifier.fillMaxWidth(), height = TITLE_BLOCK_HEIGHT)
        // 与 VideoCard 的 `Spacer(Space.x1)` 一致
        Spacer(Modifier.height(Space.x1))
        // 元信息行：VideoCard 是 16dp 头像 + 昵称
        SkeletonBox(Modifier.fillMaxWidth(0.45f), height = META_ROW_HEIGHT)
    }
}

/** 标题两行的预留高度，与 `VideoCard` 的 `heightIn(min = 40.dp)` 对齐。 */
private val TITLE_BLOCK_HEIGHT = 40.dp

/** 元信息行高度，与 `VideoCard` 的 UP 头像（16dp）对齐。 */
private val META_ROW_HEIGHT = 16.dp

/**
 * 首页首屏骨架。
 *
 * 卡片数 = 首屏列数 × 3 行，与真实网格对齐，避免内容到达时整页跳动。
 */
@Composable
fun SkeletonGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    rows: Int = 3,
    gutter: Dp = Space.gridGutterDesktop,
    rowSpacing: Dp = Space.gridRowDesktop,
    pagePadding: Dp = Space.pageDesktop,
) {
    val total = columns * rows
    // 用 Column + Row 手工分行，避免引入 LazyVerticalGrid 的滚动语义
    // （骨架屏不该可滚动）。total 仅用于日志/调试可读性。
    check(total >= 0)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = pagePadding),
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        repeat(rows) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(gutter),
                modifier = Modifier.fillMaxWidth(),
            ) {
                repeat(columns) {
                    SkeletonVideoCard(
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
