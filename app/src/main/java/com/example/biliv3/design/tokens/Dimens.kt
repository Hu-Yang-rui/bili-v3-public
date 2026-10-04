package com.example.biliv3.design.tokens

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 间距令牌。**4dp 节奏**。
 *
 * 页面里出现的间距必须来自这里，不写任意值。
 */
object Space {
    /**
     * **微间距：2dp**。
     *
     * ## 为什么在 4dp 节奏之外还要留一个 2dp
     *
     * 4dp 节奏是给"块与块之间"用的。但有些地方是**行内紧贴**：
     * - 图标与其下方的一行小字（互动栏、标签）
     * - 标题与紧邻的副标题
     *
     * 这些位置用 4dp 会显得"松、不聚拢"，2dp 才是对的。
     *
     * 实测全项目有 **29 处** `Spacer(2.dp)` —— 说明这不是个别需求，
     * 而是一个被反复手写的真实档位。所以提为令牌，
     * 避免继续散落魔法数字（也便于将来整体调）。
     *
     * ⚠️ 不要因为它"不是 4 的倍数"就把它并进 [x1] —— 语义不同。
     */
    val micro = 2.dp

    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp
    val x10 = 40.dp
    val x12 = 48.dp

    /** 页面水平内边距：桌面 / 平板 / 移动 */
    val pageDesktop = 24.dp
    val pageTablet = 20.dp
    val pageMobile = 16.dp

    /** 网格列间距。 */
    val gridGutterDesktop = 20.dp
    val gridGutterTablet = 16.dp
    val gridGutterMobile = 12.dp

    /** 网格行间距。 */
    val gridRowDesktop = 24.dp
    val gridRowTablet = 18.dp
    val gridRowMobile = 12.dp

    /** 区块纵向间距。 */
    val sectionDesktop = 28.dp
    val sectionTablet = 20.dp
    val sectionMobile = 16.dp

    /**
     * **紧凑纵向内边距**。
     *
     * 实测 10 处 `padding(vertical = 2.dp)` —— 全部用在
     * "一行文字/图标，要紧凑但不想贴边"的场景：
     * 标签行、控制条、字幕条、评论内的元信息行。
     *
     * 与 [micro] 的区别：
     * - [micro] 是**两个元素之间**的间距（Spacer）
     * - 本值是**单个元素自身**的内边距（padding）
     *
     * 语义不同，不能混用 —— 一个是"间隔"，一个是"呼吸"。
     */
    val compactVertical = 2.dp

    /** 最小触摸目标。 */
    val minTouchTarget = 48.dp

    /**
     * **小标签内边距**。
     *
     * ## 为什么单独立一个令牌
     *
     * 实测全项目有 **7 处**完全相同的 `padding(horizontal = 4.dp, vertical = 1.dp)` ——
     * 全部用在"压在封面/内容上的小标签"（时长角标、直播角标、分区角标）。
     *
     * 这已经是**一个稳定的视觉规格**，不是随手写的数字：
     * - 横向 4dp：让标签不贴边
     * - 纵向 **1dp**：刻意压到最小 —— 标签要"薄"，纵向给多了会显笨重
     *
     * 提为令牌后，7 处一次性对齐，将来调标签密度只改一处。
     */
    val tagHorizontal = 4.dp
    val tagVertical = 1.dp

    /**
     * **紧凑水平内边距（6dp）** —— 微标签 / 胶囊按钮的左右呼吸。
     *
     * ## 为什么立这一档（原来是 21 处手算）
     *
     * 实测有 **21 处**写的是 `Space.x1 + 2.dp`（= 6dp），分布在 9 个文件里：
     * 微标签、小胶囊按钮、"编辑/收起"这类行内动作。
     *
     * 这类写法有两个问题：
     * 1. **规格不可见**：6dp 这个值在令牌表里查不到，
     *    想统一调"小控件的左右呼吸"得先全局搜算术式
     * 2. **语义说不清**：`Space.x1 + 2.dp` 读起来是"4 加 2"，
     *    而不是"紧凑控件的水平内边距"
     *
     * 它是 [x1](4) 与 [x2](8) 之间**刻意**的一档：
     * 小标签用 x1 会显挤、用 x2 会显松，6dp 正好。
     *
     * ⚠️ 只用于**小面积控件**（标签、胶囊）。区块级间距仍走 [x3]/[x4]。
     */
    val compactHorizontal = 6.dp

    /**
     * **列表行纵向内边距（10dp）** —— 行与行之间的呼吸。
     *
     * ## 为什么立这一档（原来是 3 处 `Space.x2 + 2.dp`）
     *
     * 用在"整行可点的列表项"（选择器选项行、侧栏条目、空态内容区）。
     *
     * 与 [compactHorizontal] 的区别是**轴向与用途**：
     * - [compactHorizontal] 是**小控件的左右**（标签、胶囊）
     * - 本值是**列表行的上下**（决定一屏能放几行）
     *
     * 10dp 的取舍：8dp 行显挤、12dp 一屏少一行，10dp 是列表密度与呼吸的平衡点。
     */
    val rowVertical = 10.dp

