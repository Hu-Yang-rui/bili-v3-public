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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.GlassSurface
import com.example.biliv3.design.v3.ProvideGlassBackdrop
import com.example.biliv3.design.v3.V3Radius

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
     * 深色主题下**压在视频上**的玻璃底色。
     *
     * ## ⚠️ 为什么是 **72% 黑**，而不是"淡白玻璃"
     *
     * 第一版用了 12% 白（`0x1FFFFFFF`），实测**文字完全不可读** ——
     * 白色文字压在浅色视频画面（白 T 恤 / 雪景 / 白墙）上直接消失。
     *
     * 这是玻璃风格的经典陷阱：
     * **透明度必须由"最坏情况"决定，而不是由"好看的截图"决定。**
     *
     * 第二版用 55% 黑，文字可读了，但**底下的画面轮廓仍太清楚**
     * （实测：UP 卡里能明显看出人物的头肩形状），
     * 视觉上"脏"，且干扰卡片内的文字阅读。
     *
     * 72% 黑是最终值：
     * - 底下的色彩仍然透出来（玻璃感保留，这是它和"纯色块"的区别）
     * - 但轮廓已经糊到不构成干扰
     * - 白字对比度 ≈ 7:1（远超 WCAG AA）
     *
     * ⚠️ **只用于压在视频上的表面**。静态页面用 [surfaceTintDark]。
     */
    val tintDark = Color(0xB8000000)   // 黑 72%

    /**
     * 浅色主题下**压在视频上**的玻璃底色。
     *
     * ## 🔴 为什么不能是"白玻璃"
     *
     * 第一版用了 **40% 白**（`0x66FFFFFF`），实测**完全没有玻璃感** ——
     * 白色半透明压在**亮色视频画面**（雪景、白墙、天空）上，
     * 结果就是"白玻璃压白画面"，看起来是**一块纯白实心板**，
     * 底下什么都透不出来（截图实测：浅色下右上角按钮组像白色贴纸）。
     *
     * 深色下之所以没问题，是因为 72% 黑压亮画面对比强烈。
     * **浅色是反过来的问题** —— 必须让玻璃比画面**更暗**，
     * 才能"透出"底下的内容。
     *
     * ## 现在的值：黑 32%
     *
     * - 底下的画面清晰透出（这才是玻璃）
     * - 文字用深色（浅色主题的文字色），压在 32% 黑 + 模糊上对比度足够
     * - 与深色的 72% 黑形成"同一策略、不同强度"的对称
     *
     * 一句话：**玻璃的染色方向必须与"底下内容的亮度"相反** ——
     * 底下亮就压暗，底下暗就提亮。
     */
    val tintLight = Color(0x52000000)  // 黑 32%

    /**
     * 深色主题下**静态页面**（首页/搜索/我的/消息）的玻璃底。
     *
     * ## 🔴 为什么必须和 [tintDark] 分开
     *
     * 实测：把 55% 黑玻璃用在静态页上，卡片会**几乎看不见** ——
     * 页面底是 `#121114`，55% 黑压上去比底色还暗，
     * 视觉上像"在深色页面上挖了几个黑洞"，只剩一圈描边。
     *
     * 根本原因：**静态页背后没有东西可模糊**（底色是纯色）。
     * 玻璃拟态在纯色背景上的"立体感"必须靠**比底色更亮**来实现 ——
     * 这是它与"压在视频上"（靠压暗保证可读）完全相反的策略。
     *
     * 用 12% 白：卡片比页面底亮一档，浮起来，且不刺眼。
     * 配合 20% 白高光边，就是标准 glassmorphism 观感。
     */
    val surfaceTintDark = Color(0x1FFFFFFF)   // 白 12%

    /**
     * 浅色主题下静态页面的玻璃底。
     *
     * 页面底是 `#F5F7FA`（近白），卡片要比它更白才能浮起来。
     */
    val surfaceTintLight = Color(0xB3FFFFFF)   // 白 70%

    /**
     * 顶部高光边（玻璃的"厚度感"来源）。
     *
     * ⚠️ 浅色下**不能再用白边** —— 白边压白玻璃等于没边（实测完全看不见）。
     * 浅色玻璃压的是**视频画面**（明暗不定），所以边要能"双向可见"：
     * 用**半透明黑**，在亮画面上是暗边、在暗画面上也能靠模糊底衬托。
     */
    val borderDark = Color(0x33FFFFFF)  // 白 20%
    val borderLight = Color(0x33000000) // 黑 20%

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
 * 玻璃面板在**树的深处**（卡片、控制条、底部信息区），而帧来自**播放器 View**。
 * 层层传参会污染一路的签名。用一个共享 holder，
 * 面板只依赖它、不依赖播放器的存在。
 *
 * ## ⚠️ 帧是**预先模糊好**的
 *
 * `frame` 里的位图在**抓帧时就已经做过 box blur**，不是原始画面。
 *
 * 这样做有三个好处：
 * 1. **卡片可以纯用 `drawBehind` 画它** —— 不碰子树，文字永远不会被糊
 *    （`graphicsLayer{renderEffect}` 会把子树一起糊，这是踩过的坑）
 * 2. 模糊只算**一次**（在 1/8 小图上，约 4 万像素），所有卡片共用
 * 3. 不依赖 API 31（`RenderEffect` 在低版本无效）
 *
 * ## 线程
 *
 * `frame` 只在主线程写（`getBitmap` 需在 UI 线程调用）。
 */
