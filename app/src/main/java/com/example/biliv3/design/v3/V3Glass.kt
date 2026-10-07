package com.example.biliv3.design.v3

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * **Liquid Glass 材质引擎**（Compose 原生实现）。
 *
 * ---
 *
 * # 🔴 先讲清楚 Android 上能做到什么、做不到什么
 *
 * 参考仓库 `BarredEwe/LiquidGlass` 是 **SwiftUI + Metal** 实现，管线是：
 *
 * ```
 * ① HierarchySnapshotCapturer  抓取玻璃层**背后**的视图层级 → CGImage
 * ② BackgroundTextureProvider  转成 MTLTexture + GPU 模糊
 * ③ MetalShaderView            自定义 fragment shader（模糊 + 折射 + 高光）
 * ④ 更新管理                    continuous / once / manual 三种刷新模式
 * ```
 *
 * ## Compose 侧的三个硬约束
 *
 * | 约束 | 后果 |
 * |---|---|
 * | **没有"抓背后视图"的公开 API** | 不能像 Metal 那样采样真实背景像素 |
 * | **`Modifier.blur()` 糊的是自己的子树** | 挂在玻璃上会把**文字一起糊掉** |
 * | `graphicsLayer{renderEffect}` 同样作用于**该层及其子树** | 同上 |
 *
 * 所以"真·折射 + 真·背景采样"在纯 Compose 里**做不出来**
 * （要 `PixelCopy` / 自定义 RenderNode，代价与风险都很大）。
 *
 * ---
 *
 * # 本项目的实现：四层合成（诚实的近似）
 *
 * ```
 * ┌─ 玻璃容器（clip 到 shape）────────────────────┐
 * │ ① 背景采样层   ← 由调用方提供（见 GlassBackdrop）│
 * │ ② 材质层       底色 + 叠色                      │
 * │ ③ 边缘层       rim 环 + specular 上下渐变        │
 * │ ④ 内容层       ← 绝不参与任何模糊                │
 * └───────────────────────────────────────────────┘
 * ```
 *
 * ## ① 背景采样层的两个来源
 *
 * | 来源 | 何时用 | 效果 |
 * |---|---|---|
 * | [GlassBackdrop]（调用方喂帧） | 播放器 / 视频页 | **真背景模糊** |
 * | 无（null） | 静态页（首页 / 设置 / 列表） | 半透明底 + 高光边 |
 *
 * ⚠️ **静态页的"降级"不是缺陷** —— 静态页背后是**纯色底**，
 * 模糊纯色仍然是纯色。所以"半透明 + 高光边"就是它**正确**的形态。
 *
 * 旧系统（`design/Glass.kt`）也得出过同一结论，但本项目**换掉了它的抓帧方式**：
 * 旧实现用 `TextureView.getBitmap()` 每 400ms 轮询（主线程），
 * 成本高且只对播放器有效。新实现改为**播放器主动喂帧**，
 * 只在帧真正变化时推送。
 *
 * ## ② 折射（refraction）
 *
 * iOS 27 实测值是 `refraction: 100`（lens distortion amount），
 * 但那是 Figma 的 GLASS effect 参数，**无法在 Compose 里表达**
 * （需要逐像素重映射 UV）。
 *
 * 本项目的替代：用**上下渐变的高光边**模拟"光在边缘被弯折"的观感
 * （对应实测的 `innerShadow` 在 ±40 偏移各一次）。
 *
 * ⚠️ **这是视觉近似，不是真折射。如实标注，不假装做到了。**
 *
 * ---
 *
 * # 性能
 *
 * 参考仓库自己的建议（可直接照用）：静态 UI 用 `.once`、
 * 动画背景用 `.continuous(0.05)`、**避免大量玻璃同时存在**。
 *
 * 本项目对应：
 * - 静态页玻璃**零采样**（没有背景层，不产生 GPU 工作）
 * - 播放器玻璃**只在帧变化时**更新
 * - 模糊在**小图上**做（抓帧时已缩到 1/8），一次算完所有玻璃共用
 */
object V3Glass {

