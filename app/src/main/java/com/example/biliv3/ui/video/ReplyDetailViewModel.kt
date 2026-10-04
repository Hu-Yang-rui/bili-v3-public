package com.example.biliv3.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.CommentRepository
import com.example.biliv3.data.model.CommentItem
import com.example.biliv3.ui.component.userMessageFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 楼中楼详情页 ViewModel（某条评论的全部回复）。
 *
 * ## 为什么需要这个页面
 *
 * `reply/wbi/main` 每条评论**最多内嵌 3 条回复**。
 * 首版点「查看全部 N 条回复」只是把已加载的 3 条展开
 * （`CommentSection` 的注释里也承认了这一点）——
 * 用户点完还是只有 3 条，而按钮写着"全部 N 条"，属于**信息不实**。
 *
 * 现在「查看全部」进这个独立页，用 `x/v2/reply/reply` 真正分页拉全量。
 *
 * ## 分页方式与主评论不同
 *
 * 主评论用**游标**（`next` = 上一页最后一条 rpid），
 * 这个接口用**页码**（`pn`）。两套分页混用会导致翻页错乱，
 * 所以这里单独一个 ViewModel，不硬塞进 `VideoDetailViewModel`。
 */
class ReplyDetailViewModel(
    private val repo: CommentRepository,
    private val oid: Long,
    private val root: Long,
    private val upMid: Long,
    /** 主评论（页面顶部展示，避免用户忘了自己在看哪条评论的回复）。 */
    val rootComment: CommentItem?,
) : ViewModel() {

    private val _replies = MutableStateFlow<List<CommentItem>>(emptyList())
    val replies: StateFlow<List<CommentItem>> = _replies.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /**
     * 加载失败原因。null = 没失败。
     *
     * ⚠️ 上一版失败时把 `_replies` 置空、`_hasMore` 置 false，
     * 页面显示「还没有回复」—— 用户会以为**这条评论真的没人回**，
     * 实际是请求挂了。见 `AGENTS.md` §1 自检第 7 项。
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val isLoggedIn: Boolean get() = repo.isLoggedIn

    private var page = 1

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            page = 1

            val result = runCatching {
                repo.replies(oid = oid, root = root, upMid = upMid, page = 1)
            }

            result.fold(
                onSuccess = { r ->
                    _replies.value = r.comments
                    _hasMore.value = !r.isEnd
                },
                onFailure = {
                    _replies.value = emptyList()
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
            val result = runCatching {
                repo.replies(oid = oid, root = root, upMid = upMid, page = next)
            }

            result.fold(
                onSuccess = { r ->
                    if (r.comments.isEmpty()) {
                        _hasMore.value = false
                    } else {
                        // 按 rpid 去重（翻页期间可能有新回复插入）
                        val seen = _replies.value.mapTo(HashSet()) { it.rpid }
                        _replies.value = _replies.value + r.comments.filter { it.rpid !in seen }
                        page = next
                        _hasMore.value = !r.isEnd
                    }
                },
                onFailure = {
                    // ⚠️ 翻页失败不置 hasMore=false（同 Live/Category）。
                },
            )
            _loadingMore.value = false
        }
    }

    /** 点赞（乐观更新 + 失败回滚）。 */
    fun like(comment: CommentItem) {
        if (!repo.isLoggedIn) {
            _toast.value = "请先登录"
            return
        }
        val target = !comment.liked
        val delta = if (target) 1 else -1

        _replies.value = _replies.value.map { c ->
            if (c.rpid == comment.rpid) {
                c.copy(liked = target, likeCount = (c.likeCount + delta).coerceAtLeast(0))
            } else {
                c
            }
        }

        viewModelScope.launch {
            repo.likeComment(oid = comment.oid, rpid = comment.rpid, like = target)
                .onFailure {
                    // 图标与数字一起回滚
                    _replies.value = _replies.value.map { c ->
                        if (c.rpid == comment.rpid) {
                            c.copy(
                                liked = !target,
                                likeCount = (c.likeCount - delta).coerceAtLeast(0),
                            )
                        } else {
                            c
                        }
                    }
                    _toast.value = "操作失败"
                }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun retry() = load()
}

/** 楼中楼详情 VM 工厂。 */
class ReplyDetailVmFactory(
    private val repo: CommentRepository,
    private val oid: Long,
    private val root: Long,
    private val upMid: Long,
    private val rootComment: CommentItem?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ReplyDetailViewModel(repo, oid, root, upMid, rootComment) as T
}
