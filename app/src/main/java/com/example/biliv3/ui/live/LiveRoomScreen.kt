package com.example.biliv3.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.example.biliv3.data.LiveRoom
import com.example.biliv3.data.live.LiveMessage
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.component.TerminalLoadingState
import com.example.biliv3.ui.video.PlayerSurfaceBinding
import com.example.biliv3.ui.video.attachPlayerSurface
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * 直播间页（**应用内播放**，v1.6.3）。
 *
 * ## 从"跳浏览器"改成"应用内播放"
 *
 * 此前 `LiveScreen` 的点击行为是 `openExternalUrl(live.bilibili.com/{id})` ——
 * 理由是"缺 FLV/HLS 依赖，硬做会得到点进去黑屏"（见 `LiveViewModel` 的
 * 历史说明）。那个理由当时是成立的，但代价是**每次看直播都要跳出应用**。
 *
 * v1.6.3 补上了 `media3-exoplayer-hls` 依赖，实测 B 站直播确实返回
 * 可播的 HLS（`.m3u8`，`#EXTM3U` + `EXT-X-TARGETDURATION:3` + `.ts` 分片）
 * 与 FLV 地址，所以现在**真的能播了**，不再需要跳出。
 *
 * ## 复用点（没有新建第二套播放栈）
 *
 * | 复用 | 来源 |
 * |---|---|---|
 * | 播放器实例 | `PlayerHolder`（Activity 级单例，与视频详情页同一个） |
 * | 渲染 View | `holder` 的共享 `TextureView`（与详情页同一个） |
 * | 媒体源构建 | `PlayerFactory.buildLiveMediaSource` |
 * | 请求头 | `PlayerFactory.mediaDataSource()`（含必需的 Referer） |
 * | 加载/空/错误态 | `TerminalLoadingState` / `EmptyState` / `ErrorState` |
 *
 * ## 三态齐全（§1 自检第 7 项）
 *
 * - **加载中**：终端风加载态
 * - **未开播**：空态 + 「刷新」按钮（主播下播是正常状态，不是错误）
 * - **取流失败**：错误态 + 重试
 */
