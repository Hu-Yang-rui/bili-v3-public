package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import com.example.biliv3.data.model.CommentItem
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space

/**
 * 评论输入弹层（发主评论 / 回复评论）。
 *
 * ## 布局（底部弹出，带键盘避让）
 *
 * ```
 * ┌────────────────────────────────┐
 * │ 发表评论 / 回复 @某人            │
 * │ ┌────────────────────────────┐ │
 * │ │ 说点什么…                   │ │
 * │ └────────────────────────────┘ │
 * │                  [ 发送 ]      │
 * └────────────────────────────────┘
 * ```
 *
 * ## `imePadding()` 是必需的
 *
 * 输入框在屏幕底部，键盘弹起会盖住它。不加 `imePadding` 用户
 * 看不见自己打的内容 —— 这是移动端输入框最常见的可用性缺陷。
 *
 * ## 为什么用 `Dialog` 而不是行内输入框
 *
 * 详情页本身是可滚动的 `LazyColumn`，行内输入框会随滚动跑掉、
 * 且键盘弹出时的布局位移很难控。Dialog 有独立 window，
 * 位置稳定、返回键能正常关闭。
 */
@Composable
fun CommentInputSheet(
    replyTo: CommentItem?,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    val colors = BiliTheme.colors
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    // 打开即聚焦（用户点"回复"就是要打字，不该再点一次输入框）
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    // ⚠️ 为什么这里**不用 Dialog**（踩了四次坑，完整记录）
    //
    // ## 四次无效尝试
    //
    // | # | 方案 | 结果 |
    // |---|---|---|
    // | 1 | 里层 Column 加 `imePadding()` | 无效。它是 `BottomCenter` 的子元素，加 padding 只让自己变高，父级仍把**底边**对齐到屏幕底（键盘之下） |
    // | 2 | 把 `imePadding()` 移到外层 Box | 无效 |
    // | 3 | dialog window 设 `SOFT_INPUT_ADJUST_RESIZE` | 无效 |
    // | 4 | `ViewCompat.getRootWindowInsets` 读 IME 高度 | 无效，读到恒为 0 |
    // | 5 | `getWindowVisibleDisplayFrame` 测键盘高度 | 无效，`covered` 恒为 0 |
    //
    // ## 根因
    //
    // `Dialog` 是**独立 window**，且我们设了
    // `decorFitsSystemWindows = false`（为了让遮罩铺满全屏、不被状态栏裁切）。
    // 这个 flag 让该 window **完全不参与 IME inset 派发** ——
    // 方案 3/4/5 全部失效，因为它们的共同前提都是"window 会为键盘让位"。
    //
    // ## 采用的方案：不用 Dialog，改用 Activity 内的浮层
    //
    // 本 Composable 直接渲染在**当前页面的 composition** 里（一个覆盖全屏的
    // `Box`），而不是独立 window。这样：
    //
    // - 用的是 Activity 的 window，`WindowInsets.ime` 正常派发
    //   → `imePadding()` 生效（本页其它地方一直这么用）
    // - 保住全屏遮罩（`fillMaxSize` + 半透明底）
    //
    // ## 代价（可接受）
    //
    // - 不再自动拦截系统返回键 → 由 `VideoDetailScreen` 的 `BackHandler`
    //   统一处理（那里本来就有分层返回逻辑，见 `ui.hasOverlay`）
    // - 不再有独立 window 的层级隔离 → 但本浮层已是页面最上层，
    //   且弹层互斥由 `VideoDetailUiState.overlay` 保证
    //
    // 见 `VideoDetailScreen.kt` 中 `ui.overlay == DetailOverlay.CommentInput`
    // 的分支调用。
    Box(
        modifier = Modifier
            .fillMaxSize()
            // ⚠️ imePadding 必须在这里 —— 这是 Activity window 的
            // composition，inset 正常派发
            .imePadding()
            .background(colors.scrimPanel)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.panel, topEnd = Radius.panel))
                // 弹层用 `surfaceElevated`（比卡片亮一档）——
                // 深色下投影不可见，分层只能靠提亮。
                .background(colors.surfaceElevated)
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(horizontal = Space.x4, vertical = Space.x3),
        ) {
            Text(
                text = if (replyTo != null) "回复 @${replyTo.userName}" else "发表评论",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textSecondarySafe,
                ),
                maxLines = 1,
            )

            Spacer(Modifier.height(Space.x2))

            // ---- 输入框 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(INPUT_HEIGHT)
                    .clip(RoundedCornerShape(Radius.interactive))
                    .background(colors.bgHover)
                    .padding(horizontal = Space.x3, vertical = Space.x2),
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "说点什么…",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.body,
                            color = colors.textTertiary,
                        ),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.textPrimary,
                    ),
                    cursorBrush = SolidColor(colors.brandPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
            }

            Spacer(Modifier.height(Space.x3))

            // ---- 发送 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                val enabled = text.isNotBlank()
                Text(
                    text = "发送",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) colors.textOnBrand else colors.textTertiary,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(if (enabled) colors.brandPrimary else colors.bgHover)
                        .clickable(enabled = enabled) {
                            onSend(text.trim())
                        }
                        .padding(horizontal = Space.x5, vertical = Space.x2),
                )
            }
        }
    }
}

