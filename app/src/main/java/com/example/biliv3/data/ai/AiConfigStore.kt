package com.example.biliv3.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 第三方 AI 的配置存储。
 *
 * ## 🔴 API Key 必须加密（安全红线）
 *
 * API Key 与 `SESSDATA` 同级：拿到就能以用户身份调用（并**花用户的钱**）。
 * 明文落在 `/data/data/.../shared_prefs/` 下，root 或备份提取都能读走。
 *
 * 所以复用 `AuthStore` 的同一套方案：[EncryptedSharedPreferences] +
 * Android Keystore（密钥不可导出，卸载即销毁）。
 *
 * ## 🔴 三条绝不违反的规则
 *
 * 1. **不硬编码**：本项目**不内置**任何 Key。没有配置时功能直接不可用，
 *    而不是偷偷用一个"公共 Key"（那会让所有人的用量记在一个人头上）。
 * 2. **不进日志**：任何分支都不 `Log` 这个值 —— 连长度都不打。
 * 3. **不在页面显示完整值**：UI 只显示 [maskedKey]（前 4 位 + 长度），
 *    与 `CookieCodec.maskedSummary` 同一条约定（§7.16-94）。
 *
 * ## 为什么与 [com.example.biliv3.data.SettingsStore] 分开
 *
 * `SettingsStore` 是**明文** DataStore（倍速、弹幕这些不敏感）。
 * 把 Key 混进去会让整个设置文件都必须加密，代价大且没必要。
 * 分开后：敏感的在加密存储，普通的在 DataStore，各司其职。
 *
 * ## 为什么不用 DataStore + 手写 AES
 *
 * 同 `AuthStore`：`EncryptedSharedPreferences` 是官方现成方案，
 * 自己写 KeyStore + AES-GCM 容易出错（IV 复用、填充模式等）。
 */
class AiConfigStore(context: Context) {

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

    private val _config = MutableStateFlow(read())

    /** 当前配置（UI 订阅它；改完立即同步）。 */
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    /** 当前配置快照（同步读，调用方发请求时用）。 */
    fun snapshot(): AiConfig = _config.value

    /**
     * **明文** API Key（仅发请求时调用）。
     *
     * ## 为什么单独一个方法，而不是放进 [AiConfig]
     *
     * [AiConfig] 会传到 UI 层。若它带明文 Key，任何一次
     * `toString()` / 日志打印 / 崩溃报告都会泄露它。
     *
     * 所以 `AiConfig` 只有 `hasKey` + `maskedKey`，明文**只在
     * 发请求的那一瞬**从这里现取 —— 这让"哪里会碰到明文"
     * 在代码里**只有一处**，可审计。
     *
     * ⚠️ 调用方**绝不可**把它写进日志、异常消息或 UI。
     */
    fun plainKey(): String = prefs.getString(KEY_API_KEY, "").orEmpty()

    /**
     * 保存配置。
     *
     * ⚠️ [apiKey] 传 `null` 表示"不改动现有 Key"（用户只改了模型名时，
     * 不该因为输入框是空的就把已存的 Key 抹掉）。
     * 传空串才是"显式清空"。
     */
    fun save(
        baseUrl: String,
        model: String,
        apiKey: String?,
    ) {
        prefs.edit().apply {
            putString(KEY_BASE_URL, baseUrl.trim())
            putString(KEY_MODEL, model.trim())
            if (apiKey != null) putString(KEY_API_KEY, apiKey.trim())
        }.apply()
        _config.value = read()
    }

    /** 清空全部配置（含 Key）。 */
    fun clear() {
        prefs.edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_MODEL)
            .remove(KEY_API_KEY)
            .apply()
        _config.value = read()
    }

    private fun read(): AiConfig = AiConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
        hasKey = prefs.getString(KEY_API_KEY, "").orEmpty().isNotEmpty(),
        maskedKey = mask(prefs.getString(KEY_API_KEY, "").orEmpty()),
    )

    /**
     * 脱敏显示：`sk-a…(48)`。
     *
     * 只给**前 4 位 + 总长度** —— 前缀能帮用户确认"我填的是哪一把"，
     * 而 4 位不足以缩小爆破范围。这与 `CookieCodec` 的处理一致。
     *
     * ⚠️ 绝不在任何情况下返回完整值。
     */
    private fun mask(key: String): String {
        if (key.isEmpty()) return ""
        val head = key.take(MASK_PREFIX)
        return "$head…(${key.length})"
    }

    private companion object {
        const val PREFS_NAME = "biliv3_ai_config"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"

        /** 脱敏保留的前缀长度。 */
        const val MASK_PREFIX = 4
    }
}

/**
 * AI 配置快照。
 *
 * @param baseUrl OpenAI 风格 API 的基地址，如
 *   `https://api.openai.com/v1`。**不含** `/chat/completions` ——
 *   那一截由调用方拼（便于兼容各家路径不同的服务）。
 * @param model 模型名，如 `gpt-4o-mini`。
 * @param hasKey 是否已配置 Key（UI 据此判断"能不能用"）。
 * @param maskedKey 脱敏后的 Key（可直接显示）。
 */
data class AiConfig(
    val baseUrl: String = "",
    val model: String = "",
    val hasKey: Boolean = false,
    val maskedKey: String = "",
) {
    /** 是否可用于调用（三项都齐才可用）。 */
    val usable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && hasKey

    /** 还缺什么（给用户看的提示）。 */
    val missingHint: String
        get() = when {
            baseUrl.isBlank() -> "请先填写 API 地址"
            model.isBlank() -> "请先填写模型名"
            !hasKey -> "请先填写 API Key"
            else -> ""
        }
}
