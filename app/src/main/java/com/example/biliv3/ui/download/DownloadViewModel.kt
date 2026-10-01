package com.example.biliv3.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.download.DownloadState
import com.example.biliv3.data.download.DownloadStore
import com.example.biliv3.data.download.DownloadedItem
import com.example.biliv3.data.download.VideoDownloader
import com.example.biliv3.data.model.PlayInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 离线缓存管理页 ViewModel。
 *
 * ## 为什么这个 VM 之前不存在
 *
 * `VideoDownloader`（315 行，含断点续传）与 `DownloadStore` 早已写好、
 * 也在 `AppContainer` 里注入了，但**没有任何 UI 调用它们** ——
 * 一个功能完整的下载器没有任何入口，用户完全看不到。
 * 这是「离线缓存管理页」被判为缺失的直接原因。
 *
 * ## 两组数据
 *
 * | 来源 | 内容 | 用途 |
 * |---|---|---|
 * | `DownloadStore.items` | 已完成（可播的完整缓存） | 「已缓存」Tab |
 * | `VideoDownloader.tasks` | 进行中 / 失败 | 「下载中」Tab |
 *
 * ⚠️ 已完成的任务会从 `tasks` 里移除（见 `VideoDownloader.download` 末尾），
 * 所以两个集合**不会重叠**，合并展示也不会出现同一条两次。
 */
class DownloadViewModel(
    private val store: DownloadStore,
    private val downloader: VideoDownloader,
) : ViewModel() {

    /** 已完成的缓存。 */
    val items: StateFlow<List<DownloadedItem>> = store.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 进行中 / 失败的任务。 */
    val tasks: StateFlow<Map<String, com.example.biliv3.data.download.DownloadTask>> =
        downloader.tasks

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 删除一条缓存（同时删文件）。 */
    fun delete(item: DownloadedItem) {
        viewModelScope.launch {
            runCatching { downloader.delete(item) }
                .onSuccess { _toast.value = "已删除" }
                .onFailure { _toast.value = it.message ?: "删除失败" }
        }
    }

    /** 取消一个进行中的任务（保留已下载的分片，下次可续传）。 */
    fun cancel(bvid: String, cid: Long) {
        downloader.cancel(bvid, cid)
        _toast.value = "已取消（已下载部分保留，可续传）"
    }

    fun consumeToast() {
        _toast.value = null
    }

    /** 缓存总占用（字节）。 */
    val totalBytes: Long get() = items.value.sumOf { it.totalBytes }
}

/** 下载管理页 VM 工厂。 */
class DownloadVmFactory(
    private val store: DownloadStore,
    private val downloader: VideoDownloader,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DownloadViewModel(store, downloader) as T
}

/**
 * 详情页的下载入口 ViewModel（轻量）。
 *
 * 与 [DownloadViewModel] 分开的原因：详情页只需要
 * 「当前视频是否已缓存」+「发起下载」两件事，
 * 拉整份缓存列表（可能上百条）是浪费。
 */
class VideoDownloadEntryViewModel(
    private val store: DownloadStore,
    private val downloader: VideoDownloader,
) : ViewModel() {

    /** 当前视频（某个分P）是否已缓存。 */
    private val _downloaded = MutableStateFlow(false)
    val downloaded: StateFlow<Boolean> = _downloaded.asStateFlow()

    /** 当前视频的下载任务（null = 没有任务）。 */
    private val _task = MutableStateFlow<com.example.biliv3.data.download.DownloadTask?>(null)
    val task: StateFlow<com.example.biliv3.data.download.DownloadTask?> = _task.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var currentBvid: String = ""
    private var currentCid: Long = 0L

    /**
     * 绑定当前视频并刷新状态。
     *
     * 详情页切换分P 时会换 cid，必须重新绑定（否则会显示上一 P 的状态）。
     */
    fun bind(bvid: String, cid: Long) {
        if (bvid == currentBvid && cid == currentCid) return
        currentBvid = bvid
        currentCid = cid

        viewModelScope.launch {
            _downloaded.value = runCatching { store.isDownloaded(bvid, cid) }
                .getOrDefault(false)
        }
        viewModelScope.launch {
            downloader.tasks.collect { map ->
                _task.value = map["$bvid:$cid"]
            }
        }
    }

    /**
     * 发起下载。
     *
     * @param info 已取好的流信息（详情页此时已经取过流，直接复用，
     *             不再打一次 playurl —— 少一次签名请求也少一次风控计数）
     */
    fun download(
        info: PlayInfo,
        bvid: String,
        cid: Long,
        aid: Long,
        title: String,
        cover: String,
        authorName: String,
        pageIndex: Int,
        pageLabel: String,
        durationSeconds: Int,
    ) {
        if (info.videoUrl.isEmpty()) {
            _toast.value = "还没取流，先点封面播放一次再下载"
            return
        }
        viewModelScope.launch {
            _toast.value = "开始下载…"
            downloader.download(
                info = info,
                bvid = bvid,
                cid = cid,
                aid = aid,
                title = title,
                cover = cover,
                authorName = authorName,
                pageIndex = pageIndex,
                pageLabel = pageLabel,
                qualityLabel = info.currentQualityLabel,
                durationSeconds = durationSeconds,
            )
                .onSuccess {
                    _downloaded.value = true
                    _toast.value = "下载完成"
                }
                .onFailure { _toast.value = it.message ?: "下载失败" }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }
}

/** 详情页下载入口 VM 工厂。 */
class VideoDownloadEntryVmFactory(
    private val store: DownloadStore,
    private val downloader: VideoDownloader,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        VideoDownloadEntryViewModel(store, downloader) as T
}

/** 任务是否处于"进行中"（决定 UI 显示进度还是重试按钮）。 */
val com.example.biliv3.data.download.DownloadTask.isRunning: Boolean
    get() = state is DownloadState.Downloading
