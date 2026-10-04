package com.example.biliv3

import android.content.Context
import com.example.biliv3.data.BangumiRepository
import com.example.biliv3.data.CommentRepository
import com.example.biliv3.data.PmRepository
import com.example.biliv3.data.FavoritesSync
import com.example.biliv3.data.HomeRepository
import com.example.biliv3.data.LibraryRepository
import com.example.biliv3.data.RankingRepository
import com.example.biliv3.data.InteractionRepository
import com.example.biliv3.data.SearchHistoryStore
import com.example.biliv3.data.SettingsStore
import com.example.biliv3.data.VideoRepository
import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.auth.AuthCookieJar
import com.example.biliv3.data.auth.AuthRepository
import com.example.biliv3.data.auth.AuthStore
import com.example.biliv3.data.danmaku.DanmakuRepository
import com.example.biliv3.data.download.DownloadStore
import com.example.biliv3.data.download.VideoDownloader
import com.example.biliv3.data.subtitle.SubtitleRepository
import com.example.biliv3.player.PlayerHolder
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 应用级依赖容器（手写 DI）。
 *
 * ## ⚠️ 为什么必须是单例
 *
 * 登录态的载体是 **Cookie**，而 Cookie 由 `OkHttpClient` 的 `CookieJar` 持有。
 *
 * 早期每个 ViewModel 各自 `BiliApi()`，等于各自持有一个 CookieJar ——
 * 后果是：
 * 1. 登录页拿到的 `SESSDATA` 只存在于那一个实例里
 * 2. 首页/搜索/详情用的是**另一个**实例，仍然是未登录
 * 3. 表现就是"登录成功了但哪里都没登录"
 *
 * 所以全应用必须共用**同一个** OkHttpClient → 同一个 CookieJar → 同一份登录态。
 *
 * ## 为什么不用 Hilt
 *
 * 单用户项目、依赖图只有这一层，手写容器就这几行。
 * Hilt 会引入注解处理器显著拖慢构建（当前已有 KSP 在跑），收益不抵成本
 * （见 `AGENTS.md` §3 架构约定）。
 *
 * ## 生命周期
 *
 * 由 `MainActivity` 在 `onCreate` 创建，持有到进程结束。
 * 传 `applicationContext` 避免持有 Activity。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** 加密凭据存储（SESSDATA 走 Android Keystore）。 */
    val authStore: AuthStore = AuthStore(appContext)

    /**
     * 多账号列表存储（同样加密）。
     *
     * 与 [authStore] 分工：[authStore] 存"**当前**生效账号"，
     * 本类存"曾经登录过的**全部**账号"，供「切换账号」使用。
     */
    val accountStore: com.example.biliv3.data.auth.AccountStore =
        com.example.biliv3.data.auth.AccountStore(appContext)

    /**
     * 账号切换的全局广播。
     *
     * 切号后所有展示登录态数据的页面（首页推荐 / 收藏 / 历史 /
     * 消息 / 私信 / 个人中心…）订阅它并重新加载，避免旧账号数据残留。
     */
    val accountSync: com.example.biliv3.data.auth.AccountSync =
        com.example.biliv3.data.auth.AccountSync()

    /** 账号切换器：多账号读写的唯一入口（保证"先存旧、再写新、后广播"顺序）。 */
    val accountSwitcher: com.example.biliv3.data.auth.AccountSwitcher =
        com.example.biliv3.data.auth.AccountSwitcher(authStore, accountStore, accountSync)

    /**
     * CookieJar：读写均落到 [AuthStore]，登录态跨重启保持。
     *
     * `AGENTS.md` §3.2 验收标准要求「重启 App 仍是登录态」。
     */
    private val cookieJar = AuthCookieJar(authStore)

    /** 全应用唯一的 OkHttp 客户端。 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cookieJar(cookieJar)
        .build()

    /** 全应用唯一的 API 客户端。 */
    val api: BiliApi = BiliApi(client)

    /** 直播列表（独立域名 api.live.bilibili.com）。 */
    val liveRepository: com.example.biliv3.data.LiveRepository =
        com.example.biliv3.data.LiveRepository(api)

    /**
     * 主页数据。
     *
     * ⚠️ 注入 [liveRepository] 是为了让右侧栏的「正在直播」有真实数据 ——
     * 此前它恒为空列表（硬编码），整块永不渲染。
     */
    val homeRepository: HomeRepository = HomeRepository(api, liveRepository)
    val videoRepository: VideoRepository = VideoRepository(api)
    val authRepository: AuthRepository = AuthRepository(api, authStore)
    val searchHistoryStore: SearchHistoryStore = SearchHistoryStore(appContext)

    /**
     * 应用设置（持久化）。
     *
     * 全应用唯一 —— 设置需要跨页面共享：
     * 播放器读倍速、详情页读弹幕开关、设置页写。
     * 放在某个页面的 ViewModel 里其他页面就拿不到。
     */
    val settingsStore: SettingsStore = SettingsStore(appContext)

    /** 互动仓库（点赞/投币/收藏/分享）。需要 AuthStore 取 csrf。 */
    val interactionRepository: InteractionRepository = InteractionRepository(api, authStore)

    /**
     * 字幕 / AI 翻译仓库。
     *
     * 走 gRPC（`bilibili.subtitle.Subtitle/SubtitleView`）拿 AI 翻译轨，
     * 失败时回退 REST（`x/player/wbi/v2` 的 `subtitle.subtitles[]`）。
     */
    val subtitleRepository: SubtitleRepository = SubtitleRepository(api)

    /**
     * 弹幕仓库。
     *
     * `x/v2/dm/web/seg.so` 返回 **protobuf**（实测未压缩，不是 deflate），
     * 按 6 分钟一片懒加载。
     *
     * ## ⚠️ 必须传共享 [client]
     *
     * 发弹幕（`x/v2/dm/post`）需要 cookie 里的 `SESSDATA`。
     * 首版这里没传 client，`DanmakuRepository` 会自己 new 一个
     * **不带 CookieJar** 的实例 → 请求无 cookie → 服务端返回
     * `-101 账号未登录` → 表现为"发弹幕点了没反应"。
     */
    val danmakuRepository: DanmakuRepository = DanmakuRepository(
        client = client,
        store = authStore,
    )

    /** 评论仓库。x/v2/reply/wbi/main 实测未登录也可读(code=0)。 */
    /**
     * 评论仓库。读评论未登录可用；点赞/删除/回复需要登录态
     * （写操作要 csrf），所以传入 authStore。
     */
    val commentRepository: CommentRepository = CommentRepository(api, authStore)

