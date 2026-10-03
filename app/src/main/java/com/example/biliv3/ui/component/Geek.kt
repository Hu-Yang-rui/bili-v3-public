package com.example.biliv3.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.DeviceTier
import com.example.biliv3.design.LocalDeviceTier
import com.example.biliv3.design.tokens.FontFamilies
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Motion
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Space

/**
 * 极客点缀组件集。
 *
 * ---
 *
 * ## 🔴 使用边界（这是本文件存在的全部理由）
 *
 * 这些组件是**标点，不是正文**。约束：
 *
 * 1. **总面积 ≤ 一屏的 5%** —— 它们只能出现在角落、状态行、微标签
 * 2. **只用于「系统状态」语境** —— 加载 / 空 / 错误 / 读数，
 *    不要拿它装饰普通内容
 * 3. **不改变信息结构** —— 提示符只是前缀，不新增信息层级
 *
 * ## 严禁（本文件不提供任何相关能力）
 *
 * - 代码雨 / 矩阵雨 / 满屏字符滚动
 * - 扫描线 / 故障艺术 / 像素风
 * - 黑底亮绿字铺满
 * - 无意义的命令行刷屏动画
 *
 * 一句话：**如果一个元素去掉后信息没有损失，它就不该存在** ——
 * 除非它承担"这里在发生什么"的状态语义（如光标表示"进行中"）。
 */

// ---------------------------------------------------------------------------
// 1. 等宽读数
// ---------------------------------------------------------------------------

/**
 * 等宽数字读数。
 *
 * 用于**时间轴、计数、分辨率、错误码**这类技术信息。
 *
 * ## 为什么等宽有意义（不只是风格）
 *
 * 比例字体下 `1` 比 `8` 窄，数字跳动时整行会**左右抖动** ——
 * 时间轴每秒刷新一次，抖动非常明显。等宽字体让每个数字占同样宽度，
 * 读数稳定。**这是功能收益，不是装饰。**
 *
 * ⚠️ 只用于数字 / 短技术串。**不要用于中文正文** —— 等宽中文极难读。
 */
@Composable
fun MonoReadout(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = BiliTheme.colors.textSecondarySafe,
    fontSize: TextUnit = FontSize.monoReadout,
    weight: FontWeight = FontWeight.Medium,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamilies.mono,
            fontSize = fontSize,
            lineHeight = FontSize.monoReadoutLine,
            color = color,
            fontWeight = weight,
            // 轻微字距：等宽数字之间太挤会显得"糊成一团"
            letterSpacing = 0.5.sp,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// 2. 提示符前缀
// ---------------------------------------------------------------------------

/**
 * 提示符 + 内容的一行。
 *
 * ```
 * $ 正在加载推荐…
 * > 没有更多了
 * ```
 *
 * ## 用在哪
 *
 * 只用在**状态语境**：加载中、空态、错误态、日志式提示。
 * 不要用在正文段落前 —— 那会变成"到处都是 $ 符号"的装饰噪音。
 *
 * ## 颜色
 *
 * 提示符用 `accentTerminal`（终端青，低饱和），内容用常规文字色。
 * **提示符不抢内容** —— 它是标记，不是主角。
 */
@Composable
fun PromptLine(
    text: String,
    modifier: Modifier = Modifier,
    symbol: String = "$",
    textColor: Color = BiliTheme.colors.textSecondarySafe,
) {
    val colors = BiliTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamilies.mono,
                fontSize = FontSize.label,
                color = colors.accentTerminal,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.width(Space.x2))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamilies.mono,
                fontSize = FontSize.label,
                lineHeight = FontSize.labelLine,
                color = textColor,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// 3. 方块光标
// ---------------------------------------------------------------------------

/**
 * 闪烁方块光标。
 *
 * ## 语义：**"正在进行"**
 *
 * 它唯一的作用是表达"这里在等 / 在跑"。所以：
 * - **只在加载/进行中显示**，静止状态下不要显示（那才是纯装饰）
 * - **低端设备降级为常亮**（不做闪烁动画，省一次每帧重组）
 *
 * ## 为什么不用"呼吸灯"或"扫描线"
 *
 * 那些是"科技感"套路，与"现代简洁"冲突。
 * 一个 8dp 的方块按 1.2s 周期明暗变化，已经足够表达"活着"。
 */
@Composable
fun BlockCursor(
    modifier: Modifier = Modifier,
    color: Color = BiliTheme.colors.accentTerminal,
    size: Dp = 8.dp,
) {
    val tier = LocalDeviceTier.current

    // 低端设备：不做动画（省掉一个每帧重组）
    val alpha = if (tier == DeviceTier.Low) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "cursor")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.SHIMMER_MS / 2, easing = Motion.linear),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cursorAlpha",
        )
        a
    }

    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha)
            .background(color, RoundedCornerShape(1.dp)),
    )
}