// ==================== 旧 Dialog 实现（已弃用，保留备查）====================
//
// 下面是改造前的 Dialog 版本。保留它是因为其中记录了键盘避让的
// 四次失败尝试（见上方说明）—— 若将来 Compose 修复了 Dialog 的
// IME inset 派发，可以改回 Dialog 以获得更好的返回键/层级行为。
//
// 关键差异：`Dialog { Box(...) { Column(...) } }` 是独立 window，
// `decorFitsSystemWindows = false` 使其不接收 IME insets。
//
@Suppress("unused")
private const val LEGACY_DIALOG_IMPLEMENTATION_NOTE = """
见 git 历史。改造原因：Dialog 独立 window + decorFitsSystemWindows=false
导致 imePadding / ADJUST_RESIZE / getRootWindowInsets / 
getWindowVisibleDisplayFrame 四种键盘避让方案全部失效。
"""

/**
 * 弹幕输入弹层（内容 + 颜色 + 位置）。
 *
 * ## 三项都必填吗
 *
 * 内容必填；颜色与位置有**合理默认**（白色、滚动），
 * 用户不选也能直接发 —— 减少必填项是移动端输入的基本原则。
 *
 * ## 位置只有三种
 *
 * 官方弹幕模式里用户可选的只有「滚动 / 顶部 / 底部」
 * （`mode` 1 / 5 / 4）。高级弹幕（mode 7/8）带定位脚本，
 * 本项目不支持渲染，也不该让用户发。
 *
 * @param onSend (内容, 颜色十进制 RGB, mode)
 */
