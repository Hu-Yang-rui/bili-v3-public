package com.example.biliv3.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.biliv3.data.InteractionRepository
import com.example.biliv3.data.VideoRepository
import com.example.biliv3.data.subtitle.SubtitleRepository

/**
 * [VideoDetailViewModel] 的工厂。
 *
 * 详情页的 ViewModel 需要 `bvid` 参数，而 Compose 的 `viewModel()`
 * 默认只支持无参构造，因此必须显式提供工厂。
 *
 * ## 为什么不引入 Hilt
 *
 * 单用户项目、页面数量有限，手工工厂只有这几行。
 * Hilt 会引入注解处理器，显著拖慢构建（当前已有 KSP 在跑），
 * 收益不抵成本（见 `AGENTS.md` §3 架构约定）。
 *
 * @param interactionRepo 传 null 时互动功能整体不可用（如预览/测试环境）。
 */
class VideoDetailViewModelFactory(
    private val bvid: String,
    private val videoRepo: VideoRepository? = null,
    private val interactionRepo: InteractionRepository? = null,
    private val subtitleRepo: SubtitleRepository? = null,
    private val danmakuRepo: com.example.biliv3.data.danmaku.DanmakuRepository? = null,
    private val videoshotRepo: com.example.biliv3.data.VideoshotRepository? = null,
    private val commentRepo: com.example.biliv3.data.CommentRepository? = null,
    /** 空降助手（第三方可跳过片段）。传 null 时该功能整体不启用。 */
    private val sponsorBlockRepo: com.example.biliv3.data.SponsorBlockRepository? = null,
    private val authHeader: String = "",
    /** 收藏状态全局广播。传 null 时用独立实例（预览环境够用）。 */
    private val favoritesSync: com.example.biliv3.data.FavoritesSync? = null,
    /** 播放进度持久化（在线断点续播）。 */
    private val progressStore: com.example.biliv3.data.PlaybackProgressStore? = null,
    /** 服务端历史上报（多端续播）。 */
    private val libraryRepo: com.example.biliv3.data.LibraryRepository? = null,
    /** 设置（「保存观看历史」开关的消费者）。 */
    private val settingsStore: com.example.biliv3.data.SettingsStore? = null,
    /** AI 总结（v1.6.3）。传 null 时该功能整体不启用。 */
    private val aiSummaryRepo: com.example.biliv3.data.ai.AiSummaryRepository? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(VideoDetailViewModel::class.java)) {
            "未知的 ViewModel: ${modelClass.name}"
        }
        return VideoDetailViewModel(
            bvid = bvid,
            repo = videoRepo ?: VideoRepository(),
            interactions = interactionRepo,
            subtitleRepo = subtitleRepo,
            danmakuRepo = danmakuRepo,
            videoshotRepo = videoshotRepo,
            commentRepo = commentRepo,
            sponsorBlockRepo = sponsorBlockRepo,
            authHeader = authHeader,
            favoritesSync = favoritesSync ?: com.example.biliv3.data.FavoritesSync(),
            progressStore = progressStore,
            libraryRepo = libraryRepo,
            settingsStore = settingsStore,
            aiSummaryRepo = aiSummaryRepo,
        ) as T
    }
}
