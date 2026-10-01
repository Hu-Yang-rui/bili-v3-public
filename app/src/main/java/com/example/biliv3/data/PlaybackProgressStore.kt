package com.example.biliv3.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore 实例（进度库独立一份，与设置分开，避免互相拖慢）。 */
private val Context.progressDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_progress",
)

/**
 * 播放进度持久化（**在线播放**的断点续播）。
 *
 * ## 为什么需要它（此前这是缺的）
 *
 * 首版**只有离线缓存**存了 `lastPositionMs`（见 `DownloadStore`），
 * 在线播放**完全不记进度** —— 退出视频再进来，永远从 0 开始。
 * 而「播放进度记忆」是 B 站移动端的基本行为（历史记录页显示进度条、
 * 再次进入提示"上次看到 12:34"）。
 *
 * ## 为什么本地存一份，而不只依赖服务端历史
 *
 * 服务端的 `history/cursor` 有**分钟级延迟**，且需要登录：
 * - 未登录用户没有服务端历史 → 本地这份是他们唯一的续播来源
 * - 登录用户刚退出就重进 → 服务端还没写入，本地能立刻续上
 *
 * 两者关系：**本地负责即时性，服务端负责跨端同步**，都要有。
 *
 * ## 键的设计
 *
 * `bvid:cid` —— 分P 有独立 cid，每个分P 的进度必须独立
 * （否则 P1 看到 10 分钟，切到 P2 也显示 10 分钟）。
 *
 * ## 为什么用 String 值存毫秒而不是 Long
 *
 * 键本身要拼 `bvid:cid`，已经是 String；值用 Long 更省空间，
 * 但 DataStore 的 Preferences 支持 `longPreferencesKey`。
 * 这里用 Long —— 注意读的时候要做类型兜底（历史版本可能存过 String）。
 */
class PlaybackProgressStore(context: Context) {

    private val store = context.applicationContext.progressDataStore

    /**
     * 全部进度快照。
     *
     * UI（历史列表、详情页）用它一次拿到多个视频的进度，
     * 避免 N 次单独查询。
     */
    val all: Flow<Map<String, Long>> = store.data.map { p ->
        p.asMap()
            .filterKeys { it.name.startsWith(PREFIX) }
            .mapNotNull { (k, v) ->
                val value = when (v) {
                    is Long -> v
                    is Int -> v.toLong()
                    is String -> v.toLongOrNull()
                    else -> null
                } ?: return@mapNotNull null
                k.name.removePrefix(PREFIX) to value
            }
            .toMap()
    }

    /** 读某个视频/分P 的进度（毫秒）。没有记录返回 0。 */
    suspend fun get(bvid: String, cid: Long): Long = runCatching {
        val p = store.data.first()
        p[key(bvid, cid)] ?: 0L
    }.getOrDefault(0L)

    /** 写进度（毫秒）。 */
    suspend fun put(bvid: String, cid: Long, positionMs: Long) {
        if (positionMs <= 0L) return
        runCatching { store.edit { it[key(bvid, cid)] = positionMs } }
    }

    /**
     * 清除某个视频的进度。
     *
     * 「保存观看历史」关闭时，或视频看完（>95%）时调用 ——
     * 看完还留着进度会让下次进入弹出一个没意义的续播提示。
     */
    suspend fun clear(bvid: String, cid: Long) {
        runCatching { store.edit { it.remove(key(bvid, cid)) } }
    }

    /** 清空全部进度（设置页「清理缓存」用）。 */
    suspend fun clearAll() {
        runCatching {
            store.edit { p ->
                p.asMap().keys
                    .filter { it.name.startsWith(PREFIX) }
                    .forEach { p.remove(it) }
            }
        }
    }

    private fun key(bvid: String, cid: Long) = longPreferencesKey("$PREFIX$bvid:$cid")

    companion object {
        private const val PREFIX = "p_"

        /**
         * 续播提示的阈值。
         *
         * <5% 视为"没看"（用户只是点了一下封面），
         * >95% 视为"看完了"（提示续播没有意义）。
         * 这是官方 App 的行为，也是本项目 `AGENTS.md` §11 的验收要求。
         */
        const val MIN_RATIO = 0.05f
        const val MAX_RATIO = 0.95f

        /** 是否值得弹续播提示。 */
        fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs <= 0L || positionMs <= 0L) return false
            val ratio = positionMs.toFloat() / durationMs
            return ratio in MIN_RATIO..MAX_RATIO
        }

        /** 进度键（`bvid:cid`）。UI 与仓库共用同一拼法，避免不一致。 */
        fun keyOf(bvid: String, cid: Long): String = "$bvid:$cid"
    }
}
