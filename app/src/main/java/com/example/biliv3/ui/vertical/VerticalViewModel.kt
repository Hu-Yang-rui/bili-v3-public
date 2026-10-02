package com.example.biliv3.ui.vertical

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.InteractionRepository
import com.example.biliv3.data.VerticalFeedRepository
import com.example.biliv3.data.VideoRepository
import com.example.biliv3.data.api.InteractionState
import com.example.biliv3.data.model.PlayInfo
import com.example.biliv3.data.model.VideoDetail
import com.example.biliv3.data.model.VideoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 竖屏观看模式的 UI 状态。
 *
 * ## 为什么当前项要单独一份 detail
 *
 * 列表只需要 `VideoItem`（封面/标题/UP），但当前项要**播放**，
 * 需要 `PlayInfo`（取流地址）+ `VideoDetail`（简介/统计/分P）。
 * 所以进入某项时才拉详情，避免进页面就打 10 个详情请求。
 */
data class VerticalUiState(
    /** 已探明的竖屏视频列表。 */
    val items: List<VideoItem> = emptyList(),
    /** 首屏加载中（探测第一批竖屏）。 */
    val loading: Boolean = true,
    /** 后台还在继续探测更多（列表可滚动，但底部还没满）。 */
    val loadingMore: Boolean = false,
    /** 当前播放项的索引。 */
    val currentIndex: Int = 0,
    /** 当前项详情（含简介/统计）。null = 还在拉。 */
    val detail: VideoDetail? = null,
    /** 当前项取流信息。null = 还在取。 */
    val playInfo: PlayInfo? = null,
    /** 当前项互动状态（点赞/投币/收藏）。 */
    val interaction: InteractionState = InteractionState(),
    /** 是否已关注当前 UP 主。 */
    val following: Boolean = false,
    /** 一次性提示。 */
    val toast: String? = null,
    /** 错误（首屏加载失败时展示）。 */
    val error: String? = null,
)

/**
 * 竖屏观看模式 ViewModel。
 *
 * ## 与现有模块的联动
 *
 * 互动（点赞/投币/收藏/关注）全部走**同一个** [InteractionRepository]，
 * 所以详情页 / 收藏夹 / 历史看到的状态天然一致 ——
 * 不需要额外同步代码。这是"不做成孤立页面"的关键：
 * 复用同一份数据层，而不是自己写一套。
 */