    /**
     * 玻璃层级（对应 iOS 27 的四个变体）。
     *
     * 模糊半径取**实测值**（不是"看着像玻璃"的大半径）——
     * 大半径会糊掉底下内容，反而失去"透出背景"的意义。
     */
    enum class Level(
        /** 模糊半径（dp）。 */
        val blur: Dp,
        /** 底色透明度乘数。 */
        val tintScale: Float,
    ) {
        /** 大面板（Sheet 主体、全屏浮层）—— 面积大，要最清。 */
        UltraThin(3.6f.dp, 1.0f),

        /** 中等控件（播放器控制条、浮动按钮）。 */
        Thin(6f.dp, 0.95f),

        /** **默认**：底部导航、播放器主控制层。 */
        Regular(6f.dp, 1.0f),

        /**
         * 压在**高对比媒体**上（画面之上的小控件）。
         *
         * ⚠️ 实测 clear 的基础色近乎不透明（`#101010 @1.0`）——
         * 这看着反直觉，但正是它"在高对比画面上仍可读"的原因。
         */
        Clear(1.6f.dp, 1.6f),
    }
}

/**
 * 玻璃的**背景帧提供者**。
 *
 * ## 为什么用"调用方喂帧"而不是"玻璃自己去抓"
 *
 * 玻璃在树的深处（导航栏、控制条、Sheet），而**帧只有播放器知道**。
 * 让玻璃自己去抓意味着它要依赖 `TextureView`，且每块玻璃各抓一次（N 倍成本）。
 *
 * 改为：**播放器抓一次 → 写进这里 → 所有玻璃读同一份**。
 *
 * ## 帧是**预先模糊好**的
 *
 * `frame` 里的位图在抓取时就已经做过 box blur。这样玻璃只需 `drawImage`，
 * 不碰子树，**文字永远不会被糊** —— `graphicsLayer{renderEffect}`
 * 会把子树一起糊，这是旧系统踩过的真实 bug。
 *
 * ## 线程
 *
 * `frame` 只在主线程写（`getBitmap` 的线程要求）。
 */
class GlassBackdrop {
    /** 当前帧（已缩小且已模糊）。null = 无背景可采样。 */
    var frame by mutableStateOf<ImageBitmap?>(null)

    /** 主色调（给玻璃染色，避免灰玻璃压彩色画面发脏）。 */
    var dominantColor by mutableStateOf<Color?>(null)

    /** 背景是否可用（播放器已起播且有画面）。 */
    val available: Boolean get() = frame != null
}

/** 玻璃背景的作用域。null = 静态页（无背景可采样）。 */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** 在子树内提供玻璃背景（有视频的页面调用）。 */
@Composable
fun ProvideGlassBackdrop(
    backdrop: GlassBackdrop?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        content = content,
    )
}

