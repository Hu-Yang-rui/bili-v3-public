package com.example.biliv3.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.LiveRepository
import com.example.biliv3.data.LiveRoom
import com.example.biliv3.data.LiveStream
import com.example.biliv3.data.SettingsStore
import com.example.biliv3.data.auth.AuthStore
import com.example.biliv3.data.live.LiveDanmakuClient
import com.example.biliv3.data.live.LiveMessage
import com.example.biliv3.data.live.LivePermissions
import com.example.biliv3.data.live.LiveRole
import com.example.biliv3.data.live.LiveRoleResolver
import com.example.biliv3.data.live.ModerationResult
import com.example.biliv3.player.PlayerHolder
import com.example.biliv3.ui.component.userMessageFor
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 直播间 ViewModel（应用内播放 + 实时聊天 + 房管，v1.6.4）。
 *
 * ## 它管什么
 *
 * 1. 取流 + 装配播放器（v1.6.3 已有）
 * 2. **弹幕 WebSocket**：连接、收消息、断线状态
 * 3. **权限模型**：当前账号在本房间能做什么（数据层判定）
 * 4. **房管操作**：禁言 / 解除 / 踢出 / 黑名单（含防并发）
 *
 * ## 🔴 权限为什么在 ViewModel 而不是 UI
 *
 * 需求第 6 条：不能只根据 UI 判断权限。
 * [permissions] 是唯一的判定点，UI 只**读**它决定显示哪些菜单项；
 * 每个写操作在发起前还会再查一次 [LivePermissions.canActOn]。
 *
 * ## 🔴 房管身份是**尽力而为**（已知限制）
 *
 * 房管名单接口实测**未找到**（试了 10 个路径）。所以：
 * - 主播身份**可靠**（uid 对比，`get_anchor_in_room` 实测可用）
 * - 房管身份只能靠弹幕里的 admin 标记逐步建立，**可能漏判**
 *
 * 漏判的方向是安全的（少显示入口），但会导致真实房管看不到管理菜单。
 * 这一点必须如实告知用户（UI 里有对应文案）。
 *
 * ## 消息为什么有上限
 *
 * 聊天是**无界流**。不设上限的话挂 2 小时直播会累积几万条消息 +
 * 几万个头像 URL，内存会持续增长。所以只保留最近 [MAX_MESSAGES] 条 ——
 * 与官方客户端行为一致（往上翻也是有限的）。
 */