class VerticalViewModel(
    private val feed: VerticalFeedRepository,
    private val videoRepository: VideoRepository,
    private val interactions: InteractionRepository?,
    /** 关注态读写走用户主页那套仓库（关注是 UP 维度的操作）。 */
    private val spaceRepository: com.example.biliv3.data.SpaceRepository? = null,
    private val currentMid: () -> Long,
) : ViewModel() {

    private val _state = MutableStateFlow(VerticalUiState())
    val state: StateFlow<VerticalUiState> = _state.asStateFlow()

    /** 当前项详情加载任务（切项时取消，避免旧结果覆盖新项）。 */
    private var detailJob: Job? = null

    init {
        loadFirstPage()
    }

    /** 首屏：探出第一批竖屏后立刻渲染，同时后台继续补足。 */
    fun loadFirstPage() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)

            val first = runCatching {
                feed.loadVertical(minCount = FIRST_BATCH) { batch ->
                    // 增量渲染：每探到一批就先显示，用户不用干等
                    val merged = (_state.value.items + batch).distinctBy { it.bvid }
                    _state.value = _state.value.copy(
                        items = merged,
                        loading = false,
                    )
                    // 第一批到手就加载第 0 项详情
                    if (_state.value.detail == null && merged.isNotEmpty()) {
                        loadDetail(0)
                    }
                }
            }

            if (first.isFailure) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = first.exceptionOrNull()?.message ?: "竖屏内容加载失败",
                )
                return@launch
            }

            if (_state.value.items.isEmpty()) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "暂时没有探到竖屏视频，下拉重试",
                )
            }
        }
    }

    /** 滑动到某项：更新索引并加载该项详情（预加载相邻项由 UI 负责触发）。 */
    fun onPageChanged(index: Int) {
        if (index == _state.value.currentIndex && _state.value.detail != null) return
        _state.value = _state.value.copy(
            currentIndex = index,
            // 切项立刻清空上一项的数据，避免"显示着新视频的标题、
            // 却在播上一个视频"的错配
            detail = null,
            playInfo = null,
            interaction = InteractionState(),
            following = false,
        )
        loadDetail(index)
    }

    /**
     * 加载某项的详情 + 取流 + 互动态。
     *
     * 三者并发：详情是主链路，取流失败不影响标题/简介展示。
     */
    fun loadDetail(index: Int) {
        val item = _state.value.items.getOrNull(index) ?: return

        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            // ---- 详情（主链路）----
            val detail = runCatching { videoRepository.detail(item.bvid) }.getOrNull()
            if (detail == null) {
                _state.value = _state.value.copy(toast = "详情加载失败")
                return@launch
            }

            // 索引可能已经变了（用户快速滑动），丢弃过期结果
            if (_state.value.currentIndex != index) return@launch
            _state.value = _state.value.copy(detail = detail)

            // ---- 取流（增强：失败只影响播放，不影响信息展示）----
            launch {
                val info = runCatching { videoRepository.playInfo(detail.bvid, detail.cid) }
                    .getOrNull()
                if (_state.value.currentIndex == index) {
                    _state.value = _state.value.copy(playInfo = info)
                }
            }

            // ---- 互动态 + 关注态（增强）----
            launch {
                val st = interactions?.relation(detail.bvid)
                val following = spaceRepository?.isFollowing(detail.ownerMid) ?: false
                if (_state.value.currentIndex == index) {
                    _state.value = _state.value.copy(
                        interaction = st ?: InteractionState(),
                        following = following,
                    )
                }
            }
        }
    }

    /** 预加载相邻项的详情（滑动前就把下一条准备好，避免切过去白屏）。 */
    fun preload(index: Int) {
        val item = _state.value.items.getOrNull(index) ?: return
        viewModelScope.launch {
            // 只预拉详情（不取流）—— 取流会占用 CDN 带宽与解码器
            runCatching { videoRepository.detail(item.bvid) }
        }
    }

    // ---------------- 互动 ----------------

    /** 点赞 / 取消点赞。乐观更新 + 失败回滚。 */
    fun toggleLike() {
        val repo = interactions ?: return
        val cur = _state.value
        val detail = cur.detail ?: return

        if (!repo.isLoggedIn) {
            _state.value = cur.copy(toast = "请先登录")
            return
        }

        val next = !cur.interaction.liked
        val prev = cur.interaction
        _state.value = cur.copy(interaction = prev.copy(liked = next))

        viewModelScope.launch {
            repo.like(detail.bvid, next)
                .onSuccess {
                    // 点赞数本地同步（+1/-1），与详情页行为一致
                    val d = _state.value.detail ?: return@onSuccess
                    _state.value = _state.value.copy(
                        detail = d.copy(
                            likeCount = (d.likeCount + if (next) 1 else -1).coerceAtLeast(0),
                        ),
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        interaction = prev,
                        toast = e.message ?: "操作失败",
                    )
                }
        }
    }

    /** 投币。 */
    fun coin(count: Int, alsoLike: Boolean) {
        val repo = interactions ?: return
        val detail = _state.value.detail ?: return

        if (!repo.isLoggedIn) {
            _state.value = _state.value.copy(toast = "请先登录")
            return
        }

        viewModelScope.launch {
            repo.coin(detail.bvid, count, alsoLike)
                .onSuccess {
                    val d = _state.value.detail ?: return@onSuccess
                    val i = _state.value.interaction
                    _state.value = _state.value.copy(
                        detail = d.copy(
                            coinCount = (d.coinCount + count).coerceAtLeast(0),
                            likeCount = if (alsoLike && !i.liked) {
                                (d.likeCount + 1).coerceAtLeast(0)
                            } else {
                                d.likeCount
                            },
                        ),
                        interaction = i.copy(
                            coined = true,
                            liked = i.liked || alsoLike,
                        ),
                        toast = "投币成功",
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(toast = e.message ?: "投币失败")
                }
        }
    }

    /** 收藏 / 取消收藏。 */
    fun toggleFavorite() {
        val repo = interactions ?: return
        val cur = _state.value
        val detail = cur.detail ?: return

        if (!repo.isLoggedIn) {
            _state.value = cur.copy(toast = "请先登录")
            return
        }

        val next = !cur.interaction.favored
        val prev = cur.interaction
        _state.value = cur.copy(interaction = prev.copy(favored = next))

        viewModelScope.launch {
            repo.favorite(detail.bvid, next)
                .onSuccess {
                    _state.value = _state.value.copy(
                        toast = if (next) "已收藏" else "已取消收藏",
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        interaction = prev,
                        toast = e.message ?: "操作失败",
                    )
                }
        }
    }

    /**
     * 关注 / 取关当前 UP 主。
     *
     * 乐观更新 + 失败回滚 —— 与用户主页行为一致。
     * 关注是 **UP 维度**的操作，所以走 `SpaceRepository`（与主页同一套），
     * 而不是 `InteractionRepository`（那是视频维度的点赞/投币/收藏）。
     */
    fun toggleFollow() {
        val repo = spaceRepository ?: return
        val cur = _state.value
        val detail = cur.detail ?: return

        if (!repo.isLoggedIn) {
            _state.value = cur.copy(toast = "请先登录")
            return
        }

        val next = !cur.following
        _state.value = cur.copy(following = next)

        viewModelScope.launch {
            repo.setFollow(detail.ownerMid, next)
                .onSuccess {
                    _state.value = _state.value.copy(
                        toast = if (next) "已关注" else "已取消关注",
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        following = !next,
                        toast = e.message ?: "操作失败",
                    )
                }
        }
    }

    /** 分享上报（埋点，失败静默）。 */
    fun onShared() {
        val bvid = _state.value.detail?.bvid ?: return
        viewModelScope.launch { interactions?.shareReport(bvid) }
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }

    fun retry() = loadFirstPage()

    /** 当前登录 mid（供 UI 判断"是否自己的视频"）。 */
    fun myMid(): Long = currentMid()

    companion object {
        /** 首屏至少先凑几条就渲染（其余后台补）。 */
        const val FIRST_BATCH = 4
    }
}

/** 竖屏模式 VM 工厂。 */
class VerticalVmFactory(
    private val feed: VerticalFeedRepository,
    private val videoRepository: VideoRepository,
    private val interactions: InteractionRepository?,
    /** 关注态读写走用户主页那套仓库（关注是 UP 维度的操作）。 */
    private val spaceRepository: com.example.biliv3.data.SpaceRepository? = null,
    private val currentMid: () -> Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        VerticalViewModel(feed, videoRepository, interactions, spaceRepository, currentMid) as T
}
