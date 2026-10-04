package com.example.biliv3.data.lyrics

import com.example.biliv3.data.subtitle.SubtitleRepository
import com.example.biliv3.data.subtitle.SubtitleTrack

/**
 * **内置 Provider：用 B 站字幕当歌词**。
 *
 * ## 为什么这是首选 Provider（而不是去爬第三方歌词站）
 *
 * 本项目已经有 `SubtitleRepository`（手写 protobuf + gRPC 帧，
 * 见 `AGENTS-P2.md` §10 与 `data/subtitle/`），它拿到的字幕**天然带时间轴**：
 *
 * | 维度 | B 站字幕 | 第三方歌词站 |
 * |---|---|---|
 * | 时间轴 | ✅ 官方，与视频严格对齐 | ⚠️ 需匹配，常有偏差 |
 * | 鉴权 | ✅ 走现有 `BiliApi`（已登录时可用） | ❌ 第三方，且不能带 Cookie |
 * | 稳定性 | ✅ 同源接口 | ⚠️ 会挂 / 会改 |
 * | 覆盖 | ⚠️ 只有 UP 传了字幕才有 | ✅ 更广 |
 *
 * 所以策略是：**字幕优先，第三方作为兜底**（见 `LyricsRepository` 的链式回退）。
 * 这样既复用了现有能力，又不把歌词功能绑死在"必须有人传字幕"上。
 *
 * ## ⚠️ 不能带 Cookie 给第三方 —— 但字幕走的是自家 API
 *
 * 字幕接口用 `SubtitleRepository`，它内部用的是**共享 `BiliApi`**
 * （需要登录态才能取部分字幕）—— 这是**本项目自己的账号**，合规。
 * 而第三方歌词 Provider 必须用**独立 OkHttpClient**（§4.2 红线）。
 */
class SubtitleLyricsProvider(
    private val repo: SubtitleRepository,
) : LyricsProvider {

    override val id: String = "builtin.subtitle"
    override val displayName: String = "视频字幕"

    override suspend fun fetch(
        bvid: String,
        cid: Long,
        aid: Long,
        title: String,
        durationMs: Long,
    ): LyricsResult {
        if (cid <= 0L || aid <= 0L) return LyricsResult.Unavailable

        // ⚠️ `SubtitleRepository.tracks()` **内部吞掉了异常**
        // （gRPC 失败退 REST，REST 也失败就返回空列表）——
        // 所以这里拿到的"空列表"**无法区分**「没字幕」与「取字幕失败」。
        //
        // 处置：按「没字幕」处理（Unavailable），让 `LyricsRepository`
        // 继续回退到下一个 Provider。这是**保守选择** ——
        // 若当成 Error，用户会因为一次网络抖动看到"加载失败"，
        // 而实际只是这个视频没人传字幕。
        val tracks = repo.tracks(aid = aid, cid = cid)
        if (tracks.isEmpty()) return LyricsResult.Unavailable

        // 选轨优先级：中文人工 > 中文 AI > 任意第一条
        val track = pickTrack(tracks)

        // `body()` 会抛 `SubtitleException` —— 这里的失败是**真失败**
        val body = try {
            repo.body(track)
        } catch (e: Exception) {
            return LyricsResult.Error(
                message = e.message ?: "字幕内容加载失败",
                retryable = true,
            )
        }

        if (body.cues.isEmpty()) return LyricsResult.Unavailable

        val lines = body.cues
            .mapNotNull { cue ->
                val text = cue.content.trim()
                if (text.isEmpty()) return@mapNotNull null
                LyricLine(timeMs = (cue.from * 1000).toLong(), text = text)
            }
            .sortedBy { it.timeMs }

        if (lines.isEmpty()) return LyricsResult.Unavailable

        return LyricsResult.Success(
            lyrics = Lyrics(lines),
            source = displayName,
        )
    }

    /**
     * 选轨：优先人工中文，其次 AI 中文，最后任意。
     *
     * 理由：AI 字幕会有识别错误，人工字幕质量更高；
     * 而歌词场景下"文字对不对"直接影响体验。
     */
    private fun pickTrack(tracks: List<SubtitleTrack>): SubtitleTrack {
        val zh = tracks.filter { it.lan.startsWith("zh") }
        // 人工（非 AI）中文优先
        zh.firstOrNull { !it.isAi }?.let { return it }
        zh.firstOrNull()?.let { return it }
        // 退而求其次：非翻译轨
        tracks.firstOrNull { !it.isAiTranslate }?.let { return it }
        return tracks.first()
    }
}
