package com.example.biliv3.design.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 颜色令牌。
 *
 * ---
 *
 * ## 设计语言：现代冷调中性 + 克制的极客点缀
 *
 * | 层 | 做法 |
 * |---|---|
 * | **基调** | 冷调中性灰（略带蓝），现代、干净、有技术感 |
 * | **品牌** | B 站粉 / 蓝保留为品牌真值（这是 B 站客户端） |
 * | **点缀** | 极少量终端青（[accentTerminal]），只用在**提示符 / 读数 / 光标 / 微标签** |
 *
 * ---
 *
 * ## ⚠️ 极客元素的使用边界（必须遵守）
 *
 * **允许**（克制点缀，见 `ui/component/Geek.kt`）：
 * - 等宽字体做**数字读数**（时间轴、计数、码率）
 * - `$` / `>` 提示符前缀（仅限加载/空/错误态）
 * - 方块光标（仅限"正在输入/进行中"）
 * - 极淡网格底（仅限空态）
 * - 终端青做**小面积**强调（一条 1dp 线、一个点）
 *
 * **严禁**：
 * - 代码雨 / 矩阵雨 / 满屏滚动字符
 * - 老式终端堆砌（黑底亮绿字铺满）
 * - 过度霓虹、荧光色、刺眼发光
 * - 扫描线 / 故障艺术 / 像素风
 *
 * 一句话：**极客元素是"标点"，不是"正文"** —— 它的总面积不应超过一屏的 5%。
 *
 * ---
 *
 * ## 三条对比度铁律
 *
 * 1. [brandPrimary] 只做**背景**（CTA 底、选中胶囊、进度条）。做文字必须用 [textBrandSafe]
 * 2. [brandSecondary] 只做**图标 / 装饰**
 * 3. 正文级次要信息用 [textSecondarySafe]，不要直接用 [textSecondary]
 *
 * 页面里**禁止硬编码色值**，一律通过 `BiliTheme.colors` 取。
 */
@Immutable
data class BiliColors(
    // ---------------- 品牌 ----------------
    /** 品牌粉。**仅用于背景**。 */
    val brandPrimary: Color,
    /** 品牌蓝。**仅用于图标 / 装饰**。 */
    val brandSecondary: Color,
    val brandPrimaryHover: Color,
    val brandPrimaryActive: Color,
    /** 选中底 / Tag 底（低透明度）。 */
    val brandPrimaryDim: Color,
    val brandSecondaryHover: Color,

    // ---------------- 极客点缀（克制使用）----------------
    /**
     * 终端青。**只做小面积强调**：提示符、光标、1dp 分隔线、微标签。
     *
     * ⚠️ 不要用它做大面积背景或正文 —— 那是"廉价赛博朋克"的起点。
     */
    val accentTerminal: Color,
    /** 终端青的弱化版（做底、做描边）。 */
    val accentTerminalDim: Color,
    /** 极淡网格线（空态 / 骨架屏底纹）。 */
    val gridLine: Color,

    // ---------------- 文字安全变体 ----------------
    /** 粉字唯一合法值。 */
    val textBrandSafe: Color,
    /** 蓝字唯一合法值。 */
    val textLinkSafe: Color,
    /** 次要文字标准值（播放量、UP 名、说明）。 */
    val textSecondarySafe: Color,

    // ---------------- 基础层（三级层次）----------------
    /** 页面底。 */
    val bgBase: Color,
    /** 卡片 / 面板。 */
    val bgCard: Color,
    /** 悬浮 / 输入框 / 展开区。 */
    val bgHover: Color,
    /** 更高一层（弹窗、底部面板）。 */
    val surfaceElevated: Color,

    // ---------------- 文字 ----------------
    val textPrimary: Color,
    /** 原始次要色。**只允许 ≥18px 大字或纯装饰**。 */
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnBrand: Color,
    val textOnMedia: Color,

    // ---------------- 描边 ----------------
    /** 极淡分隔（1dp）。 */
    val borderHairline: Color,
    /** 明确边界（输入框、聚焦）。 */
    val borderStrong: Color,

    // ---------------- 遮罩 ----------------
    /** 时长角标底、封面压字底。 */
    val overlayCover: Color,
    /** 弹窗遮罩。 */
    val scrim: Color,
    /** 弹层遮罩（Dialog / 底部面板）。 */
    val scrimPanel: Color,
    /** 播放器浮层圆钮底。 */
    val overlayControl: Color,
    /** 播放器浮层上的图标 / 文字。 */
    val onOverlay: Color,
    /** 进度条未播轨道。 */
    val trackInactive: Color,
    /** 封面压字渐变终点。 */
    val gradientMediaEnd: Color,

    // ---------------- 状态 ----------------
    val stateError: Color,
    val stateSuccess: Color,
    val stateLive: Color,

    // ---------------- 互动激活色 ----------------
    val accentCoin: Color,
    val accentFavorite: Color,
    val accentCoinBright: Color,
    val onAccentCoin: Color,

    // ---------------- 播放器 / 媒体 ----------------
    /** 弹幕描边。 */
    val danmakuStroke: Color,
    /** 字幕条底。 */
    val subtitleScrim: Color,
    /**
     * 播放器底色。
     *
     * ⚠️ **两个主题都是纯黑** —— 画面周围任何灰都是干扰。
     * 全站不用纯黑（防 OLED 拖影），但播放器区域不滚动，无拖影问题。
     */
    val playerBackground: Color,

    // ---------------- 二维码 ----------------
    /**
     * 二维码承载底。
     *
     * ⚠️ **两个主题同值**（近白）—— 深底白码在部分扫码器上识别率低。
     */
    val qrSurface: Color,
    val onQrSurface: Color,

    // ---------------- 榜单 ----------------
    val rankFirst: Color,
    val rankSecond: Color,
    val rankThird: Color,

    // ---------------- 骨架 / 占位 ----------------
    val skeletonBase: Color,
    val skeletonHighlight: Color,
    val coverPlaceholder: Color,
    val avatarPlaceholder: Color,

    // ---------------- 分区 ----------------
    val categoryAccent: Color,
    val categorySurface: Color,
)

