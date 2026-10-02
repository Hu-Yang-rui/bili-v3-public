package com.example.biliv3.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个已保存的账号。
 *
 * ## 为什么把 cookie 一起存
 *
 * B 站的登录态**完全由 cookie 承载**（`SESSDATA` + `bili_jct` + `DedeUserID`）。
 * 切换账号的本质就是"换一套 cookie" —— 所以每个账号必须带着自己的
 * cookie 独立保存，否则切过去只是换了个昵称、请求仍用旧身份，
 * 那才是最糟的「串号」。
 *
 * @param mid 账号 id（`DedeUserID`）。0 表示凭据不完整，不应入库。
 * @param cookie 该账号的完整 cookie 串。**敏感数据**，必须加密存储。
 * @param savedAt 保存时间（毫秒），用于列表排序（最近使用在前）。
 */
data class SavedAccount(
    val mid: Long,
    val name: String,
    val face: String,
    val cookie: String,
    val savedAt: Long,
) {
    /** 头像地址。走统一构造（含 https 归一 + 尺寸后缀剥离）。 */
    fun faceUrl(size: Int = 120): String =
        com.example.biliv3.data.model.CoverUrls.avatar(face, size)

    /** 展示名。昵称为空时退化成 UID，不留空白。 */
    val displayName: String get() = name.ifEmpty { "UID $mid" }
}

/**
 * 多账号存储。
 *
 * ## 与 [AuthStore] 的关系（关键设计）
 *
 * | 类 | 职责 |
 * |---|---|
 * | [AuthStore] | **当前生效**的那一个账号（全 App 读写它的 cookie） |
 * | [AccountStore] | **曾经登录过**的全部账号（用于切换） |
 *
 * 这样设计的原因：现有 20+ 处代码都通过 `AuthStore.cookie` 拿登录态，
 * 若把 [AuthStore] 改成"账号列表 + 当前索引"，那些调用点全要改，
 * 且极易漏改 → 串号。**保持 [AuthStore] 语义不变**（它就是"当前账号"），
 * 多账号只是它外面的一个历史列表，改动面最小、语义最清晰。
 *
 * 切换账号 = 把当前 [AuthStore] 的凭据存回列表，再把目标账号的凭据
 * 写进 [AuthStore]。所有既有代码无需感知这个过程。
 *
 * ## 为什么单独一个加密文件
 *
 * 与 [AuthStore] 用同一个 `MasterKey`（同一把 Keystore 密钥），
 * 但分开文件：账号列表是"多个凭据"，与"当前凭据"生命周期不同
 * （退出登录不该清空列表 —— 用户可能只是想切另一个号）。
 */
class AccountStore(context: Context) : AccountListStore {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * 全部已保存账号，**最近使用在前**。
     *
     * 解析失败（旧版本格式 / 数据损坏）时返回空列表而不是抛异常 ——
     * 账号列表读不出来不该让整个「切换账号」页崩溃，
     * 用户仍能重新登录添加。
     */
    override fun list(): List<SavedAccount> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val mid = o.optLong("mid", 0L)
                val cookie = o.optString("cookie")
                // 缺 mid 或 cookie 的条目是坏数据，直接丢弃
                if (mid <= 0L || cookie.isEmpty()) return@mapNotNull null
                SavedAccount(
                    mid = mid,
                    name = o.optString("name"),
                    face = o.optString("face"),
                    cookie = cookie,
                    savedAt = o.optLong("savedAt", 0L),
                )
            }.sortedByDescending { it.savedAt }
        }.getOrDefault(emptyList())
    }

    /** 按 mid 取单个账号。 */
    override fun get(mid: Long): SavedAccount? = list().firstOrNull { it.mid == mid }

    /**
     * 新增或更新一个账号。
     *
     * 同 mid 视为**更新**（刷新 cookie / 昵称 / 头像并置顶），
     * 而不是插入重复项 —— 否则重复登录同一个号会出现两条一样的记录。
     */
    override fun upsert(account: SavedAccount) {
        if (account.mid <= 0L || account.cookie.isEmpty()) return
        val current = list().filterNot { it.mid == account.mid }
        write(listOf(account) + current)
    }

    /** 移除一个账号。 */
    override fun remove(mid: Long) {
        write(list().filterNot { it.mid == mid })
    }

    /** 清空全部账号（「退出并清除全部」用）。 */
    override fun clear() {
        prefs.edit().remove(KEY_ACCOUNTS).apply()
    }

    private fun write(accounts: List<SavedAccount>) {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("mid", a.mid)
                    put("name", a.name)
                    put("face", a.face)
                    put("cookie", a.cookie)
                    put("savedAt", a.savedAt)
                },
            )
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "biliv3_accounts"
        const val KEY_ACCOUNTS = "accounts"
    }
}
