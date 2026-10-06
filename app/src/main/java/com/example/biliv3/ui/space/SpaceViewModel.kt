package com.example.biliv3.ui.space

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.DynamicItem
import com.example.biliv3.data.DynamicRepository
import com.example.biliv3.data.SpaceProfile
import com.example.biliv3.data.SpaceRepository
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.ui.component.userMessageFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 用户主页 ViewModel。
 *
 * ## 三个 Tab 的数据来源
 *
 * | Tab | 来源 | 失败表现 |
 * |---|---|---|
 * | 主页（投稿） | `space/wbi/arc/search` | 空列表（增强模块） |
 * | 动态 | `polymer/web-dynamic/v1/feed/space` | 空列表 |
 * | 资料 | `x/web-interface/card` | 整页错误态（**核心**） |
 *
 * 资料失败才是错误 —— 没有昵称/头像的主页没有意义。
 * 投稿与动态失败只影响各自 Tab。
 *
 * ## 关注态的乐观更新
 *
 * 关注按钮点了立刻变（本地先改），失败回滚并提示。
 * 与点赞同理：等网络往返会让用户以为没点上而重复点。
 */
class SpaceViewModel(
    private val repo: SpaceRepository,
    private val dynamicRepo: DynamicRepository? = null,
    private val mid: Long,
    /**
     * 「特别关注」的本地存储（v1.6.3）。
     *
     * ## 🔴 它**不能**发关注请求
     *
     * `LocalAttentionStore` 只持有 `Context`，**没有** `BiliApi`、
     * 没有 `OkHttpClient` —— 所以"特别关注"在类型上就不可能
     * 触发真实关注。这是需求「不执行真实 B 站关注」的编译期保证。
     *
     * 传 null = 该功能不可用（预览/测试环境），此时 UI 不渲染入口。
     */
    private val attentionStore: com.example.biliv3.data.LocalAttentionStore? = null,
) : ViewModel() {

    private val _profile = MutableStateFlow<SpaceProfile?>(null)
    val profile: StateFlow<SpaceProfile?> = _profile.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _dynamics = MutableStateFlow<List<DynamicItem>>(emptyList())
    val dynamics: StateFlow<List<DynamicItem>> = _dynamics.asStateFlow()

    private val _dynamicLoading = MutableStateFlow(false)
    val dynamicLoading: StateFlow<Boolean> = _dynamicLoading.asStateFlow()

    /** 是否已关注。 */
    private val _following = MutableStateFlow(false)
    val following: StateFlow<Boolean> = _following.asStateFlow()

    /** 关注数（本地乐观 +1/-1 用）。 */
    private val _fans = MutableStateFlow(0)
    val fans: StateFlow<Int> = _fans.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 关注按钮是否在请求中（防连点）。 */
    private val _followPending = MutableStateFlow(false)
    val followPending: StateFlow<Boolean> = _followPending.asStateFlow()

    // ---------------------------------------------------------------------
    // 特别关注（本地书签，v1.6.3）
    // ---------------------------------------------------------------------

    /**
     * 是否已在「特别关注」里。
     *
     * ⚠️ 与 [following]（B 站**真实**关注态）是**两个独立的量** ——
     * 不合并、不互推。用户可能"真实关注了但没特别关注"，
     * 也可能"特别关注了但没真实关注"。合并任意一个方向都会产生
     * 假的同步关系。
     */
    private val _attended = MutableStateFlow(false)
    val attended: StateFlow<Boolean> = _attended.asStateFlow()

    /** 该功能是否可用（存储已注入）。 */
    val attentionAvailable: Boolean get() = attentionStore != null

    /**
     * 切换特别关注（**纯本地**，无网络、无 csrf、不需要登录）。
     *
     * ## 为什么不需要登录
     *
     * 它只是本机的一个书签。要求登录才能"标记自己想留意的人"
     * 是没有道理的 —— 而且会让未登录用户体验不到这个功能。
     */
    fun toggleAttention() {
        val store = attentionStore ?: return
        val p = _profile.value
        if (p == null) {
            _toast.value = "用户资料还没加载完，请稍后再试"
            return
        }

        val target = !_attended.value
        // 乐观更新（本地写盘很快，但 UI 要立刻反馈）
        _attended.value = target
        _toast.value = if (target) "已加入特别关注（仅本机）" else "已取消特别关注"

        viewModelScope.launch {
            val ok = runCatching {
                store.toggle(
                    com.example.biliv3.data.AttendedUser(
                        mid = p.mid,
                        name = p.name,
                        face = p.face,
                    ),
                )
            }.getOrNull()
            // 写盘失败 → 回滚 + 如实告知（不能留下"看着加上了、实际没存"的假象）
            if (ok == null) {
                _attended.value = !target
                _toast.value = "本地保存失败，请稍后重试"
            } else {
                _attended.value = ok
            }
        }
    }

    val isLoggedIn: Boolean get() = repo.isLoggedIn

    /** 是否是自己（自己的主页不显示关注按钮，显示"编辑资料"占位）。 */
    val isSelf: Boolean get() = repo.currentMid > 0 && repo.currentMid == mid

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            runCatching { repo.profile(mid) }
                .onSuccess { p ->
                    if (p == null) {
                        _error.value = "用户不存在或资料不可见"
                    } else {
                        _profile.value = p
                        _fans.value = p.fans
                    }
                }
                .onFailure { _error.value = userMessageFor(it) }

            _loading.value = false

            // 投稿与关注态并行拉（都是增强信息）
            launch { loadVideos() }
            launch { loadFollowState() }
            launch { loadAttentionState() }
        }
    }

    /** 读本地"特别关注"状态（纯本地，无网络）。 */
    private suspend fun loadAttentionState() {
        val store = attentionStore ?: return
        _attended.value = runCatching { store.contains(mid) }.getOrDefault(false)
    }

    private suspend fun loadVideos() {
        val list = runCatching { repo.videos(mid) }.getOrDefault(emptyList())
        // 投稿接口不返回头像 —— 用资料卡的头像补上，
        // 否则每张卡片左侧都是一个空占位（看起来像加载失败）
        val face = _profile.value?.face.orEmpty()
        _videos.value = if (face.isEmpty()) {
            list
        } else {
            list.map { it.copy(authorFace = face) }
        }
    }

    private suspend fun loadFollowState() {
        if (!isLoggedIn) {
            _following.value = false
            return
        }
        _following.value = runCatching { repo.isFollowing(mid) }.getOrDefault(false)
    }

    /** 拉动态（切到「动态」Tab 时才调，避免进页面就打三个接口）。 */
    fun loadDynamics() {
        val dr = dynamicRepo ?: return
        if (_dynamicLoading.value || _dynamics.value.isNotEmpty()) return

        viewModelScope.launch {
            _dynamicLoading.value = true
            val page = runCatching { dr.spaceFeed(mid) }.getOrNull()
            _dynamics.value = page?.items ?: emptyList()
            _dynamicLoading.value = false
        }
    }

    /**
     * 关注 / 取关（乐观更新 + 失败回滚）。
     *
     * 粉丝数同时 +1/-1 —— 只改按钮不改数字会出现"已关注但粉丝数没变"，
     * 是「关注类」验收明确禁止的状态不同步。
     */
    fun toggleFollow() {
        if (!isLoggedIn) {
            _toast.value = "请先登录"
            return
        }
        if (_followPending.value) return

        val target = !_following.value
        val prevFans = _fans.value

        _following.value = target
        _fans.value = (prevFans + if (target) 1 else -1).coerceAtLeast(0)
        _followPending.value = true

        viewModelScope.launch {
            repo.setFollow(mid, target)
                .onSuccess {
                    _toast.value = if (target) "已关注" else "已取消关注"
                }
                .onFailure { e ->
                    // 回滚（按钮 + 粉丝数一起）
                    _following.value = !target
                    _fans.value = prevFans
                    _toast.value = userMessageFor(e)
                }
            _followPending.value = false
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun retry() = load()
}

/** 用户主页 VM 工厂。 */
class SpaceVmFactory(
    private val repo: SpaceRepository,
    private val dynamicRepo: DynamicRepository?,
    private val mid: Long,
    /** 特别关注的本地存储（v1.6.3）。传 null 时该功能不渲染入口。 */
    private val attentionStore: com.example.biliv3.data.LocalAttentionStore? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SpaceViewModel(repo, dynamicRepo, mid, attentionStore) as T
}
