package com.example.biliv3.ui.video

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import com.example.biliv3.design.tokens.Motion
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.example.biliv3.data.model.PlayInfo
import com.example.biliv3.data.model.VideoDetail
import com.example.biliv3.data.model.VideoItem
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.data.model.formatRelativeTime
import com.example.biliv3.design.ruleTop
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.player.PlayerFactory
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.component.ProvideShimmer
import com.example.biliv3.ui.component.SkeletonBox
import com.example.biliv3.ui.component.VideoCard

/**
 * 视频详情页。
 *
 * ## 布局（自上而下，紧凑）
 *
 * ```
 * [← 返回]                    ← 52dp 顶栏
 * ┌───────────────────────┐
 * │  封面 / 播放器   ⚙    │  ← 16:9，点击封面才起播
 * └───────────────────────┘
 * [头像] 标题两行…            ← UP 内联在标题左侧
 * 播放量 · 弹幕 · 时间
 * 简介（收起，点击展开）
 * [👍 1.2万] [🪙 投币] [⭐ 收藏] [↗ 分享]
 * 分P 选择器（仅多P）
 * 相关推荐网格
 * ```
 *
 * ## 相对上一版的改动
 *
 * | 项 | 之前 | 现在 |
 * |---|---|---|
 * | 起播 | 进页面自动取流播放 | **点封面才起播**（省流量、首屏更快） |
 * | UP 主 | 独占一个卡片区域 | **内联到标题左侧** |
 * | 简介 | 常显 4 行 | **默认收起，可展开** |
 * | 清晰度 | 正文区一大块胶囊 | **收进右上角齿轮** |
 * | 互动 | 无 | 点赞/投币/收藏/分享 |
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun VideoDetailScreen(
    bvid: String,
    windowSize: WindowSize,
    /**
     * 进入后要定位的评论 rpid（空 = 不定位）。
     *
     * 来自「AI 查成分 → 在 APP 内查看」：只打开视频是不够的，
     * 用户还得自己在几百条评论里翻。带上它后会自动切评论 Tab、
     * 翻页找到该评论、滚动并短暂高亮。
     */
    focusRpid: String = "",
    /** 空降助手：是否启用自动跳过片段（来自设置）。 */
    sponsorBlockEnabled: Boolean = false,
    /** 空降助手：可跳过区间画到进度条上（v1.6.3）。 */
    skipSegments: List<com.example.biliv3.data.SkipSegment> = emptyList(),
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onVideoClick: (String) -> Unit = {},
    onLoginRequired: () -> Unit = {},
    /**
     * 应用设置。
     *
     * ⚠️ 传进来而不是让 ViewModel 自己读 —— 详情页的 ViewModel 是
     * key = "detail-$bvid" 的**每视频一份**，若由它持有 SettingsStore，
     * 会出现"每个视频各订阅一次 DataStore"的浪费。
     * 由导航层订阅一次、按值传入更省。
     *
     * 数据流是**单向**的：设置页写 DataStore → 这里读 → 应用到播放器。
     */
    settings: com.example.biliv3.data.Settings = com.example.biliv3.data.Settings(),
    /**
     * Activity 级播放器持有者。
     *
     * 由导航层从 `AppContainer` 传入。**不能用默认值新建** ——
     * 那会变成"每个页面各持一个播放器"，PiP 与切页继续播都会失效。
     * 默认值只用于 Compose Preview。
     */
    holder: com.example.biliv3.player.PlayerHolder = rememberPreviewHolder(),
    /**
     * 是否处于 PiP 小窗。
     *
     * PiP 下必须隐藏绝大多数控件（顶栏、互动栏、设置齿轮）——
     * 小窗尺寸下它们要么挤压画面，要么根本点不中。
     */
    isInPip: Boolean = false,
    /**
     * 请求进入 PiP。不支持的设备上应传入 `{ false }`。
     */
    onEnterPip: () -> Boolean = { false },
    /**
     * 切换「听视频」（v1.3.0）。
     *
     * 由导航层接到 `container.playbackController.toggleAudioOnly()` ——
     * 模式状态在 `PlaybackController`（全 App 唯一写入方），本页只是触发点。
     * 切换时**播放位置保持**（先记位置 → 重建 MediaSource → seek 回去）。
     */
    onToggleAudioOnly: () -> Unit = {},
    /**
     * 打开黑胶唱片模式（v1.3.0）。
     *
     * 由导航层 `navController.navigate(Routes.PLAYER)` 实现 ——
     * 黑胶要整屏空间，做成独立页面而不是本页内的浮层。
     */
    onOpenVinyl: () -> Unit = {},
    /**
     * v1.3.0：当前播放模式（看视频 / 听视频 / 黑胶）。
     *
     * 由导航层从 `container.playbackController.state.mode` 订阅后传入。
     * 页面**不持有**模式状态 —— 它是应用级的（切页 / PiP 后仍生效）。
     */
    playbackMode: com.example.biliv3.player.PlaybackMode =
        com.example.biliv3.player.PlaybackMode.VIDEO,
    /**
     * v1.3.0：把当前视频登记进应用级播放队列。
     *
     * 由导航层接到 `container.playbackController.registerInQueue(...)`。
     * **覆盖式**登记（队列 = 当前这一个视频），不是追加 ——
     * 理由见 `PlaybackQueue.setSingle` 的注释。
     */
    onRegisterInQueue: (String, Long, String, String, String) -> Unit =
        { _, _, _, _, _ -> },
    /**
     * 🔴 取流成功后把 [PlayInfo] 回填给 `PlaybackController`（v1.4.2 修）。
     *
     * ## 为什么必须有这个回调
     *
     * `onRegisterInQueue` 只登记**队列项**（bvid/cid/标题…），不含 URL；
     * 而 `PlaybackController.setMode()` 重建 MediaSource 时读的是它内部的
     * `currentPlayInfo`（含 URL）。两者此前是**断开的**。
     *
     * 后果（装机实测）：详情页播到 `00:08` → 点黑胶 →
     * 黑胶页显示 **`00:00 / 00:00`**、进度条消失；返回后变成「点击播放」。
     * 根因是 `setMode` 里 `if (info != null)` 判空失败，
     * **跳过了「记位置 → 重建 → seek 回去」**，播放器被释放且没重建。
     *
     * 参数：PlayInfo（含 videoUrl / audioUrl）+ 当前 aid。
     */
    onPlayInfoReady: (com.example.biliv3.data.model.PlayInfo, Long) -> Unit =
        { _, _ -> },
    /**
     * 播放器弹层里改弹幕档位时回调，用于**写回全局设置**。
     *
     * 参数顺序：启用 / 不透明度 / 字号 / 显示区域。
     * 不提供时只在本次播放内生效，不持久化。
     */
    onDanmakuSettingsChanged: (Boolean, Float, Float, Float) -> Unit =
        { _, _, _, _ -> },
    /** 设置里「自动起播」为 true 时使用。 */
    onApplySpeed: (Float) -> Unit = { },
    /** 点 UP 头像 / 名字 → 用户主页。 */
    onOwnerClick: (Long) -> Unit = {},
    /** 「查看全部 N 条回复」→ 楼中楼详情页。(oid, root, upMid) */
    onLoadMoreReplies: (Long) -> Unit = {},
    /** 正在加载更多回复的 rpid 集合（显示 loading + 防重复点击）。 */
    replyLoading: Set<Long> = emptySet(),
    /** 某条主评论的回复是否已拉到底。 */
    repliesExhausted: (Long) -> Boolean = { true },
    /** 要定位的评论 rpid（null = 不定位）。AI 查成分「在 APP 内查看」用。 */
    focusCommentRpid: String? = null,
    /** 定位完成（滚动+高亮）后回调，上层清空定位目标。 */
    onFocusCommentHandled: () -> Unit = {},
    /** 打开楼中楼详情页（oid, root, upMid）。
     *
     * 与 [onViewAllReplies] 是同一个语义 —— 保留两个名字是为了
     * 让"详情页内部转发"与"外部注入"两处可读性更好；
     * 实际只有后者会被调用。
     */
    onOpenReplyDetail: (Long, Long, Long) -> Unit = { _, _, _ -> },
    /**
     * 离线缓存入口。
     *
     * 传 null 表示不启用下载（预览环境）。启用时由导航层注入真实实现，
     * 详情页只负责"点下载"这个动作。
     */
    downloadEntry: com.example.biliv3.ui.download.VideoDownloadEntryViewModel? = null,
    /** 打开离线缓存管理页。 */
    onOpenDownloads: () -> Unit = {},
    /**
     * 打开私信列表（v1.5.3）。
     *
     * 「分享给 B站好友」走这条路：站内分享不需要任何第三方 SDK，
     * 是这个 App **真实能做到**的分享方式。
     */
    onOpenMessages: () -> Unit = {},
    /** 加入稍后再看。由导航层注入（需要 LibraryRepository）。 */
    onAddToView: (Long) -> Unit = {},
    /** 该视频是否已在稍后再看。 */
    inToView: Boolean = false,
    /** 续播位置（毫秒）。0 = 从头播。 */
    resumePositionMs: Long = 0L,
    /** 续播提示是否已被用户消费（点"继续"或"从头"后置 true）。 */
    onResumeConsumed: () -> Unit = {},
    /**
     * 打开 AI 总结（v1.6.3）。
     *
     * 由导航层接到"打开设置页的 AI 配置"（当第三方未配置时）。
     * 详情页本身只负责弹层。
     */
    onOpenAiSettings: () -> Unit = {},
    viewModel: VideoDetailViewModel = viewModel(
        key = "detail-$bvid",
        factory = VideoDetailViewModelFactory(bvid),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playState by viewModel.playState.collectAsStateWithLifecycle()
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val interaction by viewModel.interaction.collectAsStateWithLifecycle()
    val descExpanded by viewModel.descExpanded.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val subtitleTracks by viewModel.subtitleTracks.collectAsStateWithLifecycle()
    val activeSubtitle by viewModel.activeSubtitle.collectAsStateWithLifecycle()
    val subtitleLoading by viewModel.subtitleLoading.collectAsStateWithLifecycle()
    val subtitleError by viewModel.subtitleError.collectAsStateWithLifecycle()
    val danmaku by viewModel.danmaku.collectAsStateWithLifecycle()
    // 进度条拖动预览（v1.5.3）：null = 该视频没有预览资源，UI 降级
    val videoshot by viewModel.videoshot.collectAsStateWithLifecycle()
    // 章节（空降助手，v1.5.3）：空列表 = 该视频没有章节（常态，不显示该块）
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()

    /**
     * 当前播放位置（秒）—— **只用于高亮"正在哪一章"**（v1.5.3）。
     *
     * ⚠️ 数据来源是 `PlayerControls` 的上报，不是这里自己轮询 ——
     * `PlayerControls` 本来就在轮询 `player.currentPosition` 刷时间轴，
     * 复用它已有的循环是零成本；这里再起一条协程就是重复读同一个值。
     *
     * ⚠️ 只有在**有章节**时才需要它，所以 `chapters` 为空时这个值
     * 保持 0，不会引起任何额外重组。
     */
    var currentPositionSeconds by remember { mutableIntStateOf(0) }
    val danmakuEnabled by viewModel.danmakuEnabled.collectAsStateWithLifecycle()
    val danmakuAlpha by viewModel.danmakuAlpha.collectAsStateWithLifecycle()
    val danmakuFontScale by viewModel.danmakuFontScale.collectAsStateWithLifecycle()
    val danmakuArea by viewModel.danmakuArea.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    // 楼中楼就地分页（v1.5.1）：正在加载的 rpid 集合 —— 用于显示 loading 与防重复点击
    val replyLoading by viewModel.replyLoading.collectAsStateWithLifecycle()
    val coinBalance by viewModel.coinBalance.collectAsStateWithLifecycle()
    // 空降助手：已过滤 + 已合并的可跳过片段（v1.6.3）。
    // 用于把区间画到进度条上 —— 拖动前就能看见哪几段会被跳过。
    val skipSegments by viewModel.skipSegments.collectAsStateWithLifecycle()
    val commentTotal by viewModel.commentTotal.collectAsStateWithLifecycle()
    val commentLoading by viewModel.commentLoading.collectAsStateWithLifecycle()
    val commentLoadingMore by viewModel.commentLoadingMore.collectAsStateWithLifecycle()
    val commentHasMore by viewModel.commentHasMore.collectAsStateWithLifecycle()
    // 首屏评论失败原因（null = 没失败）。UI 据此区分「加载失败」与「还没有评论」。
    val commentError by viewModel.commentError.collectAsStateWithLifecycle()
    val commentSort by viewModel.commentSort.collectAsStateWithLifecycle()
    val colors = BiliTheme.colors
    val context = LocalContext.current

    var playerError by remember { mutableStateOf<String?>(null) }

    /**
     * ⚠️ 播放器来自 **Activity 级** [PlayerHolder]，不再是本页 `remember` 的。
     *
     * ## 为什么必须这样（PiP / 切页继续播的硬前提）
     *
     * 原先播放器是页面局部状态 + `DisposableEffect` 里 release，
     * 于是"页面销毁 → 播放器释放"。而 PiP 场景下：
     *
     * - 进 PiP 后，若导航栈变化或配置变更导致本 Composable 被销毁，
     *   播放器会跟着被 release → **小窗直接黑掉**
     * - 切到别的页面（如点相关推荐）也会中断声音
     *
     * 提升到 [PlayerHolder] 后，页面只做"绑定/解绑"，不负责生命周期。
     *
     * ## 页面销毁时**不 release**
     *
     * 只在**离开视频场景**（如返回键退出详情页且不是进 PiP）时才释放。
     * 这个判断放在 [onLeaveVideo] 回调里，由导航层决定。
     */
    val player = holder.player

    // 点击封面：取得播放器 + 开始取流
    val handleStartPlay: () -> Unit = {
        val p = holder.acquire(bvid)
        // 应用设置里的默认倍速。放在创建时就设，而不是起播后 ——
        // 否则用户会听到/看到一瞬间的原速，再"跳"到设定的倍速。
        runCatching { p.setPlaybackSpeed(settings.defaultSpeed) }
        viewModel.startPlayback()
    }

    /**
     * 把设置里的弹幕偏好同步到详情页。
     *
     * ## ⚠️ 必须跟随 `settings` 变化，不能只用 `LaunchedEffect(Unit)`
     *
     * 第一版写的是 `LaunchedEffect(Unit)`（只在进页面时同步一次），
     * 理由写的是"避免把本视频的临时调整冲掉"。**那个理由是错的**：
     *
     * - 播放器弹层里的临时调整，写进的正是**同一个 ViewModel**
     *   （`viewModel.setDanmakuArea(...)`），并不存在另一个"本视频临时值"
     *   会被设置页覆盖。
     * - 而 `LaunchedEffect(Unit)` 导致真正的问题：
     *   用户去设置页改了字号/不透明度，**返回视频页不生效** ——
     *   这就是"选择框与其他位置没有联动"。
     *
     * 现在用 `settings` 作 key：设置一变就同步。
     * 用户在播放器弹层里的调整依然立即生效（它直接写 ViewModel，
     * 不经过这里），两者不会互相打架。
     */
    LaunchedEffect(settings.danmakuEnabled, settings.danmakuAlpha,
        settings.danmakuFontScale, settings.danmakuArea) {
        viewModel.setDanmakuEnabled(settings.danmakuEnabled)
        viewModel.setDanmakuAlpha(settings.danmakuAlpha)
        viewModel.setDanmakuFontScale(settings.danmakuFontScale)
        viewModel.setDanmakuArea(settings.danmakuArea)
    }

    /**
     * 「自动起播」。
     *
     * 默认关闭（省流量、首屏更快）—— 这也是原先的行为。
     * 打开后进页面即取流起播，与官方 App 一致。
     *
     * ⚠️ 只在**首次进入**时触发一次：`LaunchedEffect(Unit)` 保证
     * 后续重组不会反复起播。
     */
    LaunchedEffect(Unit) {
        if (settings.autoPlay) handleStartPlay()
    }

    /**
     * ⚠️ 本页面**不再 release 播放器**（这是 PiP 的关键改动）。
     *
     * ## 历史沿革（两版，理由完全不同）
     *
     * **第一版**：`DisposableEffect(player) { onDispose { player?.release() } }`
     * → 把刚创建的播放器立刻释放掉，导致「视频完全无法播放」。
     * 根因是 key 变化时 Compose 先 dispose 旧 effect，而闭包读到的
     * 已经是新实例。改成 `DisposableEffect(Unit)` 后修复。
     *
     * **第二版（现在）**：播放器生命周期**上移到 [PlayerHolder]**，
     * 页面完全不再负责 release。
     *
     * 为什么必须再改：PiP 下本 Composable 可能被销毁，若仍在这里 release，
     * 小窗会直接黑掉、切页会中断声音。释放改由
     * [MainActivity.onDestroy]（真正退出应用）与导航层的
     * `onLeaveVideo` 决定。
     *
     * 这里只做**错误监听绑定**：把 holder 的错误回调接到本页的 UI 状态。
     * 解绑时只清回调，不碰播放器。
     */
    DisposableEffect(holder) {
        val prev = holder.onError
        holder.onError = { e -> playerError = describePlayerError(e) }
        onDispose {
            // 只恢复回调，**不释放播放器**
            holder.onError = prev
        }
    }

    LaunchedEffect(playState) {
        if (playState is PlayState.Ready) playerError = null
    }

    // ---- 毛玻璃帧源 ----
    //
    // 持续从共享 TextureView 抓帧，喂给 holder.backdrop。
    // 抓帧在 holder 里（跨页面共享同一份帧），这里只负责"启动它"。
    //
    // PiP 下关掉：小窗里没有玻璃面板，抓帧纯属浪费。
    com.example.biliv3.design.VideoBackdropEffect(
        backdrop = holder.backdrop,
        textureProvider = { holder.textureView },
        enabled = !isInPip,
    )

    /**
     * 左栏视图切换：true=评论，false=简介。
     *
     * ⚠️ 注意这是**切换控件**的状态，不是"简介展开"。
     * 简介正文的展开由 `descExpanded`（标题右侧倒 V）控制，两者独立。
     *
     * ⚠️ 默认必须是 **false（简介）**。
     * 此前是 `true`，于是点开任何视频第一眼看到的是评论区 ——
     * 用户还没看到这个视频讲什么、谁发的，就先被评论占据整屏
     * （而且评论模式下 UP 信息 / 互动栏全被隐藏，等于"信息最少的视图"
     * 反而成了默认视图）。评论需用户主动切换。
     */
    var commentTabSelected by remember { mutableStateOf(false) }

    // ---- 定位到指定评论（AI 查成分「在 APP 内查看」）----
    //
    // 进来时若带了 focusRpid：切到评论 Tab 并请求 ViewModel 翻页找到它。
    // 用 `focusRpid` 作 key —— 同一个页面被不同评论复用时会重新触发。
    LaunchedEffect(focusRpid) {
        if (focusRpid.isNotEmpty()) {
            commentTabSelected = true
            viewModel.focusComment(focusRpid)
        }
    }

    val focusCommentRpid by viewModel.focusCommentRpid.collectAsStateWithLifecycle()
    val focusingComment by viewModel.focusingComment.collectAsStateWithLifecycle()

    /** 一次性提示（发弹幕入口用，复用现有 snackbar 通路）。 */
    var toastMessage by remember { mutableStateOf<String?>(null) }

    /**
     * 正在回复的目标评论（null = 未在回复，输入框按"发主评论"处理）。
     *
     * 与评论输入框联动：点某条评论的「回复」→ 这里被设置 →
     * 输入框显示"回复 @某人"并带上 root/parent 参数。
     */
    var replyTarget by remember {
        mutableStateOf<com.example.biliv3.data.model.CommentItem?>(null)
    }

    /** 评论输入弹层开关。 */
    var showCommentInput by remember { mutableStateOf(false) }

    /** 弹幕输入弹层开关。 */
    var showDanmakuInput by remember { mutableStateOf(false) }

    var isFullscreen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    /**
     * 「更多」菜单开关（下载 / 稍后再看 / 分享渠道）。
     *
     * ⚠️ 这个菜单此前**不存在**，导致：
     * - `VideoDownloader`（315 行、含断点续传）没有任何 UI 入口
     * - `LibraryRepository.addToView()` 没有任何 UI 入口
     * - `ItemMoreMenu` 的分享渠道横排只在收藏夹页用到
     */
    var showMoreMenu by remember { mutableStateOf(false) }


    /**
     * 投币确认弹窗开关。
     *
     * ⚠️ 投币**必须**经确认 —— 硬币是不可撤销的消耗品，
     * 而互动栏里投币与点赞/收藏相邻，误触概率不低。
     */
    var showCoinDialog by remember { mutableStateOf(false) }

    /**
     * AI 总结弹层开关（v1.6.3）。
     *
     * 与其它弹层一样放在**根 Box 内**渲染 —— 保证覆盖在页面之上
     * （与评论输入浮层踩过的坑同源）。
     */
    var showSummary by remember { mutableStateOf(false) }

    // 打开投币弹窗前先拉余额（弹窗要显示）。
    // 放这里而不是弹窗内部：弹窗是纯展示组件，不该自己发请求。
    if (showCoinDialog) {
        LaunchedEffect(Unit) { viewModel.loadCoinBalance() }
    }

    /**
     * 分层返回（从最上层往下逐级消费）。
     *
     * ## ⚠️ 为什么必须显式分层（这是「退出时灵时不灵」的根因）
     *
     * 页面里有**多个**覆盖层，但它们的返回处理方式并不一致：
     *
     * | 覆盖层 | 实现 | 返回由谁处理 |
     * |---|---|---|
     * | 设置 / 投币 | `Dialog`（独立 window） | Dialog 自己，优先 |
     * | **评论输入 / 弹幕输入** | **页面内 Box**（不是 Dialog） | **此前没人处理** ❌ |
     *
     * 后两个之所以不用 Dialog，是因为 Dialog 的独立 window 配合
     * `decorFitsSystemWindows=false` 会让键盘避让失效（详见
     * `CommentInputSheet` 的说明）。代价就是**它们不拦截系统返回**。
     *
     * 此前这里的 `BackHandler` 只处理「全屏 → 退全屏」一种情况，
     * 于是评论/弹幕输入浮层开着时按返回：事件直接穿透到 NavHost →
     * `popBackStack()` → **整个视频页被弹掉**，用户回到首页。
     * 而浮层有没有开、当时是否全屏，都会影响命中哪条分支 ——
     * 表现就是"有时能退有时不能退 / 退得莫名其妙"。
     *
     * 现在按优先级逐层消费，保证每次返回只做一件事、且可预期：
     * 输入浮层 → 更多菜单 → 全屏 → 交给 NavHost 返回上一页。
     */
    val hasInputOverlay = showCommentInput || showDanmakuInput
    androidx.activity.compose.BackHandler(
        enabled = hasInputOverlay || showMoreMenu || isFullscreen,
    ) {
        when {
            // ① 输入浮层优先（最上层，且是"用户正在输入"的状态）
            showCommentInput -> {
                showCommentInput = false
                replyTarget = null
            }

            showDanmakuInput -> showDanmakuInput = false

            // ② 更多菜单
            showMoreMenu -> showMoreMenu = false

            // ③ 全屏 → 先退全屏（不退页面）
            isFullscreen -> isFullscreen = false
        }
    }

    /**
     * 全屏时进入**沉浸模式**（隐藏状态栏 / 导航栏）。
     *
     * ## ⚠️ 这是「全屏没铺满、有黑边留白」的直接原因
     *
     * `MainActivity` 调了 `enableEdgeToEdge()`，内容虽然铺到系统栏底下，
     * 但**系统栏本身仍然可见并占位**。此前全屏只把 Compose 内容
     * 换成 `fillMaxSize`，没有让系统栏真正隐藏 —— 于是：
     * - 状态栏仍在顶部占一条 → 画面顶部被裁/被挡
     * - 导航栏仍在底部占一条 → 画面底部留白
     * 观感就是"全屏了但没盖住整屏"。
     *
     * 修法：全屏时用 `WindowInsetsControllerCompat` 隐藏两条系统栏，
     * 并临时切到 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（滑动可临时唤出）。
     * 退出全屏 / 离开页面时**必须恢复**，否则会把系统栏永久藏掉
     * （`DisposableEffect` 的 onDispose 就是干这个的）。
     */
    val view = LocalView.current
    DisposableEffect(isFullscreen) {
        val window = (view.context as? android.app.Activity)?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = androidx.core.view.WindowCompat
                .getInsetsController(window, view)
            if (isFullscreen) {
                controller?.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                // 离开页面或退出全屏都要把系统栏还回来
                controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * 播放进度持久化（在线断点续播）。
     *
     * ## 为什么在 dispose 时上报，而不是逐帧
     *
     * 逐帧写 DataStore 是主线程 IO，会直接拖慢播放页。
     * 只需要"离开这个视频时记住看到哪"这一个时机 ——
     * 退出、切页、进 PiP 都覆盖到了。
     *
     * ## ⚠️ key 只放 bvid
     *
     * 放 `currentPage` 会导致**每次切分P 都重建 effect**，
     * 而 effect 重建 = 先 dispose 旧的 = 用旧分P 的进度写一次。
     * 用 `rememberUpdatedState` 在 dispose 时读最新分P，逻辑才正确
     * （与 MainShell 里播放器释放的坑是同一类）。
     */
    val latestPage by rememberUpdatedState(currentPage)
    val latestDetail by rememberUpdatedState(
        (state as? DetailUiState.Content)?.detail,
    )
    val latestPlayer by rememberUpdatedState(player)

    androidx.compose.runtime.DisposableEffect(bvid) {
        onDispose {
            val p = latestPlayer ?: return@onDispose
            val d = latestDetail ?: return@onDispose
            val pos = runCatching { p.currentPosition }.getOrDefault(0L)
            if (pos <= 0L) return@onDispose

            // 把当前分P 的 cid 也记上 —— 否则多P 视频的续播会串到 P1
            val cid = d.pages.getOrNull(latestPage)?.cid ?: d.cid
            viewModel.reportProgressForCid(cid, pos)
        }
    }

    /**
     * 续播提示条。
     *
     * 只在「有进度」且「用户还没点过」时显示，两种选择都要能点：
     * - 继续播放 → seek 到该位置
     * - 从头播放 → 忽略进度（并清除，避免每次进来都弹）
     *
     * ⚠️ 阈值由 `PlaybackProgressStore.shouldResume` 判定
     * （<5% 视为没看、>95% 视为看完），不在 UI 里重复实现。
     *
     * ⚠️ 必须渲染在**根 Box 内部**（与 SnackbarHost 同级），
     * 否则它不会浮在页面之上 —— 与评论输入浮层踩过的是同一个坑。
     */

    // 弹幕按播放进度懒加载（每 6 分钟一片）。
    // 跟随播放进度轮询，进到新分片时自动拉取。
    //
    // ⚠️ 只在**播放中**轮询。原先无条件每 3 秒唤醒一次，
    // 即使没起播/已暂停也在轮询，纯属空转（省电 + 减少主线程调度）。
    // 恢复播放会重建本 effect，所以不会漏掉新的分片。
    LaunchedEffect(player, playState, currentPage) {
        val p = player ?: return@LaunchedEffect
        if (playState !is PlayState.Ready) return@LaunchedEffect
        while (true) {
            if (!p.isPlaying) {
                kotlinx.coroutines.delay(DANMAKU_IDLE_POLL_MS)
                continue
            }
            val pos = runCatching { p.currentPosition }.getOrNull() ?: break
            viewModel.ensureDanmakuLoaded(pos)
            kotlinx.coroutines.delay(DANMAKU_LOAD_POLL_MS)
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    // 页面内一次性提示（发弹幕入口等）
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbar.showSnackbar(it)
            toastMessage = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgBase),
        ) {
            // ⚠️ **没有**顶部固定白栏（对照官方结构）。
            //
            // 原先这里有一条常驻的 52dp 白底「← 视频详情」栏，
            // 会：①占掉纵向空间 ②把播放器往下推 ③与官方观感不符。
            //
            // 现在播放器**直接顶到状态栏**，页面一进来就是画面。
            // 返回按钮改为浮在画面上（点画面才浮现，见 PlayerArea）。

            // ---- 玻璃作用域：**只覆盖压在视频上的表面** ----
            //
            // ⚠️ 这里**刻意不把整页包进去**。
            //
            // 第一版把整个详情页包进 `ProvideGlassBackdrop`，结果是：
            // 播放器**下方**的 UP 卡 / 互动栏 / 推荐卡也去糊视频帧 ——
            // 但那些卡片**根本不在视频上面**，物理上"底下没有视频"。
            // 表现是卡片里透出视频画面的"幽灵轮廓"（实测截图里能清楚
            // 看到人物的头肩形状），既脏又干扰阅读。
            //
            // 正确做法：只有**真正叠在视频画面上**的浮层才拿 backdrop ——
            // 即 `PlayerArea` 内部的返回键、右上角按钮组、控制条。
            // 播放器下方的卡片走静态页配方（比页面底亮一档）。
            //
            // 这也正是玻璃拟态的物理前提：**玻璃必须压在东西上面**。
            when (val s = state) {
                is DetailUiState.Loading -> ProvideShimmer { DetailSkeleton() }

                is DetailUiState.Error -> ErrorState(
                    title = "视频加载失败",
                    description = s.message,
                    onRetry = viewModel::load,
                    modifier = Modifier.fillMaxSize(),
                )

                is DetailUiState.Content -> DetailContent(
                    detail = s.detail,
                    related = s.related,
                    windowSize = windowSize,
                    playState = playState,
                    currentPage = currentPage,
                    player = player,
                    playerError = playerError,
                    interaction = interaction,
                    descExpanded = descExpanded,
                    isFullscreen = isFullscreen,
                    // 定位到指定评论（AI 查成分「在 APP 内查看」）
                    focusCommentRpid = focusCommentRpid,
                    onFocusCommentHandled = viewModel::consumeFocusComment,
                    activeSubtitle = activeSubtitle,
                    danmaku = danmaku,
                    videoshot = videoshot,
                onPositionTick = { sec -> currentPositionSeconds = sec },
                    danmakuEnabled = danmakuEnabled,
                    danmakuAlpha = danmakuAlpha,
                    danmakuFontScale = danmakuFontScale,
                    danmakuArea = danmakuArea,
                    danmakuBlockModes = settings.danmakuBlockModes,
                    danmakuBlockKeywords = settings.danmakuBlockKeywords,
                    onToggleDanmaku = viewModel::toggleDanmaku,
                    onDanmakuAlpha = viewModel::setDanmakuAlpha,
                    onDanmakuFontScale = viewModel::setDanmakuFontScale,
                    onDanmakuArea = viewModel::setDanmakuArea,
                    comments = comments,
                    commentTotal = commentTotal,
                    commentLoading = commentLoading,
                    commentLoadingMore = commentLoadingMore,
                    commentHasMore = commentHasMore,
                    commentError = commentError,
                    onLoadMoreComments = viewModel::loadMoreComments,
                    onLikeComment = viewModel::likeComment,
                    onDeleteComment = viewModel::deleteComment,
                    onReplyComment = { c ->
                        replyTarget = c
                        showCommentInput = true
                    },
                    // 评论排序：mode 3=热度 2=时间（此前硬编码 3、无切换入口）
                    commentSort = commentSort,
                    onSortChange = viewModel::setCommentSort,
                    onReportComment = viewModel::reportComment,
                    reportReasons = viewModel.reportReasons,
                    // 评论头像 → 用户主页（此前完全不可点）
                    onAvatarClick = { mid ->
                        if (mid > 0) onOwnerClick(mid)
                    },
                    // 「查看全部 N 条回复」→ **就地加载下一页**（v1.5.1，不再跳独立页）
                        onLoadMoreReplies = viewModel::loadMoreReplies,
                        replyLoading = replyLoading,
                        repliesExhausted = viewModel::repliesExhausted,                    isLoggedIn = viewModel.isLoggedIn,
                    onStartPlay = handleStartPlay,
                    onToggleFullscreen = { isFullscreen = !isFullscreen },
                    onOpenSettings = { showSettings = true },
                    onEnterPip = onEnterPip,
                    isInPip = isInPip,
                    onToggleAudioOnly = onToggleAudioOnly,
                    onOpenVinyl = onOpenVinyl,
                    playbackMode = playbackMode,
                    onBack = onBack,
                    holder = holder,
                    onToggleDesc = viewModel::toggleDesc,
                    commentTabSelected = commentTabSelected,
                    onSelectCommentTab = { commentTabSelected = it },
                    // 发弹幕：与弹幕开关/播放器状态联动（见 DanmakuInputSheet）
                    onSendDanmaku = { showDanmakuInput = true },
                    onSelectPage = viewModel::selectPage,
                    chapters = chapters,
                    currentPositionSeconds = currentPositionSeconds,
                    // 空降助手：可跳过区间画到进度条上（v1.6.3）
                    skipSegments = skipSegments,
                    // AI 总结入口（v1.6.3）。未注入仓库时为 null → 不渲染入口。
                    onOpenSummary = if (viewModel.aiSummaryAvailable) {
                        {
                            showSummary = true
                            viewModel.loadSummary()
                        }
                    } else {
                        null
                    },
                    onJumpChapter = { index -> viewModel.jumpToChapter(index, player) },
                    onRetryPlay = {
                        playerError = null
                        viewModel.retryPlay()
                    },
                    onPlayerError = { msg -> playerError = msg },
                    onLike = {
                        if (viewModel.isLoggedIn) viewModel.toggleLike() else onLoginRequired()
                    },
                    // 投币：先弹确认框，用户在框里选份数才真正执行
                    onCoin = {
                        if (viewModel.isLoggedIn) showCoinDialog = true else onLoginRequired()
                    },
                    onFavorite = {
                        if (viewModel.isLoggedIn) viewModel.toggleFavorite() else onLoginRequired()
                    },
                    onShare = {
                        // 🔴 改为打开**应用内分享面板**（v1.4.2 修 #14）。
                        //
                        // 首版直接 `createChooser()` 弹系统分享 ——
                        // 问题是：
                        // 1. 系统面板样式与本 App 完全不同，是明显的"跳出感"
                        // 2. 无法提供"复制链接"这种不需要离开 App 的轻量操作
                        // 3. 无法在面板里标注第三方渠道（微信等需 SDK，我们不集成）
                        //
                        // 现在复用 `ItemMoreMenu`（⋮ 菜单用的同一个面板），
                        // 它已含「微信 / 朋友圈 / 下载分享 / 复制链接」四个渠道，
                        // 其中「复制链接」**不离开 App**，其余渠道再转系统分享。
                        // 这样两条入口（互动栏 + ⋮ 菜单）行为一致。
                        showMoreMenu = true
                    },
                    onVideoClick = onVideoClick,
                    // 头像 / 名字 → UP 主主页（此前完全不可点）
                    onOwnerClick = { mid ->
                        if (mid > 0) onOwnerClick(mid)
                    },
                    // 未登录拦截：评论区与简介区的所有写操作都走这里。
                    // 此前 DetailContent 内部拿不到这个回调，评论模式下
                    // 「回复 / 点赞 / 举报」在未登录时只能静默失败（问题 9）。
                    onLoginRequired = onLoginRequired,
                    // ⋮ → 下载 / 稍后再看 / 分享渠道
                    onMoreClick = { showMoreMenu = true },
                    // v1.3.0：把当前视频登记进应用级队列 ——
                    // 否则黑胶/听视频页读不到 currentItem，
                    // 会显示「暂无播放」而音频却在响（装机实测发现的缺口）。
                    onRegisterInQueue = { d ->
                        onRegisterInQueue(
                            d.bvid,
                            d.cid,
                            d.title,
                            d.ownerName,
                            d.coverUrl(),
                        )
                    },
                    // 🔴 取流成功后把 PlayInfo 回填给 controller（v1.4.2 修）。
                    // 不传的话 `setMode()` 会因 `info == null` 跳过重建，
                    // 表现为「进黑胶后时间归零、返回变点击播放」。
                    onPlayInfoReady = onPlayInfoReady,
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Space.x12),
        )

        // ---- 续播提示条 ----
        if (resumePositionMs > 0L && playState is PlayState.Ready) {
            ResumeBar(
                positionMs = resumePositionMs,
                onResume = {
                    runCatching { player?.seekTo(resumePositionMs) }
                    onResumeConsumed()
                },
                onDismiss = { onResumeConsumed() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = Space.x12, start = Space.x3, end = Space.x3),
            )
        }

        // ---- 评论输入浮层 ----
        //
        // ⚠️ 必须放在**根 Box 内部**（与 SnackbarHost 同级），
        // 否则它不会覆盖在页面之上。
        //
        // ⚠️ 而且它**不再是 Dialog**（原实现是 Dialog，已改）：
        // Dialog 是独立 window，配合 `decorFitsSystemWindows = false`
        // 导致键盘避让彻底失效（四次尝试全部无效，详见 CommentInputSheet 的说明）。
        // 现在它是页面内的全屏浮层，`imePadding()` 正常生效。
        //
        // 与评论区的「回复」入口联动：replyTarget 非空时标题显示"回复 @某人"，
        // 并把 root/parent 一起带给 ViewModel（决定挂在哪一层楼）。
        if (showCommentInput) {
            CommentInputSheet(
                replyTo = replyTarget,
                onDismiss = {
                    showCommentInput = false
                    replyTarget = null
                },
                onSend = { text ->
                    val target = replyTarget
                    viewModel.postComment(
                        message = text,
                        // 回复主评论时 root == parent
                        root = target?.rpid ?: 0L,
                        parent = target?.rpid ?: 0L,
                    )
                    showCommentInput = false
                    replyTarget = null
                },
            )
        }

        // ---- 弹幕输入浮层 ----
        //
        // 与评论输入浮层同理：放在根 Box 内，且不再是 Dialog
        // （Dialog 独立 window 会让键盘避让失效）。
        if (showDanmakuInput) {
            DanmakuInputSheet(
                onDismiss = { showDanmakuInput = false },
                onSend = { text, color, mode ->
                    // 弹幕时间取当前播放进度 —— 与播放器状态联动，
                    // 保证它出现在用户正在看的位置。
                    val pos = runCatching { player?.currentPosition ?: 0L }
                        .getOrDefault(0L)
                    viewModel.sendDanmaku(text, color, mode, pos)
                    showDanmakuInput = false
                },
            )
        }

        // ---- 更多菜单（⋮）：下载 / 稍后再看 / 分享渠道 ----
        //
        // ⚠️ 这是「下载」与「稍后再看」唯一的 UI 入口。
        // 此前两个功能底层都完整实现（`VideoDownloader` 315 行、
        // `LibraryRepository.addToView()`），却没有任何页面调用它们。
        //
        // 放在根 Box 内、与其它浮层同级 —— 保证它覆盖在页面之上。
        if (showMoreMenu) {
            val cur = state as? DetailUiState.Content
            val detail = cur?.detail
            val readyInfo = (playState as? PlayState.Ready)?.info

            com.example.biliv3.ui.component.ItemMoreMenu(
                title = detail?.title ?: "视频",
                isFavorited = interaction.favored,
                onDismiss = { showMoreMenu = false },
                onShareChannel = { channel ->
                    showMoreMenu = false
                    // 🔴 v1.5.3：**按渠道真正分发**（原实现所有渠道走同一个
                    // `createChooser`，等于渠道名是装饰 —— 选"微信"和选
                    // "复制链接"之外的三项，行为完全一样）。
                    //
                    // ## 分层策略（由"能不能做到"决定，不是想当然）
                    //
                    // | 渠道 | 做法 | 为什么 |
                    // |---|---|---|
                    // | 复制链接 | 直接写剪贴板 | 不离开 App，最轻 |
                    // | B站好友 | 打开本 App 的私信 → 选人 → 粘贴 | **站内分享是真实能力**，不需要任何第三方 SDK |
                    // | 微信/朋友圈/QQ | `ACTION_SEND` + **指定包名** | 装了就直接进对应 App，没装则降级到系统面板 |
                    // | 小红书 | 尝试包名，**失败降级** | 小红书对 `ACTION_SEND` 支持不稳定，必须容错 |
                    //
                    // ⚠️ **不伪造 SDK 集成**：微信/QQ 的"分享到朋友圈""分享给好友"
                    // 若要精确到那种程度需要官方 SDK + AppID（要注册开发者）。
                    // 这里用 `setPackage` 直达目标 App，**能做到的部分就做到**，
                    // 做不到的部分（如朋友圈直接发图）明确不假装。
                    val shareUrl = "https://www.bilibili.com/video/$bvid"
                    val shareTitle = (state as? DetailUiState.Content)?.detail?.title.orEmpty()
                    when (channel) {
                        "复制链接" -> {
                            copyToClipboard(context, shareUrl)
                        }

                        // ---- B站好友：走**站内私信**，不离开 App ----
                        "B站好友" -> {
                            // 进私信列表，由用户自己选人。
                            // 链接已复制好，用户粘贴即可 —— 这是没有
                            // "选好友"弹窗（需要好友列表接口）时最诚实的做法。
                            copyToClipboard(context, shareUrl)
                            onOpenMessages()
                        }

                        else -> {
                            // 其余渠道：按包名直达，失败则降级系统面板
                            val target = shareChannelPackage(channel)
                            val sent = if (target != null) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_SEND,
                                        ).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, shareUrl)
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, shareTitle)
                                            setPackage(target)
                                        },
                                    )
                                }.isSuccess
                            } else {
                                false
                            }
                            // 直达失败（未安装 / 不支持该 intent）→ 系统面板兜底
                            if (!sent) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent.createChooser(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_SEND,
                                            ).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    android.content.Intent.EXTRA_TEXT,
                                                    shareUrl,
                                                )
                                            },
                                            "分享到",
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    viewModel.onShared()
                },
                onToggleFavorite = {
                    showMoreMenu = false
                    if (viewModel.isLoggedIn) viewModel.toggleFavorite() else onLoginRequired()
                },
                extraActions = buildList {
                    // ---- 缓存（离线下载）----
                    if (detail != null && downloadEntry != null) {
                        val downloaded = downloadEntry.downloaded.collectAsStateWithLifecycle().value
                        add(
                            com.example.biliv3.ui.component.MoreMenuAction(
                                label = if (downloaded) "已缓存（查看缓存）" else "缓存到本地",
                                icon = androidx.compose.material.icons.Icons.Outlined.Download,
                                onClick = {
                                    if (downloaded) {
                                        showMoreMenu = false
                                        onOpenDownloads()
                                    } else if (readyInfo == null) {
                                        // ⚠️ 没取过流就没法下载 —— 直说，
                                        // 而不是发一个注定失败的请求
                                        showMoreMenu = false
                                        toastMessage = "请先点击封面开始播放，再缓存"
                                    } else {
                                        showMoreMenu = false
                                        val page = detail.pages.getOrNull(currentPage)
                                        downloadEntry.download(
                                            info = readyInfo,
                                            bvid = detail.bvid,
                                            cid = page?.cid ?: detail.cid,
                                            aid = detail.aid,
                                            title = detail.title,
                                            cover = detail.cover,
                                            authorName = detail.ownerName,
                                            pageIndex = currentPage,
                                            pageLabel = if (detail.isMultiPart) {
                                                "P${currentPage + 1}"
                                            } else {
                                                ""
                                            },
                                            durationSeconds = detail.durationSeconds,
                                        )
                                    }
                                },
                            ),
                        )
                    }

                    // ---- 稍后再看 ----
                    if (detail != null) {
                        add(
                            com.example.biliv3.ui.component.MoreMenuAction(
                                label = if (inToView) "已在稍后再看" else "加入稍后再看",
                                icon = androidx.compose.material.icons.Icons.Outlined.Schedule,
                                onClick = {
                                    showMoreMenu = false
                                    if (!viewModel.isLoggedIn) {
                                        onLoginRequired()
                                    } else if (inToView) {
                                        toastMessage = "已在稍后再看列表里"
                                    } else {
                                        onAddToView(detail.aid)
                                    }
                                },
                            ),
                        )
                    }
                },
            )
        }
    }

    // ---- 播放设置弹层（字幕 / AI 翻译、清晰度、倍速）----
    //
    // ⚠️ 打开条件**不能要求 ready != null**。
    //
    // 之前写的是 `showSettings && ready != null && activePlayer != null`，
    // 于是"还没点播放"或"播放失败"时弹层根本打不开 ——
    // 而 AI 字幕正是最需要在这些状态下也能选的。
    //
    // 现在：只要 showSettings 就打开。清晰度/倍速区在无取流信息时
    // 自动不渲染（由 PlayerSettingsSheet 内部判断），字幕区始终可用。
    val ready = playState as? PlayState.Ready
    val activePlayer = player
    if (showSettings) {
        PlayerSettingsSheet(
            info = ready?.info,
            player = activePlayer,
            subtitleTracks = subtitleTracks,
            activeSubtitle = activeSubtitle,
            subtitleLoading = subtitleLoading,
            isLoggedIn = viewModel.isLoggedIn,
            onSelectQuality = {
                viewModel.selectQuality(it)
                showSettings = false
            },
            onSelectSubtitle = { track ->
                viewModel.selectSubtitle(track)
            },
            danmakuEnabled = danmakuEnabled,
            danmakuAlpha = danmakuAlpha,
            danmakuFontScale = danmakuFontScale,
            danmakuArea = danmakuArea,
            // ⚠️ 每个回调都做两件事：改本次播放的 ViewModel 状态 + 写回全局设置。
            //
            // 只改 ViewModel 的话，播放器里调完去设置页看还是旧值（未联动）；
            // 只写设置的话，本次播放不会立即生效（要重进页面）。
            // 两边都写，才是真正的"联动"。
            onToggleDanmaku = {
                viewModel.toggleDanmaku()
                onDanmakuSettingsChanged(
                    !danmakuEnabled, danmakuAlpha, danmakuFontScale, danmakuArea,
                )
            },
            onDanmakuAlpha = { v ->
                viewModel.setDanmakuAlpha(v)
                onDanmakuSettingsChanged(danmakuEnabled, v, danmakuFontScale, danmakuArea)
            },
            onDanmakuFontScale = { v ->
                viewModel.setDanmakuFontScale(v)
                onDanmakuSettingsChanged(danmakuEnabled, danmakuAlpha, v, danmakuArea)
            },
            onDanmakuArea = { v ->
                viewModel.setDanmakuArea(v)
                onDanmakuSettingsChanged(danmakuEnabled, danmakuAlpha, danmakuFontScale, v)
            },
            onLoginRequired = {
                showSettings = false
                onLoginRequired()
            },
            onDismiss = { showSettings = false },
        )
    }

    // ---- 投币确认弹窗 ----
    //
    // ⚠️ 必须在**真正投币之前**拦一道。硬币投出不可撤销，
    // 而互动栏里投币与点赞/收藏挤在一行，误触代价很高。
    if (showCoinDialog) {
        CoinDialog(
            coinBalance = coinBalance,
            onDismiss = { showCoinDialog = false },
            onConfirm = { count, alsoLike ->
                showCoinDialog = false
                viewModel.coin(count, alsoLike)
            },
        )
    }

    // ---- 字幕错误提示 ----
    LaunchedEffect(subtitleError) {
        subtitleError?.let {
            snackbar.showSnackbar(it)
            viewModel.clearSubtitleError()
        }
    }

    // ---- AI 总结弹层（v1.6.3）----
    //
    // 与投币弹窗一样用 Dialog（独立 window，自带返回拦截）。
    // 放在根 Box 之后渲染，保证它覆盖在页面之上。
    if (showSummary) {
        val summaryState by viewModel.summaryState.collectAsStateWithLifecycle()
        AiSummarySheet(
            state = summaryState,
            onDismiss = {
                showSummary = false
                // 只清 UI 状态，**保留缓存** —— 下次打开立即出结果
                viewModel.resetSummaryState()
            },
            onRetry = { viewModel.loadSummary() },
            onOpenSettings = {
                showSummary = false
                viewModel.resetSummaryState()
                onOpenAiSettings()
            },
        )
    }
}

