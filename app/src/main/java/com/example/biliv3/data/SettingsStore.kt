package com.example.biliv3.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore 实例。挂在 Context 上，全进程唯一。 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

/**
 * 应用设置（持久化）。
 *
 * ## 设计原则
 *
 * 1. **存储与 UI 分离** —— 本类只负责读写，不知道任何 UI 细节。
 *    新增一个设置项 = 这里加两个成员（读 Flow + 写函数）。
 * 2. **默认值就是"合理默认"** —— [Settings] 的每个字段都有明确默认，
 *    首次安装不写任何东西就能正常工作。
 * 3. **Flow 驱动** —— UI 用 `collectAsStateWithLifecycle` 订阅，
 *    改一个开关后**所有订阅者立即同步**（不需要手动通知）。
 *
 * ## 为什么用 DataStore 而不是 SharedPreferences
 *
 * 同 [SearchHistoryStore]：DataStore 基于 Flow、写盘异步不阻塞主线程；
 * SharedPreferences 的 `commit()` 是同步磁盘写，主线程调用会掉帧。
 *
 * ## 为什么不放在 ViewModel 里
 *
 * 设置需要**跨页面生效**（播放器要读倍速、首页要读弹幕开关），
 * 放在某个页面的 ViewModel 里其他页面拿不到。
 * 由 [com.example.biliv3.AppContainer] 持有全应用唯一实例。
 */
class SettingsStore(
    private val context: Context,
) {

    /** 当前全部设置。UI 订阅它即可拿到最新值。 */
    val settings: Flow<Settings> = context.settingsDataStore.data.map { p ->
        Settings(
            // ---- 播放 ----
            defaultSpeed = p[KEY_SPEED] ?: Settings.DEFAULT_SPEED,
            autoPlay = p[KEY_AUTO_PLAY] ?: false,
            defaultQuality = p[KEY_QUALITY] ?: QUALITY_AUTO,
            // ---- 弹幕 ----
            danmakuEnabled = p[KEY_DANMAKU] ?: true,
            danmakuAlpha = p[KEY_DANMAKU_ALPHA] ?: 0.9f,
            danmakuFontScale = p[KEY_DANMAKU_FONT] ?: 1f,
            danmakuArea = p[KEY_DANMAKU_AREA] ?: 1f,
            danmakuBlockKeywords = (p[KEY_DANMAKU_BLOCK_KEYWORDS] ?: "")
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            danmakuBlockModes = (p[KEY_DANMAKU_BLOCK_MODES] ?: "")
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet(),
            // ---- 隐私 ----
            saveHistory = p[KEY_SAVE_HISTORY] ?: true,
            personalizedRecommend = p[KEY_PERSONALIZED] ?: true,
            // ---- 通知 ----
            notifyReply = p[KEY_NOTIFY_REPLY] ?: true,
            // ---- 通用 ----
            preferH264 = p[KEY_H264] ?: true,
            // ---- 外观 ----
            themeMode = ThemeMode.fromKey(p[KEY_THEME_MODE]),
        )
    }

    // ---------------- 播放 ----------------

    suspend fun setDefaultSpeed(v: Float) = edit { it[KEY_SPEED] = v }

    /** 进详情页是否自动起播。默认 false（省流量，与当前行为一致）。 */
    suspend fun setAutoPlay(v: Boolean) = edit { it[KEY_AUTO_PLAY] = v }

    /** 默认清晰度。`0` = 自动（取最高可用）。 */
    suspend fun setDefaultQuality(v: Int) = edit { it[KEY_QUALITY] = v }

    /**
     * 是否优先 H.264。
     *
     * ⚠️ 默认 **true**，且**不建议关闭**。
     * 判断逻辑是修「登录后无法播放」时加的：`dash.video` 里同一 id
     * 有多条不同编码，第一条可能是 HEVC，设备不支持硬解就黑屏。
     */
    suspend fun setPreferH264(v: Boolean) = edit { it[KEY_H264] = v }

    // ---------------- 弹幕 ----------------

    suspend fun setDanmakuEnabled(v: Boolean) = edit { it[KEY_DANMAKU] = v }

    suspend fun setDanmakuAlpha(v: Float) = edit { it[KEY_DANMAKU_ALPHA] = v }

    suspend fun setDanmakuFontScale(v: Float) = edit { it[KEY_DANMAKU_FONT] = v }

    suspend fun setDanmakuArea(v: Float) = edit { it[KEY_DANMAKU_AREA] = v }

    /**
     * 设置弹幕屏蔽关键词。
     *
     * 命中任一关键词的弹幕**不渲染**（本地过滤，不发请求）。
     * 消费者：`DanmakuLayer` 的 `blocked` 判定。
     */
    suspend fun setDanmakuBlockKeywords(words: List<String>) = edit {
        it[KEY_DANMAKU_BLOCK_KEYWORDS] = words
            .map { w -> w.trim() }
            .filter { w -> w.isNotEmpty() }
            .joinToString("\n")
    }

    /** 设置被屏蔽的弹幕类型（1=滚动 4=底部 5=顶部）。 */
    suspend fun setDanmakuBlockModes(modes: Set<Int>) = edit {
        it[KEY_DANMAKU_BLOCK_MODES] = modes.sorted().joinToString(",")
    }

    // ---------------- 外观 ----------------

    /** 主题模式三选一。消费者：`MainActivity.BiliApp` 决定 `darkTheme`。 */
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME_MODE] = mode.key }

    // ---------------- 隐私 ----------------

    /**
     * 是否保存观看历史。
     *
     * ## ⚠️ 这个开关**有真实消费者**（修「空转设置项」时补的接线）
     *
     * 消费点在 [com.example.biliv3.ui.video.VideoDetailViewModel.reportProgress]：
     * 关闭后播放进度**不再写本地续播记录**，也不再上报服务端历史。
     * 之前这个开关只写不读，属于「反模式 #4 空转设置项」。
     */
    suspend fun setSaveHistory(v: Boolean) = edit { it[KEY_SAVE_HISTORY] = v }

    /**
     * 个性化推荐。
     *
     * 消费者在 [com.example.biliv3.ui.home.HomeViewModel]：
     * 关闭时首页推荐流改用**分区榜**（`ranking/region`）作为内容源，
     * 而不是基于观看偏好的 `rcmd`。
     *
     * ⚠️ 这是一个**近似实现**：B 站没有"关闭个性化"的公开接口参数
     * （`rcmd` 无 `personalized=0` 之类开关），所以只能换内容源。
     * 与其留一个改了没反应的开关，不如换成行为可见的降级源。
     */
    suspend fun setPersonalizedRecommend(v: Boolean) = edit { it[KEY_PERSONALIZED] = v }

    // ---------------- 通知 ----------------

    /**
     * 回复我的通知开关。
     *
     * 消费者：[com.example.biliv3.ui.home.TopNav] 的铃铛红点。
     * 关闭后**红点不再因"回复我的"未读而亮**（私信未读仍然亮）。
     */
    suspend fun setNotifyReply(v: Boolean) = edit { it[KEY_NOTIFY_REPLY] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    companion object {
        /** 清晰度「自动」的哨兵值。 */
        const val QUALITY_AUTO = 0

        private val KEY_SPEED = floatPreferencesKey("default_speed")
        private val KEY_AUTO_PLAY = booleanPreferencesKey("auto_play")
        private val KEY_QUALITY = intPreferencesKey("default_quality")
        private val KEY_DANMAKU = booleanPreferencesKey("danmaku_enabled")
        private val KEY_DANMAKU_ALPHA = floatPreferencesKey("danmaku_alpha")
        private val KEY_DANMAKU_FONT = floatPreferencesKey("danmaku_font_scale")
        private val KEY_DANMAKU_AREA = floatPreferencesKey("danmaku_area")
        private val KEY_SAVE_HISTORY = booleanPreferencesKey("save_history")
        private val KEY_PERSONALIZED = booleanPreferencesKey("personalized_recommend")
        private val KEY_NOTIFY_REPLY = booleanPreferencesKey("notify_reply")
        private val KEY_H264 = booleanPreferencesKey("prefer_h264")

        /** 弹幕屏蔽关键词。用 `\n` 连接存储（DataStore 无 StringSet 语义保证顺序）。 */
        private val KEY_DANMAKU_BLOCK_KEYWORDS = androidx.datastore.preferences.core
            .stringPreferencesKey("danmaku_block_keywords")

        /** 屏蔽的弹幕类型：1=滚动 4=底部 5=顶部（逗号分隔）。 */
        private val KEY_DANMAKU_BLOCK_MODES = androidx.datastore.preferences.core
            .stringPreferencesKey("danmaku_block_modes")

        /** 主题模式：`system` / `dark` / `light`。 */
        private val KEY_THEME_MODE = androidx.datastore.preferences.core
            .stringPreferencesKey("theme_mode")
    }
}

