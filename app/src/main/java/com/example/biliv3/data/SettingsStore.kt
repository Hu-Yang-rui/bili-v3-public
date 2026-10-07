package com.example.biliv3.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.biliv3.data.quality.AutoQuality
import com.example.biliv3.data.quality.AutoQualitySettings
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
            // ---- 播放 ----
            autoPip = p[KEY_AUTO_PIP] ?: false,
            // ---- 空降助手 ----
            sponsorBlockEnabled = p[KEY_SB_ENABLED] ?: false,
            sponsorBlockCategories = (p[KEY_SB_CATEGORIES] ?: "")
                .takeIf { it.isNotEmpty() }
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: SponsorBlockDefaults.CATEGORIES,
            sponsorBlockShowToast = p[KEY_SB_TOAST] ?: true,
            sponsorBlockAllowUndo = p[KEY_SB_UNDO] ?: true,
            // ---- 直播 ----
            liveIncognito = p[KEY_LIVE_INCOGNITO] ?: false,
            // ---- 通用 ----
            preferH264 = p[KEY_H264] ?: true,
            // ---- 自动画质 / 音质（未发版）----
            autoQuality = AutoQualitySettings(
                enabled = p[KEY_AQ_ENABLED] ?: false,
                preferredQn = p[KEY_AQ_PREFERRED] ?: AutoQuality.QN_AUTO,
                fallbackQn = p[KEY_AQ_FALLBACK] ?: AutoQuality.QN_AUTO,
                autoAudio = p[KEY_AQ_AUTO_AUDIO] ?: true,
                dolbyAtmos = p[KEY_AQ_DOLBY] ?: false,
                hiResAudio = p[KEY_AQ_FLAC] ?: false,
                unlock = AutoQuality.UnlockFlags(
                    unlock8K = p[KEY_AQ_U8K] ?: true,
                    unlockDolbyVision = p[KEY_AQ_UDV] ?: true,
                    unlockAv1 = p[KEY_AQ_UAV1] ?: true,
                ),
                liveAutoQuality = p[KEY_AQ_LIVE] ?: true,
            ),
            // ---- 外观 ----
        )
    }

    // ---------------- 播放 ----------------

    suspend fun setDefaultSpeed(v: Float) = edit { it[KEY_SPEED] = v }

    /** 进详情页是否自动起播。默认 false（省流量，与当前行为一致）。 */
    suspend fun setAutoPlay(v: Boolean) = edit { it[KEY_AUTO_PLAY] = v }

    /** 默认清晰度。`0` = 自动（取最高可用）。 */
    suspend fun setDefaultQuality(v: Int) = edit { it[KEY_QUALITY] = v }

    // ---------------- 自动画质 / 音质（未发版）----------------

    /** 自动画质总开关。关 = 保持改动前的行为。 */
    suspend fun setAutoQualityEnabled(v: Boolean) = edit { it[KEY_AQ_ENABLED] = v }

    /** 首选画质（`0` = 不指定 → 自动最高）。 */
    suspend fun setAutoQualityPreferred(v: Int) = edit { it[KEY_AQ_PREFERRED] = v }

    /** 备选画质（首选拿不到时用）。 */
    suspend fun setAutoQualityFallback(v: Int) = edit { it[KEY_AQ_FALLBACK] = v }

    /** 自动选最高音质。 */
    suspend fun setAutoQualityAutoAudio(v: Boolean) = edit { it[KEY_AQ_AUTO_AUDIO] = v }

    /** 杜比全景声（拿不到会自动回退普通 AAC）。 */
    suspend fun setAutoQualityDolby(v: Boolean) = edit { it[KEY_AQ_DOLBY] = v }

    /** Hi-Res 无损（拿不到会自动回退普通 AAC）。 */
    suspend fun setAutoQualityFlac(v: Boolean) = edit { it[KEY_AQ_FLAC] = v }

    /** 直播自动最高画质。 */
    suspend fun setAutoQualityLive(v: Boolean) = edit { it[KEY_AQ_LIVE] = v }

    /**
     * 解锁开关（对应原脚本的「解锁设置」面板）。
     *
     * ⚠️ 这些只是**请求参数**，不保证服务端会给 —— 非会员照样只拿 480P。
     *
     * ⚠️ **只有三个**：杜比音频位（32）与无损位（4096）实测会让整条取流
     * 请求返回 `-400`，所以它们**不是可选项**，没有对应的键与开关。
     * 见 `AutoQuality.Fnval` 的实测表。
     */
    suspend fun setAutoQualityUnlock(
        unlock8K: Boolean,
        dolbyVision: Boolean,
        av1: Boolean,
    ) = edit {
        it[KEY_AQ_U8K] = unlock8K
        it[KEY_AQ_UDV] = dolbyVision
        it[KEY_AQ_UAV1] = av1
    }

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

    // ---------------- 空降助手 ----------------

    suspend fun setSponsorBlockEnabled(v: Boolean) = edit { it[KEY_SB_ENABLED] = v }

    suspend fun setSponsorBlockCategories(cats: Set<String>) = edit {
        it[KEY_SB_CATEGORIES] = cats.sorted().joinToString(",")
    }

    suspend fun setSponsorBlockShowToast(v: Boolean) = edit { it[KEY_SB_TOAST] = v }

    suspend fun setSponsorBlockAllowUndo(v: Boolean) = edit { it[KEY_SB_UNDO] = v }

    // ---------------- 直播 ----------------

    /**
     * 隐身入场。
     *
     * ## 默认值为什么是 `false`
     *
     * 与 `autoPip` 同一条理由：这是**新增能力**，默认必须保持改动前的
     * 行为（改动前根本不发入场上报 —— 因为那时没有直播播放，
     * 点条目直接开浏览器）。默认开会让"我什么都没改，怎么行为变了"。
     *
     * ## 消费者
     *
     * [com.example.biliv3.ui.live.LiveViewModel.openRoom] ——
     * 开启时**不调用** `LiveRepository.reportEntry`。
     */
    suspend fun setLiveIncognito(v: Boolean) = edit { it[KEY_LIVE_INCOGNITO] = v }

    // ---------------- 外观 ----------------

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

    /**
     * 退出 App 后自动进入小窗（画中画）。
     *
     * ## 默认值为什么是 `false`
     *
     * 这是**新增**设置，默认值必须保持与改动前一致的行为，否则
     * 老用户升级后会遇到"按 Home 键突然多出一个小窗"的意外 ——
     * 用户没有主动要求过的行为变化不该由升级带来。
     *
     * 想用的人去设置里打开即可（默认关闭 = 保持向后兼容）。
     *
     * 消费者：[com.example.biliv3.MainActivity] 的 `onUserLeaveHint`。
     */
    suspend fun setAutoPip(v: Boolean) = edit { it[KEY_AUTO_PIP] = v }

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
        private val KEY_AUTO_PIP = booleanPreferencesKey("auto_pip")
        private val KEY_H264 = booleanPreferencesKey("prefer_h264")

        /** 弹幕屏蔽关键词。用 `\n` 连接存储（DataStore 无 StringSet 语义保证顺序）。 */
        private val KEY_DANMAKU_BLOCK_KEYWORDS = androidx.datastore.preferences.core
            .stringPreferencesKey("danmaku_block_keywords")

        /** 屏蔽的弹幕类型：1=滚动 4=底部 5=顶部（逗号分隔）。 */
        private val KEY_DANMAKU_BLOCK_MODES = androidx.datastore.preferences.core
            .stringPreferencesKey("danmaku_block_modes")

        // ---- 空降助手 ----
        private val KEY_SB_ENABLED = androidx.datastore.preferences.core
            .booleanPreferencesKey("sponsor_block_enabled")

        /** 要跳过的类别（逗号分隔）。空串 = 用默认集。 */
        private val KEY_SB_CATEGORIES = androidx.datastore.preferences.core
            .stringPreferencesKey("sponsor_block_categories")

        private val KEY_SB_TOAST = androidx.datastore.preferences.core
            .booleanPreferencesKey("sponsor_block_toast")

        private val KEY_SB_UNDO = androidx.datastore.preferences.core
            .booleanPreferencesKey("sponsor_block_undo")

        // ---- 直播 ----
        private val KEY_LIVE_INCOGNITO = androidx.datastore.preferences.core
            .booleanPreferencesKey("live_incognito")

        // ---- 自动画质 / 音质（未发版）----
        //
        // 来源：AHCorn/Bilibili-Auto-Quality 的「设置音质和画质」面板。
        // ⚠️ 全部**默认保守**：总开关默认关，杜比/无损默认关 ——
        //    新功能不该改变升级前的行为（同 autoPip 的约定）。
        private val KEY_AQ_ENABLED = booleanPreferencesKey("auto_quality_enabled")
        private val KEY_AQ_PREFERRED = intPreferencesKey("auto_quality_preferred_qn")
        private val KEY_AQ_FALLBACK = intPreferencesKey("auto_quality_fallback_qn")
        private val KEY_AQ_AUTO_AUDIO = booleanPreferencesKey("auto_quality_auto_audio")
        private val KEY_AQ_DOLBY = booleanPreferencesKey("auto_quality_dolby")
        private val KEY_AQ_FLAC = booleanPreferencesKey("auto_quality_flac")
        // ⚠️ 只有三个解锁键 —— 杜比音频（fnval 32）与无损（fnval 4096）
        //    的位实测会让取流请求 -400，不能做成开关。
        private val KEY_AQ_U8K = booleanPreferencesKey("auto_quality_unlock_8k")
        private val KEY_AQ_UDV = booleanPreferencesKey("auto_quality_unlock_dolby_vision")
        private val KEY_AQ_UAV1 = booleanPreferencesKey("auto_quality_unlock_av1")
        private val KEY_AQ_LIVE = booleanPreferencesKey("auto_quality_live")
    }
}

