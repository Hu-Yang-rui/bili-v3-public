package com.example.biliv3.design.v3

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance

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
    // 十一、扩展语义色（迁移旧表时补齐）
    // =====================================================================
    //
    // ## 为什么这些要单独立，而不是从上面挑一个复用
    //
    // 全量迁移旧表（1039 处引用 / 61 个文件）时发现这些角色**没有等价物**。
    // 它们各自承载一个**独立语义**，借色会产生误导
    // （与 [skipSegment] 那条是同一个理由）：
    //
    // | 角色 | 为什么不能借色 |
    // |---|---|
    // | [qrSurface] | 二维码**必须**近白底 —— 与主题无关（深底白码识别率低）|
    // | [rankFirst] 等 | 名次是**序数**语义；借 [brand] 会让第 1 名看着像可点链接 |
    // | [skeletonBase] | 骨架屏要**两档**才能做微光，单色做不出流动感 |
    // | [categoryAccent] | 12 个分区**共用一个**强调色（§5.1：分区入口是导航不是内容）|
    // | [accentTerminal] | 极客点缀的**唯一**合法色（§5.4.3：极客元素是标点不是正文）|
    // | [skipSegment] | 进度条上已有"已播/未播"两义，跳过区间是**第三义** |
    // | [subtitleScrim] | 字幕条底要比通用遮罩**更黑**，否则白字在亮画面上发虚 |

    // ---- 二维码 ----
    /**
     * 二维码承载底。**恒为近白**，与主题无关。
     *
     * ⚠️ 不要改成深色 —— 深底白码在部分扫码器上识别率低（旧表实测结论）。
     */
    val qrSurface: Color,
    val onQrSurface: Color,

    // ---- 榜单名次（序数语义，与品牌/状态无关）----
    val rankFirst: Color,
    val rankSecond: Color,
    val rankThird: Color,

    // ---- 骨架屏（两档才能做微光）----
    val skeletonBase: Color,
    val skeletonHighlight: Color,

    // ---- 分区 ----
    /** 12 个分区**共用**的强调色（语义靠图标形状区分，不靠颜色）。 */
    val categoryAccent: Color,

    // ---- 极客点缀（克制使用，见 §5.4.3）----
    /**
     * 终端青。**只做小面积强调**：提示符、光标、1dp 分隔线、微标签。
     *
     * ⚠️ 不要用它做大面积背景或正文 —— 那是"廉价赛博朋克"的起点。
     * 总面积不应超过一屏的 5%（极客元素是"标点"，不是"正文"）。
     */
    val accentTerminal: Color,
    /** 终端青的弱化版（做底、做描边）。 */
    val accentTerminalDim: Color,
    /** 极淡网格线（空态 / 骨架屏底纹）。 */
    val gridLine: Color,

    // ---- 空降助手：进度条上的跳过区间 ----
    /**
     * 跳过区间在**进度条上**的标记色。
     *
     * 进度条上已有两种含义，不能借色：[brand]（已播）、
     * [trackInactive]（未播）。跳过区间是**第三义**。
     */
    val skipSegment: Color,
    /** 播放位置**正处于**跳过区间内时的强调色（更亮）。 */
    val skipSegmentActive: Color,

    // ---- 媒体 ----
    /** 字幕条底。比通用遮罩更黑，保证白字在亮画面上可读。 */
    val subtitleScrim: Color,
    /** 封面压字渐变的终点。 */
    val gradientMediaEnd: Color,

    // =====================================================================
    // 十二、玻璃材质（数值来自 iOS 27 实测表）
    // =====================================================================

    /**
     * 玻璃材质配置。见 [V3Materials]。
     *
     * ⚠️ 玻璃**不是全局主题** —— 只用在浮动层（底部导航 / 播放器控制 /
     * Sheet / Dialog / 浮动按钮）。普通内容一律实体。
     */
    val materials: V3Materials,

) {
    /**
     * 本套颜色是否为**浅色**（按页面底亮度判断）。
     *
     * ## 为什么用"算"而不是"存字段"
     *
     * 存一个 `val isLight: Boolean` 会让"改底色忘了改标志"成为可能 ——
     * 那是典型的**静默失效**：编译过、测试过、界面就是不对。
     * 这里从 [bgPrimary] 的相对亮度推导，**改了底色就自动跟随**。
     *
     * 阈值 0.5：`#FFFFFF` 亮度 1.0、`#000000` 亮度 0.0，
     * 两套表相距极远，中间值（灰）不属于任何一套。
     *
     * 用途：`BiliV3Theme` 据此选 Material 的 `lightColorScheme` /
     * `darkColorScheme`，以及少数"浅色下要反过来"的绘制逻辑
     * （如投影 vs 描边）。
     */
    val isLight: Boolean
        get() = bgPrimary.luminance() > 0.5f
}

