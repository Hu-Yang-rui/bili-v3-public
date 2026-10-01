package com.example.biliv3.data.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.downloadDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "downloads",
)

/**
 * 离线缓存的**索引**（元数据）。
 *
 * ## 为什么单独存索引而不是扫描文件目录
 *
 * 只靠文件目录的话，无法知道：
 * - 这条缓存属于哪个视频（bvid / cid / 分P）
 * - 用户选的清晰度、文件大小、下载时间
 * - **是否下载完整**（半截文件混在里面）
 *
 * 所以下载完成才写入索引 —— 索引里有的就是「可播的完整缓存」。
 * 半截文件由临时目录管理，不在索引里，UI 自然看不到。
 *
 * ## 为什么用 JSON 字符串而不是多个 key
 *
 * 一个列表项有 8+ 字段，用 Preferences 的多 key 存会需要
 * 拆/拼一整套逻辑，且容易漏字段。直接存一个 JSON 数组：
 * 结构自解释、增删字段不用改存储层代码。
 * 缺点是整体读写（列表通常几十条，代价可忽略）。
 *
 * ## 与历史记录 / 稍后再看的关系
 *
 * 三者是**独立**的：
 * - 缓存 = 文件在本地
 * - 历史 = 看过什么
 * - 稍后再看 = 想看的清单
 *
 * 但**离线播放时会写历史**（与在线播放一致），
 * 这样"继续播放"在离线场景也成立（见 [DownloadedItem.lastPositionMs]）。
 */
class DownloadStore(private val context: Context) {

    /** 全部已完成的缓存项（最新在前）。 */
    val items: Flow<List<DownloadedItem>> = context.downloadDataStore.data.map { p ->
        parse(p[KEY_ITEMS])
    }

    /** 按 bvid+cid 查一条。 */
    suspend fun find(bvid: String, cid: Long): DownloadedItem? =
        items.first().firstOrNull { it.bvid == bvid && it.cid == cid }

    /** 该视频是否已缓存（任意分P）。 */
    suspend fun isDownloaded(bvid: String, cid: Long): Boolean =
        find(bvid, cid) != null

    /** 写入一条（下载完成后调用）。已存在则覆盖。 */
    suspend fun upsert(item: DownloadedItem) {
        context.downloadDataStore.edit { prefs ->
            val list = parse(prefs[KEY_ITEMS]).toMutableList()
            list.removeAll { it.bvid == item.bvid && it.cid == item.cid }
            list.add(0, item) // 最新在前
            prefs[KEY_ITEMS] = serialize(list)
        }
    }

    /** 删除一条记录（文件由调用方删）。 */
    suspend fun remove(bvid: String, cid: Long) {
        context.downloadDataStore.edit { prefs ->
            val list = parse(prefs[KEY_ITEMS]).filterNot {
                it.bvid == bvid && it.cid == cid
            }
            prefs[KEY_ITEMS] = serialize(list)
        }
    }

    /** 更新播放进度（离线"继续播放"用）。 */
    suspend fun updatePosition(bvid: String, cid: Long, positionMs: Long) {
        context.downloadDataStore.edit { prefs ->
            val list = parse(prefs[KEY_ITEMS]).map {
                if (it.bvid == bvid && it.cid == cid) {
                    it.copy(lastPositionMs = positionMs)
                } else {
                    it
                }
            }
            prefs[KEY_ITEMS] = serialize(list)
        }
    }

    // ---------------- 序列化 ----------------

    private fun parse(raw: String?): List<DownloadedItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::fromJson)
            }
        }.getOrDefault(emptyList())
    }

    private fun serialize(list: List<DownloadedItem>): String {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        return arr.toString()
    }

    private fun toJson(d: DownloadedItem): JSONObject = JSONObject().apply {
        put("bvid", d.bvid)
        put("cid", d.cid)
        put("aid", d.aid)
        put("title", d.title)
        put("cover", d.cover)
        put("authorName", d.authorName)
        put("pageIndex", d.pageIndex)
        put("pageLabel", d.pageLabel)
        put("quality", d.quality)
        put("qualityLabel", d.qualityLabel)
        put("videoPath", d.videoPath)
        put("audioPath", d.audioPath)
        put("videoBytes", d.videoBytes)
        put("audioBytes", d.audioBytes)
        put("durationSeconds", d.durationSeconds)
        put("downloadedAt", d.downloadedAt)
        put("lastPositionMs", d.lastPositionMs)
    }

    private fun fromJson(o: JSONObject): DownloadedItem = DownloadedItem(
        bvid = o.optString("bvid"),
        cid = o.optLong("cid"),
        aid = o.optLong("aid"),
        title = o.optString("title"),
        cover = o.optString("cover"),
        authorName = o.optString("authorName"),
        pageIndex = o.optInt("pageIndex", 0),
        pageLabel = o.optString("pageLabel"),
        quality = o.optInt("quality", 0),
        qualityLabel = o.optString("qualityLabel"),
        videoPath = o.optString("videoPath"),
        audioPath = o.optString("audioPath"),
        videoBytes = o.optLong("videoBytes"),
        audioBytes = o.optLong("audioBytes"),
        durationSeconds = o.optInt("durationSeconds"),
        downloadedAt = o.optLong("downloadedAt"),
        lastPositionMs = o.optLong("lastPositionMs"),
    )

    private companion object {
        val KEY_ITEMS = stringPreferencesKey("items_json")
    }
}

/**
 * 一条离线缓存。
 *
 * ## 为什么存**绝对路径**而不是文件名
 *
 * 媒体文件放在应用私有目录，路径在安装期稳定；
 * 存绝对路径省去"拼路径"这一步，也便于直接交给 ExoPlayer。
 *
 * ## 音视频分离
 *
 * B 站 DASH 是音视频分轨，所以有 [videoPath] 与 [audioPath] 两个文件。
 * 播放时用 `MergingMediaSource` 合并 —— 与在线播放走**同一套**
 * [com.example.biliv3.player.PlayerFactory.buildMediaSource] 逻辑，
 * 避免离线/在线两套播放路径产生行为差异。
 *
 * @param lastPositionMs 上次播放位置。离线"继续播放"靠它
 *   （在线场景这个信息由 `history/cursor` 接口提供，
 *   但离线时可能没网，所以本地也存一份）
 */
data class DownloadedItem(
    val bvid: String,
    val cid: Long,
    val aid: Long,
    val title: String,
    val cover: String,
    val authorName: String,
    /** 分P 序号（0 基）。单P 恒为 0。 */
    val pageIndex: Int,
    /** 分P 标签，如 `P2`。单P 为空串。 */
    val pageLabel: String,
    /** 清晰度码（B 站 qn）。 */
    val quality: Int,
    val qualityLabel: String,
    val videoPath: String,
    val audioPath: String,
    val videoBytes: Long,
    val audioBytes: Long,
    val durationSeconds: Int,
    val downloadedAt: Long,
    val lastPositionMs: Long,
) {
    /** 总字节数。UI 显示"已用空间"用。 */
    val totalBytes: Long get() = videoBytes + audioBytes

    /** 是否音视频都下好了。 */
    val isComplete: Boolean
        get() = videoPath.isNotEmpty() && videoBytes > 0
}