@Composable
fun LiveRoomScreen(
    room: LiveRoom,
    onBack: () -> Unit,
    /** 打开用户主页（聊天里点用户菜单 → 进入个人主页）。 */
    onOpenUser: (Long) -> Unit = {},
    /** 复制用户名（剪贴板操作在调用方做 —— 页面不碰 Context）。 */
    onCopyName: (String) -> Unit = {},
    /** 未登录时引导登录（弹幕输入条用）。 */
    onLoginRequired: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LiveRoomViewModel,
) {
    val colors = BiliV3.colors
    val stream by viewModel.stream.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val liveEnded by viewModel.liveEnded.collectAsStateWithLifecycle()
    val player = viewModel.player

    // 聊天（v1.6.4）
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connState by viewModel.connState.collectAsStateWithLifecycle()

    // 权限（数据层判定，UI 只消费）
    val perms by viewModel.permissions.collectAsStateWithLifecycle()
    val moderating by viewModel.moderating.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val pending by viewModel.pendingConfirm.collectAsStateWithLifecycle()

    // 弹层状态：用户菜单 / 禁言时长 / 烂梗库 / 发送者资料
    var menuTarget by remember { mutableStateOf<LiveMessage?>(null) }
    var muteTarget by remember { mutableStateOf<LiveMessage?>(null) }
    var showMemes by remember { mutableStateOf(false) }

    // 🔴 直播间**刻意不提供倍速**（v1.6.6 实测结论）
    //
    // 曾打算把 `SpeedTiers.LIVE`（≤2×）接到直播间，实测后**放弃**：
    //
    //   实测 B 站 HLS 直播的播放列表（房间 545068 / 6，多次采样一致）：
    //     EXT-X-ENDLIST  = false        ← 真直播，不是"已完结"
    //     MEDIA-SEQUENCE = …569 → …573  ← 12 秒内递增 = 窗口在滑动
    //     分片数         = 3 段 × 3 秒
    //     窗口总时长     = **9 秒**
    //
    // 客户端缓冲本身就有几秒。倍速一旦 > 1×，播放位置会**持续逼近
    // 窗口右边缘**，追上后只能等新分片 → 频繁 rebuffer。
    // 即"直播加速"在 9 秒窗口下**结构性不可用** ——
    // 不是"设不进去"（`setPlaybackSpeed` 会照常接受），
    // 而是设进去之后表现出来就是卡。
    //
    // 所以**不做这个入口**，而不是做一个看起来能用、实际会卡的按钮。
    //
    // ⚠️ `SpeedTiers.LIVE` 保留在数据层：它描述的是"播放器层面安全的
    //    档位"，对将来的**时移 / 回看**场景仍然有效。当前无 UI 消费它，
    //    已在 `SpeedTiers` 的 KDoc 里注明，避免被当成死代码删掉。

    // 发送与烂梗库状态（v1.6.5）
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    val memes by viewModel.memes.collectAsStateWithLifecycle()
    val senderState by viewModel.senderProfile.collectAsStateWithLifecycle()

    /**
     * 键盘是否可见（v1.6.6）。
     *
     * 用它决定**是否收起「画面 + 房间信息」** —— 见布局处的长说明。
     *
     * ⚠️ `WindowInsets.isImeVisible` 在 foundation 1.7.2 上仍标着
     * `@ExperimentalLayoutApi`，所以要显式 opt-in（不是我们用了什么
     * 不稳定写法，是 API 本身还没转正）。
     *
     * 回退方案（若将来该 API 变动）：
     * `WindowInsets.ime.getBottom(density) > 0`。
     */
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible

    // 一次性提示
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    // 直播全屏是纯画面 —— 状态栏图标切浅色（与视频详情页同一约定）
    LiveStatusBarTint()

    // ⚠️ 用 Box 包一层：弹层（用户菜单 / 时长 / 确认）与 Snackbar
    //    必须渲染在**页面内容之上**。与视频详情页踩过的坑同源 ——
    //    浮层若不在根 Box 内，会被内容盖住或落到导航层之外。
    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // ⚠️ `imePadding()` 必须加在**根 Column** 上（v1.6.6）。
            //
            // 直播间此前完全没有键盘避让（`ui/live/` 下 0 处），
            // 而 manifest 里的 `adjustResize` 只负责缩 window，
            // **Compose 侧不接就仍然会被键盘盖住**。
            //
            // 与 `CommentInputSheet` 踩过的坑同源：IME 相关的
            // padding 必须加在**真正被压缩的那一层**。
            .imePadding()
            .background(colors.bgPrimary),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(V3Size.topBar)
                .padding(horizontal = V3Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(V3Size.touchMin)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.labelPrimary,
                    modifier = Modifier.size(V3Size.iconLg),
                )
            }
            Spacer(Modifier.width(V3Space.xxs))
            Text(
                text = room.title.ifEmpty { "直播间" },
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // ⚠️ 这里**没有**倍速按钮 —— 原因见上方「刻意不提供倍速」的长说明。
            //
            // 刷新：直播流地址会过期（实测 `expires` 约 2 小时），
            // 长时间挂着断了之后需要一个明确的重连入口。
            Box(
                modifier = Modifier
                    .size(V3Size.touchMin)
                    .clip(CircleShape)
                    .clickable { viewModel.reload() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "重新取流",
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(V3Size.iconMd),
                )
            }
        }

        // ---- 画面 + 房间信息（键盘弹起时整体收起）----
        //
        // 🔴 v1.6.6：**键盘弹起时整块收起**。
        //
        // ## 为什么必须收（而不是只加 imePadding）
        //
        // 小屏（360×640dp）固定内容高度 ≈ 472dp（顶栏 52 + 画面 202 +
        // 房间信息 118 + 输入条 ~100）。键盘占 ~290dp 后可用高度只剩
        // ~360dp → **溢出 112dp**，被裁掉的恰好是最后的输入条 ——
        // 也就是"加完 imePadding 仍然打不了字"。
        //
        // 只收紧房间信息也不够（118 → 40 仍溢出 34dp），且信息会挤成一团。
        //
        // ## 为什么收起画面可接受
        //
        // 与"听视频"语义一致：**画面收起但音频继续播** ——
        // 播放器是 Activity 级的，不随这块 UI 销毁；打完字画面立刻回来。
        //
        // 收起后布局是「顶栏 + 聊天(weight 1f) + 输入条」，
        // 聊天用 weight 吸收剩余空间 → **任何屏宽下输入条都可见**。
        if (!imeVisible) {
            // ---- 画面区（16:9）----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(colors.playerBackground),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    // 🔴 直播已结束（v1.6.6）—— 放在**最前面**：
                    // 它是比"取流失败"更明确的事实（我们确实收到了
                    // 播放器的 STATE_ENDED），不该被别的分支盖住。
                    //
                    // ⚠️ 只在真的收到 ENDED 时才会为 true，不猜（见 VM 的 watchEnded）。
                    liveEnded -> EmptyState(
                        title = "直播已结束",
                        description = "主播已经下播。若主播重新开播，点刷新即可继续观看",
                        actionLabel = "刷新",
                        onAction = { viewModel.reload() },
                        modifier = Modifier.fillMaxSize(),
                        compact = true,
                    )

                    error != null -> ErrorState(
                        title = "直播取流失败",
                        description = error,
                        onRetry = { viewModel.reload() },
                        modifier = Modifier.fillMaxSize(),
                        compact = true,
                    )

                    loading -> TerminalLoadingState(
                        text = "正在取流…",
                        modifier = Modifier.fillMaxSize(),
                    )

                    // 未开播是**正常状态**，用空态而不是错误态
                    stream == null || stream?.playable == false -> EmptyState(
                        title = "主播还没有开播",
                        description = "开播后点右上角刷新即可观看",
                        actionLabel = "刷新",
                        onAction = { viewModel.reload() },
                        modifier = Modifier.fillMaxSize(),
                        compact = true,
                    )

                    else -> {
                        LiveVideoSurface(
                            player = player,
                            holder = viewModel.holder,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // 直播中角标（压在画面左上角）
                if (stream?.playable == true && !loading) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(V3Space.xs)
                            .clip(RoundedCornerShape(V3Radius.xs))
                            .background(colors.stateLive)
                            .padding(
                                horizontal = V3Space.tagHorizontal,
                                vertical = V3Space.tagVertical,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "直播中",
                            style = V3Type.caption2.copy(
                                color = colors.labelOnMedia,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }

            // ---- 房间信息（通栏，无卡片）----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = V3Space.md,
                        end = V3Space.md,
                        top = Rhythm.between,
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = room.faceUrl(96),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(V3Size.avatarXs + V3Space.xxl)
                            .clip(CircleShape)
                            .background(colors.avatarPlaceholder),
                    )
                    Spacer(Modifier.width(V3Space.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = room.uname,
                            style = V3Type.callout.copy(
                                fontWeight = FontWeight.Medium,
                                color = colors.labelPrimary,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(V3Space.hairline))
                        Text(
                            text = buildString {
                                if (room.online > 0) {
                                    append(formatCount(room.online))
                                    append(" 人气")
                                }
                                if (room.areaName.isNotEmpty()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(room.areaName)
                                }
                                if (stream?.qualityLabel?.isNotEmpty() == true) {
                                    if (isNotEmpty()) append(" · ")
                                    append(stream?.qualityLabel)
                                }
                            },
                            style = V3Type.caption1.copy(
                                color = colors.labelSecondary,
                            ),
                        )
                    }
                }

            }
        }

        // ---- 聊天（v1.6.4）----
        //
        // 复用现有架构：WebSocket 在 Repository/Client 层，
        // ViewModel 持有消息列表与权限，这里只负责渲染。
        //
        // ⚠️ 聊天区高度用 `weight(1f)` 吃掉剩余空间 —— 它必须能滚，
        //    固定高度会让长消息被裁掉。
        //
        // 🔴 v1.6.6 修复：**这一段此前被嵌在「房间信息」那个
        //    `verticalScroll` 的 Column 内部**（v1.6.4 引入）。
        //    后果是 `LiveChatPanel` 里的 `LazyColumn` 被放进一个
        //    可滚动容器 → 测量时拿到**无限高约束** →
        //    运行期抛 `IllegalStateException: Vertically scrollable
        //    component was measured with an infinity maximum height`。
        //
        //    编译期查不出来（Compose 的约束错误只在运行期暴露），
        //    所以躲过了 lint 与单测。**判据：`LazyColumn` 的任何祖先
        //    都不能有 `verticalScroll`。**
        LiveChatPanel(
            messages = messages,
            connState = connState,
            isSelf = { uid -> viewModel.isSelf(uid) },
            isKnownAdmin = { uid -> viewModel.isKnownAdmin(uid) },
            onUserClick = { msg ->
                // 点弹幕 → 打开用户菜单（v1.6.6 起任何有发送者的消息都可点）
                menuTarget = msg
            },
            onRetryChat = { viewModel.retryChat() },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // ---- 弹幕输入条（v1.6.5）----
        LiveChatInputBar(
            text = draft,
            onTextChange = { viewModel.setDraft(it) },
            canSend = viewModel.canSend,
            sending = sending,
            error = sendError,
            onSend = { viewModel.sendDraft() },
            onOpenMemes = { showMemes = true },
            onLoginRequired = onLoginRequired,
            modifier = Modifier.padding(horizontal = V3Space.xs),
        )

        // ---- 烂梗库（v1.6.5）----
        if (showMemes) {
            MemeLibrarySheet(
                memes = memes,
                // 能否直接发送：已登录才给「发送」按钮（不显示灰按钮）
                canSend = viewModel.canSend,
                sending = sending,
                onDismiss = { showMemes = false },
                onPick = { text ->
                    viewModel.fillDraft(text)
                    showMemes = false
                },
                onCopy = { text ->
                    onCopyName(text)
                },
                onSend = { text ->
                    viewModel.sendText(text)
                    showMemes = false
                },
            )
        }

        // ---- 弹幕发送者资料（v1.6.5）----
        senderState?.let { st ->
            LiveSenderSheet(
                state = st,
                isKnownAdmin = viewModel.isKnownAdmin(st.base.uid),
                isSelf = viewModel.isSelf(st.base.uid),
                onDismiss = { viewModel.dismissSender() },
                onRetry = { viewModel.loadSender(st.base) },
                onOpenProfile = { uid ->
                    viewModel.dismissSender()
                    onOpenUser(uid)
                },
                onCopyName = { name ->
                    viewModel.dismissSender()
                    onCopyName(name)
                },
                onMention = { name ->
                    // 项目不支持 @ 语义，这里只把名字填进输入框
                    viewModel.fillDraft("$name ")
                    viewModel.dismissSender()
                },
                onModerate = { msg ->
                    viewModel.dismissSender()
                    menuTarget = msg
                },
                canModerate = perms.actions.isNotEmpty(),
            )
        }

        // ---- 用户操作菜单（按权限动态）----
        menuTarget?.let { target ->
            LiveUserMenu(
                target = target,
                permissions = perms,
                isKnownAdmin = viewModel.isKnownAdmin(target.uid),
                moderating = moderating,
                onDismiss = { menuTarget = null },
                onAction = { action ->
                    menuTarget = null
                    viewModel.requestAction(target, action)
                },
                onOpenProfile = { uid ->
                    menuTarget = null
                    onOpenUser(uid)
                },
                onCopyName = { name ->
                    menuTarget = null
                    onCopyName(name)
                },
                // v1.6.5：弹幕级操作
                onViewSender = { msg ->
                    menuTarget = null
                    viewModel.loadSender(msg)
                },
                onCopyText = { text ->
                    menuTarget = null
                    onCopyName(text)
                },
                onFillInput = { text ->
                    menuTarget = null
                    viewModel.fillDraft(text)
                },
                onRequestMute = {
                    menuTarget = null
                    muteTarget = target
                },
            )
        }

        // ---- 禁言时长选择 ----
        muteTarget?.let { target ->
            LiveMuteDurationDialog(
                targetName = target.uname,
                busy = moderating != null,
                onDismiss = { muteTarget = null },
                onConfirm = { minutes ->
                    muteTarget = null
                    viewModel.requestMute(target, minutes)
                },
            )
        }

        // ---- 高风险操作二次确认（踢出 / 黑名单）----
        pending?.let { p ->
            LiveConfirmDialog(
                actionLabel = LiveRoomViewModel.actionLabel(p.action),
                targetName = p.target.uname,
                busy = moderating != null,
                onDismiss = { viewModel.cancelPending() },
                onConfirm = { viewModel.confirmPending() },
            )
        }

    }   // end Column

    // ---- 一次性提示（操作成功 / 失败原因）----
    //
    // ⚠️ 必须在**根 Box 内**（Column 之外）—— 放进 Column 会占布局空间，
    //    而且 `align` 只在 BoxScope 里可用。
    SnackbarHost(
        hostState = snackbar,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = V3Space.xxl),
    )
    }   // end Box
}

