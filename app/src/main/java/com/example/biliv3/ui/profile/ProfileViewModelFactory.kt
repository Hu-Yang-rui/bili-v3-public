package com.example.biliv3.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.biliv3.data.auth.AccountSwitcher
import com.example.biliv3.data.auth.AccountSync
import com.example.biliv3.data.auth.AuthRepository

/**
 * [ProfileViewModel] 的工厂。
 *
 * @param switcher 退出登录走它（会保留账号到多账号列表）
 * @param accountSync 订阅账号变化，切号后本页自动刷新
 */
class ProfileViewModelFactory(
    private val repo: AuthRepository,
    private val switcher: AccountSwitcher? = null,
    private val accountSync: AccountSync? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            "未知的 ViewModel: ${modelClass.name}"
        }
        return ProfileViewModel(repo, switcher, accountSync) as T
    }
}
