package com.example.biliv3

import com.example.biliv3.data.auth.AccountListStore
import com.example.biliv3.data.auth.AccountSync
import com.example.biliv3.data.auth.AccountSwitcher
import com.example.biliv3.data.auth.AuthState
import com.example.biliv3.data.auth.SavedAccount
import com.example.biliv3.data.auth.SwitchResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 切换账号的核心逻辑测试 —— **直接测真实的 [AccountSwitcher]**。
 *
 * ## 为什么必须测（这里出错会"串号"，而且不报错）
 *
 * 切换账号要按固定顺序动两处存储：
 * ```
 * ① 把当前账号存回列表（否则当前号的 cookie 丢了）
 * ② 把目标账号写进当前态（全 App 立刻读新 cookie）
 * ③ 广播（各页面重新加载）
 * ```
 * 顺序错一步的后果都是**静默串号**：
 * - 漏 ①：切走后原账号从列表消失，再也切不回来
 * - 漏 ②：界面变了但请求仍用旧 cookie（最严重）
 * - 漏 ③：界面还显示上一个账号的数据
 *
 * 所以这里注入内存实现，把三步的实际行为逐条钉死 ——
 * 而不是"复现一遍逻辑再断言复现结果"（那测的是测试自己）。
 */
class AccountSwitchTest {

    // ---------------- 内存实现（替代 Android 加密存储）----------------

    private class FakeAuth : AuthState {
        override var cookie = ""
        override var mid = 0L
        override var userName = ""
        override var userFace = ""

        override fun save(cookie: String, mid: Long, name: String, face: String) {
            this.cookie = cookie
            this.mid = mid
            this.userName = name
            this.userFace = face
        }

        override fun clear() {
            cookie = ""
            mid = 0L
            userName = ""
            userFace = ""
        }
    }

    private class FakeAccounts : AccountListStore {
        private val items = mutableListOf<SavedAccount>()

        override fun list() = items.sortedByDescending { it.savedAt }

        override fun get(mid: Long) = list().firstOrNull { it.mid == mid }

        override fun upsert(account: SavedAccount) {
            items.removeAll { it.mid == account.mid }
            items.add(account)
        }

        override fun remove(mid: Long) {
            items.removeAll { it.mid == mid }
        }

        override fun clear() = items.clear()
    }

    private class Fixture {
        val auth = FakeAuth()
        val accounts = FakeAccounts()
        val sync = AccountSync()
        val switcher = AccountSwitcher(auth, accounts, sync)
    }

    /** 造一个已登录的当前账号。 */
    private fun Fixture.loginAs(mid: Long, name: String, cookie: String) {
        auth.save(cookie, mid, name, "face-$mid")
        switcher.rememberCurrent()
    }

    // ---------------- 切换：核心顺序 ----------------

    @Test
    fun `切换后当前态完全等于目标账号（不串号）`() {
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-A")
        // 造一个已保存的乙账号
        f.accounts.upsert(SavedAccount(200L, "乙", "face-B", "cookie-B", 1L))

        val result = f.switcher.switchTo(200L)

        assertThat(result).isInstanceOf(SwitchResult.Success::class.java)
        // 四个字段必须**全部**换成目标账号的 —— 只换部分就是串号
        assertThat(f.auth.mid).isEqualTo(200L)
        assertThat(f.auth.cookie).isEqualTo("cookie-B")
        assertThat(f.auth.userName).isEqualTo("乙")
        assertThat(f.auth.userFace).isEqualTo("face-B")
    }

    @Test
    fun `切换前会把当前账号存入列表（否则切走就回不来）`() {
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-A")
        f.accounts.upsert(SavedAccount(200L, "乙", "face-B", "cookie-B", 1L))
        // 清掉甲在列表里的记录，模拟"刚登录还没入列表"
        f.accounts.remove(100L)

        f.switcher.switchTo(200L)

        // 切换动作本身必须把甲补回列表
        assertThat(f.accounts.get(100L)).isNotNull()
        assertThat(f.accounts.get(100L)!!.cookie).isEqualTo("cookie-A")
    }

    @Test
    fun `切换会广播一次（订阅方据此重新加载）`() {
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-A")
        f.accounts.upsert(SavedAccount(200L, "乙", "face-B", "cookie-B", 1L))
        val before = f.sync.version.value

        f.switcher.switchTo(200L)

        assertThat(f.sync.version.value).isEqualTo(before + 1)
    }