/**
 * 深色。
 *
 * 数值取自 iOS 27 UI Kit 27.0.2 的语义色表（sRGB 近似值），
 * 品牌位替换为 B 站真值。
 *
 * ⚠️ **v1.1.3–v1.6.8 期间这是本项目唯一主题。**
 * 浅色主题已按用户要求加回（见 [V3LightColors]），
 * 两套表并存，由 `BiliV3` 的主题入口选择。
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

    // ---- 二维码（恒为近白，与主题无关）----
    qrSurface = Color(0xFFF7F8FA),
    onQrSurface = Color(0xFF14181F),

    // ---- 榜单名次（序数语义）----
    rankFirst = Color(0xFFFF6B6B),
    rankSecond = Color(0xFFFFA94D),
    rankThird = Color(0xFFFFD43B),

    // ---- 骨架屏 ----
    skeletonBase = Color(0xFF1C1C1E),
    skeletonHighlight = Color(0xFF2C2C2E),

    // ---- 分区（12 个分区共用单色）----
    categoryAccent = Color(0xFF8E8E93),

    // ---- 极客点缀 ----
    accentTerminal = Color(0xFF4FD1C5),
    accentTerminalDim = Color(0x1F4FD1C5),
    gridLine = Color(0x0FFFFFFF),

    // ---- 跳过区间（进度条上的第三义）----
    skipSegment = Color(0xE64FD1C5),
    skipSegmentActive = Color(0xFF9BF5EC),

    // ---- 媒体 ----
    subtitleScrim = Color(0xB3000000),
    gradientMediaEnd = Color(0x99000000),

    // ---- 玻璃 ----
    materials = V3Materials(),
)

/**
 * 浅色（**v1.6.9 起为默认主题**）。
 *
 * ---
 *
 * # 🔴 这一节推翻了 v1.1.3 的决定
 *
 * v1.1.3 曾移除浅色主题，理由是「静态页背后是纯色底，玻璃只能靠比底色更白
 * 来假装层次，那不是玻璃而是白色卡片」。**该理由针对的是"把玻璃用在页面背景上"**——
 * 而本项目从来没有把玻璃用在页面背景上：玻璃只用于**浮动层**
 * （底部导航 / Sheet / Dialog / 播放器控件）。
 *
 * 浮动层底下**始终有内容在滚**（列表、封面、画面），所以浅色下同样有东西可模糊。
 * 原来的顾虑不成立。
 *
 * ## 数值来源
 *
 * iOS 27 UI Kit 的**浅色**语义色表（sRGB 近似值），品牌位替换为 B 站真值。
 * 与深色表**逐字段对应**，字段名与顺序完全一致 —— 这样任何新增令牌
 * 都必须同时补两套，漏掉一个会编译失败（`V3Colors` 是 data class，
 * 构造参数缺一不可）。
 *
 * ## 🔴 对比度是按"白底"重算的，不是把深色值反相
 *
 * 浅色下**不能**简单地把深色值调亮 —— 深色表的很多值在暗底上达标，
 * 在白底上会严重不足。本表的每一处文字色都按**白底对比度**重算：
 *
 * | 令牌 | 白底对比度 | 要求 |
 * |---|---|---|
 * | [labelPrimary] `#000000` | 21:1 | 正文 ≥ 4.5 ✅ |
 * | [labelSecondary] `#3C3C43 @0.70` | **4.50:1** | 正文级次要 ≥ 4.5 ✅ |
 * | [labelTertiary] `#3C3C43 @0.56` | **3.05:1** | 辅助 ≥ 3.0 ✅ |
 * | [brandText] `#0066CC` | **5.56:1** | 链接文字 ≥ 4.5 ✅ |
 * | [brandBiliText] `#C2185B` | **5.87:1** | 链接文字 ≥ 4.5 ✅ |
 * | [stateError] `#D70015` | **5.39:1** | ≥ 4.5 ✅ |
 * | [stateSuccess] `#2E7D32` | **5.13:1** | ≥ 4.5 ✅ |
 * | [stateWarning] `#C93400` | **5.28:1** | ≥ 4.5 ✅ |
 *
 * ⚠️ **`labelSecondary` 用 0.70 而不是 iOS 默认的 0.60**：
 * 0.60 在白底上只有 **3.45:1**，低于本项目"正文级次要信息 ≥ 4.5:1"的要求
 * （该要求见本文件顶部说明）。0.70 刚好到 4.50:1。
 *
 * ⚠️ **`labelTertiary` 用 0.56 而不是 0.50**：0.50 只有 2.69:1，
 * 低于"辅助信息 ≥ 3:1"。0.56 是 3.05:1。
 *
 * ## 🔴 与主题**无关**的令牌（两套表里取值相同）
 *
 * 这些是"压在媒体上"的颜色，媒体本身不随主题变：
 * [labelOnMedia] · [playerBackground] · [danmakuStroke] · [overlay] ·
 * [controlOverlay] · [subtitleScrim] · [gradientMediaEnd] ·
 * [controlFlat] · [controlFlatBorder] · [clearScrim] · [qrSurface] · [onQrSurface]
 *
 * ⚠️ 它们**不是"忘了改"** —— 白字压在视频画面上，无论 App 是深色还是浅色
 * 都需要；把它们改成深色会让字幕/弹幕/角标在画面上不可读。
 */
