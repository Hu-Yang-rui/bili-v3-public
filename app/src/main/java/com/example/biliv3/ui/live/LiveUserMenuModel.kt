package com.example.biliv3.ui.live

import com.example.biliv3.data.live.LiveMessage
import com.example.biliv3.data.live.LivePermissions

/**
 * 用户菜单里能出现哪些项（v1.6.6，**纯函数**）。
 *
 * ---
 *
 * # 为什么要把它抽出来
 *
 * 此前菜单项是**写死在 `LiveUserMenu` 的渲染里**的，后果有两个：
 *
 * 1. **没法单测** —— "未登录用户不该看到管理项"这条规则只能靠人眼看
 * 2. 更糟的是 v1.6.4~v1.6.5 期间，`LiveChatPanel` 用
 *    `canModerate && m.hasUser` 决定**能不能点开菜单**，
 *    而菜单里「查看发送者 / 复制弹幕内容 / 复制用户名 / 填入输入框」
 *    **本来就与权限无关** —— 于是普通用户点弹幕完全没反应。
 *
 * 抽成纯函数后，"谁能点开"与"点开后看到什么"分成两件事：
 * - **能不能点** = [LiveMessage.hasUser]（有发送者就行）
 * - **看到什么** = 本函数（按权限算）
 *
 * ## 🔴 权限判定的唯一来源仍是 [LivePermissions]
 *
 * 本函数**不自己判断权限**，只调用 `perms.canActOn(...)`。
 * 这样"UI 隐藏"与"请求前拦截"用的是**同一套规则**（§7.22-140）。
 */
object LiveUserMenuModel {

    /** 菜单项。顺序即渲染顺序。 */
    enum class Item {
        /** 查看发送者资料。 */
        VIEW_SENDER,

        /** 进入个人主页。 */
        OPEN_PROFILE,

        /** 复制这条弹幕的内容。 */
        COPY_TEXT,

        /** 复制发送者用户名。 */
        COPY_NAME,

        /** 把用户名填进输入框。 */
        FILL_INPUT,

        // ---- 以下为管理项，只有主播/房管可见 ----

        /** 解除禁言。 */
        UNMUTE,

        /** 移出黑名单。 */
        UNBLOCK,

        /** 禁言（需选时长）。 */
        MUTE,

        /** 加入黑名单（高风险，需二次确认）。 */
        BLOCK,

        /** 踢出直播间（高风险，需二次确认）。 */
        KICK,

        /** 房管管理（**接口未找到，当前恒不出现**）。 */
        MANAGE_ADMIN,
        ;

        /** 是否是需要权限的管理项。 */
        val isModeration: Boolean
            get() = this == UNMUTE || this == UNBLOCK || this == MUTE ||
                this == BLOCK || this == KICK || this == MANAGE_ADMIN
    }

    /**
     * 通用项（**与权限无关**）。
     *
     * 需求原文：点弹幕即可查看发送者。
     * 所以这些项只要"数据够用"就出现，不看登录态、不看权限。
     */
    private val COMMON: List<Item> = listOf(
        Item.VIEW_SENDER,
        Item.OPEN_PROFILE,
        Item.COPY_TEXT,
        Item.COPY_NAME,
        Item.FILL_INPUT,
    )

    /**
     * 管理项的**固定展示顺序**。
     *
     * ⚠️ 刻意按"动作强度递增"排：先解除类（可逆、弱），
     * 后施加类（不可逆、强）。
     * 若按权限枚举顺序渲染，用户会看到"禁言 / 解除禁言"交错，
     * 误点的概率明显上升。
     */
    private val MODERATION_ORDER: List<Item> = listOf(
        Item.UNMUTE,
        Item.UNBLOCK,
        Item.MUTE,
        Item.BLOCK,
        Item.KICK,
        Item.MANAGE_ADMIN,
    )

