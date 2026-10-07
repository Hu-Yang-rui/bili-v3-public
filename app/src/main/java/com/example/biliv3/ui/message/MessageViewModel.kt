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
    /**
     * 待发送的分享内容（v1.6.7）。
     *
     * 从视频详情页「分享 → B站好友」挂载；本页进入后**自动发送一次**。
     * null = 不是从分享进来的（普通聊天）。
     */
    private val pendingShare: com.example.biliv3.data.PendingShare? = null,
    /**
     * 表情仓库（v1.6.8）。
     *
     * null = 表情功能不可用（预览/测试环境），此时 UI 不渲染表情入口。
     */
    private val emoteRepo: com.example.biliv3.data.emote.EmoteRepository? = null,
) : ViewModel() {

    data class UiState(
        val messages: List<PmMessage> = emptyList(),
        val loading: Boolean = true,
        val sending: Boolean = false,
        val error: String? = null,
        val loggedIn: Boolean = false,
        /**
         * 自动发送分享内容的结果（v1.6.7）。
         *
         * `null` = 没有自动发送过。
         * 非 null 时 UI 显示一次性提示（成功 / 真实失败原因）。
         */
        val shareResult: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 是否已经尝试过自动发送。
     *
     * ## 🔴 防重复发送的关键
     *
     * `load()` 在会话页可能被调用多次（收到新消息后刷新、用户下拉刷新）。
     * 没有这个标志的话，**每次刷新都会再发一遍分享内容** ——
     * 那是明确的刷屏行为。
     *
     * ⚠️ 用普通 `var` 而不是 StateFlow：它只用于"发没发过"的判定，
     *    不需要触发重组。
     */
    private var shareAttempted = false

    // ---------------------------------------------------------------------
    // 表情（v1.6.8）
    // ---------------------------------------------------------------------
    //
    // ⚠️ **这些字段必须声明在 `init` 之前** —— Kotlin 的属性初始化器
    //    按**声明顺序**执行，而 `init` 也是一个初始化器。
    //    曾经把 `init { loadEmotes() }` 放在这些字段**之前**，
    //    结果 `loadEmotes()` 里读 `_emoteLoading.value` 时它还是 null →
    //    `NullPointerException: MutableStateFlow.getValue() on a null reference`
    //    → **App 一进会话页就崩**。
    //
    //    判据：**`init` 里用到的字段，声明必须在 `init` 之上。**
    //    这类崩溃在编译期完全看不出来。

    /** 表情包列表（UI 直接渲染）。 */
    private val _emotePackages = MutableStateFlow<List<com.example.biliv3.data.emote.EmotePackage>>(
        emptyList(),
    )
    val emotePackages: StateFlow<List<com.example.biliv3.data.emote.EmotePackage>> =
        _emotePackages.asStateFlow()

    /** 表情加载中。 */
    private val _emoteLoading = MutableStateFlow(false)
    val emoteLoading: StateFlow<Boolean> = _emoteLoading.asStateFlow()

    /** 表情加载的问题（null = 正常）。**不影响聊天本身**。 */
    private val _emoteError = MutableStateFlow<String?>(null)
    val emoteError: StateFlow<String?> = _emoteError.asStateFlow()

    /** 表情面板是否展开。 */
    private val _emotePanelOpen = MutableStateFlow(false)
    val emotePanelOpen: StateFlow<Boolean> = _emotePanelOpen.asStateFlow()

    /**
     * 待插入输入框的表情 token（见 [insertEmote]）。
     *
     * ⚠️ 也必须声明在 `init` 之前（同上面的初始化顺序说明）。
     */
    private val _pendingEmote = MutableStateFlow<String?>(null)
    val pendingEmote: StateFlow<String?> = _pendingEmote.asStateFlow()

    /**
     * 初始化。
     *
     * 🔴 **必须放在所有 `StateFlow` 字段声明之后** ——
     * Kotlin 按声明顺序执行初始化器，`init` 里会读写这些字段。
     * 放前面会 NPE（本项目真实踩过，见上方注释）。
     */
    init {
        load()
        loadEmotes()
    }

    /**
     * 加载表情面板。
     *
     * ## 失败不影响聊天
     *
     * 表情是**附加能力** —— 拉不到时聊天照常可用，只把原因写进
     * [_emoteError] 让面板显示一行提示。**不写 `_state.error`**
     * （那是聊天本身的错误，混在一起会让用户以为消息发不出去了）。
     */
    fun loadEmotes() {
        val r = emoteRepo ?: return
        if (_emoteLoading.value) return
        _emoteLoading.value = true
        viewModelScope.launch {
            val load = runCatching { r.load() }.getOrNull()
            if (load != null) {
                _emotePackages.value = load.packages
                _emoteError.value = load.error
            } else {
                _emoteError.value = "表情加载失败"
            }
            _emoteLoading.value = false
        }
    }

    /** 展开 / 收起表情面板。展开时若还没数据就拉一次。 */
    fun toggleEmotePanel() {
        val next = !_emotePanelOpen.value
        _emotePanelOpen.value = next
        if (next && _emotePackages.value.isEmpty() && !_emoteLoading.value) {
            loadEmotes()
        }
    }

    fun closeEmotePanel() {
        _emotePanelOpen.value = false
    }

    /**
     * 待插入输入框的表情 token（v1.6.8）。
     *
     * ## 🔴 为什么走"一次性信号"而不是直接改草稿
     *
     * 草稿（`draft`）是 **ChatScreen 的 `remember` 局部状态**，
     * ViewModel 拿不到它 —— 也不该拿（草稿是纯 UI 状态，
     * 提升到 VM 会让"返回再进"时残留上一条草稿）。
     *
     * 所以 VM 只发出"用户选了哪个 token"这个**事件**，
     * 由 Screen 消费后追加到自己那份草稿里。
     *
     * 消费后必须 [consumeEmoteInsert] 清掉 —— 否则重组会**重复插入**
     * （用户点一次表情，输入框里出现两个）。
     *
     * ⚠️ 字段本身声明在 `init` **之前**（见那里的初始化顺序说明）。
     */

    /**
     * 用户选了一个表情。
     *
     * ## 🔴 传的是**官方 token**，不是表情名
     *
     * 实测：表情的 `text` 字段（如 `[doge_金箍]`）就是 B 站识别表情的
     * 内联 token。插 token 服务端才会渲染成图；插表情名（`金箍`）
     * 发出去只是普通文字 —— 那正是任务书禁止的做法。
     *
     * @param token `Emote.token`（调用方直接传它，不要自己拼）
     */
    fun insertEmote(token: String) {
        if (token.isBlank()) return
        _pendingEmote.value = token
    }

    /** 插入已消费（Screen 追加到草稿后调用，防重复插入）。 */
    fun consumeEmoteInsert() {
        _pendingEmote.value = null
    }

    /**
     * 表情功能是否可用。
     *
     * `false` 时 UI **不渲染表情按钮** —— 点了没反应的按钮是死入口（§1.6）。
     */
    val emoteAvailable: Boolean get() = emoteRepo != null

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

                    // 分享自动发送（v1.6.7）—— 在消息加载完之后做
                    maybeAutoSendShare()
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
     * 自动发送分享内容（v1.6.7）。
     *
     * ---
     *
     * # 为什么在"消息加载完之后"发
     *
     * 用户点进会话时，屏幕上会先出现历史消息。如果**同时**插入
     * 一条"正在发送"的乐观消息，它会与历史消息的渲染竞争，
     * 视觉上像是历史里本来就有一条待发。
     *
     * 等加载完再发，时序上是"看到历史 → 发出分享"，符合真实顺序。
     *
     * # 🔴 三重防重复
     *
     * 1. `shareAttempted` —— 本 VM 实例只尝试一次
     *    （`load()` 可能因刷新被多次调用）
     * 2. `pendingShare.take()` —— **取走即清空**，
     *    即使 VM 被重建也不会再拿到同一份内容
     * 3. `repo.send` 有 `_state.sending` 守卫 —— 发送中不接受第二次
     *
     * 没有这三重，"切走再回来"或"收到新消息触发刷新"都会**重复发送**，
     * 而那是明确的刷屏行为（任务书禁止）。
     *
     * # 失败处理
     *
     * 失败时**不清空 pendingShare 的语义结果**，而是把真实原因写进
     * `shareResult` 让 UI 显示 —— 用户至少知道"没发出去"，
     * 并且剪贴板里还有链接可以手动发（分享时已复制）。
     *
     * **绝不**在失败时显示"已发送"。
     */
    private fun maybeAutoSendShare() {
        if (shareAttempted) return
        val share = pendingShare ?: return

        // take() 是"取走即清空" —— 拿不到就是没有待发送内容
        val pending = share.take() ?: return
        shareAttempted = true

        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            val result = repo.send(talkerId, pending.body)
            _state.value = _state.value.copy(
                sending = false,
                shareResult = result.fold(
                    // ⚠️ `onSuccess` 也要写成 lambda —— `Result.fold` 的两个
                    //    参数都是函数类型，直接传字符串会类型不匹配。
                    onSuccess = { "已发送分享" },
                    onFailure = { e -> "分享发送失败：${userMessageFor(e)}" },
                ),
            )
            // 成功后刷新消息列表，让刚发的那条出现
            // （send 内部已乐观插入，这里只是确保与服务端一致）
            if (result.isSuccess) load()
        }
    }

    /** 消费一次性分享提示（UI 显示过后调用）。 */
    fun consumeShareResult() {
        _state.value = _state.value.copy(shareResult = null)
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
    /** 待发送的分享内容（v1.6.7）。null = 普通聊天。 */
    private val pendingShare: com.example.biliv3.data.PendingShare? = null,
    /** 表情仓库（v1.6.8）。null = 不渲染表情入口。 */
    private val emoteRepo: com.example.biliv3.data.emote.EmoteRepository? = null,
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(repo, talkerId, pendingShare, emoteRepo) as T
    }
}
