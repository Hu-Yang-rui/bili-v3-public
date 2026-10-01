package com.example.biliv3.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.biliv3.data.SearchHistoryStore

/**
 * [SearchViewModel] 的工厂。
 *
 * 需要 `Context` 构造 `SearchHistoryStore`（DataStore 挂在其上），
 * 而 `viewModel()` 默认只支持无参构造。
 *
 * 用 `applicationContext` 而非 Activity Context ——
 * ViewModel 的生命周期长于 Activity，持有 Activity Context 会泄漏。
 */
class SearchViewModelFactory(
    private val context: Context,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SearchViewModel::class.java)) {
            "未知的 ViewModel: ${modelClass.name}"
        }
        return SearchViewModel(SearchHistoryStore(context.applicationContext)) as T
    }
}
