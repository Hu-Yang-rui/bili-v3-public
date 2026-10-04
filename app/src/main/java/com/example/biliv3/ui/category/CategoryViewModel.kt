package com.example.biliv3.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.CategoryRepository
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.ui.component.userMessageFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 分区页 ViewModel。
 *
 * ## 两档排序（最新 / 热门）
 *
 * 官方分区页的筛选维度极多（时间 / 播放量 / 时长 / 子分区 / 番剧类型…），
 * 但**接口层能稳定拿到的只有两档**。与其做一排点了没反应的下拉，
 * 不如只给两个真实可用的档位 —— 每个都有真实数据源：
 *
 * | 档位 | 接口 | 排序依据 |
 * |---|---|---|
 * | 最新 | `dynamic/region` | 投稿时间 |
 * | 热门 | `ranking/region` | 分区热度 |
 *
 * ## 分页
 *
 * `dynamic/region` 支持 `pn` 翻页；`ranking/region` **不支持**
 * （只返回一页热门），所以热门档位没有"加载更多"。
 */
class CategoryViewModel(
    private val repo: CategoryRepository,
    private val rid: Int,
) : ViewModel() {

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /**
     * 加载失败原因。null = 没失败。
     *
     * ⚠️ 上一版是 `runCatching { }.getOrDefault(emptyList())` ——
     * 失败与"分区确实没有内容"都得到空列表，页面统一显示
     * 「这个分区暂时没有内容」。断网时用户会以为**这个分区被清空了**。
     * 见 `AGENTS.md` §1 自检第 7 项（三态齐全）。
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 当前档位：true = 最新，false = 热门。 */
    private val _sortLatest = MutableStateFlow(true)
    val sortLatest: StateFlow<Boolean> = _sortLatest.asStateFlow()

    private var page = 1

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            page = 1

            val result = if (_sortLatest.value) {
                runCatching { repo.latest(rid, page = 1) }
            } else {
                runCatching { repo.hot(rid) }
            }

            result.fold(
                onSuccess = { list ->
                    _videos.value = list
                    // 热门档位只有一页，明确标记没有更多
                    _hasMore.value = _sortLatest.value && list.isNotEmpty()
                },
                onFailure = {
                    _videos.value = emptyList()
                    _hasMore.value = false
                    _error.value = userMessageFor(it)
                },
            )
            _loading.value = false
        }
    }

    /**
     * 切换档位。
     *
     * 切换后**清空列表并回到顶部** —— 沿用旧列表会让用户看到
     * 新旧两批内容混在一起。
     */
    fun setSortLatest(latest: Boolean) {
        if (latest == _sortLatest.value) return
        _sortLatest.value = latest
        _videos.value = emptyList()
        _hasMore.value = true
        load()
    }

    fun loadMore() {
        if (!_sortLatest.value) return
        if (_loadingMore.value || !_hasMore.value || _loading.value) return

        viewModelScope.launch {
            _loadingMore.value = true
            val next = page + 1
            val result = runCatching { repo.latest(rid, page = next) }

            result.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) {
                        _hasMore.value = false
                    } else {
                        val seen = _videos.value.mapTo(HashSet()) { it.bvid }
                        _videos.value = _videos.value + list.filter { it.bvid !in seen }
                        page = next
                        _hasMore.value = true
                    }
                },
                onFailure = {
                    // ⚠️ 翻页失败不置 hasMore=false：网络抖动不该
                    // 把列表永久截断（同 LiveViewModel.loadMore）。
                },
            )
            _loadingMore.value = false
        }
    }

    fun retry() = load()
}

/** 分区页 VM 工厂。 */
class CategoryVmFactory(
    private val repo: CategoryRepository,
    private val rid: Int,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CategoryViewModel(repo, rid) as T
}
