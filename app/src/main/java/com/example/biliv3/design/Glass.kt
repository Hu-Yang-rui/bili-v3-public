package com.example.biliv3.design

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.tokens.Radius
import kotlinx.coroutines.delay

/**
 * 毛玻璃（glassmorphism）设计系统。
 *
 * ---
 *
 * ## 🔴 先说清楚一个技术真相
 *
 * **Compose 的 `Modifier.blur()` 模糊的是「自己的内容」，不是「背后的东西」。**
 *
 * ```kotlin
 * // ❌ 错误期待：以为会把后面的视频糊掉
 * Box(Modifier.blur(24.dp)) { Text("已跳过广告") }
 * // 实际结果：文字自己糊了，视频一点没变
 * ```
 *
 * 所以"玻璃压在视频上"**无法**靠 `Modifier.blur()` 实现。
 *
 * ---
 *
 * ## 本项目的实现路径：抓帧 → 缩小 → 放大
 *
 * | 步骤 | 做法 | 为什么 |
 * |---|---|---|
 * | 1. 抓帧 | `TextureView.getBitmap(w/8, h/8)` | 拿到视频当前画面的一张小图 |
 * | 2. 放大 | `drawImage(dstSize = 面板大小, filterQuality = Low)` | **双线性放大天然产生模糊** |
 * | 3. 平滑 | `RenderEffect` 高斯模糊（API 31+） | 在缩小版上再糊一次，质感更好 |
 * | 4. 上色 | 半透明白底 + 高光描边 | 玻璃的"实体感"来自这层，不是模糊 |
 *
 * 第 2 步是关键：**把 1/8 的小图拉满屏，本身就是一次廉价且效果不错的模糊**。
 * 它不依赖 API 31，所以在 `minSdk 26` 上也有效。
 *
 * ---
 *
 * ## 为什么要 `TextureView`
 *
 * `PlayerView` 默认用 `SurfaceView` —— 它的内容在**独立的合成层**，
 * `getBitmap()` 拿不到（返回黑图）。必须切到 `surface_type="texture_view"`。
 *
 * 附带收益：`TextureView` 是普通 View，可以**跨页面 re-parent**
 * 而不重建 surface —— 这同时解决了「竖屏↔横屏切换黑一帧」的问题。
 *
 * ---
 *
 * ## 分层结构（顺序不能错）
 *
 * ```
 * Box(面板)
 *  ├─ ① 模糊底：drawImage(小图拉满) + blur     ← 只有这层被模糊
 *  ├─ ② 玻璃色：半透明白/黑
 *  ├─ ③ 高光边：1dp 白描边
 *  └─ ④ 内容：文字/图标                        ← 绝不能进 blur
 * ```
 *
 * ⚠️ **内容层绝不能放进被模糊的 Box** —— 否则字会糊掉。
 * 这是这套 API 设计成"分层参数"而不是"一个 Modifier"的原因。
 */
object GlassTokens {

    /** 轻玻璃：小面板、标签。 */
    val blurSmall = 12.dp

    /** 标准玻璃：底部信息区、控制条。 */
    val blurMedium = 24.dp

    /** 重玻璃：全屏弹层。 */
    val blurLarge = 32.dp

    /**
     * 深色主题的玻璃底色。
     *
     * ## ⚠️ 为什么是 **55% 黑**，而不是"淡白玻璃"
     *
     * 第一版用了 12% 白（`0x1FFFFFFF`），实测**文字完全不可读** ——
     * 白色文字压在浅色视频画面（白 T 恤 / 雪景 / 白墙）上直接消失。
     *
     * 这是玻璃风格的经典陷阱：
     * **透明度必须由"最坏情况"决定，而不是由"好看的截图"决定。**
     *
     * 视频内容不可控，总会有亮场景。所以玻璃底必须足够暗，
     * 保证白字在任何画面上都达到可读对比度。
     *
     * 55% 黑 + 模糊 + 高光边：
     * - 仍能隐约透出底下画面（玻璃感保留）
     * - 白字对比度 ≈ 4.5:1（WCAG AA 达标）
     *
     * 一句话：**这是"压暗的玻璃"，不是"发白的玻璃"。**
     */
    val tintDark = Color(0x8C000000)   // 黑 55%

    /**
     * 浅色主题的玻璃底色。
     *
     * 浅色下玻璃多压在**页面**（非视频）上，文字是深色的 ——
     * 40% 白即可，不需要压暗。
     */
    val tintLight = Color(0x66FFFFFF)  // 白 40%

    /** 顶部高光边（玻璃的"厚度感"来源）。 */
    val borderDark = Color(0x33FFFFFF)  // 白 20%
    val borderLight = Color(0x33FFFFFF) // 白 20%

    /** 玻璃上的文字色（深色主题）。 */
    val onGlassDark = Color(0xFFFFFFFF)
    val onGlassDarkSecondary = Color(0xB3FFFFFF)  // 白 70%
}

