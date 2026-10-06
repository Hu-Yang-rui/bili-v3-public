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
 * ## 范围说明（v1.6.3 更新：直播已改为**应用内播放**）
 *
 * ### 此前为什么不做播放
 *
 * 原实现点击条目 → 打开**系统浏览器**的官方直播间页面，理由是：
 * 直播流需要 HTTP-FLV / HLS 的另一套播放路径，而当时依赖里只有
 * `media3-exoplayer-dash`。硬做会得到一个"点进去黑屏"的直播间。
 *
 * ### 现在为什么能做
 *
 * v1.6.3 补上了 `media3-exoplayer-hls`，并实测确认 B 站直播接口
 * （`xlive/web-room/v2/index/getRoomPlayInfo`）确实返回可播地址：
 *
 * ```
 * code=0, live_status=1
 * http_hls    ts   avc   -> https://.../live_xxx.m3u8?expires=...
 * http_stream flv  avc   -> https://.../live_xxx.flv?expires=...
 * ```
 *
 * 实测拉取 `.m3u8` 得到 `HTTP 200` + `application/vnd.apple.mpegurl`
 * + `#EXTM3U` / `#EXT-X-TARGETDURATION:3` / `.ts` 分片 —— 是**真实可用**的
 * 直播清单，不是推测。
 *
 * 所以现在点击条目 → **进站内直播间页播放**（见 `LiveRoomScreen`），
 * 不再跳出应用。
 *
 * ### 本项目仍然**不做**的
 *
 * - **直播弹幕**：走 WebSocket 长连接（`sub` 协议），与视频弹幕的
 *   HTTP protobuf 分片是两套东西。直播间页会**如实说明**未接入，
 *   而不是做一个假的弹幕区。
 * - **清晰度切换**：接口给了 `accept_qn`，但本项目只取服务端默认档
 *   （原画优先，未登录会降级）。不提供切换，也不假装能切。
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
