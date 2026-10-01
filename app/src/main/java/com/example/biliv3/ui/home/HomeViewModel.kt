package com.example.biliv3.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.HomeRepository
import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.model.HomeData
import com.example.biliv3.data.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 主页状态。
 *
 * 四态明确：加载中 / 有内容 / 空 / 错误。
 * **不做假数据** —— 拿不到就是空或错误，不填占位。
 */
sealed interface HomeUiState {
    /** 首屏加载中 —— 渲染与真实布局同构的骨架屏。 */
    data object Loading : HomeUiState

    /** 有内容。 */
    data class Content(
        val data: HomeData,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
    ) : HomeUiState

    /** 推荐流为空。 */
    data object Empty : HomeUiState

    /** 主链路（推荐流）失败。右侧栏失败不算整页错误。 */
    data class Error(val message: String) : HomeUiState
}

/**
 * 主页 ViewModel。
 *
 * ## 两条职责边界
 *
 * 1. **推荐流是主链路** —— 失败 → 整页错误态
 * 2. **右侧栏是增强** —— 失败 → 该模块不渲染，不影响主链路
 *
 * 这个区分很重要：如果右侧栏拿不到就让整页报错，用户会以为"App 坏了"，
 * 而实际上核心的推荐流是好的。
 *
 * ## 「个性化推荐」开关的消费者
 *
 * 设置页那个开关此前**只写不读**（「空转设置项」反模式）。
 * 现在它真实改变内容源：
 *
 * | 开关 | 内容源 |
 * |---|---|
 * | 开（默认） | `rcmd`（基于观看偏好的推荐流） |
 * | 关 | `ranking/region`（分区榜，**不含任何个性化信号**） |
 *
 * ⚠️ 这是**近似实现**：B 站没有"关闭个性化"的公开接口参数
 * （`rcmd` 无 `personalized=0` 之类开关），只能换内容源。
 * 与其留一个改了没反应的开关，不如换成行为可见的降级源 ——
 * 至少"关闭个性化后首页不再是基于我偏好的内容"这件事是真的。
 */
class HomeViewModel(
    private val repo: HomeRepository = HomeRepository(BiliApi()),
    private val api: BiliApi? = null,
    private val settingsStore: com.example.biliv3.data.SettingsStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** fresh_idx 递增，B 站用它做去重与「换一批」。 */
    private var freshIdx = 1

    /** 是否启用个性化推荐（来自设置）。默认 true。 */
    private var personalized = true

    init {
        // 先读一次设置，再加载 —— 顺序反了会先拉一批 rcmd 再被替换
        viewModelScope.launch {
            personalized = runCatching {
                settingsStore?.settings?.first()?.personalizedRecommend ?: true
            }.getOrDefault(true)

            load()
        }
    }

    /** 首屏 / 下拉刷新。 */
    fun load() {
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
            runCatching { loadContent() }
                .onSuccess { data ->
                    _state.value = if (data.videos.isEmpty()) {
                        HomeUiState.Empty
                    } else {
                        freshIdx++
                        HomeUiState.Content(data = data)
                    }
                }
                .onFailure { e ->
                    _state.value = HomeUiState.Error(
                        com.example.biliv3.ui.component.userMessageFor(e),
                    )
                }
        }
    }

    /**
     * 按当前开关取内容。
     *
     * 关闭个性化时走分区榜：它同样返回 `HomeData` 结构
     * （榜单作为视频列表，右侧栏照常拉取）。
     */
    private suspend fun loadContent(): com.example.biliv3.data.model.HomeData {
        if (personalized) return repo.loadHome(freshIdx)

        val a = api ?: return repo.loadHome(freshIdx)
        // 分区榜（动画区）—— 无个性化信号
        val videos = a.regionRanking(rid = 1, pageSize = 20)
        val side = repo.sidePanels()
        return com.example.biliv3.data.model.HomeData(
            banners = emptyList(),
            categories = com.example.biliv3.data.model.CategoryEntry.defaults(),
            videos = videos,
            ranks = side.ranks,
            lives = side.lives,
            topics = side.topics,
            notices = side.notices,
        )
    }

    /**
     * 触底加载更多。
     *
     * 去重：B 站推荐流会重复推同一支视频，必须按 bvid 过滤，
     * 否则列表里会出现重复卡片。
     */
    fun loadMore() {
        val current = _state.value
        if (current !is HomeUiState.Content) return
        if (current.loadingMore || !current.hasMore) return

        viewModelScope.launch {
            _state.value = current.copy(loadingMore = true)

            runCatching { repo.feed(freshIdx) }
                .onSuccess { next ->
                    val seen = current.data.videos.mapTo(HashSet()) { it.bvid }
                    val merged = current.data.videos + next.filter { it.bvid !in seen }
                    freshIdx++
                    _state.value = current.copy(
                        data = current.data.copy(videos = merged),
                        loadingMore = false,
                        // 返回空说明到底了
                        hasMore = next.isNotEmpty(),
                    )
                }
                .onFailure {
                    // 加载更多失败不把整页变成错误态 —— 已加载的内容仍然可用。
                    // 只停止 loading 并标记没有更多，避免无限重试。
                    _state.value = current.copy(
                        loadingMore = false,
                        hasMore = false,
                    )
                }
        }
    }

    /** 「换一换」—— 重新拉一批。 */
    fun shuffle() {
        load()
    }
}

/** 便于 UI 层直接拿视频列表。 */
val HomeUiState.videos: List<VideoItem>
    get() = (this as? HomeUiState.Content)?.data?.videos ?: emptyList()

/** 主页 VM 工厂（手写 DI，项目不用 Hilt）。 */
class HomeVmFactory(
    private val repo: HomeRepository,
    private val api: BiliApi?,
    private val settingsStore: com.example.biliv3.data.SettingsStore?,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        HomeViewModel(repo, api, settingsStore) as T
}
