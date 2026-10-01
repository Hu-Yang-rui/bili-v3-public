package com.example.biliv3.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.LiveRepository
import com.example.biliv3.data.LiveRoom
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

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var page = 1

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            page = 1
            val result = runCatching { repo.list(page = 1) }.getOrNull()
            _rooms.value = result?.rooms ?: emptyList()
            _hasMore.value = result?.hasMore ?: false
            _loading.value = false
        }
    }

    fun loadMore() {
        if (_loadingMore.value || !_hasMore.value || _loading.value) return

        viewModelScope.launch {
            _loadingMore.value = true
            val next = page + 1
            val result = runCatching { repo.list(page = next) }.getOrNull()

            if (result == null || result.rooms.isEmpty()) {
                _hasMore.value = false
            } else {
                // 按 roomId 去重
                val seen = _rooms.value.mapTo(HashSet()) { it.roomId }
                _rooms.value = _rooms.value + result.rooms.filter { it.roomId !in seen }
                page = next
                _hasMore.value = result.hasMore
            }
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
