package com.example.biliv3.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.CategoryRepository
import com.example.biliv3.data.model.VideoItem
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
            page = 1

            val list = if (_sortLatest.value) {
                runCatching { repo.latest(rid, page = 1) }.getOrDefault(emptyList())
            } else {
                runCatching { repo.hot(rid) }.getOrDefault(emptyList())
            }

            _videos.value = list
            // 热门档位只有一页，明确标记没有更多
            _hasMore.value = _sortLatest.value && list.isNotEmpty()
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
            val list = runCatching { repo.latest(rid, page = next) }.getOrDefault(emptyList())

            if (list.isEmpty()) {
                _hasMore.value = false
            } else {
                val seen = _videos.value.mapTo(HashSet()) { it.bvid }
                _videos.value = _videos.value + list.filter { it.bvid !in seen }
                page = next
                _hasMore.value = list.isNotEmpty()
            }
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
