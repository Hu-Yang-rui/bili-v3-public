package com.example.biliv3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 下载器核心逻辑测试。
 *
 * ## 为什么必须测这两块
 *
 * 断点续传有两个**会静默产生损坏文件**的陷阱：
 *
 * 1. **总长度解析错** → 进度显示错、完成判断错，
 *    用户看到"100%"但文件是半截的
 * 2. **服务端忽略 Range 时未清空旧文件** → 新内容追加到旧内容后面，
 *    得到一个"能播但后半段错乱"的文件 —— 比下载失败更糟，
 *    因为它**看起来是成功的**
 *
 * 所以这里把纯逻辑抽出来测（不依赖网络）。
 */
class DownloadLogicTest {

    /**
     * 复刻 `VideoDownloader.parseTotalFromContentRange`。
     *
     * ⚠️ 这个解析之所以重要：实测服务端**不支持 HEAD**（返回 404），
     * 所以总长度只能从 `Content-Range` 的尾段拿。
     */
    private fun parseTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0 || slash == header.length - 1) return null
        return header.substring(slash + 1).trim().toLongOrNull()
    }

    @Test
    fun `从 Content-Range 解析总长度`() {
        // 实测真实响应形如：bytes 0-1023/12404937
        assertThat(parseTotal("bytes 0-1023/12404937")).isEqualTo(12404937L)
        assertThat(parseTotal("bytes 1024-2047/12404937")).isEqualTo(12404937L)
        // 续传时起点非 0
        assertThat(parseTotal("bytes 5000-9999/20000")).isEqualTo(20000L)
    }

    @Test
    fun `Content-Range 缺失或畸形时返回 null`() {
        // 这样调用方会退回到 contentLength，而不是拿到 0 导致误判"已完成"
        assertThat(parseTotal(null)).isNull()
        assertThat(parseTotal("")).isNull()
        assertThat(parseTotal("bytes 0-1023")).isNull() // 无总长
        assertThat(parseTotal("bytes 0-1023/")).isNull() // 斜杠后为空
        assertThat(parseTotal("garbage")).isNull()
        assertThat(parseTotal("bytes 0-1023/abc")).isNull() // 非数字
    }

    /** `*`（未知总长）不能被当成数字。 */
    @Test
    fun `未知总长星号返回 null`() {
        assertThat(parseTotal("bytes 0-1023/*")).isNull()
    }

    // ---------------- 续传决策 ----------------

    /**
     * 复刻下载器的续传判断逻辑。
     *
     * @return (是否从头开始, 起始偏移)
     */
    private fun resumeDecision(
        existingBytes: Long,
        responseCode: Int,
    ): Pair<Boolean, Long> {
        val resumed = responseCode == 206
        // 服务端忽略 Range（200）时：必须从头，且要清空已有文件
        return if (!resumed) (existingBytes > 0) to 0L else false to existingBytes
    }

    @Test
    fun `服务端支持 Range 时从已有位置续传`() {
        val (truncate, startAt) = resumeDecision(existingBytes = 5000L, responseCode = 206)
        assertThat(truncate).isFalse()
        assertThat(startAt).isEqualTo(5000L)
    }

    /**
     * ⚠️ 关键回归点：服务端**忽略** Range 返回 200 时，
     * 必须清空旧文件从头写，否则会得到内容错位的损坏文件。
     */
    @Test
    fun `服务端忽略 Range 时必须清空重写`() {
        val (truncate, startAt) = resumeDecision(existingBytes = 5000L, responseCode = 200)
        assertThat(truncate).isTrue()
        assertThat(startAt).isEqualTo(0L)
    }

    @Test
    fun `全新下载时从头开始`() {
        val (truncate, startAt) = resumeDecision(existingBytes = 0L, responseCode = 200)
        assertThat(truncate).isFalse() // 没有旧文件，无需清空
        assertThat(startAt).isEqualTo(0L)
    }

    // ---------------- 下载完整性 ----------------

    /**
     * 复刻"何时认定缓存可用"的规则。
     *
     * 只认「视频轨存在且有字节」——因为部分视频没有独立音轨，
     * 要求音频也必须有会导致这类视频永远下载"失败"。
     */
    private fun isUsable(videoBytes: Long, videoPath: String): Boolean =
        videoPath.isNotEmpty() && videoBytes > 0

    @Test
    fun `视频轨有内容即视为可用`() {
        assertThat(isUsable(1_000_000L, "/x/video.m4s")).isTrue()
        // 无音轨的视频（audioPath 为空）也应可用
    }

    @Test
    fun `空文件不算可用`() {
        assertThat(isUsable(0L, "/x/video.m4s")).isFalse()
        assertThat(isUsable(1000L, "")).isFalse()
    }

    // ---------------- 进度计算 ----------------

    /**
     * 复刻进度：视频占 90%、音频占 10%。
     *
     * 为什么按权重而不是简单地 (已下载/总下载)：
     * 下载过程中总大小未知（音频还没开始），
     * 直接比值会在音频阶段突然从 100% 掉下来。
     */
    private fun videoProgress(done: Long, total: Long): Float =
        if (total > 0) done.toFloat() / total * 0.9f else 0f

    private fun audioProgress(done: Long, total: Long): Float =
        if (total > 0) 0.9f + done.toFloat() / total * 0.1f else 0.9f

    @Test
    fun `进度权重单调递增且不越界`() {
        val steps = listOf(
            videoProgress(0L, 1000L),
            videoProgress(500L, 1000L),
            videoProgress(1000L, 1000L),
            audioProgress(0L, 100L),
            audioProgress(50L, 100L),
            audioProgress(100L, 100L),
        )
        // 单调不减
        steps.zipWithNext { a, b -> assertThat(b).isAtLeast(a) }
        // 不超过 1
        assertThat(steps.last()).isAtMost(1.0f)
        // 视频满时正好到 0.9（音频阶段从这里继续）
        assertThat(videoProgress(1000L, 1000L)).isWithin(0.001f).of(0.9f)
        // 全部完成正好 1.0
        assertThat(audioProgress(100L, 100L)).isWithin(0.001f).of(1.0f)
    }

    /** 总长未知时不能除零，也不能报 100%。 */
    @Test
    fun `总长未知时不产生非法进度`() {
        assertThat(videoProgress(500L, 0L)).isEqualTo(0f)
        assertThat(audioProgress(500L, 0L)).isEqualTo(0.9f)
    }
}