/**
 * 直播画面渲染。
 *
 * ## 复用共享 `TextureView`（与视频详情页同一个）
 *
 * 理由与 `VideoPlayerSurface` 完全一致：避免 SurfaceView 抓不到帧、
 * 避免切换页面黑一帧。区别只是**媒体源不同**（HLS/FLV 而不是 DASH），
 * 所以装配走 `LiveRoomViewModel`，渲染这一层照旧复用同一个 View。
 */
@Composable
private fun LiveVideoSurface(
    player: ExoPlayer?,
    holder: com.example.biliv3.player.PlayerHolder?,
    modifier: Modifier = Modifier,
) {
    if (player == null) return

    // ⚠️ 必须是 `remember` 的**局部**状态，不能是顶层 var ——
    // 顶层 var 会被所有实例共享，两个直播间同时存在时互相覆盖 binder，
    // 表现为"退出来再进另一个房间，画面是上一个的 / 黑的"。
    var binder by remember { mutableStateOf<PlayerSurfaceBinding?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.FrameLayout(ctx).also { container ->
                // 复用共享 TextureView（与视频详情页同一个）——
                // 理由见 VideoPlayerSurface：避免抓不到帧、避免切页黑一帧。
                val tv = holder?.attachTextureViewTo(container)
                    ?: run {
                        // 无 holder（预览/测试）时退化为自建一个，保持组件可用
                        android.view.TextureView(ctx).also {
                            container.addView(
                                it,
                                android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    }
                // TextureView 不能直接挂 ExoPlayer —— 需要 Surface 中转
                binder = attachPlayerSurface(player, tv)
            }
        },
        update = { binder?.rebind(player) },
        onRelease = {
            binder?.dispose()
            binder = null
            // ⚠️ 不释放播放器：它是 Activity 级的，由导航层决定何时释放
        },
    )
}

/**
 * 直播画面的状态栏图标切浅色。
 *
 * 与 `PlayerStatusBarTint` 同一做法 —— 直播画面也是深色，需要浅色图标。
 */
@Composable
private fun LiveStatusBarTint() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view)
        }
        controller?.isAppearanceLightStatusBars = false
        onDispose { }
    }
}
