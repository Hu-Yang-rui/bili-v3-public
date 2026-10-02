package com.example.biliv3.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 账号切换的**全局广播**。
 *
 * ## 为什么必须有它（"切换后不能有旧账号残留"）
 *
 * 切换账号后，全 App 的**登录态相关数据**都必须重新加载：
 * 首页推荐、个人中心、收藏、历史、稍后再看、消息、私信、关注列表、
 * 点赞/投币状态……这些分散在 20+ 个 ViewModel 里。
 *
 * 若每个页面各自判断"账号变了没"，会出现：
 * - 漏改几处 → 那几个页面仍是旧账号数据（**串号**，最严重的问题）
 * - 判断写错（比如只比 mid，但同一账号重新登录 cookie 变了）
 * - 20+ 处重复逻辑，改一次要动 20 个文件
 *
 * ## 机制：与 [FavoritesSync] 同构的版本号广播
 *
 * 只广播"**账号变了**"这一事实（单调递增的版本号），不传具体账号。
 * 订阅方统一语义是"重新拉一次" —— 天然正确，且不用在每个页面
 * 各写一遍增量合并。
 *
 * 与 [FavoritesSync] 分开而不是复用它：两者语义不同
 * （收藏变化 ≠ 账号变化），合并会让"收藏页"在切号时收到无关通知，
 * 也会让"切号"被误当成收藏变更。
 *
 * ## 用法
 *
 * 写入方（[AccountSwitcher] 内部）：
 * ```
 * accountSync.notifyChanged()
 * ```
 *
 * 订阅方（任何展示登录态数据的页面）：
 * ```
 * val v by accountSync.version.collectAsStateWithLifecycle()
 * LaunchedEffect(v) { if (v > 0) reload() }
 * ```
 */
class AccountSync {

    private val _version = MutableStateFlow(0L)

    /** 当前版本号。每次账号切换 +1。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** 广播一次「账号已变化」。 */
    fun notifyChanged() {
        _version.update { it + 1 }
    }
}

/** 切换结果。UI 据此给成功/失败提示，而不是"点了没反应"。 */
sealed interface SwitchResult {
    /** 切换成功。 */
    data class Success(val account: SavedAccount) : SwitchResult

    /** 目标账号不存在（可能已被移除）。 */
    data class NotFound(val mid: Long) : SwitchResult

    /** 凭据无效 —— cookie 为空或 mid 为 0。 */
    data class Invalid(val reason: String) : SwitchResult
}

/**
 * 账号切换器：多账号读写的**唯一入口**。
 *
 * ## 为什么单独一层，而不是把逻辑塞进 ViewModel
 *
 * 切换账号要动两处存储（[AccountStore] 列表 + [AuthStore] 当前态）
 * 并广播 [AccountSync]。这个顺序**错一步就会串号**：
 *
 * ```
 * ① 把当前账号存回列表（否则当前号的 cookie 丢了）
 * ② 把目标账号写进 AuthStore（全 App 立刻读新 cookie）
 * ③ 广播（各页面重新加载）
 * ```
 *
 * 放在一处、只有一份实现，才能保证 20+ 个调用场景（列表点击切换、
 * 登录后自动入列表、退出登录、移除账号）都走同一套正确顺序。
 */
class AccountSwitcher(
    private val authStore: AuthState,
    private val accountStore: AccountListStore,
    private val accountSync: AccountSync,
) {

    /** 已保存的账号列表（最近使用在前）。 */
    fun accounts(): List<SavedAccount> = accountStore.list()

    /**
     * 把**当前登录态**存入账号列表。
     *
     * 调用时机：
     * - 登录成功后（让新账号自动出现在列表里）
     * - 切换前（先保住当前账号的 cookie）
     *
     * mid 为 0（凭据不完整）时**不入库** —— 存进去会得到一个
     * 切过去也无法使用的坏账号。
     */
    fun rememberCurrent(): SavedAccount? {
        val mid = authStore.mid
        val cookie = authStore.cookie
        if (mid <= 0L || cookie.isEmpty()) return null

        val account = SavedAccount(
            mid = mid,
            name = authStore.userName,
            face = authStore.userFace,
            cookie = cookie,
            savedAt = System.currentTimeMillis(),
        )
        accountStore.upsert(account)
        return account
    }

    /**
     * 切换到指定账号。
     *
     * 顺序见类注释 —— 必须"先存旧的、再写新的、最后广播"。
     *
     * @param preserveCurrent 是否先把当前账号存回列表。
     *   **默认 true**（正常切换要保住当前账号）。
     *   但 [removeAccount] 走这条路时必须传 false —— 它刚刚删掉了
     *   当前账号，若再"存回"就把删除操作撤销了
     *   （实测 bug：移除当前账号后它又出现在列表里）。
     */
    fun switchTo(mid: Long, preserveCurrent: Boolean = true): SwitchResult {
        val target = accountStore.get(mid)
            ?: return SwitchResult.NotFound(mid)

        if (target.cookie.isEmpty() || target.mid <= 0L) {
            return SwitchResult.Invalid("该账号的登录凭据不完整，请重新登录")
        }

        // ① 先保住当前账号（可能刚登录还没入列表）
        if (preserveCurrent) rememberCurrent()

        // ② 写新账号到 AuthStore（全 App 立刻读新 cookie）
        authStore.save(
            cookie = target.cookie,
            mid = target.mid,
            name = target.name,
            face = target.face,
        )

        // ③ 广播：所有订阅方重新加载
        accountSync.notifyChanged()

        return SwitchResult.Success(target)
    }

    /**
     * 移除一个账号。
     *
     * 若移除的正是**当前账号**，则：
     * - 列表里还有别的账号 → 自动切到最近使用的那一个
     * - 列表空了 → 退出登录（清当前态）
     *
     * 不这么做的话会出现"移除当前账号后，界面还显示它的数据但
     * 请求已经没凭据了" —— 比直接登出更让人困惑。
     */
    fun removeAccount(mid: Long): SwitchResult? {
        val wasCurrent = authStore.mid == mid
        accountStore.remove(mid)

        if (!wasCurrent) return null

        val next = accountStore.list().firstOrNull()
        if (next != null) {
            // ⚠️ preserveCurrent = false：当前账号**刚刚被删掉**，
            // 若在这里"存回当前账号"会把删除操作撤销
            // （被单测抓到的真实 bug）。
            return switchTo(next.mid, preserveCurrent = false)
        }

        // 没有别的账号了 → 登出
        authStore.clear()
        accountSync.notifyChanged()
        return null
    }

    /**
     * 退出当前账号（保留列表里其它账号）。
     *
     * 与 [removeAccount] 的区别：这是"退出登录"语义，
     * **当前账号仍留在列表里**（方便下次一键切回）。
     */
    fun logout() {
        rememberCurrent()
        authStore.clear()
        accountSync.notifyChanged()
    }

    /** 退出并清空全部已保存账号。 */
    fun logoutAndForgetAll() {
        authStore.clear()
        accountStore.clear()
        accountSync.notifyChanged()
    }
}
