package com.example.biliv3.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.example.biliv3.data.model.PlayInfo

/**
 * 应用级播放器持有者（**跨页面 / 跨 PiP 存活**）。
 *
 * ## ⚠️ 为什么必须提升到这一层（PiP 的硬前提）
 *
 * 原先 `ExoPlayer` 建在 `VideoDetailScreen` 的 Composable 里，
 * 用 `DisposableEffect(Unit) { onDispose { player.release() } }` 释放 ——
 * 也就是**页面销毁即释放播放器**。
 *
 * 这与以下需求直接冲突：
 *
 * | 需求 | 为什么冲突 |
 * |---|---|
 * | PiP 小窗 | 进 PiP 时 Activity 进后台，但页面 Composable 可能被销毁 → 播放器被 release |
 * | 切换页面继续播 | 离开详情页即销毁 → 声音断掉 |
 * | 锁屏播控 | 需要有稳定的 Player 实例持有 MediaSession |
 *
 * 所以播放器必须由**比页面更长命**的对象持有。
 *
 * ## 为什么不放 AppContainer（进程级）
 *
 * 进程级会导致「退出所有界面后播放器仍活着」→ 声音停不下来、泄漏解码器。
 * Activity 级是恰当的生命周期：用户离开应用（Activity 销毁）就释放。
 *
 * ## 为什么不直接上 MediaSessionService
 *
 * 那是"后台播放 + 通知栏控制"的完整方案，需要前台服务 + 通知权限。
 * 本项目的 PiP 需求（应用可见但非全屏时继续播）用 Activity 级足够。
 * 将来若要真正的后台播放，再把本类换成 Service 绑定即可 —— 调用方接口不变。
 *
 * ## 单例语义
 *
 * 同一时刻只应有一个播放器（一个视频在播）。`acquire()` 复用已有实例，
 * 只在需要时重建，避免"每个视频页各建一个"导致的多路解码器。
 */
class PlayerHolder(private val context: Context) {

    /**
     * 当前播放器。null = 尚未创建。
     *
     * 用 `mutableStateOf` 让 Compose 能观察到"播放器被创建/销毁"，
     * 页面重建（如 PiP 进出）时能重新绑定到同一个实例。
     */
    var player: ExoPlayer? by mutableStateOf(null)
        private set

    /** 播放器错误回调（页面订阅，用于展示错误态）。 */
    var onError: ((PlaybackException) -> Unit)? = null

    /**
     * 当前装配的流信息（用于判断"是否同一个流"）。
     *
     * 切清晰度 / 切分P / 换视频时 URL 变化 → 需要重新装配 MediaSource。
     */
    var currentPlayInfo: PlayInfo? = null
        private set

    /**
     * 当前装配是否为"只听音频"。
     *
     * 与 `currentPlayInfo` 一起参与"是否需要重建"的判断 ——
     * 同一条流在看/听之间切换时视频轨有无不同，必须重建。
     */
    var currentAudioOnly: Boolean = false
        private set

    /** 当前关联的视频标识（bvid 或 ep_id 的字符串形式）。 */
    var currentKey: String? = null
        private set

    /**
     * **共享的** `TextureView` 承载容器。
     *
     * ---
     *
     * ## 为什么需要一个"跨页面复用"的 View
     *
     * 毛玻璃需要**抓视频帧**（见 `design/Glass.kt`），而抓帧要求：
     *
     * 1. 画面必须渲染在 `TextureView` 上
     *    （`SurfaceView` 的内容在独立合成层，`getBitmap()` 只能拿到黑图）
     * 2. 这个 View 必须**长期存活** —— 否则切换页面时重新创建，
     *    `TextureView` 的 Surface 会重建，画面**黑一帧**
     *
     * 所以：一个 `PlayerHolder` 只持有一个 View，所有页面共用它。
     *
     * ## 为什么不是 Compose 的 `remember`
     *
     * Compose 的 `remember` 生命周期跟随**组合**。竖屏页与详情页是
     * 两个不同的组合，各自 `remember` 会得到两个 View → 又回到
     * "两个 View 抢一个 ExoPlayer"的老问题。
     *
     * 放在 `PlayerHolder`（Activity 级单例）里，生命周期与 Activity 一致。
     */
    private var sharedTextureView: android.view.TextureView? = null

    /** 取共享 `TextureView`（懒创建）。供 `VideoPlayerSurface` 挂载、玻璃抓帧。 */
    fun obtainTextureView(): android.view.TextureView =
        sharedTextureView ?: android.view.TextureView(context).also {
            sharedTextureView = it
        }

