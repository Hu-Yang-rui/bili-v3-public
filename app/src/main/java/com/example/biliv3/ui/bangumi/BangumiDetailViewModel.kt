package com.example.biliv3.ui.bangumi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.BangumiDetail
import com.example.biliv3.data.BangumiEpisode
import com.example.biliv3.data.BangumiRepository
import com.example.biliv3.ui.component.userMessageFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 番剧详情 ViewModel。
 *
 * ## 为什么需要它（番剧条目此前不可点）
 *
 * 首版 `BangumiScreen` 的 `onItemClick = { }` 是**空 lambda** ——
 * 番剧卡片看着能点、点了没反应，是明确的死入口。
 *
 * 根因写在当时的注释里：番剧播放需要 `ep_id` 取流
 * （`pgc/player/web/playurl`），与 UGC 的 `bvid` 是**两套体系**，
 * 硬跳视频详情页会因为 bvid 非法而报错。
 *
 * 现在的做法：
 * 1. 番剧条目 → **番剧详情页**（本页），展示分集列表
 * 2. 点某一集 → 该集如果有 `bvid`（部分番剧的 PGC 内容同时有 UGC 稿件），
 *    直接跳视频详情页播放；没有 bvid 时明确提示
 *    「该集需在官方 App 观看」（不做假的可点状态）
 *
 * 这样至少：条目可点、能看到全部剧集信息、能播的集能播。
 */
class BangumiDetailViewModel(
    private val repo: BangumiRepository,
    private val seasonId: Long,
    /** 凭据（追番写操作需要）。传 null 时追番按钮引导登录。 */
    private val store: com.example.biliv3.data.auth.AuthStore? = null,
) : ViewModel() {

    private val _detail = MutableStateFlow<BangumiDetail?>(null)
    val detail: StateFlow<BangumiDetail?> = _detail.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 是否已追番（乐观更新用）。 */
    private val _following = MutableStateFlow(false)
    val following: StateFlow<Boolean> = _following.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val isLoggedIn: Boolean get() = store?.isLoggedIn == true

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            // ⚠️ 现在 `repo.season` 失败会**抛异常**（v1.2.5），
            // 所以这里要区分"抛了"与"返回 null"：
            // 抛出 = 请求/接口错误；null 只可能是 seasonId 非法。
            runCatching { repo.season(seasonId) }.fold(
                onSuccess = { d ->
                    if (d == null) {
                        _error.value = "该剧集不存在或已下架"
                    } else {
                        _detail.value = d
                        _following.value = d.isFollowed
                    }
                },
                onFailure = { _error.value = userMessageFor(it) },
            )
            _loading.value = false
        }
    }

    /**
     * 追番 / 取消追番（乐观更新 + 失败回滚）。
     *
     * ## 回滚必须同时恢复按钮与追番人数
     *
     * 只改一个会出现"已追番但人数没变"这种不自洽的状态 ——
     * 这是「关注类」验收明确禁止的状态不同步。
     */
    fun toggleFollow() {
        val st = store
        if (st == null || !st.isLoggedIn) {
            _toast.value = "请先登录"
            return
        }

        val target = !_following.value
        val prevCount = _detail.value?.followCount ?: 0

        _following.value = target
        _detail.value = _detail.value?.copy(
            isFollowed = target,
            followCount = (prevCount + if (target) 1 else -1).coerceAtLeast(0),
        )

        viewModelScope.launch {
            repo.setFollow(seasonId, target, st)
                .onSuccess { _toast.value = if (target) "已追番" else "已取消追番" }
                .onFailure { e ->
                    // 回滚（按钮 + 人数一起）
                    _following.value = !target
                    _detail.value = _detail.value?.copy(
                        isFollowed = !target,
                        followCount = prevCount,
                    )
                    _toast.value = e.message ?: "操作失败"
                }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun retry() = load()
}

/** 番剧详情 VM 工厂。 */
class BangumiDetailVmFactory(
    private val repo: BangumiRepository,
    private val seasonId: Long,
    private val store: com.example.biliv3.data.auth.AuthStore? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        BangumiDetailViewModel(repo, seasonId, store) as T
}

/** 某一集是否可在本应用内播放（有 bvid 才行）。 */
fun BangumiEpisode.playableInApp(): Boolean = bvid.isNotEmpty()