/**
 * 浅色玻璃材质。
 *
 * ---
 *
 * # 与深色玻璃的差别（不只是"把颜色调亮"）
 *
 * | 维度 | 深色 | 浅色 |
 * |---|---|---|
 * | 底色 | 中性深灰 `#1A1A1A @70%` | 近白 `#F7F7FA @72%` |
 * | 边缘环 rim | 浅灰（**提亮**边缘） | 淡黑（**压出**边界） |
 * | specular 上 | 白 20% | 白 **60%**（浅色玻璃的高光更明显） |
 * | 投影 | 几乎不可见（黑底黑影） | **可见**（白底上投影才成立） |
 *
 * ## 🔴 为什么 rim 的方向要反过来
 *
 * 深色下玻璃比底色**亮**，边缘用浅灰是在"延续提亮"。
 * 浅色下玻璃比内容**亮**（近白压在彩色封面上），
 * 如果再画浅色 rim 就完全没有边界 —— 必须用**很淡的暗色**压出边界，
 * 否则玻璃面板会"融"进白底页面里。
 *
 * ## 🔴 为什么浅色下投影终于有用了
 *
 * 深色表里 `shadow` 的注释写着「深色下投影几乎不可见」——
 * 这是物理事实：黑底上的黑影看不出来。
 * 浅色下正好相反：白底上的淡黑影是**分层的主要手段**，
 * 所以这里的投影是真正在工作的（alpha 也调高了）。
 *
 * ## ⚠️ 与主题无关的部分
 *
 * [clearScrim] / [controlFlat] / [controlFlatBorder] 三者**压在媒体画面上**，
 * 浅色下保持深色值 —— 它们要的是"在视频画面上保证白字可读"，
 * 与 App 主题无关（详见各自 KDoc）。
 */
