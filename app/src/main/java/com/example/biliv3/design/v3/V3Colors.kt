package com.example.biliv3.design.v3

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **BiliV3 设计系统 v3 —— 颜色令牌**（全量 UI 重构）。
 *
 * ---
 *
 * # 来源
 *
 * 本套令牌**重新设计**，不再沿用旧 `design/tokens/Colors.kt` 的视觉参数。
 * 参考（**只取设计语言与数值规格，不搬源码**）：
 *
 * | 参考 | 取什么 |
 * |---|---|
 * | `seunghan91/ios27-design-system`（MIT） | 语义色分层、背景层级、标签四级、填充四级、分隔线、玻璃材质数值 |
 * | `BarredEwe/LiquidGlass`（MIT，Swift/Metal） | **材质原理**：抓背景 → 模糊 → 折射 → 边缘高光 + 内外阴影 |
 *
 * ⚠️ 那两个仓库分别是 **Web(CSS)** 与 **SwiftUI/Metal** 实现，
 * 本项目是 **Android + Jetpack Compose** —— 所以只借**设计语言**，
 * 落地手段全部按 Compose 重写（见 `Glass.kt`）。
 *
 * ---
 *
 * # 与旧令牌的关系
 *
 * | | 旧（`design/tokens/Colors.kt`） | 新（本文件） |
 * |---|---|---|
 * | 底色 | 冷调蓝灰 `#0E1116` | **中性近黑** `#000000` + 三级灰阶（iOS 27 语义） |
 * | 分层 | 靠"比底色更亮"的卡片色 | **背景层级 + 填充层级**两套正交系统 |
 * | 品牌 | 粉色为主 | 粉**降为强调色**，主色改为系统蓝（更中性、更耐看） |
 * | 玻璃 | 单一 72% 黑 | **四级材质**（ultraThin/thin/regular/thick）+ 透明滑杆 |
 *
 * ---
 *
 * # 🔴 三层颜色系统（iOS 27 的核心组织方式）
 *
 * 旧系统只有"三级背景 + 一堆语义色"。新系统按**用途**分成三层，
 * 每层解决一个不同的问题 —— 这是"层级清晰"的结构性保证：
 *
 * | 层 | 解决什么 | 用在哪 |
 * |---|---|---|
 * | **Background**（背景） | "这一块是什么区域" | 页面底、分组底、浮起区 |
 * | **Fill**（填充） | "这是一个控件" | 输入框、胶囊、分段控件、未选中态 |
 * | **Label**（标签） | "文字有多重要" | 主/次/三级/四级文字 |
 *
 * ⚠️ **三层不能互相替代**：用 Background 当 Fill 会让控件"看起来是个区域"；
 * 用 Fill 当 Label 会得到"没有对比度的文字"。旧系统的 `bgHover` 就是
 * 混用的产物（既是"悬浮区域"又是"输入框底"）。
 *
 * ---
 *
 * # 对比度铁律
 *
 * 1. [brand] **只做背景 / 图标**；做文字必须用 [brandText]
 * 2. 正文级次要信息用 [labelSecondary]（它在暗底上实测 ≥ 4.5:1）
 * 3. 三级 / 四级文字**只用于非关键信息**（时间戳、辅助说明），不做正文
 *
 * 页面里**禁止硬编码色值**，一律通过 `BiliV3.colors` 取。
 */
