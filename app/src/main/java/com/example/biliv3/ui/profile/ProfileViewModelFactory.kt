package com.example.biliv3.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.biliv3.data.auth.AuthRepository

/** [ProfileViewModel] 的工厂。 */
class ProfileViewModelFactory(
    private val repo: AuthRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            "未知的 ViewModel: ${modelClass.name}"
        }
        return ProfileViewModel(repo) as T
    }
}