/**
 * 空降助手的默认设置。
 *
 * ## 默认跳哪些类别
 *
 * 默认只开**真正让人厌烦、且跳过不会丢失信息**的三类：
 * - `sponsor`（恰饭广告）—— 核心诉求
 * - `selfpromo`（自我推广 / 一键三连提示）
 * - `intro` / `outro`（片头片尾动画）
 *
 * **默认不开**的：
 * - `interaction`（互动提示）—— 有些是内容的一部分
 * - `poi_highlight` / `exclusive_access` —— 是"定位"不是"跳过"，
 *   语义不同（`actionType` 为 `poi`/`full`），跳过会让人莫名其妙
 * - `preview` / `music_offtopic` —— 争议较大，交给用户自己开
 */
object SponsorBlockDefaults {
    val CATEGORIES: Set<String> = setOf(
        "sponsor",
        "selfpromo",
        "intro",
        "outro",
    )
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

    /**
     * 退出 App（按 Home / 切到后台）时自动进入小窗。
     *
     * 默认 **false**：这是新增能力，默认必须保持改动前的行为，
     * 否则升级会带来"没要求过的行为变化"。
     */
    val autoPip: Boolean = false,

    // ---- 空降助手（SponsorBlock for Bilibili）----
    /**
     * 总开关。默认**关**。
     *
     * ## 为什么默认关而不是开
     *
     * 它会让播放器**自动跳转进度**（跳过赞助片段）。自动改变播放位置的
     * 行为必须由用户明确开启 —— 否则会出现"视频自己跳了"的困惑，
     * 而且用户会怀疑是 bug。
     *
     * 另外这是**第三方社区数据**（bsbsb.top），默认开启等于替用户
     * 做了一个"接受第三方数据"的决定。
     */
    val sponsorBlockEnabled: Boolean = false,