/**
 * **玻璃表面** —— 本项目所有浮动层的统一材质。
 *
 * ## 用法
 *
 * ```kotlin
 * GlassSurface(shape = RoundedCornerShape(V3Radius.sheet)) {
 *     Text("内容")   // ← 不会被模糊
 * }
 * ```
 *
 * ## 分层顺序（不能错）
 *
 * ```
 * ① 背景采样（可选）  ← 只有这层被模糊
 * ② 材质底色 + 叠色
 * ③ rim 环 + specular 上下渐变
 * ④ 内容             ← 绝不进模糊
 * ```
 *
 * @param level 玻璃层级（决定模糊半径与底色强度）
 * @param shape 形状。**必须是圆角形状**（直角玻璃看起来像贴纸）
 * @param backdrop 显式指定背景；不传则读 [LocalGlassBackdrop]
 * @param tint 覆盖底色（如播放器控制层要更暗）
 * @param colorTint 是否用背景主色调给玻璃染色
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(V3Radius.sheet),
    level: V3Glass.Level = V3Glass.Level.Regular,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    tint: Color? = null,
    /** 是否画边缘环（实测：一圈浅灰，不是深色内描边）。 */
    rim: Boolean = true,
    /** 是否画上下高光。 */
    specular: Boolean = true,
    /** 是否用背景主色调染色。 */
    colorTint: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val m = BiliV3.colors.materials
    val frame = backdrop?.frame
    val dominant = backdrop?.dominantColor

    // 模糊半径按透明度滑杆缩放（低端设备可调高 = 更省 GPU）
    val blurDp = level.blur * m.blurScale()

    // ---- 底色 ----
    val baseTint = remember(tint, m, level, dominant, colorTint) {
        val t = tint ?: m.tint
        val scaled = t.copy(alpha = (t.alpha * level.tintScale).coerceIn(0f, 1f))
        // 压在彩色画面上时混一点主色调 —— 避免"灰玻璃压彩色"发脏
        if (colorTint && dominant != null) blend(scaled, dominant, 0.12f) else scaled
    }

    Box(modifier = modifier.clip(shape)) {
        // ---- ① 背景采样层 ----
        //
        // ⚠️ 独立子 Box：模糊只作用在它身上。
        //    绝不能把 renderEffect 挂到外层 Box —— 那会把内容（文字）一起糊掉。
        //    这是旧系统 design/Glass.kt 记录过的真实 bug。
        if (frame != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .backdropLayer(frame = frame, blur = blurDp),
            )
        }

        // ---- ② 材质底色 + 叠色 ----
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(baseTint)
                .background(m.overlay),
        )

        // ---- ③ rim 环 + specular ----
        if (rim) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(width = 1.dp, color = m.rim, shape = shape),
            )
        }
        if (specular) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val top = Brush.verticalGradient(
                            colors = listOf(m.specularTop, Color.Transparent),
                            startY = 0f,
                            endY = size.height,
                        )
                        val bottom = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, m.specularBottom),
                            startY = 0f,
                            endY = size.height,
                        )
                        onDrawBehind {
                            // 上亮（光从上来）—— 只铺上半，避免整体提亮成灰雾
                            drawRect(
                                brush = top,
                                size = Size(size.width, size.height * 0.5f),
                            )
                            // 下暗（对应实测 innerShadow 的下半条）
                            drawRect(
                                brush = bottom,
                                topLeft = Offset(0f, size.height * 0.5f),
                                size = Size(size.width, size.height * 0.5f),
                            )
                        }
                    },
            )
        }

        // ---- ④ 内容（在独立层之上，绝不参与模糊）----
        content()
    }
}

/**
 * 背景帧层：按 cover 语义绘制 + 高斯模糊。
 *
 * ## 为什么模糊分两处做
 *
 * - **抓帧时**：box blur（软件、低成本、所有 API 可用）
 * - **这里**：`RenderEffect` 高斯（硬件、更细腻、**API 31+**）
 *
 * 两者叠加 = 低版本也有模糊（box blur 的结果），高版本更细腻。
 * **低版本上 `RenderEffect` 会被 Compose 自动忽略**（官方行为），
 * 所以不需要写 `Build.VERSION.SDK_INT` 判断 —— 那反而更容易出错
 * （自己调 `android.graphics.RenderEffect` 在低版本会抛异常）。
 */
private fun Modifier.backdropLayer(frame: ImageBitmap, blur: Dp): Modifier = this
    .drawWithCache {
        onDrawBehind { drawBackdropCover(frame) }
    }
    .graphicsLayer {
        renderEffect = BlurEffect(
            radiusX = blur.toPx(),
            radiusY = blur.toPx(),
            edgeTreatment = TileMode.Clamp,
        )
    }

/**
 * 按 **cover** 语义绘制背景（保持比例、铺满、居中裁切）。
 *
 * ⚠️ 不能用 `drawImage(dstSize = 面板大小)` —— 那是拉伸（会变形）。
 * 玻璃面板常是扁的（控制条、导航栏），拉伸会把画面压成横向拉丝。
 */
private fun DrawScope.drawBackdropCover(image: ImageBitmap) {
    if (image.width <= 0 || image.height <= 0) return
    val scale = maxOf(
        size.width / image.width.toFloat(),
        size.height / image.height.toFloat(),
    )
    val dstW = image.width * scale
    val dstH = image.height * scale
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(
            ((size.width - dstW) / 2f).toInt(),
            ((size.height - dstH) / 2f).toInt(),
        ),
        dstSize = IntSize(dstW.toInt(), dstH.toInt()),
        // Low = 双线性 + 无 mipmap → 放大时柔和（这正是"模糊"的一部分）
        filterQuality = FilterQuality.Low,
    )
}

/** 两个颜色按比例混合（给玻璃染色用）。 */
private fun blend(base: Color, accent: Color, ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return Color(
        red = base.red * (1 - r) + accent.red * r,
        green = base.green * (1 - r) + accent.green * r,
        blue = base.blue * (1 - r) + accent.blue * r,
        alpha = base.alpha,
    )
}
