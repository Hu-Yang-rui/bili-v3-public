package com.example.biliv3

import com.example.biliv3.data.live.DanmakuDraft
import com.example.biliv3.data.live.LiveMessage
import com.example.biliv3.data.live.LivePermissions
import com.example.biliv3.data.live.LiveRole
import com.example.biliv3.ui.live.LiveUserMenuModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 用户菜单项计算 + 弹幕草稿规则测试（v1.6.6）。
 *
 * ## 为什么这组测试重要
 *
 * ### 菜单项
 * v1.6.4~v1.6.5 期间有一个**真实的用户可见 bug**：
 * `LiveChatPanel` 用 `canModerate && hasUser` 决定"能不能点开菜单"，
 * 而菜单里「查看发送者 / 复制弹幕内容 / 复制用户名 / 填入输入框」
 * **本来就与权限无关** → **普通用户点弹幕完全没反应**。
 *
 * 这类 bug 的特征是"**没有任何报错**"，只能靠把规则钉进测试来防回归。
 *
 * ### 弹幕草稿
 * 长度上限与"可发送"判定决定了发送按钮的可用状态。
 * 判错会出现"按钮亮着但点了发不出去"或"内容合法却发不出去"。
 */
class LiveMenuAndDraftTest {

    // ---------------- 测试夹具 ----------------

    private fun msg(
        uid: Long = 100L,
        uname: String = "某人",
        text: String = "哈哈",
        role: LiveRole = LiveRole.NORMAL,
    ) = LiveMessage(
        kind = LiveMessage.Kind.DANMAKU,
        uid = uid,
        uname = uname,
        face = "f",
        text = text,
        role = role,
    )

    /** 未登录。 */
    private val anon = LivePermissions(
        loggedIn = false,
        selfMid = 0L,
        anchorUid = 999L,
        selfRole = LiveRole.NORMAL,
    )

    /** 已登录的普通用户。 */
    private val normal = LivePermissions(
        loggedIn = true,
        selfMid = 500L,
        anchorUid = 999L,
        selfRole = LiveRole.NORMAL,
    )

    /** 房管。 */
    private val admin = LivePermissions(
        loggedIn = true,
        selfMid = 500L,
        anchorUid = 999L,
        selfRole = LiveRole.ADMIN,
    )

    /** 主播本人。 */
    private val anchor = LivePermissions(
        loggedIn = true,
        selfMid = 999L,
        anchorUid = 999L,
        selfRole = LiveRole.ANCHOR,
    )

    // ---------------- 通用项：与权限无关 ----------------

    /**
     * 🔴 **回归测试**：未登录用户也必须能看到通用项。
     *
     * 这正是那个真实 bug 的判据 —— 普通用户点开菜单应该看到
     * 「查看发送者」等，而不是空菜单。
     */
    @Test
    fun `未登录也能看到通用项`() {
        val items = LiveUserMenuModel.items(msg(), anon)
        assertThat(items).contains(LiveUserMenuModel.Item.VIEW_SENDER)
        assertThat(items).contains(LiveUserMenuModel.Item.OPEN_PROFILE)
        assertThat(items).contains(LiveUserMenuModel.Item.COPY_TEXT)
        assertThat(items).contains(LiveUserMenuModel.Item.COPY_NAME)
        assertThat(items).contains(LiveUserMenuModel.Item.FILL_INPUT)
    }

    /** 未登录**不该**看到任何管理项。 */
    @Test
    fun `未登录没有管理项`() {
        val items = LiveUserMenuModel.items(msg(), anon)
        assertThat(items.filter { it.isModeration }).isEmpty()
    }

    /** 已登录的普通用户同样没有管理项。 */
    @Test
    fun `普通用户没有管理项`() {
        val items = LiveUserMenuModel.items(msg(), normal)
        assertThat(items.filter { it.isModeration }).isEmpty()
        // 但通用项要在
        assertThat(items).contains(LiveUserMenuModel.Item.VIEW_SENDER)
    }

    /** 通用项**永远排在管理项之前**（菜单结构稳定）。 */
    @Test
    fun `通用项排在管理项之前`() {
        val items = LiveUserMenuModel.items(msg(uid = 100L), admin)
        val firstMod = items.indexOfFirst { it.isModeration }
        val lastCommon = items.indexOfLast { !it.isModeration }
        assertThat(lastCommon).isLessThan(firstMod)
    }

    // ---------------- 数据不足时的降级 ----------------