@Immutable
data class V3Colors(
    // =====================================================================
    // 一、Background —— 区域层级
    // =====================================================================

    /**
     * 页面底。**纯黑**。
     *
     * ## 为什么这次敢用纯黑（旧系统明确禁止过）
     *
     * 旧系统的理由是对的：纯黑在 OLED 上滚动会有拖影（黑像素不发光，
     * 像素点亮/熄灭有响应延迟）。
     *
     * 但 iOS 27 的深色背景就是 `#000000`，而**玻璃材质的质感依赖纯黑底** ——
     * 只有底足够黑，玻璃的"提亮"才看得出来。折中方案：
     * - 页面底用纯黑（获得材质对比）
     * - **滚动内容不直接坐在纯黑上**，而是坐在 [bgSecondary] 的区域里
     * - 玻璃层自带 [materials] 的提亮，拖影面积被大幅压缩
     */
    val bgPrimary: Color,

    /** 二级背景。**分组底**（列表分组、内容区块）。比页面底亮一档。 */
    val bgSecondary: Color,

    /** 三级背景。**更内嵌**的区域（分组内的子块、代码块底）。 */
    val bgTertiary: Color,

    /** 浮起背景（Elevated）。**弹层 / 面板**坐在这一层。 */
    val bgElevated: Color,

    /** 二级浮起背景。 */
    val bgSecondaryElevated: Color,

    // =====================================================================
    // 二、Fill —— 控件层级（与 Background 正交）
    // =====================================================================

    /**
     * 主填充。**最明确的控件底**：选中的分段、强调胶囊。
     *
     * ⚠️ 不是"卡片底"。控件用 Fill，区域用 Background —— 见类文档。
     */
    val fillPrimary: Color,

    /** 次填充。输入框、未选中胶囊、可点的整行。 */
    val fillSecondary: Color,

    /** 三级填充。最轻的可点反馈（hover / 长按起手）。 */
    val fillTertiary: Color,

    /** 四级填充。几乎不可见的区域暗示（骨架、极淡底）。 */
    val fillQuaternary: Color,

    // =====================================================================
    // 三、Label —— 文字层级
    // =====================================================================

    /** 主文字。标题、正文主体。 */
    val labelPrimary: Color,

    /**
     * 次文字。**正文级次要信息**（UP 名、播放量、说明）。
     *
     * 实测在 `bgPrimary` 上对比度 ≥ 4.5:1，可做正文。
     */
    val labelSecondary: Color,

    /** 三级文字。时间戳、辅助标注。**不做正文**。 */
    val labelTertiary: Color,

    /** 四级文字。占位符、禁用态。 */
    val labelQuaternary: Color,

    /** 品牌色上的文字（按钮文字）。 */
    val labelOnBrand: Color,

    /** 媒体（封面 / 画面）上的文字。**恒白**，与主题无关。 */
    val labelOnMedia: Color,

    // =====================================================================
    // 四、Separator —— 分隔
    // =====================================================================

    /** 不透明分隔线（深色下几乎不用，保留给高对比场景）。 */
    val separatorOpaque: Color,

    /**
     * 半透明分隔线。**默认分隔手段**。
     *
     * ⚠️ 无卡片架构下，分隔线承担"分组边界"的职责 ——
     * 但**优先用间距**，线只在必须硬边界处用（见 §5.1 硬规则 4）。
     */
    val separator: Color,

    // =====================================================================
    // 五、品牌与强调
    // =====================================================================

    /**
     * 品牌主色。
     *
     * ## 🔴 为什么主色从"粉"改成"蓝"
     *
     * 旧系统把 B 站粉当主色，于是**每个可点元素都是粉的** ——
     * 一屏下来粉色失去强调意义（"什么都强调 = 什么都不强调"）。
     *
     * 新系统：**蓝是交互色**（可点、选中、链接），
     * **粉降为品牌标识色**（只用在"这是 B 站内容"的地方：Logo、品牌标签、点赞激活）。
     * 这样粉色重新变得"稀有且有意义"。
     */
    val brand: Color,

    /** 品牌粉（B 站真值）。只做品牌标识与点赞激活。 */
    val brandBili: Color,

    /** 品牌蓝的文字安全版（链接文字）。 */
    val brandText: Color,

    /** 品牌粉的文字安全版。 */
    val brandBiliText: Color,

    /** 品牌色的弱化底（选中胶囊底、标签底）。 */
    val brandDim: Color,

    // =====================================================================
    // 六、状态
    // =====================================================================

    val stateError: Color,
    val stateSuccess: Color,
    val stateWarning: Color,
    /** 直播中（红点 / LIVE 角标）。 */
    val stateLive: Color,

    // =====================================================================
    // 七、遮罩
    // =====================================================================

    /** 通用遮罩（图片压字、时长角标）。 */
    val overlay: Color,

    /** 弹层遮罩（Sheet / Dialog 背后）。 */
    val scrim: Color,

    /** 媒体上的控制圆钮底。 */
    val controlOverlay: Color,

    // =====================================================================
    // 八、媒体 / 播放器
    // =====================================================================

    /** 播放器底。**恒纯黑**。 */
    val playerBackground: Color,

    /** 弹幕描边。 */
    val danmakuStroke: Color,

    /** 进度条未播轨道。 */
    val trackInactive: Color,

    /** 封面占位。 */
    val coverPlaceholder: Color,

    /** 头像占位。 */
    val avatarPlaceholder: Color,

    // =====================================================================
    // 九、互动
    // =====================================================================

    /** 投币金。 */
    val accentCoin: Color,
    /** 收藏黄。 */
    val accentFavorite: Color,
    val onAccentCoin: Color,

    // =====================================================================
    // 十、第三方渠道品牌色（他方真值，豁免）
    // =====================================================================

    val channelWechat: Color,
    val channelMoments: Color,
    val channelDownload: Color,
    val channelCopyLink: Color,

    // =====================================================================
    // 十一、玻璃材质（数值来自 iOS 27 实测表）
    // =====================================================================

    /**
     * 玻璃材质配置。见 [V3Materials]。
     *
     * ⚠️ 玻璃**不是全局主题** —— 只用在浮动层（底部导航 / 播放器控制 /
     * Sheet / Dialog / 浮动按钮）。普通内容一律实体。
     */
    val materials: V3Materials,
)

