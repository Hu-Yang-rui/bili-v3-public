package com.example.biliv3.ui.video

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.DeviceTier
import com.example.biliv3.design.LocalDeviceTier
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Motion
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import kotlinx.coroutines.launch

/**
 * 投币确认弹窗。
 *
 * ## 为什么必须确认
 *
 * 硬币是**不可撤销的消耗品**（投出去不能退回）。
 * 直接点一下「投币」就扣掉，误触代价很高 ——
 * 而互动栏里投币与点赞/收藏相邻，误触概率不低。
 *
 * ## 交互：选择 → 确认（两步，不可合并）
 *
 * ```
 * [1 硬币]  [2 硬币]     ← 点/滑是"选中"，不提交
 * ☑ 同时点赞内容          ← 可反复切换
 *      ( 确认投币 )       ← 只有这里才真正提交
 * ```
 *
 * 第一版是"点卡片直接投"，导致两个真实缺陷：用户无法切换数量、
 * 「同时点赞」永远赶不上提交。`CoinSelectionTest` 把"选择必须独立于
 * 提交"钉死了。
 *
 * ## v1.6.3 新增：女仆装小人 + 浮空硬币 + 取币投掷动画
 *
 * ### 硬币**可点也可滑**
 *
 * 两个 `CoinOptionCard` 换成了 [HorizontalPager]。页码是**唯一真相**，
 * 投币数量由 `CoinChoice.countAt(page)` 派生 —— 于是"滑到 2 再点 1"
 * 不会出现高亮与实投不一致（投币不可撤销，这种不一致代价是真实硬币）。
 * 点击通过 `animateScrollToPage` 驱动**同一个** pager，两条输入不分叉。
 *
 * ### 动画不引入第二套动效系统
 *
 * 时长全部落在 [Motion] 的既有阶梯（PRESS/FADE/PAGE/LONG）附近，
 * 相位机是纯函数 [CoinThrow]（有 `CoinThrowTest` 钉死"不允许瞬移"）。
 *
 * ### 视觉全部取自现有令牌
 *
 * 女仆装用 `bgHover`/`borderStrong`/`textSecondarySafe` 这些既有色，
 * 浮空特效用 `accentCoinBright` 一族。**没有新增任何色值** ——
 * §5.2「页面内零硬编码」与"禁止第二套配色"。
 *
 * @param coinBalance 硬币余额；null 表示未知（未登录或接口失败），
 *                    此时不显示余额行而不是显示 `0`
 * @param onConfirm 确认投币。(数量, 是否同时点赞)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoinDialog(
    coinBalance: Double?,
    onDismiss: () -> Unit,
    onConfirm: (count: Int, alsoLike: Boolean) -> Unit,
) {
    val colors = BiliTheme.colors
    val tier = LocalDeviceTier.current
    // 低端机不做悬浮特效（每帧重绘的粒子/波纹），语义不变
    val canAnimate = tier != DeviceTier.Low

    /**
     * 页码是**唯一真相**，投币数量由它派生。
     *
     * 这样点击与滑动天然一致，不存在"两份状态打架"。
     */
    val pagerState = rememberPagerState(
        initialPage = CoinChoice.defaultPage,
        pageCount = { CoinChoice.size },
    )
    val selected = CoinChoice.countAt(pagerState.currentPage)
    val scope = rememberCoroutineScope()

    var alsoLike by remember { mutableStateOf(false) }

    /**
     * 投币动画进度。
     *
     * `null` = 未开始（静止态：小人抬手、硬币悬浮）。
     * 非 null = 正在播放，值为已流逝毫秒。
     *
     * ⚠️ 用 `Animatable` 而不是 `animateFloatAsState`：这里需要的是
     * **一次性的时间轴**（0 → TOTAL），且完成时要回调 `onConfirm`。
     * `animateFloatAsState` 是"状态 → 状态"的收敛动画，没有完成回调，
     * 用它就得再挂一个 `LaunchedEffect` 去判"是否到 1"，更绕也更易错。
     */
    val throwAnim = remember { Animatable(0f) }
    var throwing by remember { mutableStateOf(false) }

    // 动画播完 → 真正提交
    LaunchedEffect(throwing) {
        if (!throwing) return@LaunchedEffect
        throwAnim.snapTo(0f)
        throwAnim.animateTo(
            targetValue = CoinThrow.TOTAL_MS.toFloat(),
            animationSpec = tween(
                durationMillis = CoinThrow.TOTAL_MS.toInt(),
                easing = LinearEasing,
            ),
        )
        onConfirm(selected, alsoLike)
    }

    val elapsedMs = if (throwing) throwAnim.value.toLong() else -1L

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
                // 点遮罩 = 取消（投币动画播放中不响应，避免"投到一半被取消"）
                .clickable(enabled = !throwing, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Space.x6)
                    .fillMaxWidth()
                    // 🔴 v1.5.3：**透明轻量浮层**（用户要求"保留视频背景"）
                    //
                    // 用不透明面板会把正在投币的那个视频糊掉。
                    // 与 §5.1「无卡片」一致：投币面板是**操作**不是内容容器。
                    .clickable(enabled = false) {}
                    .padding(vertical = Space.x5),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ---- 硬币选择：可点 + 可左右滑 ----
                CoinPager(
                    pagerState = pagerState,
                    enabled = !throwing,
                    onClickPage = { page ->
                        // 点击 = 驱动**同一个** pager，不另存一份状态
                        scope.launch { pagerState.animateScrollToPage(page) }
                    },
                )

                Spacer(Modifier.height(Space.x2))

                // 页码指示点（滑动的可发现性：没有它用户不知道还能滑）
                PageDots(
                    count = CoinChoice.size,
                    current = pagerState.currentPage,
                )

                Spacer(Modifier.height(Space.x4))

                // ---- 女仆装小人 + 浮空硬币 + 取币投掷动画 ----
                MaidCoinScene(
                    count = selected,
                    elapsedMs = elapsedMs,
                    canAnimate = canAnimate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SCENE_H),
                )

                Spacer(Modifier.height(Space.x2))

                Text(
                    text = if (throwing) "投币中…" else "将投出 $selected 枚硬币",
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
                        .clickable(enabled = !throwing) { alsoLike = !alsoLike }
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
                // 只有这里才真正提交（提交发生在动画播完之后）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4)
                        .height(CONFIRM_BUTTON_H)
                        .clip(RoundedCornerShape(Radius.interactive))
                        .background(
                            if (throwing) colors.accentCoin else colors.accentCoinBright,
                        )
                        .clickable(enabled = !throwing) { throwing = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (throwing) "投出中…" else "确认投币",
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
                        .clickable(enabled = !throwing, onClick = onDismiss),
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
 * 硬币选择 pager：**点击与左右滑动共用一份状态**。
 *
 * ## 为什么用 `HorizontalPager` 而不是自己写手势
 *
 * 项目已经在 `BannerCarousel` 里用了 `HorizontalPager`（首页 Banner）。
 * 复用它意味着：手势竞争、fling 惯性、边界回弹、PC 鼠标拖拽
 * （Compose 的 pager 对 pointer 输入统一处理）全都由框架保证，
 * 不需要我们重新实现一遍 —— 而自己写 `detectHorizontalDragGestures`
 * 一定会漏掉惯性、边界与鼠标这几种情况。
 *
 * ## 点击如何与滑动统一
 *
 * 点击不直接改任何"选中值"，而是 `animateScrollToPage(page)` ——
 * 让 pager 自己滚过去。这样无论用户是点还是滑，最终状态都只有
 * 一个来源（`pagerState.currentPage`）。
 *
 * @param onClickPage 点某一页时请求滚到该页
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoinPager(
    pagerState: androidx.compose.foundation.pager.PagerState,
    enabled: Boolean,
    onClickPage: (Int) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        // 一屏只显示一页，两侧留出相邻页的"边缘"，让用户看得出还能滑
        pageSpacing = Space.x4,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Space.x10,
        ),
        userScrollEnabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_H),
    ) { page ->
        val count = CoinChoice.countAt(page)
        CoinOptionCard(
            count = count,
            // 选中态由**是否停在当前页**决定，不另存状态
            selected = page == pagerState.currentPage,
            onClick = { onClickPage(page) },
        )
    }
}

