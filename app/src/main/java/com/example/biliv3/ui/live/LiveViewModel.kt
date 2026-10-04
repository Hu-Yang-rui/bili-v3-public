package com.example.biliv3.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.LiveRepository
import com.example.biliv3.data.LiveRoom
import com.example.biliv3.ui.component.userMessageFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 直播列表 ViewModel。
 *
 * ## 范围说明（重要）
 *
 * 本项目**只呈现"有哪些直播"**，不做直播间播放。
 *
 * 理由：直播流需要 HTTP-FLV / HLS 的另一套播放路径
 * （Media3 的 FLV 扩展或 HLS 模块），而当前依赖里只有
 * `media3-exoplayer-dash`。硬做会得到一个"点进去黑屏"的直播间 ——
 * 那是比"没有这个功能"更差的体验。
 *
 * 所以点击直播条目 → 打开**系统浏览器**看官方直播间页面。
 * 这是真实可用的出口，不是死入口。
 */
class LiveViewModel(
    private val repo: LiveRepository,
) : ViewModel() {

    private val _rooms = MutableStateFlow<List<LiveRoom>>(emptyList())
    val rooms: StateFlow<List<LiveRoom>> = _rooms.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /**
     * 加载失败原因。null = 没失败。
     *
     * ## ⚠️ 为什么必须单独立一个 error，而不是"空列表兜底"
     *
     * 上一版这里是 `runCatching { ... }.getOrNull()`，失败时
     * `_rooms` 拿到 `emptyList()` —— 于是**断网与"真的没有直播"长得一模一样**，
     * 页面显示「当前没有正在直播的房间」。
     *
     * 这是**假空态**：用户以为"半夜没人直播"，实际是自己网断了，
     * 而且**没有任何重试入口**（`retry()` 当时是死代码）。
     * `AGENTS.md` §1 自检第 7 项要求三态齐全，这是明确违规。
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var page = 1

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            page = 1
            val result = runCatching { repo.list(page = 1) }
            result.fold(
                onSuccess = {
                    _rooms.value = it.rooms
                    _hasMore.value = it.hasMore
                },
                onFailure = {
                    // ⚠️ 失败必须**清空列表并置错误** —— 若保留旧列表，
                    // 用户看到的是过期数据 + 没有错误提示，比空白更糟。
                    _rooms.value = emptyList()
                    _hasMore.value = false
                    _error.value = userMessageFor(it)
                },
            )
            _loading.value = false
        }
    }

    fun loadMore() {
        if (_loadingMore.value || !_hasMore.value || _loading.value) return

        viewModelScope.launch {
            _loadingMore.value = true
            val next = page + 1
            val result = runCatching { repo.list(page = next) }

            result.fold(
                onSuccess = { r ->
                    if (r.rooms.isEmpty()) {
                        // 真的到底了
                        _hasMore.value = false
                    } else {
                        // 按 roomId 去重
                        val seen = _rooms.value.mapTo(HashSet()) { it.roomId }
                        _rooms.value = _rooms.value + r.rooms.filter { it.roomId !in seen }
                        page = next
                        _hasMore.value = r.hasMore
                    }
                },
                onFailure = {
                    // ⚠️ 翻页失败**不能**把 hasMore 置 false ——
                    // 那等于"网络抖一下就宣布没有更多了"，列表被永久截断
                    // 且用户没有任何提示。保持 hasMore=true，
                    // 用户再滑一次即可自然重试。
                },
            )
            _loadingMore.value = false
        }
    }

    fun retry() = load()
}

/** 直播列表 VM 工厂。 */
class LiveVmFactory(
    private val repo: LiveRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LiveViewModel(repo) as T
}
