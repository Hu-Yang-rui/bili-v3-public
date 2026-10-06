package com.example.biliv3.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.example.biliv3.data.LiveRoom
import com.example.biliv3.data.live.LiveMessage
import com.example.biliv3.data.model.formatCount
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Rhythm
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.example.biliv3.ui.component.EmptyState
import com.example.biliv3.ui.component.ErrorState
import com.example.biliv3.ui.component.TerminalLoadingState
import com.example.biliv3.ui.video.PlayerSurfaceBinding
import com.example.biliv3.ui.video.attachPlayerSurface

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
    modifier: Modifier = Modifier,
    viewModel: LiveRoomViewModel,
) {
    val colors = BiliTheme.colors
    val stream by viewModel.stream.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val player = viewModel.player

    // 聊天（v1.6.4）
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connState by viewModel.connState.collectAsStateWithLifecycle()

    // 权限（数据层判定，UI 只消费）
    val perms by viewModel.permissions.collectAsStateWithLifecycle()
    val moderating by viewModel.moderating.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val pending by viewModel.pendingConfirm.collectAsStateWithLifecycle()

    // 弹层状态：用户菜单 / 禁言时长
    var menuTarget by remember { mutableStateOf<LiveMessage?>(null) }
    var muteTarget by remember { mutableStateOf<LiveMessage?>(null) }

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
            .background(colors.bgBase),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(Sizes.topBarMobile)
                .padding(horizontal = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(Sizes.iconXl),
                )
            }
            Spacer(Modifier.width(Space.x1))
            Text(
                text = room.title.ifEmpty { "直播间" },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 刷新：直播流地址会过期（实测 `expires` 约 2 小时），
            // 长时间挂着断了之后需要一个明确的重连入口。
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(CircleShape)
                    .clickable { viewModel.reload() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "重新取流",
                    tint = colors.textSecondarySafe,
                    modifier = Modifier.size(Sizes.iconLg),
                )
            }
        }

        // ---- 画面区（16:9）----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(colors.playerBackground),
            contentAlignment = Alignment.Center,
        ) {
            when {
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
                        .padding(Space.compactHorizontal)
                        .clip(RoundedCornerShape(Radius.badge))
                        .background(colors.stateLive)
                        .padding(
                            horizontal = Space.tagHorizontal,
                            vertical = Space.tagVertical,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "直播中",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.badge,
                            color = colors.onOverlay,
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
                    start = Space.x4,
                    end = Space.x4,
                    top = Rhythm.between,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = room.faceUrl(96),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(Sizes.upAvatar + Space.x8)
                        .clip(CircleShape)
                        .background(colors.avatarPlaceholder),
                )
                Spacer(Modifier.width(Space.x3))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = room.uname,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = FontSize.body,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Space.micro))
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
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            color = colors.textSecondarySafe,
                        ),
                    )
                }
            }

            // ---- 聊天（v1.6.4）----
            //
            // 复用现有架构：WebSocket 在 Repository/Client 层，
            // ViewModel 持有消息列表与权限，这里只负责渲染。
            //
            // ⚠️ 聊天区高度用 `weight(1f)` 吃掉剩余空间 —— 它必须能滚，
            //    固定高度会让长消息被裁掉。
            LiveChatPanel(
                messages = messages,
                connState = connState,
                canModerate = perms.actions.isNotEmpty(),
                isSelf = { uid -> viewModel.isSelf(uid) },
                isKnownAdmin = { uid -> viewModel.isKnownAdmin(uid) },
                onUserClick = { msg -> menuTarget = msg },
                onRetryChat = { viewModel.retryChat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
            .padding(bottom = Space.x8),
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