/** 页码指示点：让"可以左右滑"这件事可被发现。 */
@Composable
private fun PageDots(count: Int, current: Int) {
    val colors = BiliTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Space.x1)) {
        repeat(count) { i ->
            val on = i == current
            Box(
                modifier = Modifier
                    .size(if (on) Sizes.dotLg else Sizes.dotSm)
                    .clip(CircleShape)
                    .background(if (on) colors.accentCoinBright else colors.borderStrong),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 女仆装小人 + 浮空硬币场景
// ---------------------------------------------------------------------------

/**
 * 女仆装小人 + 浮空硬币 + 取币 / 投掷动画。
 *
 * ## 为什么全部自绘（Canvas），不用图片 / emoji
 *
 * 1. §5.1 明确**禁止 emoji 当结构图标**
 * 2. 引一张 PNG 会：① 增体积 ② 无法随深色主题取色 ③ 无法让"硬币数量"
 *    跟着选择实时变（需求要求小人拿住的是"用户所选"的硬币）
 * 3. 自绘只有几十行 Canvas，天然适配主题、零体积
 *
 * ## 女仆装怎么表达（不引入第二套视觉）
 *
 * 用**形状**而不是颜色区分服装：围裙用一条梯形浅色块 + 头饰用两个
 * 小三角，颜色全部取自现有令牌（`bgHover` / `borderStrong` /
 * `textSecondarySafe`）。这样它在深色主题下与其它自绘元素同族，
 * 不会像"贴了一张卡通图"。
 *
 * ## 浮空特效（需求第 5 条）
 *
 * 三层，全部小面积、低饱和：
 * - **光晕**：硬币下方一团径向渐隐的青金色（`accentCoinBright`）
 * - **波纹**：两圈向外扩散并淡出的圆环（`infiniteRepeatable`）
 * - **粒子**：三颗沿弧线上浮的小点
 *
 * 强度由 [CoinThrow.hoverFxIntensity] 驱动 —— 取币开始后**归零**。
 * 特效必须跟着状态走，不能是纯装饰（§5.1 原则 4「动效只服务反馈」）。
 *
 * ## 性能
 *
 * - 静止态的呼吸/波纹用 `rememberInfiniteTransition`，**只在 Hovering
 *   期间存在**（`canAnimate` 为 false 时不创建）
 * - 投币期间读的是 `Animatable.value`，逐帧重组不可避免（本来就是动画）
 * - 低端机（`DeviceTier.Low`）关掉波纹与粒子，只留静态光晕
 */
@Composable
private fun MaidCoinScene(
    count: Int,
    elapsedMs: Long,
    canAnimate: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors

    // 静止态的持续脉动（只在没有投币动画时跑，省一次每帧重组）
    val idlePulse = if (canAnimate && elapsedMs < 0L) {
        val transition = rememberInfiniteTransition(label = "coinHover")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.SHIMMER_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "coinHoverPhase",
        )
        p
    } else {
        0f
    }

    val phase = CoinThrow.phaseAt(elapsedMs)
    val lift = CoinThrow.coinLift(elapsedMs)
    val drift = CoinThrow.coinDrift(elapsedMs)
    val coinAlpha = CoinThrow.coinAlpha(elapsedMs)
    val handLift = CoinThrow.handLift(elapsedMs)
    val fx = CoinThrow.hoverFxIntensity(elapsedMs)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // ---- 布局基准（相对画布高度的比例，天然适配不同屏宽）----
        val groundY = h * 0.96f          // 脚底
        val headR = h * 0.11f            // 头半径
        val hipY = groundY - h * 0.34f   // 髋
        val shoulderY = groundY - h * 0.66f

        // 手的高度随动画变化：handLift = 1 时手抬到肩上方
        val handY = shoulderY - h * 0.20f * handLift

        // 硬币的"悬浮位" = 手上方
        val hoverY = handY - h * 0.20f
        // 硬币实际位置 = 悬浮位 + 纵向偏移（取币向下、投出向上）
        val coinY = hoverY - lift * h * 0.16f
        val coinX = cx + drift * w * 0.5f
        val coinR = h * 0.075f

        // ---- ① 悬浮特效（在硬币下方，先画 = 在底层）----
        if (fx > 0f) {
            drawHoverFx(
                cx = coinX,
                cy = coinY + coinR,
                radius = coinR,
                intensity = fx,
                phase = idlePulse,
                color = colors.accentCoinBright,
                withRings = canAnimate,
            )
        }

        // ---- ② 小人 ----
        drawMaid(
            cx = cx,
            groundY = groundY,
            hipY = hipY,
            shoulderY = shoulderY,
            handY = handY,
            headR = headR,
            bodyColor = colors.textSecondarySafe,
            clothColor = colors.bgHover,
            lineColor = colors.borderStrong,
            strokePx = STROKE.toPx(),
        )

        // ---- ③ 硬币（画在小人之上 = 手里/空中的硬币）----
        if (coinAlpha > 0.01f) {
            val shown = count
            repeat(shown.coerceIn(1, CoinChoice.size)) { i ->
                // 两枚时左右分开一点，避免完全重叠看不出来
                val offX = if (shown > 1) (i - (shown - 1) / 2f) * coinR * 1.5f else 0f
                drawCoin(
                    cx = coinX + offX,
                    cy = coinY,
                    radius = coinR * 0.62f,
                    alpha = coinAlpha,
                    face = colors.accentCoinBright,
                    mark = colors.onAccentCoin,
                )
            }
        }
    }
}

/**
 * 悬浮特效：光晕 + 波纹 + 粒子。
 *
 * 全部是**低饱和、小面积**的，符合 §5.1「极客元素是标点，不是正文」。
 * 不用大面积 blur、不用霓虹堆砌。
 */
private fun DrawScope.drawHoverFx(
    cx: Float,
    cy: Float,
    radius: Float,
    intensity: Float,
    phase: Float,
    color: Color,
    withRings: Boolean,
) {
    // 光晕：一圈柔和的外扩（用多层低透明度圆模拟，避免 blur 的离屏开销）
    drawCircle(
        color = color.copy(alpha = 0.10f * intensity),
        radius = radius * 2.1f,
        center = Offset(cx, cy),
    )
    drawCircle(
        color = color.copy(alpha = 0.16f * intensity),
        radius = radius * 1.4f,
        center = Offset(cx, cy),
    )

    if (!withRings) return

    // 波纹：两圈向外扩散并淡出（相位错开半周期，形成连续扩散感）
    repeat(2) { i ->
        val p = ((phase + i * 0.5f) % 1f)
        val r = radius * (1.1f + 1.9f * p)
        drawCircle(
            color = color.copy(alpha = 0.28f * (1f - p) * intensity),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 1.2.dp.toPx()),
        )
    }

    // 粒子：三颗沿弧线上浮的小点（"能量"的方向感来自它们向上）
    repeat(3) { i ->
        val p = ((phase + i * 0.33f) % 1f)
        val ang = (-Math.PI / 2.0).toFloat() + (i - 1) * 0.5f
        val rr = radius * (1.2f + 0.9f * p)
        drawCircle(
            color = color.copy(alpha = 0.5f * (1f - p) * intensity),
            radius = 1.4.dp.toPx(),
            center = Offset(
                cx + rr * kotlin.math.cos(ang),
                cy + rr * kotlin.math.sin(ang),
            ),
        )
    }
}