    /** uid 无效 → 不能查资料 / 进主页（那两个动作做不到）。 */
    @Test
    fun `uid 无效时不显示查资料与进主页`() {
        val items = LiveUserMenuModel.items(msg(uid = 0L), normal)
        assertThat(items).doesNotContain(LiveUserMenuModel.Item.VIEW_SENDER)
        assertThat(items).doesNotContain(LiveUserMenuModel.Item.OPEN_PROFILE)
        // 但用户名相关的仍可（用户名不依赖 uid）
        assertThat(items).contains(LiveUserMenuModel.Item.COPY_NAME)
    }

    /** 没有正文 → 不显示「复制弹幕内容」。 */
    @Test
    fun `无正文时不显示复制内容`() {
        val items = LiveUserMenuModel.items(msg(text = ""), normal)
        assertThat(items).doesNotContain(LiveUserMenuModel.Item.COPY_TEXT)
    }

    /** 没有用户名 → 不显示「复制用户名」「填入输入框」。 */
    @Test
    fun `无用户名时不显示复制与填入`() {
        val items = LiveUserMenuModel.items(msg(uname = ""), normal)
        assertThat(items).doesNotContain(LiveUserMenuModel.Item.COPY_NAME)
        assertThat(items).doesNotContain(LiveUserMenuModel.Item.FILL_INPUT)
    }

    // ---------------- 管理项：按身份出现 ----------------

    /** 房管能看到禁言 / 踢出 / 黑名单。 */
    @Test
    fun `房管能看到管理项`() {
        val items = LiveUserMenuModel.items(msg(uid = 100L), admin)
        assertThat(items).contains(LiveUserMenuModel.Item.MUTE)
        assertThat(items).contains(LiveUserMenuModel.Item.KICK)
        assertThat(items).contains(LiveUserMenuModel.Item.BLOCK)
    }

    /** 🔴 房管**不能**看到房管管理（那是主播专属）。 */
    @Test
    fun `房管看不到房管管理`() {
        val items = LiveUserMenuModel.items(msg(uid = 100L), admin)
        assertThat(items).doesNotContain(LiveUserMenuModel.Item.MANAGE_ADMIN)
    }

    /**
     * 🔴 主播看不到 `MANAGE_ADMIN`。
     *
     * 因为**房管管理接口未找到**（实测试了 10 个路径全部 NOT FOUND），
     * `LivePermissions.canActOn(MANAGE_ADMIN)` 对任何身份都返回 false。
     *
     * 这条测试的作用是**钉住"不显示做不到的功能"** ——
     * 将来若有人误把 `MANAGE_ADMIN` 放开，这里会立刻红。
     */
    @Test
    fun `房管管理项不出现`() {
        for (perms in listOf(anon, normal, admin, anchor)) {
            val items = LiveUserMenuModel.items(msg(uid = 100L), perms)
            assertThat(items).doesNotContain(LiveUserMenuModel.Item.MANAGE_ADMIN)
        }
    }

    /** 不能对自己操作 → 对自己时没有管理项。 */
    @Test
    fun `不能对自己操作`() {
        // admin 的 selfMid = 500
        val items = LiveUserMenuModel.items(msg(uid = 500L), admin)
        assertThat(items.filter { it.isModeration }).isEmpty()
    }

    /** 不能对主播操作 → 对主播时没有管理项。 */
    @Test
    fun `不能对主播操作`() {
        // anchorUid = 999
        val items = LiveUserMenuModel.items(msg(uid = 999L), admin)
        assertThat(items.filter { it.isModeration }).isEmpty()
    }

    // ---------------- 顺序 ----------------

    /**
     * 管理项按"动作强度递增"排：解除类在前、施加类在后。
     *
     * 若按权限枚举顺序渲染，用户会看到"禁言 / 解除禁言"交错，
     * 误点概率明显上升。
     */
    @Test
    fun `管理项先解除后施加`() {
        val items = LiveUserMenuModel.items(msg(uid = 100L), admin)
        val mod = items.filter { it.isModeration }
        val iUnmute = mod.indexOf(LiveUserMenuModel.Item.UNMUTE)
        val iMute = mod.indexOf(LiveUserMenuModel.Item.MUTE)
        val iBlock = mod.indexOf(LiveUserMenuModel.Item.BLOCK)
        val iKick = mod.indexOf(LiveUserMenuModel.Item.KICK)

        assertThat(iUnmute).isLessThan(iMute)
        assertThat(iMute).isLessThan(iBlock)
        assertThat(iBlock).isLessThan(iKick)
    }

    /** 顺序**稳定**：同样的输入算两次结果一致（UI 不该抖动）。 */
    @Test
    fun `顺序稳定`() {
        val a = LiveUserMenuModel.items(msg(uid = 100L), admin)
        val b = LiveUserMenuModel.items(msg(uid = 100L), admin)
        assertThat(a).isEqualTo(b)
    }