/**
 * 播放器在**非全屏**状态下使用的宽高比。
 *
 * ## 为什么按断点区分
 *
 * | 场景 | 比例 | 理由 |
 * |---|---|---|
 * | 移动（竖屏） | **4:3** | 16:9 只占约 25% 屏高，画面太小；4:3 让画面明显变大 |
 * | 平板 / 桌面 | 16:9 | 宽屏下 16:9 才是视频的正确比例，放大反而留黑边过多 |
 *
 * ## 为什么不是"直接用视频真实比例"
 *
 * 视频真实比例要等取流后才拿到，而**未起播时就要显示封面**。
 * 若封面用 16:9、起播后跳成 4:3，会出现明显的高度跳变。
 * 用固定比例保证"未播/在播"布局一致。
 *
 * 真实画面由 `PlayerView` 的 `RESIZE_MODE_FIT` 负责按原比例缩放，
 * 所以即使容器是 4:3、视频是 16:9，画面也**不会被拉伸变形**，
 * 只会在容器内上下留黑边（画面本身比 16:9 容器大得多）。
 */
/**
 * 播放器容器比例。
 *
 * ## ⚠️ 优先级：真实视频比例 > 断点回退值（这是「比例不正确」的根因）
 *
 * 此前**无条件**按断点取固定值（竖屏 4:3 / 宽屏 16:9），完全忽略
 * `PlayInfo.width/height` —— 而接口本来就给了真实分辨率。
 * 后果：
 * - 16:9 的视频塞进 4:3 容器 → 上下留大量黑边（画面显得又小又偏）
 * - 竖版（9:16）视频塞进 4:3 容器 → 左右留黑边，画面极小
 * - 全屏时容器变成 `fillMaxSize`，但播放器仍按 FIT 缩放，
 *   在非 16:9 屏幕上就会出现"没铺满、有黑边"的观感
 *
 * 现在：起播后**用真实比例**；未起播（只有封面）时才回退到断点值 ——
 * 封面按固定比例显示，避免"封面 16:9 → 起播变 4:3"的高度跳变。
 *
 * @param playInfo 已取流的信息；未起播时为 null
 */
