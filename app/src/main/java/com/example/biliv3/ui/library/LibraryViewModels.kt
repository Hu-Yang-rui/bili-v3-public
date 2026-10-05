package com.example.biliv3.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.FavFolder
import com.example.biliv3.data.FavoriteEntry
import com.example.biliv3.data.HistoryEntry
import com.example.biliv3.data.LibraryRepository
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.ui.component.userMessageFor
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

    /**
     * 加载失败的原因（null = 没有失败）。
     *
     * ## 🔴 v1.5.2 新增：失败不能伪装成空（§7.8-44 同类错误）
     *
     * 原来这里是：
     * ```kotlin
     * val fs = runCatching { repo.favoriteFolders(repo.currentMid) }
     *     .getOrDefault(emptyList())     // ← 失败变成空列表
     * ```
     *
     * 于是**任何失败**（未登录 / mid 无效 / 网络断 / 风控）
     * 都渲染成「还没有收藏夹」—— 一句**假的事实断言**。
     * 用户报告"收藏功能失效"时看到的就是这句话，而账号实际有 6 个收藏夹。
     *
     * 现在：失败写 `error`，UI 优先显示错误态 + 可重试；
     * 只有**真的**拉到空列表才显示「还没有收藏夹」。
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val isLoggedIn: Boolean get() = repo.isLoggedIn

    init {
        load()
    }

    fun load() {
        if (!isLoggedIn) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            // ⚠️ mid 无效要**明确报错**，不能静默返回空 ——
            // `favoriteFolders` 对 `mid <= 0` 是直接 return emptyList()，
            // 那条路径在 UI 上必须与"真的没有收藏夹"区分开。
            val mid = repo.currentMid
            if (mid <= 0L) {
                _folders.value = emptyList()
                _previews.value = emptyMap()
                _error.value = "登录信息不完整，请重新登录"
                _loading.value = false
                return@launch
            }

            val result = runCatching { repo.favoriteFolders(mid) }
            if (result.isFailure) {
                _folders.value = emptyList()
                _previews.value = emptyMap()
                _error.value = userMessageFor(
                    result.exceptionOrNull() ?: IllegalStateException("收藏夹加载失败"),
                )
                _loading.value = false
                return@launch
            }
            val fs = result.getOrDefault(emptyList())
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

    // ---------------- v1.3.0 批量整理 ----------------

    /**
     * 多选状态。
     *
     * ⚠️ 每次列表变化都要 `updateKeys`（见 `_items` 的 setter 注释），
     * 否则"全选"会基于过期列表计算。
     */
    private val _selection = MutableStateFlow(BatchSelection())
    val selection: StateFlow<BatchSelection> = _selection.asStateFlow()

    /** 批量操作进行中（禁用按钮 + 显示进度）。 */
    private val _batchRunning = MutableStateFlow(false)
    val batchRunning: StateFlow<Boolean> = _batchRunning.asStateFlow()

    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val batchProgress: StateFlow<Pair<Int, Int>?> = _batchProgress.asStateFlow()

    /** 当前收藏夹里全部已加载项的 key（顺序 = 列表顺序）。 */
    private fun loadedKeys(): List<String> = _items.value.map { it.favItemId.toString() }

    /** 同步选择集（列表变了就调）。 */
    private fun syncSelection() {
        _selection.value = _selection.value.updateKeys(loadedKeys())
    }

    fun toggleSelect(key: String) {
        _selection.value.toggle(key)
        // ⚠️ 必须重新赋值触发 StateFlow 通知 —— BatchSelection 是可变对象，
        // 原地修改不会发通知（这也是它为什么提供 updateKeys 返回新实例）。
        _selection.value = _selection.value.updateKeys(loadedKeys())
    }

    fun selectAll() {
        _selection.value.selectAll()
        _selection.value = _selection.value.updateKeys(loadedKeys())
    }

    fun clearSelection() {
        _selection.value.clear()
        _selection.value = _selection.value.updateKeys(loadedKeys())
    }

    fun invertSelection() {
        _selection.value.invert()
        _selection.value = _selection.value.updateKeys(loadedKeys())
    }

    fun selectCurrentPage() {
        _selection.value.selectPage(loadedKeys())
        _selection.value = _selection.value.updateKeys(loadedKeys())
    }

    /** 把选中的 favItemId 映射回条目（顺序 = 列表顺序，不是选择顺序）。 */
    private fun selectedEntries(): List<FavoriteEntry> {
        val sel = _selection.value
        if (sel.isEmpty) return emptyList()
        return _items.value.filter { it.favItemId.toString() in sel.keys() }
    }

    // ⚠️ `FavRef` 是 `LibraryRepository` 的**嵌套类**（不是顶层），
    // 所以必须写全限定名 —— 直接 import 会报 Unresolved reference。
    private fun refsOf(entries: List<FavoriteEntry>): List<LibraryRepository.FavRef> =
        entries.map {
            LibraryRepository.FavRef(
                bvid = it.video.bvid,
                aid = it.aid,
                favItemId = it.favItemId,
                folderId = folderId,
            )
        }

    /**
     * 批量移动到另一个收藏夹。
     *
     * ⚠️ 与单条 `removeFavorite` 不同，这里**不做乐观移除**：
     * 移动成功后条目会从当前夹消失，但如果部分失败，
     * 乐观更新会让"哪些成功了"变得无法判断。所以批量操作
     * **等结果回来再刷新**，宁可慢一点也不能显示错。
     */
    fun batchMoveTo(targetFolderId: Long) {
        val entries = selectedEntries()
        if (entries.isEmpty() || _batchRunning.value) return

        viewModelScope.launch {
            _batchRunning.value = true
            _batchProgress.value = 0 to entries.size
            val results = repo.batchMove(refsOf(entries), targetFolderId) { d, t ->
                _batchProgress.value = d to t
            }
            _batchRunning.value = false
            _batchProgress.value = null
            _toast.value = repo.summarize(results)

            // 成功的从列表移除（失败的留着，用户能看到哪些没走）
            val okIds = results.filter { it.ok }.map { it.bvid }.toHashSet()
            _items.value = _items.value.filterNot { it.video.bvid in okIds }
            clearSelection()
            if (results.any { it.ok }) sync?.notifyChanged()
        }
    }

    /** 批量取消收藏（**破坏性操作，UI 必须先确认**）。 */
    fun batchRemove() {
        val entries = selectedEntries()
        if (entries.isEmpty() || _batchRunning.value) return

        viewModelScope.launch {
            _batchRunning.value = true
            _batchProgress.value = 0 to entries.size
            val results = repo.batchRemoveFavorite(refsOf(entries)) { d, t ->
                _batchProgress.value = d to t
            }
            _batchRunning.value = false
            _batchProgress.value = null
            _toast.value = repo.summarize(results)

            val okIds = results.filter { it.ok }.map { it.bvid }.toHashSet()
            _items.value = _items.value.filterNot { it.video.bvid in okIds }
            clearSelection()
            if (results.any { it.ok }) sync?.notifyChanged()
        }
    }

    /** 批量加入稍后再看。 */
    fun batchAddToView() {
        val entries = selectedEntries()
        if (entries.isEmpty() || _batchRunning.value) return

        viewModelScope.launch {
            _batchRunning.value = true
            _batchProgress.value = 0 to entries.size
            val results = repo.batchAddToView(entries.map { it.aid }) { d, t ->
                _batchProgress.value = d to t
            }
            _batchRunning.value = false
            _batchProgress.value = null
            // ⚠️ 加入稍后再看**不改收藏夹内容**，所以不移除条目
            _toast.value = repo.summarize(results)
            clearSelection()
        }
    }

    /**
     * 批量加入播放队列。
     *
     * ⚠️ `cid` 传 0 —— 收藏夹列表**不带 cid**（`VideoItem` 里没有这个字段），
     * 而 `QueueItem.cid` 的语义正是"0 = 尚未确定（还没拉详情）"。
     * 真正播放时页面拉到详情再回填，不要在这里瞎猜一个 cid。
     */
    fun batchAddToQueue(
        controller: com.example.biliv3.player.PlaybackController,
    ) {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        var added = 0
        entries.forEach { e ->
            val ok = controller.queue.ensurePresent(
                com.example.biliv3.player.QueueItem(
                    bvid = e.video.bvid,
                    cid = 0L,
                    title = e.video.title,
                    author = e.video.authorName,
                    cover = e.video.cover,
                    durationSeconds = e.video.durationSeconds,
                ),
            )
            if (ok) added++
        }
        _toast.value = if (added == 0) {
            "已在队列中"
        } else {
            "已加入队列 $added 首"
        }
        clearSelection()
    }

    // ---------------- 批量移动：目标收藏夹 ----------------

    /**
     * 可移动到的其他收藏夹（**不含当前夹**）。
     *
     * ⚠️ 懒加载：只在用户点「移动」时才拉。
     * 收藏夹列表要单独一个请求，进页面就拉属于白费流量。
     */
    private val _otherFolders = MutableStateFlow<List<FavFolder>>(emptyList())
    val otherFolders: StateFlow<List<FavFolder>> = _otherFolders.asStateFlow()

    private var foldersLoaded = false

    /**
     * 拉取可移动的目标收藏夹。
     *
     * ⚠️ mid 从 `repo.currentMid` 取，**不由 UI 传** —— UI 不该知道 mid 从哪来，
     * 而且传错（比如传 0）的表现是"移动列表永远为空"，很难排查。
     */
    fun loadOtherFolders() {
        val mid = repo.currentMid
        if (foldersLoaded || mid <= 0L) return
        viewModelScope.launch {
            runCatching { repo.favoriteFolders(mid) }
                .onSuccess { all ->
                    // 排除当前夹 —— 移到自己没有意义，且服务端会报错
                    _otherFolders.value = all.filter { it.id != folderId }
                    foldersLoaded = true
                }
                .onFailure {
                    // ⚠️ 失败**不设** foldersLoaded，下次点「移动」还能重试。
                    // 设了的话一次网络抖动就永久没有移动入口。
                    _toast.value = "收藏夹列表加载失败"
                }
        }
    }
}

/**
 * 快速整理页**不需要自己的 ViewModel**（v1.3.0）。
 *
 * ## 为什么删掉了最初的 `OrganizeViewModel`
 *
 * 初版让它继承 `FavoriteFolderViewModel` 复用批量逻辑。问题是 Kotlin 里
 * `FavoriteFolderViewModel` 是 final，要继承得把 `items` / `load` / `isLoggedIn`
 * 等**一堆成员全改成 open** —— 为了一个页面的复用去松动整个类的封装，
 * 代价远大于收益，而且 `open` 会让"谁能改这些状态"变得含糊。
 *
 * ## 现在的做法
 *
 * 直接复用 `FavoriteFolderViewModel`（它本来就管列表 + 批量操作），
 * **候选筛选放在 UI 层**（`buildCandidates` 是纯函数，见 `OrganizeScreen.kt`）。
 * 好处：
 * - 批量逻辑只有一份（不会出现"整理页能删但收藏夹页删不了"）
 * - `buildCandidates` 是纯函数，可单测，且不依赖 Android
 */
