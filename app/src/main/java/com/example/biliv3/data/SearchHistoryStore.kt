package com.example.biliv3.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore 实例。挂在 Context 上，全进程唯一。 */
private val Context.searchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "search_history",
)

/**
 * 搜索历史。
 *
 * ## 为什么用 DataStore 而不是 SharedPreferences
 *
 * DataStore 基于 Flow，写入异步且不阻塞主线程；
 * SharedPreferences 的 `commit()` 是同步磁盘写，主线程调用会掉帧。
 * 而且 DataStore 已在依赖里（`AGENTS.md` §3.2）。
 *
 * ## 为什么用「单字符串 + 分隔符」而不是 stringSet
 *
 * `stringSet` 天然去重，但**不保证顺序**（`Set` 无序），
 * 而搜索历史必须"最近的在最前"。所以这里用单个字符串按顺序拼接，
 * 去重逻辑自己写（见 [add]）。
 */
class SearchHistoryStore(
    private val context: Context,
) {

    /** 最近搜索词，最新在前。 */
    val history: Flow<List<String>> = context.searchDataStore.data.map { prefs ->
        prefs[KEY_HISTORY].splitToList()
    }

    /**
     * 记录一次搜索。
     *
     * 已存在的词会被**移到最前**而不是重复添加 ——
     * 反复搜同一个词时它应该浮到顶部，而不是堆一屏重复项。
     */
    suspend fun add(keyword: String) {
        val word = keyword.trim()
        if (word.isEmpty()) return

        context.searchDataStore.edit { prefs ->
            val current = prefs[KEY_HISTORY].splitToList()
            val next = (listOf(word) + current.filter { it != word }).take(MAX_ITEMS)
            prefs[KEY_HISTORY] = next.joinToString(SEPARATOR)
        }
    }

    /** 删除单条。 */
    suspend fun remove(keyword: String) {
        context.searchDataStore.edit { prefs ->
            val current = prefs[KEY_HISTORY].splitToList()
            prefs[KEY_HISTORY] = current.filter { it != keyword }.joinToString(SEPARATOR)
        }
    }

    /** 清空全部。 */
    suspend fun clear() {
        context.searchDataStore.edit { prefs ->
            prefs.remove(KEY_HISTORY)
        }
    }

    private fun String?.splitToList(): List<String> =
        this?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

    private companion object {
        val KEY_HISTORY = stringPreferencesKey("history_raw")

        /**
         * 分隔符用不可见字符 `\u001F`（Unit Separator）。
         *
         * 不能用逗号 —— 搜索词本身可能含逗号，会拆错。
         */
        const val SEPARATOR = "\u001F"

        /** 上限 20 条，超出丢最旧的。 */
        const val MAX_ITEMS = 20
    }
}
