package com.example.biliv3.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.biliv3.data.SettingsStore

/** [SettingsViewModel] 工厂。只需要一个 Store。 */
class SettingsViewModelFactory(
    private val store: SettingsStore,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(store) as T
        }
        throw IllegalArgumentException("未知的 ViewModel: ${modelClass.name}")
    }
}
