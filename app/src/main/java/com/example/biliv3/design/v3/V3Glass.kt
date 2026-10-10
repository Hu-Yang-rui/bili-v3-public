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
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

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

/**
 * **Haze 状态的作用域**（真背景采样 + 折射）。
 *
 * ---
 *
 * # 与 [LocalGlassBackdrop] 的分工
 *
 * 本文件现在有**两套**背景采样机制，各自适用不同场景：
 *
 * | 机制 | 来源 | 能力 | 用在哪 |
 * |---|---|---|---|
 * | [GlassBackdrop] | 播放器喂帧 | 模糊（box blur + RenderEffect） | **视频页**（画面在 TextureView 里，Compose 抓不到） |
 * | [LocalHazeState] | **Haze 库** | 模糊 + **边缘折射 + 色散** | **普通 Compose 内容**（列表、导航栏、面板） |
 *
 * ## 🔴 为什么需要两套（不能只用 Haze）
 *
 * Haze 的采样前提是"背景内容**画在同一个 Compose 图层里**"。
 * 而本项目的播放器是 `TextureView`（平台 View），
 * **Haze 抓不到它**（其文档明确写了 `SurfaceView` 无法被捕获）。
 *
 * 所以视频页继续用 [GlassBackdrop]（播放器主动喂帧），
 * 其余页面用 Haze（能拿到真折射）。
 *
 * ⚠️ **不要在同一处同时启用两者** —— 会叠加两次模糊，画面发灰。
 */
