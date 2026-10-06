package com.example.biliv3.data.live

/**
 * 直播管理操作的结果。
 *
 * ---
 *
 * # 为什么必须区分这么多种失败
 *
 * 需求第 9 条明确要求区分：成功 / 失败 / 权限不足 / 未登录 / 接口不可用 / 网络错误 / 未知错误。
 *
 * 项目**已经踩过**这个坑（§7.8-44）：Repository 把错误码吞掉返回空数据，
 * 上层把"失败"当成"正常的空状态"。在管理操作上这个错误的代价更大 ——
 * **用户会以为禁言成功了，而实际上没有**，于是一个正在刷屏的人
 * 继续刷屏，而管理员以为处理完了。
 *
 * 所以：每种失败都带**用户能看懂的原因** + **能否重试**的判断。
 */
sealed interface ModerationResult {

    /** 成功。 */
    data object Success : ModerationResult

    /**
     * 失败。
     *
     * @param kind 失败类型（决定 UI 文案与是否有重试按钮）
     * @param message 用户能看懂的原因（**不吞原始信息**）
     * @param code B 站业务错误码（0 = 非业务错误，如网络异常）
     */
    data class Failure(
        val kind: Kind,
        val message: String,
        val code: Int = 0,
    ) : ModerationResult {

        /** 失败类型。 */
        enum class Kind {
            /** 未登录。UI 应引导登录，**重试无用**。 */
            NOT_LOGGED_IN,

            /** 权限不足（不是房管 / 已被撤销）。**重试无用**。 */
            NO_PERMISSION,

            /** 目标用户无效（不存在 / 已离开）。可换人重试。 */
            INVALID_TARGET,

            /** 被频率限制（操作太快）。**稍后重试有效**。 */
            RATE_LIMITED,

            /** 接口不可用（下线 / 路径失效）。**重试无用**，需要改代码。 */
            ENDPOINT_UNAVAILABLE,

            /** 网络错误。**重试有效**。 */
            NETWORK,

            /** 其他未知错误。可重试但未必有用。 */
            UNKNOWN,
        }

        /** 是否值得让用户点"重试"。 */
        val retryable: Boolean
            get() = when (kind) {
                Kind.RATE_LIMITED, Kind.NETWORK, Kind.UNKNOWN -> true
                else -> false
            }
    }

    /** 便捷判断。 */
    val isSuccess: Boolean get() = this is Success
}

/**
 * 把 B 站直播接口的响应翻译成 [ModerationResult]。
 *
 * ## 实测的错误码（2026-10-06，未登录探测）
 *
 * | code | 含义 | 归类 |
 * |---|---|---|
 * | `0` | 成功 | [ModerationResult.Success] |
 * | `-101` / `3` | 未登录 | NOT_LOGGED_IN |
 * | `-403` | 无权限 | NO_PERMISSION |
 * | `-400` | 参数错 | INVALID_TARGET |
 * | `1000003`「方法未在控制器中找到」 | **接口不存在** | ENDPOINT_UNAVAILABLE |
 * | `65530`「invalid request」 | 鉴权/签名被拒 | NO_PERMISSION |
 *
 * ⚠️ `1000003` 与 `65530` 是**关键区分**：
 * - `1000003` = 路径/方法写错 → 这是**代码 bug**，重试一万次也没用
 * - `65530` = 方法存在但被拒 → 可能是权限，也可能是参数不完整
 *
 * 把两者混成一个"操作失败"会让线上排查完全失去方向
 * （本项目在直播接口探测时正是靠这个区分才定位到 WBI 签名的缺失）。
 */
object LiveErrorMapper {

    /** B 站「方法未找到」。 */
    const val CODE_METHOD_MISSING = 1000003

    /** B 站「invalid request」（鉴权/签名被拒）。 */
    const val CODE_INVALID_REQUEST = 65530

    /** 未登录（HTTP 层）。 */
    const val CODE_NOT_LOGGED_IN = -101

    /** 未登录（直播域）。 */
    const val CODE_NOT_LOGGED_IN_LIVE = 3

    /** 无权限。 */
    const val CODE_NO_PERMISSION = -403

    /** 参数错误。 */
    const val CODE_BAD_REQUEST = -400

    /**
     * 按错误码归类。
     *
     * @param code B 站业务码
     * @param message 服务端原始 message（保留给用户/日志，不吞）
     */
    fun fromCode(code: Int, message: String): ModerationResult.Failure = when (code) {
        CODE_NOT_LOGGED_IN, CODE_NOT_LOGGED_IN_LIVE -> ModerationResult.Failure(
            kind = ModerationResult.Failure.Kind.NOT_LOGGED_IN,
            message = "请先登录",
            code = code,
        )

        CODE_NO_PERMISSION, CODE_INVALID_REQUEST -> ModerationResult.Failure(
            kind = ModerationResult.Failure.Kind.NO_PERMISSION,
            message = "权限不足：只有主播或房管可以执行该操作",
            code = code,
        )

        CODE_BAD_REQUEST -> ModerationResult.Failure(
            kind = ModerationResult.Failure.Kind.INVALID_TARGET,
            message = message.ifEmpty { "参数不正确，请确认目标用户" },
            code = code,
        )

        CODE_METHOD_MISSING -> ModerationResult.Failure(
            kind = ModerationResult.Failure.Kind.ENDPOINT_UNAVAILABLE,
            // 这条是**代码问题**，如实说出来比含糊的"操作失败"有用得多
            message = "该功能对应的接口不存在（服务端未找到方法）",
            code = code,
        )

        // 直播域常见的限流码
        -412, -509 -> ModerationResult.Failure(
            kind = ModerationResult.Failure.Kind.RATE_LIMITED,
            message = "操作太频繁，请稍后再试",
            code = code,
        )

        else -> ModerationResult.Failure(
            kind = ModerationResult.Failure.Kind.UNKNOWN,
            message = message.ifEmpty { "操作失败（$code）" },
            code = code,
        )
    }

    /** 网络异常归类（没有业务码）。 */
    fun fromException(e: Throwable): ModerationResult.Failure {
        val raw = e.message.orEmpty()
        val kind = when {
            raw.contains("timeout", ignoreCase = true) -> ModerationResult.Failure.Kind.NETWORK
            raw.contains("Unable to resolve host", ignoreCase = true) ->
                ModerationResult.Failure.Kind.NETWORK
            raw.contains("Failed to connect", ignoreCase = true) ->
                ModerationResult.Failure.Kind.NETWORK
            else -> ModerationResult.Failure.Kind.UNKNOWN
        }
        return ModerationResult.Failure(
            kind = kind,
            message = when (kind) {
                ModerationResult.Failure.Kind.NETWORK -> "网络不可用，请检查连接后重试"
                else -> "操作失败：${raw.ifEmpty { "未知错误" }}"
            },
        )
    }
}