/**
 * 主题模式（设置页「外观」三选一）。
 *
 * ⚠️ 之前 `BiliTheme` **只认 `isSystemInDarkTheme()`**，用户无法切换 ——
 * 这是「深色模式」这项功能被判为缺失的直接原因。
 */
enum class ThemeMode {
    /** 跟随系统。 */
    System,

    /** 强制深色（本项目的主态）。 */
    Dark,

    /** 强制浅色。 */
    Light;

    val key: String get() = when (this) {
        System -> "system"
        Dark -> "dark"
        Light -> "light"
    }

    companion object {
        fun fromKey(k: String?): ThemeMode = when (k) {
            "dark" -> Dark
            "light" -> Light
            else -> System
        }
    }
}

/**
 * 设置快照。
 *
 * 所有字段都有默认值 —— 首次安装什么都不写也能正常工作。
 */
data class Settings(
    // ---- 播放 ----
    /** 默认倍速。 */
    val defaultSpeed: Float = DEFAULT_SPEED,
    /** 进详情页自动起播。 */
    val autoPlay: Boolean = false,
    /** 默认清晰度。0 = 自动。 */
    val defaultQuality: Int = SettingsStore.QUALITY_AUTO,
    /** 优先 H.264（关掉可能黑屏，见 setPreferH264 说明）。 */
    val preferH264: Boolean = true,

    // ---- 弹幕 ----
    val danmakuEnabled: Boolean = true,
    val danmakuAlpha: Float = 0.9f,
    val danmakuFontScale: Float = 1f,
    val danmakuArea: Float = 1f,
    /** 屏蔽关键词（命中任一即不渲染）。 */
    val danmakuBlockKeywords: List<String> = emptyList(),
    /** 屏蔽的弹幕类型：1=滚动 4=底部 5=顶部。空集 = 全不屏蔽。 */
    val danmakuBlockModes: Set<Int> = emptySet(),

    // ---- 隐私 ----
    /** 是否保存观看历史。 */
    val saveHistory: Boolean = true,
    /** 是否接收个性化推荐。 */
    val personalizedRecommend: Boolean = true,

    // ---- 通知 ----
    val notifyReply: Boolean = true,

    // ---- 外观 ----
    /** 主题模式。 */
    val themeMode: ThemeMode = ThemeMode.System,
) {
    companion object {
        const val DEFAULT_SPEED = 1f
    }
}