/**
 * 深色（**本项目唯一主题**）。
 *
 * 数值取自 iOS 27 UI Kit 27.0.2 的语义色表（sRGB 近似值），
 * 品牌位替换为 B 站真值。
 */
val V3DarkColors = V3Colors(
    // ---- Background ----
    bgPrimary = Color(0xFF000000),
    bgSecondary = Color(0xFF1C1C1E),
    bgTertiary = Color(0xFF2C2C2E),
    bgElevated = Color(0xFF1C1C1E),
    bgSecondaryElevated = Color(0xFF2C2C2E),

    // ---- Fill ----
    // 实测（iOS 27）：primary 0.36 / secondary 0.32 / tertiary 0.24 / quaternary 0.18
    // 基色 #787880 是中性灰，不偏蓝（旧系统的 #1F2530 明显偏蓝）
    fillPrimary = Color(0x5C787880),
    fillSecondary = Color(0x52787880),
    fillTertiary = Color(0x3D767680),
    fillQuaternary = Color(0x2E767680),

    // ---- Label ----
    // 实测：primary #ffffff @1.0 / secondary #ebebf5 @0.7 / tertiary @0.3 / quaternary @0.16
    labelPrimary = Color(0xFFFFFFFF),
    labelSecondary = Color(0xFFEBEBF5).copy(alpha = 0.7f),
    labelTertiary = Color(0xFFEBEBF5).copy(alpha = 0.45f),
    labelQuaternary = Color(0xFFEBEBF5).copy(alpha = 0.28f),
    labelOnBrand = Color(0xFF000000),
    labelOnMedia = Color(0xFFFFFFFF),

    // ---- Separator ----
    separatorOpaque = Color(0xFF38383A),
    // 实测：nonOpaque dark = #ffffff @0.17。本项目用略低的值（0.12）——
    // 无卡片架构下分隔线很多，0.17 会显"脏"
    separator = Color(0x1FFFFFFF),

    // ---- 品牌 ----
    brand = Color(0xFF0091FF),
    brandBili = Color(0xFFFF8FB0),
    brandText = Color(0xFF5CB8FF),
    brandBiliText = Color(0xFFFF9EBB),
    brandDim = Color(0x330091FF),

    // ---- 状态 ----
    stateError = Color(0xFFFF4245),
    stateSuccess = Color(0xFF30D158),
    stateWarning = Color(0xFFFF9230),
    stateLive = Color(0xFFFF375F),

    // ---- 遮罩 ----
    overlay = Color(0x8A000000),
    scrim = Color(0xA6000000),
    controlOverlay = Color(0xB3000000),

    // ---- 媒体 ----
    playerBackground = Color(0xFF000000),
    danmakuStroke = Color(0xCC000000),
    trackInactive = Color(0x3DFFFFFF),
    coverPlaceholder = Color(0xFF1C1C1E),
    avatarPlaceholder = Color(0xFF2C2C2E),

    // ---- 互动 ----
    accentCoin = Color(0xFFFFCC00),
    accentFavorite = Color(0xFFFFD600),
    onAccentCoin = Color(0xFF1A1400),

    // ---- 第三方渠道（他方品牌真值）----
    channelWechat = Color(0xFF4CAF50),
    channelMoments = Color(0xFF66BB6A),
    channelDownload = Color(0xFF7E57C2),
    channelCopyLink = Color(0xFF42A5F5),

    // ---- 玻璃 ----
    materials = V3Materials(),
)

