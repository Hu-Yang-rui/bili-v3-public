package com.example.biliv3.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.InteractionRepository
import com.example.biliv3.data.NotLoggedInException
import com.example.biliv3.data.VideoRepository
import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.InteractionState
import com.example.biliv3.data.danmaku.DanmakuItem
import com.example.biliv3.data.CommentRepository
import com.example.biliv3.data.danmaku.DanmakuRepository
import com.example.biliv3.data.model.CommentItem
import com.example.biliv3.data.model.PlayInfo
import com.example.biliv3.data.model.VideoDetail
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.data.subtitle.SubtitleBody
import com.example.biliv3.data.subtitle.SubtitleRepository
import com.example.biliv3.data.subtitle.SubtitleTrack
import com.example.biliv3.ui.component.userMessageFor
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 视频详情页状态。
 *
 * ## 为什么详情和取流分开两个状态
 *
 * 详情（标题/UP/简介）很快，取流要签名 + CDN 调度，慢一些。
 * 合成一个状态的话，取流没回来就整页空白 —— 用户盯着白屏等。
 */
sealed interface DetailUiState {
    data object Loading : DetailUiState

    data class Content(
        val detail: VideoDetail,
        val related: List<VideoItem> = emptyList(),
    ) : DetailUiState

    data class Error(val message: String) : DetailUiState
}

/**
 * 播放状态。
 *
 * ## ⚠️ [NotStarted]：进页面不自动取流
 *
 * 之前的实现是一进详情页就自动取流 + 播放，问题有两个：
 * 1. **浪费流量**：用户可能只想看简介，并不想看视频
 * 2. **拖慢页面**：取流是重请求（签名 + CDN 调度），与详情渲染抢带宽
 *
 * 现在进页面只显示**封面**，点击后才取流播放。
 */
sealed interface PlayState {
    /** 尚未起播（只显示封面）。 */
    data object NotStarted : PlayState

    data object Loading : PlayState

    data class Ready(val info: PlayInfo) : PlayState

    data class Failed(val message: String) : PlayState
}

/**
 * 详情页 ViewModel。
 *
 * @param bvid 从导航参数传入。
 * @param interactions 互动仓库；传 null 时互动功能整体不可用（如预览环境）。
 * @param subtitleRepo 字幕仓库；传 null 时字幕功能不可用。
 * @param authHeader App 端鉴权串（AI 字幕 gRPC 需要），为空则走 REST 路径。
 */
