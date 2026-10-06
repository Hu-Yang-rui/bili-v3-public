package com.example.biliv3.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore 实例（与设置、进度分开，避免互相拖慢）。 */
private val Context.attentionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "local_attention",
)

/**
 * 「特别关注」—— **纯本地**的关注标记。
 *
 * ## 🔴 它做什么、不做什么（这一节是本类存在的全部理由）
 *
 * **做**：
 * - 把一个 UP 主记进本机的一个列表
 * - 在用户主页与关注列表里显示明确标识
 * - 可以随时取消
 *
 * **不做**（**永不**做）：
 * - ❌ 不调用 `x/relation/modify`
 * - ❌ 不发送任何网络请求
 * - ❌ 不改变用户在 B 站的真实关注状态
 * - ❌ 不影响 UP 主的粉丝数
 *
 * ## 为什么必须有这个名字与标识（而不是"假装关注"）
 *
 * 如果 UI 上看起来与真实关注一样，用户会**误以为已经关注了** ——
 * 那是一个会持续误导人的假象（去网页版一看根本没关注）。
 * 所以：
 * - 名字用「特别关注」，并在副标题写明**仅本机可见**
 * - 列表里与真实关注**分区显示**，不混在一起
 * - 标识用**不同颜色 + 不同图标**，不只靠文字
 *
 * ## 为什么不用 `SpaceRepository.setFollow`
 *
 * 那个方法会真的发请求。本类的全部方法**没有任何网络依赖** ——
 * 构造它只需要 `Context`，拿不到 `OkHttpClient`，也就**不可能**
 * 误发请求。这是用类型系统保证的红线，不是靠"记得别调"。
 *
 * ## 存的是"能重建对象的最小完整集"
 *
 * 只存 mid + 名字 + 头像。**不存**粉丝数、等级、签名 ——
 * 那些会变，存下来必然过期（§7.10-54 的教训）。
 * 展示时若需要更多信息，由用户主页自己拉。
 */
class LocalAttentionStore(context: Context) {

    private val store = context.applicationContext.attentionDataStore

    /** 全部特别关注的用户（最新加入的在前）。 */
    val items: Flow<List<AttendedUser>> = store.data.map { p ->
        parse(p[KEY_ITEMS])
    }

    /** 是否已在特别关注里（同步快照，UI 首帧用）。 */
    suspend fun contains(mid: Long): Boolean =
        itemsSnapshot().any { it.mid == mid }

    /**
     * 加入 / 移除。
     *
     * @return 操作后是否在列表里（true = 已加入）
     */
    suspend fun toggle(user: AttendedUser): Boolean {
        if (user.mid <= 0L) return false
        var nowIn = false
        store.edit { p ->
            val list = parse(p[KEY_ITEMS])
            val existing = list.any { it.mid == user.mid }
            val next = if (existing) {
                nowIn = false
                list.filterNot { it.mid == user.mid }
            } else {
                nowIn = true
                // 最新在前
                listOf(user) + list.filterNot { it.mid == user.mid }
            }
            p[KEY_ITEMS] = serialize(next)
        }
        return nowIn
    }

    /** 直接移除（列表页的"取消"用）。 */
    suspend fun remove(mid: Long) {
        store.edit { p ->
            p[KEY_ITEMS] = serialize(parse(p[KEY_ITEMS]).filterNot { it.mid == mid })
        }
    }

    /** 清空（设置页"清空特别关注"用）。 */
    suspend fun clear() {
        store.edit { it.remove(KEY_ITEMS) }
    }

    private suspend fun itemsSnapshot(): List<AttendedUser> =
        runCatching { items.first() }.getOrDefault(emptyList())

    // ---------------- 序列化 ----------------
    //
    // 与 `DownloadStore` 同一套做法：一个列表项多字段时用 JSON 数组存，
    // 而不是把每个字段拆成一个 Preferences key（拆了就要写一整套
    // 拼/拆逻辑，且容易漏字段）。
    //
    // ⚠️ 用 `MiniJson` 的读侧 + `org.json` 的写侧？不行 ——
    // org.json 在本地单测里是 stub（调用即抛 not mocked），
    // 所以**读写两侧都用纯 Kotlin**，这样解析逻辑能被单测覆盖。

    private fun parse(raw: String?): List<AttendedUser> {
        if (raw.isNullOrBlank()) return emptyList()
        return MiniJson.elements(raw).mapNotNull { obj ->
            val mid = MiniJson.long(obj, "mid") ?: return@mapNotNull null
            if (mid <= 0L) return@mapNotNull null
            AttendedUser(
                mid = mid,
                name = MiniJson.string(obj, "name").orEmpty(),
                face = MiniJson.string(obj, "face").orEmpty(),
            )
        }
    }

    private fun serialize(list: List<AttendedUser>): String =
        list.joinToString(prefix = "[", postfix = "]", separator = ",") { u ->
            """{"mid":${u.mid},"name":${escape(u.name)},"face":${escape(u.face)}}"""
        }

    /** 极简 JSON 字符串转义（只需处理引号与反斜杠，名字里不会有换行）。 */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private companion object {
        private val KEY_ITEMS = stringPreferencesKey("items")
    }
}

/**
 * 一个被"特别关注"的用户。
 *
 * ⚠️ 只有**能重建对象的最小完整集**（§7.10-54）：
 * 粉丝数 / 等级 / 签名都会变，存下来必然过期。
 */
data class AttendedUser(
    val mid: Long,
    val name: String,
    val face: String,
) {
    /** 头像地址（走统一构造，避免缓存分裂）。 */
    fun faceUrl(size: Int = 96): String =
        com.example.biliv3.data.model.CoverUrls.avatar(face, size)
}
