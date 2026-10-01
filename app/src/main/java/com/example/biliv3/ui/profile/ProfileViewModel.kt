package com.example.biliv3.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.auth.AuthRepository
import com.example.biliv3.data.auth.UserInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「我的」页状态。
 *
 * ## 三态
 *
 * - [Loading]：启动时查一次登录态
 * - [Guest]：未登录 → 显示登录引导
 * - [LoggedIn]：已登录 → 显示用户信息 + 功能入口
 *
 * 「未登录」是**正常状态**而不是错误，所以单独一个分支，
 * 不塞进 Error。
 */
sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data object Guest : ProfileUiState
    data class LoggedIn(val user: UserInfo) : ProfileUiState
}

/**
 * 「我的」页 ViewModel。
 *
 * 登录态来自 `AuthRepository`，而它的 Cookie 由**全应用共享**的
 * `OkHttpClient` 持有（见 `AppContainer`），所以这里查到的状态
 * 与首页/搜索/详情用的是同一份。
 */
class ProfileViewModel(
    private val repo: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新查询登录态。从登录页返回时调用。 */
    fun refresh() {
        viewModelScope.launch {
            _state.value = ProfileUiState.Loading
            val user = runCatching { repo.fetchUserInfo() }.getOrNull()
            _state.value = if (user != null) {
                ProfileUiState.LoggedIn(user)
            } else {
                ProfileUiState.Guest
            }
        }
    }

    /** 退出登录。 */
    fun logout() {
        repo.logout()
        _state.value = ProfileUiState.Guest
    }
}