internal fun playerAspectRatio(
    windowSize: WindowSize,
    playInfo: PlayInfo?,
): Float {
    // 真实比例优先（宽高都有效才采用，避免脏数据把画面压成一条线）
    val w = playInfo?.width ?: 0
    val h = playInfo?.height ?: 0
    if (w > 0 && h > 0) {
        val r = w.toFloat() / h.toFloat()
        // 兜底：极端比例（如接口脏数据 1×9999）会让容器塌掉或撑爆，
        // 限制在合理区间内，超出则视为不可信、回退断点值。
        if (r.isFinite() && r in MIN_PLAYER_ASPECT..MAX_PLAYER_ASPECT) {
            // 🔴 v1.6.2「播放区域扩大」：
            //   横屏视频（r >= 1）→ 容器**最多** 4:3。
            //
            //   ⚠️ 注意方向：`aspectRatio = 宽/高`，所以**值越小容器越高**。
            //      16:9 = 1.78 → 高 = 0.56×宽（1080 宽屏只有 608px 高）
            //      4:3  = 1.33 → 高 = 0.75×宽（810px 高）
            //   要"放大"就必须取**较小**的值 → `minOf`，不是 `maxOf`。
            //   （第一版写成 `maxOf`，被单测 `横屏视频的容器至少 4 比 3` 抓到。）
            //
            //   画面本身由 PlayerView 的 `RESIZE_MODE_FIT` 按原比例缩放，
            //   **不会变形**，只是上下黑边更宽 —— 观感上就是"播放器更大"。
            //   竖版视频（r < 1）→ 保持真实比例，本来就高，再放大顶掉整屏。
            return if (r >= 1f) minOf(r, PLAYER_ASPECT_PORTRAIT) else r
        }
    }
    return when (windowSize) {
        WindowSize.Mobile -> PLAYER_ASPECT_PORTRAIT
        WindowSize.Tablet, WindowSize.Desktop -> PLAYER_ASPECT_WIDE
    }
}

