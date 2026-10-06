package com.example.biliv3.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.biliv3.data.SettingsStore
import com.example.biliv3.data.auth.AuthStore

/**
 * [SettingsViewModel] 工厂。
 *
 * ## 为什么要把 [AuthStore] 传进来
 *
 * v1.4.3 起设置页多了「开发者工具 · Cookie 管理」，它必须读写**同一份**
 * 登录凭据（`AuthStore`）—— 不能另开一份存储，否则会出现
 * 「Cookie 管理里显示已登录，实际请求还是旧凭据」。
 *
 * 用**可选参数**（默认 null）而不是必填：单测与 `@Preview` 里不注入也能跑，
 * 只是 Cookie 区块会显示"账号模块不可用"。
 */
class SettingsViewModelFactory(
    private val store: SettingsStore,
    private val authStore: AuthStore? = null,
    /** 第三方 AI 配置（加密存储）。传 null 时 AI 区块显示不可用。 */
    private val aiConfigStore: com.example.biliv3.data.ai.AiConfigStore? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(store).also {
                it.authStore = authStore
                it.aiConfigStore = aiConfigStore
                it.refreshCookieState()
                it.refreshAiConfig()
            } as T
        }
        throw IllegalArgumentException("未知的 ViewModel: ${modelClass.name}")
    }
}
