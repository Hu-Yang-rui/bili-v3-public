package com.example.biliv3.data.download

import android.content.Context
import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliHeaders
import com.example.biliv3.data.model.PlayInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 视频下载器。
 *
 * ## 为什么自己写而不是用 Media3 `DownloadManager`
 *
 * B 站取流返回的是**分离的音视频 fMP4 直链**，不是标准 `.mpd` 清单
 * （`AGENTS.md` §7.2 已实测）。Media3 的 DownloadManager 面向
 * DASH/HLS 清单，喂 fMP4 直链反而要绕一圈。
 *
 * 而下载逻辑本身很直白：两个 URL、两个文件、可断点续传。
 * 用已有的 OkHttp 直接下，代码量更小、行为更可控。
 *
 * ## ⚠️ 实测约束（决定了实现方式）
 *
 * 1. **直链不支持 HEAD**（返回 404，content-length=18）
 *    → **不能**用 HEAD 探测文件大小；必须靠 GET 的
 *      `Content-Range: bytes 0-0/总长` 拿总长度。
 * 2. **支持 Range**（206 + `content-range`）
 *    → 可实现断点续传：中断后从已下载字节继续，不用重下。
 * 3. **URL 带 `deadline` 参数，约 2h 过期**
 *    → 续传前必须校验；过期要重新取流拿新 URL，
 *      否则会拿到 403 并写进半截文件。
 *
 * ## 下载流程
 *
 * ```
 * 取流(playurl) → 选清晰度/编码 → 下载视频轨 → 下载音频轨
 *   → 校验字节数 → 写入 DownloadStore 索引 → UI 可见
 * ```
 *
 * **只在全部完成后写索引** —— 半截文件不进索引，
 * 用户不会看到一个"点了播不了"的缓存项。
 */