    /**
     * [Item] → 权限动作。`null` = 不是管理项。
     *
     * ⚠️ 能映射到动作 **不等于** 菜单里会出现 —— 还要过 [IMPLEMENTED]
     * 这一道（"有权限"与"客户端能做到"是两件事）。
     */
    fun actionOf(item: Item): LivePermissions.Action? = when (item) {
        Item.MUTE -> LivePermissions.Action.MUTE
        Item.UNMUTE -> LivePermissions.Action.UNMUTE
        Item.KICK -> LivePermissions.Action.KICK
        Item.BLOCK -> LivePermissions.Action.BLOCK
        Item.UNBLOCK -> LivePermissions.Action.UNBLOCK
        Item.MANAGE_ADMIN -> LivePermissions.Action.MANAGE_ADMIN
        else -> null
    }

    /**
     * **本客户端真的实现了**的权限动作。
     *
     * ---
     *
     * ## 🔴 为什么需要这个集合（而不是"有权限就显示"）
     *
     * `LivePermissions.actions` 描述的是**角色被允许做什么**
     * （主播"有权限"管理房管）—— 那是**权限模型**，它是对的。
     *
     * 但"客户端**能执行**"是另一件事：
     *
     * | 动作 | 端点实测 | 本客户端 |
     * |---|---|---|
     * | MUTE / UNMUTE / KICK / BLOCK / UNBLOCK | `banned_service/...` → `65530`（**存在**） | ✅ 已实现 |
     * | MANAGE_ADMIN（加/删房管） | 试了 10 个路径全部 **NOT FOUND** | ❌ **无法实现** |
     *
     * 若不在这里拦一道，**主播会看到一个「房管管理」按钮**，
     * 点下去永远只得到"接口未找到" —— 这正是需求禁止的
     * 「做不到却显示成能做」。
     *
     * ## 为什么不直接从 `LivePermissions.actions` 里删掉 MANAGE_ADMIN
     *
     * 那样会**丢掉"主播有这个权限"这个事实** ——
     * 将来一旦找到端点，又要重新推导一遍角色权限。
     * 保持"权限模型完整 + 实现层过滤"，两件事各自独立、各自可验证。
     *
     * ⚠️ 将来找到房管管理端点后，**只需把 MANAGE_ADMIN 加进这个集合**，
     * 菜单项会自动出现（同时应删掉 `LiveRoomViewModel.execute` 里
     * 那个 `ENDPOINT_UNAVAILABLE` 分支）。
     */
    private val IMPLEMENTED: Set<LivePermissions.Action> = setOf(
        LivePermissions.Action.MUTE,
        LivePermissions.Action.UNMUTE,
        LivePermissions.Action.KICK,
        LivePermissions.Action.BLOCK,
        LivePermissions.Action.UNBLOCK,
        // MANAGE_ADMIN 刻意不在其中 —— 端点不存在，见上方说明
    )

    /**
     * 算出这条弹幕的菜单项。
     *
     * @param target 目标消息
     * @param perms 当前账号在本房间的权限（**唯一判定来源**）
     * @return 按渲染顺序排列的菜单项；**永不为空**
     *   （至少会有「关闭」由 UI 自己加，不在此列）
     */
    fun items(target: LiveMessage, perms: LivePermissions): List<Item> {
        val out = ArrayList<Item>(COMMON.size + MODERATION_ORDER.size)

        for (item in COMMON) {
            if (available(item, target)) out.add(item)
        }

        for (item in MODERATION_ORDER) {
            val action = actionOf(item) ?: continue

            // ① 客户端**真的实现了**这个动作吗？
            //    （MANAGE_ADMIN 未实现 —— 端点不存在，见 IMPLEMENTED 的说明）
            if (action !in IMPLEMENTED) continue

            // ② 当前账号**有权限**对这个目标执行吗？
            //    走 `canActOn` —— 与发起请求前的拦截**同一个函数**，
            //    这里不做任何额外判断（不复制规则，避免两处漂移）。
            if (perms.canActOn(target.uid, action)) out.add(item)
        }

        return out
    }

    /** 通用项的可用性（**只看数据够不够，不看权限**）。 */
    private fun available(item: Item, target: LiveMessage): Boolean = when (item) {
        // uid 有效才能查资料 / 进主页
        Item.VIEW_SENDER, Item.OPEN_PROFILE -> target.uid > 0L
        // 有正文才能复制正文
        Item.COPY_TEXT -> target.text.isNotEmpty()
        // 有用户名才能复制 / 填入
        Item.COPY_NAME, Item.FILL_INPUT -> target.uname.isNotEmpty()
        else -> false
    }
}
