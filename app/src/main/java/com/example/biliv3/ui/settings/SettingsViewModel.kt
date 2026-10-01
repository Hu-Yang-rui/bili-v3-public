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

    // ---- 外观 ----

    fun setThemeMode(v: com.example.biliv3.data.ThemeMode) = launch { store.setThemeMode(v) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
