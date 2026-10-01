package com.example.biliv3.ui.bangumi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.BangumiItem
import com.example.biliv3.data.BangumiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 番剧索引 ViewModel。
 *
 * 切类型就重拉（不同分类数据完全不同，缓存收益低）。
 */
class BangumiViewModel(
    private val repo: BangumiRepository,
) : ViewModel() {

    private val _tabs = MutableStateFlow(BangumiTab.defaults())
    val tabs: StateFlow<List<BangumiTab>> = _tabs.asStateFlow()

    private val _selectedType = MutableStateFlow(BangumiRepository.TYPE_BANGUMI)
    val selectedType: StateFlow<Int> = _selectedType.asStateFlow()

    private val _items = MutableStateFlow<List<BangumiItem>>(emptyList())
    val items: StateFlow<List<BangumiItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        load(BangumiRepository.TYPE_BANGUMI)
    }

    fun selectTab(seasonType: Int) {
        if (seasonType == _selectedType.value && _items.value.isNotEmpty()) return
        _selectedType.value = seasonType
        load(seasonType)
    }

    fun retry() {
        load(_selectedType.value)
    }

    private fun load(seasonType: Int) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            val list = repo.index(seasonType = seasonType)
            _items.value = list
            if (list.isEmpty()) {
                _error.value = "暂时拿不到数据，可能是请求过于频繁"
            }
            _loading.value = false
        }
    }
}

/** [BangumiViewModel] 的工厂。 */
class BangumiViewModelFactory(
    private val repo: BangumiRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(BangumiViewModel::class.java)) {
            "未知的 ViewModel: ${modelClass.name}"
        }
        return BangumiViewModel(repo) as T
    }
}
