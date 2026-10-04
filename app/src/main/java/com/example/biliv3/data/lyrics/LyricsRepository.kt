package com.example.biliv3.data.lyrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 歌词 UI 状态。
 *
 * ## 四态而非三态
 *
 * 任务书要求三态齐全，但歌词场景实际有**四种**，
 * 且第 4 种（未匹配）与"空"含义完全不同：
 *
 * | 状态 | 含义 | UI |
 * |---|---|---|
 * | [Loading] | 正在拉 | 转圈 / 骨架 |
 * | [Ready] | 有歌词 | 歌词列表 |
 * | [NoLyrics] | 这首歌**确实没有**歌词 | 「暂无歌词」+ 说明 |
 * | [Failed] | 加载**失败** | 错误 + 重试按钮 |
 *
 * ⚠️ 把 [NoLyrics] 与 [Failed] 合并是**本项目反复踩过的坑**
 * （`AGENTS-P2.md` 坑 44：把失败显示成空，等于对用户说假话）。
 * 前者不需要重试，后者必须给重试入口。
 */
sealed interface LyricsUiState {

    data object Idle : LyricsUiState

    data object Loading : LyricsUiState

    /**
     * 歌词就绪。
     *
     * @param source 来源名（"视频字幕" / "本地文件"），UI 要**标注来源** ——
     *        与 aicu 数据同样遵守"不冒充官方"的原则（§4.3）
     */
    data class Ready(val lyrics: Lyrics, val source: String) : LyricsUiState

    /** 确实没有歌词（正常结果）。 */
    data object NoLyrics : LyricsUiState

    /** 加载失败。 */
    data class Failed(val message: String, val retryable: Boolean = true) : LyricsUiState
}

/**
 * 歌词仓库。
 *
 * ## 职责
 *
 * 1. **链式回退**：按 `providers` 顺序尝试，第一个给出结果的胜出
 * 2. **缓存**：同一 `bvid:cid` 只拉一次（切页面回来不重复请求）
 * 3. **状态机**：把 Provider 的结果翻译成 [LyricsUiState]
 *
 * ## 为什么不把逻辑塞进 Composable
 *
 * 任务书 §8 明确要求「不要把歌词逻辑全部塞进播放器 Composable」。
 * 除了可测试性，还有一个现实原因：**歌词要在多个 UI 里复用**
 * （播放器歌词层、黑胶模式、全屏歌词页），
 * 塞进任一 Composable 都意味着其它两处要复制一遍。
 */
class LyricsRepository(
    private val providers: List<LyricsProvider>,
) {

    private val _state = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    /** 缓存键：`bvid:cid`。 */
    private val cache = LinkedHashMap<String, LyricsUiState>()

    /** 当前正在加载的键（避免并发重复请求）。 */
    private var loadingKey: String? = null

    /**
     * 用户手动调整的时间偏移（毫秒）。
     *
     * ## 为什么需要它
     *
     * 任务书 §8 要求支持"时间偏移"。真实场景：字幕与音频对不齐
     * （AI 字幕尤其常见），用户需要一个"歌词快一点/慢一点"的调节。
     *
     * ⚠️ 这是**用户级设置**，与 LRC 文件自带的 `[offset:]` **叠加**：
     * 最终偏移 = LRC offset + 用户 offset（见 [effectiveLyrics]）。
     */
    private val _userOffsetMs = MutableStateFlow(0L)
    val userOffsetMs: StateFlow<Long> = _userOffsetMs.asStateFlow()

    /** 应用用户偏移后的歌词（`indexAt` 会用到）。 */
    fun effectiveLyrics(): Lyrics? {
        val s = _state.value as? LyricsUiState.Ready ?: return null
        val extra = _userOffsetMs.value
        if (extra == 0L) return s.lyrics
        // 用户偏移为正值 = 歌词延后 → 叠加到 offsetMs 上
        return s.lyrics.copy(offsetMs = s.lyrics.offsetMs + extra)
    }

    /** 调整用户偏移（毫秒，可正可负）。 */
    fun adjustUserOffset(deltaMs: Long) {
        _userOffsetMs.value = (_userOffsetMs.value + deltaMs).coerceIn(-30_000L, 30_000L)
    }

    fun resetUserOffset() {
        _userOffsetMs.value = 0L
    }

    /**
     * 加载歌词。
     *
     * @param force true = 忽略缓存重新拉（用户点"重试"时）
     */
    suspend fun load(
        bvid: String,
        cid: Long,
        aid: Long,
        title: String,
        durationMs: Long,
        force: Boolean = false,
    ) {
        val key = "$bvid:$cid"

        // 命中缓存：直接切换状态，不打网络
        if (!force) {
            cache[key]?.let {
                _state.value = it
                return
            }
        }

        // 并发去重：同一 key 正在加载时直接返回
        if (loadingKey == key) return
        loadingKey = key

        _state.value = LyricsUiState.Loading
        _userOffsetMs.value = 0L

        val result = resolve(bvid, cid, aid, title, durationMs)

        cache[key] = result
        loadingKey = null
        _state.value = result
    }

    /**
     * 依次尝试各 Provider。
     *
     * 回退规则（**这是本类的核心逻辑**）：
     * - `Success` → 立刻返回，不再尝试后面的
     * - `Unavailable` → 继续尝试下一个（这个源没有，不代表别的源也没有）
     * - `Error` → **也继续尝试下一个**，但记住第一个错误；
     *   全部失败才返回错误
     *
     * 为什么 Error 也继续：字幕源失败（如未登录）时，
     * 本地文件源可能仍然可用。过早放弃会让用户白等一次重试。
     */
    private suspend fun resolve(
        bvid: String,
        cid: Long,
        aid: Long,
        title: String,
        durationMs: Long,
    ): LyricsUiState {
        if (providers.isEmpty()) return LyricsUiState.NoLyrics

        var firstError: LyricsResult.Error? = null

        for (p in providers) {
            val r = try {
                p.fetch(bvid, cid, aid, title, durationMs)
            } catch (e: Exception) {
                // Provider 违反约定抛了异常 —— 隔离它，不让整个歌词功能挂掉
                LyricsResult.Error(e.message ?: "${p.displayName} 异常", retryable = true)
            }

            when (r) {
                is LyricsResult.Success ->
                    return LyricsUiState.Ready(r.lyrics, r.source)

                LyricsResult.Unavailable -> Unit   // 继续下一个

                is LyricsResult.Error -> {
                    if (firstError == null) firstError = r
                }
            }
        }

        // 全部 Provider 都没有 → NoLyrics（正常结果，不是错误）
        // 有错误且没有任何源成功 → Failed（真失败，给重试）
        return firstError?.let { LyricsUiState.Failed(it.message, it.retryable) }
            ?: LyricsUiState.NoLyrics
    }

    /** 清空缓存（切换账号 / 清缓存时调用）。 */
    fun clearCache() {
        cache.clear()
        _state.value = LyricsUiState.Idle
        _userOffsetMs.value = 0L
    }

    /** 清掉某一个视频的缓存（重试时用）。 */
    fun invalidate(bvid: String, cid: Long) {
        cache.remove("$bvid:$cid")
    }
}
