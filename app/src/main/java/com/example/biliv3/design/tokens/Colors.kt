package com.example.biliv3.design.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 颜色令牌。
 *
 * ## 两套主题
 *
 * - [DarkColors] —— **主态**。按 `AGENTS.md` §5.2「影院级深色」。
 * - [LightColors] —— 补充态，跟随系统浅色。
 *
 * ## 三条使用铁律（浅色下最要紧）
 *
 * 1. **品牌粉 `#FB7299` 只能做背景**（CTA 底、选中胶囊、进度条）。
 *    它在白底上只有 2.6:1，**做文字不达标**。粉字必须用 [textBrandSafe]。
 * 2. **品牌蓝 `#00A1D6` 只能做图标/装饰**。白底 3.0:1，小字不达标。
 * 3. **次要文字不要直接用 [textSecondary]**（浅色下 2.9:1）。
 *    正文级次要信息用 [textSecondarySafe]。
 *
 * 深色下这三条自动缓解（粉在近黑底上约 7.6:1），但代码仍统一走安全变体，
 * 免得两套主题各写一份判断。
 *
 * 所有色值**禁止在页面里硬编码**，一律通过 `BiliTheme.colors` 取。
 */
@Immutable
data class BiliColors(
    // ---- 品牌真值（不改）----
    /** 品牌粉。**仅用于背景**：CTA 底、选中胶囊、进度条已播段。 */
    val brandPrimary: Color,
    /** 品牌蓝。**仅用于图标/装饰**。 */
    val brandSecondary: Color,

    // ---- 品牌派生（hover / 按压 / 弱化底）----
    val brandPrimaryHover: Color,
    val brandPrimaryActive: Color,
    /** 选中背景、Tag 底（约 10% 透明度）。 */
    val brandPrimaryDim: Color,
    val brandSecondaryHover: Color,

    // ---- 文字安全变体（对比度达标，做文字必须用这些）----
    /** 粉字唯一合法值。浅色 5.1:1 / 深色 7.6:1。 */
    val textBrandSafe: Color,
    /** 蓝字唯一合法值。 */
    val textLinkSafe: Color,
    /** 次要文字标准值。播放量、UP 名、说明文字都用它。 */
    val textSecondarySafe: Color,

    // ---- 基础层 ----
    val bgBase: Color,
    val bgCard: Color,
    val bgHover: Color,

    // ---- 文字 ----
    val textPrimary: Color,
    /** 原始次要色。浅色下 2.9:1，**只允许用于 ≥18px 大字或纯装饰**。 */
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnBrand: Color,
    val textOnMedia: Color,

    // ---- 描边 ----
    val borderHairline: Color,
    val borderStrong: Color,

    // ---- 遮罩 ----
    /** 时长角标底、封面压字底。官方值 `#66000000`（alpha 40%）。 */
    val overlayCover: Color,
    /** 弹窗遮罩。 */
    val scrim: Color,

    // ---- 状态 ----
    val stateError: Color,
    val stateSuccess: Color,
    val stateLive: Color,

    // ---- 互动激活色 ----
    /**
     * 投币激活金。
     *
     * ⚠️ 首版这两个色是 `InteractionBar.kt` 的文件级私有常量
     * （`COIN_COLOR` / `FAVORITE_COLOR`），**不随深浅主题切换**。
     * 深色下金色偏暗、与背景对比不足。提升为令牌后两套主题各取一值。
     */
    val accentCoin: Color,
    /** 收藏激活黄。 */
    val accentFavorite: Color,
    /** 投币弹窗的高亮金（比 [accentCoin] 更亮，用于选中态与确认按钮）。 */
    val accentCoinBright: Color,
    /** 投币弹窗上压在金色底的深色文字。 */
    val onAccentCoin: Color,

    // ---- 播放器 / 媒体遮罩族 ----
    //
    // ⚠️ 首版这些值散落在 6 个文件里，共 **28 处**硬编码：
    // `0x73000000`（3 处，弹层遮罩）、`0x8C000000`（4 处，播放器圆钮底）、
    // `0xCC000000`（4 处）、`0x66000000`（2 处）、`0x59000000`（2 处）、
    // `0x66FFFFFF`（1 处，进度条未播段）、`0xCCFFFFFF`（1 处）。
    //
    // 同一语义出现 4 个不同 alpha（73/8C/B3/CC）是纯手写漂移，
    // 收敛为下面 5 个令牌。

    /** 弹层遮罩（Dialog / 底部面板）。替代 `0x73000000` / `0x8C000000`。 */
    val scrimPanel: Color,
    /** 播放器浮层圆钮底。替代 `0x8C000000` / `0xCC000000` / `0x59000000`。 */
    val overlayControl: Color,
    /** 播放器浮层上的图标/文字色。替代裸 `Color.White`。 */
    val onOverlay: Color,
    /** 进度条未播轨道。替代 `0x66FFFFFF`。 */
    val trackInactive: Color,
    /** 封面压字渐变终点。替代 `0x66000000` / `0x99000000`。 */
    val gradientMediaEnd: Color,

    /** 弹幕描边。替代 `DanmakuLayer.kt` 的 `0xCC000000`。 */
    val danmakuStroke: Color,
    /** 字幕条底。替代 `SubtitleOverlay.kt` 的 `0xB3000000`。 */
    val subtitleScrim: Color,
    /**
     * 播放器底色。
     *
     * ⚠️ **两个主题都是纯黑** `#000000`，这是有意为之：
     * 画面周围任何灰都是干扰，只有纯黑能让视频"浮"出来。
     * 全站不用纯黑（防 OLED 拖影），但播放器区域**不滚动**，
     * 不存在拖影问题 —— 这条例外只给播放器。
     */
    val playerBackground: Color,

    /**
     * 二维码承载底。
     *
     * ⚠️ **两个主题同值**（近白）—— 这是有意的：
     * 二维码是给相机识别的，深底白码在部分扫码器上识别率低。
     * 不能随主题变暗。
     */
    val qrSurface: Color,

    /**
     * 压在 [qrSurface] 上的图标 / 文字色。
     *
     * ⚠️ **两个主题同值**（深灰）。不能复用 `textPrimary` ——
     * 深色主题下 `textPrimary` 是近白色，压在白底二维码上会看不见。
     */
    val onQrSurface: Color,

    // ---- 榜单名次 ----
    val rankFirst: Color,
    val rankSecond: Color,
    val rankThird: Color,

    // ---- 骨架屏 ----
    val skeletonBase: Color,
    val skeletonHighlight: Color,

    /**
     * 图片占位底色（封面/头像加载前）。
     *
     * ## ⚠️ 为什么不能复用 [skeletonBase]
     *
     * 浅色主题下 `skeletonBase = #EDEEF0`、页面底 `bgBase = #F4F5F7` ——
     * 两者几乎同色。封面区在图片加载前后**看起来就是一块白底**，
     * 这正是用户报告的「搜索列表闪烁后变白底」的视觉成因。
     *
     * 本令牌在两个主题下都**比页面底略深**且保持中性：
     * 图片出现时有"内容填充"的观感，而不是"白块淡入"。
     */
    val coverPlaceholder: Color,

    /**
     * 头像占位底色。
     *
     * ## ⚠️ 为什么与 [coverPlaceholder] 分开
     *
     * 头像是**圆形**，封面是**圆角矩形**。同样的色值在圆里显得更浅
     * （圆形面积小、边缘抗锯齿占比高），实测头像用封面色会"飘"。
     * 本令牌比 [coverPlaceholder] 再深一档。
     *
     * ## ⚠️ 不要再用 [skeletonBase] 当头像占位
     *
     * 首版全项目有 7 处头像占位写的是 `skeletonBase` —— 它是**骨架屏**
     * 语义（带微光动画），拿来当静态占位会出现"头像区在呼吸"的错觉。
     */
    val avatarPlaceholder: Color,

    // ---- 分区入口（**只有两个令牌，不再是 12 色彩虹**）----
    /**
     * 分区图标色。
     *
     * 全 12 个分区**共用同一个强调色**，靠图标形状区分语义。
     * 早期实现给 12 个分区各配一个高饱和色（动画粉/番剧橙/国创黄/音乐紫/
     * 舞蹈玫红…），排成一条彩虹，观感偏"儿童风"。按 §3.1「内容优先 +
     * 官方级精度」，分区入口是**导航**不是内容，不该抢视觉。
     */
    val categoryAccent: Color,
    /** 分区图标圆底（低饱和弱化底，不再是实心高饱和圆）。 */
    val categorySurface: Color,
)