    @Test
    fun `切换到不存在的账号返回 NotFound 且不改动当前态`() {
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-A")

        val result = f.switcher.switchTo(999L)

        assertThat(result).isInstanceOf(SwitchResult.NotFound::class.java)
        // 失败不能破坏现状
        assertThat(f.auth.mid).isEqualTo(100L)
        assertThat(f.auth.cookie).isEqualTo("cookie-A")
    }

    @Test
    fun `凭据不完整的账号切换被拒绝`() {
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-A")
        // 绕过 upsert 的校验，直接塞一个坏账号进存储
        f.accounts.upsert(SavedAccount(300L, "坏", "", "", 2L))

        val result = f.switcher.switchTo(300L)

        assertThat(result).isInstanceOf(SwitchResult.Invalid::class.java)
        assertThat(f.auth.mid).isEqualTo(100L)
    }

    // ---------------- 列表管理 ----------------

    @Test
    fun `同一账号重复登录只保留一条（更新而非追加）`() {
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-old")
        f.loginAs(100L, "甲改名", "cookie-new")

        assertThat(f.accounts.list().size).isEqualTo(1)
        assertThat(f.accounts.get(100L)!!.userNameOr("")).isEqualTo("甲改名")
        assertThat(f.accounts.get(100L)!!.cookie).isEqualTo("cookie-new")
    }

    private fun SavedAccount.userNameOr(fallback: String) = name.ifEmpty { fallback }

    @Test
    fun `账号列表按最近使用排序`() {
        val f = Fixture()
        f.accounts.upsert(SavedAccount(1L, "旧", "", "c", 100L))
        f.accounts.upsert(SavedAccount(2L, "中", "", "c", 200L))
        f.accounts.upsert(SavedAccount(3L, "新", "", "c", 300L))

        assertThat(f.accounts.list().map { it.mid }).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test
    fun `凭据不完整的账号不会被记入列表`() {
        val f = Fixture()
        // mid=0：rememberCurrent 应直接返回 null，不入库
        f.auth.save("cookie", 0L, "坏", "")
        val saved = f.switcher.rememberCurrent()

        assertThat(saved).isNull()
        assertThat(f.accounts.list()).isEmpty()
    }

    // ---------------- 移除 / 登出 ----------------

    @Test
    fun `移除当前账号且还有其它账号时自动切过去`() {
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-A")
        f.accounts.upsert(SavedAccount(200L, "乙", "face-B", "cookie-B", 2L))

        val result = f.switcher.removeAccount(100L)

        assertThat(result).isInstanceOf(SwitchResult.Success::class.java)
        assertThat(f.auth.mid).isEqualTo(200L)
        assertThat(f.auth.cookie).isEqualTo("cookie-B")
        assertThat(f.accounts.get(100L)).isNull()
    }

    @Test
    fun `移除当前账号且已无其它账号时登出`() {
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-A")

        val result = f.switcher.removeAccount(100L)

        assertThat(result).isNull()
        assertThat(f.auth.mid).isEqualTo(0L)
        assertThat(f.auth.cookie).isEmpty()
        assertThat(f.accounts.list()).isEmpty()
    }

    @Test
    fun `移除非当前账号不影响当前登录态`() {
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-A")
        f.accounts.upsert(SavedAccount(200L, "乙", "face-B", "cookie-B", 2L))

        f.switcher.removeAccount(200L)

        assertThat(f.auth.mid).isEqualTo(100L)
        assertThat(f.auth.cookie).isEqualTo("cookie-A")
    }

    @Test
    fun `登出后账号仍留在列表里以便一键切回`() {
        // 用户实际反馈过的场景：退出后想切回来，结果号没了
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-A")

        f.switcher.logout()

        assertThat(f.auth.mid).isEqualTo(0L)              // 已登出
        assertThat(f.accounts.get(100L)).isNotNull()      // 但账号还在
        assertThat(f.accounts.get(100L)!!.cookie).isEqualTo("cookie-A")
    }

    @Test
    fun `退出并清除全部账号会清空列表`() {
        val f = Fixture()
        f.loginAs(100L, "甲", "cookie-A")
        f.accounts.upsert(SavedAccount(200L, "乙", "face-B", "cookie-B", 2L))

        f.switcher.logoutAndForgetAll()

        assertThat(f.auth.mid).isEqualTo(0L)
        assertThat(f.accounts.list()).isEmpty()
    }

    // ---------------- 广播 ----------------

    @Test
    fun `广播版本号单调递增`() {
        val sync = AccountSync()
        assertThat(sync.version.value).isEqualTo(0L)
        sync.notifyChanged()
        sync.notifyChanged()
        assertThat(sync.version.value).isEqualTo(2L)
    }
}
