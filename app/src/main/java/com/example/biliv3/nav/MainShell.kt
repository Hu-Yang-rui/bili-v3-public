package com.example.biliv3.nav

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.biliv3.AppContainer
import com.example.biliv3.data.FavFolder
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.WindowSize
import com.example.biliv3.design.tokens.Motion
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.PlaceholderScreen
import com.example.biliv3.ui.aicu.AicuScreen
import com.example.biliv3.ui.bangumi.BangumiScreen
import com.example.biliv3.ui.bangumi.BangumiViewModel
import com.example.biliv3.ui.bangumi.BangumiViewModelFactory
import com.example.biliv3.ui.bangumi.BangumiDetailScreen
import com.example.biliv3.ui.category.CategoryScreen
import com.example.biliv3.ui.download.DownloadScreen
import com.example.biliv3.ui.dynamic.DynamicScreen
import com.example.biliv3.ui.live.LiveScreen
import com.example.biliv3.ui.space.SpaceScreen
import com.example.biliv3.ui.home.BottomNav
import com.example.biliv3.ui.library.FavoriteFolderScreen
import com.example.biliv3.ui.library.FavoriteFolderViewModel
import com.example.biliv3.ui.library.FavoriteScreen
import com.example.biliv3.ui.library.FavoriteViewModel
import com.example.biliv3.ui.library.HistoryScreen
import com.example.biliv3.ui.library.HistoryViewModel
import com.example.biliv3.ui.library.LibraryViewModelFactory
import com.example.biliv3.ui.library.ToViewScreen
import com.example.biliv3.ui.library.ToViewViewModel
import com.example.biliv3.ui.home.HomeScreen
import com.example.biliv3.ui.login.LoginScreen
import com.example.biliv3.ui.login.LoginViewModelFactory
import com.example.biliv3.ui.profile.ProfileScreen
import com.example.biliv3.ui.profile.ProfileViewModel
import com.example.biliv3.ui.profile.ProfileViewModelFactory
import com.example.biliv3.ui.ranking.RankingScreen
import com.example.biliv3.ui.ranking.RankingViewModel
import com.example.biliv3.ui.ranking.RankingViewModelFactory
import com.example.biliv3.ui.search.SearchScreen
import com.example.biliv3.ui.settings.SettingsScreen
import com.example.biliv3.ui.settings.SettingsViewModelFactory
import com.example.biliv3.ui.video.VideoDetailScreen
import com.example.biliv3.ui.video.VideoDetailViewModelFactory
import com.example.biliv3.ui.video.ReplyDetailScreen
import com.example.biliv3.ui.video.ReplyDetailVmFactory

/**
 * 应用外壳：`NavHost` + 底部导航。
 *
 * ## 为什么底部导航放在外壳而不是 HomeScreen 里
 *
 * 底部导航要在三个 Tab（首页/动态/我的）之间**保持存在**。
 * 如果它留在 `HomeScreen` 的 Scaffold 里，切到"动态"就会消失 ——
 * 每个页面各画一遍底栏，既重复又会闪烁。
 *
 * 因此：外壳持有底栏 + NavHost；`HomeScreen` 只保留自己的顶栏。
 *
 * ## 底栏显示条件
 *
 * 只在**三个 Tab 路由**且是移动端时显示。
 * 视频详情、搜索这类页面是沉浸式二级页，压一条底栏会挤占内容
 * （详情页尤其需要纵向空间给播放器和相关推荐）。
 */