/** 可信比例下限（约 1:3 的竖版）。 */
private const val MIN_PLAYER_ASPECT = 0.33f

/** 可信比例上限（约 3:1 的超宽）。 */
private const val MAX_PLAYER_ASPECT = 3.0f

/**
 * 竖屏播放器比例 4:3。
 *
 * 取值的取舍：比 16:9 高很多（画面面积增大 ~78%），
 * 又不像 1:1 那样在横屏视频上留过多黑边。
 */
private const val PLAYER_ASPECT_PORTRAIT = 4f / 3f

/** 宽屏播放器比例 16:9（视频标准比例）。 */
private const val PLAYER_ASPECT_WIDE = 16f / 9f

/**
 * 元信息小项：图标 + 文字。
 *
 * ## 为什么图标与文字用 `Space.x1` 的小间距
 *
 * 图标与文字属于**同一个语义单元**（"这是播放量"），
 * 间距要明显小于项与项之间（`Space.x3`）——
 * 靠间距的**对比**建立分组，比加分隔线更轻。
 */
/**
 * 详情页里**所有卡片**统一的横向内缩量。
 *
 * ## ⚠️ 为什么必须抽成常量（一个真实的视觉不一致 bug）
 *
 * 此前各卡片各自写内缩，结果只有一部分内缩了：
 *
 * | 卡片 | 修复前 | 结果 |
 * |---|---|---|
 * | UP 信息 / 选集 / 视频简介 | `Space.x3` | 内缩 ✅ |
 * | 工具条（简介·评论切换） | **无** | 通栏贴边 ❌ |
 * | 互动栏（点赞/投币/收藏/分享） | **无** | 通栏贴边 ❌ |
 *
 * 同一屏里两种卡片宽度 —— 用户看到的就是
 * 「为什么点赞那里的框没收，其他区域却收缩了」。
 *
 * 抽成常量后，新增卡片只要用 [CARD_INSET] 就自动对齐，
 * 不会再出现"漏写一处"（漏写时是通栏，视觉上很明显但不报错）。
 */
private val CARD_INSET = Space.x3

@Composable
private fun MetaItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    val colors = BiliTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(Sizes.iconSm + 2.dp),
        )
        Spacer(Modifier.width(Space.x1))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = colors.textSecondary,
            ),
            maxLines = 1,
        )
    }
}

/**
 * 浮动返回按钮（叠在视频画面左上角）。
 *
 * ## 为什么不再是固定顶栏
 *
 * 原先顶部有一条常驻白底「← 视频详情」栏。参考官方结构后改为：
 * **播放器顶到状态栏，没有任何固定栏** —— 页面一进来就是画面。
 *
 * 返回入口改为浮在画面上的圆形按钮，**与播放控件同步显隐**：
 * 默认隐藏 → 点画面浮现 → 再点/3 秒后淡出。
 *
 * ## 为什么必须留返回入口（不能真的全隐藏）
 *
 * 虽然"默认隐藏"是需求，但若连返回都完全不可见，
 * 用户会以为"进了个没有出口的页面"。所以：
 * - 它随点击浮现（发现路径清晰：点一下画面）
 * - 系统返回手势**始终可用**（不依赖这个按钮）
 *
 * ## 视觉
 *
 * 半透明黑圆底 + 白色箭头 —— 与齿轮/小窗按钮同一套语言，
 * 保证压在任意亮度的画面上都清晰可辨。
 *
 * ## ⚠️ 尺寸必须与右上角按钮组一致（用户反馈"退出键过大"）
 *
 * 此前这里是 `Space.minTouchTarget`（**48dp**），而右上角那组是
 * **36dp** —— 左上角比右上角大一圈，
 * 在一屏"默认纯画面"的播放器上非常抢眼。
 *
 * 现在视觉尺寸统一为 [PLAYER_CHROME_BUTTON]（36dp），
 * 但**触摸目标仍保证 ≥48dp**：外层 Box 撑出 48dp 命中区、
 * 内层画 36dp 的可见圆 —— 直接给可见圆钮设 48dp 会让它视觉变大，
 * 那正是这次要修的问题。
 */
/**
 * 播放器左上角返回键。
 *
 * ## ⚠️ 为什么**不能用 `AnimatedVisibility` 控制可点性**（修过的一个真 bug）
 *
 * 此前实现是 `AnimatedVisibility(visible = chromeVisible) { ...clickable... }`。
 * `AnimatedVisibility(visible = false)` 会把整棵子树**移出组合**（不只是变透明），
 * 于是：
 *
 * - 默认进页面 `chromeVisible = false` → **返回键根本不存在**，点左上角是点空气
 * - 自动隐藏 3 秒后再次消失 → 又要先点一下画面才能点返回
 *
 * 用户感受就是「退出键时灵时不灵」—— 其实是「有时压根没有这个按钮」。
 *
 * ## 现在的做法：**常驻可点，只让视觉淡出**
 *
 * 用 `alpha` 控制可见度，组件**始终在组合里、始终可点**。
 * 淡出到 0 时用户看不见它，但左上角依然是有效退出区 ——
 * 这与 YouTube / 官方客户端的肌肉记忆一致（左上角永远是退出）。
 *
 * ⚠️ 不要改回 `AnimatedVisibility`。若确实要"不可见时也不可点"，
 * 必须先想清楚：那时用户要靠什么退出？
 */
@Composable
private fun FloatingBackButton(
    onClick: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    // 视觉淡入淡出，但组件不离开组合 → 命中区始终存在
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(Motion.PAGE_FADE_MS, easing = Motion.standard),
        label = "backButtonAlpha",
    )

    Box(
        modifier = modifier
            .statusBarsPadding()
            .padding(start = Space.x2, top = Space.x2)
            .size(PLAYER_CHROME_TOUCH)
            // 🔴 v1.5.1：**去掉默认指示**（用户反馈"点左上角出现白色方框"）。
            //
            // 根因：`Modifier.clickable(onClick)` 默认开 `LocalIndication`
            // 与 `LocalFocusManager` 的焦点高亮。在播放器这种**全屏深色浮层**
            // 上，焦点框表现为一个**白色矩形描边**（indication 画的是矩形，
            // 不是圆形）—— 与圆钮外形不一致，非常突兀。
            //
            // 为什么会出现焦点态：电视 / 键鼠 / 部分 ROM 的可访问性导航
            // 会把焦点落到第一个可点控件上，触摸前就已经"选中"了它。
            //
            // ⚠️ 修法是**关掉视觉指示**，不是关掉可点性 ——
            // `onClick`、无障碍语义（`contentDescription`）全部保留，
            // 盲人读屏仍能识别并激活这个按钮（§任务书："保留合理的无障碍语义"）。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(PLAYER_CHROME_BUTTON)
                .alpha(alpha)
                .clip(CircleShape)
                .background(colors.overlayControl),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = colors.onOverlay,
                modifier = Modifier.size(Sizes.iconMd),
            )
        }
    }
}


