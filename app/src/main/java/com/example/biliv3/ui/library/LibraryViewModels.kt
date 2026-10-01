package com.example.biliv3.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.FavFolder
import com.example.biliv3.data.FavoriteEntry
import com.example.biliv3.data.HistoryEntry
import com.example.biliv3.data.LibraryRepository
import com.example.biliv3.data.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 历史记录 ViewModel。
 *
 * 用**游标**分页（`max` + `view_at`），不是页码 ——
 * 历史是"流式"数据，新记录不断插入，页码会错位。
 */
class HistoryViewModel(
    private val repo: LibraryRepository,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    val isLoggedIn: Boolean get() = repo.isLoggedIn

    private var cursorMax = 0L
    private var cursorViewAt = 0L

    init {
        load()
    }

    fun load() {
        if (!isLoggedIn) return
        viewModelScope.launch {
            _loading.value = true
            cursorMax = 0L
            cursorViewAt = 0L
            runCatching { repo.history() }
                .onSuccess { page ->
                    _entries.value = page.items
                    cursorMax = page.nextMax
                    cursorViewAt = page.nextViewAt
                    _hasMore.value = !page.isEnd
                }
            _loading.value = false
        }
    }

    fun loadMore() {
        if (!isLoggedIn) return
        if (_loadingMore.value || !_hasMore.value) return

        viewModelScope.launch {
            _loadingMore.value = true
            runCatching { repo.history(max = cursorMax, viewAt = cursorViewAt) }
                .onSuccess { page ->
                    // 按 (bvid, viewAt) 去重 —— 同一视频可能看多次
                    val seen = _entries.value.mapTo(HashSet()) { it.video.bvid + it.viewAt }
                    _entries.value = _entries.value +
                        page.items.filter { it.video.bvid + it.viewAt !in seen }
                    cursorMax = page.nextMax
                    cursorViewAt = page.nextViewAt
                    _hasMore.value = !page.isEnd
                }
                .onFailure { _hasMore.value = false }
            _loadingMore.value = false
        }
    }
}

/** 稍后再看 ViewModel。 */
class ToViewViewModel(
    private val repo: LibraryRepository,
) : ViewModel() {

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val isLoggedIn: Boolean get() = repo.isLoggedIn

    init {
        load()
    }

    fun load() {
        if (!isLoggedIn) return
        viewModelScope.launch {
            _loading.value = true
            _videos.value = repo.toView()
            _loading.value = false
        }
    }
}

/**
 * 「我的收藏」总览 ViewModel（收藏夹分组 + 预览网格）。
 *
 * ## 架构（对照官方）
 *
 * 总览页只负责两件事：
 * 1. 拉收藏夹列表（含各自的 `media_count`）
 * 2. 为每个夹拉**前几张**做预览
 *
 * 点进某个夹由 [FavoriteFolderViewModel] 单独负责完整列表。
 * 拆两个 ViewModel 的原因：总览要的是"全部夹的概览"，
 * 详情要的是"单个夹的完整分页"，两者的加载策略与状态完全不同。
 *
 * ## 预览并发拉取的取舍
 *
 * 收藏夹可能有十几个，逐个串行拉会很慢。这里用 `coroutineScope` 并发，
 * 但**每个夹只拉 1 页（3 条）** —— 总请求量可控。
 * 某个夹拉失败只影响它自己（显示"空"），不影响其它夹。
 */
