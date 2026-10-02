package com.example.biliv3.data.auth

/**
 * [AccountSwitcher] 依赖的最小"当前登录态"契约。
 *
 * ## 为什么抽接口（而不是直接用具体类）
 *
 * 真实实现 [AuthStore] 建立在 `EncryptedSharedPreferences` 上，
 * **必须跑在 Android 环境**。若 [AccountSwitcher] 直接依赖它，
 * 这段"切号顺序"的核心逻辑就**无法在 JVM 单测里验证** ——
 * 而它恰恰是最容易出串号的地方。
 *
 * 抽成接口后：
 * - 生产代码注入 [AuthStore]（行为不变）
 * - 单测注入内存实现，把"先存旧、再写新、后广播"的顺序钉死
 *
 * ## 为什么只有读属性 + save/clear，没有 `var cookie`
 *
 * [AuthStore] 的 `cookie` 是 `internal set`（只有 `AuthCookieJar`
 * 应该写它，其它地方误写会破坏登录态）。接口若声明 `var cookie`，
 * 就要求实现把 setter 放开成 public —— 那是**为了可测性削弱封装**。
 * 所以这里只暴露读 + [save]（一个受控的整体写入入口）。
 */
interface AuthState {
    /** 当前 cookie 串。 */
    val cookie: String

    /** 当前账号 mid。0 = 未登录。 */
    val mid: Long

    /** 当前账号昵称。 */
    val userName: String

    /** 当前账号头像。 */
    val userFace: String

    /** 整体写入登录态（切号 / 登录成功用）。 */
    fun save(cookie: String, mid: Long, name: String, face: String)

    /** 清当前登录态。 */
    fun clear()
}

/** 账号列表存储契约。见 [AuthState] 的说明。 */
interface AccountListStore {
    fun list(): List<SavedAccount>
    fun get(mid: Long): SavedAccount?
    fun upsert(account: SavedAccount)
    fun remove(mid: Long)
    fun clear()
}