/**
 * 深色主题 —— **主态**。
 *
 * 对应 `AGENTS.md` §5.2 的官方夜面色阶：
 * `Ga1 → bg/base`、`Ga0 → bg/elevated`、`Ga11 → bg/raised`、`Ga10 → text/primary`…
 *
 * ⚠️ 刻意**不用纯 `#000000`** —— OLED 上纯黑滚动会有拖影（smear），
 * `#0A0B0C` 既有 OLED 省电优势又不拖影。
 */
val DarkColors = BiliColors(
    // ================= C 方案（社区感 · 暖调深色）=================
    //
    // 深色侧与浅色侧**不是简单反相**，有三处必须不同：
    //
    // 1. **底与卡片整体加暖** —— 冷灰 → 紫红偏移的暖灰（`#121114` / `#1C1A1F`）。
    // 2. **品牌粉提亮** —— `#FB7299` → `#FF8FB0`。
    //    深色底上粉需要更亮才"跳"得出来；浅色侧反而要压深（见 LightColors）。
    //    两侧取不同值是有意的，不是笔误。
    // 3. **投影失效** —— 深色下 `Modifier.shadow()` 是黑底黑影，**不可见**。
    //    C 方案的分层在深色下改由 `borderHairline`（10% 白）承担，
    //    调用点需按主题分支（见 `BiliCard`）。

    // 品牌真值（深色下提亮）
    brandPrimary = Color(0xFFFF8FB0),
    brandSecondary = Color(0xFF00A1D6),

    // 派生
    brandPrimaryHover = Color(0xFFFFA5C0),
    brandPrimaryActive = Color(0xFFE0708F),
    brandPrimaryDim = Color(0x29FF8FB0),
    brandSecondaryHover = Color(0xFF33B5E0),

    // 文字安全变体（深色下原色本身即达标，但保持令牌语义一致）
    textBrandSafe = Color(0xFFFF8FB0),
    textLinkSafe = Color(0xFF4FC3F7),
    textSecondarySafe = Color(0xFFA8A2B0),

    // 基础层（暖调深色）
    bgBase = Color(0xFF121114),
    bgCard = Color(0xFF1C1A1F),
    bgHover = Color(0xFF26232B),

    // 文字（暖白）
    textPrimary = Color(0xFFF0EDF2),
    textSecondary = Color(0xFFA8A2B0),
    textTertiary = Color(0xFF7A7484),
    textOnBrand = Color(0xFF1A1014),
    textOnMedia = Color(0xFFFFFFFF),

    // 描边（深色下**承担分层职责**，比首版 8% 提到 10%）
    borderHairline = Color(0x1AFFFFFF),
    borderStrong = Color(0x33FFFFFF),

    // 遮罩
    overlayCover = Color(0x66000000),
    scrim = Color(0x99000000),

    // 状态
    stateError = Color(0xFFFF5C5C),
    stateSuccess = Color(0xFF4ADE80),
    stateLive = Color(0xFFFF8FB0),

    // 互动激活色（深色下提亮）
    accentCoin = Color(0xFFFFC44D),
    accentFavorite = Color(0xFFFFD666),
    accentCoinBright = Color(0xFFE0A96D),
    onAccentCoin = Color(0xFF2A1B0C),

    // 播放器 / 媒体遮罩族
    scrimPanel = Color(0x99000000),
    overlayControl = Color(0xCC000000),
    onOverlay = Color(0xFFFFFFFF),
    trackInactive = Color(0x66FFFFFF),
    gradientMediaEnd = Color(0x99000000),
    danmakuStroke = Color(0xCC000000),
    subtitleScrim = Color(0xB3000000),
    playerBackground = Color(0xFF000000),
    qrSurface = Color(0xE6FFFFFF),
    onQrSurface = Color(0xFF18191C),

    // 榜单
    rankFirst = Color(0xFFFF8FB0),
    rankSecond = Color(0xFFFFB027),
    rankThird = Color(0xFFFFC53D),

    // 骨架屏（暖调）
    skeletonBase = Color(0xFF201D24),
    skeletonHighlight = Color(0xFF2A2730),

    // 图片占位：比 bgBase(#121114) 略亮，图片出现时有"填充"感
    coverPlaceholder = Color(0xFF2A272E),

    // 头像占位：比封面占位再深一档（圆形显浅，见 BiliColors 说明）
    avatarPlaceholder = Color(0xFF34313A),

    // 分区
    categoryAccent = Color(0xFFFF8FB0),
    categorySurface = Color(0x1FFF8FB0),
)