/**
 * 私信仓库。
 *
 * ⚠️ 私信接口在 `api.vc.bilibili.com`（与主 API 不同域名），
 * 且发送需要 WBI 签名 —— 详见 PmRepository 的说明。
 */
val pmRepository: PmRepository = PmRepository(api, authStore)

    /** 历史 / 稍后再看 / 收藏夹。全部需要登录。 */
    val libraryRepository: LibraryRepository = LibraryRepository(api, authStore)

    /**
     * 收藏状态全局广播。
     *
     * 全应用唯一 —— 收藏动作发生在详情页 / 收藏列表，
     * 而状态影响收藏列表 / 我的页 / 详情页互动栏。
     * 没有这个广播就会出现"在 A 处收藏了，B 处不更新"的孤立感。
     */
    val favoritesSync: FavoritesSync = FavoritesSync()

    /**
     * 应用级播放器持有者。
     *
     * ⚠️ **不再是页面级** —— PiP 小窗 / 切页继续播放 / 锁屏播控
     * 都要求播放器活得比页面久（详见 [PlayerHolder] 的说明）。
     *
     * 传 `appContext`：PlayerHolder 持有播放器，不能连带持有 Activity。
     */
    val playerHolder: PlayerHolder = PlayerHolder(appContext)

    /**
     * 歌词仓库（v1.3.0）。
     *
     * ## Provider 顺序即优先级
     *
     * 1. `SubtitleLyricsProvider` —— 复用现有 `subtitleRepository`，
     *    时间轴与视频严格对齐，且走自家 API（不泄露 Cookie）
     * 2. （将来）第三方歌词源 / 本地 `.lrc` 文件
     *
     * ⚠️ 第三方源**必须**用独立 OkHttpClient（§4.2 红线），
     * 绝不能复用 [client]（它挂着 B 站 CookieJar）。
     */
    val lyricsRepository: com.example.biliv3.data.lyrics.LyricsRepository =
        com.example.biliv3.data.lyrics.LyricsRepository(
            providers = listOf(
                com.example.biliv3.data.lyrics.SubtitleLyricsProvider(subtitleRepository),
            ),
        )

    /**
     * 统一播放控制器（v1.3.0）—— 队列 / 模式 / 定时器 / 歌词的唯一入口。
     *
     * ⚠️ 它**不创建** ExoPlayer，所有播放都经过 [playerHolder]
     * （任务书 §22「禁止创建第二套 Player」）。
     *
     * `scope` 用 `MainScope()`：控制器与 Activity 同寿命，
     * 定时器轮询不会在 Activity 销毁后继续跑。
     */
    val playbackController: com.example.biliv3.player.PlaybackController =
        com.example.biliv3.player.PlaybackController(
            context = appContext,
            holder = playerHolder,
            scope = kotlinx.coroutines.MainScope(),
            lyricsRepository = lyricsRepository,
        )

    /**
     * 系统媒体中心桥（v1.3.0）。
     *
     * 连接 [com.example.biliv3.player.PlaybackService] ——
     * 锁屏 / 通知栏 / 耳机按键通过它控制播放。
     */
    val mediaSessionBridge: com.example.biliv3.player.MediaSessionBridge =
        com.example.biliv3.player.MediaSessionBridge(appContext)

    /**
     * 插件管理器（v1.3.0）。
     *
     * ## 权限隔离的关键：只传 `PluginHost` 而不是整个 container
     *
     * 插件通过 `PluginContext` 访问能力，而 `PluginContext` 的每个方法
     * 都从 [pluginHost] 取数据 —— **不是**从这里直接取。
     *
     * 这样插件能做的事被限制在 `PluginHost` 接口的方法里，
     * **编译期**就挡住了"偷偷拿 Repository / Cookie"（§15 红线）。
     */
    private val pluginHost: com.example.biliv3.plugin.PluginHost =
        object : com.example.biliv3.plugin.PluginHost {
            override fun playbackSnapshot(): com.example.biliv3.plugin.PluginPlaybackSnapshot? {
                val s = playbackController.state.value
                val item = s.currentItem ?: return null
                return com.example.biliv3.plugin.PluginPlaybackSnapshot(
                    title = item.title,
                    author = item.author,
                    positionMs = playbackController.positionMs(),
                    durationMs = playbackController.durationMs(),
                    isPlaying = s.isPlaying,
                )
            }

            override fun controlPlayback(
                command: com.example.biliv3.plugin.PluginPlaybackCommand,
            ): Boolean {
                when (command) {
                    com.example.biliv3.plugin.PluginPlaybackCommand.PLAY ->
                        playbackController.play()
                    com.example.biliv3.plugin.PluginPlaybackCommand.PAUSE ->
                        playbackController.pause()
                    com.example.biliv3.plugin.PluginPlaybackCommand.NEXT ->
                        playbackController.next(userInitiated = true)
                    com.example.biliv3.plugin.PluginPlaybackCommand.PREVIOUS ->
                        playbackController.previous()
                    com.example.biliv3.plugin.PluginPlaybackCommand.STOP -> {
                        playbackController.pause()
                        playbackController.queue.clear()
                    }
                }
                return true
            }

            override fun queueSnapshot(): List<com.example.biliv3.plugin.PluginQueueItem> =
                playbackController.queue.items.value.map {
                    com.example.biliv3.plugin.PluginQueueItem(
                        key = it.key,
                        title = it.title,
                        author = it.author,
                    )
                }

            override fun modifyQueue(
                action: com.example.biliv3.plugin.PluginQueueAction,
            ): Boolean = when (action) {
                is com.example.biliv3.plugin.PluginQueueAction.Add -> {
                    val item = com.example.biliv3.player.QueueItem(
                        bvid = action.item.key,
                        title = action.item.title,
                        author = action.item.author,
                    )
                    if (action.toNext) {
                        playbackController.queue.playNext(item)
                    } else {
                        playbackController.queue.add(item)
                    }
                    true
                }
                is com.example.biliv3.plugin.PluginQueueAction.Remove ->
                    playbackController.queue.remove(action.key)
                com.example.biliv3.plugin.PluginQueueAction.Clear -> {
                    playbackController.queue.clear()
                    true
                }
            }

            override fun storageDirFor(pluginId: String): java.io.File {
                // 每个插件独立子目录，且**在 App 私有目录内** ——
                // 插件拿不到别的插件的文件，也拿不到 App 数据目录
                val safe = pluginId.replace(Regex("[^A-Za-z0-9._-]"), "_")
                return java.io.File(appContext.filesDir, "plugins/$safe").apply {
                    if (!exists()) mkdirs()
                }
            }
        }

    /** 插件管理器。 */
    val pluginManager: com.example.biliv3.plugin.PluginManager =
        com.example.biliv3.plugin.PluginManager(appContext, pluginHost).apply {
            // ⚠️ 必须恢复已安装的插件 —— 否则 App 重启后插件列表为空
            // （装机实测确认过这个 bug）。放在 AppContainer 初始化里，
            // 保证任何入口进插件页时列表已经就绪。
            runCatching { restoreInstalled() }
        }

    /** 离线缓存索引（元数据）。 */
    val downloadStore: DownloadStore = DownloadStore(appContext)

    /** 视频下载器（断点续传）。 */
    val videoDownloader: VideoDownloader =
        VideoDownloader(appContext, api, downloadStore)

    /**
     * 播放进度持久化（在线播放的断点续播）。
     *
     * ⚠️ 与 [downloadStore] 的 `lastPositionMs` 是**两件事**：
     * 那个只管离线缓存视频，这个管所有在线播放。
     * 首版只有前者，所以在线播放退出后进度全丢。
     */
    val playbackProgress: com.example.biliv3.data.PlaybackProgressStore =
        com.example.biliv3.data.PlaybackProgressStore(appContext)

    /** 用户主页（资料 / 投稿 / 关注）。 */
    val spaceRepository: com.example.biliv3.data.SpaceRepository =
        com.example.biliv3.data.SpaceRepository(api, authStore)

    /** 动态流。需要登录。 */
    val dynamicRepository: com.example.biliv3.data.DynamicRepository =
        com.example.biliv3.data.DynamicRepository(api, authStore)

    /** 分区页（最新 / 热门）。 */
    val categoryRepository: com.example.biliv3.data.CategoryRepository =
        com.example.biliv3.data.CategoryRepository(api)

    /** 排行榜。全站榜失败时自动降级到分区榜。 */
    val rankingRepository: RankingRepository = RankingRepository(api)

    /** 番剧 / 影视索引。pgc/season/index/result 实测可用。 */
    val bangumiRepository: BangumiRepository = BangumiRepository(api)

    /**
     * App 端鉴权串（gRPC 用），形如 `identify_v1 <access_key>`。
     *
     * 当前恒为空 —— 未实现 App 端 access_key 换取。此时 gRPC 返回 `-400`，
     * 会自动回退到 REST 路径。补上 access_key 后 AI 翻译轨即可经 gRPC 获取。
     */
    val appAuthHeader: String get() = ""

    // ---------------- aicu.cc（第三方查成分）----------------

    /**
     * aicu API 客户端。
     *
     * ## 🔴 为什么它**不用**共享 [client]
     *
     * 共享 client = 共享 [cookieJar] = **把 B 站 `SESSDATA` / `bili_jct`
     * 明文发给第三方站点**。这是本项目最容易犯的严重错误，
     * 而且方向与 `DanmakuRepository` 当年的坑正好相反：
     *
     * | | 坑 | 后果 |
     * |---|---|---|
     * | `DanmakuRepository` | **忘了**传共享 client | 没 cookie → 写操作 `-101` |
     * | 本处 | **传了**共享 client | cookie 外泄给第三方 |
     *
     * 所以这里用 [com.example.biliv3.data.api.AicuApi.defaultClient]：
     * 不挂 CookieJar、自带 [com.example.biliv3.data.api.AicuDns]
     * 接管 DNS 解析（绕开投毒直连真实 IP）。
     */
    val aicuApi: com.example.biliv3.data.api.AicuApi =
        com.example.biliv3.data.api.AicuApi()

    /** aicu 查成分仓库（评论 / 视频弹幕 / 直播弹幕 / 用户成分）。 */
    val aicuRepository: com.example.biliv3.data.AicuRepository =
        com.example.biliv3.data.AicuRepository(aicuApi)

    // ---------------- 竖屏观看模式 ----------------

    /**
     * 竖屏观看模式数据源。
     *
     * ⚠️ 必须复用共享 [api] 与 [videoRepository] ——
     * 探测"是否竖屏"要逐条查详情取真实分辨率
     * （没有任何接口能直接给竖屏列表，见该类注释），
     * 那些请求需要登录态。
     */
    /**
     * 空降助手（第三方 bsbsb.top 的可跳过片段）。
     *
     * ⚠️ 它用**独立 client**（不挂 CookieJar）—— 第三方站点绝不能
     * 拿到 B 站登录凭据（AGENTS.md §4.2 红线）。
     */
    val sponsorBlockRepository: com.example.biliv3.data.SponsorBlockRepository =
        com.example.biliv3.data.SponsorBlockRepository()

    val verticalFeedRepository: com.example.biliv3.data.VerticalFeedRepository =
        com.example.biliv3.data.VerticalFeedRepository(api, videoRepository)
}