val V3LightMaterials = V3Materials(
    /** 玻璃底色。浅色用近白（深色是近黑）。 */
    tint = Color(0xB8F7F7FA),

    /** 玻璃叠色。实测 light 用 `#bfbfbf @0.1`。 */
    overlay = Color(0x1ABFBFBF),

    /**
     * 边缘环（rim）。浅色下用**淡黑**压出边界 ——
     * 理由见 [V3LightMaterials] 的说明（浅色玻璃必须"框"出来才看得出）。
     */
    rim = Color(0x1F000000),

    /**
     * 边缘高光。浅色玻璃的高光比深色**明显得多**
     * （近白材质上，顶部那道白光正是"玻璃感"的来源）。
     */
    specularTop = Color(0x99FFFFFF),
    specularBottom = Color(0x0A000000),

    /**
     * 玻璃投影。**浅色下这是真正在工作的**（白底上的淡黑影才看得见）。
     */
    shadow = Color(0x1F000000),

    shadowElevation = 8.dp,
    shadowBlur = 24.dp,

    /** clear 变体在媒体上的压暗层。**压在画面上，与主题无关**（保持深色值）。 */
    clearScrim = Color(0x80101010),

    /** 播放器控件扁平底。**压在动态画面上，与主题无关**（保持深色值）。 */
    controlFlat = Color(0x59000000),

    /** 播放器控件描边。同上。 */
    controlFlatBorder = Color(0x24FFFFFF),

    transparency = 0.5f,
)

