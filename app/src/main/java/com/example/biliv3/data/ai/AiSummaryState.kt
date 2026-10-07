package com.example.biliv3.data.ai

/**
 * AI 总结的真实状态（v1.6.7）。
 *
 * ---
 *
 * # 🔴 为什么必须有这个类型
 *
 * v1.6.6 及以前，AI 总结的失败被压成**一个布尔**：
 * 官方接口失败 → 直接说"没有访问权限"。这是一个**真实且严重**的错误，
 * 模拟器实测复现了它。
 *
 * 实测（2026-10-07，3 个视频对比）：
 *
 * | 调用方式 | 返回 |
 * |---|---|
 * | **不带** WBI 签名（App 旧做法） | `-403 访问权限不足` |
 * | **带** WBI 签名 | `-101 账号未登录` |
 *
 * 即：**`-403` 是"没签名"的表现，不是权限问题。**
 * 而 UI 把 `-403` 当成"真实权限不足" → 无论登录与否都显示"没有权限"。
 *
 * 根因是"用错误的调用方式实测，得出错误的事实"——
 * 于是错误的事实被写进注释、被当成规格实现。
 *
 * # 状态清单
 *
 * 需求要求至少区分这些状态，这里**逐一对应真实 code**：
 *
 * | 状态 | 触发条件 | 用户看到 |
 * |---|---|---|
 * | [Idle] | 还没请求 | 入口按钮 |
 * | [Loading] | 请求中 | 「正在生成 AI 总结…」 |
 * | [Success] | `code=0` + `data.code=0` | 总结正文 |
 * | [Generating] | `data.code=1` + `stid` 非空 | 「正在生成」+ 刷新 |
 * | [NoSpeech] | `data.code=1` + 无 `stid` | 「没有可用语音内容」 |
 * | [Unsupported] | `data.code=-1` | 「暂不支持 AI 总结」 |
 * | [NotLoggedIn] | `code=-101` | 「登录后使用」 |
 * | [Unauthorized] | `code=-403`（**且已签名**） | 「暂无权限」 |
 * | [BadRequest] | `code=-400` | 参数错误 |
 * | [NetworkError] | 网络异常 | 「网络不可用」+ 重试 |
 * | [ServerError] | 其它 code | 服务端错误 + 重试 |
 * | [ParseError] | `code=0` 但解析不出内容 | 解析失败 + 重试 |
 *
 * # 哪些状态可以重试
 *
 * [retryable] 是**真实语义**，不是 UI 猜的：
 * - 网络 / 服务端 / 解析失败 → 可重试（重试有意义）
 * - 未登录 / 无权限 / 不支持 / 无语音 → **不可重试**
 *   （重试一万次结果都一样，给重试按钮是误导）
 * - 生成中 → 可刷新（等一会儿可能就有了）
 */
sealed interface AiSummaryState {

    /** 还没发起请求。 */
    data object Idle : AiSummaryState

    /** 请求中。 */
    data object Loading : AiSummaryState

    /** 成功。 */
    data class Success(val summary: VideoSummary) : AiSummaryState

    /**
     * 官方正在生成（`data.code=1` 且带 `stid`）。
     *
     * 用户可以稍后刷新 —— 服务端确实在跑这个任务。
     */
    data object Generating : AiSummaryState

    /** 未识别到语音（`data.code=1` 但没有 `stid`）。 */
    data object NoSpeech : AiSummaryState

    /** 该视频不支持 AI 总结（`data.code=-1`）。 */
    data object Unsupported : AiSummaryState

    /** 未登录（`code=-101`）。 */
    data object NotLoggedIn : AiSummaryState

    /**
     * 真实权限不足（`code=-403`）。
     *
     * ⚠️ 只有在**签名正确**的前提下这个状态才可信 ——
     * 见类文档：不带签名时服务端也回 `-403`，那会误报。
     */
    data object Unauthorized : AiSummaryState

    /** 请求参数错误（`code=-400`）。 */
    data class BadRequest(val message: String) : AiSummaryState

    /** 网络不可用。 */
    data class NetworkError(val message: String) : AiSummaryState

    /** 服务端错误（其它非 0 code）。 */
    data class ServerError(val code: Int, val message: String) : AiSummaryState

    /** `code=0` 但内容解析不出来。 */
    data object ParseError : AiSummaryState

    /**
     * 第三方 AI 兜底也失败时的说明（**仅在官方确实不可用时出现**）。
     *
     * 它承载的是"官方为什么没用 + 第三方为什么没用"两条原因，
     * 让用户知道下一步该做什么（去配置 / 去登录 / 等一会儿）。
     */
    data class ThirdPartyUnavailable(val reason: String) : AiSummaryState

    /**
     * 这个状态**重试是否有意义**。
     *
     * 不可重试的状态在 UI 上**不给重试按钮** —— 那会误导用户
     * 反复点击一个永远不会成功的按钮。
     */
    val retryable: Boolean
        get() = when (this) {
            is NetworkError, is ServerError, is ParseError, is BadRequest -> true
            // 生成中给的是"刷新"而不是"重试"，语义不同，见 UI
            is Generating -> true
            else -> false
        }
}
