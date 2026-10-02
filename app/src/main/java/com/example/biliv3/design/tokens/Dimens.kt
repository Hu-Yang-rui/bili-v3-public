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

    /** 最小触摸目标。 */
    val minTouchTarget = 48.dp
}

/**
 * 圆角令牌。
 *
 * ## 一套收敛的圆角族（12 / 16 / 999）
 *
 * | 用途 | 值 | 理由 |
 * |---|---|---|
 * | 封面 / 卡片 / 按钮 | 12dp | 主体圆角，只保留**一个**主值 |
 * | 大面板 / 底部面板 | 16dp | 比卡片大一档，表达"层级更高" |
 * | 角标 | 4dp | 压在封面上，小圆角更利落 |
 * | 胶囊 / 头像 | 999dp | 完全圆形 |
 *
 * ⚠️ **刻意砍掉了旧的 `button = 12 / card = 16 / cover = 12 / thumb = 12` 多值并存** ——
 * 那些值实际只差 4dp，肉眼几乎不可辨，却让每次改动都要想"这里该用哪个"。
 * 收敛成"卡片=12、面板=16"两个语义值。
 */
object Radius {
    /** 封面、卡片、按钮、输入框 —— **主体圆角，最常用**。 */
    val card = 12.dp

    /** 大面板、底部面板、弹窗 —— 层级更高一档。 */
    val panel = 16.dp

    /** 时长角标、微标签。 */
    val badge = 4.dp

    /** 胶囊、头像、圆形按钮。 */
    val pill = 999.dp

    // ---- 兼容别名（保持既有调用点可用，语义同 card）----
    /** @deprecated 用 [card]。保留仅为兼容既有调用点。 */
    val cover = card
    /** @deprecated 用 [card]。 */
    val button = card
    /** @deprecated 用 [badge]。 */
    val tag = badge
    /** @deprecated 用 [card]。 */
    val thumb = card
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
