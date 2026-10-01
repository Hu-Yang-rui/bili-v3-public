package com.example.biliv3.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biliv3.data.auth.AuthRepository
import com.example.biliv3.data.auth.QrCode
import com.example.biliv3.data.auth.QrPollResult
import com.example.biliv3.data.auth.UserInfo
import com.example.biliv3.ui.component.userMessageFor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 登录方式。 */
enum class LoginMode {
    /** 扫码（API 直连，快，不受极验影响）。 */
    QR,

    /**
     * 账号登录（WebView 加载官方页面）。
     *
     * 覆盖手机号 / 密码 / 第三方等全部方式，且用户自己在页面里过极验。
     */
    WEB,
}

/**
 * 扫码登录状态。
 *
 * 对应 `AGENTS.md` §3.2 的四态状态机。
 */
sealed interface LoginUiState {
    data object Loading : LoginUiState

    data class Waiting(val qr: QrCode) : LoginUiState

    data class Scanned(val qr: QrCode) : LoginUiState

    data class Expired(val qr: QrCode?) : LoginUiState

    data class Success(val user: UserInfo?) : LoginUiState

    data class Error(val message: String) : LoginUiState
}

/**
 * 登录 ViewModel。
 *
 * ## 三种登录方式
 *
 * | 方式 | 说明 |
 * |---|---|
 * | 扫码 | 最稳，不受风控影响（B 站对密码登录风控最严） |
 * | 手机号 + 短信 | 常规方式，需收验证码 |
 * | 账号 + 密码 | 需 RSA 加密；触发极验时会提示改用扫码 |
 *
 * ## 轮询策略
 *
 * 每 2 秒轮询一次。选 2 秒而非更短：扫码本身要几秒，1 秒轮询没有
 * 体感差异，但请求量翻倍，更易触发风控。
 *
 * ## 生命周期
 *
 * 轮询协程绑在 `viewModelScope`，页面销毁自动取消 ——
 * 否则离开登录页后仍在后台每 2 秒打接口，既耗电又拉高风控风险。
 */
class LoginViewModel(
    private val repo: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Loading)
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(LoginMode.QR)
    val mode: StateFlow<LoginMode> = _mode.asStateFlow()

    /** 表单错误提示。 */
    private val _formError = MutableStateFlow<String?>(null)
    val formError: StateFlow<String?> = _formError.asStateFlow()

    /** 是否正在提交（禁用按钮防重复点击）。 */
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    private var pollJob: Job? = null

    init {
        generateQr()
        // 顺便拉设备指纹，降低后续请求的风控命中率
        viewModelScope.launch { repo.fetchBuvid() }
    }

    /**
     * 切换登录方式。
     *
     * 离开扫码模式时**停掉轮询** —— 否则用户在用手机号登录，
     * 后台还在每 2 秒打一次扫码接口，纯属浪费且增加风控。
     */
    fun switchMode(newMode: LoginMode) {
        if (_mode.value == newMode) return
        _mode.value = newMode
        _formError.value = null

        if (newMode == LoginMode.QR) {
            generateQr()
        } else {
            pollJob?.cancel()
            pollJob = null
        }
    }

    /** 生成二维码并开始轮询。 */
    fun generateQr() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _state.value = LoginUiState.Loading
            val qr = runCatching { repo.generateQrCode() }
                .getOrElse { e ->
                    _state.value = LoginUiState.Error(userMessageFor(e))
                    return@launch
                }

            _state.value = LoginUiState.Waiting(qr)
            startPolling(qr)
        }
    }

    /**
     * 轮询扫码状态。
     *
     * 循环内用 `isActive` 判断取消 —— `while(true)` 在协程取消后
     * 仍会跑完当前迭代，不判断会多打一次无谓的请求。
     */
    private suspend fun startPolling(qr: QrCode) {
        while (viewModelScope.isActive) {
            delay(POLL_INTERVAL_MS)

            val result = runCatching { repo.poll(qr.key) }
                .getOrElse { e ->
                    _state.value = LoginUiState.Error(userMessageFor(e))
                    return
                }

            when (result) {
                is QrPollResult.Waiting -> {
                    if (_state.value !is LoginUiState.Waiting) {
                        _state.value = LoginUiState.Waiting(qr)
                    }
                }

                is QrPollResult.Scanned -> _state.value = LoginUiState.Scanned(qr)

                is QrPollResult.Expired -> {
                    _state.value = LoginUiState.Expired(qr)
                    return
                }

                // ⚠️ 与 Expired 分开处理。
                //
                // 原实现把"成功但取凭据失败"也归到 Expired，用户看到
                // 「扫码成功 → 二维码已过期」，完全误导且无从自查。
                // 现在如实报错（formError 显示具体原因），并停止轮询
                // —— 同一个 key 再轮询也不会成功。
                is QrPollResult.Failed -> {
                    _formError.value = result.message
                    _state.value = LoginUiState.Expired(qr)
                    return
                }

                is QrPollResult.Success -> {
                    _state.value = LoginUiState.Success(result.user)
                    return
                }
            }
        }
    }

    // ---------------- WebView 登录 ----------------

    /**
     * 采纳 WebView 里拿到的 cookie。
     *
     * WebView 走的是官方页面，登录成功后 cookie 由系统 `CookieManager`
     * 持有。这里把它转存到应用的 `AuthStore`（加密持久化），
     * 后续 API 请求才能带上登录态。
     *
     * ## 为什么要显式转存
     *
     * WebView 的 cookie 存在系统级 `CookieManager` 里，与本应用的
     * OkHttp `CookieJar` 是**两套独立存储**。不转存的话表现就是
     * "在 WebView 里登录成功了，但 App 里还是未登录"。
     *
     * @param cookie WebView 里读到的完整 cookie 串
     */
    fun adoptWebViewCookie(cookie: String) {
        if (cookie.isBlank() || !cookie.contains("SESSDATA")) {
            _formError.value = "登录信息不完整，请重试"
            return
        }

        viewModelScope.launch {
            _submitting.value = true
            _formError.value = null

            // 先落 cookie，再查用户信息 —— fetchUserInfo 依赖登录态
            repo.adoptCookie(cookie)
            val user = repo.fetchUserInfo()

            if (user != null) {
                repo.saveUser(user)
                _state.value = LoginUiState.Success(user)
            } else {
                // cookie 有 SESSDATA 但 nav 查不到用户：SESSDATA 可能已失效，
                // 或 cookie 串缺 bili_jct 等配套字段。
                //
                // 这里**不能**塞一个空 QrCode 进 Waiting —— 那会让二维码
                // 面板渲染一个空白方块。正确做法是回到扫码流程重新生成。
                _formError.value = "登录状态校验失败，请重试或改用扫码登录"
                _state.value = LoginUiState.Loading
                generateQr()
            }
            _submitting.value = false
        }
    }

    /** 用户手动确认已登录（兜底）。 */
    fun confirmManually() {
        viewModelScope.launch {
            val user = repo.fetchUserInfo()
            if (user != null) {
                repo.saveUser(user)
                _state.value = LoginUiState.Success(user)
            } else {
                _formError.value = "还没有检测到登录，请先在手机上确认"
            }
        }
    }

    fun clearFormError() {
        _formError.value = null
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private companion object {
        /** 轮询间隔。见类注释说明为何选 2 秒。 */
        const val POLL_INTERVAL_MS = 2000L
    }
}