class LiveRoomViewModel(
    private val repo: LiveRepository,
    private val room: LiveRoom,
    /** Activity 级播放器持有者（与视频详情页同一个）。 */
    val holder: PlayerHolder? = null,
    /** 设置（「隐身入场」的消费者）。 */
    private val settingsStore: SettingsStore? = null,
    /**
     * 账号存储。
     *
     * 用途有两个，**都不可省**：
     * 1. 取当前 mid → 判定主播身份 / "自己"标识
     * 2. 取 `bili_jct` → 房管写操作必需的 csrf
     *
     * ⚠️ 写操作**没有 csrf 一定失败**（服务端一律拒绝），
     * 所以未登录时管理入口直接不显示，而不是让用户点了再报错。
     */
    private val authStore: AuthStore? = null,
    /**
     * 用户资料仓库（v1.6.5，「查看弹幕发送者」用）。
     *
     * ⚠️ 复用**已有的** `SpaceRepository`（`x/web-interface/card`）——
     * 不新建用户查询通道（需求明确要求）。
     */
    private val spaceRepo: com.example.biliv3.data.SpaceRepository? = null,
) : ViewModel() {

    // ---------------- 播放（v1.6.3 已有）----------------

    private val _stream = MutableStateFlow<LiveStream?>(null)
    val stream: StateFlow<LiveStream?> = _stream.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 直播是否已结束（v1.6.6）。
     *
     * 由播放器的 `STATE_ENDED` 置位 —— 见 [watchEnded] 的说明
     * （**只在真的收到时才为 true，不猜**）。
     */
    private val _liveEnded = MutableStateFlow(false)
    val liveEnded: StateFlow<Boolean> = _liveEnded.asStateFlow()

    /** 结束监听器（用于 [onCleared] 里移除，避免泄漏）。 */
    private var endedListener: androidx.media3.common.Player.Listener? = null

    /** 挂监听器的播放器（同一个，只为移除时用）。 */
    private var endedPlayer: ExoPlayer? = null

    val player get() = holder?.player

    private var entryReported = false

    // ---------------- 聊天（v1.6.4）----------------

    /**
     * 聊天消息（**只追加、带上限**）。
     *
     * ⚠️ 用不可变 List 而不是 mutableList：Compose 的
     * `remember(list)` / `LaunchedEffect(list)` 用 `equals` 比较，
     * 传同一个可变列表的引用会让"内容变了但引用没变"的判断失效
     * （`DanmakuLayer` 踩过同一个坑，见其 KDoc）。
     */
    private val _messages = MutableStateFlow<List<LiveMessage>>(emptyList())
    val messages: StateFlow<List<LiveMessage>> = _messages.asStateFlow()

    /** 弹幕连接状态（UI 显示"已连接 / 重连中 / 失败"）。 */
    private val _connState = MutableStateFlow(LiveDanmakuClient.ConnectionState.IDLE)
    val connState: StateFlow<LiveDanmakuClient.ConnectionState> = _connState.asStateFlow()

    /** 实时人气（`op=3` 心跳回应；0 = 未取到）。 */
    private val _popularity = MutableStateFlow(0)
    val popularity: StateFlow<Int> = _popularity.asStateFlow()

    /** 消息序号 —— 用于生成稳定 key（见 [LiveMessage.stableKey]）。 */
    private var seq = 0L

    private val client = LiveDanmakuClient(viewModelScope)

    // ---------------- 权限（v1.6.4）----------------

    private val _permissions = MutableStateFlow(LivePermissions.NONE)
    val permissions: StateFlow<LivePermissions> = _permissions.asStateFlow()

    /** 主播 uid（0 = 未知）。 */
    private var anchorUid = 0L

    /**
     * 从弹幕里收集到的"疑似房管"uid 集合。
     *
     * ## 为什么需要它（以及为什么它不是权威名单）
     *
     * 房管名单接口**实测未找到**，所以无法主动查询。
     * 唯一的信号是弹幕里的 admin 标记（`info[2][2] == 1`）——
     * 只能**被动**发现"这个人是房管"，而且只有在他发言之后才知道。
     *
     * 用途仅限**给聊天里的那个用户显示"房管"徽章**，
     * **不**用于给当前账号提权（当前账号的房管身份无法通过这个途径可靠获得）。
     */
    private val knownAdmins = mutableSetOf<Long>()

    // ---------------- 房管操作状态 ----------------

    /** 正在执行的管理操作（防并发）。`null` = 空闲。 */
    private val _moderating = MutableStateFlow<String?>(null)
    val moderating: StateFlow<String?> = _moderating.asStateFlow()

    /** 一次性提示（成功 / 失败原因）。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 待确认的高风险操作（踢出 / 加黑名单）。null = 无。 */
    private val _pendingConfirm = MutableStateFlow<PendingConfirm?>(null)
    val pendingConfirm: StateFlow<PendingConfirm?> = _pendingConfirm.asStateFlow()

    // ---------------- 弹幕发送（v1.6.5）----------------

    /** 输入框内容。放在 VM 而不是 UI 的 `remember` —— 见下方 KDoc。 */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** 发送在途（防连点）。 */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** 上次发送失败原因（成功或用户继续输入时清空）。 */
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    /** 能否发送（已登录）。 */
    val canSend: Boolean get() = authStore?.isLoggedIn == true

    // ---------------- 烂梗库（v1.6.5）----------------

    /**
     * 梗库条目（**内置，本项目整理**）。
     *
     * 纯本地常量，无需加载、不会失败 —— 所以这里**没有** loading/error 状态。
     */
    private val _memes = MutableStateFlow(com.example.biliv3.data.meme.MemeLibrary.BUILT_IN)
    val memes: StateFlow<List<com.example.biliv3.data.meme.Meme>> = _memes.asStateFlow()

    /** 正在查看的用户资料（弹幕发送者查询）。null = 未打开。 */
    private val _senderProfile = MutableStateFlow<SenderProfileState?>(null)
    val senderProfile: StateFlow<SenderProfileState?> = _senderProfile.asStateFlow()

    init {
        load()
        startChat()
    }

    // ---------------------------------------------------------------------
    // 播放
    // ---------------------------------------------------------------------

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            // 重新取流 = 用户认为主播可能又开播了 → 清掉"已结束"，
            // 否则刷新后仍显示"直播已结束"，看起来像刷新没用。
            _liveEnded.value = false

            reportEntryIfNeeded()

            // ---- 直播自动最高画质（未发版）----
            //
            // ## 与原脚本做法的区别（必须说清）
            //
            // 原脚本是**运行期轮询 + 反复换档**：读 `livePlayer.getPlayerInfo()`
            // 的 `qualityCandidates`，发现当前档不是最高就调 `switchQuality()`，
            // 因为服务端/播放器可能把档位改回去。
            //
            // 本项目**不需要轮询**：请求时就已经带了 `qn=10000`（原画，
            // 见 `LiveRepository.stream`），服务端按账号权限给到能给的最高档。
            // 没有"画质被改回去"这回事，所以**不做定时器** ——
            // 加一个每秒轮询的定时器只是耗电，换不来更高画质。
            //
            // 这里保留的只是「开关」：关掉时降级请求（见 LiveRepository.stream）。
            val autoLive = runCatching {
                settingsStore?.settings?.first()?.autoQuality?.liveAutoQuality ?: true
            }.getOrDefault(true)

            val result = runCatching { repo.stream(room.roomId, autoQuality = autoLive) }
            result.fold(
                onSuccess = { s ->
                    _stream.value = s
                    if (s.playable) bind(s)
                },
                onFailure = {
                    _stream.value = null
                    _error.value = userMessageFor(it)
                },
            )
            _loading.value = false
        }
    }

    /**
     * 入场上报 —— 「隐身入场」的全部实现。
     *
     * 开关打开时**直接返回**，一个请求都不发。
     */
    private suspend fun reportEntryIfNeeded() {
        if (entryReported) return
        entryReported = true

        val incognito = runCatching {
            settingsStore?.settings?.first()?.liveIncognito ?: false
        }.getOrDefault(false)

        if (incognito) return

        runCatching { repo.reportEntry(room.roomId) }
    }

    private fun bind(s: LiveStream) {
        val h = holder ?: return
        val p = h.acquire("live-${room.roomId}")
        runCatching { p.playWhenReady = true }

        when (val r = h.bindLive(s, playWhenReady = true)) {
            is PlayerHolder.BindResult.NoSource ->
                _error.value = "该直播间没有可播放的流"
            is PlayerHolder.BindResult.Failed ->
                _error.value = "播放器初始化失败：${r.message}"
            else -> Unit
        }

        watchEnded(p)
    }

    /**
     * 观察"直播结束"（v1.6.6）。
     *
     * ## 为什么需要它
     *
     * 主播下播后 HLS 列表不再更新，播放器会把已缓冲内容播完然后停在
     * 最后一帧 —— 界面**看起来像卡死**，用户不知道发生了什么，
     * 只能反复点刷新。
     *
     * ## 复用共享播放器，不建第二套监听体系
     *
     * 直接给 `holder.player` 加一个 `Player.Listener`，
     * 只关心 `STATE_ENDED`。**不新建播放器、不新建 Controller**。
     *
     * ## ⚠️ 只在真的收到 ENDED 时才提示（不猜）
     *
     * HLS 直播下播时**未必**上报 `STATE_ENDED`（也可能表现为
     * 播放停滞在缓冲、或列表不再更新）。本项目**没有真机验证过**
     * 这个分支，所以：
     * - 收到 `STATE_ENDED` → 显示"直播已结束"
     * - 收不到 → 保持现状（现有逻辑已覆盖"未开播"的空态）
     *
     * **绝不**用"多久没更新"这类启发式去猜"结束了" ——
     * 那会在主播短暂卡顿时误报。
     *
     * ## 生命周期
     *
     * 监听器在 `onCleared` 时移除。播放器本身**不释放**（Activity 级）。
     */
    private fun watchEnded(p: ExoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    _liveEnded.value = true
                }
            }
        }
        runCatching { p.addListener(listener) }
        endedListener = listener
        endedPlayer = p
    }

    fun onPlayerError(message: String) {
        _error.value = message
    }

    /** 手动刷新（重新取流；**不重复**上报入场）。 */
    fun reload() = load()

    // ---------------------------------------------------------------------
    // 聊天
    // ---------------------------------------------------------------------

    /**
     * 连接弹幕。
     *
     * ## 顺序很重要
     *
     * 先取主播 uid（身份判定要用），再取弹幕接入信息。
     * 两者**都要 WBI 签名**（主播 uid 那个不需要，实测）。
     *
     * ## 失败必须让用户看见
     *
     * 取不到 token / host 时进 [LiveDanmakuClient.ConnectionState.FAILED]，
     * UI 显示"聊天连接失败 + 重试"。
     * **不能**只让聊天区空着 —— 那与"直播间没人说话"分不清。
     */
    private fun startChat() {
        viewModelScope.launch {
            // ① 身份（增强信息，失败不阻断）
            anchorUid = runCatching { repo.anchorUid(room.roomId) }.getOrDefault(0L)
            refreshPermissions()

            // ② 弹幕接入
            val info = runCatching { repo.danmakuInfo(room.roomId) }
            info.fold(
                onSuccess = { i ->
                    client.onMessage = { msg -> onLiveMessage(msg) }
                    client.onStateChange = { s -> _connState.value = s }
                    client.onPopularity = { p -> _popularity.value = p }
                    client.connect(
                        roomId = room.roomId,
                        token = i.token,
                        hosts = i.hosts,
                        buvid = authStore?.buvid3.orEmpty(),
                        anchorUid = anchorUid,
                    )
                },
                onFailure = { e ->
                    _connState.value = LiveDanmakuClient.ConnectionState.FAILED
                    // 如实告诉用户聊天不可用（而不是让聊天区静静空着）
                    addSystemMessage(
                        "聊天连接失败：${userMessageFor(e)}",
                    )
                },
            )
        }
    }

    /** 手动重连聊天（UI 的"重试"按钮）。 */
    fun retryChat() {
        _connState.value = LiveDanmakuClient.ConnectionState.CONNECTING
        startChat()
    }

    /**
     * 收到一条消息。
     *
     * ⚠️ 这个方法在 **OkHttp 的线程**上被调用（`onMessage` 回调）。
     * 所有状态写入都通过 `MutableStateFlow`（线程安全），
     * 但为了不阻塞 OkHttp 的读线程，这里只做"追加 + 截断"这种 O(1)/O(n) 小操作。
     */
    private fun onLiveMessage(msg: LiveMessage) {
        // 记录疑似房管（用于给那个用户显示徽章）
        if (msg.role == LiveRole.ADMIN && msg.uid > 0L) {
            synchronized(knownAdmins) { knownAdmins.add(msg.uid) }
        }

        seq++
        val cur = _messages.value
        // 只保留最近 N 条 —— 聊天是无界流，不设上限会一直涨内存
        val next = if (cur.size >= MAX_MESSAGES) {
            cur.subList(cur.size - MAX_MESSAGES + 1, cur.size) + msg
        } else {
            cur + msg
        }
        _messages.value = next
    }

    /** 加一条系统消息（连接状态、操作结果等）。 */
    private fun addSystemMessage(text: String) {
        seq++
        val cur = _messages.value
        val msg = LiveMessage(
            kind = LiveMessage.Kind.SYSTEM,
            uid = 0L,
            uname = "",
            face = "",
            text = text,
        )
        _messages.value = if (cur.size >= MAX_MESSAGES) {
            cur.subList(cur.size - MAX_MESSAGES + 1, cur.size) + msg
        } else {
            cur + msg
        }
    }

    // ---------------------------------------------------------------------
    // 权限
    // ---------------------------------------------------------------------

    /**
     * 重算权限（登录态 / 主播 uid 变化后调用）。
     *
     * ## 当前账号的房管身份怎么判定
     *
     * **判不出来**。实测没有权威接口，而弹幕里的 admin 标记
     * 只能说明"那个人是房管"，无法说明"我是房管"（我不发言就收不到）。
     *
     * 所以 [LiveRole.NORMAL] 是默认值。真实房管会**看不到管理菜单** ——
     * 这是已知限制，不是 bug。**服务端仍会正确执行**（如果有人从其它
     * 客户端操作）；本应用只是不给入口。
     *
     * 这样做的方向是安全的：不猜 = 不会显示不该有的入口。
     */
    private fun refreshPermissions() {
        val loggedIn = authStore?.isLoggedIn == true
        val selfMid = authStore?.mid ?: 0L

        // 只有"确认是主播"这一条可靠路径
        val selfRole = if (
            loggedIn && selfMid > 0L && anchorUid > 0L && selfMid == anchorUid
        ) {
            LiveRole.ANCHOR
        } else {
            LiveRole.NORMAL
        }

        _permissions.value = LivePermissions(
            loggedIn = loggedIn,
            selfMid = selfMid,
            anchorUid = anchorUid,
            selfRole = selfRole,
        )
    }

    /** 某条消息的发送者是否是"自己"（UI 高亮用）。 */
    fun isSelf(uid: Long): Boolean {
        val mid = authStore?.mid ?: 0L
        return mid > 0L && uid == mid
    }

    /** 某个 uid 是否已知是房管（用于徽章）。 */
    fun isKnownAdmin(uid: Long): Boolean =
        synchronized(knownAdmins) { uid in knownAdmins }

    // ---------------------------------------------------------------------
    // 房管操作
    // ---------------------------------------------------------------------

    /**
     * 请求执行一个管理操作。
     *
     * ## 高风险操作必须先确认（需求第 3 条）
     *
     * 踢出 / 加入黑名单是不可撤销的（对方被移出后要重新进直播间），
     * 所以走 [pendingConfirm]，由 UI 弹二次确认。
     *
     * 禁言 / 解除禁言相对可逆（解除禁言就是反向操作），直接执行。
     */
    fun requestAction(target: LiveMessage, action: LivePermissions.Action) {
        val perms = _permissions.value
        // 🔴 数据层再拦一次 —— 不依赖 UI 是否隐藏了按钮
        if (!perms.canActOn(target.uid, action)) {
            _toast.value = when {
                !perms.loggedIn -> "请先登录"
                target.uid <= 0L -> "无法识别该用户"
                perms.selfMid > 0L && target.uid == perms.selfMid -> "不能对自己操作"
                perms.anchorUid > 0L && target.uid == perms.anchorUid -> "不能对主播操作"
                else -> "权限不足：只有主播或房管可以执行该操作"
            }
            return
        }

        when (action) {
            // 高风险：先弹确认
            LivePermissions.Action.KICK,
            LivePermissions.Action.BLOCK,
            -> _pendingConfirm.value = PendingConfirm(target, action)

            // 可逆 / 低风险：直接执行
            else -> execute(target, action, durationMinutes = 0)
        }
    }

    /** 确认待处理的高风险操作。 */
    fun confirmPending() {
        val p = _pendingConfirm.value ?: return
        _pendingConfirm.value = null
        execute(p.target, p.action, durationMinutes = 0)
    }

    /**
     * 直接请求禁言（时长已由 UI 选好）。
     *
     * 与 [requestAction] 分开是因为禁言**需要额外参数**（时长），
     * 而 UI 的时长选择是一个独立弹层 —— 让它复用同一个
     * `requestAction` 会需要在签名里加一个"仅禁言有意义"的参数。
     */
    fun requestMute(target: LiveMessage, minutes: Int) {
        val perms = _permissions.value
        if (!perms.canActOn(target.uid, LivePermissions.Action.MUTE)) {
            _toast.value = "权限不足：只有主播或房管可以禁言"
            return
        }
        if (minutes <= 0) {
            _toast.value = "禁言时长必须大于 0"
            return
        }
        execute(target, LivePermissions.Action.MUTE, durationMinutes = minutes)
    }

    /** 取消待处理操作。 */
    fun cancelPending() {
        _pendingConfirm.value = null
    }

    /**
     * 真正执行。
     *
     * ## 🔴 防并发（需求第 11 条）
     *
     * `_moderating` 非空时**直接丢弃**后续请求 —— 而不是排队。
     * 管理操作不是幂等的（禁言两次会覆盖时长），排队执行会让
     * 用户"连点两下"变成两次请求，其中一次的时长可能不是他想要的。
     *
     * 这与 `toggleLike` 的防并发是同一套思路（见其 KDoc）。
     */
    private fun execute(
        target: LiveMessage,
        action: LivePermissions.Action,
        durationMinutes: Int,
    ) {
        if (_moderating.value != null) return
        val csrf = authStore?.biliJct.orEmpty()
        if (csrf.isEmpty()) {
            _toast.value = "请先登录"
            return
        }

        _moderating.value = actionLabel(action)
        viewModelScope.launch {
            val result = runCatching {
                when (action) {
                    LivePermissions.Action.MUTE ->
                        repo.mute(room.roomId, target.uid, durationMinutes, csrf)
                    LivePermissions.Action.UNMUTE ->
                        repo.unmute(room.roomId, target.uid, csrf)
                    LivePermissions.Action.KICK ->
                        repo.kick(room.roomId, target.uid, csrf)
                    LivePermissions.Action.BLOCK ->
                        repo.block(room.roomId, target.uid, csrf)
                    LivePermissions.Action.UNBLOCK ->
                        repo.unblock(room.roomId, target.uid, csrf)
                    // 房管管理：接口**未找到**，如实说明而不是假装成功。
                    //
                    // ⚠️ 这个分支现在是**防御性**的 —— 菜单已由
                    //    `LiveUserMenuModel.IMPLEMENTED` 拦掉，
                    //    正常路径**不会**走到这里。
                    //    留着是为了万一有人直接调 `requestAction`
                    //    （或将来放开了菜单但忘了实现端点）时，
                    //    仍然得到一个**明确的失败**而不是静默成功。
                    LivePermissions.Action.MANAGE_ADMIN ->
                        ModerationResult.Failure(
                            kind = ModerationResult.Failure.Kind.ENDPOINT_UNAVAILABLE,
                            message = "房管管理接口未找到，本应用暂不支持",
                        )
                }
            }.getOrElse { com.example.biliv3.data.live.LiveErrorMapper.fromException(it) }

            _moderating.value = null

            // 🔴 结果如实反馈：成功就是成功，失败把**原因**说清楚
            _toast.value = when (result) {
                is ModerationResult.Success ->
                    "${actionLabel(action)}成功：${target.uname.ifEmpty { "该用户" }}"
                is ModerationResult.Failure ->
                    "${actionLabel(action)}失败：${result.message}"
            }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    // ---------------------------------------------------------------------
    // 弹幕发送（v1.6.5）
    // ---------------------------------------------------------------------

    /** 更新草稿。用户重新输入时清掉上次的失败提示。 */
    fun setDraft(text: String) {
        _draft.value = text
        if (_sendError.value != null) _sendError.value = null
    }

    /**
     * 发送当前草稿。
     *
     * ## 🔴 输入框**不在请求前清空**（§7.15-86）
     *
     * 清空必须由"发送成功"决定。否则失败时用户刚打的内容就没了 ——
     * 而失败恰恰是最需要保留内容重试的时候。
     *
     * ## 🔴 绝不伪造成功
     *
     * 走 [LiveRepository.sendDanmaku]（真实的 `msg/send`），
     * 结果按 7 类错误如实反馈。成功才清空 + 提示。
     *
     * ## 防并发
     *
     * `_sending` 在途时直接丢弃 —— 与房管操作同一约定（§7.22-141）。
     * 弹幕发送尤其重要：连点两下会**真的发两条**（服务端不做去重）。
     */
    fun sendDraft() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        if (_sending.value) return

        val csrf = authStore?.biliJct.orEmpty()
        if (csrf.isEmpty()) {
            _sendError.value = "请先登录"
            return
        }

        _sending.value = true
        _sendError.value = null

        viewModelScope.launch {
            val result = repo.sendDanmaku(
                roomId = room.roomId,
                text = text,
                csrf = csrf,
            )
            _sending.value = false

            when (result) {
                is ModerationResult.Success -> {
                    // 成功才清空
                    _draft.value = ""
                    _toast.value = "弹幕已发送"
                }
                is ModerationResult.Failure -> {
                    // 失败：保留草稿，把原因显示在输入框下方
                    _sendError.value = result.message
                }
            }
        }
    }

    /** 直接把一段文本发出去（烂梗库的「发送」按钮）。 */
    fun sendText(text: String) {
        if (text.isBlank()) return
        _draft.value = text
        sendDraft()
    }

    /** 把一段文本填入输入框（烂梗库的「填入」，**不发送**）。 */
    fun fillDraft(text: String) {
        _draft.value = text
        _sendError.value = null
    }

    // ---------------------------------------------------------------------
    // 弹幕发送者查询（v1.6.5）
    // ---------------------------------------------------------------------

    /**
     * 查询某条弹幕的发送者资料。
     *
     * ## 复用现有用户信息接口
     *
     * 走 [com.example.biliv3.data.SpaceRepository.profile]（`x/web-interface/card`）
     * —— 项目已有的唯一用户资料接口，**不新建**查询通道。
     *
     * ## 🔴 失败显示明确错误，**不显示虚假资料**
     *
     * 需求原文：
     * > 用户信息查询失败时显示明确错误，不要显示虚假的用户资料。
     *
     * 所以失败进 [SenderProfileState.Failed]，UI 显示错误 + 重试；
     * **不会**退回显示"昵称未知 / 等级 0"这类看起来像真的的空数据。
     *
     * ## 不重复请求已存在的数据
     *
     * 需求原文：不要为了这个功能重复请求已经存在的数据。
     *
     * 弹幕本身**已经带来**了用户名 / 头像 / 粉丝牌 / 大航海 / 用户等级
     * （见 `LiveMessage`）—— 这些**直接复用消息里的值**，
     * 只为"接口才有的信息"（粉丝数、签名、等级）发一次请求。
     */
    fun loadSender(msg: LiveMessage) {
        if (msg.uid <= 0L) {
            _senderProfile.value = SenderProfileState.Failed(
                base = msg,
                message = "这条消息没有可查询的用户",
            )
            return
        }

        val spaceRepo = spaceRepo
        if (spaceRepo == null) {
            _senderProfile.value = SenderProfileState.Failed(
                base = msg,
                message = "用户资料模块不可用",
            )
            return
        }

        _senderProfile.value = SenderProfileState.Loading(msg)
        viewModelScope.launch {
            runCatching { spaceRepo.profile(msg.uid) }
                .onSuccess { p ->
                    _senderProfile.value = if (p == null) {
                        // 接口成功但没这个人 —— 如实说，不编资料
                        SenderProfileState.Failed(msg, "该用户不存在或资料不可见")
                    } else {
                        SenderProfileState.Loaded(msg, p)
                    }
                }
                .onFailure { e ->
                    _senderProfile.value = SenderProfileState.Failed(
                        base = msg,
                        message = userMessageFor(e),
                    )
                }
        }
    }

    /** 关闭发送者资料浮层。 */
    fun dismissSender() {
        _senderProfile.value = null
    }

    override fun onCleared() {
        super.onCleared()
        client.stop()

        // 移除结束监听器（v1.6.6）—— 不移除会让 listener 持有 VM，
        // 而播放器是 Activity 级的、活得比 VM 久 → 泄漏。
        val lp = endedPlayer
        val ll = endedListener
        if (lp != null && ll != null) {
            runCatching { lp.removeListener(ll) }
        }
        endedPlayer = null
        endedListener = null

        // ⚠️ **不释放播放器** —— 它是 Activity 级的（与视频详情页同一约定）
    }

    /**
     * 待二次确认的操作。
     *
     * @param target 目标用户
     * @param action 要执行的操作
     */
    data class PendingConfirm(
        val target: LiveMessage,
        val action: LivePermissions.Action,
    )

    /**
     * 「查看发送者」的状态。
     *
     * ## 为什么分 [Loading] / [Loaded] / [Failed] 三态而不是"资料 + 错误"
     *
     * 三态互斥，用密封接口天然排除"既在加载又有资料"这种非法组合
     * （与 `AiSummaryUiState` 同一思路）。
     *
     * ## ⚠️ [Failed] 也带 [base]（消息里已有的信息）
     *
     * 因为**弹幕本身已经带来了**用户名/头像/身份 —— 那些是**确定的**，
     * 不该因为"接口查询失败"就一起不显示。
     * 失败时显示"消息里的信息 + 查询失败原因"，而不是一片空白。
     */
    sealed interface SenderProfileState {
        /** 消息里已有的基础信息（永远可用）。 */
        val base: LiveMessage

        /** 正在查询接口补充信息。 */
        data class Loading(override val base: LiveMessage) : SenderProfileState

        /** 查询成功。 */
        data class Loaded(
            override val base: LiveMessage,
            val profile: com.example.biliv3.data.SpaceProfile,
        ) : SenderProfileState

        /** 查询失败（**不显示虚假资料**）。 */
        data class Failed(
            override val base: LiveMessage,
            val message: String,
        ) : SenderProfileState
    }

    companion object {
        /**
         * 聊天消息上限。
         *
         * 200 条 ≈ 手机屏幕上翻十几屏。再多用户也不会看，
         * 但每条都持有用户名/头像 URL，挂着不清理会持续吃内存。
         */
        const val MAX_MESSAGES = 200

        /** 操作的中文名（提示文案用）。 */
        fun actionLabel(action: LivePermissions.Action): String = when (action) {
            LivePermissions.Action.MUTE -> "禁言"
            LivePermissions.Action.UNMUTE -> "解除禁言"
            LivePermissions.Action.KICK -> "踢出直播间"
            LivePermissions.Action.BLOCK -> "加入黑名单"
            LivePermissions.Action.UNBLOCK -> "移出黑名单"
            LivePermissions.Action.MANAGE_ADMIN -> "房管管理"
        }
    }
}

/**
 * 直播间 VM 工厂。
 *
 * ⚠️ 参数与 [LiveRoomViewModel] 的构造参数**一一对应**。
 * 少传一个（尤其是 `authStore`）会让权限判定恒为未登录、
 * 管理入口永远不出现 —— 而这类"静默少传"不会有编译错误，
 * 因为其它参数都有默认值。
 */
class LiveRoomVmFactory(
    private val repo: LiveRepository,
    private val room: LiveRoom,
    private val holder: PlayerHolder?,
    private val settingsStore: SettingsStore?,
    /** 账号存储：取 mid（身份判定）与 csrf（写操作必需）。 */
    private val authStore: AuthStore? = null,
    /** 用户资料（「查看发送者」）。复用已有 SpaceRepository。 */
    private val spaceRepo: com.example.biliv3.data.SpaceRepository? = null,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LiveRoomViewModel(
            repo, room, holder, settingsStore, authStore, spaceRepo,
        ) as T
}