class VideoDetailViewModel(
    private val bvid: String,
    private val repo: VideoRepository = VideoRepository(BiliApi()),
    private val interactions: InteractionRepository? = null,
    private val subtitleRepo: SubtitleRepository? = null,
    private val danmakuRepo: DanmakuRepository? = null,
    private val commentRepo: CommentRepository? = null,
    private val authHeader: String = "",
    /**
     * 空降助手仓库（第三方 bsbsb.top 的"可跳过片段"）。
     *
     * null = 不启用该功能。
     */
    private val sponsorBlockRepo: com.example.biliv3.data.SponsorBlockRepository? = null,
    /** 收藏状态全局广播（见 [com.example.biliv3.data.FavoritesSync]）。 */
    private val favoritesSync: com.example.biliv3.data.FavoritesSync =
        com.example.biliv3.data.FavoritesSync(),
    /**
     * 播放进度持久化（本地，在线断点续播）。
     *
     * ⚠️ 首版**没有这个依赖** —— 在线播放退出后进度全丢，
     * 只有离线缓存存了 `lastPositionMs`。这是「播放进度记忆」
     * 被判为缺失的直接原因。
     */
    private val progressStore: com.example.biliv3.data.PlaybackProgressStore? = null,
    /**
     * 服务端历史仓库（上报进度，实现多端续播）。
     *
     * 本地进度负责**即时性**，服务端历史负责**跨端同步**，两者都要有。
     */
    private val libraryRepo: com.example.biliv3.data.LibraryRepository? = null,
    /** 设置（「保存观看历史」开关的消费者）。 */
    private val settingsStore: com.example.biliv3.data.SettingsStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** 初始为 [PlayState.NotStarted] —— 等用户点击封面。 */
    private val _playState = MutableStateFlow<PlayState>(PlayState.NotStarted)
    val playState: StateFlow<PlayState> = _playState.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    // ---------------- 互动 ----------------

    private val _interaction = MutableStateFlow(InteractionState())
    val interaction: StateFlow<InteractionState> = _interaction.asStateFlow()

    /** 一次性提示（操作失败、需登录等）。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /**
     * 硬币余额。null = 未知（未登录 / 拉取失败）。
     *
     * 投币确认弹窗要显示它；未知时 UI 不显示余额行而不是显示 0。
     */
    private val _coinBalance = MutableStateFlow<Double?>(null)
    val coinBalance: StateFlow<Double?> = _coinBalance.asStateFlow()

    /** 用户是否已登录。UI 据此决定点击互动时是否弹登录引导。 */
    val isLoggedIn: Boolean get() = interactions?.isLoggedIn == true

    /** 简介是否展开。默认收起。 */
    private val _descExpanded = MutableStateFlow(false)
    val descExpanded: StateFlow<Boolean> = _descExpanded.asStateFlow()

    // ---------------- 字幕 / AI 翻译 ----------------

    /** 可选字幕轨（含 AI 翻译轨）。 */
    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks.asStateFlow()

    /** 当前选中的字幕（null = 字幕关闭）。 */
    private val _activeSubtitle = MutableStateFlow<SubtitleBody?>(null)
    val activeSubtitle: StateFlow<SubtitleBody?> = _activeSubtitle.asStateFlow()

    private val _subtitleLoading = MutableStateFlow(false)
    val subtitleLoading: StateFlow<Boolean> = _subtitleLoading.asStateFlow()

    private val _subtitleError = MutableStateFlow<String?>(null)
    val subtitleError: StateFlow<String?> = _subtitleError.asStateFlow()

    // ---------------- 弹幕 ----------------

    /** 已加载的弹幕（当前分片 + 预取的下一片）。 */
    private val _danmaku = MutableStateFlow<List<DanmakuItem>>(emptyList())
    val danmaku: StateFlow<List<DanmakuItem>> = _danmaku.asStateFlow()

    /** 弹幕开关。默认开启（符合 B 站使用习惯）。 */
    private val _danmakuEnabled = MutableStateFlow(true)
    val danmakuEnabled: StateFlow<Boolean> = _danmakuEnabled.asStateFlow()

    /** 弹幕不透明度 0f~1f。 */
    private val _danmakuAlpha = MutableStateFlow(0.9f)
    val danmakuAlpha: StateFlow<Float> = _danmakuAlpha.asStateFlow()

    /** 弹幕字号缩放 0.6f~1.6f。 */
    private val _danmakuFontScale = MutableStateFlow(1f)
    val danmakuFontScale: StateFlow<Float> = _danmakuFontScale.asStateFlow()

    /** 弹幕显示区域占比（1f = 全屏，0.5f = 下半屏）。 */
    private val _danmakuArea = MutableStateFlow(1f)
    val danmakuArea: StateFlow<Float> = _danmakuArea.asStateFlow()

    /** 已加载到的分片号。0 = 未加载。 */
    private var loadedSegment = 0

    // ---------------- 评论 ----------------

    private val _comments = MutableStateFlow<List<CommentItem>>(emptyList())
    val comments: StateFlow<List<CommentItem>> = _comments.asStateFlow()

    private val _commentTotal = MutableStateFlow(0)
    val commentTotal: StateFlow<Int> = _commentTotal.asStateFlow()

    private val _commentLoading = MutableStateFlow(false)
    val commentLoading: StateFlow<Boolean> = _commentLoading.asStateFlow()

    private val _commentLoadingMore = MutableStateFlow(false)
    val commentLoadingMore: StateFlow<Boolean> = _commentLoadingMore.asStateFlow()

    private val _commentHasMore = MutableStateFlow(true)
    val commentHasMore: StateFlow<Boolean> = _commentHasMore.asStateFlow()

    /** 评论分页游标。 */
    private var commentCursor = 0L

    /**
     * 评论排序方式。
     *
     * `3` = 按热度（官方默认），`2` = 按时间。
     *
     * ## ⚠️ 之前是**硬编码 3**，UI 也没有切换入口
     *
     * `CommentRepository.comments(mode=3)` 的默认值就是 3，
     * 而调用处从不传 mode —— 于是「评论排序」这项功能形同不存在。
     * 现在提成状态，切换后**重置游标并重拉第一页**。
     */
    private val _commentSort = MutableStateFlow(COMMENT_SORT_HOT)
    val commentSort: StateFlow<Int> = _commentSort.asStateFlow()

    init {
        load()
    }

    /**
     * 切换评论排序（热度 ↔ 时间）。
     *
     * 切换后必须**重置游标 + 重拉第一页**：游标是"上一页最后一条 rpid"，
     * 排序变了之后这个游标在新排序里毫无意义，沿用会拿到错乱的一页。
     */
    fun setCommentSort(mode: Int) {
        if (mode == _commentSort.value) return
        _commentSort.value = mode
        commentCursor = 0L
        _comments.value = emptyList()
        loadComments()
    }

    /** 拉详情 + 相关推荐（并行）。**不取流**。 */
    fun load() {
        viewModelScope.launch {
            _state.value = DetailUiState.Loading
            runCatching { repo.detail(bvid) }
                .onSuccess { detail ->
                    _state.value = DetailUiState.Content(detail = detail)
                    // 相关推荐与互动状态都是增强模块，失败静默
                    launch {
                        // ⚠️ 必须自己接住异常（v1.2.5）：`repo.related` 现在会对
                        // `code != 0` 抛异常。"失败静默"是**有意设计**（增强模块
                        // 不该阻断主内容），但静默的前提是**真的接住** ——
                        // 否则异常会崩在子协程里，反而比不静默更糟。
                        val related = runCatching { repo.related(bvid) }.getOrDefault(emptyList())
                        val cur = _state.value
                        if (cur is DetailUiState.Content) {
                            _state.value = cur.copy(related = related)
                        }
                    }
                    launch {
                        _interaction.value = interactions?.relation(bvid) ?: InteractionState()
                    }
                    // 字幕轨提前拉：用户点开字幕菜单时不该再等
                    launch { loadSubtitleTracks(detail) }
                    // 评论是增强模块，失败静默
                    launch { loadComments() }

                    // ⚠️ UP 粉丝数 / 在看人数**不在 view 接口里**，
                    // 需要额外两个请求。并行拉，失败静默（不显示该项）。
                    launch {
                        // 同上：两个请求各自静默失败（缺失就显示 0，
                        // 与 VideoDetail 的默认值一致）
                        val fans = runCatching { repo.ownerFans(detail.ownerMid) }.getOrDefault(0)
                        val viewers = runCatching { repo.viewerCount(detail.aid, detail.cid) }.getOrDefault(0)
                        val cur = _state.value
                        if (cur is DetailUiState.Content) {
                            _state.value = cur.copy(
                                detail = cur.detail.copy(
                                    ownerFans = fans,
                                    viewers = viewers,
                                ),
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _state.value = DetailUiState.Error(userMessageFor(e))
                }
        }
    }

    /**
     * 拉字幕轨列表。
     *
     * ⚠️ 需要登录态 —— 实测未登录时 REST 与 gRPC 都拿不到字幕轨
     * （`x/player/wbi/v2` 的 `subtitles[]` 恒为空）。
     * 因此失败静默，由 UI 提示"登录后可用"。
     */
    private fun loadSubtitleTracks(detail: VideoDetail) {
        val sr = subtitleRepo ?: return
        viewModelScope.launch {
            runCatching {
                sr.tracks(aid = detail.aid, cid = detail.cid, authorization = authHeader)
            }.onSuccess { _subtitleTracks.value = it }
        }
    }

    /**
     * 选择字幕轨。传 null 关闭字幕。
     *
     * AI 翻译轨由 `SubtitleTrack.isAiTranslate` 识别（`aiType == 1`）。
     */
    fun selectSubtitle(track: SubtitleTrack?) {
        if (track == null) {
            _activeSubtitle.value = null
            return
        }
        val sr = subtitleRepo ?: return

        viewModelScope.launch {
            _subtitleLoading.value = true
            _subtitleError.value = null
            runCatching { sr.body(track) }
                .onSuccess { _activeSubtitle.value = it }
                .onFailure { e -> _subtitleError.value = e.message ?: "字幕加载失败" }
            _subtitleLoading.value = false
        }
    }

    fun clearSubtitleError() {
        _subtitleError.value = null
    }

    // ---------------- 弹幕操作 ----------------

    fun toggleDanmaku() {
        _danmakuEnabled.value = !_danmakuEnabled.value
    }

    /**
     * 直接设置弹幕开关（设置页的全局默认值灌入时用）。
     *
     * 与 [toggleDanmaku] 分开：设置页给的是**明确的目标值**，
     * 用 toggle 会在"当前值恰好相同"时反而翻转，产生意外。
     */
    fun setDanmakuEnabled(v: Boolean) {
        _danmakuEnabled.value = v
    }

    fun setDanmakuAlpha(v: Float) {
        _danmakuAlpha.value = v.coerceIn(0.1f, 1f)
    }

    fun setDanmakuFontScale(v: Float) {
        _danmakuFontScale.value = v.coerceIn(0.6f, 1.6f)
    }

    fun setDanmakuArea(v: Float) {
        _danmakuArea.value = v.coerceIn(0.25f, 1f)
    }

    /**
     * 按当前播放进度确保弹幕已加载。
     *
     * ## 为什么按分片懒加载
     *
     * 弹幕按 6 分钟一片。一部 30 分钟视频有 5 片，
     * 全量拉取既慢又浪费流量。这里只拉"当前进度所在片"，
     * 并**预取下一片**（避免看到片尾时突然断档）。
     *
     * @param positionMs 当前播放进度（毫秒）
     */
    fun ensureDanmakuLoaded(positionMs: Long) {
        val cur = _state.value
        if (cur !is DetailUiState.Content) return
        val dr = danmakuRepo ?: return

        val cid = cur.detail.pages.getOrNull(_currentPage.value)?.cid ?: cur.detail.cid
        if (cid <= 0L) return

        val segment = (positionMs / DanmakuRepository.SEGMENT_DURATION_MS).toInt() + 1
        if (segment == loadedSegment) return
        loadedSegment = segment

        viewModelScope.launch {
            // 当前片 + 下一片一起拉（并行），下一片用于无缝衔接
            val current = async { dr.segment(cid, segment) }
            val next = async { dr.segment(cid, segment + 1) }
            val merged = (current.await() + next.await()).sortedBy { it.progressMs }
            _danmaku.value = merged
        }
    }

    /** 切换分P 时重置弹幕（不同 cid 弹幕不同）。 */
    private fun resetDanmaku() {
        loadedSegment = 0
        _danmaku.value = emptyList()
    }

    // ---------------- 评论操作 ----------------

    /**
     * 拉首屏评论。
     *
     * 在详情加载完成后调用（评论是增强模块，失败静默）。
     */
    fun loadComments() {
        val cr = commentRepo ?: return
        val cur = _state.value
        if (cur !is DetailUiState.Content) return

        viewModelScope.launch {
            _commentLoading.value = true
            commentCursor = 0L
            runCatching {
                cr.comments(
                    oid = cur.detail.aid,
                    upMid = cur.detail.ownerMid,
                    mode = _commentSort.value,
                )
            }
                .onSuccess { page ->
                    _comments.value = page.comments
                    _commentTotal.value = page.total
                    commentCursor = page.nextCursor
                    _commentHasMore.value = !page.isEnd
                }
                .onFailure { /* 静默：评论是增强模块 */ }
            _commentLoading.value = false
        }
    }

    /** 加载更多评论。 */
    fun loadMoreComments() {
        val cr = commentRepo ?: return
        val cur = _state.value
        if (cur !is DetailUiState.Content) return
        if (_commentLoadingMore.value || !_commentHasMore.value) return

        viewModelScope.launch {
            _commentLoadingMore.value = true
            runCatching {
                cr.comments(
                    oid = cur.detail.aid,
                    upMid = cur.detail.ownerMid,
                    next = commentCursor,
                    mode = _commentSort.value,
                )
            }
                .onSuccess { page ->
                    // 按 rpid 去重：游标分页在插入新评论时可能重复
                    val seen = _comments.value.mapTo(HashSet()) { it.rpid }
                    _comments.value = _comments.value + page.comments.filter { it.rpid !in seen }
                    commentCursor = page.nextCursor
                    _commentHasMore.value = !page.isEnd
                }
                .onFailure {
                    // 失败就停止，避免无限重试
                    _commentHasMore.value = false
                }
            _commentLoadingMore.value = false
        }
    }

    // ---------------- 定位到指定评论（AI 查成分「在 APP 内查看」）----------------

    /**
     * 要定位的评论 rpid。UI 观察它决定"滚到哪条"。
     *
     * 定位完成后由 UI 调 [consumeFocusComment] 清空，
     * 避免用户手动滚动后又被拉回去。
     */
    private val _focusCommentRpid = MutableStateFlow<String?>(null)
    val focusCommentRpid: StateFlow<String?> = _focusCommentRpid.asStateFlow()

    /** 定位是否还在进行（UI 可显示"正在定位评论…"）。 */
    private val _focusingComment = MutableStateFlow(false)
    val focusingComment: StateFlow<Boolean> = _focusingComment.asStateFlow()

    /** UI 完成滚动+高亮后调用，清掉一次性定位目标。 */
    fun consumeFocusComment() {
        _focusCommentRpid.value = null
        _focusingComment.value = false
    }

    // ---------------- 空降助手（跳过恰饭广告等）----------------

    /** 已加载的可跳过片段（按开始时间升序、已合并）。 */
    private val _skipSegments = MutableStateFlow<List<com.example.biliv3.data.SkipSegment>>(
        emptyList(),
    )
    val skipSegments: StateFlow<List<com.example.biliv3.data.SkipSegment>> =
        _skipSegments.asStateFlow()

    /** 刚跳过的片段（驱动"已跳过 xx，撤销"提示条；null = 无提示）。 */
    private val _justSkipped = MutableStateFlow<JustSkipped?>(null)
    val justSkipped: StateFlow<JustSkipped?> = _justSkipped.asStateFlow()

    /** 已处理过的片段 UUID，避免同一个片段被重复跳。 */
    private var skippedUuids = HashSet<String>()

    /**
     * 拉取当前分P 的可跳过片段。
     *
     * 在取流成功（拿到 cid）后调用一次。失败**静默** ——
     * 这是第三方增强功能，拿不到就不跳，不该影响播放。
     */
    fun loadSkipSegments(categories: Set<String>) {
        val repo = sponsorBlockRepo ?: return
        val cur = _state.value
        if (cur !is DetailUiState.Content) return

        val cid = cur.detail.pages.getOrNull(_currentPage.value)?.cid ?: cur.detail.cid
        if (cid <= 0L) return

        viewModelScope.launch {
            val list = repo.segments(bvid = bvid, cid = cid, categories = categories)
            _skipSegments.value = list
            skippedUuids = HashSet()
        }
    }

    /** 切换分P 时清空片段（不同 cid 的片段不同）。 */
    private fun resetSkipSegments() {
        _skipSegments.value = emptyList()
        skippedUuids = HashSet()
        _justSkipped.value = null
    }

    /**
     * 按当前播放位置判断是否该跳过。
     *
     * ## 判定规则
     *
     * 位置落在某个片段区间内、且该片段**还没跳过** → 返回目标时间。
     *
     * ## 为什么用"已跳过集合"而不是只比较时间
     *
     * 跳过之后播放器位置会到 `endSeconds`，此时已不在区间内，看似不需要去重。
     * 但用户**可能手动拖回**片段里（比如想看看广告讲了什么）——
     * 那时若还跳，就变成了"用户拖不进去"的 bug。
     * 用 UUID 记录"跳过一次就不再跳"，行为可预期。
     *
     * @param positionSeconds 当前播放位置（秒）
     * @return 要跳到的目标时间（秒）；null = 不需要跳
     */
    fun skipTargetFor(positionSeconds: Double): Double? {
        val seg = com.example.biliv3.data.SponsorBlockLogic.findSegmentAt(
            segments = _skipSegments.value,
            positionSeconds = positionSeconds,
            alreadySkipped = skippedUuids,
            tailMarginSeconds = SKIP_TAIL_MARGIN_SECONDS,
        ) ?: return null

        if (seg.uuid.isNotEmpty()) skippedUuids.add(seg.uuid)
        _justSkipped.value = JustSkipped(
            segment = seg,
            fromSeconds = positionSeconds,
        )
        return seg.endSeconds
    }

    /** 撤销上次跳过（退回原位置）。 */
    fun undoSkip(): Double? {
        val last = _justSkipped.value ?: return null
        // 允许再次跳（用户可能撤销后立刻又想跳）
        skippedUuids.remove(last.segment.uuid)
        _justSkipped.value = null
        return last.fromSeconds
    }

    /** 提示条消失。 */
    fun consumeJustSkipped() {
        _justSkipped.value = null
    }

    /**
     * 翻页找到指定评论并请求定位。
     *
     * ## 为什么要翻页而不是"直接请求那条评论"
     *
     * 评论接口没有"按 rpid 取单条"的能力，只有游标分页。
     * 所以只能从第一页开始逐页拉，直到命中 —— 或者达到上限放弃。
     *
     * ## 为什么必须有上限
     *
     * 热评视频有几千条评论，全量翻页会：
     * - 打几十个请求（对 B 站是压力，也容易被风控）
     * - 让用户等很久，期间界面没反馈
     *
     * 所以最多翻 [MAX_FOCUS_PAGES] 页（约 200 条）。找不到就**如实告知**
     * "该评论在更靠后的位置，已加载到第 N 页" —— 比无限转圈好。
     */
    fun focusComment(rpid: String) {
        if (rpid.isEmpty()) return
        val cr = commentRepo ?: return
        val cur = _state.value
        if (cur !is DetailUiState.Content) return

        _focusCommentRpid.value = rpid
        _focusingComment.value = true

        viewModelScope.launch {
            var cursor = 0L
            var pages = 0

            while (pages < MAX_FOCUS_PAGES) {
                // 已在已加载列表里 → 直接命中，不用再请求
                if (_comments.value.any { it.rpid.toString() == rpid }) {
                    _focusingComment.value = false
                    return@launch
                }

                val page = runCatching {
                    cr.comments(
                        oid = cur.detail.aid,
                        upMid = cur.detail.ownerMid,
                        next = cursor,
                        mode = _commentSort.value,
                    )
                }.getOrNull() ?: break

                // 合并（去重，与 loadMoreComments 同规则）
                val seen = _comments.value.mapTo(HashSet()) { it.rpid }
                _comments.value = _comments.value + page.comments.filter { it.rpid !in seen }
                commentCursor = page.nextCursor
                _commentHasMore.value = !page.isEnd
                pages++

                if (_comments.value.any { it.rpid.toString() == rpid }) {
                    _focusingComment.value = false
                    return@launch
                }
                if (page.isEnd) break
                cursor = page.nextCursor
            }

            // 走到这里说明没找到
            _focusingComment.value = false
            _focusCommentRpid.value = null
            toast("该评论位置较靠后，已加载 $pages 页仍未找到")
        }
    }

    /**
     * 举报评论。
     *
     * ## 举报后**本地立即隐藏**
     *
     * 接口不保证即时生效（要人工审核）。若只弹个 toast，
     * 用户会以为"举报了但没反应"而重复举报 —— 所以本地先移除该条，
     * 与服务端最终结果保持一致（审核通过时它本来也会消失）。
     */
    fun reportComment(
        comment: com.example.biliv3.data.model.CommentItem,
        reason: Int,
    ) {
        val cr = commentRepo ?: return
        if (!isLoggedIn) return toast("请先登录")

        viewModelScope.launch {
            cr.reportComment(oid = comment.oid, rpid = comment.rpid, reason = reason)
                .onSuccess {
                    _comments.value = _comments.value.filterNot { it.rpid == comment.rpid }
                    toast("举报已提交")
                }
                .onFailure { e -> toast(userMessageFor(e)) }
        }
    }

    /** 可选的举报理由（编号 + 文案）。 */
    val reportReasons: List<Pair<Int, String>>
        get() = commentRepo?.reportReasons ?: emptyList()

    // ---------------- 弹幕发送 ----------------

    /**
     * 发送弹幕。
     *
     * ## 三条联动（需求要求）
     *
     * 1. **发送后即时显示** —— 本地先插进 `_danmaku` 列表，
     *    用户立刻能看到自己的弹幕（不必等接口/重新拉分片）
     * 2. **与弹幕开关联动** —— 若当前弹幕是关的，发送时自动打开
     *    （否则用户发了却看不见，会以为失败）
     * 3. **与播放器状态联动** —— 弹幕时间取**当前播放进度**，
     *    保证它出现在用户正在看的位置
     *
     * @param text 内容
     * @param color 十进制 RGB
     * @param mode 1=滚动 4=底部 5=顶部
     */
    fun sendDanmaku(
        text: String,
        color: Int,
        mode: Int,
        /**
         * 弹幕出现时间（毫秒）。
         *
         * ⚠️ 由 **UI 层**传入，不从 ViewModel 读播放器 ——
         * ViewModel 不该持有 ExoPlayer（那是 UI 层的资源，
         * 且播放器现在是 Activity 级、生命周期与 VM 不一致）。
         */
        progressMs: Long,
    ) {
        val dr = danmakuRepo ?: return
        if (!isLoggedIn) return toast("请先登录")
        if (text.isBlank()) return toast("弹幕内容不能为空")

        val cur = _state.value
        if (cur !is DetailUiState.Content) return

        viewModelScope.launch {
            dr.send(
                bvid = bvid,
                cid = cur.detail.cid,
                message = text,
                progressMs = progressMs,
                mode = mode,
                color = color,
            )
                .onSuccess {
                    toast("弹幕已发送")
                    // ① 即时显示：本地插入（乐观）。
                    // 不重新拉分片 —— 服务端有延迟，重拉可能还没有这条。
                    val mine = com.example.biliv3.data.danmaku.DanmakuItem(
                        id = System.currentTimeMillis(),
                        idStr = "",
                        progressMs = progressMs.toInt(),
                        mode = mode,
                        fontSize = 25,
                        color = color,
                        content = text,
                    )
                    _danmaku.value = (_danmaku.value + mine)
                        .sortedBy { it.progressMs }
                    // ② 自动打开弹幕开关（否则用户看不见自己发的）
                    if (!_danmakuEnabled.value) _danmakuEnabled.value = true
                }
                .onFailure { e -> toast(userMessageFor(e)) }
        }
    }

    // ---------------- 评论写操作 ----------------

    /**
     * 给评论点赞 / 取消（乐观更新 + 失败回滚）。
     *
     * ## 为什么本地立刻改
     *
     * 点赞是高频轻量操作，等网络往返会让用户以为"没点上"而重复点。
     * 先改本地、失败再回滚，是这类操作的标准做法。
     *
     * 回滚时必须**同时**恢复 `liked` 与 `likeCount` —— 只改一个会出现
     * "已点赞但数字没变"这种不自洽的状态。
     */
    fun likeComment(comment: com.example.biliv3.data.model.CommentItem) {
        val cr = commentRepo ?: return
        if (!isLoggedIn) return toast("请先登录")

        val target = !comment.liked
        val delta = if (target) 1 else -1

        // 乐观更新
        _comments.value = _comments.value.map { c ->
            if (c.rpid == comment.rpid) {
                c.copy(liked = target, likeCount = (c.likeCount + delta).coerceAtLeast(0))
            } else {
                c
            }
        }

        viewModelScope.launch {
            cr.likeComment(oid = comment.oid, rpid = comment.rpid, like = target)
                .onFailure { e ->
                    // 回滚（liked 与 count 一起）
                    _comments.value = _comments.value.map { c ->
                        if (c.rpid == comment.rpid) {
                            c.copy(
                                liked = !target,
                                likeCount = (c.likeCount - delta).coerceAtLeast(0),
                            )
                        } else {
                            c
                        }
                    }
                    toast(userMessageFor(e))
                }
        }
    }

    /**
     * 删除自己的评论（乐观移除 + 失败回滚到原位）。
     *
     * 回滚要插回**原来的位置**而不是末尾 —— 位置变了用户会以为删错了。
     */
    fun deleteComment(comment: com.example.biliv3.data.model.CommentItem) {
        val cr = commentRepo ?: return
        if (!isLoggedIn) return toast("请先登录")

        val snapshot = _comments.value
        val index = snapshot.indexOfFirst { it.rpid == comment.rpid }
        if (index < 0) return

        _comments.value = snapshot.filterNot { it.rpid == comment.rpid }

        viewModelScope.launch {
            cr.deleteComment(oid = comment.oid, rpid = comment.rpid)
                .onSuccess {
                    toast("已删除")
                    _commentTotal.value = (_commentTotal.value - 1).coerceAtLeast(0)
                }
                .onFailure { e ->
                    _comments.value = snapshot.toMutableList().apply {
                        add(index.coerceAtMost(size), comment)
                    }
                    toast(userMessageFor(e))
                }
        }
    }

    /**
     * 发表评论 / 回复。
     *
     * @param message 内容
     * @param root 主评论 rpid（回复时）。发主评论传 0
     * @param parent 被回复的 rpid（回复时）。发主评论传 0
     */
    fun postComment(
        message: String,
        root: Long = 0L,
        parent: Long = 0L,
    ) {
        val cr = commentRepo ?: return
        if (!isLoggedIn) return toast("请先登录")
        if (message.isBlank()) return toast("说点什么吧")

        val cur = _state.value
        if (cur !is DetailUiState.Content) return

        viewModelScope.launch {
            cr.addComment(
                oid = cur.detail.aid,
                message = message,
                root = root,
                parent = parent,
            )
                .onSuccess {
                    toast(if (root > 0) "回复成功" else "评论成功")
                    // 重新拉第一页：新评论要出现在最前（按热度排序时位置不定，
                    // 本地插入无法预测正确位置，重拉最稳）
                    loadComments()
                }
                .onFailure { e -> toast(userMessageFor(e)) }
        }
    }

    /**
     * 用户点击封面 → 开始取流播放。
     *
     * 这是「点击后才进入视频」的入口。已在取流/已就绪时忽略重复点击。
     */
    fun startPlayback() {
        val cur = _state.value
        if (cur !is DetailUiState.Content) return
        val ps = _playState.value
        if (ps is PlayState.Loading || ps is PlayState.Ready) return
        fetchPlayInfo(cur.detail, _currentPage.value)
    }

    /** 切换分P。每个分P 有独立 cid，必须重新取流。 */
    fun selectPage(index: Int) {
        val cur = _state.value
        if (cur !is DetailUiState.Content) return
        if (index !in cur.detail.pages.indices) return
        if (index == _currentPage.value) return

        _currentPage.value = index
        // 分P 变了 → cid 变了 → 弹幕/片段都不同，必须重置
        resetDanmaku()
        resetSkipSegments()
        fetchPlayInfo(cur.detail, index)
    }

    /** 切换清晰度。不缓存旧 URL（约 2h 过期）。 */
    fun selectQuality(quality: Int) {
        val cur = _state.value
        if (cur !is DetailUiState.Content) return
        fetchPlayInfo(cur.detail, _currentPage.value, quality)
    }

    /** 取流失败后手动重试。 */
    fun retryPlay() {
        val cur = _state.value
        if (cur !is DetailUiState.Content) return
        fetchPlayInfo(cur.detail, _currentPage.value)
    }

    /** 展开/收起简介。 */
    fun toggleDesc() {
        _descExpanded.value = !_descExpanded.value
    }

    // ---------------- 互动操作 ----------------

    fun toggleLike() {
        val repoI = interactions ?: return
        if (!repoI.isLoggedIn) return toast("请先登录")
        val next = !_interaction.value.liked
        // 乐观更新：先改 UI 再发请求，失败回滚。
        // 点赞是高频轻操作，等网络回来再变会有明显延迟感。
        _interaction.value = _interaction.value.copy(liked = next)
        viewModelScope.launch {
            repoI.like(bvid, next)
                .onFailure { e ->
                    _interaction.value = _interaction.value.copy(liked = !next)
                    toast(userMessageFor(e))
                }
        }
    }

    /**
     * 投币。
     *
     * ## 已投过币时不再允许重复投
     *
     * B 站一个视频最多投 2 枚，且**已投过就返回 `-403 超过投币上限`**。
     * UI 层在入口处就拦住（弹 toast），而不是发一次注定失败的请求 ——
     * 少一次风控计数。
     *
     * @param count 1 或 2
     * @param alsoLike 是否同时点赞（弹窗里的「同时点赞内容」）
     */
    fun coin(count: Int = 1, alsoLike: Boolean = false) {
        val repoI = interactions ?: return
        if (!repoI.isLoggedIn) return toast("请先登录")
        if (_interaction.value.coined) return toast("你已经投过币了")

        viewModelScope.launch {
            repoI.coin(bvid, count, alsoLike)
                .onSuccess {
                    // 同时点赞时，一并把点赞态置为已赞（与服务器一致）
                    _interaction.value = _interaction.value.copy(
                        coined = true,
                        liked = if (alsoLike) true else _interaction.value.liked,
                    )
                    toast("投币成功")
                }
                .onFailure { e -> toast(userMessageFor(e)) }
        }
    }

    /**
     * 拉硬币余额（投币确认弹窗要显示）。
     *
     * 失败/未登录时为 null —— UI 不显示余额行，
     * 而不是显示 `0`（0 会让用户以为投不了了）。
     */
    fun loadCoinBalance() {
        val repoI = interactions ?: return
        if (!repoI.isLoggedIn) {
            _coinBalance.value = null
            return
        }
        viewModelScope.launch {
            _coinBalance.value = runCatching { repoI.coinBalance() }.getOrNull()
        }
    }

    fun toggleFavorite() {
        val repoI = interactions ?: return
        if (!repoI.isLoggedIn) return toast("请先登录")
        val next = !_interaction.value.favored
        _interaction.value = _interaction.value.copy(favored = next)
        viewModelScope.launch {
            repoI.favorite(bvid, next)
                .onSuccess {
                    toast(if (next) "已收藏" else "已取消收藏")
                    // ⚠️ 通知其它页面刷新（收藏列表 / 我的页计数）。
                    // 不通知的话，用户从详情页收藏后回到「我的收藏」，
                    // 列表还是旧的 —— 就是"功能孤立、状态不同步"。
                    favoritesSync.notifyChanged()
                }
                .onFailure { e ->
                    _interaction.value = _interaction.value.copy(favored = !next)
                    toast(userMessageFor(e))
                }
        }
    }

    /** 分享上报（分享面板由 UI 层唤起）。 */
    fun onShared() {
        viewModelScope.launch { interactions?.shareReport(bvid) }
    }

    /** 提示已消费。 */
    fun consumeToast() {
        _toast.value = null
    }

    /**
     * 当前视频的 UP 主 mid（0 = 详情还没加载）。
     *
     * 楼中楼详情页需要它判断"哪条回复是 UP 主发的"——
     * 不传的话子页面里 UP 标记会全部消失。
     */
    val ownerMid: Long
        get() = (_state.value as? DetailUiState.Content)?.detail?.ownerMid ?: 0L

    // ---------------- 播放进度持久化 ----------------

    /** 最近一次上报的进度（秒）。用于避免同一秒内重复上报。 */
    private var lastReportedSecond = -1

    /**
     * 上报播放进度（本地 + 服务端）。
     *
     * ## 调用时机
     *
     * UI 层在**离开页面**（`onDispose`）与**暂停**时调用，
     * 而不是逐帧上报 —— 逐帧写 DataStore 会拖慢主线程。
     *
     * ## 「保存观看历史」开关的消费者就在这里
     *
     * 设置页那个开关此前**只写不读**（「空转设置项」反模式）。
     * 现在它真实控制两件事：
     * - 关：不写本地续播记录，也不上报服务端历史
     * - 开：两者都写
     *
     * ## 看完的视频清除进度
     *
     * >95% 视为看完。留着进度会让下次进入弹出一个没意义的续播提示
     * （`PlaybackProgressStore.shouldResume` 也会拦住，但清掉更干净）。
     *
     * @param positionMs 当前播放位置（毫秒）
     */
    fun reportProgress(positionMs: Long) {
        val cur = _state.value
        if (cur !is DetailUiState.Content) return
        val page = cur.detail.pages.getOrNull(_currentPage.value)
        reportProgressForCid(page?.cid ?: cur.detail.cid, positionMs)
    }

    /**
     * 按**指定 cid** 上报进度。
     *
     * UI 层在 dispose 时用这个 —— 那时 `_currentPage` 可能已经被
     * 下一次进入重置，但播放器上还挂着上一次的位置，
     * 直接读 `_currentPage` 会把进度写到错误的分P 上。
     */
    fun reportProgressForCid(cid: Long, positionMs: Long) {
        val cur = _state.value
        if (cur !is DetailUiState.Content) return
        if (positionMs <= 0L || cid <= 0L) return

        val second = (positionMs / 1000).toInt()
        if (second == lastReportedSecond) return
        lastReportedSecond = second

        val page = cur.detail.pages.firstOrNull { it.cid == cid }
        val durationMs = (page?.durationSeconds ?: cur.detail.durationSeconds) * 1000L

        viewModelScope.launch {
            // 读开关（默认 true）。拿不到设置时按"保存"处理 ——
            // 宁可多记一次，也不要因为读设置失败而丢进度。
            val save = runCatching {
                settingsStore?.settings?.first()?.saveHistory ?: true
            }.getOrDefault(true)
            if (!save) return@launch

            if (durationMs > 0 && positionMs >= (durationMs * 0.95f).toLong()) {
                // 看完了：清进度，不留续播提示
                progressStore?.clear(bvid, cid)
            } else {
                progressStore?.put(bvid, cid, positionMs)
            }

            // 服务端历史上报（未登录时内部直接返回 false，不发请求）
            libraryRepo?.reportProgress(aid = cur.detail.aid, cid = cid, progressSeconds = second)
        }
    }

    /**
     * 读本地续播位置（毫秒）。
     *
     * 0 表示没有记录 / 不值得续播（<5% 或 >95%）。
     */
    suspend fun resumePosition(cid: Long): Long {
        val store = progressStore ?: return 0L
        val ms = runCatching { store.get(bvid, cid) }.getOrDefault(0L)
        if (ms <= 0L) return 0L

        val cur = _state.value as? DetailUiState.Content ?: return 0L
        val page = cur.detail.pages.firstOrNull { it.cid == cid }
        val durationMs = (page?.durationSeconds ?: cur.detail.durationSeconds) * 1000L
        if (durationMs <= 0L) return ms

        return if (com.example.biliv3.data.PlaybackProgressStore.shouldResume(ms, durationMs)) {
            ms
        } else {
            0L
        }
    }

    private fun toast(msg: String) {
        _toast.value = msg
    }

    private fun fetchPlayInfo(
        detail: VideoDetail,
        pageIndex: Int,
        quality: Int = 0,
    ) {
        val page = detail.pages.getOrNull(pageIndex)
        val cid = page?.cid ?: detail.cid
        if (cid <= 0L) {
            _playState.value = PlayState.Failed("缺少 cid，无法取流")
            return
        }

        viewModelScope.launch {
            _playState.value = PlayState.Loading
            runCatching { repo.playInfo(detail.bvid, cid, quality) }
                .onSuccess { _playState.value = PlayState.Ready(it) }
                .onFailure {
                    _playState.value = PlayState.Failed(userMessageFor(it))
                }
        }
    }
}

/** 便于 UI 层统一处理未登录。 */
fun Throwable.isNotLoggedIn(): Boolean = this is NotLoggedInException

/** 评论排序：按热度（官方默认）。 */
const val COMMENT_SORT_HOT = 3

/** 评论排序：按时间。 */
const val COMMENT_SORT_TIME = 2

/**
 * 定位评论时最多翻几页。
 *
 * 约 20 条/页 → 200 条。不设上限会为一条评论打几十个请求，
 * 既给 B 站压力也容易触发风控，且用户要干等。
 */
const val MAX_FOCUS_PAGES = 10

/**
 * 跳过片段的尾部余量（秒）。
 *
 * 位置已经非常接近片段末尾（差不到这个值）时就不再跳 ——
 * 否则会出现"跳到 527.0s，而当前位置 526.9s"这种无意义跳转，
 * 且容易和 `endSeconds` 的浮点误差打架形成抖动。
 */
const val SKIP_TAIL_MARGIN_SECONDS = 0.5

/**
 * 刚跳过一个片段（驱动"已跳过"提示条）。
 *
 * @param segment 被跳过的片段
 * @param fromSeconds 跳过前的位置，用于"撤销"时退回
 */
data class JustSkipped(
    val segment: com.example.biliv3.data.SkipSegment,
    val fromSeconds: Double,
)