class VideoDownloader(
    private val context: Context,
    private val api: BiliApi,
    private val store: DownloadStore,
) {

    /**
     * 进行中的任务（key = "bvid:cid"）。
     *
     * UI 订阅它显示进度。用 StateFlow 而不是回调 ——
     * 下载管理页可能同时观察多个任务，Flow 天然支持。
     */
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    /**
     * 下载专用 OkHttp。
     *
     * 与 API 客户端**分开**：下载是长连接大流量，
     * 用 API 的 20s 超时会把正常的大文件下载误杀。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 读超时给足：单个分片可能较大
        .readTimeout(60, TimeUnit.SECONDS)
        // 整体不设上限 —— 大文件下载本来就慢
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 缓存根目录（应用私有，不需要存储权限）。 */
    private val root: File
        get() = File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }

    /**
     * 下载一个视频（某个分P 的某个清晰度）。
     *
     * @param info 已取好的流信息（包含选定清晰度的音视频直链）
     * @param pageIndex 分P 序号（0 基）
     * @param pageLabel 分P 标签（如 `P2`），单P 传空串
     * @param qualityLabel 清晰度的中文描述（如 `高清 720P`）
     */
    suspend fun download(
        info: PlayInfo,
        bvid: String,
        cid: Long,
        aid: Long,
        title: String,
        cover: String,
        authorName: String,
        pageIndex: Int,
        pageLabel: String,
        qualityLabel: String,
        durationSeconds: Int,
    ): Result<DownloadedItem> = withContext(Dispatchers.IO) {
        val key = taskKey(bvid, cid)

        if (info.videoUrl.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("取流地址为空，无法下载"))
        }

        // 目标文件：按 bvid_cid 命名，避免不同分P 互相覆盖
        val dir = File(root, "${bvid}_$cid").apply { if (!exists()) mkdirs() }
        val videoFile = File(dir, "video.m4s")
        val audioFile = File(dir, "audio.m4s")

        updateTask(key) {
            DownloadTask(
                bvid = bvid, cid = cid, title = title,
                progress = 0f, downloadedBytes = 0, totalBytes = 0,
                state = DownloadState.Downloading,
            )
        }

        try {
            // ---- 视频轨 ----
            val vTotal = downloadFile(info.videoUrl, videoFile) { done, total ->
                // 视频占总进度 90%（体积通常远大于音频）
                val p = if (total > 0) done.toFloat() / total * 0.9f else 0f
                updateTask(key) {
                    it.copy(
                        progress = p,
                        downloadedBytes = done,
                        totalBytes = total,
                    )
                }
            }

            // ---- 音频轨 ----
            val aTotal = if (info.audioUrl.isNotEmpty()) {
                downloadFile(info.audioUrl, audioFile) { done, total ->
                    val p = if (total > 0) 0.9f + done.toFloat() / total * 0.1f else 0.9f
                    updateTask(key) {
                        it.copy(
                            progress = p,
                            downloadedBytes = vTotal + done,
                            totalBytes = vTotal + total,
                        )
                    }
                }
            } else {
                // 无音轨：删掉占位文件，避免索引里出现空音频路径
                if (audioFile.exists()) audioFile.delete()
                0L
            }

            // ---- 校验：文件必须真的有内容 ----
            if (vTotal <= 0L || !videoFile.exists()) {
                throw IllegalStateException("视频轨下载为空")
            }

            val item = DownloadedItem(
                bvid = bvid,
                cid = cid,
                aid = aid,
                title = title,
                cover = cover,
                authorName = authorName,
                pageIndex = pageIndex,
                pageLabel = pageLabel,
                quality = info.currentQuality,
                qualityLabel = qualityLabel,
                videoPath = videoFile.absolutePath,
                audioPath = if (aTotal > 0) audioFile.absolutePath else "",
                videoBytes = vTotal,
                audioBytes = aTotal,
                durationSeconds = durationSeconds,
                downloadedAt = System.currentTimeMillis() / 1000,
                lastPositionMs = 0L,
            )

            // ⚠️ 只有全部完成才写索引
            store.upsert(item)

            updateTask(key) {
                it.copy(progress = 1f, state = DownloadState.Completed)
            }
            // 完成后从"进行中"移除（历史由 store 提供）
            _tasks.update { m -> m - key }

            Result.success(item)
        } catch (e: Exception) {
            updateTask(key) {
                it.copy(state = DownloadState.Failed(e.message ?: "下载失败"))
            }
            Result.failure(e)
        }
    }

    /**
     * 下载单个文件，支持**断点续传**。
     *
     * ## 实现要点
     *
     * - 已有部分文件时用 `Range: bytes=<已有长度>-` 续传
     * - 总长度从 `Content-Range` 解析（**不能 HEAD**，见类注释）
     * - 服务端忽略 Range（返回 200 而非 206）时**从头重下**，
     *   否则会把新内容追加到旧内容后面，得到损坏文件
     *
     * @param onProgress (已下载字节, 总字节)
     * @return 最终文件总字节数
     */
    private fun downloadFile(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val existing = if (target.exists()) target.length() else 0L

        val request = Request.Builder()
            .url(url)
            .apply {
                // ⚠️ 媒体请求必须带 Referer，否则 CDN 403
                BiliHeaders.media().forEach { (k, v) -> addHeader(k, v) }
                if (existing > 0) addHeader("Range", "bytes=$existing-")
            }
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                // 403 多为 URL 过期 —— 给出可读原因而不是干巴巴的状态码
                val reason = when (resp.code) {
                    403 -> "取流地址已过期，请重新下载"
                    404 -> "资源不存在"
                    else -> "HTTP ${resp.code}"
                }
                throw IllegalStateException(reason)
            }

            val body = resp.body ?: throw IllegalStateException("响应为空")

            // 是否真的续传成功（服务端支持 Range 会返回 206）
            val resumed = resp.code == 206
            val startAt = if (resumed) existing else 0L

            // 总长度：206 时从 content-range 尾段取；200 时用 contentLength
            val total = if (resumed) {
                parseTotalFromContentRange(resp.header("Content-Range"))
                    ?: (startAt + body.contentLength())
            } else {
                body.contentLength()
            }

            // 服务端忽略了 Range：必须清空重写，否则内容会错位
            if (!resumed && existing > 0) {
                target.delete()
            }

            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(startAt)
                var written = startAt
                val buf = ByteArray(BUFFER_SIZE)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                }
            }

            return total
        }
    }

    /**
     * 从 `Content-Range: bytes 0-1023/12404937` 解析总长度。
     *
     * 这就是**不能依赖 HEAD** 的补偿手段（实测 HEAD 返回 404）。
     */
    private fun parseTotalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0 || slash == header.length - 1) return null
        return header.substring(slash + 1).trim().toLongOrNull()
    }

    /**
     * 取消下载任务。
     *
     * 只标记状态；已下载的部分文件**保留**，下次可续传。
     */
    fun cancel(bvid: String, cid: Long) {
        val key = taskKey(bvid, cid)
        updateTask(key) { it.copy(state = DownloadState.Cancelled) }
        _tasks.update { m -> m - key }
    }

    /**
     * 删除缓存（文件 + 索引）。
     *
     * ⚠️ 顺序：先删**索引**再删文件。
     * 反过来的话，删文件到一半失败会留下"索引里有、文件没了"的
     * 僵尸条目，用户看到缓存却播不了。
     */
    suspend fun delete(item: DownloadedItem) = withContext(Dispatchers.IO) {
        store.remove(item.bvid, item.cid)
        runCatching { File(item.videoPath).parentFile?.deleteRecursively() }
        Unit
    }

    /** 计算全部缓存占用（字节）。 */
    suspend fun totalSize(): Long = store.items.first().sumOf { it.totalBytes }

    private fun updateTask(key: String, block: (DownloadTask) -> DownloadTask) {
        _tasks.update { m ->
            val cur = m[key] ?: return@update m
            m + (key to block(cur))
        }
    }

    private fun taskKey(bvid: String, cid: Long) = "$bvid:$cid"

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

/** 下载任务状态。 */
data class DownloadTask(
    val bvid: String,
    val cid: Long,
    val title: String,
    /** 0f~1f。 */
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val state: DownloadState,
)

/** 下载状态。 */
sealed interface DownloadState {
    data object Downloading : DownloadState
    data object Completed : DownloadState

    /** 用户主动取消（部分文件保留，可续传）。 */
    data object Cancelled : DownloadState

    data class Failed(val message: String) : DownloadState
}
