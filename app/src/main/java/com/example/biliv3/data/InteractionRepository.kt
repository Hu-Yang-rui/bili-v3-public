package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.InteractionState
import com.example.biliv3.data.auth.AuthStore

/**
 * 视频互动：点赞 / 投币 / 收藏 / 分享。
 *
 * ## 为什么统一在这里取 csrf
 *
 * 所有写操作都需要 `csrf`，而它的值就是 cookie 里的 `bili_jct`。
 * 让每个调用方自己去 store 里翻 cookie 很容易漏或写错，
 * 统一从 [AuthStore] 取一次。
 *
 * ## 未登录的处理
 *
 * [isLoggedIn] 为 false 时，写操作**直接返回失败**而不发请求 ——
 * 未登录时请求必定被拒（`-101`），白发一次还多一次风控记录。
 * UI 层应先检查登录态并引导登录。
 */
class InteractionRepository(
    private val api: BiliApi,
    private val store: AuthStore,
) {

    val isLoggedIn: Boolean get() = store.isLoggedIn

    /** 查询互动状态。未登录返回全 false。 */
    suspend fun relation(bvid: String): InteractionState {
        if (!isLoggedIn) return InteractionState()
        return api.relation(bvid)
    }

    /** 点赞 / 取消。 */
    suspend fun like(bvid: String, liked: Boolean): Result<Unit> =
        write { csrf -> api.like(bvid, liked, csrf) }

    /** 投币。 */
    suspend fun coin(bvid: String, count: Int, alsoLike: Boolean = false): Result<Unit> =
        write { csrf -> api.coin(bvid, count, csrf, alsoLike) }

    /** 硬币余额。未登录返回 null（UI 据此不显示余额行）。 */
    suspend fun coinBalance(): Double? {
        if (!isLoggedIn) return null
        return api.coinBalance()
    }

    /**
     * 收藏 / 取消收藏。
     *
     * ⚠️ 收藏夹接口的 `up_mid` 是**必填**（实测传空返回 `-400`），
     * 所以必须先拿到当前用户的 mid，否则收藏必定失败。
     *
     * ## 🔴 为什么要传 `aid` 而不是 `bvid`（v1.5.1 修的真实 bug）
     *
     * `fav/resource/deal` 的 `rid` 参数要的是**数字 aid**。
     * 原实现把 bvid 字符串传进去 → 服务端不认 → **收藏从未真正生效**，
     * 但本地图标已经乐观更新了 —— 这就是用户报告的「收藏失效」。
     *
     * 实测（真实账号）：
     * ```
     * rid=<aid>       -> code=0，收藏确实生效（用 fav/resource/ids 复核过）
     * rid=BVxxxxxxx   -> 失败
     * ```
     *
     * ⚠️ 调用方必须传 `VideoDetail.aid`，**不要**传 `bvid`。
     * 这与读接口 `fav/resource/ids`（接受 bvid）不同 —— B 站读写接口
     * 在这点上不一致，别想当然。
     */
    suspend fun favorite(aid: Long, add: Boolean): Result<Unit> {
        if (aid <= 0L) return Result.failure(IllegalStateException("视频 aid 无效"))
        if (!isLoggedIn) return Result.failure(NotLoggedInException())
        val csrf = store.biliJct
        if (csrf.isEmpty()) return Result.failure(NotLoggedInException())

        val mid = store.mid
        if (mid <= 0) return Result.failure(IllegalStateException("登录信息不完整，请重新登录"))

        val folderId = api.defaultFavFolder(mid)
            ?: return Result.failure(IllegalStateException("没有可用的收藏夹"))

        return runCatching {
            if (!api.favorite(aid, add, csrf, listOf(folderId))) {
                throw IllegalStateException(if (add) "收藏失败" else "取消收藏失败")
            }
        }
    }

    /**
     * 分享上报（仅埋点，**永不阻塞用户**）。
     *
     * 实测：未登录时 `share/add` 返回 `-403 账号异常`，比其它互动接口的
     * `-101` 更严。所以：
     * - 未登录直接跳过，不发请求
     * - 任何失败都吞掉 —— 分享面板已经由系统弹出，埋点失败不该报错
     */
    suspend fun shareReport(bvid: String) {
        if (!isLoggedIn) return
        val csrf = store.biliJct
        if (csrf.isEmpty()) return
        runCatching { api.shareReport(bvid, csrf) }
    }

    /** 写操作的公共部分：登录态 + csrf 校验，再执行。 */
    private suspend fun write(block: suspend (String) -> Boolean): Result<Unit> {
        if (!isLoggedIn) return Result.failure(NotLoggedInException())
        val csrf = store.biliJct
        if (csrf.isEmpty()) return Result.failure(NotLoggedInException())

        return runCatching {
            if (!block(csrf)) throw IllegalStateException("操作失败，请稍后重试")
        }
    }
}

/** 未登录。UI 层据此弹登录引导，而不是显示"操作失败"。 */
class NotLoggedInException : Exception("请先登录")