val V3LightColors = V3Colors(
    // ---- Background ----
    // iOS 浅色：systemBackground #FFFFFF / secondary #F2F2F7 / tertiary #E5E5EA
    //
    // ⚠️ 与深色**方向相反**：深色靠"提亮"分层（纯黑 → 灰），
    //    浅色靠"压暗"分层（纯白 → 浅灰）。层级语义（谁更靠前）不变。
    bgPrimary = Color(0xFFFFFFFF),
    bgSecondary = Color(0xFFF2F2F7),
    bgTertiary = Color(0xFFE5E5EA),
    bgElevated = Color(0xFFFFFFFF),
    bgSecondaryElevated = Color(0xFFF2F2F7),

    // ---- Fill ----
    // iOS 浅色：基色同为 #787880，但 alpha 明显更低
    // （深色 0.36/0.32/0.24/0.18 → 浅色 0.20/0.16/0.12/0.08）
    //
    // ⚠️ 浅色下填充必须**更淡**：同样的 alpha 在白色上会显得很重，
    //    因为白底本身已经很亮，再加灰就"脏"。
    fillPrimary = Color(0x33787880),
    fillSecondary = Color(0x29787880),
    fillTertiary = Color(0x1F767680),
    fillQuaternary = Color(0x14767680),

    // ---- Label ----
    // iOS 浅色：基色 #3C3C43（深灰，不是纯黑 —— 纯黑太硬）
    labelPrimary = Color(0xFF000000),
    labelSecondary = Color(0xFF3C3C43).copy(alpha = 0.70f),
    labelTertiary = Color(0xFF3C3C43).copy(alpha = 0.56f),
    labelQuaternary = Color(0xFF3C3C43).copy(alpha = 0.30f),
    // ⚠️ 品牌按钮在浅色下仍是**蓝底白字**（brand 在浅色下没变浅），
    //    所以这里是白字；深色表里是黑字（深色下 brand 更亮）。
    labelOnBrand = Color(0xFFFFFFFF),
    labelOnMedia = Color(0xFFFFFFFF),

    // ---- Separator ----
    separatorOpaque = Color(0xFFC6C6C8),
    // iOS 浅色 nonOpaque = #3C3C43 @0.29。本项目取略低值（0.18）——
    // 理由与深色一致：无卡片架构下分隔线很多，0.29 会显"脏"。
    separator = Color(0x2E3C3C43),

    // ---- 品牌 ----
    // 🔴 浅色下品牌色必须**变深**，否则白底上对比度不足：
    //    #0091FF 在白底只有 2.6:1（不达标），#007AFF 是 4.1:1（仅够大字号）
    brand = Color(0xFF007AFF),
    brandBili = Color(0xFFFB7299),
    // 文字专用（≥4.5:1）
    brandText = Color(0xFF0066CC),
    brandBiliText = Color(0xFFC2185B),
    brandDim = Color(0x1A007AFF),

    // ---- 状态 ----
    // 同样全部改为浅色专用值（深色表的值在白底上普遍只有 2~3:1）
    stateError = Color(0xFFD70015),
    stateSuccess = Color(0xFF2E7D32),
    stateWarning = Color(0xFFC93400),
    stateLive = Color(0xFFD70036),

    // ---- 遮罩（压在媒体/内容上，与主题无关）----
    // ⚠️ 浅色下遮罩**仍然要暗**：它的作用是"压暗底下的东西让前景可读"，
    //    改成白色会失去这个作用。
    overlay = Color(0x8A000000),
    scrim = Color(0x66000000),
    controlOverlay = Color(0xB3000000),

    // ---- 媒体 ----
    playerBackground = Color(0xFF000000),
    danmakuStroke = Color(0xCC000000),
    // ⚠️ 进度条轨道：播放器内恒为亮色（压黑底），与主题无关
    trackInactive = Color(0x3DFFFFFF),
    // 封面/头像占位：浅色下用**浅灰**（深色表是深灰）
    coverPlaceholder = Color(0xFFE5E5EA),
    avatarPlaceholder = Color(0xFFE5E5EA),

    // ---- 互动 ----
    // 金币/收藏的"金"在白底上需要更深才看得清
    accentCoin = Color(0xFFF5A623),
    accentFavorite = Color(0xFFE6A700),
    onAccentCoin = Color(0xFF1A1400),

    // ---- 第三方渠道（他方品牌真值，不随主题变）----
    channelWechat = Color(0xFF07C160),
    channelMoments = Color(0xFF4CAF50),
    channelDownload = Color(0xFF7E57C2),
    channelCopyLink = Color(0xFF007AFF),

    // ---- 二维码（**恒为近白底**，与主题无关：扫码需要高对比）----
    qrSurface = Color(0xFFF7F8FA),
    onQrSurface = Color(0xFF14181F),

    // ---- 榜单名次 ----
    // 浅色下用更深的橙红系（深色表的值在白底上偏"荧光"）
    rankFirst = Color(0xFFFF3B30),
    rankSecond = Color(0xFFFF9500),
    rankThird = Color(0xFFB8860B),

    // ---- 骨架屏 ----
    // ⚠️ 与深色**方向相反**：深色是"底暗、高光更亮"，
    //    浅色是"底浅灰、高光更白"。
    skeletonBase = Color(0xFFE5E5EA),
    skeletonHighlight = Color(0xFFF7F7FA),

    // ---- 分区（12 个分区共用单色）----
    categoryAccent = Color(0xFF6C6C70),

    // ---- 极客点缀 ----
    // 终端青在白底上必须变深（#4FD1C5 在白底只有 1.9:1）
    accentTerminal = Color(0xFF0D9488),
    accentTerminalDim = Color(0x1F0D9488),
    // ⚠️ 网格线方向相反：深色是"白 6%"，浅色是"黑 6%"
    gridLine = Color(0x0F000000),

    // ---- 跳过区间（进度条上的第三义）----
    skipSegment = Color(0xE60D9488),
    skipSegmentActive = Color(0xFF0F766E),

    // ---- 媒体（压在画面上，与主题无关）----
    subtitleScrim = Color(0xB3000000),
    gradientMediaEnd = Color(0x99000000),

    // ---- 玻璃 ----
    materials = V3LightMaterials,
)


