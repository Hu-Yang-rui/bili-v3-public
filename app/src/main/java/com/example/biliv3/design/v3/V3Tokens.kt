package com.example.biliv3.design.v3

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **BiliV3 设计系统 v3 —— 排版 / 间距 / 圆角 / 尺寸**。
 *
 * ---
 *
 * # 排版来源
 *
 * 数值取自 iOS 27 UI Kit 27.0.2 的 `typography.json`（**实测**，
 * 从 kit 的 TEXT 图层与共享文本样式读出）。
 *
 * ## 🔴 关键换算：iOS 的 `pt` → Compose 的 `sp`
 *
 * iOS 的 17pt body 与 Android 的 17sp 在**物理尺寸**上不等价
 * （Android 的 `sp` 随用户字号缩放，iOS 的 `pt` 也随 Dynamic Type 缩放，
 * 所以两者**语义相同**，可以直接按数值搬）。
 *
 * 唯一要调整的是**中文**：SF Pro 的拉丁字面比中文窄，
 * 同一字号下中文显得更大。所以中文标题档位比 iOS 原值**略收**（见各条目注释）。
 *
 * ## 与旧系统（`FontSize`）的差异
 *
 * | | 旧 | 新 |
 * |---|---|---|
 * | 档位数 | 8 档（10~20sp） | **11 档**（11~34sp，含 iOS 的 largeTitle/title1） |
 * | 命名 | `titleMd` / `bodySm`（尺寸导向） | `headline` / `subheadline`（**语义导向**） |
 * | 字重 | 5 个常量 | 保持，但**强调统一用 Semibold**（iOS 27 实测：caption 的强调是 Semibold 不是 Medium） |
 *
 * ⚠️ 命名从"尺寸"改成"语义"是有意的：`titleMd` 读不出"该用在哪"，
 * 于是页面里到处是"看着差不多就挑一个"。`headline`/`body`/`footnote`
 * 自带使用场景（这也是 iOS 的做法）。
 */
object V3Type {

    /**
     * 中文字形回落链。
     *
     * ⚠️ 拉丁字体不含中文字形，必须显式声明 `SansSerif`，
     * 否则部分设备会掉到衬线字体。
     */
    val family: FontFamily = FontFamily.SansSerif

    /** 等宽（**只用于数字读数**：时间轴、码率、错误码）。 */
    val mono: FontFamily = FontFamily.Monospace

    // ---------------------------------------------------------------------
    // 样式（数值来自 iOS 27 实测表）
    // ---------------------------------------------------------------------

    /** 34/41 —— 大标题。首页顶部、页面主标题。 */
    val largeTitle = style(34, 41, FontWeight.Bold)

    /** 28/34 —— 一级标题。 */
    val title1 = style(28, 34, FontWeight.Bold)

    /** 22/28 —— 二级标题。视频详情页标题。 */
    val title2 = style(22, 28, FontWeight.Bold)

    /** 20/25 —— 三级标题。区块标题。 */
    val title3 = style(20, 25, FontWeight.SemiBold)

    /** 17/22 —— 头条。列表行主文字、按钮。 */
    val headline = style(17, 22, FontWeight.SemiBold)

    /** 17/22 —— 正文。 */
    val body = style(17, 22, FontWeight.Normal)

    /** 16/21 —— 说明文字。 */
    val callout = style(16, 21, FontWeight.Normal)

    /** 15/20 —— 副标题。UP 名、次要行。 */
    val subheadline = style(15, 20, FontWeight.Normal)

    /** 13/18 —— 脚注。元信息、标签。 */
    val footnote = style(13, 18, FontWeight.Normal)

    /** 12/16 —— 说明 1。计数、角标文字。 */
    val caption1 = style(12, 16, FontWeight.Normal)

    /** 11/13 —— 说明 2。最小档（时长角标）。 */
    val caption2 = style(11, 13, FontWeight.Normal)

    // ---------------------------------------------------------------------
    // 强调变体
    // ---------------------------------------------------------------------

