package com.example.biliv3.data.emote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.biliv3.data.api.BiliApi
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** 表情缓存用的 DataStore。 */
private val Context.emoteDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "emote_panel",
)

/**
 * 表情仓库（v1.6.8）。
 *
 * ---
 *
 * # 数据来源
 *
 * `GET x/emote/user/panel?business=reply` —— **B 站官方接口**（实测 `code=0`）。
 *
 * ⚠️ **未登录时 `packages=null`** —— 表情包是**账号相关**的
 * （不同账号解锁的收藏集不同），所以未登录时没有表情可发，
 * 面板应显示"登录后可用"而不是空列表。
 *
 * # 为什么缓存
 *
 * 431 个表情、5 个包，响应体不小（实测约 100 KB+）。
 * 私信面板是**高频打开**的地方，每次开都拉一遍是浪费。
 *
 * 缓存策略与 `MemeRepository` 不同：那个是"本地优先、远程补充"，
 * 这里是"**网络优先、缓存兜底**"—— 因为表情包**会变**
 * （账号解锁新收藏集后必须能看到），不能拿旧缓存当真相。
 *
 * # 为什么用共享 client（带 CookieJar）
 *
 * 与 AI 第三方调用相反：这个接口**就是 B 站自己的**，
 * 必须带登录态才拿得到数据。所以走 [api]（共享 client）。
 */
class EmoteRepository(
    private val api: BiliApi,
    context: Context,
) {

    private val store = context.applicationContext.emoteDataStore

    /**
     * 取表情面板。
     *
     * ## 流程
     *
     * 1. 有网络 → 请求 → 成功则**写缓存**并返回
     * 2. 请求失败 → 用缓存（如果有）
     * 3. 都没有 → 返回空列表（调用方显示空态 / 登录引导）
     *
     * @return (包列表, 是否来自缓存, 错误信息)
     */
    suspend fun load(): EmoteLoad {
        val fetched = runCatching { api.emotePanel("reply") }

        val json = fetched.getOrNull()
        if (json != null && json.optInt("code", -1) == 0) {
            val packages = EmoteParser.parse(json)
            if (packages.isNotEmpty()) {
                runCatching { store.edit { it[KEY_CACHE] = json.toString() } }
                return EmoteLoad(packages, fromCache = false, error = null)
            }
            // 接口成功但没解析出表情 —— 通常是**未登录**（packages=null）。
            // 如实说明，不要显示成"没有表情包"。
            return EmoteLoad(
                packages = emptyList(),
                fromCache = false,
                error = "登录后才能使用表情",
            )
        }

        // ---- 请求失败 → 缓存兜底 ----
        val cached = runCatching { store.data.first()[KEY_CACHE] }.getOrNull()
        if (!cached.isNullOrBlank()) {
            val packages = EmoteParser.parse(runCatching { JSONObject(cached) }.getOrNull())
            if (packages.isNotEmpty()) {
                val why = fetched.exceptionOrNull()?.message
                return EmoteLoad(
                    packages = packages,
                    fromCache = true,
                    error = if (why.isNullOrBlank()) null else "无法刷新表情，正在显示缓存",
                )
            }
        }

        return EmoteLoad(
            packages = emptyList(),
            fromCache = false,
            error = fetched.exceptionOrNull()?.let { userHint(it) } ?: "表情加载失败",
        )
    }

    /** 把异常翻译成用户看得懂的话（与项目其它仓库同一约定）。 */
    private fun userHint(e: Throwable): String {
        val s = (e.message ?: "").lowercase()
        return when {
            s.contains("unable to resolve host") || s.contains("timeout") ||
                s.contains("enotfound") || s.contains("failed to connect") ->
                "网络不可用，请检查连接后重试"
            else -> "表情加载失败，请稍后重试"
        }
    }

    private companion object {
        val KEY_CACHE = stringPreferencesKey("panel_json")
    }
}

/**
 * 一次表情加载的结果。
 *
 * @param packages 表情包（**可能为空** —— 未登录 / 加载失败）
 * @param fromCache 是否来自缓存（UI 可提示"正在显示缓存"）
 * @param error 失败/降级原因（null = 一切正常）
 */
data class EmoteLoad(
    val packages: List<EmotePackage>,
    val fromCache: Boolean,
    val error: String?,
)