@Composable
private fun DetailContent(
    detail: VideoDetail,
    related: List<VideoItem>,
    /** 断点。决定播放器宽高比（竖屏放大画面）。 */
    windowSize: WindowSize,
    playState: PlayState,
    currentPage: Int,
    player: ExoPlayer?,
    playerError: String?,
    interaction: com.example.biliv3.data.api.InteractionState,
    descExpanded: Boolean,
    isFullscreen: Boolean,
    /**
     * 要定位的评论 rpid（null = 不定位）。
     *
     * 来自「AI 查成分 → 在 APP 内查看」：只打开视频不够，
     * 用户还得自己在几百条评论里翻。
     */
    focusCommentRpid: String?,
    /** 定位完成（滚动 + 高亮）后回调，上层据此清空定位目标。 */
    onFocusCommentHandled: () -> Unit,
    activeSubtitle: com.example.biliv3.data.subtitle.SubtitleBody?,
    danmaku: List<com.example.biliv3.data.danmaku.DanmakuItem>,
    /** 进度条拖动预览精灵图（v1.5.3）。null = 该视频没有预览资源。 */
    videoshot: com.example.biliv3.data.Videoshot? = null,
    /** 整秒位置上报（v1.5.3）。透传给 PlayerArea 用于章节高亮。 */
    onPositionTick: (Int) -> Unit = {},
    danmakuEnabled: Boolean,
    danmakuAlpha: Float,
    danmakuFontScale: Float,
    danmakuArea: Float,
    /** 本地屏蔽：弹幕类型（1=滚动 4=底部 5=顶部）。 */
    danmakuBlockModes: Set<Int>,
    /** 本地屏蔽：关键词。 */
    danmakuBlockKeywords: List<String>,
    onToggleDanmaku: () -> Unit,
    onDanmakuAlpha: (Float) -> Unit,
    onDanmakuFontScale: (Float) -> Unit,
    onDanmakuArea: (Float) -> Unit,
    comments: List<com.example.biliv3.data.model.CommentItem>,
    commentTotal: Int,
    commentLoading: Boolean,
    commentLoadingMore: Boolean,
    commentHasMore: Boolean,
    /** 首屏评论加载失败原因（null = 没失败）。用于区分错误态与空态。 */
    commentError: String?,
    onLoadMoreComments: () -> Unit,
    /** 评论点赞 / 删除 / 回复。 */
    onLikeComment: (com.example.biliv3.data.model.CommentItem) -> Unit,
    onDeleteComment: (com.example.biliv3.data.model.CommentItem) -> Unit,
    onReplyComment: (com.example.biliv3.data.model.CommentItem) -> Unit,
    /** 评论排序（3=热度 2=时间）。 */
    commentSort: Int,
    onSortChange: (Int) -> Unit,
    /** 举报评论：目标 + 理由编号。 */
    onReportComment: (com.example.biliv3.data.model.CommentItem, Int) -> Unit,
    /** 可选的举报理由。 */
    reportReasons: List<Pair<Int, String>>,
    /** 点评论者头像 → 用户主页。 */
    onAvatarClick: (Long) -> Unit,
    /** 「查看全部 N 条回复」→ 楼中楼详情页。 */
    onLoadMoreReplies: (Long) -> Unit,
    replyLoading: Set<Long>,
    repliesExhausted: (Long) -> Boolean,
    /** 是否已登录（评论/弹幕的写操作需要）。 */
    isLoggedIn: Boolean,
    onStartPlay: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 请求进入 PiP 小窗。 */
    onEnterPip: () -> Boolean,
    /** 是否已在 PiP 中（PiP 下隐藏设置齿轮等）。 */
    isInPip: Boolean,
    /** v1.3.0：切换听视频模式。 */
    onToggleAudioOnly: () -> Unit,
    /** v1.3.0：打开黑胶唱片模式。 */
    onOpenVinyl: () -> Unit,
    /** v1.3.0：当前播放模式（决定是否装配视频轨）。 */
    playbackMode: com.example.biliv3.player.PlaybackMode,
    /** Activity 级播放器持有者（装配去重）。 */
    holder: com.example.biliv3.player.PlayerHolder?,
    /** 返回。传给 PlayerArea 的浮动返回按钮。 */
    onBack: () -> Unit,
    onToggleDesc: () -> Unit,
    /** 左栏视图切换（true=评论，false=简介）。 */
    commentTabSelected: Boolean,
    onSelectCommentTab: (Boolean) -> Unit,
    /** 右栏：发弹幕入口。 */
    onSendDanmaku: () -> Unit,
    onSelectPage: (Int) -> Unit,
    /** 章节（空降助手，v1.5.3）。**空列表是常态** —— 实测 60 个视频全无章节。 */
    chapters: List<com.example.biliv3.data.VideoChapter> = emptyList(),
    /** 当前播放位置（秒）。用于高亮"正在哪一章"。 */
    currentPositionSeconds: Int = 0,
    /**
     * 空降助手：可跳过片段（v1.6.3）—— 画在进度条轨道上。
     *
     * ⚠️ 传的是 `viewModel.skipSegments`（**已按设置里的类别过滤、已合并**）。
     * 不要在这里再过滤一次，否则会出现"设置里关了 intro，
     * 进度条上却还有 intro 色块"的双重真相。
     */
    skipSegments: List<com.example.biliv3.data.SkipSegment> = emptyList(),
    /** 点击章节跳转。 */
    onJumpChapter: (Int) -> Unit = {},
    /**
     * AI 总结入口（v1.6.3）。`null` = 该功能不可用，不渲染入口。
     *
     * ⚠️ 必须可空 —— 传一个空 lambda 会让按钮看起来能点但没反应
     * （§1.6 死入口）。渲染侧据 `null` 判断是否显示。
     */
    onOpenSummary: (() -> Unit)? = null,
    onRetryPlay: () -> Unit,
    onPlayerError: (String) -> Unit,
    onLike: () -> Unit,
    onCoin: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onVideoClick: (String) -> Unit,
    /** 点 UP 头像/名字 → 用户主页。 */
    onOwnerClick: (Long) -> Unit,
    /**
     * 未登录拦截：评论区的回复 / 点赞 / 举报在未登录时走这里，
     * 由上层导航到登录页并给提示。
     */
    onLoginRequired: () -> Unit = {},
    /** 点元信息行的 ⋮ → 更多菜单（下载 / 稍后再看 / 分享渠道）。 */
    onMoreClick: () -> Unit,
    /**
     * v1.3.0：把当前视频登记进播放队列。
     *
     * ⚠️ 详情页**不持有**队列（那是 `PlaybackController` 的），
     * 只在这里把"当前在播什么"告诉它 —— 否则黑胶/听视频页
     * 读不到 `currentItem`，会显示「暂无播放」。
     */
    onRegisterInQueue: (com.example.biliv3.data.model.VideoDetail) -> Unit = {},
    /**
     * 取流成功后回填 PlayInfo（v1.4.2 修）。
     *
     * 与 [onRegisterInQueue] 是**两件事**：那个登记"播的是什么"（队列项），
     * 这个缓存"怎么播"（URL）。`setMode()` 重建 MediaSource 只认后者。
     */
    onPlayInfoReady: (com.example.biliv3.data.model.PlayInfo, Long) -> Unit =
        { _, _ -> },
) {
    val colors = BiliTheme.colors

    // 🔴 取流成功 → 把 PlayInfo 回填给 controller（v1.4.2 修）。
    //
    // 必须在这里做：`setMode()` 重建 MediaSource 时读的是 controller 里
    // 缓存的 PlayInfo；详情页不回填，controller 就永远是 null，
    // 于是切黑胶时 `if (info != null)` 判空失败 → 跳过重建 →
    // 播放器被释放且没重建（实测：进黑胶时间归零、返回变「点击播放」）。
    //
    // key 用 `playState`：分 P 切换 / 清晰度切换都会产生新的 PlayState.Ready，
    // 每次都要把最新的 URL 告诉 controller。
    androidx.compose.runtime.LaunchedEffect(playState) {
        if (playState is PlayState.Ready) {
            onPlayInfoReady(playState.info, detail.aid)
        }
    }

    // v1.3.0：把当前视频登记进播放队列。
    //
    // ## 为什么必须在这里做
    //
    // 队列是**应用级**的（`PlaybackController.queue`），但详情页此前
    // 完全不碰它 —— 于是黑胶/听视频页读到的 `currentItem` 是 null，
    // 表现为「暂无播放」而音频却在响（装机实测发现的缺口）。
    //
    // 登记时机：详情加载完成（有标题/作者/封面）后**覆盖式**写入单曲队列。
    // 用 `setSingle` 语义而不是 append：详情页是"从某处点进来的一个视频"，
    // 不该把之前的队列越堆越长。
    androidx.compose.runtime.LaunchedEffect(detail.bvid, detail.cid) {
        onRegisterInQueue(detail)
    }

    if (isFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.playerBackground),
        ) {
            PlayerArea(
                playState = playState,
                player = player,
                playerError = playerError,
                coverUrl = detail.coverUrl(),
                isFullscreen = true,
                activeSubtitle = activeSubtitle,
                danmaku = danmaku,
                videoshot = videoshot,
                onPositionTick = onPositionTick,
                danmakuEnabled = danmakuEnabled,
                danmakuAlpha = danmakuAlpha,
                danmakuFontScale = danmakuFontScale,
                danmakuArea = danmakuArea,
                danmakuBlockModes = danmakuBlockModes,
                danmakuBlockKeywords = danmakuBlockKeywords,
                onStartPlay = onStartPlay,
                onToggleFullscreen = onToggleFullscreen,
                onOpenSettings = onOpenSettings,
                onEnterPip = onEnterPip,
                isInPip = isInPip,
                // ⚠️ 全屏下浮动返回键必须是「退全屏」，不是「退页面」。
                //
                // 此前这里传的是 `onBack`，于是全屏时点左上角返回键
                // **直接退出整个视频页**，而按系统返回键只是退全屏
                // （BackHandler 处理）—— 两个入口行为不一致，
                // 用户感受就是"退出时灵时不灵、行为还不同"。
                //
                // 现在两者统一：先退全屏，留在视频页；再按一次才退页面。
                onBack = onToggleFullscreen,
                onRetryPlay = onRetryPlay,
                onPlayerError = onPlayerError,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    // ================= 评论模式：独立布局 =================
    //
    // ⚠️ 为什么必须**跳出外层 LazyColumn**（这是一个真实的渲染 bug）
    //
    // 评论列表自己是 `LazyColumn`（要支持无限滚动）。
    // 若把它当成外层 LazyColumn 的一个 item，它就落在
    // **无限高度约束**下 —— 内层 LazyColumn 拿不到确定高度，
    // 会塌成 0 高，表现为"点了评论一片空白"。
    //
    // 修法：评论模式下用 `Column` + `weight(1f)` 给评论区一个
    // **确定的剩余高度**，播放器与工具条固定在上方。
    // 这样内层滚动有界、无限滚动也才有正确的"触底"判定。
    if (commentTabSelected) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // C 方案：评论模式下，播放器 + 工具条 + 评论区之间留出
                // 页面底色的间隙，让三块各自成为独立卡片。
                .background(colors.bgBase),
            // 与简介模式用同一套间距节奏（问题 11）：
            // 手写的 `Spacer(height = Space.x2)` 容易漏、且与 LazyColumn
            // 分支不一致。统一用 arrangement 表达"卡片间距"。
            verticalArrangement = Arrangement.spacedBy(Space.x2),
        ) {
            // ---- 播放器（固定在顶部，不随评论滚动）----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 真实视频比例优先（见 playerAspectRatio 的说明）
                    .aspectRatio(
                        playerAspectRatio(
                            windowSize,
                            (playState as? PlayState.Ready)?.info,
                        ),
                    )
                    .background(colors.playerBackground),
            ) {
                PlayerArea(
                    playState = playState,
                    player = player,
                    playerError = playerError,
                    coverUrl = detail.coverUrl(),
                    isFullscreen = false,
                    activeSubtitle = activeSubtitle,
                    danmaku = danmaku,
                    videoshot = videoshot,
                onPositionTick = onPositionTick,
                    danmakuEnabled = danmakuEnabled,
                    danmakuAlpha = danmakuAlpha,
                    danmakuFontScale = danmakuFontScale,
                    danmakuArea = danmakuArea,
                    danmakuBlockModes = danmakuBlockModes,
                    danmakuBlockKeywords = danmakuBlockKeywords,
                    onStartPlay = onStartPlay,
                    onToggleFullscreen = onToggleFullscreen,
                    onOpenSettings = onOpenSettings,
                    onEnterPip = onEnterPip,
                    isInPip = isInPip,
                    onToggleAudioOnly = onToggleAudioOnly,
                    onOpenVinyl = onOpenVinyl,
                    playbackMode = playbackMode,
                    holder = holder,
                    onBack = onBack,
                    onRetryPlay = onRetryPlay,
                    onPlayerError = onPlayerError,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ---- 工具条（固定）----
            VideoToolRow(
                commentTabSelected = commentTabSelected,
                commentCount = commentTotal,
                onSelectTab = onSelectCommentTab,
                danmakuEnabled = danmakuEnabled,
                onToggleDanmaku = onToggleDanmaku,
                onSendDanmaku = onSendDanmaku,
                onOpenSummary = onOpenSummary,
                modifier = Modifier.padding(horizontal = CARD_INSET),
            )

            // ---- 评论占满剩余高度（weight 给它确定高度）----
            //
            // ⚠️ 这里必须与下面 LazyColumn 分支传**同一套回调**。
            //
            // 此前这条分支漏传了三个：
            // - `onAvatarClick`  → 评论模式点头像完全没反应（问题 7）
            // - `onLoginRequired`→ 未登录点回复/点赞/举报静默无提示（问题 9）
            // - `onViewAllReplies`→「查看全部 N 条回复」是死入口
            //
            // 而 `commentTabSelected` 默认是 true，也就是说**默认视图**走的
            // 正是这条漏传的分支 —— 问题被放大到"每次进视频都命中"。
            // 现在两条分支参数对齐，任何一条都不再是"功能残缺版"。
            CommentSection(
                comments = comments,
                total = commentTotal,
                loading = commentLoading,
                loadingMore = commentLoadingMore,
                hasMore = commentHasMore,
                isLoggedIn = isLoggedIn,
                // 错误态优先于空态：失败不能显示成「还没有评论」
                error = commentError,
                onRetry = onLoadMoreComments,
                onLoadMore = onLoadMoreComments,
                onLike = onLikeComment,
                onDelete = onDeleteComment,
                onReply = onReplyComment,
                sortMode = commentSort,
                onSortChange = onSortChange,
                onReport = onReportComment,
                reportReasons = reportReasons,
                onAvatarClick = onAvatarClick,
                onLoadMoreReplies = onLoadMoreReplies,
                        replyLoading = replyLoading,
                        repliesExhausted = repliesExhausted,
                // 定位到指定评论（AI 查成分「在 APP 内查看」）
                focusRpid = focusCommentRpid,
                onFocusHandled = onFocusCommentHandled,
                onLoginRequired = onLoginRequired,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Space.x3),
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = Space.x8),
        // ⚠️ 卡片之间必须有稳定间距（问题 11 的根因）。
        //
        // 此前所有 item 紧贴在一起，而每个 item 自己都是一张
        // 带描边/投影的卡片 —— 视觉上糊成一整块"卡片墙"，
        // 用户的反馈是"点开视频后整体 UI 很突出、很抢眼"。
        //
        // 用 `verticalArrangement` 统一给间距，而不是在每个 item 里
        // 手写 Spacer：后者必然会漏几处（此前就漏了 tool-row 与
        // owner-meta 之间），且改一次要动多处。
        verticalArrangement = Arrangement.spacedBy(Space.x2),
        modifier = Modifier.fillMaxSize(),
    ) {
        // ---- 播放器 / 封面（16:9）----
        item(key = "player") {
            // ---- 播放器 / 封面 ----
            //
            // ## ⚠️ 为什么竖屏用**更高的比例**而不是固定 16:9（用户反馈"画面太小"）
            //
            // 固定 16:9 在 1080×2400（20:9）手机上只占约 **25%** 屏高，
            // 上方无内容、下方全是详情，观感就是"视频挤在顶上一条"。
            //
            // 官方在竖屏下也不是死守 16:9 —— 播放器会占据更接近
            // "半屏以上"的区域，让画面成为主角。
            //
            // 这里按屏幕宽高比动态取：**竖屏取 4:3**（画面明显更大，
            // 且横向视频用 FIT 缩放时会上下留黑边但整体更大），
            // 横屏/桌面仍用 16:9（宽屏下 16:9 才是正确比例）。
            //
            // 用 `aspectRatio` 而不是固定高度：不同机型宽度不同，
            // 固定高度会在小屏上过高、大屏上过矮。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 真实视频比例优先（见 playerAspectRatio 的说明）
                    .aspectRatio(
                        playerAspectRatio(
                            windowSize,
                            (playState as? PlayState.Ready)?.info,
                        ),
                    )
                    .background(colors.playerBackground),
            ) {
                PlayerArea(
                    playState = playState,
                    player = player,
                    playerError = playerError,
                    coverUrl = detail.coverUrl(),
                    isFullscreen = false,
                    activeSubtitle = activeSubtitle,
                    danmaku = danmaku,
                    videoshot = videoshot,
                onPositionTick = onPositionTick,
                    danmakuEnabled = danmakuEnabled,
                    danmakuAlpha = danmakuAlpha,
                    danmakuFontScale = danmakuFontScale,
                    danmakuArea = danmakuArea,
                    danmakuBlockModes = danmakuBlockModes,
                    danmakuBlockKeywords = danmakuBlockKeywords,
                    onStartPlay = onStartPlay,
                    onToggleFullscreen = onToggleFullscreen,
                    onOpenSettings = onOpenSettings,
                    onEnterPip = onEnterPip,
                    isInPip = isInPip,
                    onToggleAudioOnly = onToggleAudioOnly,
                    onOpenVinyl = onOpenVinyl,
                    playbackMode = playbackMode,
                    holder = holder,
                    onRetryPlay = onRetryPlay,
                    onPlayerError = onPlayerError,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---- 左栏切换控件 + 右栏发弹幕（播放器正下方）----
        //
        // 对照官方结构：左边是"看什么"（简介/评论切换），
        // 右边是"我要说什么"（发弹幕 + 开关）。
        item(key = "tool-row") {
            VideoToolRow(
                commentTabSelected = commentTabSelected,
                commentCount = commentTotal,
                onSelectTab = onSelectCommentTab,
                danmakuEnabled = danmakuEnabled,
                onToggleDanmaku = onToggleDanmaku,
                onSendDanmaku = onSendDanmaku,
                onOpenSummary = onOpenSummary,
                // ⚠️ 必须与其它卡片用同一个横向内缩量（见下方 CARD_INSET 说明）。
                // 此前这里没有 padding，于是工具条是**通栏贴边**的，
                // 而紧邻的 UP 信息卡是内缩的 —— 两张卡左右边缘不齐，
                // 视觉上像"有一块没做完"。
                modifier = Modifier.padding(horizontal = CARD_INSET),
            )
        }

        // ================= 视图分支（需求：切到评论时只显示评论）=================
        //
        // ⚠️ 切到「评论」后**只保留评论区**：UP 信息、互动栏、分P、
        // 相关推荐全部不渲染。
        //
        // 为什么：评论是"沉浸式阅读"场景。上方压着 UP 信息与互动栏
        // 会挤掉大量可视区（手机竖屏尤其明显），用户明确要求专注评论。
        //
        // 切回「简介」时这些内容**自然恢复** —— 它们只是条件分支，
        // 不涉及状态重建（数据仍在 ViewModel 里）。
        if (!commentTabSelected) {
            // ================= UP 信息区（垂直排列）=================
            //
            // 严格按用户要求的顺序自上而下：
            //   头像 → 名字（头像右侧）→ 粉丝数·视频数 → 标题（右侧挂简介倒V）
            //   → 播放量·发布时间·在看人数 → 互动按钮
            item(key = "owner-meta") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 🔴 乙·质感：**不再是卡片**。
                        //
                        // 上一版是 `biliCard()` —— 圆角 + 描边 + 左右内缩
                        // （`CARD_INSET`），把"这个视频是什么"整块装进一个盒子。
                        //
                        // 现在：**左右贴到屏幕边**（去掉 CARD_INSET），
                        // 靠上下间距与内容自身分组。详情页的正文区
                        // 本来就该通栏 —— 它是页面主体，不是页面里的一张卡。
                        //
                        // ## ⚠️ 间距只给上边，不给下边（这是系统性规则）
                        //
                        // 上一版上下都给了 `Rhythm.between`，而下一个区块
                        // 上边**也**给了 `Rhythm.between` → 两块之间实际是
                        // **28 + 28 = 56dp**，视觉上是一条突兀的空白带
                        // （实测截图里互动栏与分P之间就是这个问题）。
                        //
                        // **规则：间距只由「下方那个区块的上边」提供。**
                        // 这样任意两块之间恒为一份间距，不会因为"两边都加"
                        // 而翻倍。全站统一遵守。
                        .padding(
                            start = Space.x4,
                            end = Space.x4,
                            top = Rhythm.between,
                        ),
                ) {
                    // ---- ① 头像 + ② 名字 / ③ 粉丝数·视频数 ----
                    //
                    // ⚠️ 头像与名字**都可点**，进 UP 主主页。
                    // 此前这里没有任何点击 —— 详情页的 UP 头像是全应用
                    // 最显眼的"看着能点其实不能点"的元素之一。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = detail.ownerFaceUrl(96),
                            contentDescription = "进入 UP 主主页",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(OWNER_AVATAR)
                                .clip(CircleShape)
                                .background(colors.avatarPlaceholder)
                                .clickable { onOwnerClick(detail.ownerMid) },
                        )
                        Spacer(Modifier.width(Space.x3))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radius.badge))
                                .clickable { onOwnerClick(detail.ownerMid) },
                        ) {
                            // ② 名字
                            Text(
                                text = detail.ownerName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = FontSize.body,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(Space.micro))
                            // ③ 粉丝数 · 视频数
                            //
                            // ⚠️ 两项都是"增强信息"，取不到时**不显示该项**
                            // 而不是显示 0 —— 显示"0 个视频"是**错误信息**
                            // （UP 主明明有很多视频），比不显示更糟。
                            //
                            // 实测：粉丝数来自 `x/relation/stat`（可用）；
                            // 视频数需要 WBI 签名的 `space/wbi/arc/search`，
                            // 当前未接入 —— 所以只显示粉丝数。
                            val meta = buildString {
                                if (detail.ownerFans > 0) {
                                    append(formatCount(detail.ownerFans)).append(" 粉丝")
                                }
                                if (detail.ownerVideoCount > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append(detail.ownerVideoCount).append(" 个视频")
                                }
                            }
                            if (meta.isNotEmpty()) {
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = FontSize.label,
                                        color = colors.textSecondarySafe,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(Space.x3))

                    // ---- ④ 标题 + 最右侧的简介倒 V ----
                    //
                    // ⚠️ 字重从 SemiBold 降到 Medium（问题 11）。
                    //
                    // 详情页是"看视频"的页面，视频画面才是主角；
                    // 标题用 SemiBold + titleMd 在 1080p 屏上非常"砸眼"，
                    // 与下方一堆卡片叠加后整体观感过于浓重。
                    // Medium 仍足以建立层级（标题比正文大且更亮），
                    // 但不再抢画面。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = detail.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = FontSize.titleMd,
                                lineHeight = FontSize.titleMdLine,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // 简介入口：只在有简介时可点（没有简介则按钮无意义）
                        if (detail.desc.isNotBlank()) {
                            Spacer(Modifier.width(Space.x1))
                            DescToggleButton(
                                expanded = descExpanded,
                                onClick = onToggleDesc,
                            )
                        }
                    }

                    // ---- 简介正文（默认隐藏，点倒 V 才展开）----
                    if (descExpanded && detail.desc.isNotBlank()) {
                        Spacer(Modifier.height(Space.x2))
                        Text(
                            text = detail.desc,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = FontSize.bodySm,
                                lineHeight = FontSize.bodySmLine,
                                color = colors.textSecondarySafe,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                // 简介块**不是交互元素** —— 它只是可读的文本区，
                                // 按 §5.1 硬规则 2 一律直角。
                                // （原来用 `Radius.button`(12dp)，那是"卡片"的语言）
                                .background(colors.bgHover)
                                .padding(Space.x3),
                        )
                    }

                    Spacer(Modifier.height(Space.x2))

                    // ---- ⑤ 播放量 · 弹幕 · 发布时间 · 在看人数（带图标）----
                    //
                    // ⚠️ 加图标的理由（用户要求"信息更直观"）：
                    //
                    // 原先是一串纯文字「287.5万 播放 · 4.7万 弹幕 · 3天前 · 156 人在看」，
                    // 四段信息挤在同一个字号里，用户要逐字读才知道哪段是什么。
                    //
                    // 加上图标后每段有了**视觉锚点**：扫一眼就知道
                    // "这排是数据"，不需要读文字。图标也承担了分隔作用，
                    // 可以省掉中间的 `·`，横向更省空间。
                    //
                    // 图标统一用 `Outlined` 描边风格 + `iconSm` 尺寸 ——
                    // 与 App 其它地方的线性图标一致，不会显得突兀。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.x3),
                    ) {
                        // 播放量
                        MetaItem(
                            icon = Icons.Outlined.PlayCircleOutline,
                            text = formatCount(detail.viewCount),
                        )
                        // 弹幕数
                        if (detail.danmakuCount > 0) {
                            MetaItem(
                                icon = Icons.Outlined.ChatBubbleOutline,
                                text = formatCount(detail.danmakuCount),
                            )
                        }
                        // 发布时间
                        val timeLabel = formatRelativeTime(detail.publishedAt)
                        if (timeLabel.isNotEmpty()) {
                            MetaItem(
                                icon = Icons.Outlined.Schedule,
                                text = timeLabel,
                            )
                        }
                        // 在看人数。
                        //
                        // 🔴 v1.5.1：**只在 ≥2 人时才显示**。
                        //
                        // 实测（真实账号，脚本直连）：`x/player/v2` 的
                        // `online_count` 对冷门视频**恒返回 1** —— 那 1 个人
                        // 就是当前观看者自己。原实现 `> 0` 就把"1 人在看"
                        // 显示出来，用户看到的是一条**永远不变的无意义数字**，
                        // 观感上等同"数据坏了"。
                        //
                        // 判据：这个数字只有在**能说明"还有别人在看"**时才有信息量。
                        // 1 = 只有自己 → 不显示；≥2 → 显示。
                        if (detail.viewers > 1) {
                            MetaItem(
                                icon = Icons.Outlined.RemoveRedEye,
                                text = "${formatCount(detail.viewers)} 人在看",
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        // ---- 更多（⋮）----
                        //
                        // ⚠️ 这个入口此前**完全不存在** —— 于是「下载」与
                        // 「稍后再看」两项功能虽然底层都写好了
                        // （`VideoDownloader` 315 行、`addToView()`），
                        // 却没有任何 UI 调用，用户完全够不到。
                        //
                        // 放在元信息行最右侧：它属于"对这个视频做点什么"，
                        // 与播放量/时间同一行不冲突（左信息右动作）。
                        Box(
                            modifier = Modifier
                                .size(Space.minTouchTarget)
                                .clip(CircleShape)
                                .clickable(onClick = onMoreClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多操作",
                                tint = colors.textSecondarySafe,
                                modifier = Modifier.size(Sizes.iconLg),
                            )
                        }
                    }

                    // ---- 互动栏（与 UP 信息同卡）----
                    //
                    // 放在卡片最底部：语义上"看完这个视频是什么 → 我能做什么"，
                    // 与上方信息是同一块内容的收尾。
                    //
                    // 上方用一条极淡的分隔线（而非再套一层卡）——
                    // 它只需要"分区"这一个作用，不需要自己的边界。
                    Spacer(Modifier.height(Space.x2))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.borderHairline),
                    )
                    InteractionBar(
                        interaction = interaction,
                        likeCount = detail.likeCount,
                        coinCount = detail.coinCount,
                        favoriteCount = detail.favoriteCount,
                        shareCount = detail.shareCount,
                        onLike = onLike,
                        onCoin = onCoin,
                        onFavorite = onFavorite,
                        onShare = onShare,
                        // ⚠️ 不再需要横向内缩 —— 它已在卡内。
                        // 之前每块各自 `padding(horizontal = CARD_INSET)`
                        // 是"三块并列"时代的产物，合并后这个内缩会变成
                        // 卡内多余的留白（卡片被挤窄）。
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---- 分P（仅多P）----
            if (detail.isMultiPart) {
                item(key = "pages") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 🔴 乙·质感：去卡片，改用**上边一条发丝线**分组。
                            //
                            // 分P 与上方内容语义不同（"这个视频有哪些分P"），
                            // 需要一条硬边界；但不需要一个盒子。
                            .ruleTop(color = Rule.color)
                            // ⚠️ 间距**只给上边**（v1.4.2 修）。
                            //
                            // 首版同时给了 top 与 bottom，注释却写着"只给上边" ——
                            // 注释与代码不一致，且实际效果是与下一个区块的
                            // `top = Rhythm.between` 叠成 **56dp** 空白带
                            // （28 + 28），比全站任何区块间距都大一倍。
                            //
                            // 规则见 `Surface.kt` 的 Rhythm 文档：
                            // 「间距只由下方区块的 top 提供，bottom 一律不加」。
                            .padding(
                                start = Space.x4,
                                end = Space.x4,
                                top = Rhythm.between,
                            ),
                    ) {
                        Text(
                            text = "选集（${detail.pages.size}）",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = FontSize.body,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            ),
                            modifier = Modifier.padding(horizontal = Space.x4),
                        )
                        Spacer(Modifier.height(Space.x2))
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = Space.x4),
                            horizontalArrangement = Arrangement.spacedBy(Space.x2),
                        ) {
                            items(detail.pages.size) { index ->
                                val page = detail.pages[index]
                                PageChip(
                                    label = "P${page.page}",
                                    selected = index == currentPage,
                                    onClick = { onSelectPage(index) },
                                )
                            }
                        }
                    }
                }
            }

            // ================= 章节（空降助手，v1.5.3）=================
            //
            // ## 为什么"没有章节"时**整块不渲染**
            //
            // 实测扫了排行榜 + 热门共 **60 个视频，`view_points` 全为空数组** ——
            // 章节是 UP 主投稿时**手动添加**的，属少数视频。
            // 若常驻显示「该视频没有章节」，等于给 99% 的视频挂一句
            // 无用的话（§1.1：不做无信息量的产出）。
            //
            // 所以：**有章节才渲染**；没有就整块不出现。
            // 这与"加载失败"是两件事 —— 失败也不会显示假章节。
            //
            // ⚠️ 必须是**独立的 `item`**，不能塞进上面 `item(key="pages")`
            // 的 `Column` 里 —— 那样它会落在那个 Column 的作用域内，
            // 而 `LazyRow`/`items` 只能在 `LazyListScope` 里调（编译期就会报
            // `@Composable invocations can only happen from the context of
            // a @Composable function`）。
            if (chapters.isNotEmpty()) {
                item(key = "chapters") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .ruleTop(color = Rule.color)
                            .padding(
                                start = Space.x4,
                                end = Space.x4,
                                top = Rhythm.between,
                            ),
                    ) {
                        Text(
                            text = "章节（${chapters.size}）",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = FontSize.body,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            ),
                            modifier = Modifier.padding(horizontal = Space.x4),
                        )
                        Spacer(Modifier.height(Space.x2))
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = Space.x4),
                            horizontalArrangement = Arrangement.spacedBy(Space.x2),
                        ) {
                            items(chapters.size) { index ->
                                val ch = chapters[index]
                                ChapterChip(
                                    timeLabel = ch.timeLabel,
                                    title = ch.title,
                                    // 当前播放位置落在本章 → 高亮（"我在哪一章"）
                                    active = currentPositionSeconds in
                                        ch.fromSeconds until ch.toSeconds,
                                    onClick = { onJumpChapter(index) },
                                )
                            }
                        }
                    }
                }
            }

            // ---- 相关推荐 ----
            //
            // ⚠️ 整块必须有 bgCard 背景。
            //
            // 之前这一段的标题与卡片**没有设置背景**，直接落在页面底色
            // bgBase（浅灰）上，而它上方的标题区/简介区/互动栏都是 bgCard（白）。
            // 结果就是详情页中间突兀地出现一条灰色色带，
            // 看起来像"这块没渲染好"——这就是「相关推荐颜色与主题不匹配」。
            // ---- 评论 / 完整简介（左栏内容，由顶部标签决定）----
            //
            // ⚠️ 位置：必须在「相关推荐」**之前**。
            //
            // 之前放在整个列表最后（相关推荐之后），于是点「评论」标签后
            // 评论确实渲染了、但在屏幕外 —— 用户要滚过全部相关推荐才看得到，
            // 表现就是"点了评论没反应"。
            //
            // 现在紧跟互动栏：点击标签 → 内容就在眼前。
            //
            // ⚠️ 这里**不再渲染简介**（这是「为什么有两个简介」的根因）。
            //
            // 此前这里有一个常驻的「视频简介」卡片，无条件显示 `detail.desc`；
            // 而标题右侧的倒 V 展开后也显示同一份 `detail.desc` ——
            // 于是同一个简介在页面上出现两次：
            //   ① 标题右侧倒 V 展开的
            //   ② 这张「视频简介」卡片
            // 用户看到的"两个简介"就是这么来的。
            //
            // 按已确认的交互约定，简介**只有一个入口**：
            // 位于标题最右侧、倒 V 图标、默认隐藏、点击才展开。
            // 因此删掉这张常驻卡片，简介统一由倒 V 控制。
            //
            // 为什么评论不能留在本 LazyColumn 里：评论列表自己是
            // `LazyColumn`（要无限滚动），嵌在外层 LazyColumn 的 item 里
            // 会落在**无限高度约束**下、塌成 0 高 —— 表现就是"点了评论一片空白"。
            // （评论模式已在函数开头 `return` 走独立布局，所以本分支
            //   只可能是「简介」态，这里无需再放任何简介内容。）

            if (related.isNotEmpty()) {
                item(key = "related-header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // C 方案：相关推荐标题不再单独铺一层 bgCard ——
                            // 下方每张卡片自带容器，标题只需与上方互动栏留出间距。
                            .padding(
                                start = Space.x4,
                                end = Space.x4,
                                top = Space.x5,
                                bottom = Space.x2,
                            ),
                    ) {
                        Text(
                            text = "相关推荐",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = FontSize.titleMd,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            ),
                        )
                    }
                }
                // ---- 相关推荐：两列网格 ----
                //
                // ⚠️ 首版是**单列全宽**：每张卡片铺满屏宽、封面 16:10
                // → 单卡高约 240dp，一屏只能看 2 张，且大封面挤压了
                // 标题与 UP 名的可读性。
                //
                // C 方案加了卡片容器后这个问题被放大（卡片边框让"巨卡"更明显）。
                // 改为两列：与首页推荐流一致，一屏可见 4 张。
                //
                // 用 chunked 手工分行而不是 LazyVerticalGrid ——
                // 外层已经是 LazyColumn，嵌套可滚动容器会导致
                // 内层拿到无限高度约束（这正是评论区的坑，见文件顶部说明）。
                items(
                    items = related.chunked(2),
                    key = { row -> row.joinToString("|") { it.bvid } },
                    contentType = { "related-row" },
                ) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.x3, vertical = Space.x1),
                        horizontalArrangement = Arrangement.spacedBy(Space.x2),
                    ) {
                        row.forEach { video ->
                            VideoCard(
                                video = video,
                                onClick = { onVideoClick(video.bvid) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 奇数个时补一个空位，避免最后一张被拉成整行宽
                        if (row.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

        }
    }
}

/**
 * 播放器顶部安全区（刘海 / 灵动岛 / 挖孔）。
 *
 * ## 为什么只有视频页需要
 *
 * 视频页是**唯一**全屏纯黑（播放器顶到状态栏）的页面，所以：
 * - 黑带与画面同色 → 视觉上是播放器的自然延伸，不突兀
 * - 状态栏图标在这里必须**浅色**（黑底 + 深色图标 = 看不见）
 *
 * 而首页/我的/搜索是浅色页面，凭空加黑带非常突兀
 * （试过全局加，结论是错的）。所以黑带只属于视频页。
 *
 * ## 两件事一起做
 *
 * 1. **画一条状态栏高度的黑带** —— 盖住"内容铺到状态栏底下"的那一段
 * 2. **把状态栏图标切成浅色** —— 否则黑底上图标隐形
 *
 * ⚠️ 高度取 `WindowInsets.statusBars` 的**实时值**，不写死：
 * 灵动岛机型比普通刘海更高，写死必然错位。
 *
 * ⚠️ 状态栏图标颜色是**全局窗口属性**，必须在离开页面时**还原**，
 * 否则会把"浅色图标"泄漏给后续的浅色页面（那里需要深色图标）。
 */
/**
 * 顶部安全区的**副作用部分**：把状态栏图标切成浅色。
 *
 * ## 🔴 v1.5.2 拆分说明（修"顶部安全区没生效"）
 *
 * 原来这个函数**同时**做两件事：
 * 1. 画一条状态栏高度的黑带
 * 2. 把状态栏图标切成浅色
 *
 * 第 1 件是错的 —— 它被调在播放器 `Box` **之外**（同级兄弟），
 * 而播放器画面后画 → **盖住黑带**。实测截图：状态栏图标直接压在画面上。
 *
 * 现在黑带移到 `PlayerArea` 的 `Box` **内部第一层**（最底），
 * 且播放器内容加 `statusBarsPadding()` 真正下移。
 * 本函数只保留第 2 件（全局窗口属性，与绘制位置无关）。
 *
 * ⚠️ 状态栏图标颜色是**全局窗口属性**，与"画在哪"无关，
 * 所以它留在页面级调用是对的。
 */
@Composable
private fun PlayerStatusBarTint() {
    val view = androidx.compose.ui.platform.LocalView.current

    // 深色主题下状态栏图标**本来就该是浅色**（`values-night/themes.xml`
    // 的 `windowLightStatusBar=false`），所以这里其实无需切换。
    //
    // ⚠️ 但**保留这行强制设置**，因为：
    // 系统可能是浅色主题（Android 会选 `values/themes.xml`，
    // 那份现在也写了 false，但第三方 ROM / 用户手动改过开发者选项时
    // 不保证）。这里显式压一次，确保黑底配浅色图标。
    //
    // 不再需要保存/还原 —— 全应用恒为深色，没有"另一种状态"可还原。
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view)
        }
        controller?.isAppearanceLightStatusBars = false
        onDispose { }
    }
}

