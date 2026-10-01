package com.example.biliv3.ui.ranking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.RankingRepository
import com.example.biliv3.data.RankingTab
import com.example.biliv3.data.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 排行榜 ViewModel。
 *
 * ## 切 Tab 就重拉
 *
 * 不同分区榜的数据完全不同，缓存收益低（用户很少来回切）。
 * 直接重拉，避免维护多份缓存带来的状态复杂。
 */
class RankingViewModel(
    private val repo: RankingRepository,
) : ViewModel() {

    private val _tabs = MutableStateFlow(RankingTab.defaults())
    val tabs: StateFlow<List<RankingTab>> = _tabs.asStateFlow()

    private val _selectedRid = MutableStateFlow(0)
    val selectedRid: StateFlow<Int> = _selectedRid.asStateFlow()

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        load(0)
    }

    fun selectTab(rid: Int) {
        if (rid == _selectedRid.value && _videos.value.isNotEmpty()) return
        _selectedRid.value = rid
        load(rid)
    }

    fun retry() {
        load(_selectedRid.value)
    }

    private fun load(rid: Int) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            val result = if (rid == 0) {
                // 全站榜走 ranking()，内部已含"失败退分区榜"的降级
                repo.ranking(rid = 0)
            } else {
                repo.regionRanking(rid)
            }

            _videos.value = result
            // 空结果不一定算错误（分区可能真的没榜），但给出提示更好
            if (result.isEmpty()) {
                _error.value = "暂时拿不到数据，可能是请求过于频繁"
            }
            _loading.value = false
        }
    }
}

/** [RankingViewModel] 的工厂。 */
class RankingViewModelFactory(
    private val repo: RankingRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RankingViewModel::class.java)) {
            "未知的 ViewModel: ${modelClass.name}"
        }
        return RankingViewModel(repo) as T
    }
}