/**
 * 女仆装小人（自绘）。
 *
 * ## 形状
 *
 * ```
 *      ▲▲        ← 头饰（两个小三角 = 女仆头带）
 *     ( ● )      ← 头
 *    ╱  │  ╲     ← 抬起的手臂（随动画变高）
 *   ┌───┴───┐
 *   │  ▭▭▭  │    ← 围裙（浅色梯形，女仆装的识别位）
 *   └───┬───┘
 *      ╱ ╲       ← 裙摆
 *     ╱   ╲
 * ```
 *
 * ## 为什么用"围裙 + 头饰"表达女仆装
 *
 * 这两件是女仆装的**形状识别位**，与颜色无关 —— 于是不需要
 * 引入任何新色值（§5.2），在深色主题下也不会显得突兀。
 * 不用蕾丝/花边等细节：那是"装饰"，而这里只需要"可辨认"。
 *
 * ⚠️ 手的高度由 `handY` 传入（调用方已按 `CoinThrow.handLift` 算好），
 * 本函数不再接收一个独立的"抬手程度"参数 —— 一个量只有一个来源，
 * 两个来源必然漂移（手臂位置与硬币悬浮位会错开）。
 */
private fun DrawScope.drawMaid(
    cx: Float,
    groundY: Float,
    hipY: Float,
    shoulderY: Float,
    handY: Float,
    headR: Float,
    bodyColor: Color,
    clothColor: Color,
    lineColor: Color,
    strokePx: Float,
) {
    val headCy = shoulderY - headR * 1.6f
    val armLen = (handY - shoulderY)

    // ---- 手臂（先画，让躯干盖住根部）----
    // 手的高度由 handY 表达（调用方已按 CoinThrow.handLift 算好），
    // 所以这里不再单独接收 handReach —— 一个量只有一个来源。
    val armSpread = headR * 0.9f
    drawLine(
        color = bodyColor,
        start = Offset(cx - armSpread, shoulderY),
        end = Offset(cx - armSpread * 0.75f, shoulderY + armLen),
        strokeWidth = strokePx,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = bodyColor,
        start = Offset(cx + armSpread, shoulderY),
        end = Offset(cx + armSpread * 0.75f, shoulderY + armLen),
        strokeWidth = strokePx,
        cap = StrokeCap.Round,
    )

    // ---- 头 ----
    drawCircle(
        color = bodyColor,
        radius = headR,
        center = Offset(cx, headCy),
        style = Stroke(width = strokePx),
    )

    // ---- 头饰（两个小三角 = 女仆头带，形状识别位）----
    val bandY = headCy - headR * 0.72f
    repeat(2) { i ->
        val dx = (i * 2 - 1) * headR * 0.55f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx + dx, bandY - headR * 0.5f)
            lineTo(cx + dx - headR * 0.28f, bandY)
            lineTo(cx + dx + headR * 0.28f, bandY)
            close()
        }
        drawPath(path, color = clothColor)
        drawPath(path, color = lineColor, style = Stroke(width = 1f))
    }

    // ---- 躯干 ----
    drawLine(
        color = bodyColor,
        start = Offset(cx, headCy + headR),
        end = Offset(cx, hipY),
        strokeWidth = strokePx,
        cap = StrokeCap.Round,
    )

    // ---- 裙摆（梯形，女仆装的轮廓识别位）----
    val skirtTop = hipY - headR * 0.25f
    val skirtBottom = groundY - headR * 0.2f
    val topHalf = headR * 0.5f
    val bottomHalf = headR * 1.5f
    val skirt = androidx.compose.ui.graphics.Path().apply {
        moveTo(cx - topHalf, skirtTop)
        lineTo(cx + topHalf, skirtTop)
        lineTo(cx + bottomHalf, skirtBottom)
        lineTo(cx - bottomHalf, skirtBottom)
        close()
    }
    drawPath(skirt, color = clothColor)
    drawPath(skirt, color = lineColor, style = Stroke(width = 1f))

    // ---- 围裙（浅色小梯形，压在裙摆上 = 女仆装的识别位）----
    val apronTop = skirtTop + headR * 0.2f
    val apronBottom = skirtBottom - headR * 0.15f
    val apron = androidx.compose.ui.graphics.Path().apply {
        moveTo(cx - headR * 0.38f, apronTop)
        lineTo(cx + headR * 0.38f, apronTop)
        lineTo(cx + headR * 0.62f, apronBottom)
        lineTo(cx - headR * 0.62f, apronBottom)
        close()
    }
    drawPath(apron, color = lineColor.copy(alpha = 0.55f))

    // ---- 腿（裙摆下露出一点）----
    val legY = skirtBottom
    drawLine(
        color = bodyColor,
        start = Offset(cx - headR * 0.3f, legY),
        end = Offset(cx - headR * 0.35f, groundY),
        strokeWidth = strokePx,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = bodyColor,
        start = Offset(cx + headR * 0.3f, legY),
        end = Offset(cx + headR * 0.35f, groundY),
        strokeWidth = strokePx,
        cap = StrokeCap.Round,
    )
}

