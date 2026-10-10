package com.example.biliv3.design.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.example.biliv3.design.v3.V3DarkColors
import com.example.biliv3.design.v3.V3LightColors

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

    // ---------------- 空降助手：进度条上的跳过区间 ----------------
    /**
     * 跳过区间在**进度条上**的标记色。
     *
     * ## 为什么单独立一个语义色，而不是复用现有色
     *
     * 进度条上已经有两种含义，不能借色：
     * - [brandPrimary]（粉）= **已播**
     * - [trackInactive]（半透明白）= **未播**
     *
     * 跳过区间是**第三种**信息（"这一段会被自动跳过"），借任何一个都会
     * 变成误导：用粉会被读成"已播到这里"，用白会被读成"还没播"。
     *
     * 取 [accentTerminal]（终端青）是刻意的：它在本项目里的语义就是
     * **"系统给出的技术标注"**（读数、参数、状态），而跳过区间正是
     * 社区数据给出的时间标注，不是内容本身。面积也符合
     * §5.1「极客元素是标点，不是正文」—— 它只是轨道上的几段细带。
     */
    val skipSegment: Color,
    /** 播放位置**正处于**跳过区间内时的强调色（比 [skipSegment] 更亮）。 */
    val skipSegmentActive: Color,

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

    // ---------------- 第三方渠道品牌色（明确豁免）----------------
    //
    // ## ⚠️ 为什么这 4 个值**允许**写死（§5.2「页面内零硬编码」的豁免）
    //
    // 它们**不是本项目的设计决策，而是别人的品牌真值** ——
    // 微信绿 / 朋友圈绿 / 下载紫 / 复制蓝。这类颜色：
    //
    // 1. **不能随我们的主题变**：改了就不是微信的绿了。
    //    分享面板里图标形状是通用轮廓（规避商标），**颜色才是识别位**
    // 2. **不在我们的调色体系内**：与品牌粉 / 极客青无任何关系，
    //    若混进上面那些角色位，会误导成"这是我们的一个语义色"
    //
    // 所以集中定义在此（而不是散在页面里）：既"值可查、一处可改"，
    // 又诚实标注它们是**外来值**。
    //
    // 📌 历史：这 4 个值原先直接写在 `ItemMoreMenu.SHARE_CHANNELS` 里。
    // `AGENTS.md` §11.1 记为待办 ——「可论证豁免，但需在代码里写明豁免理由，
    // 否则与『忘了改』无法区分」。v1.2.4 补上理由并上移到令牌层。
    val channelWechat: Color,
    val channelMoments: Color,
    val channelDownload: Color,
    val channelCopyLink: Color,
)

/**
 * 深色（主态）。
 *
 * ---
 *
 * # 🔴 v3 全量 UI 重构：本表已改为**新设计系统的投影**
 *
 * 旧的调色板（冷调蓝灰 `#0E1116` + 三级卡片色）**已废弃**。
 * 现在这里的每个值都取自 `design/v3/V3Colors.kt` ——
 * **新系统是唯一真值来源**，本表只是为了让尚未迁移的页面继续编译。
 *
 * ## 为什么保留这张表，而不是直接删掉
 *
 * 全量重构要迁移 95 个文件。如果一次性改掉 `BiliTheme.colors.xxx` 的
 * 所有调用点，中间态无法编译、也无法逐页验证。
 *
 * 所以策略是：**新系统提供真值 → 旧表映射到新值 → 逐页迁移 → 迁完删表**。
 * 这样任何时刻 App 都能跑、能截图对比。
 *
 * ## 映射原则
 *
 * | 旧角色 | 新归属 |
 * |---|---|
 * | `brandPrimary`（粉，做交互色） | → **交互蓝** `V3.brand`（粉色降为品牌标识） |
 * | `bgBase` / `bgCard` / `bgHover` | → 背景三级 `bgPrimary` / `bgSecondary` / `bgTertiary` |
 * | `textPrimary` / `textSecondarySafe` | → 标签四级 `labelPrimary` / `labelSecondary` |
 * | `borderHairline` | → `separator` |
 * | `accentTerminal`（极客青） | → 保留（承担"技术标注"语义，新系统继续用） |
 *
 * ⚠️ **新代码请直接用 `BiliV3.colors`**，不要再往本表加字段。
 */
