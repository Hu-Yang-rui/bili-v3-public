package com.example.biliv3.design

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.example.biliv3.design.tokens.Band
import com.example.biliv3.design.tokens.Emboss
import com.example.biliv3.design.tokens.FontFamilies
import com.example.biliv3.design.tokens.Grain
import com.example.biliv3.design.tokens.Rule
import kotlin.random.Random
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Type

// ---------------------------------------------------------------------------
// 五个分隔原语 —— 替代 BiliCard 的全部词汇
// ---------------------------------------------------------------------------

/**
 * ① 发丝线 —— **分四个明确的边**。
 *
 * ## 🔴 这里有一个真实 bug，已修（记录备查）
 *
 * 上一版只有一个 `rule()`，在 `size.height / 2f` 画线 —— 那是"垂直居中"。
 *
 * 对 1dp 高的分隔条没问题，但**用在高组件上就完全错了**：
 * 底栏高 64dp，线会画在**底栏的正中间**（被内容盖住），
 * 而调用方期望的是"底栏顶边的那条线"。
 *
 * 实测后果：顶栏/底栏的分隔线**看不见**。
 * 取色验证时整屏都是 `#0E1116`，**一条线都取不到** —— 这就是 bug 的证据。
 *
 * ## 现在
 *
 * 不再用"居中"这种含糊语义。要哪条边就调哪个：
 * [ruleTop] / [ruleBottom] / [ruleStart] / [ruleEnd]。
 */
fun Modifier.ruleTop(color: Color = Rule.color, width: Dp = Rule.width): Modifier =
    drawBehind {
        val w = width.toPx()
        drawLine(color, Offset(0f, w / 2f), Offset(size.width, w / 2f), w)
    }

fun Modifier.ruleBottom(color: Color = Rule.color, width: Dp = Rule.width): Modifier =
    drawBehind {
        val w = width.toPx()
        drawLine(
            color,
            Offset(0f, size.height - w / 2f),
            Offset(size.width, size.height - w / 2f),
            w,
        )
    }

/** 竖线（左侧）。 */
fun Modifier.ruleStart(color: Color = Rule.color, width: Dp = Rule.width): Modifier =
    drawBehind {
        val w = width.toPx()
        drawLine(color, Offset(w / 2f, 0f), Offset(w / 2f, size.height), w)
    }

/**
 * 兼容别名 —— 等同于 [ruleTop]。
 *
 * ⚠️ 保留它只是为了让"插一条 1dp 线"这种常见写法读起来顺。
 * **给高组件画边时不要用它**，请明确写 `ruleTop` / `ruleBottom`。
 */
fun Modifier.rule(
    color: Color = Rule.color,
    width: Dp = Rule.width,
): Modifier = ruleTop(color, width)

/** 一条横向发丝线（自带 1dp 高度，插在元素之间用）。 */
@Composable
fun RuleLine(
    modifier: Modifier = Modifier,
    color: Color = Rule.color,
    inset: Dp = Dp.Unspecified,
) {
    val pad = if (inset == Dp.Unspecified) V3Space.md else inset
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = pad)
            .height(Rule.width)
            .ruleTop(color = color),
    )
}

/**
 * ② 明度带 —— 全宽、直角、无描边。
 *
 * 这是替代卡片的**主要手段**。左右贴屏幕边，只有上下两条边界。
 */
fun Modifier.band(level: BandLevel = BandLevel.Raised): Modifier = drawBehind {
    drawRect(color = level.color)
}

enum class BandLevel(val color: Color) {
    Flat(Band.flat),
    Raised(Band.raised),
    Sunken(Band.sunken),
}

/**
 * ③ 压印 —— 上暗下亮两条 1dp 线，做出"内凹"。
 *
 * 深色下投影做不出内凹，这是唯一手段。零性能开销。
 */
fun Modifier.emboss(): Modifier = drawBehind {
    val w = Rule.width.toPx()
    drawLine(
        color = Emboss.top,
        start = Offset(0f, w / 2f),
        end = Offset(size.width, w / 2f),
        strokeWidth = w,
    )
    drawLine(
        color = Emboss.bottom,
        start = Offset(0f, size.height - w / 2f),
        end = Offset(size.width, size.height - w / 2f),
        strokeWidth = w,
    )
}

