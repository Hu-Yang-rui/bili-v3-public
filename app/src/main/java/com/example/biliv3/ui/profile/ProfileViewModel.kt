package com.example.biliv3.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.auth.AccountSwitcher
import com.example.biliv3.data.auth.AccountSync
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
 *
 * ## ⚠️ 为什么要订阅 [AccountSync]
 *
 * 切换账号后本页必须立刻显示新账号 —— 若只在 `init` 查一次，
 * 切号回来仍是旧账号的头像昵称（**串号**）。
 * 订阅版本号后，切号会触发一次 `refresh()`。
 */
class ProfileViewModel(
    private val repo: AuthRepository,
    private val switcher: AccountSwitcher? = null,
    accountSync: AccountSync? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        refresh()
        // 账号变化 → 重新拉当前账号信息
        accountSync?.let { sync ->
            viewModelScope.launch {
                sync.version.collect { v ->
                    if (v > 0L) refresh()
                }
            }
        }
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

    /**
     * 退出登录。
     *
     * ⚠️ 走 [AccountSwitcher.logout] 而不是 `repo.logout()`：
     * 前者会**先把当前账号存进多账号列表**再清当前态，
     * 于是用户之后能在「切换账号」里一键切回。
     * 直接用 `repo.logout()` 会让这个账号从列表里消失
     * （"退出后还想切回来却发现号没了"）。
     */
    fun logout() {
        if (switcher != null) {
            switcher.logout()
        } else {
            repo.logout()
        }
        _state.value = ProfileUiState.Guest
    }
}