class VideoBackdrop {
    /** 当前帧（已缩小 **且已模糊**）。null = 还没抓到 / 无视频。 */
    var frame by mutableStateOf<ImageBitmap?>(null)

    /** 当前帧的主色调，用于玻璃染色（避免灰白玻璃压彩色画面发脏）。 */
    var dominantColor by mutableStateOf<Color?>(null)
}

/**
 * 玻璃底图的作用域。
 *
 * ## 为什么需要它（这是"全量玻璃"的关键）
 *
 * `biliCard()` 是**纯 Modifier**，它无法自己去问"现在有没有视频帧"。
 * 用 CompositionLocal 把 [VideoBackdrop] 注入下去后：
 *
 * - **视频页**：`ProvideGlassBackdrop(holder.backdrop) { ... }` 包一层
 *   → 里面所有卡片自动变成**真毛玻璃**（糊的是视频画面）
 * - **静态页**：不提供 → 卡片退化为**半透明 + 高光边**
 *   （静态页背后是纯色底，模糊纯色仍是纯色，所以这是正确形态，不是降级）
 *
 * 一句话：**同一份 `biliCard()` 代码，在有视频的地方是真玻璃，
 * 在没视频的地方是标准玻璃拟态。**
 */
val LocalGlassBackdrop = androidx.compose.runtime.staticCompositionLocalOf<VideoBackdrop?> {
    null
}

/** 在子树内启用玻璃底图（有视频的页面调用）。 */
@Composable
fun ProvideGlassBackdrop(
    backdrop: VideoBackdrop?,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        content = content,
    )
}

/**
 * 当前是否处于玻璃态（用于让组件按需调整对比度）。
 *
 * 实际上只要提供了 backdrop 或有玻璃令牌就算。这里返回恒定 true ——
 * 因为**全量替换后整个 App 都是玻璃**，这个查询留给将来可能的"关闭玻璃"开关。
 */
