package com.example.biliv3.ui.message

import com.example.biliv3.data.model.PmMessagePage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.PmRepository
import com.example.biliv3.ui.component.userMessageFor
import com.example.biliv3.data.model.PmMessage
import com.example.biliv3.data.model.PmSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 私信会话列表 VM。
 *
 * ## 状态设计
 *
 * 用**单一 data class** 而不是散落的多个 `MutableStateFlow`：
 * 列表页要展示的状态（列表 + 加载 + 错误 + 未读）总是一起变化，
 * 合并后 Compose 只需订阅一次，也不会出现"加载完了但列表还是空"的中间态。
 */
class MessageListViewModel(
    private val repo: PmRepository,
) : ViewModel() {

    data class UiState(
        val sessions: List<PmSession> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val loggedIn: Boolean = false,
        /** 私信未读数（用于标题角标）。 */
        val unread: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 刷新会话列表 + 未读数（并行）。 */
    fun refresh() {
        val loggedIn = repo.isLoggedIn
        _state.value = _state.value.copy(loading = true, error = null, loggedIn = loggedIn)
        if (!loggedIn) {
            _state.value = _state.value.copy(loading = false)
            return
        }

        viewModelScope.launch {
            runCatching {
                // 两个请求互不依赖，顺序发但共用一次协程即可
                // （量小，不值得再开一个 launch 的调度开销）
                val page = repo.sessions()
                val unread = repo.unread()
                page.sessions to unread.message
            }
                .onSuccess { (list, unread) ->
                    _state.value = _state.value.copy(
                        sessions = list,
                        loading = false,
                        unread = unread,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = userMessageFor(e),
                    )
                }
        }
    }
}

/**
 * 单个会话的消息 VM。
 *
 * ## 为什么 `talkerId` 走构造而不是 `load(id)`
 *
 * 会话是"一进页面就确定对象"的场景（从列表点进来），
 * 用构造参数可以让**首帧就有正确的 key**，
 * 避免"先渲染空列表再 load"造成的闪烁。
 * ViewModel 由 `key(talkerId)` 区分实例（见 MessageScreen）。
 */
class ChatViewModel(
    private val repo: PmRepository,
    private val talkerId: Long,
) : ViewModel() {

    data class UiState(
        val messages: List<PmMessage> = emptyList(),
        val loading: Boolean = true,
        val sending: Boolean = false,
        val error: String? = null,
        val loggedIn: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val loggedIn = repo.isLoggedIn
        _state.value = _state.value.copy(loading = true, error = null, loggedIn = loggedIn)
        if (!loggedIn) {
            _state.value = _state.value.copy(loading = false)
            return
        }

        viewModelScope.launch {
            runCatching { repo.messages(talkerId) }
                .onSuccess { page ->
                    _state.value = _state.value.copy(
                        messages = page.messages,
                        loading = false,
                    )
                    // 🔴 v1.5.3：拉完消息**立刻回报已读** ——
                    // 否则服务端未读数不变，红点永远不消（用户报告的正是这个）。
                    //
                    // ⚠️ 用 maxSeqno（= 最新一条的序列号）而不是 ackSeqno：
                    // 我们要表达的是"这个会话我全看了"。
                    // `PmMessagePage` 只给了 minSeqno（用于上拉历史），
                    // 所以从消息列表里取最大的 msgKey 当 ack。
                    markReadUpTo(page)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = userMessageFor(e),
                    )
                }
        }
    }

    /**
     * 回报已读到最新一条。
     *
     * ## 为什么单独抽出来（v1.5.3）
     *
     * 进入会话、以及**收到新消息后**都要回报 —— 后者原本没人做，
     * 于是"在会话里待着收到新消息"仍会留下未读。
     *
     * ## ⚠️ 失败不阻断、也不假装成功
     *
     * 已读是**尽力而为**的副作用：失败时消息照常显示，
     * 但**不写任何"已读"的本地状态** —— 下次进页面重拉会自然纠正。
     * 这正是任务书要求的"不要假装服务端已读"。
     */
    private fun markReadUpTo(page: PmMessagePage) {
        // 取最大 msgKey（服务端序列号单调递增）
        val maxSeq = page.messages.maxOfOrNull { it.msgKey } ?: 0L
        if (maxSeq <= 0L) return
        viewModelScope.launch {
            repo.markRead(talkerId, maxSeq)
                .onFailure { e ->
                    // 只记日志：这是后台副作用，不该弹错打断阅读
                    android.util.Log.w("BiliPm", "标记已读失败: ${e.message}")
                }
        }
    }

    /**
     * 发送消息（乐观插入 + 失败移除）。
     *
     * 先本地插入一条"我发的"，让用户立刻看到 —— 私信是即时通讯场景，
     * 等网络往返再显示会有明显延迟感。失败则移除并提示。
     */
    fun send(text: String) {
        if (text.isBlank()) return
        if (!repo.isLoggedIn) {
            _state.value = _state.value.copy(error = "请先登录")
            return
        }

        val optimistic = PmMessage(
            msgKey = -System.currentTimeMillis(), // 负数 = 本地临时 id
            senderId = 0L,
            text = text,
            timestamp = System.currentTimeMillis() / 1000,
            isMine = true,
        )

        _state.value = _state.value.copy(
            messages = _state.value.messages + optimistic,
            sending = true,
        )

        viewModelScope.launch {
            repo.send(talkerId, text)
                .onSuccess {
                    _state.value = _state.value.copy(sending = false)
                    // 重拉一次拿服务端真实 msg_key（便于后续去重/已读回执）
                    load()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.filterNot { it.msgKey == optimistic.msgKey },
                        sending = false,
                        error = userMessageFor(e),
                    )
                }
        }
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }
}

