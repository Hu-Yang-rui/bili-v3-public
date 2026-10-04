package com.example.biliv3.ui.bangumi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.BangumiItem
import com.example.biliv3.data.BangumiRepository
import com.example.biliv3.ui.component.userMessageFor
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

            // ⚠️ 必须包 runCatching（v1.2.5）：
            // `repo.index()` 现在会对 `code != 0` **抛异常**
            // （原先返回空列表，把"接口报错"伪装成"没有内容"，见 §7.8-44）。
            // 不接住的话，网络异常会直接崩在协程里。
            val result = runCatching { repo.index(seasonType = seasonType) }

            result.fold(
                onSuccess = { list ->
                    _items.value = list
                    // 成功但为空 = 真的没有内容（不再是失败）
                    if (list.isEmpty()) {
                        _error.value = "这个分类暂时没有内容"
                    }
                },
                onFailure = {
                    _items.value = emptyList()
                    _error.value = userMessageFor(it)
                },
            )
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