val LocalHazeState = staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/** 在子树内提供 Haze 状态（需要真折射的页面调用）。 */
@Composable
fun ProvideHazeState(
    state: dev.chrisbanes.haze.HazeState?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalHazeState provides state,
        content = content,
    )
}

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
 * ---
 *
 * # 🔴 两条渲染路径（自动选择）
 *
 * | 条件 | 路径 | 能力 |
 * |---|---|---|
 * | 子树内有 [LocalHazeState] | **Haze**（`haze-glass`） | 模糊 + **边缘折射 + 色散**（真折射） |
 * | 否则有 [GlassBackdrop] | 内置四层合成 | 模糊（视频帧采样） |
 * | 都没有 | 内置四层合成（无背景） | 半透明 + 高光边 |
 *
 * ## 为什么不是"一律用 Haze"
 *
 * Haze 要求背景内容**画在同一个 Compose 图层**里。而本项目的播放器是
 * `TextureView`（平台 View），**Haze 抓不到**（其文档明确：`SurfaceView`
 * 无法被捕获，`PreviewView` 需切 `COMPATIBLE` 模式）。
 *
 * 所以：
 * - **视频页** → 走 [GlassBackdrop]（播放器主动喂帧，Haze 做不到）
 * - **普通页面** → 走 Haze（能拿到真折射）
 *
 * ⚠️ 两者**不会**同时生效（Haze 优先），所以不会叠加两次模糊。
 *
 * ## 分层顺序（内置路径，不能错）
 *
 * ```
 * ① 背景采样（可选）  ← 只有这层被模糊
 * ② 材质底色 + 叠色
 * ③ rim 环 + specular 上下渐变
 * ④ 内容             ← 绝不进模糊
 * ```
 *
 * @param level 玻璃层级（决定模糊半径与底色强度）
 * @param shape 形状。**必须是圆角形状**（直角玻璃看起来像贴纸，且折射需要圆角）
 * @param backdrop 显式指定背景；不传则读 [LocalGlassBackdrop]
 * @param tint 覆盖底色（如播放器控制层要更暗）
 * @param colorTint 是否用背景主色调给玻璃染色
 * @param refraction 是否允许 Haze 路径做**边缘折射**。默认 true；
 *   压在密集文字上时可关掉（折射会让文字边缘发虚）
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
    /** Haze 路径是否做边缘折射（真折射）。 */
    refraction: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val m = BiliV3.colors.materials

    // ---- 帧源：两条来源都要认（见下方说明）----
    //
    // ## 🔴 这里曾经只认 v3 的 `LocalGlassBackdrop`，导致视频页玻璃是"假的"
    //
    // 本项目**同时存在两套玻璃背景机制**（历史原因）：
    //
    // | 类 | CompositionLocal | 谁在喂帧 |
    // |---|---|---|
    // | `design/Glass.kt` 的 `VideoBackdrop` | `design.LocalGlassBackdrop` | **`VideoBackdropEffect`**（真正在抓帧的那个）|
    // | `design/v3/V3Glass.kt` 的 `GlassBackdrop` | `v3.LocalGlassBackdrop` | **无人喂**（零调用点）|
    //
    // `PlayerHolder.backdrop` 的类型是**旧的** `VideoBackdrop`，
    // 而 `VideoDetailScreen` 用**旧的** `ProvideGlassBackdrop` 注入 ——
    // 但播放器浮层控件用的是**新的** `GlassSurface`，它只读 v3 的 Local。
    //
    // 结果：**帧抓到了、也确实模糊了，但没有任何 v3 玻璃读得到它**。
    // 实测（模拟器 1080×2400，logcat 确认 `backdrop: 首帧抓取成功 (135x84)`）：
    // 播放器中央播放钮的玻璃**没有背景采样**，只是"半透明色块压着视频"——
    // 也就是旧系统批评的那种"假玻璃"。
    //
    // ## 为什么用"双读"而不是把两套合并
    //
    // 合并是**正确**的终局，但旧 `VideoBackdrop` 还被
    // `design/BiliCard.kt` 的 `biliCard()` 消费者（尚未迁移的页面）依赖，
    // 一次删掉会连带改十几个文件。这里先做**低风险的桥接**：
    // v3 优先，读不到就回退到旧的那一份。
    //
    // ⚠️ 两者**不会**同时有值（旧路径喂的是 `VideoBackdrop`，
    //    v3 路径目前无人喂），所以不存在"叠加两次模糊"。
    //    TODO：全部页面迁到 v3 后，删掉旧 `VideoBackdrop` 与这条回退。
    val v3Backdrop = backdrop
    val legacy = com.example.biliv3.design.LocalGlassBackdrop.current
    val frame = v3Backdrop?.frame ?: legacy?.frame
    val dominant = v3Backdrop?.dominantColor ?: legacy?.dominantColor

    // ---- Haze 路径（有 state 时优先）----
    val hazeState = LocalHazeState.current
    if (hazeState != null) {
        HazeGlassSurface(
            modifier = modifier,
            shape = shape,
            level = level,
            hazeState = hazeState,
            tint = tint,
            rim = rim,
            specular = specular,
            refraction = refraction,
            content = content,
        )
        return
    }

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
                        // 🔴 必须用**一条**三段式渐变铺满整个高度。
                        //
                        // ## 这里曾经画出一条"底栏黑线"（真实事故，已实测证明）
                        //
                        // 旧实现是两个半高矩形、各配一条渐变：
                        //
                        // ```
                        // 上半：specularTop → Transparent，startY=0, endY=size.height  ← 全高！
                        // 下半：Transparent → specularBottom，startY=0, endY=size.height  ← 全高！
                        // ```
                        //
                        // ⚠️ `endY` 写的是**整个玻璃的高度**，而矩形只有**一半**高。
                        // 于是渐变在中点被"截断"——两半各停在一半透明度上：
                        //
                        // - 接缝上方 = `specularTop × 0.5`（半透明白）
                        // - 接缝下方 = `specularBottom × 0.5`（半透明黑）
                        //
                        // 结果是一条**清晰、纯黑、机械、2px 的横向黑线**，
                        // 正好落在玻璃托板的 50% 高度处。
                        //
                        // 模拟器实测（1080×2400 / density 2.625，底部导航托板
                        // y 2174–2337 → 中点 2255.5）：
                        //
                        // ```
                        // y=2254  row-mean luma = 88.86
                        // y=2256  row-mean luma = 65.67   ← 2px 内骤降 23.2
                        // ```
                        //
                        // 判据：**"渐变被画进一个比它自己小的矩形"** ——
                        // 只要 `startY/endY` 与 `drawRect(size=)` 不是同一个矩形，
                        // 就一定会出现硬边。修法不是调透明度，而是让两者一致。
                        //
                        // 现在改成一条连续渐变（0 → 0.5 → 1），
                        // 接缝处两侧**都恰好是 0 alpha**，几何上不可能再有硬边。
                        val edge = Brush.verticalGradient(
                            0f to m.specularTop,
                            0.5f to Color.Transparent,
                            1f to m.specularBottom,
                        )
                        onDrawBehind {
                            drawRect(brush = edge)
                        }
                    },
            )
        }

        // ---- ④ 内容（在独立层之上，绝不参与模糊）----
        content()
    }
}

