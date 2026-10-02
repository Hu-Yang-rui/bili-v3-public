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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

        // ⚠️ 状态栏图标颜色**不做全局覆盖**，交给主题
        // （浅色主题 windowLightStatusBar=true → 深色图标；
        //  深色主题 false → 浅色图标）。绝大多数页面是浅色底，
        // 主题的默认选择就是对的。
        //
        // 只有**视频详情页**顶部是纯黑（播放器 + 安全区），那里需要浅色图标 ——
        // 由该页面自己按需切换（见 VideoDetailScreen 的 PlayerSafeAreaTop），
        // 离开时还原，避免污染其他页面。

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

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：上滑 / 按 Home 自动进小窗，无需用户点按钮
            builder.setAutoEnterEnabled(true)
            // 小窗内可用的快捷操作（系统会渲染在 PiP 窗口上）
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
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
            container.playerHolder.player?.let { p ->
                if (!p.isReleased) p.pause()
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

    // ⚠️ 主题模式来自设置（三选一：跟随系统 / 强制深色 / 强制浅色）。
    //
    // 之前 `BiliTheme` **只认 `isSystemInDarkTheme()`**，用户无法在应用内切换
    // —— 这是「深色模式三选一」被判为缺失的直接原因。
    // 首帧用 `Settings()` 的默认值（跟随系统），DataStore 到位后自动重绘，
    // 不会闪白（`values-night` 已处理窗口背景）。
    val settings by container.settingsStore.settings
        .collectAsStateWithLifecycle(initialValue = com.example.biliv3.data.Settings())

    val darkTheme = when (settings.themeMode) {
        com.example.biliv3.data.ThemeMode.Dark -> true
        com.example.biliv3.data.ThemeMode.Light -> false
        com.example.biliv3.data.ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    BiliTheme(windowSize = windowSize, darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            MainShell(
                windowSize = windowSize,
                container = container,
                isInPip = pipState.value,
            )
        }
    }
}