/**
 * 播放器区域：封面 / 画面 + 控制层。
 *
 * ## 未起播时显示封面 + 播放按钮
 *
 * 这是「点击后才进入视频」的落点：用户看到的是封面大图 + 中央播放键，
 * 点一下才真正取流。比一进页面就自动播放更省流量、首屏也更快。
 */
@Composable
private fun PlayerArea(
    playState: PlayState,
    player: ExoPlayer?,
    playerError: String?,
    coverUrl: String,
    isFullscreen: Boolean,
    activeSubtitle: com.example.biliv3.data.subtitle.SubtitleBody?,
    danmaku: List<com.example.biliv3.data.danmaku.DanmakuItem>,
    /** 进度条拖动预览精灵图（v1.5.3）。null = 该视频没有预览资源。 */
    videoshot: com.example.biliv3.data.Videoshot? = null,
    /** 整秒位置上报（v1.5.3）。用于章节高亮。 */
    onPositionTick: (Int) -> Unit = {},
    danmakuEnabled: Boolean,
    danmakuAlpha: Float,
    danmakuFontScale: Float,
    danmakuArea: Float,
    /** 本地屏蔽：弹幕类型（1=滚动 4=底部 5=顶部）。 */
    danmakuBlockModes: Set<Int> = emptySet(),
    /** 本地屏蔽：关键词。 */
    danmakuBlockKeywords: List<String> = emptyList(),
    onStartPlay: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnterPip: () -> Boolean,
    isInPip: Boolean,
    /**
     * 切换「听视频」（v1.3.0）。
     *
     * 由 `PlaybackController.setMode(AUDIO/VIDEO)` 实现 ——
     * **切换时保持播放位置**（先记位置 → 重建 MediaSource → seek 回去）。
     */
    onToggleAudioOnly: () -> Unit = {},
    /**
     * 打开黑胶唱片模式（v1.3.0）。
     *
     * 进独立的全屏播放页（`Routes.PLAYER`），不是本页内的浮层 ——
     * 黑胶需要整屏空间，塞在播放器小窗里没有意义。
     */
    onOpenVinyl: () -> Unit = {},
    /**
     * v1.3.0：当前播放模式（看视频 / 听视频 / 黑胶）。
     *
     * ⚠️ 由导航层从 `container.playbackController.state.mode` 传入 ——
     * **不能在本页 `remember`**：模式是应用级的，
     * 页面重建（PiP 进出 / 转屏）后必须仍然生效。
     */
    playbackMode: com.example.biliv3.player.PlaybackMode =
        com.example.biliv3.player.PlaybackMode.VIDEO,
    /** Activity 级播放器持有者（装配去重）。 */
    holder: com.example.biliv3.player.PlayerHolder? = null,
    /** 返回。浮动返回按钮用它（默认隐藏、点画面才浮现）。 */
    onBack: () -> Unit = {},
    onRetryPlay: () -> Unit,
    onPlayerError: (String) -> Unit,
    /** 空降助手：是否启用自动跳过片段。 */
    sponsorBlockEnabled: Boolean = false,
    /** 空降助手：可跳过区间画到进度条上（v1.6.3）。 */
    skipSegments: List<com.example.biliv3.data.SkipSegment> = emptyList(),
    /** 判定当前进度该不该跳（含去重）。 */
    skipTargetFor: (Double) -> Double? = { null },
    /** 进度条拖动状态变化。 */
    onSeekingChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    /**
     * 控件与浮动返回按钮的**共享可见性**。
     *
     * 默认 false：页面一进来就是**纯画面**（无任何浮层），
     * 与官方结构一致。点一下画面才浮现返回按钮/控件，
     * 再点或播放中 3 秒后自动淡出。
     */
    var chromeVisible by remember { mutableStateOf(false) }

    /** 用户是否正在拖进度条（拖动中不跳片段）。 */
    var isUserSeeking by remember { mutableStateOf(false) }

    // 🔴 v1.5.2 修「顶部安全区没生效」。
    //
    // ## 根因：画在了错误的位置
    //
    // 原来 `PlayerSafeAreaTop()` 调在下面那个 `Box` **之外** ——
    // 它是同级的兄弟节点，而 `Box` 里的播放器画面会**盖在它上面**
    // （后画的在上）。实测截图：状态栏时间/信号/电池直接压在视频画面上，
    // 黑带完全看不见。
    //
    // ## 修法：安全区进 Box，且内容整体下移
    //
    // 只把黑带画在底层还不够 —— 视频画面仍会**顶到屏幕最上沿**，
    // 被状态栏图标压住。所以还要给内容加 `statusBarsPadding()`：
    // 安全区占住状态栏高度，画面从它下面开始。
    //
    // ⚠️ 这正是任务书要求的"根据实际 Window Insets 正确布局"，
    // 而不是"加固定 dp 的顶部 padding"。`statusBarsPadding()` 读的是
    // 系统真实 Insets（刘海/挖孔/手势条都算在内）。
    val statusBarTop = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()

    // 状态栏图标切浅色（全局窗口属性，与绘制位置无关）
    PlayerStatusBarTint()

    // ---- 玻璃作用域：**只覆盖播放器区域** ----
    //
    // 播放器区域内的浮层（返回键、右上角按钮组、底部控制条）是
    // **真正叠在视频画面上**的 —— 它们拿 backdrop 才有意义，
    // 糊的就是它们正下方的画面。
    //
    // ⚠️ 不能放大到整个页面。播放器**下方**的卡片（UP 信息 / 互动栏 /
    // 相关推荐）物理上不在视频上面，让它们糊视频帧会透出"幽灵轮廓"，
    // 既脏又干扰阅读 —— 实测截图里能清楚看到人物的头肩形状。
    //
    // 玻璃拟态的物理前提是：**玻璃必须压在东西上面**。
    com.example.biliv3.design.ProvideGlassBackdrop(backdrop = holder?.backdrop) {
    Box(modifier = modifier) {
        // ---- 顶部安全区（Box 内第一层 = 最底层）----
        //
        // 必须在播放器内容**之前**画，否则被画面盖住。
        // 用 `playerBackground`（纯黑）—— 与视频黑边一致，不产生色带。
        if (statusBarTop > 0.dp) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusBarTop)
                    .background(colors.playerBackground),
            )
        }

        when {
            playerError != null -> PlayerPlaceholder(
                message = playerError,
                onRetry = onRetryPlay,
                // 内容下移，避开状态栏
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            )

            playState is PlayState.Ready && player != null -> {
                VideoPlayerSurface(
                    info = playState.info,
                    player = player,
                    onError = onPlayerError,
                    // 交给 holder 判断是否需要重新装配：
                    // PiP 进出/页面重建时不重复 prepare，保住缓冲与进度
                    holder = holder,
                    // v1.3.0：听视频 / 黑胶时**不装配视频轨**（真省解码，
                    // 不是把画面藏起来 —— 见 §7.10-59）
                    mode = playbackMode,
                    // ⚠️ 加 `statusBarsPadding()` —— 画面从安全区下方开始，
                    // 不再被状态栏图标压住
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                )
                // 弹幕层：在画面之上、字幕之下（弹幕不遮挡字幕）
                DanmakuLayer(
                    player = player,
                    danmaku = danmaku,
                    enabled = danmakuEnabled,
                    alpha = danmakuAlpha,
                    fontScale = danmakuFontScale,
                    displayArea = danmakuArea,
                    // 本地屏蔽规则（设置页可改，改完立即作用于当前播放）
                    blockModes = danmakuBlockModes,
                    blockKeywords = danmakuBlockKeywords,
                    modifier = Modifier.fillMaxSize(),
                )
                // 字幕层叠在弹幕之上（字幕优先可读）
                SubtitleOverlay(
                    player = player,
                    body = activeSubtitle,
                    modifier = Modifier.fillMaxSize(),
                )
                PlayerControls(
                    player = player,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                    // 受控：与浮动返回按钮同步显隐
                    controlsVisible = chromeVisible,
                    // 拖动进度条时的画面预览（v1.5.3）
                    // null = 该视频没有预览资源 → 降级为只显示时间文字
                    videoshot = videoshot,
                onPositionTick = onPositionTick,
                    // 上报整秒位置 → 章节高亮（复用 PlayerControls 已有的轮询，
                    // 不再另起协程读同一个 player）
                    onToggleControls = { chromeVisible = !chromeVisible },
                    onAutoHide = { chromeVisible = false },
                    // 拖动中不跳片段（空降助手依赖）
                    onSeekingChanged = { seeking ->
                        isUserSeeking = seeking
                        onSeekingChanged(seeking)
                    },
                    // 空降助手：把可跳过区间画到轨道上（v1.6.3）。
                    // 拖动进度条时能直接看见哪几段会被自动跳过。
                    skipSegments = skipSegments,
                    modifier = Modifier.fillMaxSize(),
                )

                // 空降助手：按进度自动跳过片段（只调 seekTo，不参与布局）
                SponsorBlockSkipper(
                    player = player,
                    enabled = sponsorBlockEnabled,
                    skipTargetFor = skipTargetFor,
                    isUserSeeking = isUserSeeking,
                )
            }

            playState is PlayState.Loading -> {
                // 取流中仍显示封面，避免黑屏闪烁
                CoverWithPlayButton(coverUrl = coverUrl, onClick = null, loading = true)
            }

            playState is PlayState.Failed -> PlayerPlaceholder(
                message = playState.message,
                onRetry = onRetryPlay,
                modifier = Modifier.fillMaxSize(),
            )

            // Ready 但 player 尚未创建（理论上不会发生，兜底显示封面）
            // 以及 NotStarted：显示封面 + 播放键，等用户点击
            else -> CoverWithPlayButton(coverUrl = coverUrl, onClick = onStartPlay)
        }

        // ================= 左上角浮动返回按钮 =================
        //
        // 页面已去掉固定顶栏（对照官方：播放器顶到状态栏）。
        // 返回入口改为浮在画面上的圆钮，**与控件同步显隐**：
        // 默认隐藏 → 点画面浮现 → 再点/3 秒后淡出。
        //
        // 系统返回手势始终可用，不依赖这个按钮（所以"默认隐藏"不会
        // 让用户被困住）。
        if (!isInPip) {
            FloatingBackButton(
                onClick = onBack,
                visible = chromeVisible,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        // ================= 右上角按钮组（小窗 + 全屏 + 齿轮）=================
        //
        // ⚠️ 这是页面**唯一**的右上角控件组（问题 1 的根因之一）。
        //
        // 此前 `PlayerControls` 内部**也**画了一个 `align(TopEnd)` 的全屏按钮，
        // 与这里的按钮组叠在同一角落。后者的 composition 顺序更靠后，
        // 覆盖在上层、优先拿到命中测试 —— 于是"点全屏"有时点到、
        // 有时点到下面的齿轮/小窗，表现就是"时灵时不灵"。
        //
        // 现在全屏按钮收进本组，右上角只有一处，不再有重叠。
        //
        // 仍跟随 `chromeVisible`：默认纯画面，点一下三个一起出现。
        // PiP 下整组隐藏（小窗里点不中且挡画面）。
        if (!isInPip) {
            // 🔴 同样**不能用 `AnimatedVisibility`**（v1.4.1 修）。
            //
            // 与左上角返回键、中央播放键是**同一个坑**：
            // `visible = false` 会把整组按钮移出组合树 → 组件不存在 →
            // 想点齿轮时点击落在视频画面上（只唤出控件），
            // 用户必须先"点一下唤出"再"点第二下"，很别扭。
            //
            // 现在用 alpha 淡出：看不见但**命中区始终在**。
            // 隐藏状态下直接点齿轮位置即可打开设置。
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .alpha(
                        animateFloatAsState(
                            targetValue = if (chromeVisible) 1f else 0f,
                            animationSpec = tween(Motion.FADE_MS, easing = Motion.standard),
                            label = "chromeAlpha",
                        ).value,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 右上角按钮组：**无面板的幽灵图标**（v1.5.1 简化）。
                //
                // ## 历史（三次收窄）
                //
                // 1. 最初走 `biliCard()` 玻璃 → v1.4.1 发现玻璃糊边缘、降对比度，
                //    改成 `surfaceElevated` 实体面板 + 发丝描边
                // 2. v1.5.1 去掉面板底与描边，改独立圆钮；但**错把听视频与黑胶
                //    合并成一个入口并绑到 `onOpenSettings`**（v1.5.2 已修回归）
                // 3. v1.5.2（本轮）：用户要求"四个选项整体靠最右上角排列、
                //    小尺寸统一图标、触摸区仍足够"
                //
                // ## 现在（判据：画面是主体，控件是标点）
                //
                // - 无面板底、无描边 —— 不形成连续色块
                // - 圆钮视觉 28dp + 图标 14dp（`Sizes.iconSm`），
                //   触摸热区仍由 `PlayerChromeButton` 撑到 48dp
                // - 间距压到 `Space.x1`（4dp）—— "整体靠最右上角排列"
                // - **右对齐且紧贴右上角**：`Arrangement.End` + 最小外边距，
                //   而不是居中或留大片空白
                //
                // 触摸热区仍保证 48dp（见 `PlayerChromeButton`）。
                Row(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = Space.x1, vertical = Space.x1),
                    horizontalArrangement = Arrangement.spacedBy(Space.x1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                        // 小窗入口：只在可播放时显示（没画面时进 PiP 没意义）
                        if (playState is PlayState.Ready && player != null) {
                            PlayerChromeButton(
                                icon = Icons.Filled.PictureInPictureAlt,
                                contentDescription = "小窗播放",
                                onClick = { onEnterPip() },
                            )

                            // 🔴 v1.5.2 修回归：v1.5.1 我把这两个按钮**合并**成一个，
                            // 并把 onClick 错写成 `onOpenSettings` ——
                            // 于是用户点「听视频」打开的是**设置面板**，
                            // 听视频模式根本没进去（用户报告的正是这个）。
                            //
                            // 合并本身也是错的：听视频与黑胶是**两个不同的模式**，
                            // 共用一个图标必然二义。现在恢复为两个独立入口，
                            // 各自绑正确的回调（`onToggleAudioOnly` / `onOpenVinyl`）。
                            PlayerChromeButton(
                                icon = Icons.Filled.Headphones,
                                contentDescription = "听视频（只听不看）",
                                onClick = onToggleAudioOnly,
                            )
                            PlayerChromeButton(
                                icon = Icons.Filled.Album,
                                contentDescription = "黑胶唱片模式",
                                onClick = onOpenVinyl,
                            )
                        }

                        // 全屏 / 退出全屏（从 PlayerControls 收归到这里）
                        PlayerChromeButton(
                            icon = if (isFullscreen) {
                                Icons.Filled.FullscreenExit
                            } else {
                                Icons.Filled.Fullscreen
                            },
                            contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                            onClick = onToggleFullscreen,
                        )

                        PlayerChromeButton(
                            icon = Icons.Filled.Settings,
                            contentDescription = "播放设置与字幕",
                            onClick = onOpenSettings,
                        )
                }
            }
        }
    }
    }
}