    /**
     * 强调版（Semibold）。
     *
     * ## ⚠️ 为什么强调统一用 Semibold 而不是 Medium
     *
     * iOS 27 实测记录里明确写了：`caption1` 的 emphasized 是 **Semibold**，
     * 且**确认过两次**（kit 的四个样本组 + 运行时 `SFUI-Semibold`）。
     * 旧项目里 Medium/SemiBold 混用，导致"同样重要的文字粗细不一"。
     */
    fun emphasized(base: TextStyle): TextStyle = base.copy(fontWeight = FontWeight.SemiBold)

    /** 等宽读数样式（时间轴 / 码率 / 错误码）。 */
    fun readout(size: Int = 12, weight: FontWeight = FontWeight.Medium): TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = size.sp,
        lineHeight = (size + 4).sp,
        fontWeight = weight,
    )

    /**
     * 构造一个样式。
     *
     * `letterSpacing` 按 iOS 实测表给（大字号是**负**值 —— 这是 SF Pro
     * 的视觉修正；中文同样受益，因为大字号下中文字面显得过散）。
     */
    private fun style(
        size: Int,
        line: Int,
        weight: FontWeight,
        tracking: Float = tracking(size),
    ): TextStyle = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = tracking.sp,
        // 中文行高要按"字面框"对齐，否则首行会有额外留白
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

    /** 字距（实测表：大字号负、小字号正）。 */
    private fun tracking(size: Int): Float = when {
        size >= 28 -> 0.38f
        size >= 22 -> -0.26f
        size >= 20 -> -0.45f
        size >= 17 -> -0.43f
        size >= 16 -> -0.31f
        size >= 15 -> -0.23f
        size >= 13 -> -0.08f
        size >= 12 -> 0f
        else -> 0.06f
    }
}

/**
 * **间距令牌**（8pt 网格，iOS 27 实测）。
 *
 * ---
 *
 * # 🔴 与旧系统的结构性差异
 *
 * 旧系统是 **4dp 节奏**（4/8/12/16/20/24/32/40/48），还额外造了
 * `micro(2)` / `compactHorizontal(6)` / `rowVertical(10)` / `compactVertical(2)`
 * 这些**非网格档位** —— 理由是"实测有 N 处手写了这个值"。
 *
 * 那其实是**症状**：档位不够用时，页面会自己造值。
 * 新系统按 iOS 27 的 8pt 网格重排，并把旧的那些"补丁档位"重新归类：
 *
 * | 旧档位 | 新归属 |
 * |---|---|
 * | `micro(2)` | → [hairline]（**这是线宽，不是间距**） |
 * | `compactHorizontal(6)` | → [xs]（8）+ 控件自身 padding 承担 |
 * | `rowVertical(10)` | → [sm]（12）|
 * | `compactVertical(2)` | → [hairline] 或 [xs] |
 *
 * ⚠️ **2dp 不是间距**。它是"发丝线宽度"或"紧贴修正"。
 * 把它当间距用（旧系统的 `Spacer(2.dp)` 出现 29 处）会让 8pt 网格失效 ——
 * 网格的价值就在于"任意两个间距都有明确的倍数关系"。
 */
object V3Space {
    /** 0 —— 明确表达"不要间距"（覆盖上游 padding）。 */
    val none = 0.dp

    /**
     * 2dp —— **紧贴修正**，不是间距。
     *
     * 只用于"图标与其下方一行小字"这类**必须咬合**的位置。
     * 任何"两个区块之间"的距离都不该用它。
     */
    val hairline = 2.dp

    /** 4dp —— 最小间距（图标与文字之间）。 */
    val xxs = 4.dp

    /** 8dp —— 小间距（组内元素）。 */
    val xs = 8.dp

    /** 12dp —— 中间距（列表行之间、行内分栏）。 */
    val sm = 12.dp

    /** 16dp —— 标准内容边距。**页面左右边距就是它**。 */
    val md = 16.dp

    /** 20dp —— 大间距。 */
    val lg = 20.dp

    /** 24dp —— 区块内边距。 */
    val xl = 24.dp

    /** 32dp —— 区块之间。 */
    val xxl = 32.dp

