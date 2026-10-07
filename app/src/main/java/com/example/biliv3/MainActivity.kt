package com.example.biliv3

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.WindowSize
import com.example.biliv3.nav.MainShell

/**
 * 入口 Activity。
 *
 * ## 断点判定
 *
 * 用 `LocalConfiguration.screenWidthDp` 判定：
 *   - ≥1280 → Desktop（5 列 + 右侧栏）
 *   - 768–1279 → Tablet（3 列，右侧栏下移）
 *   - <768 → Mobile（2 列 + 底部导航）
 *
 * 这是**最小宽度**而非物理宽度 —— 折叠屏展开、平板分屏都能正确响应。
 *
 * ## 职责边界
 *
 * Activity 只做三件事：判定断点、装配主题、**托管 PiP 小窗**。
 * 导航与页面组织在 [MainShell] 里。
 *
 * ## PiP（画中画）支持
 *
 * 关键点有三个，缺一个都会出问题：
 *
 * 1. **`configChanges` 必须包含 `screenSize|smallestScreenSize|screenLayout`**
 *    （见 AndroidManifest）。否则进出 PiP 会**重建 Activity** ——
 *    播放器、页面状态全丢，表现为"进小窗就重新开始加载"。
 * 2. **播放器必须活在 Activity 之上**（[AppContainer.playerHolder]）。
 *    页面 Composable 在 PiP 下可能被销毁，播放器不能被它带走。
 * 3. **`autoEnterEnabled`**（Android 12+）让系统在按 Home / 上滑时
 *    **自动**进小窗，不需要我们拦截 —— 这是官方推荐的体验。
 *    低版本只能靠用户点按钮触发 [enterPip]。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {

    /**
     * 应用级依赖容器。
     *
     * 在 Activity 创建时初始化，持有到进程结束。
     * **必须全应用唯一** —— 登录态的载体是 Cookie，
     * 而 Cookie 由 OkHttpClient 的 CookieJar 持有；
     * 多个容器 = 多份 CookieJar = "登录成功了但哪里都没登录"。
     */
    private lateinit var container: AppContainer

    /**
     * 当前是否处于 PiP 模式。
     *
     * 用 Compose 的 `mutableStateOf` 暴露给 UI，让播放页据此
     * **隐藏非必要控件**（官方在 PiP 下只保留播放/暂停）。
     */
    private val pipState = androidx.compose.runtime.mutableStateOf(false)

    /** 当前是否在 PiP 中（供 UI 读取）。 */
    val isInPip: Boolean get() = pipState.value

    /**
     * 是否**已请求**进入 PiP（不等系统回调）。
     *
     * ## 为什么需要这个（"点小窗后视频暂停"的根因）
     *
     * 进 PiP 的系统回调顺序是：
     * ```
     * enterPictureInPictureMode()
     *   → onStop()                              ← 此时 isInPip 还是 false
     *   → onPictureInPictureModeChanged(true)   ← 现在才 true
     * ```
     *
     * 所以 `onStop()` 里只看 `isInPip` 会误判为"退出前台"，把播放暂停掉。
     * 本标记在**发起**时立刻置位，挡住这次误暂停。
     */
    private var pipRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(this)
        enableEdgeToEdge()

        // v1.3.0：连接 MediaSessionService。
        //
        // ⚠️ 必须在 onCreate 里连、**只连一次** —— `MediaController` 是
        // 跨进程代理，重复 build 会拿到多个 controller，各自持有
        // 一份状态，通知栏按钮就会出现"点一次动两下"。
        //
        // 连不上也不影响前台播放（`handoffToService` 会返回 false），
        // 所以这里不检查结果、不提示用户。
        runCatching { container.mediaSessionBridge.connect() }

        /**
         * 补齐设备指纹 `buvid3`（v1.6.7 修的真实 bug）。
         *
         * ## 为什么放在启动时
         *
         * `buvid3` 是**设备指纹**，不在用户粘贴的 Cookie 串里。
         * 缺它时：**读操作全部正常**（首页/视频/余额），
         * **写操作全部 `-401 非法访问`**（投币/点赞/收藏/关注/评论）——
         * 模拟器实测确认过。
         *
         * 此前 `fetchBuvid()` 只被 `LoginViewModel` 调用（扫码路径），
         * 所以：
         * - 用 `importCookie` 登录的老账号 → **永远没有 buvid3** → 写操作全坏
         * - 已经登录的账号（本次修好之前登的）→ 也不会自己补上
         *
         * 所以这里在启动时**自愈一次**。
         *
         * ## 为什么是裸 `launch` 而不是 `repeatOnLifecycle`
         *
         * 这是**一次性动作**，不是状态订阅。`repeatOnLifecycle` 会在每次
         * 回到前台时**重跑**一遍 —— 对一个已经补好的指纹来说是无谓的网络请求。
         * 上面两个订阅用它是对的（它们确实要持续收集），这里不是。
         *
         * ## 为什么不阻塞启动
         *
         * 它是网络请求，等它会让冷启动多一个 RTT。用户真正发写请求时
         * 通常已经补好了；就算还没好，那次请求会失败并**如实报错**。
         */
        lifecycleScope.launch {
            runCatching {
                val store = container.authStore
                if (store.isLoggedIn && !store.hasBuvid) {
                    container.authRepository.fetchBuvid()
                }
            }
        }

        // 状态栏图标颜色：**全应用恒为浅色图标**（配深色底）。
        //
        // 由 `values/themes.xml` + `values-night/themes.xml` 的
        // `windowLightStatusBar=false` 声明（两份内容相同 —— 因为
        // 系统主题决定选哪份，而应用自身恒为深色）。
        //
        // 视频详情页顶部是纯黑安全区，同样需要浅色图标，
        // 那里会再显式压一次（见 `VideoDetailScreen.PlayerSafeAreaTop`）。

        /**
         * 设备性能档位。
         *
         * 只探测**一次**（读系统服务，不适合反复执行），之后全程不变。
         * 由 `BiliApp` 通过 `LocalDeviceTier` 注入，驱动视觉降级：
         * 低端关模糊与微光、中端降模糊半径、高端满配。
         *
         * 在 `onCreate` 里算而不是放进 Composable —— 后者每次重组都会读一次
         * `ActivityManager`。
         */
        deviceTier = com.example.biliv3.design.detectDeviceTier(this)

        /**
         * 订阅「退出后自动小窗」设置（v1.4.2，v1.5.1 补 PiP 参数同步）。
         *
         * 用 `repeatOnLifecycle(STARTED)` 而不是裸 `launch` 收集 ——
         * 后者在 Activity 进后台时仍在收集，白白占用资源。
         *
         * 缓存到 [autoPipEnabled]：`onUserLeaveHint()` 是同步回调，
         * 来不及等 DataStore（详见该字段的说明）。
         *
         * 🔴 v1.5.1：**同时刷新系统侧 PiP 参数** ——
         * `setAutoEnterEnabled` 是持久状态，不重设的话关掉开关也不生效
         * （"无论开关怎么设，退出都弹小窗"的根因）。
         */
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settingsStore.settings.collect { s ->
                    autoPipEnabled = s.autoPip
                    refreshPipParams()
                }
            }
        }

        setContent {
            CompositionLocalProvider(
                com.example.biliv3.design.LocalDeviceTier provides deviceTier,
            ) {
                BiliApp(container, pipState)
            }
        }
    }

    /** 设备性能档位，启动时探测一次。 */
    private var deviceTier: com.example.biliv3.design.DeviceTier =
        com.example.biliv3.design.DeviceTier.High

    /**
     * 请求进入 PiP。
     *
     * @return 是否成功发起（设备不支持 / 参数非法时返回 false）
     *
     * 宽高比用 **16:9** 而不是当前画面比例 ——
     * 竖屏视频若按 9:16 请求，在某些 ROM 上会得到一个极窄的窗口，
     * 反而看不清。16:9 是通用且安全的选择（官方也是这个比例）。
     */
    fun enterPip(): Boolean {
        if (!supportsPip()) return false

        // ⚠️ 在**发起前**置位：onStop() 会先于 PiP 回调执行，
        // 那时若没有这个标记会把播放误暂停（小窗里显示暂停按钮）。
        pipRequested = true

        // 确保进小窗前是**播放中** —— 用户点小窗就是想"继续看"。
        // 若此时恰好暂停着，小窗会是一个静止画面，不符合预期。
        container.playerHolder.player?.let { p ->
            if (!p.isReleased) p.play()
        }

        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(buildPipParams())
            } else {
                false
            }
        }.getOrDefault(false)

        // 发起失败（设备不支持 / 参数非法）要把标记清掉，
        // 否则之后真的退到后台时不会暂停，声音会在后台一直响。
        if (!ok) pipRequested = false
        return ok
    }

    /** 设备 / 系统是否允许 PiP。 */
    private fun supportsPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * 构建 PiP 参数。
     *
     * ## 🔴 `setAutoEnterEnabled` 必须跟设置开关联动（v1.5.1 修的真实 bug）
     *
     * 原实现无条件 `setAutoEnterEnabled(true)` —— 这是**系统级**的"上滑/按 Home
     * 自动进小窗"开关，**完全绕过 `autoPipEnabled`**。
     *
     * 后果正是用户报告的：「无论设置开关是否开启，退出 APP 都会出现小窗」。
     * 因为用户按 Home 时是**系统**主动把小窗弹出来，我们的 `onUserLeaveHint`
     * 里那层 `if (!autoPipEnabled) return` 根本没机会执行。
     *
     * ⚠️ 关键细节：`setAutoEnterEnabled` 是**持久状态** —— 系统记住最后一次
     * 设置的值，不是"每次进 PiP 才读"。所以关闭开关时必须**显式传 false**
     * 把它关掉，否则上一次的 true 会一直生效。
     *
     * 判据：`autoPipEnabled == true` → 允许系统自动进；
     * `false` → 明确禁止（用户要的是"退出就停"，不是"退出变小窗"）。
     */
    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ⚠️ 跟开关走，不能写死 true —— 见上面的 KDoc
            builder.setAutoEnterEnabled(autoPipEnabled)
            // 小窗内可用的快捷操作（系统会渲染在 PiP 窗口上）
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /**
     * 设置开关变化时**同步刷新** PiP 参数。
     *
     * ## 为什么必须单独做这件事
     *
     * `setAutoEnterEnabled` 只在 `setPictureInPictureParams()` 被调用时
     * 才写进系统。用户关掉开关后如果只是改了内存里的 `autoPipEnabled`，
     * **系统那边还是旧值** —— 表现为"关了开关，按 Home 还是弹小窗"。
     *
     * 所以订阅到设置变化时立刻重设一次参数（`setPictureInPictureParams`
     * 不要求 Activity 在前台，后台调用也生效）。
     */
    private fun refreshPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!supportsPip()) return
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    /**
     * PiP 状态变化回调。
     *
     * ⚠️ 这是**唯一可靠**的 PiP 状态来源 ——
     * 不要用 `onResume/onPause` 判断，PiP 下 Activity 仍是 resumed 状态。
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipState.value = isInPictureInPictureMode
        // 退出小窗后清掉"已请求"标记，让后续的 onStop 恢复正常语义
        // （真的退到后台时应当暂停，而不是因为残留标记而不暂停）。
        if (!isInPictureInPictureMode) {
            pipRequested = false
        }
    }

    /**
     * 用户点 PiP 窗口的 ✕ 关闭时的回调（Android 12+）。
     *
     * 官方语义：PiP 被关闭**不等于**用户要停止播放 ——
     * 系统只是把窗口收起来了。此时应当**暂停**而不是 release，
     * 这样用户从最近任务切回来还能接着看。
     */
    override fun onStop() {
        super.onStop()
        // 离开前台且不在 PiP：暂停播放（避免后台偷偷放声音/耗流量）。
        // 在 PiP 中时**不停**，那正是"小窗继续播"的语义。
        //
        // ⚠️ 必须同时看 `isInPip` 与 `pipRequested`（这是"点小窗后暂停"的根因）。
        //
        // 时序问题：点小窗 → `enterPictureInPictureMode()` →
        // 系统**先**调 `onStop()`，**之后**才调
        // `onPictureInPictureModeChanged(true)`。
        //
        // 因此 `onStop()` 执行时 `isInPip` 仍是 false，于是无条件 pause ——
        // 小窗刚建好就是暂停态、还显示播放三角（正是用户看到的现象）。
        //
        // `pipRequested` 在**发起**进小窗时立刻置 true，不等回调，
        // 用它挡住这次误暂停。
        if (!isInPip && !pipRequested) {
            // v1.3.0：如果播放已交给 Service（听视频模式），
            // **不要**暂停前台 —— 那会让"后台继续听"失效。
            //
            // ⚠️ 交接后前台 holder 已经被 pause 过（见 `handoffToService`），
            // 所以这里跳过不会漏暂停；而 Service 侧的播放是**故意**要继续的。
            val serviceOwns = runCatching {
                container.playbackController.isServicePlaying()
            }.getOrDefault(false)

            if (!serviceOwns) {
                container.playerHolder.player?.let { p ->
                    if (!p.isReleased) p.pause()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity 真正销毁（用户划掉应用）时才释放播放器
        if (isFinishing) {
            container.playerHolder.release()
        }
    }

    /**
     * 「退出 App 后自动进入小窗」的缓存值（v1.4.2 新增）。
     *
     * ## 为什么缓存而不是每次读 DataStore
     *
     * `onUserLeaveHint()` 是**同步**回调，必须在其中立刻决定是否
     * `enterPictureInPictureMode()`；而读 DataStore 是挂起操作，
     * 等它返回时用户已经离开、PiP 时机已过（系统会拒绝）。
     *
     * 所以启动时读一次、订阅变化更新缓存，回调里只读这个字段。
     * 用 `repeatOnLifecycle(STARTED)` 订阅，避免后台无谓收集。
     */
    @Volatile
    private var autoPipEnabled: Boolean = false

    /**
     * 用户**主动**离开 App（按 Home / 最近任务 / 手势回桌面）时触发。
     *
     * ## 为什么用 `onUserLeaveHint` 而不是 `onStop`
     *
     * `onStop` 也会在**锁屏、切到另一个 App、被系统回收**时触发，
     * 那些场景自动弹小窗是打扰（尤其锁屏时弹一个视频窗口很怪）。
     * `onUserLeaveHint` 只在"用户主动离开"时调用，语义正好匹配
     * 「退出 App 后自动进入小窗」。
     *
     * ## 不与手动小窗冲突
     *
     * - 已经在小窗里（`isInPip`）：不重复请求
     * - 已经在请求中（`pipRequested`）：不重复请求
     * - 没有播放中的播放器：不请求（否则小窗是一片黑）
     * - 听视频交给 Service 时**不请求** —— 那种情况用户要的是
     *   "后台听"，不是"弹出画面"
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (!autoPipEnabled) return
        if (isInPip || pipRequested) return
        if (!supportsPip()) return

        // 只有真正在播、且前台持有播放器时才值得弹小窗。
        // 交给 Service（听视频）时不弹：那是纯音频场景，弹画面无意义。
        //
        // ⚠️ 用 `player == null` 判空即可，**不要读 `player.isReleased`** ——
        // 那是 Media3 的 `@UnstableApi`，在 MainActivity 里引用会让 lint
        // 多报一条 `UnsafeOptInUsageError`（本文件没有类级 @OptIn）。
        // 拿不到实例（holder 已释放 / 从未创建）时 `player` 本身就是 null，
        // 语义足够。
        val player = container.playerHolder.player
        if (player == null) return

        runCatching { enterPip() }
    }
}

@Composable
private fun BiliApp(
    container: AppContainer,
    pipState: androidx.compose.runtime.MutableState<Boolean>,
) {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp

    val windowSize = when {
        widthDp >= 1280 -> WindowSize.Desktop
        widthDp >= 768 -> WindowSize.Tablet
        else -> WindowSize.Mobile
    }

    // 🔴 主题恒为深色（v1.1.3 起移除浅色主题）。
    //
    // 浅色主题下的"玻璃"在静态页上是假的（背后是纯色底，没东西可模糊），
    // 详见 `design/BiliTheme.kt` 的 KDoc。
    //
    // 因此不再读 `settings.themeMode` —— 用户没有可选项。
    // 注意：`Settings.themeMode` 字段本身保留（兼容旧数据），
    // 但**不再有任何消费者**。
    BiliTheme(windowSize = windowSize) {
        Surface(modifier = Modifier.fillMaxSize()) {
            MainShell(
                windowSize = windowSize,
                container = container,
                isInPip = pipState.value,
            )
        }
    }
}
