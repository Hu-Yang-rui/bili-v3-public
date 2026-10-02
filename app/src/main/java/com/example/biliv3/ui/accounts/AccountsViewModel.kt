package com.example.biliv3.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.auth.AccountSwitcher
import com.example.biliv3.data.auth.SavedAccount
import com.example.biliv3.data.auth.SwitchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 切换账号页的状态。
 *
 * 四态齐全（加载 / 空 / 错误 / 内容），与项目其它页面一致。
 */
data class AccountsUiState(
    val accounts: List<SavedAccount> = emptyList(),
    /** 当前生效账号的 mid。0 = 未登录。 */
    val currentMid: Long = 0L,
    /** 正在切换的账号 mid（用于给该行显示 loading，防连点）。 */
    val switchingMid: Long = 0L,
    /** 一次性提示（成功 / 失败），消费后置 null。 */
    val toast: String? = null,
)

/**
 * 切换账号 ViewModel。
 *
 * ## 为什么切换要给"正在切换"状态
 *
 * 切换要动两处加密存储 + 广播，虽然都是本地操作（毫秒级），
 * 但用户可能连点两行 —— 没有 pending 状态就会出现两次切换交错，
 * 最终生效的账号不确定。所以切到哪一行，那一行显示 loading 且
 * 全列表暂时不可点。
 */
class AccountsViewModel(
    private val switcher: AccountSwitcher,
    private val currentMid: () -> Long,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountsUiState())
    val state: StateFlow<AccountsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新读账号列表 + 当前 mid。 */
    fun refresh() {
        _state.value = _state.value.copy(
            accounts = switcher.accounts(),
            currentMid = currentMid(),
        )
    }

    /**
     * 切换到某个账号。
     *
     * 成功/失败都给提示 —— 不出现"点了没反应"。
     */
    fun switchTo(mid: Long) {
        if (_state.value.switchingMid != 0L) return
        if (mid == _state.value.currentMid) {
            _state.value = _state.value.copy(toast = "已经是当前账号")
            return
        }

        _state.value = _state.value.copy(switchingMid = mid)
        viewModelScope.launch {
            val result = switcher.switchTo(mid)
            val msg = when (result) {
                is SwitchResult.Success -> "已切换到 ${result.account.displayName}"
                is SwitchResult.NotFound -> "该账号已不存在，请重新登录"
                is SwitchResult.Invalid -> result.reason
            }
            refresh()
            _state.value = _state.value.copy(switchingMid = 0L, toast = msg)
        }
    }

    /**
     * 移除账号。
     *
     * 移除的是当前账号时，[AccountSwitcher] 会自动切到下一个
     * （没有下一个则登出）—— 这里只需如实提示结果。
     */
    fun remove(mid: Long) {
        if (_state.value.switchingMid != 0L) return
        val wasCurrent = mid == _state.value.currentMid
        val name = _state.value.accounts.firstOrNull { it.mid == mid }?.displayName.orEmpty()

        viewModelScope.launch {
            val result = switcher.removeAccount(mid)
            refresh()
            val msg = when {
                // 移除当前账号且自动切到了别的号
                result is SwitchResult.Success -> "已移除 $name，切换到 ${result.account.displayName}"
                // 移除当前账号且已无其它账号 → 登出
                wasCurrent -> "已移除 $name，当前为未登录状态"
                else -> "已移除 $name"
            }
            _state.value = _state.value.copy(toast = msg)
        }
    }

    /** 退出当前账号（保留列表里的其它账号，方便一键切回）。 */
    fun logout() {
        if (_state.value.switchingMid != 0L) return
        switcher.logout()
        refresh()
        _state.value = _state.value.copy(toast = "已退出登录")
    }

    /** 退出并清空全部已保存账号。 */
    fun logoutAndForgetAll() {
        if (_state.value.switchingMid != 0L) return
        switcher.logoutAndForgetAll()
        refresh()
        _state.value = _state.value.copy(toast = "已退出并清除全部账号")
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }
}

/** 切换账号 VM 工厂。 */
class AccountsVmFactory(
    private val switcher: AccountSwitcher,
    private val currentMid: () -> Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AccountsViewModel(switcher, currentMid) as T
}
