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
import com.example.biliv3.data.quality.AutoQuality
import com.example.biliv3.data.quality.AutoQualitySettings
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
    /** 进度条拖动预览（v1.5.3）。null = 测试/预览模式。 */
    private val videoshotRepo: com.example.biliv3.data.VideoshotRepository? = null,
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
    /**
     * AI 总结仓库（v1.6.3）。
     *
     * null = 该功能不可用（预览/测试环境），此时 UI 不渲染入口。
     *
     * ⚠️ 官方总结走它的共享 `BiliApi`（带 Cookie）；
     * 第三方走它内部的**独立 client**（不挂 CookieJar）——
     * 详见 `AiSummaryRepository` 的红线说明。
     */
    private val aiSummaryRepo: com.example.biliv3.data.ai.AiSummaryRepository? = null,
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

    /**
     * 点赞请求是否在途。
     *
     * 用于**防并发点击** —— 快速连点会发出多个 like/unlike 请求，
     * 响应顺序不确定，本地与服务端状态可能永久不一致。
     * 详见 [toggleLike] 的说明。
     */
    private val _likeInFlight = MutableStateFlow(false)

    /**
     * 收藏请求是否在途（与 [_likeInFlight] 同理，见 [toggleFavorite]）。
     */
    private val _favoriteInFlight = MutableStateFlow(false)

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

    /**
     * 本次播放中用户**手动选过**的清晰度（未发版）。
     *
     * ## 为什么必须记住它
     *
     * 用户手动选了 720P 后，切分P 会重新取流 —— 那时若还按
     * 「默认清晰度 / 自动最高」决定，就会**把用户的选择顶掉**。
     * 表现是"我明明选了 720P，换个 P 又变回 1080P"。
     *
     * `0` = 本次播放内没手动选过（才允许用设置里的默认值）。
     */
    private var manualQuality: Int = 0

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

    // ---------------------------------------------------------------------
    // 楼中楼「就地分页」（v1.5.1）
    // ---------------------------------------------------------------------

    /**
     * 各主评论**已加载到的回复页**（rpid → 已加载页数）。
     *
     * ## 🔴 为什么要有它（用户报告 #12）
     *
     * 原先「查看全部 N 条回复」是**跳到独立页**（`ReplyDetailScreen`）。
     * 用户明确要求："需要在当前评论区域持续查看回复，不要每次跳转到独立页面"。
     *
     * `reply/wbi/main` 每条评论最多内嵌 **3** 条回复（实测），
     * 想要更多只能走 `x/v2/reply/reply` 分页。这里记录每条评论
     * **已经拉了几页**，实现"就地继续加载"。
     *
     * ⚠️ 用 rpid 作 key 而不是全局一个页码 —— 不同评论的回复必须
     * **各自独立分页**，否则展开 A 再展开 B 会用错偏移量。
     */
    private val _replyPages = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val replyPages: StateFlow<Map<Long, Int>> = _replyPages.asStateFlow()

    /** 正在加载更多回复的主评论 rpid 集合（用于显示 loading，防重复点击）。 */
    private val _replyLoading = MutableStateFlow<Set<Long>>(emptySet())
    val replyLoading: StateFlow<Set<Long>> = _replyLoading.asStateFlow()

    /**
     * 就地加载某条主评论的**下一页**回复。
     *
     * - 第 1 页也走这里（点「展开」时把内嵌的 3 条替换成第 1 页完整数据）
     * - 已加载的回复**按 rpid 去重**，不会重复出现
     * - 加载中**丢弃**重复点击（防并发）
     * - 失败**保留已有回复**，只把 rpid 从 loading 集合移除，用户可重试
     *
     * @return 是否还有更多（false = 已到底，UI 据此隐藏按钮）
     */
    fun loadMoreReplies(rootRpid: Long) {
        val repo = commentRepo ?: return
        val cur = _state.value
        if (cur !is DetailUiState.Content) return
        val oid = cur.detail.aid
        if (oid <= 0L || rootRpid <= 0L) return

        // 防并发：同一条评论同时只允许一个请求在途
        if (rootRpid in _replyLoading.value) return
        _replyLoading.value = _replyLoading.value + rootRpid

        val nextPage = (_replyPages.value[rootRpid] ?: 0) + 1

        viewModelScope.launch {
            runCatching { repo.replies(oid = oid, root = rootRpid, page = nextPage) }
                .onSuccess { page ->
                    // 合并：已有的 + 新拉的，按 rpid 去重
                    _comments.value = _comments.value.map { c ->
                        if (c.rpid != rootRpid) return@map c
                        val existing = c.replies
                        val seen = existing.mapTo(HashSet()) { it.rpid }
                        val merged = existing + page.comments.filter { it.rpid !in seen }
                        c.copy(
                            replies = merged,
                            // 让 UI 知道"已突破内嵌上限"，不再截断显示
                            replyCount = maxOf(c.replyCount, merged.size),
                        )
                    }
                    _replyPages.value = _replyPages.value + (rootRpid to nextPage)
                    // 这一页没满 → 到底了
                    if (page.isEnd || page.comments.isEmpty()) {
                        _replyPages.value = _replyPages.value + (rootRpid to REPLY_PAGE_MAX)
                    }
                }
                .onFailure { e ->
                    // ⚠️ 失败不改数据（已有回复保留），只提示。
                    // 不用 `_commentError`（那是首屏的），否则整个列表会变成错误页。
                    toast("加载回复失败：${userMessageFor(e)}")
                }
            _replyLoading.value = _replyLoading.value - rootRpid
        }
    }

    /** 该主评论是否已把回复拉到底（UI 据此隐藏「加载更多」）。 */
    fun repliesExhausted(rootRpid: Long): Boolean =
        (_replyPages.value[rootRpid] ?: 0) >= REPLY_PAGE_MAX

    /** 楼中楼每页条数（与 `CommentRepository.replies` 的默认值一致）。 */
    private val REPLY_PAGE_SIZE = 20

    /**
     * 已加载页数的"到底"哨兵。
     *
     * 用 `>= REPLY_PAGE_MAX` 判"没有更多"，而不是再开一个 Set ——
     * 少一份需要同步的状态，就不会出现"两处不一致"。
     */
    private val REPLY_PAGE_MAX = Int.MAX_VALUE

    /**
     * 评论**首屏**加载失败的原因（null = 没有失败）。
     *
     * ## 为什么必须有这个状态
     *
     * 没有它时，"加载失败"与"真的没有评论"在 UI 上完全同形 ——
     * 都会走到 `comments.isEmpty()` 分支并显示「还没有评论」。
     * 那是一句假的事实断言（v1.4.2 修）。
     *
     * 只表示**首屏**失败：翻页失败仍走 [_commentHasMore]，
     * 已有评论不会被错误态顶掉。
     */
    private val _commentError = MutableStateFlow<String?>(null)
    val commentError: StateFlow<String?> = _commentError.asStateFlow()

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
                        // 🔴 v1.5.3：一次请求取回「在看人数 + 章节」——
                        // 两者本来就在同一个 `player/v2` 响应里
                        // （`online_count` / `view_points`），分两次拉纯属浪费。
                        val meta = runCatching { repo.playerMeta(detail.aid, detail.cid) }
                            .getOrDefault(com.example.biliv3.data.PlayerMeta())
                        val viewers = meta.online
                        val cur = _state.value
                        if (cur is DetailUiState.Content) {
                            _state.value = cur.copy(
                                detail = cur.detail.copy(
                                    ownerFans = fans,
                                    viewers = viewers,
                                ),
                            )
                        }
                        // 章节单独存 StateFlow —— 它要参与"章节列表"与
                        // "当前位置属于哪一章"，塞进 VideoDetail 会让那个
                        // data class 承担不属于它的职责。
                        _chapters.value = meta.chapters
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
            // 🔴 v1.5.1 修：**两片各自独立容错**，不能因为预取失败丢掉当前片。
            //
            // 原写法 `current.await() + next.await()` 有真实缺陷：
            // `async` 里任一个抛异常，`await()` 就会把它抛出来 →
            // **整个协程崩掉** → `_danmaku` 保持上一次的值（首次是空）→
            // 表现为「这个视频没有弹幕」，而其实**当前片拉到了**。
            //
            // 触发场景很常见：视频接近末尾时 `segment + 1` 超出范围，
            // 接口返回非 0 code 或空 → 预取失败 → 当前片的弹幕也被丢掉。
            //
            // 现在：各自 `runCatching`，当前片失败才算失败；预取失败忽略。
            val current = runCatching { dr.segment(cid, segment) }.getOrDefault(emptyList())
            val next = runCatching { dr.segment(cid, segment + 1) }.getOrDefault(emptyList())

            // ⚠️ 竞态防护：这期间可能已切分P（cid 变了）。
            // 若不再匹配，丢弃本次结果，避免把旧 cid 的弹幕盖上去。
            val nowCid = runCatching {
                (_state.value as? DetailUiState.Content)
                    ?.detail?.pages?.getOrNull(_currentPage.value)?.cid
            }.getOrNull()
            if (nowCid != null && nowCid != cid) return@launch

            _danmaku.value = (current + next).sortedBy { it.progressMs }
        }
    }

    /** 切换分P 时重置弹幕（不同 cid 弹幕不同）。 */
    private fun resetDanmaku() {
        loadedSegment = 0
        _danmaku.value = emptyList()
    }

    /**
     * 当前视频的章节（v1.5.3）。
     *
     * ## ⚠️ 空列表是**常态**，不是失败
     *
     * 实测扫了排行榜 + 热门共 **60 个视频，`view_points` 全为空数组** ——
     * 章节是 UP 主投稿时**手动添加**的，属少数视频。
     *
     * 所以 UI 侧必须把「空」显示成**诚实的空态**（"该视频没有章节"），
     * 既不能造假数据，也不能把空当成加载失败。
     */
    private val _chapters = MutableStateFlow<List<com.example.biliv3.data.VideoChapter>>(emptyList())
    val chapters: StateFlow<List<com.example.biliv3.data.VideoChapter>> = _chapters.asStateFlow()

    /** 当前播放位置落在哪一章（null = 不在任何章节内 / 没有章节）。 */
    fun chapterAt(positionSeconds: Int): com.example.biliv3.data.VideoChapter? =
        _chapters.value.firstOrNull { it.contains(positionSeconds) }

    /**
     * 跳转到指定章节（空降）。
     *
     * ⚠️ 与「自动跳过片段」不同 —— 这是**用户主动点击**，
     * 所以不受 `sponsorBlockEnabled` 开关约束，也不做二次确认。
     *
     * @param chapterIndex `chapters` 里的下标
     * @param player 当前播放器（null 时只更新 UI 不 seek）
     */
    fun jumpToChapter(
        chapterIndex: Int,
        player: androidx.media3.exoplayer.ExoPlayer?,
    ) {
        val ch = _chapters.value.getOrNull(chapterIndex) ?: return
        runCatching { player?.seekTo(ch.fromSeconds * 1000L) }
    }

    /** 切换分P 时重置章节（不同 cid 章节不同）。 */
    private fun resetChapters() {
        _chapters.value = emptyList()
    }

    // ---------------- 进度条拖动预览（v1.5.3） ----------------

    /**
     * 当前视频的预览精灵图。
     *
     * `null` = 还没拉到 / 该视频没有预览资源 —— UI 两种情况都降级为
     * "只显示时间文字"，不伪造画面。
     *
     * ⚠️ 与弹幕不同，**不按进度懒加载** —— 整张精灵图一次拉完
     * （实测约 1.5 MB，包含全部 68 帧），拖动时才裁帧。
     * 分次拉反而会在拖动中产生 IO 抖动。
     */
    private val _videoshot = MutableStateFlow<com.example.biliv3.data.Videoshot?>(null)
    val videoshot: StateFlow<com.example.biliv3.data.Videoshot?> = _videoshot.asStateFlow()

    /** 已拉过预览的 cid（换分P 时 cid 变 → 要重拉）。 */
    private var videoshotCid = 0L

    /**
     * 拉当前分P 的进度条预览。
     *
     * 失败 / 无资源都静默保持 null —— 预览是增强功能，
     * 弹错误反而打断拖动。
     */
    private fun loadVideoshot(cid: Long) {
        val repo = videoshotRepo ?: return
        if (cid <= 0L || cid == videoshotCid) return
        videoshotCid = cid
        // 换分P 先把旧图清掉，否则会短暂显示**上一个分P**的预览帧
        _videoshot.value = null

        viewModelScope.launch {
            val shot = repo.fetch(bvid, cid)
            // 竞态防护：这期间可能又切了分P
            if (videoshotCid == cid) _videoshot.value = shot
        }
    }

    // ---------------- AI 总结（v1.6.3）----------------

    /** AI 总结的 UI 状态（弹层订阅）。 */
    private val _summaryState = MutableStateFlow<AiSummaryUiState>(AiSummaryUiState.Idle)
    val summaryState: StateFlow<AiSummaryUiState> = _summaryState.asStateFlow()

    /**
     * 按 `bvid:cid` 缓存已生成的总结。
     *
     * ## 为什么缓存（需求："已经生成过总结可以缓存，避免不必要的重复请求"）
     *
     * 第三方总结要跑一次 LLM（几十秒 + 花钱）。用户关掉弹层再打开
     * 是很自然的动作，每次都重跑既慢又浪费。
     *
     * ## 为什么只缓存在内存、不落盘
     *
     * 与 `PlaybackProgressStore`（需要跨重启）的取舍不同：总结是
     * **当次观看的辅助信息**，且它的正确性依赖"当时的字幕与模型版本"。
     * 存盘会让用户几天后看到一份基于旧字幕的总结，却以为是最新的。
     *
     * 换分P（cid 变）自然拿到不同的缓存条目 —— key 里带 cid 就是为了这个。
     */
    private val summaryCache = HashMap<String, com.example.biliv3.data.ai.VideoSummary>()

    /** AI 总结是否可用（仓库已注入）。 */
    val aiSummaryAvailable: Boolean get() = aiSummaryRepo != null

    /**
     * 取 AI 总结（官方优先 → 第三方）。
     *
     * ## 缓存命中时**立即**返回，不再请求
     *
     * 这是需求明确要求的"避免不必要的重复请求"。
     */
    fun loadSummary() {
        val repo = aiSummaryRepo ?: return
        val cur = _state.value
        if (cur !is DetailUiState.Content) return

        val cid = cur.detail.pages.getOrNull(_currentPage.value)?.cid ?: cur.detail.cid
        val key = "$bvid:$cid"

        // 命中缓存：直接给结果（不重新请求）
        summaryCache[key]?.let {
            _summaryState.value = AiSummaryUiState.Done(it)
            return
        }

        viewModelScope.launch {
            _summaryState.value = AiSummaryUiState.Loading(AiSummaryUiState.Step.OFFICIAL)

            // 🔴 v1.6.7：改用 summarizeState —— 它返回**真实状态**，
            //    不再把一切失败压成"没有权限"。
            //    旧实现调 summarize() 拿 Result，失败时只剩一句 message，
            //    UI 只能猜，于是显示成"没有访问权限"。
            val st = repo.summarizeState(
                bvid = bvid,
                cid = cid,
                upMid = cur.detail.ownerMid,
                title = cur.detail.title,
                desc = cur.detail.desc,
            )

            _summaryState.value = toUiState(st, repo)
        }
    }

    /**
     * 真实状态 → UI 状态（v1.6.7）。
     *
     * ## 🔴 为什么这一层必须一一对应，不能合并
     *
     * 需求明确禁止「API 失败 → hasPermission=false → 显示没有权限」。
     * 所以这里**每个数据层状态映射到各自的 UI 文案**，
     * 并且区分「能不能重试」—— 不可重试的状态不给重试按钮，
     * 否则是在引导用户反复点击一个永远不会成功的按钮。
     */
    private fun toUiState(
        st: com.example.biliv3.data.ai.AiSummaryState,
        repo: com.example.biliv3.data.ai.AiSummaryRepository,
    ): AiSummaryUiState = when (st) {
        is com.example.biliv3.data.ai.AiSummaryState.Success ->
            AiSummaryUiState.Done(st.summary)

        com.example.biliv3.data.ai.AiSummaryState.Loading ->
            AiSummaryUiState.Loading(AiSummaryUiState.Step.OFFICIAL)

        // ---- 确定性的"走不通"，**不给重试**（重试没意义）----
        com.example.biliv3.data.ai.AiSummaryState.NotLoggedIn ->
            AiSummaryUiState.Blocked(
                title = "登录后使用 AI 总结",
                detail = "官方 AI 总结需要登录账号。登录后回来重新打开即可。",
                action = AiSummaryUiState.BlockedAction.LOGIN,
            )

        com.example.biliv3.data.ai.AiSummaryState.Unauthorized ->
            AiSummaryUiState.Blocked(
                title = "当前账号暂无 AI 总结权限",
                detail = "服务端明确返回权限不足。这不是网络问题，重试无效。",
                action = AiSummaryUiState.BlockedAction.NONE,
            )

        com.example.biliv3.data.ai.AiSummaryState.NoSpeech ->
            AiSummaryUiState.Blocked(
                title = "该视频没有可用于总结的语音内容",
                detail = "官方没有识别到语音，因此无法生成总结。",
                action = AiSummaryUiState.BlockedAction.NONE,
            )

        com.example.biliv3.data.ai.AiSummaryState.Unsupported ->
            AiSummaryUiState.Blocked(
                title = "该视频暂不支持 AI 总结",
                detail = "服务端返回该视频不支持摘要。",
                action = AiSummaryUiState.BlockedAction.NONE,
            )

        // ---- 生成中：给"刷新"而不是"重试" ----
        com.example.biliv3.data.ai.AiSummaryState.Generating ->
            AiSummaryUiState.Blocked(
                title = "AI 总结正在生成",
                detail = "服务端正在处理这个视频，稍后刷新可能就有了。",
                action = AiSummaryUiState.BlockedAction.REFRESH,
            )

        // ---- 可恢复的失败：给重试 ----
        is com.example.biliv3.data.ai.AiSummaryState.NetworkError ->
            AiSummaryUiState.Error(message = st.message, needsConfig = false)

        is com.example.biliv3.data.ai.AiSummaryState.ServerError ->
            AiSummaryUiState.Error(
                message = "AI 总结服务异常：${st.message}",
                needsConfig = false,
            )

        is com.example.biliv3.data.ai.AiSummaryState.BadRequest ->
            AiSummaryUiState.Error(message = st.message, needsConfig = false)

        com.example.biliv3.data.ai.AiSummaryState.ParseError ->
            AiSummaryUiState.Error(
                message = "拿到了总结内容但无法解析，请重试",
                needsConfig = false,
            )

        // ---- 官方不可用 + 第三方没配：说明缺什么 ----
        is com.example.biliv3.data.ai.AiSummaryState.ThirdPartyUnavailable ->
            AiSummaryUiState.Error(
                message = st.reason,
                needsConfig = !repo.thirdPartyUsable(),
            )

        com.example.biliv3.data.ai.AiSummaryState.Idle ->
            AiSummaryUiState.Idle
    }

    /** 关闭总结弹层（保留缓存，下次打开立即出结果）。 */
    fun resetSummaryState() {
        _summaryState.value = AiSummaryUiState.Idle
    }

    /** 当前 AI 配置（给设置页/提示用）。 */
    fun aiConfig(): com.example.biliv3.data.ai.AiConfig =
        aiSummaryRepo?.config() ?: com.example.biliv3.data.ai.AiConfig()

    // ---------------- 评论操作 ----------------

    /**
     * 拉首屏评论。
     *
     * ## 🔴 失败必须进入错误态，不能显示成"还没有评论"（v1.4.2 修）
     *
     * 首版这里是 `.onFailure { /* 静默：评论是增强模块 */ }` ——
     * 一个**空块**，既不写状态也不打日志。于是网络失败 / 风控 `-352` 时：
     *
     * ```
     * _comments 保持空 + _commentLoading 置 false
     *   → UI 走 `comments.isEmpty()` 分支
     *   → 显示「还没有评论，来说两句吧」
     * ```
     *
     * 这是**一句假的事实断言**：视频有 8000 条评论时，用户会以为
     * 评论被清空了。评论确实是增强模块（不该阻断详情页），
     * 但"不阻断"不等于"把失败说成空" —— 两者必须分开。
     *
     * 现在失败写入 [_commentError]，UI 据此显示"加载失败 + 重试"。
     */
    fun loadComments() {
        val cr = commentRepo ?: return
        val cur = _state.value
        if (cur !is DetailUiState.Content) return

        viewModelScope.launch {
            _commentLoading.value = true
            _commentError.value = null
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
                .onFailure { e ->
                    // 保留真实原因：UI 显示可读文案，日志留原始异常便于排查
                    _commentError.value = userMessageFor(e)
                    android.util.Log.w(TAG, "loadComments failed", e)
                }
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
     *
     * ## ⚠️ 必须同时处理顶层评论与内嵌回复（v1.4.2 修）
     *
     * 首版只 map 了 `_comments`（顶层列表）。但二级回复存在
     * `CommentItem.replies` 里，**不在顶层列表中**，于是给回复点赞时：
     *
     * 1. 乐观更新找不到 `rpid` 匹配 → 静默什么都不改（图标不动）
     * 2. API 实际成功 → 但 UI 永远不会显示
     * 3. 用户看到的是「点了没反应」，而服务端已经记了一次赞
     *
     * 现在用一个递归 map：先匹配顶层，匹配不到再进入各自的 `replies` 找。
     * 回复的回复（三级）接口不返回，但递归写法天然兼容。
     */
    fun likeComment(comment: com.example.biliv3.data.model.CommentItem) {
        val cr = commentRepo ?: return
        if (!isLoggedIn) return toast("请先登录")

        val target = !comment.liked
        val delta = if (target) 1 else -1

        // 乐观更新（顶层 + 内嵌回复）
        _comments.value = updateComment(_comments.value, comment.rpid) { c ->
            c.copy(liked = target, likeCount = (c.likeCount + delta).coerceAtLeast(0))
        }

        viewModelScope.launch {
            cr.likeComment(oid = comment.oid, rpid = comment.rpid, like = target)
                .onFailure { e ->
                    // 回滚（liked 与 count 一起）
                    _comments.value = updateComment(_comments.value, comment.rpid) { c ->
                        c.copy(
                            liked = !target,
                            likeCount = (c.likeCount - delta).coerceAtLeast(0),
                        )
                    }
                    toast(userMessageFor(e))
                }
        }
    }

    /**
     * 在评论树里按 `rpid` 定位并替换，找不到就原样返回。
     *
     * 顶层找不到时会**递归进入每条评论的 [CommentItem.replies]** ——
     * 这是回复点赞能正确生效的关键（见 [likeComment] 的说明）。
     */
    private fun updateComment(
        list: List<com.example.biliv3.data.model.CommentItem>,
        rpid: Long,
        transform: (com.example.biliv3.data.model.CommentItem) -> com.example.biliv3.data.model.CommentItem,
    ): List<com.example.biliv3.data.model.CommentItem> =
        updateCommentInTree(list, rpid, transform)

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
        // 分P 变了 → cid 变了 → 弹幕/片段/章节都不同，必须重置
        resetDanmaku()
        resetSkipSegments()
        resetChapters()
        fetchPlayInfo(cur.detail, index)
    }

    /** 切换清晰度。不缓存旧 URL（约 2h 过期）。 */
    fun selectQuality(quality: Int) {
        val cur = _state.value
        if (cur !is DetailUiState.Content) return
        // 用户显式选档 → 记下来，本次播放内后续取流（切分P / 重试）沿用，
        // 而不是被"默认清晰度"覆盖回去
        manualQuality = quality
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

    /**
     * 点赞 / 取消点赞。
     *
     * ## 🔴 必须防并发点击（v1.4.2 修 #15）
     *
     * 首版没有任何在途保护：每次点击都立刻 `launch` 一个请求。
     * 用户快速点 3 次会发出**三个并发请求**：
     *
     * ```
     * 点击1 → liked=false → 请求A(like=1)
     * 点击2 → liked=true  → 请求B(like=2)
     * 点击3 → liked=false → 请求C(like=1)
     * ```
     *
     * 三个请求的**响应顺序不确定**，服务端最终状态取决于最后到达的那个，
     * 而本地 UI 状态取决于最后一次点击 —— 两者可能永久不一致
     * （UI 显示已赞、服务端其实已取消，或反之）。
     *
     * ## 做法：在途时忽略后续点击
     *
     * 用 [_likeInFlight] 标记。在途期间的点击**直接丢弃**而不是排队 ——
     * 点赞是幂等的开关语义，"连点 3 次"的用户意图就是"切一下"，
     * 排队执行 3 次反而会转回来。
     *
     * 请求结束（无论成功失败）都在 `finally` 里复位标记，
     * 否则一次失败会把按钮永久锁死。
     */
    fun toggleLike() {
        val repoI = interactions ?: return
        if (!repoI.isLoggedIn) return toast("请先登录")
        // 在途则忽略：避免并发请求导致本地与服务端状态错乱
        if (_likeInFlight.value) return
        _likeInFlight.value = true

        val next = !_interaction.value.liked
        // 乐观更新：先改 UI 再发请求，失败回滚。
        // 点赞是高频轻操作，等网络回来再变会有明显延迟感。
        _interaction.value = _interaction.value.copy(liked = next)
        viewModelScope.launch {
            try {
                repoI.like(bvid, next)
                    .onFailure { e ->
                        // 只在**仍然是本次操作的目标态**时回滚。
                        // 若期间状态已被其它来源改写（如投币同时点赞），
                        // 盲目回滚会把那个正确的状态覆盖掉。
                        if (_interaction.value.liked == next) {
                            _interaction.value = _interaction.value.copy(liked = !next)
                        }
                        toast(userMessageFor(e))
                    }
            } finally {
                _likeInFlight.value = false
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

    /**
     * 收藏 / 取消收藏。
     *
     * ## 同样需要防并发（与 [toggleLike] 同理）
     *
     * 快速连点会并发发出 add / del 两组请求，且**收藏夹接口比点赞更重**
     * （要先查默认收藏夹）。响应乱序会让"到底收藏了没有"变得不确定，
     * 而收藏状态还要通过 `favoritesSync` 广播给其它页面 ——
     * 一个错误状态会被同步到整个 App。
     */
    fun toggleFavorite() {
        val repoI = interactions ?: return
        if (!repoI.isLoggedIn) return toast("请先登录")
        if (_favoriteInFlight.value) return

        // 🔴 v1.5.1：必须传 **aid**（数字），不能传 bvid ——
        // `fav/resource/deal` 的 `rid` 只认 aid，传 bvid 会静默失败
        // （本地图标变了、服务端没收藏 = 用户看到的"收藏失效"）。
        val cur = _state.value
        val aid = (cur as? DetailUiState.Content)?.detail?.aid ?: 0L
        if (aid <= 0L) return toast("视频信息未就绪，请稍后重试")

        _favoriteInFlight.value = true

        val next = !_interaction.value.favored
        _interaction.value = _interaction.value.copy(favored = next)
        viewModelScope.launch {
            try {
                repoI.favorite(aid, next)
                    .onSuccess {
                        toast(if (next) "已收藏" else "已取消收藏")
                        // ⚠️ 通知其它页面刷新（收藏列表 / 我的页计数）。
                        // 不通知的话，用户从详情页收藏后回到「我的收藏」，
                        // 列表还是旧的 —— 就是"功能孤立、状态不同步"。
                        favoritesSync.notifyChanged()
                    }
                    .onFailure { e ->
                        // 仅当仍是我们设的目标态时才回滚，避免覆盖其它来源
                        if (_interaction.value.favored == next) {
                            _interaction.value = _interaction.value.copy(favored = !next)
                        }
                        toast(userMessageFor(e))
                    }
            } finally {
                _favoriteInFlight.value = false
            }
        }
    }

    /** 分享上报（分享面板由 UI 层唤起）。 */
    fun onShared() {
        viewModelScope.launch { interactions?.shareReport(bvid) }
    }

    // ---------------------------------------------------------------------
    // 收藏夹选择（v1.6.7）
    // ---------------------------------------------------------------------

    /**
     * 收藏夹面板状态。
     *
     * ## 🔴 为什么需要它（用户报告的真实问题）
     *
     * 原实现点「收藏」**直接收藏到默认夹**，用户无法选择。
     * 模拟器实测：点一下 → `fav/resource/deal code=0`，
     * 但用户根本不知道收藏到哪去了。
     */
    private val _favSheet = MutableStateFlow(FavSheetState())
    val favSheet: StateFlow<FavSheetState> = _favSheet.asStateFlow()

    /**
     * 打开收藏夹面板。
     *
     * 并行拉两份数据：
     * 1. **我有哪些收藏夹**（`fav/folder/created/list-all`）
     * 2. **这个视频已在哪些夹里**（`fav/resource/ids`）
     *
     * ## 为什么要拿第 2 份
     *
     * 没有它就只能把"已收藏"当"未收藏"显示 —— 那是**假状态**：
     * 用户看到全空，以为自己没收藏过，点一下反而变成"重复收藏"
     * 或"意外取消"。
     */
    fun openFavSheet() {
        val repoI = interactions ?: return
        if (!repoI.isLoggedIn) return toast("请先登录")

        val cur = _state.value
        val aid = (cur as? DetailUiState.Content)?.detail?.aid ?: 0L
        if (aid <= 0L) return toast("视频信息未就绪，请稍后重试")

        val mid = repoI.currentMid
        if (mid <= 0L) return toast("登录信息不完整，请重新登录")

        // ⚠️ `libraryRepo` 是可空注入（预览/测试环境为 null）——
        //    没有它就拉不到收藏夹列表，如实报错而不是显示空面板。
        val lib = libraryRepo
        if (lib == null) return toast("收藏夹功能不可用")

        _favSheet.value = FavSheetState(open = true, loading = true)

        viewModelScope.launch {
            // 两个请求互不依赖 → 并行（串行会多一个 RTT 的等待感）
            val foldersDeferred = async { runCatching { lib.favoriteFolders(mid) } }
            val idsDeferred = async { runCatching { repoI.favoriteFolderIds(aid) } }

            val foldersRes = foldersDeferred.await()
            val idsRes = idsDeferred.await()

            val folders = foldersRes.getOrNull()
            if (folders == null) {
                // 收藏夹列表拿不到 → 如实报错，**不显示空列表**
                // （空列表会被读成"你没有收藏夹"，那是假的事实断言）
                _favSheet.value = FavSheetState(
                    open = true,
                    loading = false,
                    error = userMessageFor(
                        foldersRes.exceptionOrNull() ?: IllegalStateException("收藏夹加载失败"),
                    ),
                )
                return@launch
            }

            _favSheet.value = FavSheetState(
                open = true,
                loading = false,
                folders = folders,
                // 拿不到"已在哪些夹"时给空集合 —— 勾选态会全部为空。
                // ⚠️ 这是**已知的信息缺失**，不是"确认未收藏"；
                //    面板里会带一句说明（见 UI），不假装确定。
                selected = idsRes.getOrNull()?.toSet() ?: emptySet(),
                selectionUnknown = idsRes.isFailure,
            )
        }
    }

    /** 关闭收藏夹面板。 */
    fun closeFavSheet() {
        _favSheet.value = FavSheetState()
    }

    /**
     * 切换某个收藏夹的选中态。
     *
     * ## 立即写服务端（不攒到"确定"）
     *
     * 每次勾选/取消都立刻调 `fav/resource/deal`：
     * - 成功 → 更新本地勾选态 + 通知其它页面刷新
     * - 失败 → **回滚勾选态** + 显示真实原因
     *
     * 不攒批的理由：B 站的收藏夹是**独立**的资源，逐个提交的语义
     * 与用户"点一下勾一下"的心智一致；攒批反而会出现
     * "关了面板没保存"这类丢失。
     *
     * ## 防并发
     *
     * 同一时刻只允许一个写请求（与 `toggleFavorite` 同一约定）——
     * 连点两下会发出 add + del 两组请求，响应乱序后状态不确定。
     */
    fun toggleFolder(folderId: Long) {
        if (folderId <= 0L) return
        if (_favWriteInFlight.value) return

        val repoI = interactions ?: return
        val cur = _state.value
        val aid = (cur as? DetailUiState.Content)?.detail?.aid ?: 0L
        if (aid <= 0L) return

        val sheet = _favSheet.value
        val wasSelected = folderId in sheet.selected
        val next = !wasSelected

        // 乐观更新勾选态（失败回滚）
        _favSheet.value = sheet.copy(
            selected = if (next) sheet.selected + folderId else sheet.selected - folderId,
            busyFolderId = folderId,
            error = null,
        )
        _favWriteInFlight.value = true

        viewModelScope.launch {
            try {
                repoI.favoriteTo(aid, folderId, next)
                    .onSuccess {
                        // 勾选态已经乐观更新过，这里只同步"详情页的收藏按钮"
                        // —— 至少有一个夹时按钮为"已收藏"
                        val nowFavored = _favSheet.value.selected.isNotEmpty()
                        _interaction.value = _interaction.value.copy(favored = nowFavored)
                        favoritesSync.notifyChanged()
                    }
                    .onFailure { e ->
                        // 回滚到操作前
                        val s = _favSheet.value
                        _favSheet.value = s.copy(
                            selected = if (next) s.selected - folderId else s.selected + folderId,
                            error = userMessageFor(e),
                        )
                    }
            } finally {
                _favWriteInFlight.value = false
                _favSheet.value = _favSheet.value.copy(busyFolderId = 0L)
            }
        }
    }

    /** 收藏夹写入防并发。 */
    private val _favWriteInFlight = MutableStateFlow(false)

    /**
     * 收藏夹面板状态。
     *
     * @param open 是否展开
     * @param loading 正在拉列表
     * @param folders 我的收藏夹
     * @param selected **已收藏到**的收藏夹 id
     * @param selectionUnknown `selected` 是否因接口失败而不可信
     *   （true 时 UI 要说明"勾选态可能不准确"，不假装确定）
     * @param busyFolderId 正在写入的收藏夹（该行显示进行中）
     * @param error 最近一次失败原因（**如实显示**）
     */
    data class FavSheetState(
        val open: Boolean = false,
        val loading: Boolean = false,
        val folders: List<com.example.biliv3.data.FavFolder> = emptyList(),
        val selected: Set<Long> = emptySet(),
        val selectionUnknown: Boolean = false,
        val busyFolderId: Long = 0L,
        val error: String? = null,
    )

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

            // ---- 自动画质（未发版）----
            //
            // 🔴 这里解决的是一个**既有缺陷**：「默认清晰度」设置项
            //    此前是**死入口** —— 设置页能选，但没有任何调用方读它
            //    （`grep defaultQuality` 只有设置页自己）。
            //    现在它真正参与取流决策。
            val aq = settingsStore?.settings?.first()?.autoQuality
                ?: AutoQualitySettings()

            // 用户本次显式选过的档 > 设置里的默认档
            val wanted = when {
                manualQuality > 0 -> manualQuality
                aq.enabled -> aq.preferredQn
                else -> settingsStore?.settings?.first()?.defaultQuality ?: 0
            }

            // `loggedIn` 只影响"无档位时怎么解释"（未登录 vs 视频不支持），
            // 不影响取流本身 —— 见 VideoRepository.playInfo 的说明
            runCatching {
                repo.playInfo(
                    bvid = detail.bvid,
                    cid = cid,
                    quality = wanted,
                    autoQuality = aq,
                    loggedIn = isLoggedIn,
                )
            }
                .onSuccess {
                    _playState.value = PlayState.Ready(it)
                    // 取流成功 → 顺手拉进度条预览（同一 cid，失败静默）
                    loadVideoshot(cid)
                }
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
 * 日志标签。
 *
 * 用于「失败被静默」的地方 —— 用户看到的可以是友好文案，
 * 但**原始异常必须留下痕迹**，否则线上问题无法定位。
 * 与其它 Repository 的 `Bili*` 前缀保持一致。
 */
private const val TAG = "BiliVideoDetail"

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

/**
 * 在评论树里按 `rpid` 定位并替换，找不到就**原样返回同一引用**。
 *
 * ## 为什么抽成顶层函数
 *
 * 它是「回复点赞」正确性的全部逻辑，且**不依赖 Android** ——
 * 抽出来就能被普通 JVM 单测覆盖（见 `CommentLikeTest`）。
 * 留在 ViewModel 里会变成私有方法，只能靠人工点测；
 * 而这类 bug 恰恰是"人工点测容易漏"的（图标不动很容易被当成没点准）。
 *
 * ## 为什么要进 `replies`
 *
 * 二级回复存在 [CommentItem.replies] 里，**不在顶层列表**。
 * 首版只 map 顶层，导致回复点赞找不到匹配 → 静默无操作 →
 * API 成功但 UI 永远不变（v1.4.2 修）。
 *
 * ## 为什么找不到时返回原引用
 *
 * 返回"内容相同的新列表"会让 Compose 认为数据变了而多余重组；
 * 返回同一引用则天然跳过。
 */
internal fun updateCommentInTree(
    list: List<CommentItem>,
    rpid: Long,
    transform: (CommentItem) -> CommentItem,
): List<CommentItem> {
    // 顶层命中：只改这一条，不必进入子树
    if (list.any { it.rpid == rpid }) {
        return list.map { if (it.rpid == rpid) transform(it) else it }
    }
    // 顶层没有 → 在各自的 replies 里找
    var changed = false
    val next = list.map { c ->
        if (c.replies.isEmpty()) return@map c
        val updated = c.replies.map { r -> if (r.rpid == rpid) transform(r) else r }
        if (updated != c.replies) {
            changed = true
            c.copy(replies = updated)
        } else {
            c
        }
    }
    return if (changed) next else list
}
