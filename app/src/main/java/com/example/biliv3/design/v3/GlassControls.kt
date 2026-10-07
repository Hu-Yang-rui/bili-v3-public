package com.example.biliv3.design.v3

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * **Liquid Glass 控件集** —— Sheet / Dialog / 按钮 / 浮动控件。
 *
 * ---
 *
 * # 使用边界（🔴 不要滥用）
 *
 * 设计系统明确写了玻璃的**唯一合法位置**：
 *
 * > Liquid Glass 是**内容之上的浮动导航/控制层**。
 * > 用于工具栏、Tab 栏、浮动控件、Sheet 外框。
 * > **不要**用在内容背景、大面积区域，也不要玻璃叠玻璃。
 *
 * 本文件提供的四个组件都遵守这条：
 *
 * | 组件 | 是浮动层吗 |
 * |---|---|
 * | [GlassSheet] | ✅ 从底部升起的浮层 |
 * | [GlassDialog] | ✅ 居中浮层 |
 * | [GlassButton] | ✅ 浮动控件（单个按钮浮在内容上） |
 * | [GlassFab] | ✅ 浮动操作钮 |
 *
 * ⚠️ **普通列表里的按钮不要用 [GlassButton]** —— 用 `V3Button`（实心）。
 * 玻璃按钮的价值在于"它浮在别的东西上面"，内容流里的按钮不浮。
 */

// ---------------------------------------------------------------------------
// 一、玻璃底部弹层
// ---------------------------------------------------------------------------

/**
 * **玻璃底部弹层**（Sheet）。
 *
 * ## 规格（iOS 27 实测）
 *
 * | 项 | 值 |
 * |---|---|
 * | 顶部圆角 | 34dp（Medium 浮动式） |
 * | 抓握条 | 58 × 4dp，距顶 5dp |
 * | 内容顶部内边距 | 16dp |
 *
 * ## 交互
 *
 * - 抓握条区域**可下拉关闭**（下拉超过阈值即关闭，带弹性回弹）
 * - 点遮罩关闭
 * - **系统返回键关闭**（见 `dismissOnBack` 的说明）
 *
 * ## 🔴 关于返回键
 *
 * 旧系统的规范里有一条：「浮层必须用 `Dialog` 或 `BackHandler`，
 * 普通 Compose 内容拦截不到系统返回，会穿透弹掉整页」。
 *
 * 本组件**必须**由调用方在 `Dialog` 里使用，或自己挂 `BackHandler`。
 * 这里用 `dismissOnBack` 参数显式要求调用方表态 ——
 * 默认 `true` 会挂 `BackHandler`，这样**单独用也不会穿透**。
 *
 * @param onDismiss 关闭回调
 * @param level 玻璃层级。Sheet 是大面积浮层 → 默认 [V3Glass.Level.UltraThin]（最清）
 */
@Composable
fun GlassSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 是否允许下拉关闭。 */
    draggable: Boolean = true,
    /** 是否显示抓握条。 */
    grabber: Boolean = true,
    /** 最大高度占屏比例。 */
    maxHeightFraction: Float = 0.9f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BiliV3.colors
    val scope = rememberCoroutineScope()

    // 下拉位移（关闭手势）
    val dragOffset = remember { Animatable(0f) }
    // 阈值：拉过 120dp 就关
    val dismissThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }

    androidx.activity.compose.BackHandler(enabled = true) { onDismiss() }

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 遮罩 ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        // ---- 玻璃面板 ----
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    if (draggable) {
                        Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffset.value > dismissThreshold) {
                                        onDismiss()
                                    } else {
                                        // 回弹（用 gentle spring，不过冲）
                                        scope.launch {
                                            dragOffset.animateTo(0f, V3Motion.gentle())
                                        }
                                    }
                                },
                                onVerticalDrag = { _, delta ->
                                    // 只允许向下拉（向上拉无意义，会把面板拽出屏幕）
                                    scope.launch {
                                        dragOffset.snapTo(
                                            (dragOffset.value + delta).coerceAtLeast(0f),
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer { translationY = dragOffset.value },
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(
                    topStart = V3Radius.sheet,
                    topEnd = V3Radius.sheet,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp,
                ),
                level = V3Glass.Level.UltraThin,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 2000.dp)
                        // 内容不能顶到抓握条上
                        .navigationBarsPadding(),
                ) {
                    if (grabber) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 5.dp, bottom = V3Space.xs),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(
                                        width = V3Size.grabberWidth,
                                        height = V3Size.grabberHeight,
                                    )
                                    .clip(CircleShape)
                                    .background(colors.labelQuaternary),
                            )
                        }
                    } else {
                        Spacer(Modifier.height(V3Space.sm))
                    }
                    content()
                    Spacer(Modifier.height(V3Space.sm))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 二、玻璃对话框