val DarkColors = BiliColors(
    // ---- 品牌 ----
    // 🔴 交互色从"粉"改为"蓝"（见 V3Colors.brand 的说明）。
    //    粉色降为品牌标识色（brandSecondary 承载）。
    brandPrimary = V3DarkColors.brand,
    brandSecondary = V3DarkColors.brandBili,
    brandPrimaryHover = Color(0xFF3FA9FF),
    brandPrimaryActive = Color(0xFF0077D4),
    brandPrimaryDim = V3DarkColors.brandDim,
    brandSecondaryHover = Color(0xFFFFA3BF),

    // ---- 极客点缀（保留：它承担"技术标注"语义，新系统继续用）----
    accentTerminal = Color(0xFF4FD1C5),
    accentTerminalDim = Color(0x1F4FD1C5),
    gridLine = Color(0x0FFFFFFF),

    // ---- 文字安全变体 ----
    textBrandSafe = V3DarkColors.brandBiliText,
    textLinkSafe = V3DarkColors.brandText,
    textSecondarySafe = Color(0xFFB8B8C0),

    // ---- 背景三级（新系统的投影）----
    bgBase = V3DarkColors.bgPrimary,
    bgCard = V3DarkColors.bgSecondary,
    bgHover = V3DarkColors.bgTertiary,
    surfaceElevated = V3DarkColors.bgSecondaryElevated,

    // ---- 文字 ----
    textPrimary = V3DarkColors.labelPrimary,
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFF636366),
    textOnBrand = V3DarkColors.labelOnBrand,
    textOnMedia = V3DarkColors.labelOnMedia,

    // ---- 描边 ----
    borderHairline = V3DarkColors.separator,
    borderStrong = Color(0x3DFFFFFF),

    // ---- 遮罩 ----
    overlayCover = V3DarkColors.overlay,
    scrim = V3DarkColors.scrim,
    scrimPanel = V3DarkColors.scrim,
    overlayControl = V3DarkColors.controlOverlay,
    onOverlay = Color(0xFFFFFFFF),
    trackInactive = V3DarkColors.trackInactive,
    gradientMediaEnd = Color(0x99000000),

    // ---- 状态 ----
    stateError = V3DarkColors.stateError,
    stateSuccess = V3DarkColors.stateSuccess,
    stateLive = V3DarkColors.stateLive,

    // ---- 空降助手：进度条上的跳过区间 ----
    skipSegment = Color(0xE64FD1C5),
    skipSegmentActive = Color(0xFF9BF5EC),

    // ---- 互动 ----
    accentCoin = V3DarkColors.accentCoin,
    accentFavorite = V3DarkColors.accentFavorite,
    accentCoinBright = Color(0xFFFFD166),
    onAccentCoin = V3DarkColors.onAccentCoin,

    // ---- 播放器 ----
    danmakuStroke = V3DarkColors.danmakuStroke,
    subtitleScrim = Color(0xB3000000),
    playerBackground = V3DarkColors.playerBackground,

    // ---- 二维码 ----
    qrSurface = Color(0xFFF7F8FA),
    onQrSurface = Color(0xFF14181F),

    // ---- 榜单 ----
    rankFirst = Color(0xFFFF6B6B),
    rankSecond = Color(0xFFFFA94D),
    rankThird = Color(0xFFFFD43B),

    // ---- 骨架 ----
    skeletonBase = Color(0xFF1C1C1E),
    skeletonHighlight = Color(0xFF2C2C2E),

    // ---- 占位 ----
    coverPlaceholder = V3DarkColors.coverPlaceholder,
    avatarPlaceholder = V3DarkColors.avatarPlaceholder,

    // ---- 分区 ----
    categoryAccent = Color(0xFF8E8E93),
    categorySurface = Color(0xFF1C1C1E),

    // ---- 第三方渠道品牌色（他方品牌真值）----
    channelWechat = V3DarkColors.channelWechat,
    channelMoments = V3DarkColors.channelMoments,
    channelDownload = V3DarkColors.channelDownload,
    channelCopyLink = V3DarkColors.channelCopyLink,
)

/**
 * 浅色（**v1.6.9 起为默认**）。
 *
 * ---
 *
 * # 这是旧令牌表，取值**投影自 [V3LightColors]**
 *
 * 旧表 `BiliColors` 已进入维护期：新代码一律用 `BiliV3.colors`，
 * 这里只为**尚未迁移完的消费者**保留。所以策略是
 * **能投影就投影**（跟着新系统走，避免两处漂移），
 * 只有新系统里没有对应字段的才写死值。
 *
 * ⚠️ **写死值必须按白底重算** —— 不能照抄深色表的十六进制。
 * 深色表的很多值在暗底上达标，在白底上会严重不足
 * （例如 `textSecondary = #8E8E93` 在白底只有 **3.0:1**，
 * 而它承担"次要文字"职责，需要 ≥4.5:1）。
 */
