package com.example.biliv3

import com.example.biliv3.data.lyrics.LyricLine
import com.example.biliv3.data.lyrics.Lyrics
import com.example.biliv3.data.lyrics.LyricsProvider
import com.example.biliv3.data.lyrics.LyricsRepository
import com.example.biliv3.data.lyrics.LyricsResult
import com.example.biliv3.data.lyrics.LyricsUiState
import com.example.biliv3.player.SleepTimer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 歌词仓库的**链式回退**与睡眠定时器单测。
 *
 * 这两块是"状态机"逻辑，最容易出现的 bug 不是崩溃而是**状态错**：
 * - 把所有源都失败显示成"没有歌词"（对用户说假话）
 * - 一个源失败就放弃后续源（白丢可用数据）
 * - 定时器在 Activity 重建后丢失
 */
class LyricsAndTimerTest {

    // ---------------- 假 Provider ----------------

    private class FakeProvider(
        override val id: String,
        override val displayName: String,
        private val result: LyricsResult,
    ) : LyricsProvider {
        var calls = 0
            private set

        override suspend fun fetch(
            bvid: String,
            cid: Long,
            aid: Long,
            title: String,
            durationMs: Long,
        ): LyricsResult {
            calls++
            return result
        }
    }

    private class ThrowingProvider(
        override val id: String = "throwing",
        override val displayName: String = "会抛异常的源",
    ) : LyricsProvider {
        override suspend fun fetch(
            bvid: String,
            cid: Long,
            aid: Long,
            title: String,
            durationMs: Long,
        ): LyricsResult = throw IllegalStateException("插件式源崩了")
    }

    private val sampleLyrics = Lyrics(listOf(LyricLine(1000L, "测试歌词")))

    private suspend fun LyricsRepository.loadSample(force: Boolean = false) =
        load(bvid = "BV1", cid = 100L, aid = 1L, title = "t", durationMs = 60_000L, force = force)

    // ---------------- 链式回退 ----------------

    @Test
    fun `第一个源成功就不再问后面的`() = runTest {
        val p1 = FakeProvider("a", "源A", LyricsResult.Success(sampleLyrics, "源A"))
        val p2 = FakeProvider("b", "源B", LyricsResult.Success(sampleLyrics, "源B"))
        val repo = LyricsRepository(listOf(p1, p2))

        repo.loadSample()

        assertThat(repo.state.value).isInstanceOf(LyricsUiState.Ready::class.java)
        assertThat(p1.calls).isEqualTo(1)
        assertThat(p2.calls).isEqualTo(0)   // 关键：不该继续问
    }

    @Test
    fun `第一个源没有歌词时会继续尝试下一个`() = runTest {
        val p1 = FakeProvider("a", "源A", LyricsResult.Unavailable)
        val p2 = FakeProvider("b", "源B", LyricsResult.Success(sampleLyrics, "源B"))
        val repo = LyricsRepository(listOf(p1, p2))

        repo.loadSample()

        assertThat(repo.state.value).isInstanceOf(LyricsUiState.Ready::class.java)
        assertThat(p1.calls).isEqualTo(1)
        assertThat(p2.calls).isEqualTo(1)
    }

    @Test
    fun `第一个源失败时仍会继续尝试下一个`() = runTest {
        // 这是核心设计：字幕源失败（如未登录）时，本地源可能仍然可用
        val p1 = FakeProvider("a", "源A", LyricsResult.Error("网络挂了"))
        val p2 = FakeProvider("b", "源B", LyricsResult.Success(sampleLyrics, "源B"))
        val repo = LyricsRepository(listOf(p1, p2))

        repo.loadSample()

        assertThat(repo.state.value).isInstanceOf(LyricsUiState.Ready::class.java)
        assertThat(p2.calls).isEqualTo(1)
    }

    @Test
    fun `全部源都没有歌词时是 NoLyrics 而不是 Failed`() = runTest {
        val repo = LyricsRepository(
            listOf(
                FakeProvider("a", "源A", LyricsResult.Unavailable),
                FakeProvider("b", "源B", LyricsResult.Unavailable),
            ),
        )

        repo.loadSample()

        // 「没有歌词」是正常结果，不该显示错误
        assertThat(repo.state.value).isEqualTo(LyricsUiState.NoLyrics)
    }

    @Test
    fun `全部源都失败时是 Failed 而不是 NoLyrics`() = runTest {
        // 回归：把失败显示成"没有歌词"是在对用户说假话（坑 44 同类）
        val repo = LyricsRepository(
            listOf(
                FakeProvider("a", "源A", LyricsResult.Error("网络不可用")),
                FakeProvider("b", "源B", LyricsResult.Error("也挂了")),
            ),
        )

        repo.loadSample()

        val s = repo.state.value
        assertThat(s).isInstanceOf(LyricsUiState.Failed::class.java)
        // 保留**第一个**错误（更接近根因）
        assertThat((s as LyricsUiState.Failed).message).isEqualTo("网络不可用")
    }

    @Test
    fun `Provider 抛异常被隔离不会让歌词功能崩掉`() = runTest {
        val repo = LyricsRepository(
            listOf(
                ThrowingProvider(),
                FakeProvider("b", "源B", LyricsResult.Success(sampleLyrics, "源B")),
            ),
        )

        repo.loadSample()

        // 一个源崩了，后面的源仍应正常提供歌词
        assertThat(repo.state.value).isInstanceOf(LyricsUiState.Ready::class.java)
    }

    @Test
    fun `没有配置任何 Provider 时是 NoLyrics`() = runTest {
        val repo = LyricsRepository(emptyList())
        repo.loadSample()
        assertThat(repo.state.value).isEqualTo(LyricsUiState.NoLyrics)
    }