    /**
     * **细描边 / 分隔线宽度**。
     *
     * 与 [micro] 的区别：micro 是"元素之间的距离"，这里是"线本身的粗细"。
     * 语义不同，不能混用 —— 哪天要把所有间距调大，不该把线也一起调粗。
     */
    val hairline = 1.dp

    /** 进度条 / 细轨道的视觉高度。 */
    val trackHeight = 2.dp

    /**
     * **Tab 选中下划线的高度（3dp）**。
     *
     * ## 为什么立这一档（原来是 4 处 `3.dp`）
     *
     * 分区 Tab / 番剧类型 Tab / 登录方式 Tab / 排行榜分区 Tab
     * **四个地方**都手写了 `.height(3.dp)`，且上下文完全相同：
     *
     * ```
     * Box(Modifier.width(20.dp).height(3.dp)
     *     .clip(RoundedCornerShape(Radius.badge))
     *     .background(if (selected) colors.brandPrimary else Color.Transparent))
     * ```
     *
     * 与 [trackHeight] 的区别是**语义**：track 是"轨道"（进度条底），
     * 本值是"**选中标记**"（Tab 下划线）。两者数值不同（2 vs 3），
     * 将来要调下划线粗细时，不该把进度条也一起改。
     */
    val tabIndicator = 3.dp
}

/**
 * 圆角令牌。
 *
 * ## 🔴 只有两类圆角是"合法"的
 *
 * | 类别 | 值 | 用在哪 |
 * |---|---|---|
 * | **交互元素** | [interactive] 4dp | 按钮 / 输入框 / 开关 / 可点行 |
 * | 圆形 | [pill] 999dp | 头像、圆形图标按钮 |
 *
 * 其余一律**直角**（列表项、图片、封面、内容块）—— 见 §5.1 硬规则 2。
 *
 * ## 为什么把 `button` 改名成 `interactive`
 *
 * 旧名 `button` 的语义是"按钮用的圆角"，于是**任何看着像按钮的东西**都拿它来用
 * （可点行、Tab 项、分享格子…），值还是卡片时代的 12dp。
 * 名字换成 `interactive` 后，判断标准从"像不像按钮"变成
 * **"它是不是交互元素"** —— 这才是 §5.2 真正要表达的规则。
 *
 * 同时把 12dp 收敛到 4dp：无卡片架构下 12dp 是"卡片圆角"的残留，
 * 4dp 才是"控件圆角"，两者在深色下观感差别明显（12dp 显软、显旧）。
 *
 * ## 仍然保留的两个例外
 *
 * - [panel] 16dp —— 底部面板 / 浮层。它们是**独立成块的浮起面**，
 *   不是页面内元素，直角会与"从底部升起"的语义冲突。
 * - [card] 12dp —— `BiliCard` / `Glass` 的默认值（玻璃与浮层专用，§3.2）。
 *   ⚠️ 页面里**不要再新增** `card` 用法，它只服务那两个原语。
 */
object Radius {
    /**
     * **交互元素唯一圆角（4dp）**。
     *
     * 按钮 / 输入框 / 开关 / 整行可点的列表项 —— 一切"能点的东西"。
     */
    val interactive = 4.dp

    /** 角标、微标签（与 [interactive] 同值，语义区分：这是"压在某物上的小标签"）。 */
    val badge = 4.dp

    /**
     * **小控件圆角（6dp）** —— 复选框、字幕底、内嵌小徽标。
     *
     * ## 为什么单独立一档，而不是写 `badge + 2.dp`
     *
     * 代码里曾出现 `Radius.badge + 1.dp`(5dp) 与 `Radius.badge + 2.dp`(6dp) ——
     * 这类写法有两个问题：
     * 1. **值不在令牌表里**：5dp/6dp 无处可查，调"控件圆角"时不知道该改哪
     * 2. **语义说不清**：`badge + 2.dp` 读起来是"比角标大一点"，
     *    而不是"这是个 6dp 的小控件"
     *
     * 现在收敛成一个有名字的档位。三者关系：
     * `badge`(4) < `control`(6) < `panel`(16)。
     *
     * ⚠️ 它仍属**交互/小控件**范畴，不要拿它给列表项或图片用（那是直角）。
     */
    val control = 6.dp

    /** 大面板、底部面板、弹窗 —— 独立浮起面。 */
    val panel = 16.dp

    /** 玻璃 / 浮层原语的默认值。⚠️ 页面里不要新增用法，见类文档。 */
    val card = 12.dp

    /** 胶囊、头像、圆形按钮。 */
    val pill = 999.dp
}

/** 尺寸令牌。 */
object Sizes {
    /** 顶栏高度：桌面 / 平板 / 移动 */
    val topBarDesktop = 64.dp
    val topBarTablet = 56.dp
    val topBarMobile = 52.dp