@Composable
fun MainShell(
    windowSize: WindowSize,
    container: AppContainer,
    modifier: Modifier = Modifier,
    /**
     * 是否处于 PiP 小窗。
     *
     * ⚠️ PiP 下必须**隐藏底部导航与顶栏** ——
     * 小窗只有巴掌大，塞导航纯属浪费画面且误导用户可点
     * （小窗里的 Tab 点了也没意义）。
     */
    isInPip: Boolean = false,
    navController: NavHostController = rememberNavController(),
) {
    val colors = BiliTheme.colors
    // 收藏夹里的「分享」要唤起系统面板，需要 Context
    val context = androidx.compose.ui.platform.LocalContext.current
    // 把播放器弹层里的弹幕调整写回全局设置（协程作用域）
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    /**
     * 统一的「安全返回」。
     *
     * ## 为什么不能直接用 `navController.popBackStack()`
     *
     * `popBackStack()` 在**栈里只剩当前页**时返回 `false` 且什么都不做 ——
     * 页面就**卡住了**：返回键点了没反应、手势也退不出去。
     * 这不是理论风险，而是本项目的真实症状（登录后卡在「我的」页）。
     *
     * 触发场景：
     * - 进程被系统回收后重建，恢复的栈里只有登录页/二级页
     * - deep link / 通知直接拉起二级页
     * - 从 tab 反复横跳后栈被 popUpTo 清空
     *
     * 所以：弹栈失败就**兜底回首页**，保证任何页面都能退出去。
     */
    val safeBack: () -> Unit = {
        if (!navController.popBackStack()) {
            navController.navigate(Routes.HOME) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = false
                }
                launchSingleTop = true
            }
        }
    }

    val tabRoutes = listOf(Routes.HOME, Routes.DYNAMIC, Routes.PROFILE)
    // PiP 下不显示底栏
    val showBottomNav = !isInPip && windowSize.hasBottomNav && currentRoute in tabRoutes
    val selectedTab = tabRoutes.indexOf(currentRoute).coerceAtLeast(0)

    Scaffold(
        modifier = modifier,
        containerColor = colors.bgBase,
        // inset 由各页面自行消费（HomeScreen 的顶栏要 statusBars），
        // 这里只处理底栏的 navigationBars。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // ⚠️ 这里**刻意不画顶部安全区**。
        //
        // 曾经在 Scaffold 的 topBar 上铺过一条全局黑带，想"一处生效、全页面覆盖"。
        // 结果是错的：只有**视频播放页**需要它（那里本来就是纯黑播放器，
        // 黑带是画面的自然延伸）；而首页/我的/搜索这些**浅色页面**
        // 凭空多一条黑带非常突兀，像没画完。
        //
        // 需要黑带的是视频详情页 —— 见 `VideoDetailScreen` 里
        // `PlayerSafeAreaTop` 的实现与说明。
        bottomBar = {
            if (showBottomNav) {
                BottomNav(
                    selectedIndex = selectedTab,
                    onSelect = { index ->
                        navController.navigate(tabRoutes[index]) {
                            // 三个 Tab 之间切换不要堆栈，且保留各自状态
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
            // ---- 页面转场：只做「横向滑动 + 淡入」，刻意压短 ----
            //
            // ⚠️ 之前用的是 NavHost 默认转场（约 400ms 的横向滑入）。
            // 用户反馈「页面切换响应慢、有迟钝感」—— 主因就是这个：
            // 400ms 里页面已经在动了，但内容还没到，主观感受就是"卡"。
            //
            // 现在压到 Motion.PAGE_TRANSITION_MS（220ms）：
            // - 保留方向感（右侧进、左侧退），不破坏层级认知
            // - 短到"眨眼即完成"，主观上等同于瞬切
            // - 用 `standard` 缓动（前段快、后段缓），开头就有速度感
            //
            // 注意：这里只调**时长**，不删转场。完全无转场会让人分不清
            // "进入了新页"还是"页面还在加载"。
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it / 5 },
                    animationSpec = tween(Motion.PAGE_TRANSITION_MS, easing = Motion.standard),
                ) + fadeIn(
                    animationSpec = tween(Motion.PAGE_FADE_MS, easing = Motion.standard),
                )
            },
            exitTransition = {
                // 退场只淡出、不位移：底层页面"让位"即可，位移反而增加视觉噪音
                fadeOut(
                    animationSpec = tween(Motion.PAGE_FADE_MS, easing = Motion.standard),
                )
            },
            popEnterTransition = {
                fadeIn(
                    animationSpec = tween(Motion.PAGE_FADE_MS, easing = Motion.standard),
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it / 5 },
                    animationSpec = tween(Motion.PAGE_TRANSITION_MS, easing = Motion.standard),
                ) + fadeOut(
                    animationSpec = tween(Motion.PAGE_FADE_MS, easing = Motion.standard),
                )
            },
        ) {
            // ---------- 首页 ----------
            composable(Routes.HOME) {
                // 铃铛红点：订阅未读数。
                //
                // ⚠️ 之前铃铛的 `hasDot` 是**硬编码 false** 且 `clickable {}` 为空，
                // 属于「反模式 #1 死入口」+「消息红点未接线」两条。
                //
                // 红点条件：
                // - 私信未读（`PmUnread.message`）
                // - 「回复我的」未读（受设置项 `notifyReply` 控制）
                //
                // 未登录时 `unread()` 直接返回全 0，红点自然不亮。
                //
                // 账号版本号进 key：未读数按账号隔离，切号后必须重建，
                // 否则首页铃铛红点仍来自上一个账号。
                val accVersionHome by container.accountSync.version
                    .collectAsStateWithLifecycle()
                val unreadVm: com.example.biliv3.ui.message.UnreadBadgeViewModel =
                    viewModel(
                        key = "unread-$accVersionHome",
                        factory = com.example.biliv3.ui.message.UnreadVmFactory(
                            container.pmRepository,
                        ),
                    )
                val settings by container.settingsStore.settings
                    .collectAsStateWithLifecycle(initialValue = com.example.biliv3.data.Settings())
                val unread by unreadVm.unread.collectAsStateWithLifecycle()

                // 回到首页时刷新一次未读数（读完消息返回首页红点要消失）
                LaunchedEffect(currentRoute, unreadVm.isLoggedIn) {
                    if (currentRoute == Routes.HOME && unreadVm.isLoggedIn) {
                        unreadVm.refreshUnread()
                    }
                }

                HomeScreen(
                    windowSize = windowSize,
                    // ⚠️ 必须显式建 VM 并注入设置：不注入的话
                    // `settingsStore` 为 null，「个性化推荐」开关就没有消费者
                    // （这正是它此前被判为"空转设置项"的原因）。
                    viewModel = viewModel(
                        factory = com.example.biliv3.ui.home.HomeVmFactory(
                            container.homeRepository,
                            container.api,
                            container.settingsStore,
                        ),
                    ),
                    onVideoClick = { bvid ->
                        navController.navigate(Routes.video(bvid))
                    },
                    onSearchClick = { navController.navigate(Routes.SEARCH) },
                    onSeeRanking = { navController.navigate(Routes.RANKING) },
                    onMessageClick = { navController.navigate(Routes.MESSAGES) },
                    onProfileClick = {
                        // ⚠️ 必须与底部导航用**同一套 tab 语义**（popUpTo +
                        // launchSingleTop + restoreState），不能裸 navigate。
                        //
                        // 裸 navigate 会把「我的」当成普通页面**再压一个栈帧**，
                        // 于是栈里同时存在两个 profile：
                        //   - 底栏进入的那个（带 restoreState）
                        //   - 右上角头像进入的那个（裸压栈）
                        // 之后从登录页 popBackStack() 会落在哪一个不确定，
                        // 表现就是「登录完卡在『我的』页、返回无效」。
                        navController.navigate(Routes.PROFILE) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavItemClick = { label ->
                        // 顶栏导航项 → 真实路由（此前只改选中态、不跳转 = 死入口）
                        when (label) {
                            "番剧" -> navController.navigate(Routes.BANGUMI)
                            "直播" -> navController.navigate(Routes.LIVE)
                            // ⚠️ 「动态」是底部 tab，必须走 tab 语义而不是裸
                            // navigate —— 理由同 onProfileClick：裸压栈会让
                            // 栈里出现重复的 tab 帧，返回时落点不确定。
                            "动态" -> navController.navigate(Routes.DYNAMIC) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                            // 「首页」是当前页，不跳；「游戏中心 / 会员购」
                            // 第三方客户端无对应能力（游戏中心需 App 端 SDK、
                            // 会员购属电商），**保持不跳转但不留假入口** ——
                            // 见 NavLinks 的说明：这两项在点击时给出明确反馈。
                            else -> Unit
                        }
                    },
                    hasUnread = unread.message > 0 ||
                        (settings.notifyReply && unread.reply > 0),
                    // 页脚外链：用系统浏览器打开（不内嵌 WebView，理由见 Footer 的 KDoc）
                    onOpenLink = { label -> openExternal(context, label) },
                    onCategoryClick = { entry ->
                        // 「推荐」Tab 的 rid 是 0，它本身就是首页，不该跳转
                        if (entry.rid > 0) {
                            // 番剧分区（rid=13）走番剧索引页，其余走分区页。
                            // 这是**移动端唯一可达的番剧入口** —— 顶栏的
                            // 「番剧」链接在移动端被隐藏，所以必须从这里接。
                            if (entry.rid == 13) {
                                navController.navigate(Routes.BANGUMI)
                            } else {
                                navController.navigate(Routes.category(entry.rid, entry.name))
                            }
                        }
                    },
                )
            }

            // ---------- 竖屏沉浸式观看模式 ----------
            //
            // 全屏无导航栏（`showBottomNav` 的 tabRoutes 不含本路由）。
            // 播放器复用 Activity 级 holder，与详情页同一个实例。
            composable(Routes.VERTICAL) {
                val vm: com.example.biliv3.ui.vertical.VerticalViewModel = viewModel(
                    factory = com.example.biliv3.ui.vertical.VerticalVmFactory(
                        feed = container.verticalFeedRepository,
                        videoRepository = container.videoRepository,
                        interactions = container.interactionRepository,
                        spaceRepository = container.spaceRepository,
                        currentMid = { container.authStore.mid },
                        danmakuRepository = container.danmakuRepository,
                    ),
                )
                val vState by vm.state.collectAsStateWithLifecycle()
                val vSettings by container.settingsStore.settings
                    .collectAsStateWithLifecycle(initialValue = com.example.biliv3.data.Settings())
                val vSnackbar = remember { SnackbarHostState() }

                LaunchedEffect(vState.toast) {
                    vState.toast?.let {
                        vSnackbar.showSnackbar(it)
                        vm.consumeToast()
                    }
                }

                /**
                 * 离开竖屏路由时释放播放器。
                 *
                 * ⚠️ 与详情页同样的处理：用 `rememberUpdatedState` 读**最新**
                 * 的 `isInPip`，避免进小窗时误释放（见详情页那段注释）。
                 */
                val vLatestInPip by androidx.compose.runtime.rememberUpdatedState(isInPip)
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose {
                        if (!vLatestInPip) container.playerHolder.release()
                    }
                }

                /**
                 * 确保播放器已创建并绑定当前项媒体。
                 *
                 * `acquire` 在 key 相同时复用，key 变化（换视频）时**重建** ——
                 * 所以用 bvid 作 key，切项不会带着上一条流的缓冲状态。
                 */
                val vBvid = vState.items.getOrNull(vState.currentIndex)?.bvid
                LaunchedEffect(vBvid) {
                    if (vBvid != null) container.playerHolder.acquire(vBvid)
                }

                /**
                 * 订阅播放器运行时错误。
                 *
                 * ⚠️ 与详情页一样**必须订阅** —— 否则解码失败 / 网络中断会
                 * **静默黑屏**（`holder.onError` 无人接收，用户只看到黑画面
                 * 且没有任何提示）。离开时还原，避免污染其他页面。
                 */
                androidx.compose.runtime.DisposableEffect(container.playerHolder) {
                    val prev = container.playerHolder.onError
                    container.playerHolder.onError = { e ->
                        vm.showToast(
                            com.example.biliv3.ui.video.describePlayerError(e),
                        )
                    }
                    onDispose { container.playerHolder.onError = prev }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    com.example.biliv3.ui.vertical.VerticalScreen(
                        state = vState,
                        holder = container.playerHolder,
                        danmaku = vState.danmaku,
                        danmakuEnabled = vSettings.danmakuEnabled,
                        danmakuAlpha = vSettings.danmakuAlpha,
                        danmakuFontScale = vSettings.danmakuFontScale,
                        activeSubtitle = null,
                        onBack = safeBack,
                        onPageChanged = vm::onPageChanged,
                        onPreload = vm::preload,
                        onLike = vm::toggleLike,
                        // 竖屏沉浸流里不弹投币选择器 —— 弹窗会打断浏览。
                        // 直接投默认 2 枚 + 同时点赞（与官方短视频流一致）。
                        onCoin = { vm.coin(count = 2, alsoLike = true) },
                        onFavorite = vm::toggleFavorite,
                        onFollow = vm::toggleFollow,
                        onShare = {
                            vm.onShared()
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_SEND,
                            ).apply {
                                type = "text/plain"
                                vBvid?.let {
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "https://www.bilibili.com/video/$it",
                                    )
                                }
                            }
                            runCatching {
                                context.startActivity(
                                    android.content.Intent.createChooser(intent, "分享到"),
                                )
                            }
                        },
                        onRetry = vm::retry,
                        onPlayerError = { msg ->
                            vm.showToast(msg)
                        },
                    )
                    SnackbarHost(
                        hostState = vSnackbar,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                    )
                }
            }

            // ---------- 视频详情 ----------
            composable(
                route = Routes.VIDEO,
                arguments = listOf(
                    navArgument(Routes.VIDEO_ARG_BVID) { type = NavType.StringType },
                    // 可选：进入后定位到这条评论（AI 查成分「在 APP 内查看」用）
                    navArgument(Routes.VIDEO_ARG_FOCUS_RPID) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                val bvid = backStackEntry.arguments?.getString(Routes.VIDEO_ARG_BVID).orEmpty()
                val focusRpid = backStackEntry.arguments
                    ?.getString(Routes.VIDEO_ARG_FOCUS_RPID)
                    .orEmpty()

                /**
                 * ⚠️ 离开视频路由时释放播放器。
                 *
                 * ## 为什么必须显式做（否则是一个真实泄漏）
                 *
                 * 播放器已提升到 Activity 级（PiP 需要它活得比页面久）。
                 * 但"活得久"≠"永不释放" —— 若没有这个 effect：
                 *
                 * - 用户返回首页后，**声音还在响**（播放器没人管）
                 * - 连续进出 10 个视频 → 10 个解码器实例 → OOM
                 *
                 * ## ⚠️ `isInPip` 绝对不能放进 key（这是"进小窗就暂停"的根因）
                 *
                 * 曾写成 `DisposableEffect(backStackEntry, isInPip)`。
                 * 问题是：**key 一变，Compose 必定先 dispose 旧 effect**。
                 * 而进小窗时 `isInPip` 从 false→true 正好触发重建 ——
                 * dispose 那一刻闭包读到的仍是**旧值 false**，
                 * 于是走了 `release()` 分支，播放器被释放成 IDLE。
                 *
                 * 表现：小窗刚出现就是**暂停画面 + 播放三角**
                 * （用户报告的现象），且再也播不起来。
                 *
                 * 修法：key 只保留 `backStackEntry`（真正的生命周期边界），
                 * 用 `rememberUpdatedState` 在 dispose 时读取**最新**的
                 * `isInPip` —— 这样既能读到 true 而跳过释放，
                 * 又不会因为值变化而重建 effect。
                 */
                val latestInPip by androidx.compose.runtime.rememberUpdatedState(isInPip)
                androidx.compose.runtime.DisposableEffect(backStackEntry) {
                    onDispose {
                        if (!latestInPip) {
                            container.playerHolder.release()
                        }
                    }
                }

                // 订阅一次设置，按值传给详情页。
                // ⚠️ 不用 collectAsStateWithLifecycle 的默认 initial ——
                // 设置是本地同步读，首帧就要拿到真值，否则"自动起播"会失效。
                val settings by container.settingsStore.settings
                    .collectAsStateWithLifecycle(initialValue = com.example.biliv3.data.Settings())

                // ---- 下载入口（离线缓存）----
                //
                // ⚠️ 这是 `VideoDownloader`（315 行、含断点续传）唯一的 UI 入口。
                // 此前它写好了、注入到 AppContainer 了，却没有任何页面调用。
                val dlEntry: com.example.biliv3.ui.download.VideoDownloadEntryViewModel =
                    viewModel(
                        key = "dl-entry-$bvid",
                        factory = com.example.biliv3.ui.download.VideoDownloadEntryVmFactory(
                            container.downloadStore,
                            container.videoDownloader,
                        ),
                    )
                val dlToast by dlEntry.toast.collectAsStateWithLifecycle()
                val detailSnackbar = remember { SnackbarHostState() }
                LaunchedEffect(dlToast) {
                    dlToast?.let {
                        detailSnackbar.showSnackbar(it)
                        dlEntry.consumeToast()
                    }
                }

                // ---- 稍后再看状态（决定 ⋮ 菜单显示"加入"还是"已在"）----
                var inToView by remember(bvid) { mutableStateOf(false) }
                LaunchedEffect(bvid) {
                    inToView = runCatching {
                        container.libraryRepository.isInToViewByBvid(bvid)
                    }.getOrDefault(false)
                }

                // ---- 续播位置（本地进度）----
                //
                // ⚠️ 首版**在线播放完全不记进度** —— 退出再进来永远从 0 开始。
                // 只有离线缓存存了 lastPositionMs。
                var resumeMs by remember(bvid) { mutableStateOf(0L) }
                LaunchedEffect(bvid) {
                    // cid 在详情加载前未知，先用 0 查（分P 会在 bind 后细化）
                    resumeMs = runCatching {
                        container.playbackProgress.get(bvid, 0L)
                    }.getOrDefault(0L)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    VideoDetailScreen(
                        bvid = bvid,
                        windowSize = windowSize,
                        // AI 查成分「在 APP 内查看」带过来的评论 id。
                        // 非空时详情页会自动切评论 Tab、翻页定位并高亮。
                        focusRpid = focusRpid,
                        // 空降助手：总开关来自设置
                        sponsorBlockEnabled = settings.sponsorBlockEnabled,
                        settings = settings,
                        // Activity 级播放器：PiP / 切页继续播都依赖它
                        holder = container.playerHolder,
                        isInPip = isInPip,
                        // 通过 Context 拿 Activity 调 enterPictureInPictureMode。
                        // 找不到 Activity（理论上不该发生）时返回 false，
                        // 由 UI 忽略——不要崩。
                        onEnterPip = {
                            val act = context as? com.example.biliv3.MainActivity
                            act?.enterPip() ?: false
                        },
                        // ---- 新增接线（补齐缺失功能）----
                        onOwnerClick = { mid -> navController.navigate(Routes.space(mid)) },
                        onOpenReplyDetail = { oid, root, upMid ->
                            navController.navigate(Routes.replyDetail(oid, root, upMid))
                        },
                        downloadEntry = dlEntry,
                        onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) },
                        inToView = inToView,
                        onAddToView = { aid ->
                            scope.launch {
                                val ok = runCatching {
                                    container.libraryRepository.addToView(aid)
                                }.getOrDefault(false)
                                if (ok) {
                                    inToView = true
                                    detailSnackbar.showSnackbar("已加入稍后再看")
                                } else {
                                    detailSnackbar.showSnackbar("加入失败，请检查登录状态")
                                }
                            }
                        },
                        resumePositionMs = resumeMs,
                        onResumeConsumed = { resumeMs = 0L },
                        // ⚠️ 播放器弹层里改弹幕档位时，**同时写回全局设置**。
                        //
                        // 不写回的话，这两个入口就是"各改各的"：
                        // 在弹层里把字号调大，去设置页还是旧值 —— 未联动。
                        // 写回后两边始终一致。
                        onDanmakuSettingsChanged = { enabled, alpha, fontScale, area ->
                            scope.launch {
                                container.settingsStore.setDanmakuEnabled(enabled)
                                container.settingsStore.setDanmakuAlpha(alpha)
                                container.settingsStore.setDanmakuFontScale(fontScale)
                                container.settingsStore.setDanmakuArea(area)
                            }
                        },
                    onBack = safeBack,
                    // 详情页里点相关推荐 → 再进一个详情页。
                    //
                    // ⚠️ 用 popUpTo 视频页 + 恢复保存状态，而不是无限累加。
                    // 否则从首页连点 10 个推荐视频，返回要按 10 次手势，
                    // 且 10 个 ExoPlayer 实例的 ViewModel 全留在栈上。
                    // 现在「相关推荐」在同一层替换，返回一次即回首页。
                    onVideoClick = { next ->
                        navController.navigate(Routes.video(next)) {
                            popUpTo(Routes.VIDEO) { inclusive = true }
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    // 未登录点互动 → 引导登录
                    onLoginRequired = { navController.navigate(Routes.LOGIN) },
                    viewModel = viewModel(
                        key = "detail-$bvid",
                        factory = VideoDetailViewModelFactory(
                            bvid = bvid,
                            videoRepo = container.videoRepository,
                            interactionRepo = container.interactionRepository,
                            subtitleRepo = container.subtitleRepository,
                            danmakuRepo = container.danmakuRepository,
                            commentRepo = container.commentRepository,
                            sponsorBlockRepo = container.sponsorBlockRepository,
                            authHeader = container.appAuthHeader,
                            // 详情页收藏后广播，收藏列表/我的页据此刷新
                            favoritesSync = container.favoritesSync,
                            // 播放进度持久化（在线断点续播）
                            progressStore = container.playbackProgress,
                            // 服务端历史上报（多端续播）
                            libraryRepo = container.libraryRepository,
                            // 「保存观看历史」开关的消费者
                            settingsStore = container.settingsStore,
                        ),
                    ),
                )

                    SnackbarHost(
                        hostState = detailSnackbar,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Space.x12),
                    )
                }
            }

            // ---------- 搜索 ----------
            composable(Routes.SEARCH) {
                SearchScreen(
                    onBack = safeBack,
                    onVideoClick = { bvid -> navController.navigate(Routes.video(bvid)) },
                )
            }

            // ---------- 楼中楼详情（全部回复）----------
            composable(
                route = Routes.REPLY_DETAIL,
                arguments = listOf(
                    navArgument(Routes.REPLY_ARG_OID) { type = NavType.LongType },
                    navArgument(Routes.REPLY_ARG_ROOT) { type = NavType.LongType },
                    navArgument(Routes.REPLY_ARG_UP) {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
            ) { entry ->
                val oid = entry.arguments?.getLong(Routes.REPLY_ARG_OID) ?: 0L
                val root = entry.arguments?.getLong(Routes.REPLY_ARG_ROOT) ?: 0L
                val upMid = entry.arguments?.getLong(Routes.REPLY_ARG_UP) ?: 0L

                ReplyDetailScreen(
                    onBack = safeBack,
                    onAvatarClick = { mid ->
                        if (mid > 0) navController.navigate(Routes.space(mid))
                    },
                    viewModel = viewModel(
                        key = "reply-$oid-$root",
                        factory = ReplyDetailVmFactory(
                            repo = container.commentRepository,
                            oid = oid,
                            root = root,
                            upMid = upMid,
                            rootComment = null,
                        ),
                    ),
                )
            }

            // ---------- 分区 ----------
            composable(
                route = Routes.CATEGORY,
                arguments = listOf(
                    navArgument(Routes.CATEGORY_ARG_RID) { type = NavType.IntType },
                    navArgument(Routes.CATEGORY_ARG_NAME) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                val rid = backStackEntry.arguments?.getInt(Routes.CATEGORY_ARG_RID) ?: 0
                val name = backStackEntry.arguments?.getString(Routes.CATEGORY_ARG_NAME).orEmpty()

                // ⚠️ 这里此前是 PlaceholderScreen("rid = X，分区列表将在后续阶段接入")
                // —— 首页 12 个分区入口点进去全是这张图。
                CategoryScreen(
                    title = name.ifEmpty { "分区" },
                    windowSize = windowSize,
                    onBack = safeBack,
                    onVideoClick = { bvid -> navController.navigate(Routes.video(bvid)) },
                    viewModel = viewModel(
                        key = "category-$rid",
                        factory = com.example.biliv3.ui.category.CategoryVmFactory(
                            container.categoryRepository,
                            rid,
                        ),
                    ),
                )
            }

            // ---------- 扫码登录 ----------
            composable(Routes.LOGIN) {
                LoginScreen(
                    // 登录页可能是栈底（进程重建后直接落在登录页）——
                    // safeBack 会兜底回首页，不会卡住。
                    onBack = safeBack,
                    onLoggedIn = {
                        // ⚠️ 所有登录方式（扫码 / 手机号 / 密码 / WebView）
                        // 都汇聚到这一个回调 —— 在这里做两件事，保证不漏：
                        //
                        // 1. 把新账号**存入多账号列表**（否则「切换账号」里
                        //    看不到刚登录的号）
                        // 2. 广播账号变化 —— 首页/收藏/历史等订阅方重新加载
                        //
                        // 放在这里而不是各登录分支里：登录入口有 4 种，
                        // 分散写必然漏掉其中一两种（"扫码登录后列表里没有
                        // 这个号"就是这么来的）。
                        container.accountSwitcher.rememberCurrent()
                        container.accountSync.notifyChanged()

                        // 用 safeBack：登录页若已是栈底，裸 popBackStack 返回
                        // false 且无动作 → 用户**卡在登录页出不去**（主症状）。
                        safeBack()
                    },
                    viewModel = viewModel(
                        factory = LoginViewModelFactory(container.authRepository),
                    ),
                )
            }

            // ---------- 动态 ----------
            composable(Routes.DYNAMIC) {
                // ⚠️ 此前是 PlaceholderScreen("动态页依赖登录态，将在后续阶段接入")
                // —— **一个底部 Tab 点进去是占位图**。
                // 动态流来自"关注的 UP 主"，完全按账号隔离 ——
                // 切号后必须重建，否则看到上一个账号的关注动态。
                val accVersion by container.accountSync.version.collectAsStateWithLifecycle()
                DynamicScreen(
                    onLoginRequired = { navController.navigate(Routes.LOGIN) },
                    onVideoClick = { bvid -> navController.navigate(Routes.video(bvid)) },
                    viewModel = viewModel(
                        key = "dynamic-$accVersion",
                        factory = com.example.biliv3.ui.dynamic.DynamicVmFactory(
                            container.dynamicRepository,
                        ),
                    ),
                )
            }

            // ---------- 用户主页（UP 主空间）----------
            composable(
                route = Routes.SPACE,
                arguments = listOf(
                    navArgument(Routes.SPACE_ARG_MID) { type = NavType.LongType },
                ),
            ) { entry ->
                val mid = entry.arguments?.getLong(Routes.SPACE_ARG_MID) ?: 0L
                SpaceScreen(
                    onBack = safeBack,
                    onVideoClick = { bvid -> navController.navigate(Routes.video(bvid)) },
                    onLoginRequired = { navController.navigate(Routes.LOGIN) },
                    onChat = { talkerId, talkerName ->
                        navController.navigate(Routes.chat(talkerId, talkerName))
                    },
                    // 用户主页 → 查成分（带 mid 直达，省得再输一遍 UID）
                    onAicu = { mid -> navController.navigate(Routes.aicu(mid)) },
                    viewModel = viewModel(
                        key = "space-$mid",
                        factory = com.example.biliv3.ui.space.SpaceVmFactory(
                            container.spaceRepository,
                            container.dynamicRepository,
                            mid,
                        ),
                    ),
                )
            }

            // ---------- 直播列表 ----------
            composable(Routes.LIVE) {
                LiveScreen(
                    windowSize = windowSize,
                    onBack = safeBack,
                    // ⚠️ 打开**官方直播间**（系统浏览器）。
                    // 本项目不做直播流播放（缺 FLV/HLS 依赖），
                    // 硬做会得到"点进去黑屏"——比没有这个功能更差。
                    onOpenRoom = { room ->
                        openExternalUrl(context, "https://live.bilibili.com/${room.roomId}")
                    },
                    viewModel = viewModel(
                        factory = com.example.biliv3.ui.live.LiveVmFactory(
                            container.liveRepository,
                        ),
                    ),
                )
            }

            // ---------- 离线缓存管理 ----------
            composable(Routes.DOWNLOADS) {
                val dlVm: com.example.biliv3.ui.download.DownloadViewModel = viewModel(
                    factory = com.example.biliv3.ui.download.DownloadVmFactory(
                        container.downloadStore,
                        container.videoDownloader,
                    ),
                )
                val dlSnackbar = remember { SnackbarHostState() }
                val dlToast by dlVm.toast.collectAsStateWithLifecycle()
                LaunchedEffect(dlToast) {
                    dlToast?.let {
                        dlSnackbar.showSnackbar(it)
                        dlVm.consumeToast()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    DownloadScreen(
                        onBack = safeBack,
                        // 离线播放：直接进详情页（播放器会优先用本地源，
                        // 见 VideoDetailViewModel 的本地源选择）
                        onPlay = { item -> navController.navigate(Routes.video(item.bvid)) },
                        onVideoClick = { bvid -> navController.navigate(Routes.video(bvid)) },
                        viewModel = dlVm,
                    )
                    SnackbarHost(
                        hostState = dlSnackbar,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Space.x12),
                    )
                }
            }

            // ---------- 我的 ----------
            composable(Routes.PROFILE) {
                // 每次回到本页都重查登录态：从登录页返回时状态会变
                val profileVm: ProfileViewModel = viewModel(
                    factory = ProfileViewModelFactory(
                        repo = container.authRepository,
                        // 退出登录走 switcher（保留账号到多账号列表）
                        switcher = container.accountSwitcher,
                        // 切号后本页自动刷新，避免显示旧账号
                        accountSync = container.accountSync,
                    ),
                )
                // ⚠️ 收藏版本号也参与触发：在详情页收藏/取消后，
                // 「我的」页的收藏数等统计需要同步，否则显示的是旧值。
                val favVersion by container.favoritesSync.version.collectAsStateWithLifecycle()
                // ⚠️ 账号版本号同样参与：切号后必须重拉用户信息
                val accVersion by container.accountSync.version.collectAsStateWithLifecycle()
                LaunchedEffect(currentRoute, favVersion, accVersion) {
                    if (currentRoute == Routes.PROFILE) profileVm.refresh()
                }
                ProfileScreen(
                    onLoginClick = { navController.navigate(Routes.LOGIN) },
                    onNavigate = { route -> navController.navigate(route) },
                    viewModel = profileVm,
                )
            }

            // ---------- 番剧 / 影视 ----------
            composable(Routes.BANGUMI) {
                val vm: BangumiViewModel = viewModel(
                    factory = BangumiViewModelFactory(container.bangumiRepository),
                )
                val tabs by vm.tabs.collectAsStateWithLifecycle()
                val selectedType by vm.selectedType.collectAsStateWithLifecycle()
                val items by vm.items.collectAsStateWithLifecycle()
                val loading by vm.loading.collectAsStateWithLifecycle()
                val error by vm.error.collectAsStateWithLifecycle()

                BangumiScreen(
                    tabs = tabs,
                    selectedType = selectedType,
                    items = items,
                    loading = loading,
                    error = error,
                    onBack = safeBack,
                    onSelectTab = vm::selectTab,
                    // 番剧条目 → 番剧详情页（此前是 `onItemClick = { }` 空 lambda，
                    // 即明确的死入口）。
                    //
                    // 番剧走 ep_id 取流，与 UGC 的 bvid 是两套体系，
                    // 所以不能直接跳视频详情页 —— 必须先看详情选集。
                    onItemClick = { item ->
                        navController.navigate(Routes.bangumiDetail(item.seasonId))
                    },
                    onRetry = vm::retry,
                )
            }

            // ---------- 番剧详情（选集）----------
            composable(
                route = Routes.BANGUMI_DETAIL,
                arguments = listOf(
                    navArgument(Routes.BANGUMI_ARG_SEASON) { type = NavType.LongType },
                ),
            ) { entry ->
                val seasonId = entry.arguments?.getLong(Routes.BANGUMI_ARG_SEASON) ?: 0L
                BangumiDetailScreen(
                    onBack = safeBack,
                    // 有 bvid 的集 → 直接跳视频详情页播放
                    onEpisodePlayable = { bvid -> navController.navigate(Routes.video(bvid)) },
                    // 没有 bvid 的集 → 打开官方页面（不做假的可点状态）
                    onEpisodeUnavailable = { ep ->
                        val url = "https://www.bilibili.com/bangumi/play/ep${ep.epId}"
                        openExternalUrl(context, url)
                    },
                    viewModel = viewModel(
                        key = "bangumi-$seasonId",
                        factory = com.example.biliv3.ui.bangumi.BangumiDetailVmFactory(
                            container.bangumiRepository,
                            seasonId,
                            container.authStore,
                        ),
                    ),
                )
            }
            // ---------- 排行榜 ----------
            composable(Routes.RANKING) {
                val vm: RankingViewModel = viewModel(
                    factory = RankingViewModelFactory(container.rankingRepository),
                )
                val tabs by vm.tabs.collectAsStateWithLifecycle()
                val selectedRid by vm.selectedRid.collectAsStateWithLifecycle()
                val videos by vm.videos.collectAsStateWithLifecycle()
                val loading by vm.loading.collectAsStateWithLifecycle()
                val error by vm.error.collectAsStateWithLifecycle()

                RankingScreen(
                    tabs = tabs,
                    selectedRid = selectedRid,
                    videos = videos,
                    loading = loading,
                    error = error,
                    onBack = safeBack,
                    onSelectTab = vm::selectTab,
                    onVideoClick = { bvid -> navController.navigate(Routes.video(bvid)) },
                    onRetry = vm::retry,
                )
            }

            // ---------- 历史记录 ----------
            composable(Routes.HISTORY) {
                // ⚠️ 账号版本号进 key：切号后强制重建 VM → 重新拉新账号的历史。
                // 不这么做的话，切号后这里仍显示**上一个账号**的历史记录
                // （典型的"串号"）。
                val accVersion by container.accountSync.version.collectAsStateWithLifecycle()
                val vm: HistoryViewModel = viewModel(
                    key = "history-$accVersion",
                    factory = LibraryViewModelFactory(container.libraryRepository),
                )
                val entries by vm.entries.collectAsStateWithLifecycle()
                val loading by vm.loading.collectAsStateWithLifecycle()
                val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
                val hasMore by vm.hasMore.collectAsStateWithLifecycle()

                HistoryScreen(
                    entries = entries,
                    loading = loading,
                    loadingMore = loadingMore,
                    hasMore = hasMore,
                    isLoggedIn = vm.isLoggedIn,
                    onBack = safeBack,
                    onLoadMore = vm::loadMore,
                    onVideoClick = { bvid -> navController.navigate(Routes.video(bvid)) },
                    onLoginRequired = { navController.navigate(Routes.LOGIN) },
                )
            }

            // ---------- 我的收藏（总览：收藏夹分组 + 预览网格）----------
            composable(Routes.FAVORITES) {
                // ⚠️ key 里带上收藏版本号：任意地方收藏/取消后，
                // 版本号变化 → 强制重建 ViewModel → 重新拉数据。
                //
                // 这是"状态联动"落地的关键。不这样的话，
                // 用户在详情页收藏后回到这里，列表还是旧的。
                val favVersion by container.favoritesSync.version.collectAsStateWithLifecycle()
                // 账号版本号同样进 key：切号后收藏夹属于新账号，
                // 必须重建（否则看到的是旧账号的收藏）
                val accVersion by container.accountSync.version.collectAsStateWithLifecycle()

                val vm: FavoriteViewModel = viewModel(
                    key = "fav-overview-$favVersion-$accVersion",
                    factory = LibraryViewModelFactory(container.libraryRepository),
                )
                val folders by vm.folders.collectAsStateWithLifecycle()
                val previews by vm.previews.collectAsStateWithLifecycle()
                val loading by vm.loading.collectAsStateWithLifecycle()

                val favSnackbar = remember { SnackbarHostState() }

                Box(modifier = Modifier.fillMaxSize()) {
                    FavoriteScreen(
                        folders = folders,
                        previews = previews,
                        loading = loading,
                        isLoggedIn = vm.isLoggedIn,
                        onBack = safeBack,
                        onOpenFolder = { f ->
                            navController.navigate(Routes.favFolder(f.id, f.title))
                        },
                        onVideoClick = { bvid -> navController.navigate(Routes.video(bvid)) },
                        onLoginRequired = { navController.navigate(Routes.LOGIN) },
                        onRetry = vm::retry,
                    )
                    SnackbarHost(
                        hostState = favSnackbar,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Space.x12),
                    )
                }
            }

            // ---------- 收藏夹详情（单个夹的完整列表）----------
            composable(
                route = Routes.FAV_FOLDER,
                arguments = listOf(
                    navArgument(Routes.FAV_FOLDER_ARG_ID) { type = NavType.LongType },
                    navArgument(Routes.FAV_FOLDER_ARG_TITLE) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                val folderId = backStackEntry.arguments?.getLong(Routes.FAV_FOLDER_ARG_ID) ?: 0L
                val folderTitle = backStackEntry.arguments
                    ?.getString(Routes.FAV_FOLDER_ARG_TITLE).orEmpty()

                val vm: FavoriteFolderViewModel = viewModel(
                    factory = LibraryViewModelFactory(
                        repo = container.libraryRepository,
                        folderId = folderId,
                        sync = container.favoritesSync,
                    ),
                )
                val entries by vm.items.collectAsStateWithLifecycle()
                val loading by vm.loading.collectAsStateWithLifecycle()
                val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
                val hasMore by vm.hasMore.collectAsStateWithLifecycle()
                val toast by vm.toast.collectAsStateWithLifecycle()

                val favSnackbar = remember { SnackbarHostState() }
                LaunchedEffect(toast) {
                    toast?.let {
                        favSnackbar.showSnackbar(it)
                        vm.consumeToast()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    FavoriteFolderScreen(
                        folder = FavFolder(
                            id = folderId,
                            title = folderTitle.ifEmpty { "收藏夹" },
                            mediaCount = 0,
                            cover = "",
                            isDefault = false,
                        ),
                        entries = entries,
                        loading = loading,
                        loadingMore = loadingMore,
                        hasMore = hasMore,
                        isLoggedIn = vm.isLoggedIn,
                        onBack = safeBack,
                        onLoadMore = vm::loadMore,
                        onRemove = vm::removeFavorite,
                        onVideoClick = { bvid -> navController.navigate(Routes.video(bvid)) },
                        onLoginRequired = { navController.navigate(Routes.LOGIN) },
                        // 分享：唤起系统分享面板（与详情页一致的做法）
                        onShare = { entry ->
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_SEND,
                            ).apply {
                                type = "text/plain"
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "https://www.bilibili.com/video/${entry.video.bvid}",
                                )
                            }
                            runCatching {
                                context.startActivity(
                                    android.content.Intent.createChooser(intent, "分享到"),
                                )
                            }
                        },
                    )
                    SnackbarHost(
                        hostState = favSnackbar,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Space.x12),
                    )
                }
            }

            // ---------- 稍后再看 ----------
            composable(Routes.TO_VIEW) {
                // 账号版本号进 key：稍后再看是按账号存的，切号必须重建
                val accVersion by container.accountSync.version.collectAsStateWithLifecycle()
                val vm: ToViewViewModel = viewModel(
                    key = "toview-$accVersion",
                    factory = LibraryViewModelFactory(container.libraryRepository),
                )
                val videos by vm.videos.collectAsStateWithLifecycle()
                val loading by vm.loading.collectAsStateWithLifecycle()

                ToViewScreen(
                    videos = videos,
                    loading = loading,
                    isLoggedIn = vm.isLoggedIn,
                    onBack = safeBack,
                    onVideoClick = { bvid -> navController.navigate(Routes.video(bvid)) },
                    onLoginRequired = { navController.navigate(Routes.LOGIN) },
                )
            }
            // ---------- 私信会话列表 ----------
            //
            // ⚠️ 入口放在「我的」页而不是底部导航：
            // 底部已有 首页/动态/我的 三个 tab，再加一个会挤（且私信
            // 不属于高频一级入口）。放「我的」页与历史/收藏同级，
            // 符合"个人相关功能聚在一起"的信息架构。
            composable(Routes.MESSAGES) {
                // 私信按账号隔离，切号必须重建 VM
                val accVersion by container.accountSync.version.collectAsStateWithLifecycle()
                val vm: com.example.biliv3.ui.message.MessageListViewModel = viewModel(
                    key = "messages-$accVersion",
                    factory = com.example.biliv3.ui.message.MessageVmFactory(
                        container.pmRepository,
                    ),
                )
                com.example.biliv3.ui.message.MessageListScreen(
                    onBack = safeBack,
                    onOpenChat = { s ->
                        navController.navigate(Routes.chat(s.talkerId, s.talkerName))
                    },
                    onLogin = { navController.navigate(Routes.LOGIN) },
                    // 点头像进主页（与整行进聊天区分）
                    onOpenSpace = { mid -> navController.navigate(Routes.space(mid)) },
                    viewModel = vm,
                )
            }

            // ---------- 聊天 ----------
            composable(
                route = Routes.CHAT,
                arguments = listOf(
                    navArgument(Routes.CHAT_ARG_MID) { type = NavType.LongType },
                    navArgument(Routes.CHAT_ARG_NAME) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val talkerId = entry.arguments?.getLong(Routes.CHAT_ARG_MID) ?: 0L
                val talkerName = entry.arguments?.getString(Routes.CHAT_ARG_NAME).orEmpty()
                // key(talkerId)：不同会话必须是**不同的 VM 实例**，
                // 否则切人会看到上一个会话的消息（经典状态串味 bug）
                val vm: com.example.biliv3.ui.message.ChatViewModel = viewModel(
                    key = "chat-$talkerId",
                    factory = com.example.biliv3.ui.message.ChatVmFactory(
                        container.pmRepository,
                        talkerId,
                    ),
                )
                com.example.biliv3.ui.message.ChatScreen(
                    talkerName = talkerName.ifEmpty { "用户$talkerId" },
                    onBack = safeBack,
                    onLogin = { navController.navigate(Routes.LOGIN) },
                    viewModel = vm,
                )
            }

            // ---------- aicu 查成分 ----------
            composable(
                route = Routes.AICU,
                arguments = listOf(
                    // 可选参数：不带 uid 时进入"自己输入"形态。
                    // 用 LongType + defaultValue 0L（0 = 未指定），
                    // 比 nullable 更省事 —— 0 不是合法 UID，语义无歧义。
                    navArgument(Routes.AICU_ARG_UID) {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
            ) { entry ->
                val uid = entry.arguments?.getLong(Routes.AICU_ARG_UID)?.takeIf { it > 0L }

                AicuScreen(
                    onBack = safeBack,
                    // 外链走系统浏览器：aicu 的评论/弹幕最终指向 B 站官方页，
                    // 内嵌 WebView 会变成"套壳浏览器"且登录态串味。
                    onOpenUrl = { url -> openExternalUrl(context, url) },
                    // 站内跳转（问题 8）：aicu 的评论只带 **av 号**，
                    // 而视频详情路由走 bvid —— 这里统一转成 `av{n}` 形式，
                    // 由 VideoRepository.detail 识别后用 `aid` 参数请求
                    // （官方 view 接口支持 aid/bvid 二选一，无需换算表）。
                    //
                    // ⚠️ 必须把 `rpid` 一起带上（原实现用 `_` 丢掉了它）——
                    // 否则只是"打开视频"，用户还得自己在几百条评论里翻。
                    // 带 rpid 后详情页会切到评论 Tab、翻页找到它、滚动并高亮。
                    onOpenCommentInApp = { avId, rpid, dynType ->
                        if (avId.isNotEmpty()) {
                            // 只有 `dynType == 1`（视频）才能用视频详情页定位评论；
                            // 专栏(12)/动态(17) 的 oid 不在视频 id 空间，
                            // 硬跳视频页会因 bvid 非法而报错 → 退化为外链。
                            if (dynType == 1) {
                                navController.navigate(
                                    Routes.videoAtComment("av$avId", rpid),
                                )
                            } else {
                                openExternalUrl(
                                    context,
                                    com.example.biliv3.data.AicuRepository
                                        .replyTargetUrl(dynType, avId, rpid),
                                )
                            }
                        }
                    },
                    viewModel = viewModel(
                        // uid 进 key：从不同用户主页分别进来时，
                        // 各保留自己的查询状态，不会串数据。
                        key = "aicu-${uid ?: 0L}",
                        factory = com.example.biliv3.ui.aicu.AicuVmFactory(
                            repo = container.aicuRepository,
                            initialUid = uid,
                        ),
                    ),
                )
            }

            // ---------- 切换账号 ----------
            composable(Routes.ACCOUNTS) {
                com.example.biliv3.ui.accounts.AccountsScreen(
                    onBack = safeBack,
                    // 添加新账号 = 去登录页；登录成功后由 LOGIN 的
                    // onLoggedIn 自动 rememberCurrent + 广播，回来就能看到新账号
                    onAddAccount = { navController.navigate(Routes.LOGIN) },
                    viewModel = viewModel(
                        key = "accounts",
                        factory = com.example.biliv3.ui.accounts.AccountsVmFactory(
                            switcher = container.accountSwitcher,
                            // 每次读实时值（切号后立刻变化），不缓存快照
                            currentMid = { container.authStore.mid },
                        ),
                    ),
                )
            }

            // ---------- 设置 ----------
            composable(Routes.SETTINGS) {
                // 图片缓存占用：异步算一次，算完通过参数传给设置页
                // （设置页不做 IO，见 SettingsScreen 的参数说明）。
                var cacheLabel by remember { mutableStateOf("计算中…") }
                var cacheBytes by remember { mutableStateOf(0L) }
                val cacheScope = rememberCoroutineScope()

                fun recalcCache() {
                    cacheScope.launch {
                        val bytes = com.example.biliv3.util.ImageCache.sizeBytes(context)
                        cacheBytes = bytes
                        cacheLabel = com.example.biliv3.util.ImageCache.formatBytes(bytes)
                    }
                }

                LaunchedEffect(Unit) { recalcCache() }

                SettingsScreen(
                    onBack = safeBack,
                    cacheLabel = cacheLabel,
                    onClearImageCache = {
                        cacheScope.launch {
                            com.example.biliv3.util.ImageCache.clear(context)
                            // 清完必须重算，否则占用数字不动 ——
                            // 「清理后占用空间数字立即更新」是验收要求。
                            recalcCache()
                        }
                    },
                    viewModel = viewModel(
                        factory = SettingsViewModelFactory(container.settingsStore),
                    ),
                )
            }
        }
    }
}

/**
 * 用系统浏览器打开外链。
 *
 * ## 为什么是"系统浏览器"而不是内嵌 WebView
 *
 * 1. 内嵌会变成"套壳浏览器"，还要处理登录态串味（用户可能在网页版登录了别的号）
 * 2. 系统浏览器有用户自己的登录态、广告拦截、密码管理器
 * 3. 失败时**不静默** —— 给出可操作提示，避免又变成死入口
 */
private fun openExternal(context: android.content.Context, label: String) {
    // 第三方客户端没有自己的官网，这些链接指向 B 站官方页面
    val url = when (label) {
        "友情链接" -> "https://www.bilibili.com"
        "开源社区" -> "https://github.com"
        "关于我们" -> "https://www.bilibili.com/video/BV1GJ411x7h7"
        "反馈建议" -> "https://www.bilibili.com"
        else -> "https://www.bilibili.com"
    }
    openExternalUrl(context, url)
}

/** 打开任意外链（失败时提示，不静默）。 */
private fun openExternalUrl(context: android.content.Context, url: String) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse(url),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            android.widget.Toast
                .makeText(context, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT)
                .show()
        }
}