/**
 * 深色（主态）。
 *
 * 冷调中性灰底：`#0E1116` 略带蓝，比纯黑更有"材质感"，
 * 又比暖调（旧版的 `#121114`）更现代、更技术。
 */
val DarkColors = BiliColors(
    // 品牌（B 站真值，不改）
    brandPrimary = Color(0xFFFF8FB0),
    brandSecondary = Color(0xFF00A1D6),
    brandPrimaryHover = Color(0xFFFFA3BF),
    brandPrimaryActive = Color(0xFFE87A9C),
    brandPrimaryDim = Color(0x26FF8FB0),
    brandSecondaryHover = Color(0xFF33B4E0),

    // 极客点缀：低饱和青，只做小面积
    accentTerminal = Color(0xFF4FD1C5),
    accentTerminalDim = Color(0x1F4FD1C5),
    gridLine = Color(0x0FFFFFFF),

    // 文字安全变体
    textBrandSafe = Color(0xFFFF9EBB),
    textLinkSafe = Color(0xFF4FC3E8),
    textSecondarySafe = Color(0xFFA0A9B8),

    // 三级层次
    bgBase = Color(0xFF0E1116),
    bgCard = Color(0xFF171B22),
    bgHover = Color(0xFF1F2530),
    surfaceElevated = Color(0xFF232A35),

    // 文字
    textPrimary = Color(0xFFE8ECF2),
    textSecondary = Color(0xFF8B95A5),
    textTertiary = Color(0xFF66707F),
    textOnBrand = Color(0xFF1A1014),
    textOnMedia = Color(0xFFFFFFFF),

    // 描边
    borderHairline = Color(0x14FFFFFF),
    borderStrong = Color(0x2EFFFFFF),

    // 遮罩
    overlayCover = Color(0x66000000),
    scrim = Color(0x99000000),
    scrimPanel = Color(0x99000000),
    overlayControl = Color(0xCC000000),
    onOverlay = Color(0xFFFFFFFF),
    trackInactive = Color(0x3DFFFFFF),
    gradientMediaEnd = Color(0x99000000),

    // 状态
    stateError = Color(0xFFF87171),
    stateSuccess = Color(0xFF4ADE80),
    stateLive = Color(0xFFFF5C8A),

    // 互动
    accentCoin = Color(0xFFFFC44D),
    accentFavorite = Color(0xFFFFD666),
    accentCoinBright = Color(0xFFFFD166),
    onAccentCoin = Color(0xFF2B1E00),

    // 播放器
    danmakuStroke = Color(0xCC000000),
    subtitleScrim = Color(0xB3000000),
    playerBackground = Color(0xFF000000),

    // 二维码
    qrSurface = Color(0xFFF7F8FA),
    onQrSurface = Color(0xFF14181F),

    // 榜单
    rankFirst = Color(0xFFFF6B6B),
    rankSecond = Color(0xFFFFA94D),
    rankThird = Color(0xFFFFD43B),

    // 骨架
    skeletonBase = Color(0xFF1C222B),
    skeletonHighlight = Color(0xFF262E39),

    // 占位
    coverPlaceholder = Color(0xFF1B212A),
    avatarPlaceholder = Color(0xFF252D38),

    // 分区
    categoryAccent = Color(0xFF8B95A5),
    categorySurface = Color(0xFF1B212A),
)

