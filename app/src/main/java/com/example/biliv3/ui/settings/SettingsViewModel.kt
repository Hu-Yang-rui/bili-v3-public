package com.example.biliv3.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.Settings
import com.example.biliv3.data.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel。
 *
 * ## 为什么这么"薄"
 *
 * 设置的真相在 [SettingsStore]（DataStore）。本类只做两件事：
 * 1. 把 Flow 转成 `StateFlow` 供 Compose 订阅
 * 2. 把 UI 事件转发给 Store
 *
 * 不在这里放业务逻辑 —— 设置项本身没有业务，
 * 它只是「被读写的键值」，逻辑属于**消费方**（播放器、首页）。
 *
 * ## `SharingStarted.WhileSubscribed(5_000)`
 *
 * 离开页面 5 秒后停止上游收集（省电），但保留最后一次的值 ——
 * 快速来回切页时不会闪烁。5 秒是官方推荐值：
 * 足够覆盖屏幕旋转 / 配置变更造成的瞬时取消订阅。
 */
class SettingsViewModel(
    private val store: SettingsStore,
) : ViewModel() {

    val settings: StateFlow<Settings> = store.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = Settings(),
    )

    // ---- 播放 ----

    fun setDefaultSpeed(v: Float) = launch { store.setDefaultSpeed(v) }

    fun setAutoPlay(v: Boolean) = launch { store.setAutoPlay(v) }

    fun setDefaultQuality(v: Int) = launch { store.setDefaultQuality(v) }

    fun setPreferH264(v: Boolean) = launch { store.setPreferH264(v) }

    // ---- 弹幕 ----

    fun setDanmakuEnabled(v: Boolean) = launch { store.setDanmakuEnabled(v) }

    fun setDanmakuAlpha(v: Float) = launch { store.setDanmakuAlpha(v) }

    fun setDanmakuFontScale(v: Float) = launch { store.setDanmakuFontScale(v) }

    fun setDanmakuArea(v: Float) = launch { store.setDanmakuArea(v) }

    /** 弹幕屏蔽关键词（逗号 / 空格 / 换行分隔，由 UI 解析后传入）。 */
    fun setDanmakuBlockKeywords(words: List<String>) =
        launch { store.setDanmakuBlockKeywords(words) }

    /** 弹幕类型屏蔽（1=滚动 4=底部 5=顶部）。 */
    fun setDanmakuBlockModes(modes: Set<Int>) =
        launch { store.setDanmakuBlockModes(modes) }

    // ---- 隐私 ----

    fun setSaveHistory(v: Boolean) = launch { store.setSaveHistory(v) }

    fun setPersonalizedRecommend(v: Boolean) = launch { store.setPersonalizedRecommend(v) }

    // ---- 通知 ----

    fun setNotifyReply(v: Boolean) = launch { store.setNotifyReply(v) }

    // ---- 播放 ----

    /** 退出 App 后自动进入小窗（v1.4.2 新增）。 */
    fun setAutoPip(v: Boolean) = launch { store.setAutoPip(v) }

    // ---- 空降助手 ----

    fun setSponsorBlockEnabled(v: Boolean) = launch { store.setSponsorBlockEnabled(v) }

    fun setSponsorBlockCategories(cats: Set<String>) =
        launch { store.setSponsorBlockCategories(cats) }

    fun setSponsorBlockShowToast(v: Boolean) = launch { store.setSponsorBlockShowToast(v) }

    fun setSponsorBlockAllowUndo(v: Boolean) = launch { store.setSponsorBlockAllowUndo(v) }

    // ---- 直播 ----

    /**
     * 隐身入场（v1.6.3）。
     *
     * 消费者：[com.example.biliv3.ui.live.LiveRoomViewModel.reportEntryIfNeeded]
     * —— 开启后进入直播间**完全不发**入场上报请求。
     */
    fun setLiveIncognito(v: Boolean) = launch { store.setLiveIncognito(v) }

    // ---- 烂梗库 ----
    //
    // 内容是**内置常量**（本项目整理），没有可配置项、也不会加载失败，
    // 所以这里没有任何设置项。设置页只展示条数与来源说明。

    // ---------------------------------------------------------------------
    // 第三方 AI 配置（v1.6.3）
    // ---------------------------------------------------------------------

    /**
     * AI 配置状态。
     *
     * ⚠️ **只暴露脱敏摘要，绝不暴露 Key 明文** —— 与 [CookieState] 同一条
     * 红线（§7.16-94）。UI 需要"有没有配 Key"就用 [AiConfig.hasKey]。
     */
    private val _aiConfig = kotlinx.coroutines.flow.MutableStateFlow(
        com.example.biliv3.data.ai.AiConfig(),
    )
    val aiConfig: StateFlow<com.example.biliv3.data.ai.AiConfig> = _aiConfig

    /** 由 `SettingsViewModelFactory` 注入（预览/单测里可为 null）。 */
    var aiConfigStore: com.example.biliv3.data.ai.AiConfigStore? = null

    /** 重新读一次 AI 配置（导入 / 清空后调）。 */
    fun refreshAiConfig() {
        _aiConfig.value = aiConfigStore?.snapshot()
            ?: com.example.biliv3.data.ai.AiConfig()
    }

    /**
     * 保存 AI 配置。
     *
     * ⚠️ [apiKey] 传 `null` = **不改动现有 Key**（用户只改模型名时，
     * 不该因为输入框空着就把已存的 Key 抹掉）。传空串才是显式清空。
     */
    fun saveAiConfig(baseUrl: String, model: String, apiKey: String?) {
        aiConfigStore?.save(baseUrl, model, apiKey)
        refreshAiConfig()
    }

    /** 清空 AI 配置（含 Key）。 */
    fun clearAiConfig() {
        aiConfigStore?.clear()
        refreshAiConfig()
    }

    // ---------------------------------------------------------------------
    // 开发者工具：Cookie 导入 / 导出
    // ---------------------------------------------------------------------

    /**
     * Cookie 管理状态。
     *
     * ⚠️ **只暴露字段名与脱敏摘要，绝不暴露值** —— 这是安全红线（§4.3）。
     * UI 需要"有没有登录"就用 [loggedIn]，不要自己去读 cookie。
     */
    data class CookieState(
        val loggedIn: Boolean = false,
        val fieldNames: List<String> = emptyList(),
        val missingEssentials: List<String> = emptyList(),
        /** 脱敏摘要（字段名 + 长度），可直接显示。 */
        val masked: String = "",
        /** 最近一次操作的结果文案（给用户看）。 */
        val message: String? = null,
        /** [message] 是否是错误。 */
        val isError: Boolean = false,
    )

    private val _cookie = kotlinx.coroutines.flow.MutableStateFlow(CookieState())
    val cookie: StateFlow<CookieState> = _cookie

    /**
     * 刷新 Cookie 状态。
     *
     * 每次导入 / 清除后都要调 —— 否则 UI 会停留在旧状态
     * （"界面显示已登录但实际请求仍使用旧 Cookie"的成因）。
     */
    fun refreshCookieState(message: String? = null, isError: Boolean = false) {
        val fields = authStore?.exportCookieFields().orEmpty()
        val missing = if (fields.isEmpty()) emptyList()
        else com.example.biliv3.data.auth.CookieCodec.missingEssentials(fields)
        _cookie.value = CookieState(
            loggedIn = authStore?.isLoggedIn == true,
            fieldNames = fields.keys.toList(),
            missingEssentials = missing,
            masked = if (fields.isEmpty()) "" else
                com.example.biliv3.data.auth.CookieCodec.maskedSummary(fields),
            message = message,
            isError = isError,
        )
    }

    /** 清除操作结果提示（消费后调，避免重复弹 toast）。 */
    fun consumeCookieMessage() {
        _cookie.value = _cookie.value.copy(message = null, isError = false)
    }

    /**
     * 导入 cookie 文本（覆盖现有登录态）。
     *
     * 流程：解析 → 校验 → 落库 → 清身份缓存 → 刷新状态 → **通知外部刷新账号**。
     *
     * ⚠️ 解析失败时**不改动任何已有凭据** —— 不能让一次手滑粘贴把登录态弄丢。
     */
    fun importCookie(raw: String) {
        when (val r = com.example.biliv3.data.auth.CookieCodec.parse(raw)) {
            is com.example.biliv3.data.auth.CookieCodec.Result.Fail -> {
                refreshCookieState("导入失败：${r.reason}", isError = true)
            }
            is com.example.biliv3.data.auth.CookieCodec.Result.Ok -> {
                val s = authStore
                if (s == null) {
                    refreshCookieState("导入失败：账号模块不可用", isError = true)
                    return
                }
                s.importCookie(com.example.biliv3.data.auth.CookieCodec.serialize(r.pairs))
                refreshCookieState(
                    "已导入 ${r.pairs.size} 个字段，正在校验登录态…",
                )
                // 身份是服务端算出来的，必须重新拉 —— 不能本地编
                onCookieChanged?.invoke()
            }
        }
    }

    /** 清除全部登录凭据。 */
    fun clearCookie() {
        val s = authStore
        if (s == null) {
            refreshCookieState("清除失败：账号模块不可用", isError = true)
            return
        }
        s.clear()
        refreshCookieState("已清除登录凭据（保留设备指纹）")
        onCookieChanged?.invoke()
    }

    /** 导出当前 cookie 为可重新导入的字符串（明文，调用方负责写文件 + 提示风险）。 */
    fun exportCookieText(): String? {
        val fields = authStore?.exportCookieFields().orEmpty()
        if (fields.isEmpty()) return null
        return com.example.biliv3.data.auth.CookieCodec.serialize(fields)
    }

    /**
     * 外部注入：账号相关缓存需要失效时调用（重拉 nav / 清收藏缓存等）。
     *
     * 用回调而不是在 VM 里直接依赖 `AuthRepository` —— 设置页不该知道
     * 有哪些页面要刷新；由导航层统一处理。
     */
    var onCookieChanged: (() -> Unit)? = null

    /**
     * 账号存储（可为 null —— 单测 / 预览里不注入）。
     *
     * 由 `SettingsViewModelFactory` 传入。
     */
    var authStore: com.example.biliv3.data.auth.AuthStore? = null

    // ⚠️ 原 `setThemeMode` 已移除（v1.1.3 移除浅色主题）。
    //
    // 没有消费者了就删掉 —— 留着一个"改了没反应"的方法
    // 等于埋一个死入口（§1.6）。

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