    /** 要跳过的类别（见 `SponsorBlockRepository.Category`）。 */
    val sponsorBlockCategories: Set<String> = SponsorBlockDefaults.CATEGORIES,

    /** 是否显示"已跳过"提示条。关掉则静默跳过。 */
    val sponsorBlockShowToast: Boolean = true,

    /** 跳过时是否弹撤销按钮（误标时可以退回）。 */
    val sponsorBlockAllowUndo: Boolean = true,

    // ---- 直播 ----
    /**
     * 隐身入场（v1.6.3）。
     *
     * ## 它**真实控制**什么
     *
     * 开启后，进入直播间时**不调用** `roomEntryAction` 入场上报接口
     * （实测该接口 `code=0` 可用，见 `Endpoints.LIVE_ENTRY_ACTION`）。
     * 即：本应用不会产生"某某进入了直播间"的上报。
     *
     * ## 它**不**控制什么（必须如实告知用户）
     *
     * 本项目的直播条目点击后打开的是**系统浏览器**的官方直播间页面。
     * 那个页面会自己发上报，**不受本应用控制**。
     * 所以开关文案写的是"本应用不发送入场上报"，不是"完全隐身"。
     *
     * 默认 `false`（保持改动前的行为）。
     */
    val liveIncognito: Boolean = false,

    /**
     * 自动画质 / 音质（未发版）。
     *
     * 移植自 `AHCorn/Bilibili-Auto-Quality`，见 [AutoQualitySettings]。
     *
     * ⚠️ **默认值是"全关"** —— 与 [autoPip] / [sponsorBlockEnabled]
     * 同一条约定：新功能不得改变升级前的行为。
     * 关闭时 `fnval` 退化为原来的 `16`，音轨选择与改动前一致。
     */
    val autoQuality: AutoQualitySettings = AutoQualitySettings(),
) {
    companion object {
        const val DEFAULT_SPEED = 1f
    }
}