    // ---------------- actionOf ----------------

    /** 管理项都能映射到权限动作。 */
    @Test
    fun `管理项都能映射到动作`() {
        val modItems = LiveUserMenuModel.Item.entries.filter { it.isModeration }
        for (item in modItems) {
            assertThat(LiveUserMenuModel.actionOf(item)).isNotNull()
        }
    }

    /** 通用项**不**映射到权限动作（否则会被当成管理项拦截）。 */
    @Test
    fun `通用项不映射到动作`() {
        val commonItems = LiveUserMenuModel.Item.entries.filter { !it.isModeration }
        for (item in commonItems) {
            assertThat(LiveUserMenuModel.actionOf(item)).isNull()
        }
    }

    // ---------------- 弹幕草稿：长度 ----------------

    /** 上限内的文本原样通过。 */
    @Test
    fun `上限内不截断`() {
        assertThat(DanmakuDraft.clamp("哈哈")).isEqualTo("哈哈")
        assertThat(DanmakuDraft.clamp("a".repeat(DanmakuDraft.MAX_LEN)))
            .hasLength(DanmakuDraft.MAX_LEN)
    }

    /** 超长截到上限。 */
    @Test
    fun `超长截断到上限`() {
        val long = "a".repeat(DanmakuDraft.MAX_LEN + 10)
        assertThat(DanmakuDraft.clamp(long)).hasLength(DanmakuDraft.MAX_LEN)
    }

    /** 中文按**字符**算（不是字节）—— 20 个中文应全部保留。 */
    @Test
    fun `中文按字符计数`() {
        val cn = "一".repeat(DanmakuDraft.MAX_LEN)
        assertThat(DanmakuDraft.clamp(cn)).hasLength(DanmakuDraft.MAX_LEN)
        assertThat(DanmakuDraft.clamp(cn)).isEqualTo(cn)
    }

    /** 截断**只从头保留**，不改变已有字符顺序。 */
    @Test
    fun `截断保留前缀`() {
        val s = "0123456789" + "abcdefghij" + "XYZ"
        assertThat(DanmakuDraft.clamp(s)).isEqualTo(s.substring(0, DanmakuDraft.MAX_LEN))
    }

    // ---------------- 弹幕草稿：剩余与上限 ----------------

    /** 剩余字符数正确，且**不为负**。 */
    @Test
    fun `剩余字符数不为负`() {
        assertThat(DanmakuDraft.remaining("")).isEqualTo(DanmakuDraft.MAX_LEN)
        assertThat(DanmakuDraft.remaining("a".repeat(5)))
            .isEqualTo(DanmakuDraft.MAX_LEN - 5)
        assertThat(DanmakuDraft.remaining("a".repeat(DanmakuDraft.MAX_LEN))).isEqualTo(0)
        // 超长时返回 0，不是负数（否则 UI 会显示 -3）
        assertThat(DanmakuDraft.remaining("a".repeat(DanmakuDraft.MAX_LEN + 3))).isEqualTo(0)
    }

    /** 到上限的判定。 */
    @Test
    fun `上限判定`() {
        assertThat(DanmakuDraft.atLimit("a".repeat(DanmakuDraft.MAX_LEN - 1))).isFalse()
        assertThat(DanmakuDraft.atLimit("a".repeat(DanmakuDraft.MAX_LEN))).isTrue()
        assertThat(DanmakuDraft.atLimit("a".repeat(DanmakuDraft.MAX_LEN + 5))).isTrue()
    }

    // ---------------- 弹幕草稿：可发送 ----------------

    /** 有内容就能发。 */
    @Test
    fun `有内容可发送`() {
        assertThat(DanmakuDraft.canSend("哈哈")).isTrue()
        assertThat(DanmakuDraft.canSend("a")).isTrue()
    }

    /**
     * 🔴 空白串**不可发送**。
     *
     * 发出去是一条空弹幕（服务端会拒），而且列表里会占一个点不动的空行。
     */
    @Test
    fun `空白不可发送`() {
        assertThat(DanmakuDraft.canSend("")).isFalse()
        assertThat(DanmakuDraft.canSend("   ")).isFalse()
        assertThat(DanmakuDraft.canSend("\n")).isFalse()
        assertThat(DanmakuDraft.canSend("\t  \n")).isFalse()
    }

    /** 首尾有空白但中间有内容 → 可发送。 */
    @Test
    fun `首尾空白不影响可发送`() {
        assertThat(DanmakuDraft.canSend("  哈哈  ")).isTrue()
    }
}
