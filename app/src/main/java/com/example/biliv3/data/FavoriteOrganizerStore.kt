package com.example.biliv3.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 本地整理数据的 DataStore（独立一份，与进度库分开）。 */
private val Context.organizerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "favorite_organizer",
)

/**
 * 收藏整理的**本地**数据。
 *
 * ## 🔴 本类最重要的一条：本地标签 ≠ B 站收藏夹
 *
 * 任务书 §4.2 明确要求：
 * > 需要区分 B 站服务器操作 和 App 本地管理操作
 * > 不能把本地标签误认为 B 站真实收藏夹字段
 *
 * B 站的收藏夹只有「文件夹」这一层，**没有标签概念**。
 * 本类的标签纯粹存在本机 DataStore 里：
 *
 * | | B 站收藏夹 | 本地标签（本类） |
 * |---|---|---|
 * | 存储 | B 站服务器 | 本机 DataStore |
 * | 换设备 | 跟着账号走 | **丢失** |
 * | 是否影响账号 | 是 | 否 |
 * | 取消收藏 | 需要服务器请求 | 无影响 |
 *
 * 所以 UI 上**必须**明确标注"本地"，不能让用户以为
 * 自己给视频打了标签就能在官方 App 里看到。
 *
 * ## 键的设计
 *
 * `bvid` → 标签集合。用 `stringSetPreferencesKey` 而不是拼字符串 ——
 * 后者在标签含分隔符时会解析错（如标签本身含逗号）。
 */
class FavoriteOrganizerStore(context: Context) {

    private val store = context.applicationContext.organizerDataStore

    /** 全部标签：`bvid` → 标签集合。 */
    val allTags: Flow<Map<String, Set<String>>> = store.data.map { p ->
        p.asMap()
            .filterKeys { it.name.startsWith(TAG_PREFIX) }
            .mapNotNull { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                val set = v as? Set<String> ?: return@mapNotNull null
                k.name.removePrefix(TAG_PREFIX) to set
            }
            .toMap()
    }

    /** 已整理标记：bvid 集合。 */
    val organized: Flow<Set<String>> = store.data.map { p ->
        p[KEY_ORGANIZED] ?: emptySet()
    }

    /** 读某个视频的标签。 */
    suspend fun tagsOf(bvid: String): Set<String> = runCatching {
        store.data.first()[tagKey(bvid)] ?: emptySet()
    }.getOrDefault(emptySet())

    /** 批量加标签（**本地操作**）。 */
    suspend fun addTags(bvids: List<String>, tags: Set<String>) {
        if (bvids.isEmpty() || tags.isEmpty()) return
        runCatching {
            store.edit { p ->
                bvids.forEach { bvid ->
                    val key = tagKey(bvid)
                    val current = p[key] ?: emptySet()
                    p[key] = current + tags
                }
            }
        }
    }

    /** 批量移除标签（**本地操作**）。 */
    suspend fun removeTags(bvids: List<String>, tags: Set<String>) {
        if (bvids.isEmpty() || tags.isEmpty()) return
        runCatching {
            store.edit { p ->
                bvids.forEach { bvid ->
                    val key = tagKey(bvid)
                    val current = p[key] ?: return@forEach
                    val next = current - tags
                    if (next.isEmpty()) p.remove(key) else p[key] = next
                }
            }
        }
    }

    /** 批量标记已整理（**本地操作**）。 */
    suspend fun markOrganized(bvids: List<String>) {
        if (bvids.isEmpty()) return
        runCatching {
            store.edit { p ->
                p[KEY_ORGANIZED] = (p[KEY_ORGANIZED] ?: emptySet()) + bvids
            }
        }
    }

    /** 批量取消已整理标记。 */
    suspend fun unmarkOrganized(bvids: List<String>) {
        if (bvids.isEmpty()) return
        runCatching {
            store.edit { p ->
                p[KEY_ORGANIZED] = (p[KEY_ORGANIZED] ?: emptySet()) - bvids.toSet()
            }
        }
    }

    /** 清空所有本地整理数据（不影响 B 站收藏）。 */
    suspend fun clearAll() {
        runCatching {
            store.edit { p ->
                p.asMap().keys
                    .filter { it.name.startsWith(TAG_PREFIX) || it.name == KEY_ORGANIZED.name }
                    .forEach { p.remove(it) }
            }
        }
    }

    /** 所有用过的标签（用于整理页的标签筛选）。 */
    suspend fun allUsedTags(): Set<String> = runCatching {
        store.data.first().asMap()
            .filterKeys { it.name.startsWith(TAG_PREFIX) }
            .values
            .filterIsInstance<Set<String>>()
            .flatten()
            .toSet()
    }.getOrDefault(emptySet())

    private fun tagKey(bvid: String) = stringSetPreferencesKey("$TAG_PREFIX$bvid")

    companion object {
        private const val TAG_PREFIX = "tag_"
        private val KEY_ORGANIZED = stringSetPreferencesKey("organized")

        /**
         * 建议标签（快速整理的快捷入口）。
         *
         * ⚠️ 这些只是**输入建议**，不是固定枚举 ——
         * 用户可以输入任意标签（存成任意 String）。
         */
        val SUGGESTED_TAGS = listOf("待观看", "稍后细看", "收藏级", "已下载", "素材")
    }
}