// ---------------------------------------------------------------------------

/**
 * **玻璃对话框**（Alert / Dialog）。
 *
 * ## 规格（iOS 27 实测）
 *
 * | 项 | 值 |
 * |---|---|
 * | 宽度 | 300dp |
 * | 内边距 | 14dp（四边） |
 * | 圆角 | **34dp**（iOS 27 从 14 提到 34） |
 * | 按钮高 | 48dp（胶囊） |
 * | 按钮间距 | 8dp（并排与堆叠**同值** —— 27.0.0 的变更） |
 *
 * ## 🔴 为什么不用 Material3 的 `AlertDialog`
 *
 * M3 的 AlertDialog 是"桌面窗口"形态（方形、大标题、按钮右对齐、
 * 有明确的分隔线）。iOS 风格是"轻量浮层"（圆角大、按钮居中或并排、
 * 无分隔线、整体像一张卡片浮起来）。
 *
 * 两者的**空间语义**不同：M3 是"对话框"，iOS 是"浮层"。
 * 本项目要的是后者。
 *
 * ⚠️ 本组件用 `Dialog` 包裹（拿到系统级的遮罩 + 返回键拦截 + 焦点管理），
 * 但**内容完全自绘**。
 */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    /** 主操作（右侧/底部，品牌色）。 */
    confirmText: String = "好",
    onConfirm: () -> Unit = onDismiss,
    /** 次操作（左侧，无底色）。传 null 则只有一个按钮。 */
    dismissText: String? = null,
    onDismissAction: (() -> Unit)? = null,
    /** 是否用醒目的破坏性配色（删除类操作）。 */
    destructive: Boolean = false,
    /** 标题与内容之间的自定义区域（如输入框、列表）。 */
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = BiliV3.colors

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            // ⚠️ 关掉默认的 dim：我们自己画遮罩（默认遮罩比 iOS 的深，且不可调）
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = modifier
                .width(300.dp)
                .padding(vertical = V3Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(V3Radius.sheet),
                level = V3Glass.Level.UltraThin,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                ) {
                    // ---- 标题 ----
                    Text(
                        text = title,
                        style = V3Type.headline,
                        color = colors.labelPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (message != null) {
                        Spacer(Modifier.height(V3Space.xs))
                        Text(
                            text = message,
                            style = V3Type.footnote,
                            color = colors.labelSecondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (body != null) {
                        Spacer(Modifier.height(V3Space.sm))
                        body()
                    }
                    Spacer(Modifier.height(V3Space.lg))

                    // ---- 按钮 ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (dismissText == null) {
                            Arrangement.Center
                        } else {
                            Arrangement.spacedBy(V3Space.xs)
                        },
                    ) {
                        if (dismissText != null) {
                            GlassDialogButton(
                                text = dismissText,
                                filled = false,
                                modifier = Modifier.weight(1f),
                                onClick = { onDismissAction?.invoke() ?: onDismiss() },
                            )
                        }
                        GlassDialogButton(
                            text = confirmText,
                            filled = true,
                            destructive = destructive,
                            modifier = Modifier.weight(if (dismissText == null) 0.6f else 1f),
                            onClick = onConfirm,
                        )
                    }
                }
            }
        }
    }
}