class FavoriteViewModel(
    private val repo: LibraryRepository,
) : ViewModel() {

    private val _folders = MutableStateFlow<List<FavFolder>>(emptyList())
    val folders: StateFlow<List<FavFolder>> = _folders.asStateFlow()

    private val _previews = MutableStateFlow<Map<Long, List<FavoriteEntry>>>(emptyMap())
    val previews: StateFlow<Map<Long, List<FavoriteEntry>>> = _previews.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val isLoggedIn: Boolean get() = repo.isLoggedIn

    init {
        load()
    }

    fun load() {
        if (!isLoggedIn) return
        viewModelScope.launch {
            _loading.value = true

            val fs = runCatching { repo.favoriteFolders(repo.currentMid) }
                .getOrDefault(emptyList())
            _folders.value = fs

            // 并发拉每个夹的前 3 条预览
            val map = mutableMapOf<Long, List<FavoriteEntry>>()
            coroutineScope {
                fs.forEach { f ->
                    launch {
                        val page = runCatching {
                            repo.favoriteItems(f.id, page = 1, pageSize = PREVIEW_COUNT)
                        }.getOrNull()
                        if (page != null) {
                            // 并发写入普通 map 是数据竞争 —— 用 synchronized 保护
                            synchronized(map) { map[f.id] = page.items.take(PREVIEW_COUNT) }
                        }
                    }
                }
            }
            _previews.value = map
            _loading.value = false
        }
    }

    fun retry() = load()

    private companion object {
        /** 每个夹的预览条数。3 条 = 一行的 3 列。 */
        const val PREVIEW_COUNT = 3
    }
}

/**
 * 收藏夹详情 ViewModel（单个夹的完整分页列表）。
 *
 * ## 取消收藏的状态同步
 *
 * 取消成功后**本地立即移除**（乐观更新），失败则**放回原位**（回滚）。
 * 同时通过 [FavoritesSync] 广播，让总览页的预览与计数也更新 ——
 * 否则用户退回总览会看到刚删的视频还在预览里。
 */
class FavoriteFolderViewModel(
    private val repo: LibraryRepository,
    private val folderId: Long,
    private val sync: com.example.biliv3.data.FavoritesSync? = null,
) : ViewModel() {

    private val _items = MutableStateFlow<List<FavoriteEntry>>(emptyList())
    val items: StateFlow<List<FavoriteEntry>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** 一次性提示（取消收藏成功/失败）。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val isLoggedIn: Boolean get() = repo.isLoggedIn

    private var page = 1

    init {
        load()
    }

    fun load() {
        if (!isLoggedIn) return
        viewModelScope.launch {
            _loading.value = true
            page = 1
            runCatching { repo.favoriteItems(folderId, page = 1) }
                .onSuccess { p ->
                    _items.value = p.items
                    _hasMore.value = p.hasMore
                }
                .onFailure { _hasMore.value = false }
            _loading.value = false
        }
    }

    fun loadMore() {
        if (!isLoggedIn) return
        if (_loadingMore.value || !_hasMore.value) return

        viewModelScope.launch {
            _loadingMore.value = true
            val next = page + 1
            runCatching { repo.favoriteItems(folderId, page = next) }
                .onSuccess { p ->
                    // 按条目 id 去重 —— 翻页期间若有新增会出现重复
                    val seen = _items.value.mapTo(HashSet()) { it.favItemId }
                    _items.value = _items.value + p.items.filter { it.favItemId !in seen }
                    page = next
                    _hasMore.value = p.hasMore
                }
                .onFailure { _hasMore.value = false }
            _loadingMore.value = false
        }
    }

    /** 取消收藏（乐观更新 + 失败回滚 + 广播同步）。 */
    fun removeFavorite(entry: FavoriteEntry) {
        val snapshot = _items.value
        val index = snapshot.indexOfFirst { it.favItemId == entry.favItemId }
        if (index < 0) return

        // 1. 乐观移除
        _items.value = snapshot.filterNot { it.favItemId == entry.favItemId }

        viewModelScope.launch {
            repo.removeFavorite(entry.favItemId, entry.aid)
                .onSuccess {
                    _toast.value = "已取消收藏"
                    // 2. 广播：让总览页/我的页同步
                    sync?.notifyChanged()
                }
                .onFailure { e ->
                    // 3. 失败回滚到原位（不是追加到末尾 —— 位置变了会以为删错）
                    _items.value = _items.value.toMutableList().apply {
                        add(index.coerceAtMost(size), entry)
                    }
                    _toast.value = e.message ?: "取消收藏失败"
                }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun retry() = load()
}