/** 一枚硬币：外圈 + 内圈 + 中心符号。 */
private fun DrawScope.drawCoin(
    cx: Float,
    cy: Float,
    radius: Float,
    alpha: Float,
    face: Color,
    mark: Color,
) {
    drawCircle(color = face.copy(alpha = alpha), radius = radius, center = Offset(cx, cy))
    // 内圈让硬币在深色底上有"厚度"
    drawCircle(
        color = mark.copy(alpha = alpha * 0.45f),
        radius = radius * 0.62f,
        center = Offset(cx, cy),
    )
    // 中心竖线（简化符号，避免引图标）
    drawLine(
        color = mark.copy(alpha = alpha * 0.85f),
        start = Offset(cx, cy - radius * 0.32f),
        end = Offset(cx, cy + radius * 0.32f),
        strokeWidth = radius * 0.16f,
        cap = StrokeCap.Round,
    )
}

// ---------------------------------------------------------------------------
// 选项卡片 / 复选框
// ---------------------------------------------------------------------------

/**
 * 单枚投币卡片。
 *
 * 选中态用**尺寸 + 描边 + 亮度**三重表达，不只靠颜色 ——
 * 色弱用户也要能看出选了哪张（§5.1「颜色不是唯一载体」）。
 *
 * ⚠️ 它现在由 pager 渲染，宽度由 pager 的页宽决定，
 * 不再自己写 `width(...)` —— 自己定宽会和 pager 的滑动冲突。
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
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.9f,
        animationSpec = tween(Motion.FADE_MS, easing = Motion.standard),
        label = "coinCardScale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.x2, vertical = Space.x1)
            // 币数是**可点选的交互元素**（§5.1 硬规则 2：交互元素 4dp）。
            .clip(RoundedCornerShape(Radius.interactive))
            .background(base.copy(alpha = if (selected) 1f else 0.72f))
            .then(
                if (selected) {
                    Modifier.androidxBorder(colors.accentCoinBright)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(scale),
        ) {
            // 图标沿用 Material 通用图标（不使用 B 站专有素材）
            Icon(
                imageVector = Icons.Filled.MonetizationOn,
                contentDescription = null,
                tint = colors.onAccentCoin,
                modifier = Modifier.size(if (selected) Sizes.iconXl + Space.x2 else Sizes.iconXl),
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

/** 选中态的描边（抽出成扩展函数，避免在链式调用里塞条件表达式）。 */
private fun Modifier.androidxBorder(color: Color): Modifier =
    border(
        width = 1.dp,
        color = color,
        shape = RoundedCornerShape(Radius.interactive),
    )

/**
 * 复选框图形（自绘）。
 *
 * 不引 Material 的 `Checkbox`：它的默认尺寸 48dp 会把这行撑得过高，
 * 而这里需要紧凑。自绘 16dp 方块 + 勾。
 */
@Composable
private fun CheckBoxGlyph(checked: Boolean) {
    val colors = BiliTheme.colors
    Box(
        modifier = Modifier
            .size(CHECKBOX_SIZE)
            .clip(RoundedCornerShape(Radius.control))
            .background(if (checked) colors.accentCoinBright else colors.borderStrong),
        contentAlignment = Alignment.Center,
    ) {
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

/** 场景高度。够画出"抬手 + 手上方悬浮硬币"的纵向空间。 */
private val SCENE_H = 132.dp

/** 选项卡片高度（pager 页高）。 */
private val CARD_H = 92.dp

/** 自绘线条粗细。 */
private val STROKE = 1.6.dp

private val CLOSE_BUTTON = 44.dp
private val CHECKBOX_SIZE = 16.dp

/** 确认按钮高度。44dp 接近标准触摸目标。 */
private val CONFIRM_BUTTON_H = 44.dp