/**
 * 播放器浮层圆钮（返回 / 小窗 / 全屏 / 齿轮共用）。
 *
 * ## 为什么要抽出来（用户反馈"退出键过大"的根因）
 *
 * 此前四个按钮**各写一份**，于是尺寸分叉：
 * 左上角返回用 `Space.minTouchTarget`（48dp），右上角三个用 36dp ——
 * 同一层浮层里两种圆钮大小，左上角明显大一圈，很抢眼。
 *
 * 现在尺寸/圆角/底色/图标规格全部收敛到这一处：
 * 视觉 [PLAYER_CHROME_BUTTON]（36dp），触摸目标 [PLAYER_CHROME_TOUCH]（48dp）——
 * 用"外层透明 Box 撑命中区、内层画可见圆"的写法，
 * 既满足无障碍最小点击区，又不会让圆钮视觉上变大。
 */
@Composable
private fun PlayerChromeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Box(
        modifier = Modifier
            .size(PLAYER_CHROME_TOUCH)
            // 同上：去掉默认指示（白色矩形焦点框 / 涟漪），保留可点性
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // ⚠️ v1.5.1：按钮**自己画一层轻量圆底**（因为外层面板已去掉）。
        //
        // 层级演变：
        // - v1.4.1 之前：外层玻璃面板 + 按钮不画底
        // - v1.4.1：外层实体面板 + 按钮不画底（怕"板上加更深的圆"）
        // - **v1.5.1：外层无面板 + 按钮自画半透明圆底**
        //
        // 为什么现在反过来：去掉面板后若按钮完全透明，图标在**亮画面**
        // （雪景 / 白底封面）上会看不清。给每颗按钮一层
        // `overlayControl`（约 50% 黑）刚好够辨认轮廓，
        // 又不会像面板那样连成一大块色块 —— 这正是"轻量克制"的判据。
        Box(
            modifier = Modifier
                .size(PLAYER_CHROME_BUTTON)
                .clip(CircleShape)
                .background(colors.overlayControl),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = colors.onOverlay,
                modifier = Modifier.size(Sizes.iconMd),
            )
        }
    }
}

