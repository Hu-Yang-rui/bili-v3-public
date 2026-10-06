package com.example.biliv3.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.LiveRepository
import com.example.biliv3.data.LiveRoom
import com.example.biliv3.data.LiveStream
import com.example.biliv3.data.SettingsStore
import com.example.biliv3.player.PlayerHolder
import com.example.biliv3.ui.component.userMessageFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 直播间 ViewModel（**应用内播放**，v1.6.3）。
 *
 * ## 它管什么
 *
 * 1. 取流（`LiveRepository.stream`）
 * 2. 把流装配到 **Activity 级**播放器（`PlayerHolder.bindLive`）
 * 3. 「隐身入场」开关的**唯一消费者**
 *
 * ## 🔴 隐身入场的语义（这是本类存在的一条关键理由）
 *
 * 直播改成应用内播放后，本应用进入直播间**只有这一个入口**，
 * 所以开关的语义变得完整且无歧义：
 *
 * | 设置 | 行为 |
 * |---|---|
 * | 关（默认） | 进入时调一次 `reportEntry`（与官方网页一致） |
 * | 开 | **完全不发**这个请求 |
 *
 * 此前（跳浏览器的版本）开关只能保证"本应用不发"，而浏览器的上报
 * 不受控制 —— 那个限制现在**不存在了**，因为不再跳浏览器。
 *
 * ## 为什么复用 `PlayerHolder` 而不是自己建播放器
 *
 * 项目有硬约束「禁止创建第二套 Player」（见 `PlaybackController` 说明）：
 * 两个 `ExoPlayer` 会争抢音频焦点与解码器。所以直播走**同一个**
 * Activity 级 holder —— 从视频页切到直播间时，`bindLive` 会替换媒体源，
 * 播放器实例本身复用。
 */
class LiveRoomViewModel(
    private val repo: LiveRepository,
    private val room: LiveRoom,
    /** Activity 级播放器持有者（与视频详情页同一个）。 */
    val holder: PlayerHolder? = null,
    /** 设置（「隐身入场」的消费者）。 */
    private val settingsStore: SettingsStore? = null,
) : ViewModel() {

    private val _stream = MutableStateFlow<LiveStream?>(null)
    val stream: StateFlow<LiveStream?> = _stream.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 当前播放器（供 UI 渲染）。 */
    val player get() = holder?.player

    /** 本次会话是否已经上报过入场（避免刷新时重复上报）。 */
    private var entryReported = false

    init {
        load()
    }

    /** 取流 + 装配。失败写入 [error]，未开播则 `stream` 为空流。 */
    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            // ---- ① 入场上报（「隐身入场」的消费点）----
            //
            // ⚠️ 必须在**取流之前**、且只在**首次**进入时做一次：
            // 后续点"刷新"重取流不应该再报一次（那是"重连"不是"新进入"）。
            reportEntryIfNeeded()

            // ---- ② 取流 ----
            val result = runCatching { repo.stream(room.roomId) }
            result.fold(
                onSuccess = { s ->
                    _stream.value = s
                    if (s.playable) bind(s)
                },
                onFailure = {
                    // ⚠️ 失败必须进错误态（§7.8-44）：不能显示成"未开播"
                    _stream.value = null
                    _error.value = userMessageFor(it)
                },
            )
            _loading.value = false
        }
    }

    /**
     * 入场上报 —— **这就是「隐身入场」的全部实现**。
     *
     * 开关打开时**直接返回**，一个请求都不发。
     * 这是"空转设置项"的反面：设置项必须真的改变行为。
     */
    private suspend fun reportEntryIfNeeded() {
        if (entryReported) return
        entryReported = true

        // 读开关（拿不到设置时按"不隐身"处理 —— 与改动前的行为一致）
        val incognito = runCatching {
            settingsStore?.settings?.first()?.liveIncognito ?: false
        }.getOrDefault(false)

        // 🔴 隐身：不发任何请求，直接结束
        if (incognito) return

        // 非隐身：上报一次。失败**静默** —— 入场上报失败不影响观看，
        // 弹一个"上报失败"的错反而会让用户困惑。
        runCatching { repo.reportEntry(room.roomId) }
    }

    /** 把流装配到播放器。 */
    private fun bind(s: LiveStream) {
        val h = holder ?: return
        // 复用同一个播放器实例：换直播间时 key 变化会触发 holder 重建
        val p = h.acquire("live-${room.roomId}")
        runCatching { p.playWhenReady = true }

        when (val r = h.bindLive(s, playWhenReady = true)) {
            is PlayerHolder.BindResult.NoSource ->
                _error.value = "该直播间没有可播放的流"
            is PlayerHolder.BindResult.Failed ->
                _error.value = "播放器初始化失败：${r.message}"
            // Bound / Reused / NoPlayer / Released 都是正常路径
            else -> Unit
        }
    }

    /** 播放器报错（由 UI 的 Surface 层回调）。 */
    fun onPlayerError(message: String) {
        _error.value = message
    }

    /** 手动刷新（重新取流；**不重复**上报入场）。 */
    fun reload() = load()

    override fun onCleared() {
        super.onCleared()
        // ⚠️ **不释放播放器** —— 它是 Activity 级的，由导航层在
        // "离开播放场景"时统一释放（与视频详情页同一约定）。
        // 在这里 release 会让 PiP / 切页继续播失效。
    }
}

/** 直播间 VM 工厂。 */
class LiveRoomVmFactory(
    private val repo: LiveRepository,
    private val room: LiveRoom,
    private val holder: PlayerHolder?,
    private val settingsStore: SettingsStore?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LiveRoomViewModel(repo, room, holder, settingsStore) as T
}