val isGlassEnabled: Boolean get() = true

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
        // 只在首次成功时打一条日志（避免 400ms 刷屏）
        var firstFrame = true
        while (true) {
            val tv = textureProvider()
            // 诊断：抓帧失败时**必须能查到原因**（否则玻璃静默不生效）
            if (tv == null) {
                android.util.Log.d(TAG, "backdrop: textureView 为 null（播放器未创建？）")
            } else if (!tv.isAvailable) {
                android.util.Log.d(TAG, "backdrop: TextureView 未就绪（surface 还没建好）")
            } else if (tv.width <= 0 || tv.height <= 0) {
                android.util.Log.d(TAG, "backdrop: TextureView 尺寸为 0 (${tv.width}x${tv.height})")
            }
            if (tv != null && tv.isAvailable && tv.width > 0 && tv.height > 0) {
                val w = (tv.width / FRAME_DIVISOR).coerceAtLeast(1)
                val h = (tv.height / FRAME_DIVISOR).coerceAtLeast(1)
                // getBitmap 必须在主线程（TextureView 的线程约束）
                val bmp: Bitmap? = runCatching { tv.getBitmap(w, h) }.getOrNull()
                if (bmp == null) {
                    // 拿到 view 但取不到位图 —— 通常是 surface 已销毁或尺寸竞态
                    android.util.Log.d(TAG, "backdrop: getBitmap 返回 null (${w}x$h)")
                } else {
                    // ⚠️ 在**这里**就模糊好（1/8 小图上做 box blur，约 4 万像素）
                    //
                    // 为什么不在卡片上做：
                    // 卡片是 Modifier，用 `graphicsLayer{renderEffect}` 会把
                    // **子树（文字）一起糊掉** —— 这是踩过的坑。
                    // 在抓帧时预先模糊，卡片就能纯 `drawBehind` 画出来，永不碰文字。
                    //
                    // 顺带好处：只算一次，所有卡片共用；且不依赖 API 31。
                    val blurred = runCatching { boxBlur(bmp, BLUR_PASSES) }.getOrDefault(bmp)
                    backdrop.frame = blurred.asImageBitmap()
                    backdrop.dominantColor = runCatching {
                        averageColor(blurred)
                    }.getOrNull()
                    // 只在**首次**成功时打一条，避免刷屏
                    if (firstFrame) {
                        firstFrame = false
                        android.util.Log.i(TAG, "backdrop: 首帧抓取成功 (${w}x$h)")
                    }
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
 *     shape = RoundedCornerShape(V3Radius.md),
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
    shape: RoundedCornerShape = RoundedCornerShape(V3Radius.md),
    blur: Dp = GlassTokens.blurMedium,
    /** 底色。默认按主题取。传 `Color.Transparent` 可关掉染色。 */
    tint: Color? = null,
    /** 是否画顶部高光边。 */
    border: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = BiliV3.colors
    // ⚠️ 本组件**专用于"压在视频上"**，所以染色一律压暗。
    //
    // 视频画面的亮度与主题无关，必须压暗才能既"透出"又保证白字可读。
    // 描边同理用白高光边（玻璃底已压暗，白边才有"厚度感"）。
    val glassTint = tint ?: GlassTokens.tintDark
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
                            // 玻璃底一律压暗 → 一律白高光边（与主题无关）
                            color = GlassTokens.borderDark,
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

/** 日志 TAG（排查"玻璃不生效"时用）。 */
private const val TAG = "BiliGlass"

/** 抓帧时的缩小倍数。8 = 约 40KB 一张，放大后天然模糊。 */
private const val FRAME_DIVISOR = 8

/**
 * box blur 的迭代次数。
 *
 * ## 为什么用 box blur 而不是高斯
 *
 * 真正的 `RenderEffect` 高斯需要 API 31，且作用域会连子树一起糊。
 * 这里是**在 1/8 小图上做软件 box blur**：
 * - 3 次 box blur ≈ 一次高斯（中心极限定理），视觉上无差别
 * - 4 万像素 × 3 遍 = 12 万次运算，亚毫秒级
 * - 任何 API 版本都能跑
 *
 * 3 遍之后配合"放大回全屏"的双线性插值，效果已经足够柔和。
 */
private const val BLUR_PASSES = 3

/**
 * 软件 box blur（就地生成新位图）。
 *
 * 实现：横向 + 纵向各做一遍滑动窗口平均，重复 [passes] 次。
 * 用 `IntArray` 直接读写像素，避免 `getPixel/setPixel` 的 JNI 开销。
 */
private fun boxBlur(src: Bitmap, passes: Int): Bitmap {
    val w = src.width
    val h = src.height
    if (w < 2 || h < 2) return src

    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)

    val tmp = IntArray(w * h)
    repeat(passes) {
        horizontalBlur(pixels, tmp, w, h)
        verticalBlur(tmp, pixels, w, h)
    }

    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

/** 横向一维 box blur（半径 1，即 3 像素窗口）。 */
private fun horizontalBlur(src: IntArray, dst: IntArray, w: Int, h: Int) {
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val l = if (x > 0) src[row + x - 1] else src[row + x]
            val c = src[row + x]
            val r = if (x < w - 1) src[row + x + 1] else src[row + x]
            dst[row + x] = avg3(l, c, r)
        }
    }
}

/** 纵向一维 box blur。 */
private fun verticalBlur(src: IntArray, dst: IntArray, w: Int, h: Int) {
    for (x in 0 until w) {
        for (y in 0 until h) {
            val t = if (y > 0) src[(y - 1) * w + x] else src[y * w + x]
            val c = src[y * w + x]
            val b = if (y < h - 1) src[(y + 1) * w + x] else src[y * w + x]
            dst[y * w + x] = avg3(t, c, b)
        }
    }
}

/**
 * 三个 ARGB 像素求平均。
 *
 * 逐通道算，**不能**把整个 int 相加再除 —— 那样高位 alpha 会污染低位色值。
 */
private fun avg3(a: Int, b: Int, c: Int): Int {
    val aA = (a ushr 24) and 0xFF
    val aR = (a ushr 16) and 0xFF
    val aG = (a ushr 8) and 0xFF
    val aB = a and 0xFF

    val bA = (b ushr 24) and 0xFF
    val bR = (b ushr 16) and 0xFF
    val bG = (b ushr 8) and 0xFF
    val bB = b and 0xFF

    val cA = (c ushr 24) and 0xFF
    val cR = (c ushr 16) and 0xFF
    val cG = (c ushr 8) and 0xFF
    val cB = c and 0xFF

    return (((aA + bA + cA) / 3) shl 24) or
        (((aR + bR + cR) / 3) shl 16) or
        (((aG + bG + cG) / 3) shl 8) or
        ((aB + bB + cB) / 3)
}

/**
 * 抓帧间隔（毫秒）。
 *
 * 400ms：玻璃是静态装饰，滞后不可辨；同时避免主线程频繁 getBitmap。
 */
private const val FRAME_INTERVAL_MS = 400L

/** 取平均色时的采样步长。 */
private const val AVERAGE_STEP = 4