/**
 * 玻璃材质参数（iOS 27 实测表 + Compose 落地换算）。
 *
 * ---
 *
 * # 来源与换算
 *
 * iOS 27 的 `materials.json` 给的是 **CSS 的 `backdrop-filter: blur(N)`**，
 * 其中 N 就是**高斯标准差 σ**（`filter-effects-1` 规定）。
 * 而 Compose 的 `BlurEffect(radiusX)` 单位是 **px 半径**，
 * 两者在视觉上可近似对应（都按"边缘衰减到 ~0 的距离"理解）。
 *
 * ## 🔴 但 iOS 27 的实测值**远小于**网页复刻常用的值
 *
 * 原文明确记录了这段历史（值得照抄结论，避免重走弯路）：
 *
 * > 18/15/9/7 是猜的；后来提到 40/32/16/12，**两次都错** ——
 * > 那些网页复刻大多在复刻 **clear** 变体，而默认映射到 **regular**；
 * > 且 σ→CSS 的换算差了 2 倍（CSS `blur(L)` 的 σ = L，不是 L/2）。
 * > **原生比两者都清得多。**
 *
 * 所以本项目的模糊半径取 iOS 27 的实测档位（6 / 6 / 3.6 / 1.6），
 * **不取**"看起来更像玻璃"的大半径 —— 大半径会糊掉底下的内容，
 * 反而失去"透出背景"的意义（那就成了纯色板）。
 *
 * ---
 *
 * # 四个层级
 *
 * | 层级 | 模糊 | 用在哪 |
 * |---|---|---|
 * | [ultraThin] | 3.6 | 大面板（Sheet 主体、全屏浮层）—— 面积大，要最清 |
 * | [thin] | 6 | 中等控件（播放器控制条、浮动按钮） |
 * | [regular] | 6 | **默认**（底部导航、播放器主控制层） |
 * | [clear] | 1.6 | 压在**高对比媒体**上（画面之上的小控件） |
 *
 * ⚠️ [clear] 只在"底下是彩色媒体"时用 —— 它在纯色底上几乎看不出效果，
 * 且**必须**加 [clearScrim] 才能保证前景可读。
 */
@Immutable
data class V3Materials(
    /**
     * 玻璃底色（深色）。实测 `#1a1a1a @0.7`。
     *
     * ## ⚠️ 为什么不是纯白/纯黑
     *
     * iOS 27 的 regular 玻璃底色是**中性深灰 @70%** ——
     * 它压在背景上时"提亮"背景，但又不至于变成一块实心板。
     * 旧系统用 72% 纯黑，结果是"在深色页面上挖了几个黑洞"（当时的实测结论），
     * 因为它**比底色更暗**。新系统改用比纯黑**更亮**的深灰，方向是对的。
     */
    val tint: Color = Color(0xB31A1A1A),

    /** 玻璃叠色（增加"厚度感"）。实测 light 用 #bfbfbf @0.1。 */
    val overlay: Color = Color(0x1ABFBFBF),

    /**
     * 边缘环（rim）。
     *
     * 实测：**零模糊 + 正扩散的 drop shadow** ——
     * 是一圈**浅灰**，不是"深色内描边"（早先的假设是错的）。
     */
    val rim: Color = Color(0x40A6A6A6),

    /**
     * 边缘高光（specular）。
     *
     * 实测：**上下两条内阴影**（offset ±40 / spread −40 / blur 10），
     * 读起来是"光打在边缘上"，不是"一层白霜"。
     *
     * Compose 落地：用 1dp 的**上亮下暗**渐变描边近似（见 `glassEdge`）。
     */
    val specularTop: Color = Color(0x33FFFFFF),
    val specularBottom: Color = Color(0x1A000000),

    /**
     * 玻璃投影。
     *
     * 实测 `blur 48 / offsetY 8 / #000 @0.45`。
     * ⚠️ 深色下投影几乎不可见 —— 保留它主要是给"玻璃压在亮内容上"的场景
     * （如播放器控制条压在明亮画面上）。
     */
    val shadow: Color = Color(0x73000000),

    /** 投影参数（供 `Modifier.shadow` 用）。 */
    val shadowElevation: Dp = 8.dp,
    val shadowBlur: Dp = 24.dp,

    /**
     * clear 变体在媒体上必须加的压暗层。
     *
     * 实测 clear 的基础色是 `#101010 @1.0`（近乎不透明的黑）——
     * 这看着反直觉，但它就是"clear 玻璃在高对比画面上仍可读"的原因。
     */
    val clearScrim: Color = Color(0x80101010),

    /**
     * 透明度滑杆（iOS 27 新增的系统级设置）。
     *
     * `0` = 完全染色（最不透明）；`1` = 极致通透。
     * 默认 `0.5`（系统默认）。
     *
     * ⚠️ 本项目**不做设置项**（系统级能力不该由 App 复刻），
     * 但把它留成参数：低端设备降级时可以调高（更不透明 = 更省 GPU）。
     */
    val transparency: Float = 0.5f,
) {
    /**
     * 按透明度滑杆缩放后的实际玻璃底透明度。
     *
     * 实测曲线：`opacityScale = 1.6 + (-1.1) * t`。
     * `t=0` → 1.6（更不透明）；`t=1` → 0.5（更透）。
     *
     * ⚠️ 结果是**乘数**，要 clamp 到 `[0, 1]` 才能当 alpha 用。
     */
    fun tintAlpha(): Float {
        val scale = 1.6f - 1.1f * transparency
        return (tint.alpha * scale).coerceIn(0f, 1f)
    }

    /** 按滑杆缩放后的模糊倍数：`0.5 + 0.8 * t`。 */
    fun blurScale(): Float = 0.5f + 0.8f * transparency
}