/**
 * 视频帧提供者。
 *
 * 由播放器侧（`VideoPlayerSurface`）写入，由玻璃面板读取。
 *
 * ## 为什么用可变状态而不是参数传递
 *
 * 玻璃面板在**树的深处**（底部信息区、控制条），而帧来自**播放器 View**。
 * 层层传参会污染一路的签名。用一个共享 holder，
 * 面板只依赖它、不依赖播放器的存在。
 *
 * ## 线程
 *
 * `frame` 只在主线程写（`getBitmap` 需在 UI 线程调用）。
 */
class VideoBackdrop {
    /** 当前帧（已缩小）。null = 还没抓到 / 无视频。 */
    var frame by mutableStateOf<ImageBitmap?>(null)

    /** 当前帧的主色调，用于玻璃染色（避免灰白玻璃压彩色画面发脏）。 */
    var dominantColor by mutableStateOf<Color?>(null)
}

/**
 * 从 `TextureView` 持续抓帧，喂给 [VideoBackdrop]。
 *
 * ## 抓帧尺寸为什么是 1/8
 *
 * - 1080×2400 的 1/8 = 135×300，约 40KB
 * - 放大回全屏时**双线性插值天然模糊**，这正我们要的
 * - 抓大图（如 1/2）再模糊，成本高 16 倍，观感提升微乎其微
 *
 * ## 为什么是轮询而不是回调
 *
 * `TextureView` 没有"帧更新"回调。`SurfaceTexture.OnFrameAvailableListener`
 * 只能告诉你"有新帧"，但仍需自己去取。
 *
 * 轮询间隔 [FRAME_INTERVAL_MS] = 400ms：
 * - 玻璃是**静态装饰**，400ms 的滞后肉眼不可辨
 * - 视频暂停时画面不动，重复抓同一帧也无变化
 *
 * ⚠️ 抓帧在**主线程**做（`getBitmap` 的线程要求），
 * 所以间隔不能太短 —— 每次抓帧都会占用一帧的绘制时间。
 */
@Composable
fun VideoBackdropEffect(
    backdrop: VideoBackdrop,
    /** 取当前 `TextureView`。返回 null 表示暂时没有（如未起播）。 */
    textureProvider: () -> TextureView?,
    /** 是否启用。关掉可省电（如 PiP 小窗）。 */
    enabled: Boolean = true,
) {
    LaunchedEffect(backdrop, enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            val tv = textureProvider()
            if (tv != null && tv.isAvailable && tv.width > 0 && tv.height > 0) {
                val w = (tv.width / FRAME_DIVISOR).coerceAtLeast(1)
                val h = (tv.height / FRAME_DIVISOR).coerceAtLeast(1)
                // getBitmap 必须在主线程（TextureView 的线程约束）
                val bmp: Bitmap? = runCatching { tv.getBitmap(w, h) }.getOrNull()
                if (bmp != null) {
                    backdrop.frame = bmp.asImageBitmap()
                    backdrop.dominantColor = runCatching {
                        averageColor(bmp)
                    }.getOrNull()
                }
            }
            delay(FRAME_INTERVAL_MS)
        }
    }
}

/**
 * 玻璃表面。
 *
 * ## 用法
 *
 * ```kotlin
 * GlassSurface(
 *     backdrop = backdrop,
 *     shape = RoundedCornerShape(Radius.card),
 * ) {
 *     Text("@作者名")      // ← 内容不会被模糊
 * }
 * ```
 *
 * ## 降级
 *
 * | 情况 | 表现 |
 * |---|---|
 * | 有帧 + API 31+ | 缩小放大 + 高斯模糊（最佳） |
 * | 有帧 + API 26~30 | 缩小放大（**已经是模糊的**，只是没那么细腻） |
 * | 无帧（未起播/无视频） | 纯半透明底 —— 仍然成立，不"坏掉" |
 *
 * 第三种情况很重要：**没有模糊也要好看**。
 * 玻璃的质感主要来自「半透明底 + 高光边」，
 * 模糊只是让它更"透"。所以无帧时不会出现"一块死黑"。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    backdrop: VideoBackdrop? = null,
    shape: RoundedCornerShape = RoundedCornerShape(Radius.card),
    blur: Dp = GlassTokens.blurMedium,
    /** 底色。默认按主题取。传 `Color.Transparent` 可关掉染色。 */
    tint: Color? = null,
    /** 是否画顶部高光边。 */
    border: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = BiliTheme.colors
    val dark = colors.isDark
    val glassTint = tint ?: if (dark) GlassTokens.tintDark else GlassTokens.tintLight
    val frame = backdrop?.frame

    Box(modifier = modifier.clip(shape)) {
        // ---- ① 模糊底（独立子层）----
        //
        // ⚠️ **必须拆成独立子 Box**，不能把 `graphicsLayer{renderEffect}` 挂在
        // 外层 Box 上 —— 那会把**内容层（文字）一起糊掉**。
        //
        // 这是本文件注释里警告过、但第一版实现时确实踩了的坑：
        // 实测截图里玻璃底完美、文字完全消失（被糊成了背景的一部分）。
        //
        // `renderEffect` 的作用域是"该层及其子树"，所以
        // 「只糊底、不糊字」的唯一做法就是让底**自成一层**。
        if (frame != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blurredBackdrop(frame = frame, blur = blur),
            )
        }

        // ---- ② 玻璃色 ----
        // 独立子层，盖在模糊底之上、内容之下
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(glassTint)
                // ---- ③ 高光边 ----
                .then(
                    if (border) {
                        Modifier.border(
                            width = 1.dp,
                            color = if (dark) GlassTokens.borderDark else GlassTokens.borderLight,
                            shape = shape,
                        )
                    } else {
                        Modifier
                    },
                ),
        )

        // ---- ④ 内容（在独立层之上，绝不参与模糊）----
        content()
    }
}

