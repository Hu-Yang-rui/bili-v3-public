package com.example.biliv3.data.lyrics

/**
 * 歌词来源（**可插拔**）。
 *
 * ## 为什么必须抽象成 Provider
 *
 * 任务书 §8 要求「歌词来源必须设计为可插拔 Provider，
 * 而不是把某个第三方歌词网站永久写死在 UI 中」。
 *
 * 三个现实理由：
 *
 * 1. **第三方站点会挂** —— 本项目已经因为 aicu 的 DNS 投毒问题
 *    专门写了 `AicuDns`（见 `AGENTS-P2.md` §10）。把歌词源写死在 UI 里，
 *    站点一变就得改 UI 层代码
 * 2. **来源可能被替换** —— B 站自己的字幕接口（`SubtitleGrpcClient`）
 *    已经存在，将来"字幕当歌词"是自然的第二个 Provider
 * 3. **本地歌词文件** —— 用户可以自己放 `.lrc`
 *
 * ## 实现约定
 *
 * - **不得抛异常**：失败返回 [LyricsResult.Unavailable] 或 [LyricsResult.Error]，
 *   由 Repository 决定回退到下一个 Provider
 * - **不得直接持有 Cookie**：歌词源是第三方/可选能力，
 *   不参与登录态（与 `AicuApi` 同一条红线，见 §4.2）
 */
interface LyricsProvider {

    /** Provider 标识（用于日志与 UI 展示来源）。 */
    val id: String

    /** 展示名（"内置字幕" / "本地文件" 等）。 */
    val displayName: String

    /**
     * 拉取歌词。
     *
     * @param bvid 视频标识
     * @param cid 分P 的 cid（同一 bvid 不同分P 歌词不同）
     * @param aid 视频 aid（B 站字幕接口要的是 aid 不是 bvid）
     * @param title 标题（第三方歌词源按标题搜索时用）
     * @param durationMs 时长（用于校验歌词是否对得上）
     */
    suspend fun fetch(
        bvid: String,
        cid: Long,
        aid: Long,
        title: String,
        durationMs: Long,
    ): LyricsResult
}

/**
 * 歌词获取结果。
 *
 * ## 为什么把"没有歌词"与"加载失败"分成两个状态
 *
 * 这是本项目反复踩过的坑（`AGENTS-P2.md` 坑 44）：
 * 把「失败」显示成「空」是在对用户说假话 ——
 * 「这首歌没有歌词」与「网络挂了」对用户的意义完全不同：
 * 前者无需重试，后者应该给重试按钮。
 */
sealed interface LyricsResult {

    /** 拿到歌词。 */
    data class Success(val lyrics: Lyrics, val source: String) : LyricsResult

    /**
     * **该 Provider 没有这首歌的歌词**（正常结果，不是错误）。
     *
     * 例如：B 站没上传字幕、本地没有 `.lrc` 文件。
     */
    data object Unavailable : LyricsResult

    /**
     * **获取失败**（网络 / 解析 / 服务端错误）。
     *
     * @param message 面向用户的原因
     * @param retryable 是否值得重试（网络问题 true，格式错误 false）
     */
    data class Error(val message: String, val retryable: Boolean = true) : LyricsResult
}