/**
 * **玻璃表面的 `Modifier` 形式** —— 给"已经画好的面板"换材质。
 *
 * ---
 *
 * ## 🔴 为什么需要这个（而不是都用 [GlassSurface]）
 *
 * [GlassSurface] 是**容器**：它把内容包进 `Box` 里，所以调用方要改
 * **一对花括号**。对于几百行深的 Compose 树，那意味着
 * 「在 A 行加一个 `{`、在 700 行外找对应的 `}`」——
 *
 * > 实测代价：`PlayerSettingsSheet.kt` 与 `FavFolderSheet.kt`
 * > **各改坏两次**，编译器报错行号离真正的编辑点 700 行，
 * > 完全看不出问题在哪。
 *
 * 而"换材质"这件事本身**只需要一个 `Modifier`**：
 * 面板的 `Column` 已经在了，把它的
 * `.clip(...)` + `.background(...)` 换成 `.v3GlassSurface(...)` 即可 ——
 * **一个表达式替换，不动任何括号**。
 *
 * ## 语义与 [GlassSurface] 一致
 *
 * 同样的两条路径（Haze 优先、否则内置四层合成）、同样的
 * [GlassBackdrop] 回退读取（含旧 `VideoBackdrop`，见该函数的说明）。
 *
 * ⚠️ **区别**：`Modifier` 形式**只画材质、不含内容层**。
 * 内容由调用方自己的 `Column`/`Row` 承担 —— 这正好是我们要的：
 * 文字永远不会进模糊层。
 *
 * ## 用法
 *
 * ```kotlin
 * Column(
 *     modifier = Modifier
 *         .fillMaxWidth()
 *         .v3GlassSurface(
 *             shape = RoundedCornerShape(V3Radius.sheet),
 *             level = V3Glass.Level.UltraThin,
 *         ),
 * ) { ... }
 * ```
 *
 * @param shape 形状。必须是圆角形状（直角玻璃看起来像贴纸）。
 * @param level 玻璃层级（决定模糊半径与底色强度）。
 * @param backdrop 背景帧源；不传则读 [LocalGlassBackdrop]（含旧实现回退）。
 */
@Composable
fun Modifier.v3GlassSurface(
    shape: Shape = RoundedCornerShape(V3Radius.sheet),
    level: V3Glass.Level = V3Glass.Level.Regular,
    tint: Color? = null,
): Modifier {
    val m = BiliV3.colors.materials

    // 与 GlassSurface 相同的双读逻辑（见那边的长说明）
    val v3Backdrop = LocalGlassBackdrop.current
    val legacy = com.example.biliv3.design.LocalGlassBackdrop.current
    val frame = v3Backdrop?.frame ?: legacy?.frame
    val dominant = v3Backdrop?.dominantColor ?: legacy?.dominantColor

    val blurDp = level.blur * m.blurScale()

    val baseTint = remember(tint, m, level, dominant) {
        val t = tint ?: m.tint
        val scaled = t.copy(alpha = (t.alpha * level.tintScale).coerceIn(0f, 1f))
        scaled
    }

    val hazeState = LocalHazeState.current

    return this
        // Haze 路径：真折射（与 GlassSurface 的参数映射一致）
        .then(
            if (hazeState != null) {
                val rounded = shape as? RoundedCornerShape ?: RoundedCornerShape(V3Radius.sheet)
                val style = remember(level, rounded, tint, m) {
                    val base = when (level) {
                        V3Glass.Level.Clear -> dev.chrisbanes.haze.glass.GlassStyle.clear
                        else -> dev.chrisbanes.haze.glass.GlassStyle.regular
                    }
                    base.then {
                        shape(rounded)
                        tint(tint ?: m.tint)
                    }
                }
                Modifier.hazeGlass(
                    input = dev.chrisbanes.haze.HazeInput.Backdrop(hazeState),
                    style = style,
                )
            } else {
                Modifier
            },
        )
        // 内置路径：四层合成的"材质部分"（背景采样 + 底色 + rim + specular）
        .then(
            if (hazeState != null) {
                Modifier
            } else {
                Modifier
                    .clip(shape)
                    .drawWithCache {
                        val edge = Brush.verticalGradient(
                            0f to m.specularTop,
                            0.5f to Color.Transparent,
                            1f to m.specularBottom,
                        )
                        onDrawWithContent {
                            // ① 背景采样（可选）
                            if (frame != null) {
                                drawBackdropCover(frame)
                            }
                            // ② 材质底色
                            drawRect(color = baseTint)
                            drawRect(color = m.overlay)
                            // ④ 内容（调用方的 Column 画在这之上，绝不进模糊）
                            drawContent()
                            // ③ rim + specular（画在内容之上，只影响边缘观感）
                            drawRect(
                                color = m.rim,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.dp.toPx(),
                                ),
                            )
                            drawRect(brush = edge)
                        }
                    }
            },
        )
}

