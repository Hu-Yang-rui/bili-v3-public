package com.example.biliv3.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.auth.AccountSwitcher
import com.example.biliv3.data.auth.AccountSync
import com.example.biliv3.data.auth.AuthRepository
import com.example.biliv3.data.auth.UserInfo
import com.example.biliv3.ui.component.userMessageFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「我的」页状态。
 *
 * ## 四态
 *
 * - [Loading]：启动时查一次登录态
 * - [Guest]：未登录 → 显示登录引导
 * - [LoggedIn]：已登录 → 显示用户信息 + 功能入口
 * - [Failed]：**本地有凭据但查询失败**（断网 / 服务端故障）
 *
 * ## ⚠️ 为什么必须有 [Failed]
 *
 * `AuthRepository.fetchUserInfo()` 把「没登录」和「网络挂了」
 * **都折叠成 null**。上一版直接 `getOrNull() → Guest`，于是：
 *
 * - 断网时，**已登录用户看到「未登录」+ 登录按钮** —— 凭空被登出
 * - 且**没有重试入口**，用户只能杀掉 App 重开
 *
 * 判据：本地有 cookie（`hasLocalCredential`）却查不到用户信息
 * = 这是故障，不是登出。
 */
sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data object Guest : ProfileUiState
    data class LoggedIn(val user: UserInfo) : ProfileUiState

    /** 本地有凭据但查询失败 —— 网络/服务端故障，**不是登出**。 */
    data class Failed(val reason: String) : ProfileUiState
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
            val result = runCatching { repo.fetchUserInfo() }

            _state.value = result.fold(
                onSuccess = { user ->
                    if (user != null) {
                        ProfileUiState.LoggedIn(user)
                    } else if (repo.hasLocalCredential()) {
                        // ⚠️ 本地有 cookie 却查不到 → 是故障，不是登出。
                        // 置 Guest 会让已登录用户看到"未登录"+登录按钮。
                        ProfileUiState.Failed("无法连接服务器，请检查网络后重试")
                    } else {
                        ProfileUiState.Guest
                    }
                },
                onFailure = {
                    if (repo.hasLocalCredential()) {
                        ProfileUiState.Failed(userMessageFor(it))
                    } else {
                        ProfileUiState.Guest
                    }
                },
            )
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
