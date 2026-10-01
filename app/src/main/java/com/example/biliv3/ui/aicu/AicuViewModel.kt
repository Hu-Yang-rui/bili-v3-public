package com.example.biliv3.ui.aicu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.AicuLiveDanmaku
import com.example.biliv3.data.AicuRepository
import com.example.biliv3.data.AicuReply
import com.example.biliv3.data.AicuUserCard
import com.example.biliv3.data.AicuUserMark
import com.example.biliv3.data.AicuVideoDanmaku
import com.example.biliv3.data.api.AicuException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 三个 Tab。 */
enum class AicuTab(val label: String) {
    REPLIES("评论"),
    VIDEO_DANMAKU("视频弹幕"),
    LIVE_DANMAKU("直播弹幕"),
}

/**
 * aicu 查成分 ViewModel。
 *
 * ## 三态齐全（本项目硬性要求）
 *
 * | 场景 | 显示 |
 * |---|---|
 * | 还没查（首屏） | 引导态：「输入 UID 后点查询」 |
 * | 查询中 | 加载中 |
 * | 查到了但为空 | 空态：「该 UID 没有公开的评论/弹幕」 |
 * | 失败 | 错误 + 重试（**原因要具体**） |
 *
 * ## ⚠️ 为什么没有「按 UP 查直播」Tab
 *
 * aicu 的 `/api/v4/search/getlivebyup` 实测返回
 * `{"code":-403,"message":"暂时关闭 不调好不发布"}`。
 * 给它挂 UI 就是死入口（看着能点、点了报错），所以**不做这个 Tab**。
 *
 * ## 为什么首屏不发请求
 *
 * aicu 有强制排队机制（ticket），每次查询都要排队。
 * 进页面就自动查一个默认 UID 会白白消耗排队额度，
 * 也让"没输入 UID 就查到别人的数据"这件事显得莫名其妙。
 * 所以必须是**用户显式输入 + 点查询**。
 */
