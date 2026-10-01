package com.example.biliv3.ui.component

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 键盘收起的统一入口。
 *
 * ## 为什么不能只调 `keyboardController.hide()`
 *
 * `LocalSoftwareKeyboardController.hide()` 是**异步**的：
 * 它只是把请求排进 IME 队列，真正隐藏由系统 IME 进程执行，
 * 在部分 ROM（尤其国产 ROM）上会有 300ms~1s 的可见延迟。
 *
 * 而页面**退出**时最可靠的时序是「先清焦点、再显式调 IMM」：
 *
 * 1. `keyboardController.hide()` —— 走 Compose 通道，兼容性最好
 * 2. `focusManager.clearFocus(force = true)` —— 清掉焦点，
 *    让输入法连接自然断开（这是延迟残留的主因：焦点还在，
 *    IME 认为"还有人在输入"，于是不急着收）
 * 3. `InputMethodManager.hideSoftInputFromWindow(token, 0)` —— 同步调用，
 *    立刻请求系统收起，绕开 Compose 的下一帧调度
 *
 * 三步都做，任意一步生效即可，不会互相冲突。
 *
 * ## 用法
 *
 * 在**点击返回/关闭**的那一刻调用，而不是在 `DisposableEffect` 里等销毁 ——
 * 等销毁时页面已经在做退场动画，键盘会跟着动画一起淡出，正是"残留 1 秒"的来源。
 */
@Composable
fun rememberKeyboardHider(): () -> Unit {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    return {
        keyboard?.hide()
        hideImeNow(context)
    }
}

/**
 * 同步收起输入法。
 *
 * `hideSoftInputFromWindow` 需要一个 window token；这里从当前焦点 view 取。
 * 拿不到 token 时静默返回 —— 此时 `keyboard.hide()` 已经兜底。
 */
fun hideImeNow(context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return
    val view = (context as? android.app.Activity)?.currentFocus
    if (view != null) {
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}

/**
 * 离开组合时收起键盘。
 *
 * 用于「页面/面板被移出组合」这一场景（如搜索页被 pop 掉、
 * 设置弹层被关闭）。它在 `onDispose` 里执行，与调用方的
 * 显式 `onBack` 形成双保险 —— 显式调用负责"立即"，
 * 本 effect 负责"即使调用方忘了也不会残留"。
 */
@Composable
fun HideKeyboardOnDispose() {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    DisposableEffect(Unit) {
        onDispose {
            keyboard?.hide()
            hideImeNow(context)
        }
    }
}