    /** 40dp —— 章节之间。 */
    val xxxl = 40.dp

    /** 48dp —— 大章节之间。 */
    val huge = 48.dp

    /** 64dp —— 页面级分隔。 */
    val giant = 64.dp

    // ---------------------------------------------------------------------
    // 语义别名（读代码时能看出"这是什么间距"）
    // ---------------------------------------------------------------------

    /**
     * **页面左右内容边距**（16dp）。
     *
     * 实测（iOS 27）：iPhone 上列表行、顶部工具栏、章节标题、分组页脚
     * **全部**用 16pt 侧边距。这是"内容边距"的唯一真值。
     */
    val contentMargin = md

    /**
     * **顶栏的横向内边距**（2dp）—— 让**图标的光学边缘**落在 [contentMargin] 上。
     *
     * ---
     *
     * ## 🔴 这个值是怎么定出来的（实测，不是估的）
     *
     * 顶栏第一个元素是**返回按钮**（`Box(size = touchMin)` = 44dp），
     * 图标（`iconLg` = 24dp）**居中**在里面，所以图标盒左右各有
     * `(44 - 24) / 2 = 10dp` 的内部留白。
     *
     * 但**图标盒 ≠ 图标画出来的形状**：`Icons.AutoMirrored.Filled.ArrowBack`
     * 的箭头在 24dp 盒子里还带一点左侧留白（字形本身的边距）。
     *
     * 实测（1080×2400，density 2.625，设置页）：
     *
     * | Row padding | 箭头最深像素起点 | 与正文（x=43）差 |
     * |---|---|---|
     * | `xs`(8dp) —— **原值** | x=59 | **−16px ≈ −6.1dp** |
     * | 6dp（推算值） | x=54 | −11px ≈ −4.2dp |
     * | **2dp（本值）** | x=43 ✅ | **0** |
     *
     * ⚠️ **所以不能只算"10dp 内部留白"** —— 那推出 6dp，实测仍偏 4.2dp。
     *    必须把**字形自身的左边距**一起算进去。
     *
     * ⚠️ **不要"顺手"改成 [contentMargin]** —— 那会让顶栏比正文**右偏 14dp**，
     *    比原来的 6dp 更明显。顶栏的对齐基准是**图标画出来的那条边**，
     *    不是 Row 的边界，也不是图标盒的边界。
     *
     * ⚠️ 如果哪天换了返回图标（字形边距不同的），**这个值要重新实测**。
     */
    val topBarMargin = 2.dp

    /**
     * **列表行左右内边距**（16dp）—— 与 [contentMargin] 同值。
     *
     * 同值是有意的：行的内边距与页面边距对齐，文字才会形成一条竖线。
     */
    val rowPadding = md

    /**
     * **列表行纵向内边距**（11dp，实测）。
     *
     * ⚠️ 行高由"内容 + 上下 padding"决定，不是固定高度 ——
     * 固定高度会在用户放大字号时截断文字（旧系统踩过）。
     * 实测 iOS 27 的行高是 52pt = 内容(30) + 11 × 2。
     */
    val rowPaddingV = 11.dp

    /** 图标与文字之间（8dp）。 */
    val iconGap = xs

    /** 头像与文字块之间（12dp）。 */
    val avatarGap = sm

    // ---------------------------------------------------------------------
    // 非间距值（线宽 / 微型尺寸）—— 🔴 这些**不是间距**
    // ---------------------------------------------------------------------
    //
    // ## 为什么放在 [V3Space] 里却明确标注"不是间距"
    //
    // 迁移旧表时发现 `Space.hairline`(1) / `Space.tabIndicator`(3) /
    // `Space.tagVertical`(1) 无处可去 —— 它们**不是"两个元素之间的距离"**，
    // 而是**线本身的粗细**或**标签自身的呼吸**。
    //
    // 旧表把它们和间距混在同一个 object 里，正是 §5.4.3 批评的
    // 「`Spacer(2.dp)` 当间距用」那类问题的根源：**语义混装**。
    //
    // 这里仍放同一处（便于查找），但**用命名区分**：带 `line` / `divider` /
    // `tag` / `tabIndicator` 的一律不是间距。
    //
    // 🔴 判据：**"把全站间距调大一档时，这些值不应该跟着变"** —— 它们不是间距。

