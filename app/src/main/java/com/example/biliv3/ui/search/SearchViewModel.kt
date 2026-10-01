package com.example.biliv3.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.SearchHistoryStore
import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.ui.component.userMessageFor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 搜索页状态。
 *
 * ## 三个互斥阶段
 *
 * 搜索页的 UI 是「未输入 → 输入中(联想) → 已搜索(结果)」，
 * 用一个 sealed interface 表达比多个布尔标志清晰得多，
 * 也避免"联想和结果同时显示"这种非法组合。
 */
sealed interface SearchUiState {
    /** 未输入：显示搜索历史 + 热搜。 */
    data object Idle : SearchUiState

    /** 输入中：显示联想词。 */
    data class Suggesting(val keyword: String, val suggestions: List<String>) : SearchUiState

    /** 搜索中（首次）。 */
    data class Loading(val keyword: String) : SearchUiState

    /** 有结果。 */
    data class Results(
        val keyword: String,
        val items: List<VideoItem>,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val page: Int = 1,
    ) : SearchUiState

    /** 结果为空。与 [Idle] 区分：空结果要提示"没搜到"，不是回到初始页。 */
    data class Empty(val keyword: String) : SearchUiState

    /** 搜索失败。 */
    data class Error(val keyword: String, val message: String) : SearchUiState
}

/**
 * 搜索页 ViewModel。
 *
 * ## 防抖 500ms
 *
 * 每敲一个字就发请求会瞬间打满接口（且极易触发 `-352` 风控）。
 * 用 `debounce(500)` 等用户停手再发 —— 对应 `AGENTS.md` §3.3
 * 流程 2 的「输入（500ms 防抖）」。
 *
 * ## 为什么用 Flow.debounce 而不是手写 Handler
 *
 * `debounce` 自带"新事件取消旧计时"的语义，手写要自己管 Job 取消，
 * 漏掉就会出现旧请求后返回、把新结果覆盖掉的竞态。
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val historyStore: SearchHistoryStore,
    private val api: BiliApi = BiliApi(),
) : ViewModel() {

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** 输入框内容。与 [_state] 分开：输入框要即时回显，不等防抖。 */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 搜索历史（DataStore 流 → StateFlow）。 */
    val history: StateFlow<List<String>> = historyStore.history
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _hotSearch = MutableStateFlow<List<String>>(emptyList())
    val hotSearch: StateFlow<List<String>> = _hotSearch.asStateFlow()

    /** 当前搜索结果请求，用于"加载更多"时取消上一次。 */
    private var searchJob: Job? = null

    init {
        loadHotSearch()
        observeQueryForSuggest()
    }

    private fun loadHotSearch() {
        viewModelScope.launch {
            runCatching { api.hotSearch() }
                .onSuccess { _hotSearch.value = it }
            // 失败静默：热搜是增强模块，拿不到就不显示，不打扰用户
        }
    }

    /** 输入框变化。 */
    fun onQueryChange(text: String) {
        _query.value = text
        if (text.isBlank()) {
            _state.value = SearchUiState.Idle
        }
    }

    /**
     * 监听输入做联想。
     *
     * `distinctUntilChanged` 避免同一关键词重复请求；
     * `filter` 掉空串，否则清空输入框也会发一次请求。
     */
    private fun observeQueryForSuggest() {
        viewModelScope.launch {
            _query
                .debounce(SUGGEST_DEBOUNCE_MS)
                .distinctUntilChanged()
                .filter { it.isNotBlank() }
                .collect { keyword ->
                    // 已经进入结果态就不再弹联想（避免搜索结果被联想盖掉）
                    if (_state.value is SearchUiState.Results) return@collect
                    val suggestions = runCatching { api.suggest(keyword) }.getOrDefault(emptyList())
                    // 二次校验：联想返回期间用户可能又改了输入
                    if (_query.value == keyword) {
                        _state.value = SearchUiState.Suggesting(keyword, suggestions)
                    }
                }
        }
    }

    /** 执行搜索（回车 / 点联想词 / 点历史 / 点热搜）。 */
    fun search(keyword: String) {
        val word = keyword.trim()
        if (word.isEmpty()) return

        _query.value = word
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = SearchUiState.Loading(word)
            runCatching { historyStore.add(word) }
            runCatching { api.search(word, page = 1) }
                .onSuccess { result ->
                    _state.value = if (result.items.isEmpty()) {
                        SearchUiState.Empty(word)
                    } else {
                        SearchUiState.Results(
                            keyword = word,
                            items = result.items,
                            hasMore = result.items.size >= PAGE_SIZE,
                            page = 1,
                        )
                    }
                }
                .onFailure { e ->
                    _state.value = SearchUiState.Error(word, userMessageFor(e))
                }
        }
    }

    /**
     * 触底加载下一页。
     *
     * 去重：搜索结果跨页可能重复，按 bvid 过滤。
     */
    fun loadMore() {
        val current = _state.value
        if (current !is SearchUiState.Results) return
        if (current.loadingMore || !current.hasMore) return

        viewModelScope.launch {
            _state.value = current.copy(loadingMore = true)
            val nextPage = current.page + 1
            runCatching { api.search(current.keyword, page = nextPage) }
                .onSuccess { result ->
                    val seen = current.items.mapTo(HashSet()) { it.bvid }
                    val merged = current.items + result.items.filter { it.bvid !in seen }
                    _state.value = current.copy(
                        items = merged,
                        loadingMore = false,
                        page = nextPage,
                        hasMore = result.items.isNotEmpty(),
                    )
                }
                .onFailure {
                    // 加载更多失败不把整页变错误 —— 已有结果仍可用
                    _state.value = current.copy(loadingMore = false, hasMore = false)
                }
        }
    }

    /** 重试当前关键词。 */
    fun retry() {
        val keyword = when (val s = _state.value) {
            is SearchUiState.Error -> s.keyword
            is SearchUiState.Empty -> s.keyword
            else -> _query.value
        }
        search(keyword)
    }

    /** 清空输入，回到初始态。 */
    fun clearQuery() {
        _query.value = ""
        _state.value = SearchUiState.Idle
    }

    fun removeHistory(keyword: String) {
        viewModelScope.launch { historyStore.remove(keyword) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyStore.clear() }
    }

    private companion object {
        /** 联想防抖。对应 AGENTS.md §3.3「输入 500ms 防抖」。 */
        const val SUGGEST_DEBOUNCE_MS = 500L

        /** 接口每页返回 20 条（实测 pagesize=20）。 */
        const val PAGE_SIZE = 20
    }
}