@Composable
fun DanmakuInputSheet(
    onDismiss: () -> Unit,
    onSend: (text: String, color: Int, mode: Int) -> Unit,
) {
    val colors = BiliTheme.colors
    var text by remember { mutableStateOf("") }
    var colorIndex by remember { mutableIntStateOf(0) }
    var modeIndex by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    // ⚠️ 同样**不用 Dialog** —— 原因与 CommentInputSheet 完全一致
    // （Dialog 独立 window + decorFitsSystemWindows=false 会让
    //  imePadding / ADJUST_RESIZE / getRootWindowInsets /
    //  getWindowVisibleDisplayFrame 四种键盘避让方案全部失效）。
    //
    // 改为页面内浮层：调用方需把它放在根 Box 内部，见 VideoDetailScreen。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(colors.scrimPanel)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.panel, topEnd = Radius.panel))
                // 弹层用 `surfaceElevated`（比卡片亮一档）——
                // 深色下投影不可见，分层只能靠提亮。
                .background(colors.surfaceElevated)
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(horizontal = Space.x4, vertical = Space.x3),
        ) {
            Text(
                text = "发送弹幕",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textSecondarySafe,
                ),
            )

            Spacer(Modifier.height(Space.x2))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 🔴 v1.5.1：输入框加高（56 → 72dp）并让**文本区真正拿到高度**。
                    //
                    // 原写法把 `.height(56dp)` 放在 `.padding(vertical = 8dp)` **之前** ——
                    // 于是 56dp 里再扣掉上下各 8dp，文本实际只有 40dp，
                    // 加上 `BasicTextField` 默认单行居中，视觉上像"一条细缝"。
                    //
                    // 现在：先给足外层高度（72dp），文本区用 `weight`/`fillMaxHeight`
                    // 撑满扣除内边距后的空间，输入时不再局促。
                    //
                    // ⚠️ 不加 `imePadding`（键盘避让由调用方的根 Box 负责，
                    // 见本文件顶部关于"为什么不用 Dialog"的说明）。
                    .height(INPUT_HEIGHT)
                    .clip(RoundedCornerShape(Radius.interactive))
                    .background(colors.bgHover)
                    .padding(horizontal = Space.x3, vertical = Space.x2),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "发个弹幕吧…",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.body,
                            color = colors.textTertiary,
                        ),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.textPrimary,
                    ),
                    cursorBrush = SolidColor(colors.brandPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        // 撑满扣除内边距后的高度 —— 点输入框任意位置都能落光标
                        .fillMaxHeight()
                        .focusRequester(focus),
                )
            }

            Spacer(Modifier.height(Space.x3))

            // ---- 颜色选择 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "颜色",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                )
                Spacer(Modifier.width(Space.x3))
                DANMAKU_COLORS.forEachIndexed { i, c ->
                    Box(
                        modifier = Modifier
                            .size(if (i == colorIndex) COLOR_DOT_SELECTED else COLOR_DOT)
                            .clip(CircleShape)
                            .background(Color(c))
                            // ⚠️ 选中态用**描边**表达。
                            //
                            // 首版这里写的是 `.background(Color.Transparent)`
                            // —— 那是个空操作（透明背景等于没画），
                            // 所以选中态完全看不出来，属于实现漏了。
                            .then(
                                if (i == colorIndex) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = colors.textPrimary,
                                        shape = CircleShape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { colorIndex = i },
                    )
                    Spacer(Modifier.width(Space.x2))
                }
            }

            Spacer(Modifier.height(Space.x2))

            // ---- 位置选择 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "位置",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                )
                Spacer(Modifier.width(Space.x3))
                DANMAKU_MODES.forEachIndexed { i, (label, _) ->
                    val selected = i == modeIndex
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) colors.textOnBrand else colors.textSecondarySafe,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(if (selected) colors.brandPrimary else colors.bgHover)
                            .clickable { modeIndex = i }
                            .padding(horizontal = Space.x3, vertical = Space.compactHorizontal),
                    )
                    Spacer(Modifier.width(Space.x2))
                }
            }

            Spacer(Modifier.height(Space.x3))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                val enabled = text.isNotBlank()
                Text(
                    text = "发送",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) colors.textOnBrand else colors.textTertiary,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(if (enabled) colors.brandPrimary else colors.bgHover)
                        .clickable(enabled = enabled) {
                            onSend(
                                text.trim(),
                                DANMAKU_COLORS[colorIndex],
                                DANMAKU_MODES[modeIndex].second,
                            )
                        }
                        .padding(horizontal = Space.x5, vertical = Space.x2),
                )
            }
        }
    }
}

/**
 * 输入框高度。
 *
 * ## ⚠️ 72dp → 96dp 之后又收回 56dp
 *
 * 首版 72dp（≈ 3 行）。实测在键盘弹起后，面板总高
 * （标题 20 + 输入 72 + 按钮 40 + 内边距 24 + 导航栏）接近
 * 键盘上方的可视高度，加上 `imePadding` 修正后曾一度把「发送」
 * 顶出屏幕 —— 这也是当时误以为"发送按钮点了没反应"的原因之一。
 *
 * 56dp（≈ 2 行）足够写短评论，也给按钮留出确定位置。
 * 长评论靠输入框内部滚动（`BasicTextField` 默认行为）。
 */
private val INPUT_HEIGHT = 72.dp

/** 可选弹幕颜色（十进制 RGB，与接口 `color` 字段一致）。 */
private val DANMAKU_COLORS = listOf(
    16777215, // 白
    16711680, // 红
    16776960, // 黄
    65280, // 绿
    255, // 蓝
    16711935, // 紫
)

/** 可选位置：`mode` 值来自官方弹幕协议。 */
private val DANMAKU_MODES = listOf(
    "滚动" to 1,
    "顶部" to 5,
    "底部" to 4,
)

private val COLOR_DOT = 24.dp
private val COLOR_DOT_SELECTED = 30.dp