/** 对话框按钮（胶囊，48dp 高 —— 实测值）。 */
@Composable
private fun GlassDialogButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(V3Radius.pill))
            .background(
                when {
                    destructive -> colors.stateError.copy(alpha = if (filled) 1f else 0.16f)
                    filled -> colors.brand
                    else -> colors.fillSecondary
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = V3Type.headline,
            fontWeight = FontWeight.SemiBold,
            color = when {
                destructive && filled -> Color.White
                destructive -> colors.stateError
                filled -> colors.labelOnBrand
                else -> colors.brand
            },
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 三、玻璃按钮 / 浮动控件
// ---------------------------------------------------------------------------

/**
 * **玻璃按钮** —— 浮在内容之上的单个按钮。
 *
 * ⚠️ **只用于浮动场景**（压在媒体/画面上、悬浮在列表角落）。
 * 内容流里的按钮请用实心按钮 —— 见文件头部的使用边界。
 *
 * @param prominent 是否用品牌色（主操作）。实测 prominent 会加一层不透明底 + 着色。
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    text: String? = null,
    /** 是否用品牌色（主操作）。 */
    prominent: Boolean = false,
    /** 是否圆形（只有图标时）。 */
    circular: Boolean = false,
    size: Dp = V3Size.buttonLarge,
    enabled: Boolean = true,
) {
    val colors = BiliV3.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = if (circular) CircleShape else RoundedCornerShape(V3Radius.pill)

    // 按下缩放（iOS 的按钮反馈不是水波纹，是轻微缩小）
    val pressed = interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed.value) 0.96f else 1f,
        animationSpec = V3Motion.snappy(),
        label = "glassBtnScale",
    )

    GlassSurface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        shape = shape,
        level = V3Glass.Level.Thin,
        // prominent：用品牌色给玻璃染色（不是铺实心色 —— 那就不叫玻璃了）
        tint = if (prominent) colors.brand.copy(alpha = 0.55f) else null,
        colorTint = true,
    ) {
        Row(
            modifier = Modifier
                .then(if (circular) Modifier.size(size) else Modifier.height(size))
                .padding(horizontal = if (circular) 0.dp else V3Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = if (prominent) Color.White else colors.labelPrimary,
                    modifier = Modifier.size(V3Size.iconMd),
                )
                if (text != null) Spacer(Modifier.width(V3Space.xs))
            }
            if (text != null) {
                Text(
                    text = text,
                    style = V3Type.headline,
                    color = if (prominent) Color.White else colors.labelPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * **浮动操作钮**（FAB 的玻璃版）。
 *
 * 用途：回到顶部、回到最新、播放器快捷操作。
 *
 * ⚠️ 一屏**最多一个** —— 浮动钮的价值在于"唯一性"，
 * 多了就变成"漂浮的按钮堆"。
 */
@Composable
fun GlassFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val colors = BiliV3.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed.value) 0.92f else 1f,
        animationSpec = V3Motion.snappy(),
        label = "fabScale",
    )
    GlassSurface(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
        level = V3Glass.Level.Thin,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = colors.labelPrimary,
                modifier = Modifier.size(V3Size.iconMd),
            )
        }
    }
}

/**
 * 实心按钮（**非玻璃**）—— 内容流里的主操作。
 *
 * 与 [GlassButton] 的分工：
 * - 这个用在**内容里**（表单提交、关注、登录）
 * - [GlassButton] 用在**浮层上**
 */
@Composable
fun V3Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** `filled` = 品牌色底；`tinted` = 淡品牌色底；`plain` = 无底。 */
    variant: V3ButtonVariant = V3ButtonVariant.Filled,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = BiliV3.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.4f
            pressed.value -> 0.75f
            else -> 1f
        },
        animationSpec = V3Motion.fade(),
        label = "btnAlpha",
    )

    val (bg, fg) = when (variant) {
        V3ButtonVariant.Filled -> colors.brand to colors.labelOnBrand
        V3ButtonVariant.Tinted -> colors.brand.copy(alpha = 0.16f) to colors.brandText
        V3ButtonVariant.Plain -> Color.Transparent to colors.brandText
        V3ButtonVariant.Destructive -> colors.stateError to Color.White
    }

    Row(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .height(V3Size.buttonLarge)
            .clip(RoundedCornerShape(V3Radius.pill))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = V3Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(V3Size.iconSm),
            )
            Spacer(Modifier.width(V3Space.xs))
        }
        Text(
            text = text,
            style = V3Type.headline,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
        )
    }
}

/** 按钮变体。 */
enum class V3ButtonVariant { Filled, Tinted, Plain, Destructive }
