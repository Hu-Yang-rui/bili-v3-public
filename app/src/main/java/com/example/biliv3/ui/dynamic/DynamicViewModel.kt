package com.example.biliv3.ui.dynamic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.DynamicItem
import com.example.biliv3.data.DynamicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 动态流 ViewModel。
 *
 * ## 三态必须齐全（这是动态页最容易做砸的地方）
 *
 * | 场景 | 显示 |
 * |---|---|
 * | 未登录 | **登录引导**（不是"加载失败"） |
 * | 已登录但没关注任何人 | 「还没有动态，去关注几个 UP 主吧」 |
 * | 网络失败 | 错误 + 重试 |
 *
 * 三者混在一起显示"暂无数据"是最常见的错误 ——
 * 用户不知道到底是没登录、没关注、还是网络问题。
 */
class DynamicViewModel(
    private val repo: DynamicRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<DynamicItem>>(emptyList())
    val items: StateFlow<List<DynamicItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** 加载失败（null = 没失败）。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val isLoggedIn: Boolean get() = repo.isLoggedIn

    private var offset = ""

    init {
        load()
    }

    fun load() {
        if (!isLoggedIn) {
            _items.value = emptyList()
            _loading.value = false
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            offset = ""

            val page = runCatching { repo.feed() }.getOrNull()
            if (page == null) {
                _error.value = "动态加载失败，请稍后重试"
                _loading.value = false
                return@launch
            }

            _items.value = page.items
            offset = page.offset
            _hasMore.value = page.hasMore
            _loading.value = false
        }
    }

    fun loadMore() {
        if (!isLoggedIn) return
        if (_loadingMore.value || !_hasMore.value || offset.isEmpty()) return

        viewModelScope.launch {
            _loadingMore.value = true
            val page = runCatching { repo.feed(offset) }.getOrNull()
            if (page != null) {
                // 按 id 去重：翻页期间若有新动态插入会重复
                val seen = _items.value.mapTo(HashSet()) { it.id }
                _items.value = _items.value + page.items.filter { it.id !in seen }
                offset = page.offset
                _hasMore.value = page.hasMore
            } else {
                _hasMore.value = false
            }
            _loadingMore.value = false
        }
    }

    fun retry() = load()
}

/** 动态流 VM 工厂。 */
class DynamicVmFactory(
    private val repo: DynamicRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DynamicViewModel(repo) as T
}