val LightColors = BiliColors(
    // ---- 品牌 ----
    brandPrimary = V3LightColors.brand,
    brandSecondary = V3LightColors.brandBili,
    // hover / active 是旧表独有的两个态（新系统用 brandDim 表达）
    brandPrimaryHover = Color(0xFF0062CC),
    brandPrimaryActive = Color(0xFF004C99),
    brandPrimaryDim = V3LightColors.brandDim,
    brandSecondaryHover = Color(0xFFE91E63),

    // ---- 极客点缀 ----
    accentTerminal = V3LightColors.accentTerminal,
    accentTerminalDim = V3LightColors.accentTerminalDim,
    gridLine = V3LightColors.gridLine,

    // ---- 文字安全变体 ----
    textBrandSafe = V3LightColors.brandBiliText,
    textLinkSafe = V3LightColors.brandText,
    // ⚠️ 深色表是 #B8B8C0（浅灰，压暗底）；白底上必须用深灰：#5A5A60 ≈ 6.2:1
    textSecondarySafe = Color(0xFF5A5A60),

    // ---- 背景三级（新系统的投影）----
    bgBase = V3LightColors.bgPrimary,
    bgCard = V3LightColors.bgSecondary,
    bgHover = V3LightColors.bgTertiary,
    surfaceElevated = V3LightColors.bgSecondaryElevated,

    // ---- 文字 ----
    textPrimary = V3LightColors.labelPrimary,
    // ⚠️ 深色表 #8E8E93（白底仅 3.0:1）→ 浅色用 #6C6C70（≈4.9:1）
    textSecondary = Color(0xFF6C6C70),
    textTertiary = Color(0xFF8E8E93),
    textOnBrand = V3LightColors.labelOnBrand,
    textOnMedia = V3LightColors.labelOnMedia,

    // ---- 描边 ----
    borderHairline = V3LightColors.separator,
    // ⚠️ 深色表是"白 24%"（提亮边缘）；浅色必须反过来"黑 12%"（压出边界）
    borderStrong = Color(0x1F000000),

    // ---- 遮罩（压在内容上，与主题无关）----
    overlayCover = V3LightColors.overlay,
    scrim = V3LightColors.scrim,
    scrimPanel = V3LightColors.scrim,
    overlayControl = V3LightColors.controlOverlay,
    onOverlay = Color(0xFFFFFFFF),
    trackInactive = V3LightColors.trackInactive,
    gradientMediaEnd = Color(0x99000000),

    // ---- 状态 ----
    stateError = V3LightColors.stateError,
    stateSuccess = V3LightColors.stateSuccess,
    stateLive = V3LightColors.stateLive,

    // ---- 空降助手：进度条上的跳过区间 ----
    skipSegment = V3LightColors.skipSegment,
    skipSegmentActive = V3LightColors.skipSegmentActive,

    // ---- 互动 ----
    accentCoin = V3LightColors.accentCoin,
    accentFavorite = V3LightColors.accentFavorite,
    // ⚠️ 深色表是"更亮的金"（#FFD166，压暗底）；浅色下要更深才看得清
    accentCoinBright = Color(0xFFD99400),
    onAccentCoin = V3LightColors.onAccentCoin,

    // ---- 播放器（压在画面上，与主题无关）----
    danmakuStroke = V3LightColors.danmakuStroke,
    subtitleScrim = Color(0xB3000000),
    playerBackground = V3LightColors.playerBackground,

    // ---- 二维码（恒近白底）----
    qrSurface = Color(0xFFF7F8FA),
    onQrSurface = Color(0xFF14181F),

    // ---- 榜单 ----
    rankFirst = V3LightColors.rankFirst,
    rankSecond = V3LightColors.rankSecond,
    rankThird = V3LightColors.rankThird,

    // ---- 骨架 ----
    skeletonBase = V3LightColors.skeletonBase,
    skeletonHighlight = V3LightColors.skeletonHighlight,

    // ---- 占位 ----
    coverPlaceholder = V3LightColors.coverPlaceholder,
    avatarPlaceholder = V3LightColors.avatarPlaceholder,

    // ---- 分区 ----
    categoryAccent = V3LightColors.categoryAccent,
    categorySurface = V3LightColors.bgSecondary,

    // ---- 第三方渠道品牌色（他方品牌真值）----
    channelWechat = V3LightColors.channelWechat,
    channelMoments = V3LightColors.channelMoments,
    channelDownload = V3LightColors.channelDownload,
    channelCopyLink = V3LightColors.channelCopyLink,
)


/*
 * 🔴 主题：浅色为默认（v1.6.9 起）
 *
 * ## 为什么 v1.1.3 的"只做深色"被推翻
 *
 * 原来的理由：浅色下静态页面（首页/搜索/我的）背后是**纯色底，
 * 没有东西可模糊** —— 玻璃拟态在纯色背景上只能靠"比底色更白"来假装层次。
 *
 * **该理由针对的是"把玻璃用在页面背景上"** —— 而本项目从未那样用：
 * 玻璃只用于**浮动层**（底部导航 / Sheet / Dialog / 播放器控件），
 * 底下**始终有内容在滚**（列表、封面、画面），所以浅色下同样有东西可模糊。
 *
 * 原来的顾虑不成立，浅色主题已加回（[LightColors] / [V3LightColors]）。
 *
 * ⚠️ 两套表都必须维护：`V3Colors` 是 data class，新增令牌时
 * **构造参数缺一不可**，所以漏掉一套会直接编译失败（这是有意的设计）。
 */