/**
 * 当前生效的主题表（**默认浅色**）。
 *
 * 页面**不要**直接引用本常量 —— 用 `BiliV3.colors`，
 * 否则主题切换/预览覆盖会失效。
 */
val V3DefaultColors = V3LightColors

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
     *
     * ⚠️ **只用于"小面积、必须绝对可读"的场景**（如画面之上的角标）。
     *
     * 🔴 **播放器控件不要用它** —— 见 [controlFlat] 的完整说明：
     * 播放器控件需要的是"扁平压暗"，不是模糊玻璃。
     * ⚠️ 另注意 `V3Glass.Level.Clear` 的 `tintScale = 1.6` 会**放大**本值
     * （`0.50 × 1.6 = 0.80`），实测直接把控件压成实心黑饼。
     */
    val clearScrim: Color = Color(0x80101010),

    /**
     * **播放器控件的扁平底**（压在视频画面上的圆钮/胶囊）。
     *
     * ---
     *
     * ## 🔴 为什么不给播放器控件用模糊玻璃（实测两轮，最终放弃）
     *
     * 这条路走过两次，都不成立：
     *
     * **第一轮**：`GlassSurface(level = Clear, tint = clearScrim)`
     * → 控件变成**实心黑饼**。算术原因：
     * `Clear.tintScale = 1.6` 会放大传入 alpha，`0.50 × 1.6 = 0.80`
     * —— 比改之前的纯色（54%）**更黑**。
     *
     * **第二轮**：`Thin + 0.25 tint`（有效 0.24）
     * → 技术上确实能透出背景了，但**观感仍然不对**：
     * 播放器控件是**标点**，不是内容。给它们加 6dp 模糊，
     * 等于在画面上多铺一层"有纹理的玻璃" ——
     * 画面本身已经很花，控件再带纹理只会更吵。
     *
     * ## 为什么扁平才是对的
     *
     * | 维度 | 模糊玻璃 | **扁平半透明** |
     * |---|---|---|
     * | 画面干扰 | 引入一层模糊纹理 | 只有一层均匀压暗 |
     * | 性能 | 每帧要抓帧 + box blur | 一次 `drawRect`，零成本 |
     * | 可读性 | 背景被模糊后对比度下降 | 压暗均匀，白图标始终清晰 |
     * | 观感 | 与"视频画面"抢戏 | **退到画面之后**，让内容当主角 |
     *
     * > 🔴 **判据**：**模糊玻璃适合"大面积、静态、内容之上"的浮层**
     * > （底部导航 / Sheet / Dialog）。
     * > **播放器控件是"小面积、压在动态画面上"的标点** ——
     * > 它需要的只是"压暗以保证可读"，不是"材质"。
     *
     * ## 取值
     *
     * `#000000 @ 0x59` ≈ **35% 黑**：
     * - 够压暗，白色图标在雪地/白墙这类高亮画面上也清晰
     * - 够透，明亮画面上仍能看出"这是浮在画面上的"
     * - 比旧的 `controlOverlay`（70%）**轻一半** —— 旧的太"实"，
     *   一颗颗黑饼压在画面上很重
     *
     * ⚠️ 需要更实时可临时传 `colors.controlOverlay`（70%），
     * 但那会明显更"重"，只适合极少数必须绝对抢眼的场合。
     */
    val controlFlat: Color = Color(0x59000000),

    /**
     * 播放器控件的**细描边**（1dp，白 14%）。
     *
     * 扁平半透明的圆在**明亮画面**（雪地、白墙）上会"糊掉边界" ——
     * 因为 35% 黑压在白色上只是灰，与周围的浅色拉不开。
     * 一圈很淡的白描边就能把形状"托"出来，且不引入任何纹理。
     *
     * ⚠️ 这是**扁平方案**的一部分，不是"玻璃的 rim 高光"：
     * 它只有描边，没有模糊、没有 specular、没有内外阴影。
     */
    val controlFlatBorder: Color = Color(0x24FFFFFF),

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