// ---------------------------------------------------------------------------
// 4. 微标签（终端风小标签）
// ---------------------------------------------------------------------------

/**
 * 微标签：等宽小字 + 极淡底 + 细边。
 *
 * ```
 * ┌──────────┐
 * │ 1080P 60 │
 * └──────────┘
 * ```
 *
 * ## 用在哪
 *
 * **技术属性**：清晰度、编码、码率、分辨率、时长。
 * 不要用在"分类名 / 频道名"这类语义标签上 —— 那些该用普通胶囊标签。
 *
 * ## 为什么用方角而不是胶囊
 *
 * 胶囊是"内容标签"的语言（圆润、亲和）；
 * 微标签表达的是"参数"（精确、技术）。方角 + 等宽 = 参数感。
 * 这是**两种不同的语义**，视觉上必须能区分。
 */
@Composable
fun TechTag(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamilies.mono,
            fontSize = FontSize.badge,
            color = colors.textTertiary,
            letterSpacing = 0.5.sp,
        ),
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(Radius.badge))
            .background(colors.bgHover)
            .padding(horizontal = Space.x1 + 2.dp, vertical = 1.dp),
    )
}

// ---------------------------------------------------------------------------
// 5. 极淡网格底
// ---------------------------------------------------------------------------

/**
 * 极淡网格底纹。
 *
 * ## 用在哪
 *
 * **只用于空态 / 骨架屏背景**，给"这里是空的"一点材质感。
 * **不要**用在正常内容页 —— 那是纯装饰，且会与卡片边缘打架。
 *
 * ## 参数克制
 *
 * - 线宽 **1px**（不是 1dp，1dp 在 3x 屏上太粗）
 * - 间距 **24dp**（太密会变成"稿纸"）
 * - 颜色 [com.example.biliv3.design.tokens.BiliColors.gridLine]（约 6% 白，几乎看不见）
 *
 * 低端设备**不绘制**（省掉一次全屏 draw）。
 */
@Composable
fun Modifier.gridBackdrop(
    cell: Dp = 24.dp,
): Modifier {
    val tier = LocalDeviceTier.current
    if (tier == DeviceTier.Low) return this

    val colors = BiliTheme.colors
    return this.drawBehind {
        val step = cell.toPx()
        if (step <= 0f) return@drawBehind
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = colors.gridLine,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = colors.gridLine,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += step
        }
    }
}

// ---------------------------------------------------------------------------
// 6. 状态行（加载/完成提示）
// ---------------------------------------------------------------------------

/**
 * 状态行：提示符 + 等宽文字 + 可选光标。
 *
 * 用于**加载态 / 空态 / 错误态**的统一头部。
 *
 * ```
 * $ 正在加载推荐…  ▌
 * ```
 *
 * ⚠️ 不要用它替代页面的主标题或说明 —— 它是"系统在说话"的语境，
 * 不是"页面在介绍自己"。
 */
@Composable
fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
    symbol: String = "$",
    showCursor: Boolean = false,
    textColor: Color = BiliTheme.colors.textSecondarySafe,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PromptLine(text = text, symbol = symbol, textColor = textColor)
        if (showCursor) {
            Spacer(Modifier.width(Space.x2))
            BlockCursor()
        }
    }
}

/**
 * 技术信息行：多个等宽键值对。
 *
 * ```
 * 分辨率 1920x1080   编码 H.264   码率 4.2Mbps
 * ```
 *
 * 用于播放器设置面板、下载详情这类**参数展示**场景。
 */
@Composable
fun TechInfoRow(
    pairs: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        pairs.forEach { (k, v) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.compactVertical),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = k,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamilies.mono,
                        fontSize = FontSize.badge,
                        color = colors.textTertiary,
                    ),
                    modifier = Modifier.width(72.dp),
                    maxLines = 1,
                )
                MonoReadout(
                    text = v,
                    color = colors.textSecondarySafe,
                    fontSize = FontSize.label,
                )
            }
        }
    }
}