    /**
     * **发丝线宽度（1dp）** —— 分隔线 / 描边的粗细。
     *
     * ⚠️ 与 [hairline]（2dp，紧贴修正）**语义不同**：
     * 一个是"线的粗细"，一个是"两个元素咬合的距离"。
     */
    val lineHairline = 1.dp

    /**
     * **Tab 选中下划线高度（3dp）**。
     *
     * 与 [progressTrack] 的区别是**语义**：那个是"轨道底"，
     * 这个是"**选中标记**"。要调下划线粗细时不该把进度条一起改。
     */
    val tabIndicator = 3.dp

    /**
     * **小标签内边距**（压在封面/内容上的时长角标、直播角标、分区角标）。
     *
     * 实测这是一个**稳定的视觉规格**，不是随手写的数字：
     * 横向 4dp 让标签不贴边；纵向刻意压到 1dp —— 标签要"薄"，
     * 纵向给多了会显笨重。
     */
    val tagHorizontal = xxs
    val tagVertical = 1.dp

    /**
     * **进度条轨道高度（4dp）**。
     *
     * ⚠️ iOS 27 实测是**从 6 降到 4**（不是升高）—— 细轨道更现代、
     * 不抢画面。旧表是 2dp，本次一并归到 4dp。
     */
    val progressTrack = 4.dp
}

/**
 * **圆角令牌**（iOS 27 实测表）。
 *
 * ---
 *
 * # 🔴 与旧系统的根本差异
 *
 * 旧系统的规则是「**只有交互元素有圆角，其余一律直角**」，
 * 且交互元素圆角**只有 4dp 一档**（从 12dp 收敛来的）。
 *
 * 新系统**推翻这条**：iOS 27 的实测表显示圆角是**按组件分配**的
 * （`_kitObserved` 列了 13 种不同值），不是"一条几何斜坡"。
 *
 * | 组件 | 实测圆角 |
 * |---|---|
 * | 列表行内嵌小图（30pt） | 7 |
 * | 列表头尾图（42pt） | 10 |
 * | 无边框大按钮 | 12 |
 * | 菜单/上下文菜单底 | **20** |
 * | Liquid Glass Clear | **23** |
 * | Alert / Menu / Sheet 顶 | **34** |
 * | Popover / 大 Sheet 顶 | **38** |
 * | 浮动 Sheet 底 | **58** |
 * | 胶囊（所有 bar button / 玻璃按钮 / Tab 选中） | **pill** |
 *
 * ## 为什么"直角"不再成立
 *
 * 旧系统用直角是为了**反卡片**（有圆角就"像卡片"）。这个判断在当时是对的
 * —— 但真正的问题不是圆角，是**容器**。现在无卡片已经由
 * "不用独立矩形承载内容"保证（见 [V3Surface]），圆角可以还给控件。
 *
 * ⚠️ 而且**内容仍然不套圆角容器**：图片、封面、视频块保持直角，
 * 因为它们是**内容**不是控件。圆角只出现在"能点的东西"和"浮起的层"上。
 */
object V3Radius {
    /** 0 —— 内容（图片 / 封面 / 视频块）。 */
    val none = 0.dp

    /** 4dp —— 微标签、角标。 */
    val xs = 4.dp

    /** 8dp —— 小控件（复选、小胶囊内部）。 */
    val sm = 8.dp

    /** 12dp —— 按钮（无边框大按钮实测值）。 */
    val md = 12.dp

    /** 16dp —— 中等容器。 */
    val lg = 16.dp

    /** 20dp —— 菜单 / 上下文菜单底（实测）。 */
    val xl = 20.dp

    /** 23dp —— Liquid Glass Clear（实测）。 */
    val glassClear = 23.dp

    /** 26dp —— 通知卡 / 展开内容（实测）。 */
    val xxl = 26.dp