/**
 * 把缩小后的视频帧拉满 + 高斯模糊。
 *
 * ## 两段模糊叠加，各司其职
 *
 * 1. **双线性放大**（`filterQuality = Low`）
 *    小图 → 大图，本身产生柔和模糊。**这是 API 26 也有效的那一层。**
 * 2. **`BlurEffect` 高斯**（API 31+，低版本自动忽略）
 *    在放大结果上再糊一次，消除放大带来的"块状感"。
 *
 * 只做第 1 步也能看，但边缘会有一点插值的规律性纹理；
 * 加上第 2 步后过渡更自然。
 *
 * ## 为什么用 `graphicsLayer` 而不是 `Modifier.blur()`
 *
 * `Modifier.blur()` 无法只作用在"底层" —— 它会把同一个 Box 的**子内容**
 * 一起糊掉（包括文字）。这里必须"只糊底、不糊字"，
 * 所以显式用 `graphicsLayer { renderEffect = ... }` 精确控制作用域。
 *
 * ## 关于 API 版本
 *
 * Compose 的 `BlurEffect` **在 Android 12 以下会被自动忽略**
 * （见 `RenderEffect.kt` 的官方注释："Attempts to use RenderEffect on
 * older Android versions will be ignored"）。
 *
 * 所以这里**不需要**写 `Build.VERSION.SDK_INT >= S` 判断 ——
 * 低版本上它是 no-op，不会崩。这一点比自己调 `android.graphics.RenderEffect`
 * 安全（后者在低版本上会抛异常）。
 */
private fun Modifier.blurredBackdrop(frame: ImageBitmap, blur: Dp): Modifier =
    this
        .drawWithCache {
            onDrawBehind { drawBackdropCover(frame) }
        }
        .graphicsLayer {
            // 低版本自动 no-op，无需版本判断
            renderEffect = androidx.compose.ui.graphics.BlurEffect(
                radiusX = blur.toPx(),
                radiusY = blur.toPx(),
                edgeTreatment = TileMode.Clamp,
            )
        }

/**
 * 按 cover 语义绘制底图（保持比例、铺满、居中裁切）。
 *
 * ## 为什么不用 `ContentScale.Crop` 的现成 API
 *
 * `drawImage` 的 `dstSize` 是拉伸到指定尺寸（会变形）。
 * 玻璃底图**变形不明显**（反正糊了），但一旦面板很扁
 * （如底部信息区），拉伸会把画面压成"横向拉丝"——
 * 那是一种很显眼的廉价感。
 *
 * 所以这里手动算 cover：等比放大到覆盖整个面板，居中，超出部分自然裁掉
 * （`clip` 已在 [GlassSurface] 里做过）。
 */
private fun DrawScope.drawBackdropCover(image: ImageBitmap) {
    if (image.width <= 0 || image.height <= 0) return
    val scale = maxOf(
        size.width / image.width.toFloat(),
        size.height / image.height.toFloat(),
    )
    val dstW = image.width * scale
    val dstH = image.height * scale
    val left = (size.width - dstW) / 2f
    val top = (size.height - dstH) / 2f

    drawImage(
        image = image,
        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(image.width, image.height),
        dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(dstW.toInt(), dstH.toInt()),
        // Low = 双线性 + 无 mipmap → 放大时柔和（这正是我们要的"模糊"）
        filterQuality = FilterQuality.Low,
    )
}

/**
 * 取位图的平均色。
 *
 * 用途：给玻璃染色，避免"灰白玻璃压在彩色画面上"显得脏。
 * 采样步长 [AVERAGE_STEP] 跳过大部分像素 —— 只需要一个大致色调。
 */
private fun averageColor(bmp: Bitmap): Color {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0
    var y = 0
    while (y < bmp.height) {
        var x = 0
        while (x < bmp.width) {
            val c = bmp.getPixel(x, y)
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            n++
            x += AVERAGE_STEP
        }
        y += AVERAGE_STEP
    }
    if (n == 0) return Color.Gray
    return Color(
        red = (r / n).toInt(),
        green = (g / n).toInt(),
        blue = (b / n).toInt(),
    )
}

/** 抓帧时的缩小倍数。8 = 约 40KB 一张，放大后天然模糊。 */
private const val FRAME_DIVISOR = 8

/**
 * 抓帧间隔（毫秒）。
 *
 * 400ms：玻璃是静态装饰，滞后不可辨；同时避免主线程频繁 getBitmap。
 */
private const val FRAME_INTERVAL_MS = 400L

/** 取平均色时的采样步长。 */
private const val AVERAGE_STEP = 4