    /**
     * 把共享 `TextureView` 挂到指定容器。
     *
     * ## ⚠️ 必须先摘再挂
     *
     * `View` 只能有一个父级。若旧宿主还没销毁就 `addView`，
     * 会抛 `IllegalStateException: The specified child already has a parent`。
     *
     * 页面切换时新旧组合可能**短暂共存**（转场期间），
     * 所以这一步不是防御性代码，而是**必需**的。
     *
     * @return 挂载后的 view（调用方通常不需要，但便于测试断言）
     */
    fun attachTextureViewTo(container: android.view.ViewGroup): android.view.TextureView {
        val v = obtainTextureView()
        // 关键：先从旧父级摘下来
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        if (container.indexOfChild(v) < 0) {
            container.addView(
                v,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        return v
    }

    /** 当前共享 View（null = 还没创建过）。玻璃抓帧用。 */
    val textureView: android.view.TextureView? get() = sharedTextureView

    /**
     * 毛玻璃用的视频帧。
     *
     * ## 为什么放在 holder 上而不是某个页面里
     *
     * 玻璃面板要在**多个页面**用（竖屏底部信息区、详情页控制层），
     * 而帧都来自**同一个**共享 `TextureView`。
     * 放在页面里会导致"每个页面各抓一份、各自过期"。
     *
     * 由 `design/Glass.kt` 的 `VideoBackdropEffect` 持续写入。
     */
    val backdrop = com.example.biliv3.design.VideoBackdrop()

    /**
     * 释放共享 View。
     *
     * ⚠️ 只在**真正离开播放场景**时调用（如 Activity 销毁）。
     * 页面间切换**不要**调 —— 那正是黑帧的来源。
     */
    fun releaseTextureView() {
        (sharedTextureView?.parent as? android.view.ViewGroup)?.removeView(sharedTextureView)
        sharedTextureView = null
    }

    /**
     * 取得播放器（不存在则创建）。
     *
     * @param key 视频标识。与 [currentKey] 不同时会**重建**播放器 ——
     *            换视频必须重建，否则会带着上一条流的缓冲状态。
     */
    fun acquire(key: String): ExoPlayer {
        val existing = player
        if (existing != null && currentKey == key && !existing.isReleased) {
            return existing
        }

        // 换视频：先释放旧的，避免解码器叠加
        releaseInternal()

        currentKey = key
        currentPlayInfo = null
        return PlayerFactory.createPlayer(context) { e ->
            onError?.invoke(e)
        }.also { player = it }
    }

    /**
     * 装配媒体源（仅在流信息变化时真正重建）。
     *
     * @param resumePositionMs 重建后要恢复到的位置（毫秒）。
     *        **听视频 ⇄ 看视频切换时必须传**，否则会从 0 重播 ——
     *        因为切换模式必然重建 MediaSource。
     * @param audioOnly 听视频模式：不装配视频轨（真省解码，见 `PlayerFactory`）
     * @return true 表示重新装配了；false 表示流未变、复用现有缓冲
     */
    fun bindMedia(
        info: PlayInfo,
        playWhenReady: Boolean = true,
        audioOnly: Boolean = false,
        resumePositionMs: Long = 0L,
    ): BindResult {
        val p = player ?: return BindResult.NoPlayer
        if (p.isReleased) return BindResult.Released

        // URL 相同视为同一条流：不要重复 setMediaSource，
        // 否则会把用户已经缓冲的进度扔掉（表现为"卡一下重头来"）
        //
        // ⚠️ audioOnly 也必须参与比较：同一条流在"看/听"之间切换时
        // 视频轨的有无不同，必须重建（否则听视频时画面还在解码）。
        //
        // 🔴 v1.5.1：**`cid` 必须参与比较**（用户反馈"选 P2/P3 还是播 P1"）。
        //
        // 只比 URL 是不够的 —— 切分P 时两个 P 有可能拿到**完全相同的 URL**
        // （同清晰度 + CDN 复用，实测会出现）。那时 `same` 为 true →
        // 返回 `Reused` → **播放器完全不换流，仍播上一个 P**。
        //
        // `cid` 是分P 的唯一标识，比 URL 可靠得多。两者都要比：
        // - 比 cid：保证"换 P 一定换流"
        // - 比 URL：保证"同 P 内重复调用不重装"（保住缓冲）
        val same = currentPlayInfo?.let {
            it.cid == info.cid &&
                it.videoUrl == info.videoUrl && it.audioUrl == info.audioUrl &&
                it.currentQuality == info.currentQuality
        } == true && currentAudioOnly == audioOnly
        if (same) return BindResult.Reused

        val source = PlayerFactory.buildMediaSource(info, audioOnly = audioOnly)
            ?: return BindResult.NoSource

        currentPlayInfo = info
        currentAudioOnly = audioOnly
        return runCatching {
            p.setMediaSource(source)
            p.prepare()
            // 恢复位置要在 prepare 之后设置（prepare 会重置位置）
            if (resumePositionMs > 0L) {
                p.seekTo(resumePositionMs)
            }
            p.playWhenReady = playWhenReady
            BindResult.Bound
        }.getOrElse { BindResult.Failed(it.message ?: "未知错误") }
    }

    /** 释放播放器（离开视频场景时调用）。 */
    fun release() {
        releaseInternal()
        currentKey = null
        currentPlayInfo = null
        currentAudioOnly = false
    }

    private fun releaseInternal() {
        player?.let { p ->
            runCatching {
                p.stop()
                p.release()
            }
        }
        player = null
    }

    /** 是否正在播放（PiP / 后台判断用）。 */
    val isPlaying: Boolean get() = player?.isPlaying == true

    /** 当前播放位置（毫秒）。已释放返回 0。 */
    val currentPosition: Long get() = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)

    /** 时长（毫秒）。 */
    val duration: Long get() = runCatching { player?.duration?.coerceAtLeast(0L) ?: 0L }.getOrDefault(0L)

    /** 装配结果。 */
    sealed interface BindResult {
        /** 重新装配成功。 */
        data object Bound : BindResult

        /** 流未变，复用现有缓冲（不打断播放）。 */
        data object Reused : BindResult

        /** 播放器尚未创建。 */
        data object NoPlayer : BindResult

        /** 播放器已释放（竞态）。 */
        data object Released : BindResult

        /** 无法构建播放源（取流地址为空）。 */
        data object NoSource : BindResult

        /** 装配抛异常。 */
        data class Failed(val message: String) : BindResult
    }
}