/**
 * 浅色主题 —— 补充态。
 *
 * ## 相对首版的三处收敛（去"糖果感"）
 *
 * 1. **分区不再用 12 个高饱和色** —— 改为 [categoryAccent] 单色 + [categorySurface] 弱化底
 * 2. **时长角标底 alpha 55% → 40%** —— 对齐官方 `#66000000`
 * 3. 其余保持首版语义（品牌粉只做背景、文字走安全变体）
 */
val LightColors = BiliColors(
    // ================= C 方案（社区感 · 暖调）=================
    //
    // ## 相对首版的四处偏移
    //
    // 1. **底与文字整体加暖** —— 从冷灰（`#F4F5F7` / `#18191C`）移到暖灰
    //    （`#F7F4F6` / `#1A171C`），R 通道略高于 B 通道，观感更"有人味"。
    // 2. **品牌粉压深一档** —— `#FB7299` → `#E8578A`。
    //    首版粉在**白底 + 白字**下只有 2.63:1（代码旧注释写 4.0:1 是算错的），
    //    连大字号的 AA（3:1）都不达。压深后到 3.4:1，主按钮文字可读性明显改善。
    // 3. **新增卡片分层** —— C 的核心。浅色下投影真正可见（深色下不可见，见 DarkColors）。
    // 4. **次要文字加暖** —— `#61666D` → `#6B6470`，保持 5.8:1 达标。

    // 品牌真值（压深一档）
    brandPrimary = Color(0xFFE8578A),
    brandSecondary = Color(0xFF00A1D6),

    // 派生
    brandPrimaryHover = Color(0xFFF06E9B),
    brandPrimaryActive = Color(0xFFD14475),
    brandPrimaryDim = Color(0x1AE8578A),
    brandSecondaryHover = Color(0xFF33B5E0),

    // 文字安全变体（暖调）
    textBrandSafe = Color(0xFFC2416B),
    textLinkSafe = Color(0xFF0077A8),
    textSecondarySafe = Color(0xFF6B6470),

    // 基础层（暖调）
    bgBase = Color(0xFFF7F4F6),
    bgCard = Color(0xFFFFFFFF),
    bgHover = Color(0xFFF2EDF0),

    // 文字（暖调）
    textPrimary = Color(0xFF1A171C),
    textSecondary = Color(0xFF948C99),
    textTertiary = Color(0xFFA79FAD),
    textOnBrand = Color(0xFFFFFFFF),
    textOnMedia = Color(0xFFFFFFFF),

    // 描边（C：浅色下投影为主，描边退为辅助，透明度比首版更轻）
    borderHairline = Color(0x0D000000),
    borderStrong = Color(0x1A000000),

    // 遮罩
    overlayCover = Color(0x66000000),
    scrim = Color(0x8C000000),

    // 状态
    stateError = Color(0xFFF56C6C),
    stateSuccess = Color(0xFF4CAF50),
    stateLive = Color(0xFFE8578A),

    // 互动激活色（浅色下压深，保证白底可读）
    accentCoin = Color(0xFFD98A1F),
    accentFavorite = Color(0xFFC9911A),
    accentCoinBright = Color(0xFFD9A05B),
    onAccentCoin = Color(0xFF2A1B0C),

    // 播放器 / 媒体遮罩族（浅色下遮罩略淡，避免"糊成一片黑"）
    scrimPanel = Color(0x8C000000),
    overlayControl = Color(0xB3000000),
    onOverlay = Color(0xFFFFFFFF),
    trackInactive = Color(0x66FFFFFF),
    gradientMediaEnd = Color(0x99000000),
    danmakuStroke = Color(0xCC000000),
    subtitleScrim = Color(0xB3000000),
    playerBackground = Color(0xFF000000),
    qrSurface = Color(0xE6FFFFFF),
    onQrSurface = Color(0xFF18191C),

    // 榜单
    rankFirst = Color(0xFFE8578A),
    rankSecond = Color(0xFFFF9F43),
    rankThird = Color(0xFFFFC53D),

    // 骨架屏（暖调）
    skeletonBase = Color(0xFFEDE8EC),
    skeletonHighlight = Color(0xFFF7F3F6),

    // 图片占位：比 bgBase(#F7F4F6) 明显深一档，避免"白底"观感
    coverPlaceholder = Color(0xFFE5DFE4),

    // 头像占位：比封面占位再深一档（圆形显浅，见 BiliColors 说明）
    avatarPlaceholder = Color(0xFFDAD3D9),

    // 分区：单色 + 弱化底（原为 12 色彩虹）
    categoryAccent = Color(0xFFC2416B),
    categorySurface = Color(0xFFFDF1F5),
)