class AicuViewModel(
    private val repo: AicuRepository,
    /** 从用户主页带过来的 UID（可空）。有值时自动查一次。 */
    initialUid: Long? = null,
) : ViewModel() {

    // ---- 查询输入 ----

    private val _uidInput = MutableStateFlow(initialUid?.toString().orEmpty())
    val uidInput: StateFlow<String> = _uidInput.asStateFlow()

    private val _tab = MutableStateFlow(AicuTab.REPLIES)
    val tab: StateFlow<AicuTab> = _tab.asStateFlow()

    /** 已提交查询的 UID（与输入框区分：输入框改动不该立即触发查询）。 */
    private val _queryUid = MutableStateFlow(initialUid)
    val queryUid: StateFlow<Long?> = _queryUid.asStateFlow()

    // ---- 三态 ----

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    // ---- 数据 ----

    private val _replies = MutableStateFlow<List<AicuReply>>(emptyList())
    val replies: StateFlow<List<AicuReply>> = _replies.asStateFlow()

    private val _videoDanmaku = MutableStateFlow<List<AicuVideoDanmaku>>(emptyList())
    val videoDanmaku: StateFlow<List<AicuVideoDanmaku>> = _videoDanmaku.asStateFlow()

    private val _liveDanmaku = MutableStateFlow<List<AicuLiveDanmaku>>(emptyList())
    val liveDanmaku: StateFlow<List<AicuLiveDanmaku>> = _liveDanmaku.asStateFlow()

    /** 评论真实总数。**-1 表示本次没请求真实总数**（不是"0 条"）。 */
    private val _replyTotal = MutableStateFlow(-1)
    val replyTotal: StateFlow<Int> = _replyTotal.asStateFlow()

    private val _userMark = MutableStateFlow<AicuUserMark?>(null)
    val userMark: StateFlow<AicuUserMark?> = _userMark.asStateFlow()

    private val _userCard = MutableStateFlow<AicuUserCard?>(null)
    val userCard: StateFlow<AicuUserCard?> = _userCard.asStateFlow()

    private var page = 1

    init {
        // 从用户主页进来时带了 UID → 自动查一次（用户意图明确）
        if (initialUid != null && initialUid > 0L) load(reset = true)
    }

    // ---- 交互 ----

    fun onUidInputChange(value: String) {
        // 只收数字：UID 必然是纯数字，提前挡掉脏输入
        _uidInput.value = value.filter { it.isDigit() }.take(12)
    }

    fun selectTab(t: AicuTab) {
        _tab.value = t
    }

    /** 点查询。UID 非法时给出明确提示，而不是静默什么都不做。 */
    fun submit() {
        val uid = _uidInput.value.toLongOrNull()
        if (uid == null || uid <= 0L) {
            _error.value = "请输入有效的 UID（纯数字）"
            return
        }
        _queryUid.value = uid
        load(reset = true)
    }

    fun retry() {
        if (_queryUid.value != null) load(reset = true)
    }

    /**
     * 拉取当前 Tab 的数据。
     *
     * @param reset true 重置到第 1 页
     */
    fun load(reset: Boolean = false) {
        val uid = _queryUid.value ?: return

        viewModelScope.launch {
            if (reset) {
                _loading.value = true
                _error.value = null
                page = 1
                _hasMore.value = false
                // 换 UID 时清空旧数据，避免"新 UID 配旧内容"
                _replies.value = emptyList()
                _videoDanmaku.value = emptyList()
                _liveDanmaku.value = emptyList()
                _replyTotal.value = -1
                _userMark.value = null
                _userCard.value = null
            }

            val error = runCatching {
                when (_tab.value) {
                    AicuTab.REPLIES -> {
                        val p = repo.replies(uid, page = page)
                        _replies.value = if (reset) p.items else _replies.value + p.items
                        _replyTotal.value = p.allCount
                        _hasMore.value = p.items.isNotEmpty() && p.items.size >= PAGE_SIZE
                    }

                    AicuTab.VIDEO_DANMAKU -> {
                        val p = repo.videoDanmaku(uid, page = page)
                        _videoDanmaku.value = if (reset) p.items else _videoDanmaku.value + p.items
                        _hasMore.value = p.items.isNotEmpty() && p.items.size >= PAGE_SIZE
                    }

                    AicuTab.LIVE_DANMAKU -> {
                        val p = repo.liveDanmaku(uid, page = page)
                        _liveDanmaku.value = if (reset) p.items else _liveDanmaku.value + p.items
                        _hasMore.value = p.items.isNotEmpty() && p.items.size >= PAGE_SIZE
                    }
                }
                // 成分标记与资料卡是增强信息：并行拉、失败静默
                if (reset) {
                    _userMark.value = repo.userMark(uid)
                    _userCard.value = repo.userCard(uid)
                }
                null
            }.getOrElse { it }

            _error.value = error?.let { userMessageFor(it) }
            _loading.value = false
            _loadingMore.value = false
        }
    }

    /** 触底加载下一页。 */
    fun loadMore() {
        if (_loading.value || _loadingMore.value || !_hasMore.value) return
        _loadingMore.value = true
        page += 1
        load(reset = false)
    }

    /** 把异常翻译成具体可操作的文案。 */
    internal fun userMessageFor(e: Throwable): String = when (e) {
        is AicuException -> when (e.httpCode) {
            -419 -> "排队凭据已过期，请重试"
            -403 -> "该功能在 aicu 侧已关闭"
            else -> e.message.ifEmpty { "查询失败（${e.httpCode}）" }
        }
        else -> {
            val s = e.message.orEmpty()
            when {
                s.contains("UnknownHost", true) || s.contains("Unable to resolve host") ->
                    "DNS 解析失败，请检查网络"
                s.contains("timeout", true) ->
                    "请求超时（aicu 有时较慢），请重试"
                s.contains("Failed to connect") || s.contains("Connection") ->
                    "连接 aicu 失败，请检查网络后重试"
                else -> "查询失败，请稍后重试"
            }
        }
    }

    companion object {
        /** 每页条数。与 aicu 前端默认值一致。 */
        const val PAGE_SIZE = 20
    }
}

/** aicu VM 工厂。 */
class AicuVmFactory(
    private val repo: AicuRepository,
    private val initialUid: Long? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AicuViewModel(repo, initialUid) as T
}
