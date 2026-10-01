package com.example.biliv3.data.model

/** 首页 Banner 运营位。 */
data class BannerItem(
    val id: Long,
    val title: String,
    val imageUrl: String,
    val linkUrl: String,
)

/** 热门榜单条目。 */
data class RankItem(
    val rank: Int,
    val bvid: String,
    val title: String,
    val cover: String,
    val hotScore: Long,
) {
    /** 走全应用统一的封面构造（原先这里私有默认 160，是封面不一致的来源之一）。 */
    fun coverUrl(width: Int = CoverUrls.DEFAULT_COVER_WIDTH): String =
        CoverUrls.cover(cover, width)
}

/** 正在直播条目。 */
data class LiveItem(
    val roomId: Long,
    val title: String,
    val cover: String,
    val anchorName: String,
    val online: Int,
) {
    fun coverUrl(width: Int = CoverUrls.DEFAULT_COVER_WIDTH): String =
        CoverUrls.cover(cover, width)
}

/** 话题活动。 */
data class TopicItem(
    val id: Long,
    val title: String,
    val cover: String,
    val joinCount: Int,
) {
    fun coverUrl(width: Int = CoverUrls.DEFAULT_COVER_WIDTH): String =
        CoverUrls.cover(cover, width)
}

/** 公告条目。 */
data class NoticeItem(
    val id: Long,
    val text: String,
)

/**
 * 主页聚合状态。
 *
 * 四个右侧栏模块**各自独立** —— 任一模块失败或为空时整块不渲染，
 * 不影响其他模块（这是"不做假数据占位"的具体落地）。
 */
data class HomeData(
    val banners: List<BannerItem> = emptyList(),
    val categories: List<CategoryEntry> = CategoryEntry.defaults(),
    val videos: List<VideoItem> = emptyList(),
    val ranks: List<RankItem> = emptyList(),
    val lives: List<LiveItem> = emptyList(),
    val topics: List<TopicItem> = emptyList(),
    val notices: List<NoticeItem> = emptyList(),
)

/**
 * 分区入口。
 *
 * 12 个分区是**固定结构**（不依赖接口），图标为自绘图形 + 主题色底，
 * 不使用官方专有素材。
 */
data class CategoryEntry(
    val key: String,
    val name: String,
    val rid: Int,
) {
    companion object {
        fun defaults(): List<CategoryEntry> = listOf(
            CategoryEntry("douga", "动画", 1),
            CategoryEntry("bangumi", "番剧", 13),
            CategoryEntry("guochuang", "国创", 167),
            CategoryEntry("music", "音乐", 3),
            CategoryEntry("dance", "舞蹈", 129),
            CategoryEntry("game", "游戏", 4),
            CategoryEntry("technology", "科技", 188),
            CategoryEntry("life", "生活", 160),
            CategoryEntry("kichiku", "鬼畜", 119),
            CategoryEntry("fashion", "时尚", 155),
            CategoryEntry("ent", "娱乐", 5),
            CategoryEntry("movie", "影视", 181),
        )
    }
}