    // ---------------- 缓存 ----------------

    @Test
    fun `同一视频第二次加载命中缓存不打网络`() = runTest {
        val p = FakeProvider("a", "源A", LyricsResult.Success(sampleLyrics, "源A"))
        val repo = LyricsRepository(listOf(p))

        repo.loadSample()
        repo.loadSample()

        assertThat(p.calls).isEqualTo(1)
    }

    @Test
    fun `force 会忽略缓存重新拉`() = runTest {
        val p = FakeProvider("a", "源A", LyricsResult.Success(sampleLyrics, "源A"))
        val repo = LyricsRepository(listOf(p))

        repo.loadSample()
        repo.loadSample(force = true)

        assertThat(p.calls).isEqualTo(2)
    }

    @Test
    fun `不同分P 是不同的缓存键`() = runTest {
        val p = FakeProvider("a", "源A", LyricsResult.Success(sampleLyrics, "源A"))
        val repo = LyricsRepository(listOf(p))

        repo.load(bvid = "BV1", cid = 100L, aid = 1L, title = "t", durationMs = 1L)
        repo.load(bvid = "BV1", cid = 200L, aid = 1L, title = "t", durationMs = 1L)

        // 多 P 视频每个分P 的歌词独立，不能共用缓存
        assertThat(p.calls).isEqualTo(2)
    }

    @Test
    fun `清空缓存后需要重新拉`() = runTest {
        val p = FakeProvider("a", "源A", LyricsResult.Success(sampleLyrics, "源A"))
        val repo = LyricsRepository(listOf(p))

        repo.loadSample()
        repo.clearCache()
        repo.loadSample()

        assertThat(p.calls).isEqualTo(2)
        assertThat(repo.state.value).isInstanceOf(LyricsUiState.Ready::class.java)
    }

    // ---------------- 用户时间偏移 ----------------

    @Test
    fun `用户偏移与 LRC 自带偏移叠加`() = runTest {
        val lrc = Lyrics(listOf(LyricLine(10_000L, "第一行")), offsetMs = 500L)
        val repo = LyricsRepository(
            listOf(FakeProvider("a", "源A", LyricsResult.Success(lrc, "源A"))),
        )
        repo.loadSample()

        repo.adjustUserOffset(500L)

        val eff = repo.effectiveLyrics()!!
        assertThat(eff.offsetMs).isEqualTo(1000L)   // 500(文件) + 500(用户)
    }

    @Test
    fun `偏移调整有上下限不会无限放大`() = runTest {
        val repo = LyricsRepository(
            listOf(FakeProvider("a", "源A", LyricsResult.Success(sampleLyrics, "源A"))),
        )
        repo.loadSample()

        repeat(200) { repo.adjustUserOffset(1000L) }
        assertThat(repo.userOffsetMs.value).isEqualTo(30_000L)   // 上限 30s
    }

    @Test
    fun `加载新歌词会重置用户偏移`() = runTest {
        val repo = LyricsRepository(
            listOf(FakeProvider("a", "源A", LyricsResult.Success(sampleLyrics, "源A"))),
        )
        repo.loadSample()
        repo.adjustUserOffset(2000L)

        repo.loadSample(force = true)

        assertThat(repo.userOffsetMs.value).isEqualTo(0L)
    }

    // ---------------- 睡眠定时器 ----------------

    @Test
    fun `初始状态未启用`() {
        val t = SleepTimer()
        assertThat(t.active.value).isFalse()
        assertThat(t.label()).isEmpty()
    }

    @Test
    fun `设定分钟后处于启用状态并显示剩余时间`() {
        val t = SleepTimer()
        t.start(10)
        assertThat(t.active.value).isTrue()
        assertThat(t.remainingMs.value).isEqualTo(10 * 60_000L)
        assertThat(t.label()).isEqualTo("10:00")
    }

    @Test
    fun `取消后回到未启用`() {
        val t = SleepTimer()
        t.start(30)
        t.cancel()
        assertThat(t.active.value).isFalse()
        assertThat(t.remainingMs.value).isEqualTo(0L)
        assertThat(t.label()).isEmpty()
    }

    @Test
    fun `传 0 或负数等于取消`() {
        val t = SleepTimer()
        t.start(10)
        t.start(0)
        assertThat(t.active.value).isFalse()

        t.start(10)
        t.start(-5)
        assertThat(t.active.value).isFalse()
    }

    @Test
    fun `未启用时 tick 永远返回 false`() {
        val t = SleepTimer()
        assertThat(t.tick()).isFalse()
    }

    @Test
    fun `当前视频结束模式不靠时间到期`() {
        val t = SleepTimer()
        t.startEndOfItem()
        assertThat(t.active.value).isTrue()
        assertThat(t.isEndOfItemMode).isTrue()
        // 该模式由"播放结束"事件触发，tick 不该返回到期
        assertThat(t.tick()).isFalse()
        assertThat(t.label()).isEqualTo("本集结束")
    }

    @Test
    fun `切换模式会覆盖上一个定时`() {
        val t = SleepTimer()
        t.start(60)
        t.startEndOfItem()
        assertThat(t.isEndOfItemMode).isTrue()
        // 切回时间模式
        t.start(10)
        assertThat(t.isEndOfItemMode).isFalse()
        assertThat(t.label()).isEqualTo("10:00")
    }

    @Test
    fun `预设档位是 10 20 30 45 60`() {
        assertThat(SleepTimer.PRESETS).containsExactly(10, 20, 30, 45, 60).inOrder()
    }
}