/** 封面 + 中央播放按钮（未起播状态）。 */
@Composable
private fun CoverWithPlayButton(
    coverUrl: String,
    onClick: (() -> Unit)?,
    loading: Boolean = false,
) {
    val colors = BiliTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.playerBackground)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = coverUrl,
            contentDescription = "视频封面",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // 压暗，保证播放键与文字可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.overlayControl),
        )

        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                color = colors.onOverlay,
                strokeWidth = Space.trackHeight,
                modifier = Modifier.size(Sizes.iconXl + Sizes.iconMd),
            )
        } else if (onClick != null) {
            Box(
                modifier = Modifier
                    .size(PLAY_BUTTON)
                    .clip(CircleShape)
                    .background(colors.overlayControl),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = colors.onOverlay,
                    modifier = Modifier.size(Sizes.iconXl + Space.x2),
                )
            }
        }

        // 未起播时的提示
        if (onClick != null) {
            Text(
                text = "点击播放",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.onOverlay,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Space.x4),
            )
        }
    }
}

/** 分P / 清晰度的胶囊选项。 */
@Composable
internal fun PageChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.label,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.textOnBrand else colors.textSecondarySafe,
        ),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (selected) colors.brandPrimary else colors.bgHover)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.compactHorizontal),
    )
}

/**
 * 章节胶囊（空降助手，v1.5.3）。
 *
 * ## 与 [PageChip] 的区别
 *
 * `PageChip` 是**单选**（只能在一个分P），用品牌粉实底表示选中。
 * 章节是**定位**不是选择 —— 用户点它是"跳到那儿"，之后仍会随播放
 * 自然离开这一章。所以：
 *
 * - 用**描边 + 时间前缀**表达"当前所在章"，而不是整块实底
 * - 内容包含 `时间 + 标题`，因为章节的核心信息是**时间点**
 *
 * ## 形状
 *
 * 直角 + 左侧时间用等宽 —— 符合 §5.1「等宽字体读数用于时间轴」。
 */
@Composable
internal fun ChapterChip(
    timeLabel: String,
    title: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.interactive))
            .background(if (active) colors.brandPrimary.copy(alpha = 0.18f) else colors.bgHover)
            .border(
                width = if (active) 1.dp else 0.dp,
                color = if (active) colors.brandPrimary else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(Radius.interactive),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.compactHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.labelMedium.copy(
                // 等宽：时间读数用 Geek 字体族（§5.1 允许的极客点缀）
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = FontSize.badge,
                color = if (active) colors.brandPrimary else colors.textTertiary,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.width(Space.x2))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = if (active) colors.textPrimary else colors.textSecondarySafe,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
    }
}

/**
 * 分享渠道 → 目标 App 包名（v1.5.3）。
 *
 * ## 为什么要"包名直达"而不是只弹系统面板
 *
 * `createChooser` 的问题是**多一步**：用户点了"微信"，还要在系统面板里
 * 再找一次微信。`setPackage` 可以直达，体验与官方客户端一致。
 *
 * ## 为什么返回 null 而不是硬编码后失败
 *
 * `null` = **这个渠道没有明确的目标包名**（如"下载分享"），
 * 调用方据此直接走系统面板，不做无谓的 `startActivity` 尝试。
 *
 * ## ⚠️ 包名错了会怎样
 *
 * `setPackage` 指向未安装的包 → `ActivityNotFoundException` →
 * 调用方 `runCatching` 捕获 → **降级系统面板**。
 * 所以这里包名写错不会崩，只是失去"直达"这个优化。
 *
 * 包名取的是各 App **国内版**的主包名（国际版 `com.tencent.mm` 等另有变体，
 * 不在这里穷举 —— 未覆盖的会自然降级到系统面板）。
 */
private fun shareChannelPackage(channel: String): String? = when (channel) {
    "微信" -> "com.tencent.mm"
    "朋友圈" -> "com.tencent.mm"
    "QQ" -> "com.tencent.mobileqq"
    "QQ空间" -> "com.tencent.mobileqq"
    "微博" -> "com.sina.weibo"
    // 小红书：对 ACTION_SEND 的支持不稳定，尝试直达，失败会降级
    "小红书" -> "com.xingin.xhs"
    // 没有明确目标的（如"下载分享"）→ 走系统面板
    else -> null
}

/**
 * 详情页骨架：与真实布局同构，避免内容到达时跳动。
 */
@Composable
private fun DetailSkeleton() {
    val colors = BiliTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        SkeletonBox(
            modifier = Modifier.fillMaxWidth(),
            aspectRatio = 16f / 9f,
            shape = RoundedCornerShape(0.dp),
        )
        Column(modifier = Modifier.padding(Space.x4)) {
            SkeletonBox(Modifier.fillMaxWidth(), height = 16.dp)
            Spacer(Modifier.height(Space.x2))
            SkeletonBox(Modifier.fillMaxWidth(0.7f), height = 16.dp)
            Spacer(Modifier.height(Space.x3))
            SkeletonBox(Modifier.fillMaxWidth(0.4f), height = Space.x3)
        }
    }
}

/** UP 头像尺寸。比之前的 54dp 小，因为不再独占区域。 */
/**
 * UP 头像尺寸（新布局）。
 *
 * 48dp：比旧的 32dp 大 —— 新布局里头像与名字/粉丝数**垂直成组**，
 * 是信息区的主视觉锚点，太小会显得零碎。
 */
private val OWNER_AVATAR = 48.dp

private val UP_AVATAR = 32.dp

/** 弹幕分片拉取的轮询间隔（播放中）。 */
private const val DANMAKU_LOAD_POLL_MS = 3000L

/** 未播放时的轮询间隔。比播放中间隔大，避免空转。 */
private const val DANMAKU_IDLE_POLL_MS = 2000L

/**
 * 标题固定两行的高度。
 *
 * `titleMdLine` = 22sp，两行 = 44sp。用 dp 近似（22sp ≈ 22dp 在默认字号下），
 * 取 44dp 保证 1 行标题时也占满两行高度，消除不同视频间的布局抖动。
 */
private val TITLE_TWO_LINES = 44.dp

/**
 * 未起播时中央播放按钮。
 *
 * 56dp（原 64dp）：64dp 在一个 16:9 的播放器里视觉占比过大，
 * 用户反馈"播放区被按钮占了"。56dp 仍然一眼可见且好点。
 */
private val PLAY_BUTTON = 56.dp

/**
 * 播放器浮层按钮的**统一视觉尺寸**（返回 / 小窗 / 全屏 / 齿轮共用）。
 *
 * 32dp（原 36dp）：播放区要"更大更干净"，浮层元素整体收一档。
 * 32dp 是 Material 图标的标准档位，辨识度不受影响。
 *
 * 抽成常量而不是各自写死：此前返回键用 `Space.minTouchTarget`(48dp)、
 * 右上角那组用 36dp —— 同一层浮层里两种圆钮大小，
 * 左上角明显比右上角大一圈，用户反馈"退出键过大、太抢眼"。
 */
/**
 * 播放器浮层圆钮的**视觉尺寸**（v1.5.1：36 → 28dp）。
 *
 * 演变：48 → 36（v1.4.1）→ **28**（v1.5.1）。
 * 用户两次反馈"按钮过大、抢画面"，所以继续收：
 * 28dp 圆 + 14dp 图标，在 1080p 画面上是"小而精"的量级，
 * 触摸热区仍由 [PLAYER_CHROME_TOUCH] 保证 48dp。
 */
private val PLAYER_CHROME_BUTTON = 28.dp

/**
 * 播放器浮层按钮的**触摸目标**尺寸（≥48dp，满足无障碍最小点击区）。
 *
 * 视觉只有 [PLAYER_CHROME_BUTTON]，但命中区要够大 ——
 * 做法是外层透明 Box 撑到 48dp、内层画 32dp 的圆。
 * 直接给可见圆钮设 48dp 会让它视觉上变大（就是这次要修的问题）。
 */
private val PLAYER_CHROME_TOUCH = Space.minTouchTarget

/**
 * 续播提示条。
 *
 * ```
 * ┌────────────────────────────────────┐
 * │ 上次看到 12:34        继续   从头   │
 * └────────────────────────────────────┘
 * ```
 *
 * ## 为什么两个按钮都要有
 *
 * 只有"继续"会让想从头看的用户没有出口；
 * 只有"从头"则续播功能形同不存在。
 *
 * ## 为什么浮在顶部而不是塞进内容流
 *
 * 它是**瞬时提示**，不是常驻信息 —— 塞进 LazyColumn 会占掉
 * 一整块布局并在用户滚动后留在原位（语义错乱）。
 * 浮层点掉即消失，与官方行为一致。
 */
@Composable
private fun ResumeBar(
    positionMs: Long,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BiliTheme.colors
    val totalSeconds = (positionMs / 1000).toInt()
    val label = if (totalSeconds >= 3600) {
        "${totalSeconds / 3600}:" +
            "${(totalSeconds % 3600 / 60).toString().padStart(2, '0')}:" +
            "${(totalSeconds % 60).toString().padStart(2, '0')}"
    } else {
        "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 🔴 乙·质感：从"胶囊卡片"改成**直角浮条**。
            //
            // 这是压在播放器上的浮层（"上次看到 12:34 · 继续"），
            // 它需要从画面上"浮起来"，所以保留底色 —— 但**不再用胶囊**。
            //
            // 胶囊是"卡片"的语言；直角 + 半透明底是"浮层"的语言，
            // 与右上角按钮组、进度条的直角体系一致。
            .background(colors.overlayControl)
            .clickable(onClick = onResume)
            .padding(horizontal = Space.x4, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "上次看到 $label",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = colors.textPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "继续",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                fontWeight = FontWeight.SemiBold,
                color = colors.textBrandSafe,
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.badge))
                .clickable(onClick = onResume)
                .padding(horizontal = Space.x2, vertical = Space.x1),
        )
        Spacer(Modifier.width(Space.x2))
        Text(
            text = "从头",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = colors.textSecondarySafe,
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.badge))
                .clickable(onClick = onDismiss)
                .padding(horizontal = Space.x2, vertical = Space.x1),
        )
    }
}

/**
 * 复制文本到剪贴板（「复制链接」分享渠道用）。
 *
 * Android 13+ 系统会自动弹"已复制"提示，低版本没有 ——
 * 这里统一给一个 Toast，两版本行为一致。
 */
private fun copyToClipboard(context: android.content.Context, text: String) {
    runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("链接", text))
        android.widget.Toast
            .makeText(context, "链接已复制", android.widget.Toast.LENGTH_SHORT)
            .show()
    }.onFailure {
        android.widget.Toast
            .makeText(context, "复制失败", android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

/**
 * 预览用的占位 PlayerHolder。 *
 * 只在 Compose Preview（无 AppContainer）时生效 ——
 * 真正的实例必须由导航层注入，否则 PiP / 切页继续播会失效。
 */
@Composable
private fun rememberPreviewHolder(): com.example.biliv3.player.PlayerHolder {
    val ctx = LocalContext.current
    return remember { com.example.biliv3.player.PlayerHolder(ctx) }
}

/**
 * 把 ExoPlayer 的错误翻译成用户看得懂的文案。
 *
 * ExoPlayer 的原始错误（如 `ERROR_CODE_IO_BAD_HTTP_STATUS`）对用户毫无意义，
 * 但对定位问题极有价值，所以 logcat 里保留原始码，UI 上给中文原因。
 *
 * 非 private：竖屏模式（`VerticalScreen`）复用同一份映射，
 * 避免两处文案漂移。
 */
fun describePlayerError(error: androidx.media3.common.PlaybackException): String =
    when (error.errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "视频源返回错误（可能是取流地址已过期或需要登录）"

        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "网络连接失败，请检查网络后重试"

        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "视频源不存在或已被删除"

        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        -> "当前设备无法解码该视频（编码不兼容）"

        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> "视频流格式异常，无法解析"

        else -> "播放失败（${error.errorCodeName}）"
    }