/**
 * 浅色。
 *
 * 冷调近白底，与深色同一色相家族 —— 切换主题时"只是明暗变了"，
 * 不出现色相跳变。
 */
val LightColors = BiliColors(
    // 品牌
    brandPrimary = Color(0xFFE8578A),
    brandSecondary = Color(0xFF00A1D6),
    brandPrimaryHover = Color(0xFFD94A7C),
    brandPrimaryActive = Color(0xFFC43F6E),
    brandPrimaryDim = Color(0x1FE8578A),
    brandSecondaryHover = Color(0xFF0090C0),

    // 极客点缀（浅色下压深以保证对比度）
    accentTerminal = Color(0xFF0D9488),
    accentTerminalDim = Color(0x1A0D9488),
    gridLine = Color(0x0D000000),

    // 文字安全变体
    textBrandSafe = Color(0xFFC2416B),
    textLinkSafe = Color(0xFF0077A8),
    textSecondarySafe = Color(0xFF5A6472),

    // 三级层次
    bgBase = Color(0xFFF5F7FA),
    bgCard = Color(0xFFFFFFFF),
    bgHover = Color(0xFFEDF1F6),
    surfaceElevated = Color(0xFFFFFFFF),

    // 文字
    textPrimary = Color(0xFF14181F),
    textSecondary = Color(0xFF8B95A5),
    textTertiary = Color(0xFFA3ABB8),
    textOnBrand = Color(0xFFFFFFFF),
    textOnMedia = Color(0xFFFFFFFF),

    // 描边
    borderHairline = Color(0x14000000),
    borderStrong = Color(0x26000000),

    // 遮罩
    overlayCover = Color(0x66000000),
    scrim = Color(0x66000000),
    scrimPanel = Color(0x8C000000),
    overlayControl = Color(0xB3000000),
    onOverlay = Color(0xFFFFFFFF),
    trackInactive = Color(0x3DFFFFFF),
    gradientMediaEnd = Color(0x99000000),

    // 状态
    stateError = Color(0xFFDC2626),
    stateSuccess = Color(0xFF16A34A),
    stateLive = Color(0xFFE8578A),

    // 互动
    accentCoin = Color(0xFFD98A1F),
    accentFavorite = Color(0xFFC9911A),
    accentCoinBright = Color(0xFFE8A33D),
    onAccentCoin = Color(0xFFFFFFFF),

    // 播放器
    danmakuStroke = Color(0xCC000000),
    subtitleScrim = Color(0xB3000000),
    playerBackground = Color(0xFF000000),

    // 二维码
    qrSurface = Color(0xFFF7F8FA),
    onQrSurface = Color(0xFF14181F),

    // 榜单
    rankFirst = Color(0xFFE5484D),
    rankSecond = Color(0xFFD97706),
    rankThird = Color(0xFFB45309),

    // 骨架
    skeletonBase = Color(0xFFEDF1F6),
    skeletonHighlight = Color(0xFFF7F9FC),

    // 占位
    coverPlaceholder = Color(0xFFE8EDF3),
    avatarPlaceholder = Color(0xFFDDE3EB),

    // 分区
    categoryAccent = Color(0xFF5A6472),
    categorySurface = Color(0xFFEDF1F6),
)