/**
 * **Haze 路径的玻璃表面**（真折射）。
 *
 * ---
 *
 * # 参数映射：本项目令牌 → Haze `GlassStyle`
 *
 * Haze 的 `GlassStyle.regular` / `.clear` 已经按 iOS 27 校准过
 * （其文档：`Edge(28.dp)` 折射带、强度 0.85、位移 56dp）。
 * 所以这里**不重新发明光学参数**，只把本项目的"层级"映射到它的两个预设：
 *
 * | 本项目层级 | Haze 预设 | 理由 |
 * |---|---|---|
 * | [V3Glass.Level.Clear] | `GlassStyle.clear` | 两者都是"压在媒体上、要保留背景可见性" |
 * | 其余（Regular/Thin/UltraThin） | `GlassStyle.regular` | 默认材质，文字可读性优先 |
 *
 * ## 为什么不做更细的映射
 *
 * 我们的 4 个层级与 Haze 的 2 个预设**不是一对一**（我们的分层依据是
 * "面积大小"，Haze 的依据是"背景对比度"）。强行细分会造出两套都解释不通的中间态。
 * 所以只保留"要更透 → clear"这一个语义区分。
 *
 * ⚠️ Haze 自己会按 `HazePerformanceMode.Default` 选渲染档，
 * 低端设备/旧 API 会自动降级（无折射），**不需要我们写 API 判断**。
 */
@Composable
private fun HazeGlassSurface(
    modifier: Modifier,
    shape: Shape,
    level: V3Glass.Level,
    hazeState: dev.chrisbanes.haze.HazeState,
    tint: Color?,
    rim: Boolean,
    specular: Boolean,
    refraction: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val m = BiliV3.colors.materials

    // 折射需要圆角形状 —— 直角会让折射带无处可放
    val rounded = shape as? RoundedCornerShape ?: RoundedCornerShape(V3Radius.sheet)

    val style = remember(level, rounded, tint, m, refraction) {
        val base = when (level) {
            V3Glass.Level.Clear -> dev.chrisbanes.haze.glass.GlassStyle.clear
            else -> dev.chrisbanes.haze.glass.GlassStyle.regular
        }
        base.then {
            shape(rounded)
            // 底色：用本项目令牌（Haze 默认的容器色偏亮）
            tint(tint ?: m.tint)
            if (!refraction) {
                // 关掉折射：把折射位移归零，保留模糊与高光
                optics(refractionStrength = 0f)
            }
        }
    }

    Box(
        modifier = modifier
            .hazeGlass(
                input = dev.chrisbanes.haze.HazeInput.Backdrop(hazeState),
                style = style,
            ),
    ) {
        // Haze 自带 specular / rim / 边缘阴影（它的 clear 预设就是靠这些成立的），
        // 所以这里**不再叠我们自己的高光层** —— 叠加会变成"两层光"，很脏。
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