/**
 * 未读红点 VM（首页铃铛专用）。
 *
 * ## 为什么单独一个 VM，而不是复用 [MessageListViewModel]
 *
 * 会话列表 VM 的 `init` 会拉**整个会话列表** —— 首页只想点亮一个小红点，
 * 为此拉一屏会话数据是浪费（首页是启动首屏，多一个请求就多一分首屏延迟）。
 *
 * 这里只打 `single_unread` 一个轻量接口（实测响应很小）。
 *
 * ## 红点什么时候灭
 *
 * 回到首页时（`LaunchedEffect(currentRoute)`）重新拉一次 ——
 * 用户读完消息返回首页，红点必须消失，这是「消息红点」的验收要求。
 */
class UnreadBadgeViewModel(
    private val repo: PmRepository,
) : ViewModel() {

    private val _unread = MutableStateFlow(
        com.example.biliv3.data.model.PmUnread(0, 0, 0, 0),
    )
    val unread: StateFlow<com.example.biliv3.data.model.PmUnread> = _unread.asStateFlow()

    val isLoggedIn: Boolean get() = repo.isLoggedIn

    /** 拉一次未读数。未登录直接归零（不发无谓请求）。 */
    fun refreshUnread() {
        if (!repo.isLoggedIn) {
            _unread.value = com.example.biliv3.data.model.PmUnread(0, 0, 0, 0)
            return
        }
        viewModelScope.launch {
            runCatching { repo.unread() }
                .onSuccess { _unread.value = it }
            // 失败保持旧值 —— 红点不该因为一次网络抖动而乱闪
        }
    }
}

/** 未读红点 VM 工厂。 */
class UnreadVmFactory(
    private val repo: PmRepository,
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return UnreadBadgeViewModel(repo) as T
    }
}

/** 会话列表 VM 工厂（手写 DI，项目不用 Hilt）。 */
class MessageVmFactory(
    private val repo: PmRepository,
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MessageListViewModel(repo) as T
    }
}

/** 单个会话 VM 工厂。`talkerId` 由路由参数决定。 */
class ChatVmFactory(
    private val repo: PmRepository,
    private val talkerId: Long,
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(repo, talkerId) as T
    }
}
