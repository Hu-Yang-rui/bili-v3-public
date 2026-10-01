package com.example.biliv3.design.tokens

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 间距令牌。4/8 节奏。
 *
 * 页面里出现的间距**必须**来自这里，不写任意值。
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
    val pageMobile = 12.dp

    /**
     * 网格列间距。
     *
     * ⚠️ C 方案：手机端 8dp → 12dp。
     *
     * 首版卡片没有容器，8dp 就够（靠留白分组）。C 方案每张卡是**独立的实体容器**
     * 且带 3dp 投影 —— 8dp 间距会让相邻两张卡的投影互相压住，看起来"挤在一起"。
     * 12dp 是实测下限（投影 3dp × 2 张 + 呼吸空间）。
     */
    val gridGutterDesktop = 20.dp
    val gridGutterTablet = 16.dp
    val gridGutterMobile = 12.dp

    /**
     * 网格行间距。
     *
     * ## ⚠️ C 方案：分组方式变了，这个值也要跟着变
     *
     * 首版靠「卡片底部 padding 8dp + 行距 12dp = 20dp」分组（见 git 历史），
     * 因为卡片**没有容器**，只能靠空白把相邻行分开。
     *
     * C 方案卡片自带容器 + 投影，**分组由容器边缘完成**，
     * 行距只需给出"两行之间不粘连"的最小值即可，不必再叠加底部留白。
     *
     * 因此：行距 12dp 保持，但卡片内的 `padding(bottom = Space.x2)` 已移除
     * （改成了四边均匀的 `CARD_INNER_PADDING`）。
     * 实际行间空白 = 12dp（行距）+ 8dp（卡片下内边距）= 20dp，与首版持平。
     */
    val gridRowDesktop = 24.dp
    val gridRowTablet = 18.dp
    val gridRowMobile = 12.dp

    /**
     * 区块纵向间距。
     *
     * 手机端 20dp -> 12dp，与网格节奏对齐，避免"区块之间比卡片之间还松"
     * 造成的松散感。
     */
    val sectionDesktop = 28.dp
    val sectionTablet = 20.dp
    val sectionMobile = 12.dp

    /** 最小触摸目标 */
    val minTouchTarget = 48.dp
}

/**
 * 圆角令牌。
 *
 * ## ⚠️ C 方案：封面 4dp → 12dp（**推翻了首版决策**）
 *
 * 首版的论证（见 git 历史与本文件旧注释）是：
 * `AGENTS.md` §5.2 记录了「首页卡片圆角」的正向调整，
 * 官方 `corner_radius = 4dp`，12dp 会显"玩具感"。
 *
 * **那个论证的前提是「封面直接浮在页面底上、没有卡片容器」。**
 * C 方案给封面加了卡片容器（`Elevation.card` 投影 + 16dp 面板圆角），
 * 前提变了，结论也要跟着变：
 *
 * - 4dp 封面放进 16dp 卡片 → 两种半径差 4 倍，边缘"打架"
 * - 同族圆角需要接近的曲率（12 / 16 / 12 是一组）
 *
 * ## ⚠️ 这两个值必须一起改
 *
 * `cover` 与 `card` 是**成对**的：要卡片就都要大圆角，
 * 不要卡片就把 `cover` 改回 4dp、`card` 改回 12dp。
 * 单独改一个会出现上面说的"打架"。
 */
object Radius {
    /** 视频封面。**与 [card] 成对** —— 见上方说明。 */
    val cover = 12.dp
    /** 面板 / 容器（Banner、侧栏白卡、视频卡片）。 */
    val card = 16.dp
    /** 按钮、输入框。与卡片同族。 */
    val button = 12.dp
    /** 时长角标。官方 `dimen/corner_radius` = 2dp。**不随 C 变** —— 它压在封面上，小圆角更利落。 */
    val badge = 2.dp
    /** 小标签（非封面角标）。 */
    val tag = 6.dp
    /** 分区胶囊、头像 */
    val pill = 999.dp
    /** 小缩略图（直播/话题） */
    val thumb = 12.dp
}

/** 尺寸令牌。 */
object Sizes {
    /** 顶栏高度：桌面 / 平板 / 移动 */
    val topBarDesktop = 64.dp
    val topBarTablet = 56.dp
    val topBarMobile = 52.dp

    /** 分类 Tab 条高度。官方值。 */
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
     * 官方 `viewAspectRatio = 1.6`（16:10），`AGENTS.md` §5.2 明确要求。
     * 首版用了 16:9（1.778），封面偏高、单屏可见卡片数偏少。
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

/** 字号令牌。 */
object FontSize {
    /** 20/28 700 —— 分区标题、Banner 主标题 */
    val display = 20.sp
    val displayLine = 28.sp

    /** 16/24 700 —— 区块标题 */
    val titleLg = 16.sp
    val titleLgLine = 24.sp

    /** 15/22 600 —— 视频标题 */
    val titleMd = 15.sp
    val titleMdLine = 22.sp

    /** 14/22 400 —— 正文、输入 */
    val body = 14.sp
    val bodyLine = 22.sp

    /** 13/20 400 —— UP 昵称、榜单条目 */
    val bodySm = 13.sp
    val bodySmLine = 20.sp

    /** 12/18 400 —— 播放量、数据栏（官方 12sp） */
    val label = 12.sp
    val labelLine = 18.sp

    /** 10 —— 时长角标（官方 `T10`）。 */
    val badge = 10.sp

    /** 11 —— 直播角标 */
    val micro = 11.sp
}