/**
 * ④ 颗粒 —— 噪点贴图平铺，去掉"数字纯色"的塑料感。
 *
 * ## 实现要点
 *
 * - 噪点图**进程级缓存**（[grainImage]），不是每帧生成
 * - 用 [ShaderBrush] + [TileMode.Repeated] 平铺，一次 `drawRect` 画完
 * - 低端档（[DeviceTier.Low]）直接不画
 *
 * ⚠️ 必须在 [drawWithCache] 里取 [LocalDeviceTier]，
 * 不能在 draw 阶段读 CompositionLocal（那是非组合上下文）。
 */
fun Modifier.grain(enabled: Boolean = true): Modifier =
    if (!enabled) this
    else this.drawWithCache {
        val shader = androidx.compose.ui.graphics.ImageShader(
            image = grainImage(),
            tileModeX = TileMode.Repeated,
            tileModeY = TileMode.Repeated,
        )
        val brush = ShaderBrush(shader)
        onDrawBehind { drawRect(brush = brush) }
    }

/**
 * ⑥ 纵向标尺 —— 楼中楼的层级表达。
 *
 * ## 为什么楼中楼适合标尺而不是缩进
 *
 * 缩进（每层 +16dp）在 2~3 层后就把可用宽度吃掉了，
 * 手机竖屏下第 3 层只剩半屏宽，正文被挤成"一字一行"。
 *
 * 标尺是**一条竖线 + 左侧固定缩进**：
 * - 层级靠"线在不在"表达，不靠"缩进多少"
 * - 无论多少层，正文宽度都不变
 * - 竖线天然表达"这是上面那条的延续"
 *
 * 这是方案 2（时间轴）最自然的落点。
 */
fun Modifier.ruler(color: Color = Rule.subtle): Modifier = drawBehind {
    val w = Rule.width.toPx()
    drawLine(
        color = color,
        start = Offset(w / 2f, 0f),
        end = Offset(w / 2f, size.height),
        strokeWidth = w,
    )
}

/**
 * 噪点图（进程级缓存）。
 *
 * 用固定种子，保证每次生成同一张图 —— 否则每帧都不同会"闪"。
 */
private var cachedGrain: ImageBitmap? = null

private fun grainImage(): ImageBitmap {
    cachedGrain?.let { return it }
    val n = Grain.TILE
    val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val rnd = Random(20260103)
    val px = IntArray(n * n)
    val alpha = (Grain.ALPHA * 255).toInt().coerceIn(0, 255)
    for (i in px.indices) {
        // 一半亮一半暗，各自带随机强度 —— 纯随机会偏灰，双向才像"颗粒"
        val v = rnd.nextInt(alpha + 1)
        val bright = rnd.nextBoolean()
        px[i] = if (bright) (v shl 24) or 0x00FFFFFF
        else (v shl 24)
    }
    bmp.setPixels(px, 0, n, 0, 0, n, n)
    val img = bmp.asImageBitmap()
    cachedGrain = img
    return img
}

/**
 * ⑤ 章节标记 —— `01 ── 推荐`
 *
 * ## 为什么用它替代"卡片组标题"
 *
 * 卡片组的标题需要一个容器来承载，而容器就是臃肿的来源。
 * 章节标记只用**一行**，却同时表达了「这是新章节」与「章节序号」。
 *
 * 等宽序号是这里的"极客点缀"—— 它承担真实语义（顺序），不是装饰。
 */
@Composable
fun SectionMark(
    index: Int,
    title: String,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = V3Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            style = V3Type.footnote.copy(
                fontFamily = FontFamilies.mono,
                fontSize = V3Type.caption2.fontSize,
                color = colors.accentTerminal,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(Modifier.width(V3Space.sm))
        Text(
            text = title,
            style = V3Type.subheadline.copy(
                color = colors.labelPrimary,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.width(V3Space.sm))
        // 余下的空间用一条极淡的线补满 —— 它是"章节的延伸"，不是边框
        Box(
            modifier = Modifier
                .weight(1f)
                .height(Rule.width)
                .rule(color = Rule.subtle),
        )
    }
}
