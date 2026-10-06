package com.example.biliv3.data.live

/**
 * 直播间管理**权限模型**（数据层，可单测）。
 *
 * ---
 *
 * # 为什么权限判断必须在数据层
 *
 * 需求明确要求：**不能通过隐藏按钮来代替真正的权限控制**。
 *
 * 隐藏按钮只是"用户看不到"，而一个被绕过的 UI（或将来新增的入口）
 * 会直接把请求发出去。真正的控制必须在**发起请求之前**由数据层拦掉，
 * 并且服务端也会再拦一次（客户端拦只是省一次注定失败的请求）。
 *
 * 所以这里的 [LivePermissions] 是唯一的判定点：
 * - UI 只**读**它决定显示哪些菜单项
 * - Repository 在发请求前**再查一次**它
 *
 * ## 🔴 已知限制：房管身份无法权威判定
 *
 * 实测（2026-10-06，未登录）：
 * - 房管名单接口**未找到**（试了 10 个路径，全部 NOT FOUND / METHOD MISSING）
 * - `DANMU_MSG.info[2][2]` 疑似 `isAdmin`，但**没有真实房管账号可验证**
 *
 * 因此 [LivePermissions.selfRole] 的 ADMIN 分支是**尽力而为**：
 * 只有当某条弹幕带着 admin 标记时才会建立。拿不到就是 NORMAL。
 *
 * **方向是安全的**：把房管误判成普通用户 = 少显示管理入口（用户会觉得
 * 不方便）；把普通用户误判成房管 = 显示不该有的入口（用户点了会失败）。
 * 后者更糟，所以宁可漏判。
 *
 * ## 主播身份是**可靠**的
 *
 * `getInfoByRoom` / `get_anchor_in_room` 都返回主播 uid（实测可用），
 * 与当前登录 mid 直接比较即可 —— 这条没有猜测成分。
 *
 * @param loggedIn 是否已登录（未登录一切写操作都不可用）
 * @param selfMid 当前登录用户 mid（0 = 未知）
 * @param anchorUid 本直播间主播 uid（0 = 未知）
 * @param selfRole 当前用户在本直播间的身份（**尽力而为**，见类文档）
 */
data class LivePermissions(
    val loggedIn: Boolean,
    val selfMid: Long,
    val anchorUid: Long,
    val selfRole: LiveRole,
) {

    /** 是否确认是本房间主播（**唯一可靠的提权路径**）。 */
    val isAnchor: Boolean
        get() = loggedIn && selfMid > 0L && anchorUid > 0L && selfMid == anchorUid

    /**
     * 是否具备房管级能力。
     *
     * ⚠️ 主播**天然**具备房管能力；房管身份来自 [selfRole]（尽力而为）。
     */
    val isAdmin: Boolean
        get() = isAnchor || (loggedIn && selfRole == LiveRole.ADMIN)

    /**
     * 可以执行哪些管理操作。
     *
     * ## 为什么主播与房管**必须分开**
     *
     * 需求明确要求"不能让普通房管拥有主播才拥有的操作"。
     * 实测**未找到**房管名单接口，所以「添加/移除房管」这一类
     * 无法确认房管是否也有权限 —— 保守起见**只给主播**。
     *
     * 这与"服务端最终裁决"不冲突：客户端拦掉的是**确定不该有**的，
     * 而不是替代服务端判断。
     */
    val actions: Set<Action>
        get() {
            if (!loggedIn) return emptySet()
            val out = mutableSetOf<Action>()
            if (isAdmin) {
                // 房管能做的（这些是社区公认的房管权限）
                out += Action.MUTE
                out += Action.UNMUTE
                out += Action.KICK
                out += Action.BLOCK
                out += Action.UNBLOCK
            }
            if (isAnchor) {
                // 只有主播能做的
                out += Action.MANAGE_ADMIN
            }
            return out
        }

    /** 能否对**别人**执行某操作（不能对自己操作）。 */
    fun canActOn(targetUid: Long, action: Action): Boolean {
        if (!loggedIn) return false
        if (targetUid <= 0L) return false
        // 不能对自己操作 —— 服务端也会拒，客户端先拦掉省一次请求
        if (selfMid > 0L && targetUid == selfMid) return false
        // 不能对主播操作（房管禁言主播是无意义的，服务端必拒）
        if (anchorUid > 0L && targetUid == anchorUid) return false
        return action in actions
    }

    /** 管理操作类型。 */
    enum class Action {
        /** 禁言。 */
        MUTE,

        /** 解除禁言。 */
        UNMUTE,

        /** 踢出直播间。 */
        KICK,

        /** 加入黑名单。 */
        BLOCK,

        /** 移出黑名单。 */
        UNBLOCK,

        /** 添加 / 移除房管（**仅主播**）。 */
        MANAGE_ADMIN,
    }

    companion object {
        /** 未登录 / 信息未知时的默认值（**最小权限**）。 */
        val NONE = LivePermissions(
            loggedIn = false,
            selfMid = 0L,
            anchorUid = 0L,
            selfRole = LiveRole.NORMAL,
        )
    }
}