    /** 分类 Tab 条高度。 */
    val categoryTabBar = 44.dp

    /** Banner 高度 */
    val bannerDesktop = 300.dp
    val bannerTablet = 240.dp
    val bannerMobile = 160.dp

    /** 底部导航（仅移动端） */
    val bottomNav = 56.dp

    /** 右侧栏宽度 */
    val sidePanel = 288.dp

    /** 搜索框 */
    val searchHeight = 40.dp
    val searchWidthDesktop = 320.dp

    /** 分区图标 */
    val categoryIcon = 48.dp

    /**
     * 视频封面宽高比。
     *
     * 官方 `viewAspectRatio = 1.6`（16:10）。
     */
    const val coverAspectRatio = 1.6f

    /** 视频卡片 */
    val upAvatar = 20.dp
    val durationBadgeHeight = 18.dp

    /** 直播缩略图 */
    val liveThumbWidth = 96.dp
    val liveThumbHeight = 54.dp

    /** 图标尺寸 */
    val iconSm = 14.dp
    val iconMd = 18.dp
    val iconLg = 20.dp
    val iconXl = 24.dp

    /**
     * **状态圆点** —— 「直播中」角标里的呼吸点、未读红点。
     *
     * ## 为什么立这一档（原来是 `5.dp` / `8.dp` 手写）
     *
     * 这类点是**状态指示**，不是图标也不是装饰：
     * - [dotSm] 5dp —— 角标**内部**的点（旁边有文字，点只做"活着"的暗示）
     * - [dotLg] 8dp —— 独立压在图上的点（未读红点，没有文字可依，要够显眼）
     *
     * 与 [iconSm] 的区别：图标有笔画细节、需要 14dp 才看得清；
     * 实心点只需"能被看见"，5~8dp 就够 —— 用图标档位会过大。
     */
    val dotSm = 5.dp
    val dotLg = 8.dp
}

/**
 * 字体令牌。
 *
 * ---
 *
 * ## 两族字体：正文字体 + 等宽字体
 *
 * | 族 | 用途 |
 * |---|---|
 * | [FontFamilies.ui]（系统默认） | 全部正文、标题、按钮 |
 * | [FontFamilies.mono]（等宽） | **数字读数**：时间轴、计数、码率、分辨率、错误码 |
 *
 * ⚠️ 等宽字体**只用于数字与技术信息**，不用于正文 ——
 * 整段中文用等宽会非常难读，那是"为了风格牺牲可读性"。
 *
 * 例：`02:47 / 12:35`（时间轴）、`1920x1080`（分辨率）、`E-10403`（错误码）
 */
object FontFamilies {
    /** 正文字体（系统默认，跟随用户字体设置）。 */
    val ui: FontFamily = FontFamily.Default

    /**
     * 等宽字体。
     *
     * `Monospace` 在各平台都映射到系统等宽字体（Android 上是 Roboto Mono /
     * Droid Sans Mono），**无需打包字体文件**，零体积代价。
     */
    val mono: FontFamily = FontFamily.Monospace
}

/**
 * 字号阶梯。
 *
 * ## 为什么用「阶梯」而不是「一堆数值」
 *
 * 每个字号都有明确**用途**，页面里按用途取，不按"看着差不多"取。
 * 这样改一次阶梯，全站比例自动跟着变。
 */
object FontSize {
    /** 20/28 —— 大标题（Banner、分区标题） */
    val display = 20.sp
    val displayLine = 28.sp

    /** 17/24 —— 页面标题 */
    val titleLg = 17.sp
    val titleLgLine = 24.sp

    /** 15/22 —— 视频标题、卡片主文字 */
    val titleMd = 15.sp
    val titleMdLine = 22.sp

    /** 14/21 —— 正文、输入 */
    val body = 14.sp
    val bodyLine = 21.sp

    /** 13/19 —— UP 昵称、列表次要行 */
    val bodySm = 13.sp
    val bodySmLine = 19.sp

    /** 12/17 —— 播放量、数据栏、标签 */
    val label = 12.sp
    val labelLine = 17.sp

    /** 11 —— 微标签、时间戳 */
    val micro = 11.sp

    /** 10 —— 时长角标 */
    val badge = 10.sp

    /**
     * 等宽读数专用字号。
     *
     * 与 [label] 同尺寸，单独命名是为了让"这是读数"这件事在代码里可见 ——
     * 也方便将来整体调（比如低端设备放大读数）。
     */
    val monoReadout = 12.sp
    val monoReadoutLine = 16.sp

    /** 字重常量（避免页面里散落 `FontWeight.Bold` 字面量）。 */
    val weightRegular = FontWeight.Normal
    val weightMedium = FontWeight.Medium
    val weightSemiBold = FontWeight.SemiBold
    val weightBold = FontWeight.Bold
}