    /** 34dp —— **Alert / Menu / Sheet 顶部 / Liquid Glass Regular**（实测）。 */
    val sheet = 34.dp

    /** 38dp —— Popover / 大 Sheet 顶（实测）。 */
    val popover = 38.dp

    /** 58dp —— 浮动 Sheet 底（实测）。 */
    val sheetFloating = 58.dp

    /** 胶囊 / 圆形。 */
    val pill = 999.dp
}

/**
 * **尺寸令牌**（iOS 27 实测）。
 *
 * ⚠️ 与旧 `Sizes` 的差异主要是**顶栏与底栏变高了** ——
 * iOS 27 把顶栏从 44 提到 **54**，底栏（含玻璃托板）到 **62**。
 */
object V3Size {
    // ---- 栏 ----
    /**
     * 顶部工具栏（54dp，实测：iOS 27 从 44 提到 54）。
     *
     * 内容区高度是 54；若要画状态栏安全区，用 `statusBars` insets 而不是这个值。
     */
    val topBar = 54.dp

    /**
     * 底部导航**玻璃托板**高度（62dp，实测）。
     *
     * ⚠️ 托板比按钮行（54）**各向外溢出 4dp** —— 这是"玻璃浮在内容之上"的
     * 视觉来源，不是 padding 写错了。
     */
    val navGlass = 62.dp

    /** 底部导航按钮行高度（54dp）。 */
    val navRow = 54.dp

    /** 底部导航项最小宽度（72dp）。 */
    val navItemMin = 72.dp

    /** 底部导航：与屏幕底边的自然间距（实测 paddingBottom 25pt）。 */
    val navBottomGap = 12.dp

    // ---- 行 ----
    /** 标准列表行高（52dp，实测）。 */
    val rowRegular = 52.dp

    /** 高列表行（68dp，实测）。 */
    val rowTall = 68.dp

    // ---- 触摸 ----
    /**
     * 最小触摸目标（44dp）。
     *
     * ⚠️ **旧系统用的是 48dp**。iOS 27 实测确认 44 是**下限**
     * （顶部栏按钮恰好 44×44），而底部工具栏按钮是 48、大按钮是 50。
     * 所以 44 是"最小"，不是"标准"—— 能大就大。
     */
    val touchMin = 44.dp

    // ---- 控件 ----
    /** 大按钮高（50dp，实测）。 */
    val buttonLarge = 50.dp
    /** 中按钮高（34dp，实测）。 */
    val buttonMedium = 34.dp
    /** 小按钮高（28dp，实测）。 */
    val buttonSmall = 28.dp

    /** 搜索框高。 */
    val searchField = 36.dp

    /** 进度条轨道高（4dp，实测：iOS 27 从 6 提到… 其实是**降到 4**）。 */
    val progressTrack = 4.dp

    /** 抓握条（Sheet 顶部把手）：58×4，实测。 */
    val grabberWidth = 58.dp
    val grabberHeight = 4.dp

    // ---- 图标 ----
    /** 14dp —— 最小图标（行内小标记）。 */
    val iconXs = 14.dp
    /** 17dp —— 列表行尾箭头 / 小图标。 */
    val iconSm = 17.dp
    /** 20dp —— 标准图标。 */
    val iconMd = 20.dp
    /** 24dp —— 大图标（导航栏）。 */
    val iconLg = 24.dp
    /** 28dp —— 特大（播放器主控制）。 */
    val iconXl = 28.dp

    // ---- 状态点 ----
    /** 5dp —— 角标内的点。 */
    val dotSm = 5.dp
    /** 8dp —— 独立红点。 */
    val dotLg = 8.dp

    // ---- 头像 ----
    val avatarXs = 20.dp
    val avatarSm = 28.dp
    val avatarMd = 40.dp
    val avatarLg = 56.dp
    val avatarXl = 72.dp

    // ---- 媒体 ----
    /** 封面比例（16:10，B 站官方 `viewAspectRatio = 1.6`）。 */
    const val coverAspect = 1.6f

    /** 时长角标高。 */
    val durationBadge = 18.dp
}
