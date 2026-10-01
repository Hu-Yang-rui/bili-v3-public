package com.example.biliv3.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.biliv3.data.auth.AuthRepository

/**
 * [LoginViewModel] 的工厂。
 *
 * 需要 [AuthRepository]（内含加密凭据存储与全应用共享的 API 客户端），
 * 由 `AppContainer` 提供，不能无参构造。
 */
class LoginViewModelFactory(
    private val repo: AuthRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            "未知的 ViewModel: ${modelClass.name}"
        }
        return LoginViewModel(repo) as T
    }
}
