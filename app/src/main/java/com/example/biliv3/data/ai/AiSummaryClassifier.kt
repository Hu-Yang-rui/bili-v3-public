package com.example.biliv3.data.ai

import org.json.JSONObject

/**
 * 把官方 AI 总结的**真实响应**分类成 [AiSummaryState]（v1.6.7，**纯函数**）。
 *
 * ---
 *
 * # 🔴 为什么必须单独一个分类器
 *
 * v1.6.6 及以前，官方接口的任何失败都被压成"没有访问权限"。
 * 模拟器实测复现了这个 bug，根因是**调用方式错误导致误判**：
 *
 * | 调用方式 | 返回 |
 * |---|---|
 * | 不带 WBI 签名 | `-403 访问权限不足` |
 * | 带 WBI 签名 | `-101 账号未登录` |
 *
 * 也就是说 `-403` 在**不带签名**时是"签名缺失"的伪装，
 * 只有**签名正确**时它才真的表示权限不足。
 *
 * 分类器把这条规则写死在一个地方，并且**可被单测覆盖** ——
 * 而不是散在 Repository 的 if/else 里（那样改了没人知道）。
 *
 * # 两层 code
 *
 * 官方把结果码放在**两层**，必须分别判：
 *
 * - **根级 `code`**：请求层面的结果（`-101` 未登录 / `-403` 无权限 / `-400` 参数）
 * - **`data.code`**：内容层面的结果（`0` 有 / `1` 暂无 / `-1` 不支持）
 *
 * 只判一层会漏掉一半情况 —— 例如根级 `0` 但 `data.code=1`（没现成总结）
 * 会被误当成"解析失败"。
 */
object AiSummaryClassifier {

    /** 根级：成功。 */
    private const val CODE_OK = 0

    /** 根级：未登录。 */
    private const val CODE_NOT_LOGGED_IN = -101

    /** 根级：访问权限不足。 */
    private const val CODE_FORBIDDEN = -403

    /** 根级：请求错误。 */
    private const val CODE_BAD_REQUEST = -400

    /** `data.code`：有现成总结。 */
    private const val DATA_OK = 0

    /** `data.code`：暂无总结（需看 `stid` 区分生成中 / 无语音）。 */
    private const val DATA_NOT_READY = 1

    /** `data.code`：不支持或处理异常。 */
    private const val DATA_UNSUPPORTED = -1

    /**
     * 分类一个**已成功取回**的响应体。
     *
     * @param json 官方接口的响应；null 表示响应体无法解析成 JSON
     * @return 对应的状态。**不会返回 [AiSummaryState.Loading]** ——
     *   那是请求开始时的状态，不是响应能表达的
     */
    fun classify(json: JSONObject?): AiSummaryState {
        // 连 JSON 都解不出 —— 归为服务端错误（响应体损坏）
        if (json == null) {
            return AiSummaryState.ServerError(-1, "响应内容无法解析")
        }

        // ---- 第一层：根级 code ----
        val rootCode = json.optInt("code", Int.MIN_VALUE)
        when (rootCode) {
            CODE_OK -> Unit // 继续看 data 层

            CODE_NOT_LOGGED_IN -> return AiSummaryState.NotLoggedIn

            // ⚠️ 只有在**签名正确**时到达这里才可信（见类文档）。
            //    调用方必须保证 signed = true。
            CODE_FORBIDDEN -> return AiSummaryState.Unauthorized

            CODE_BAD_REQUEST -> return AiSummaryState.BadRequest(
                json.optString("message").ifEmpty { "请求参数错误" },
            )

            else -> return AiSummaryState.ServerError(
                code = rootCode,
                message = json.optString("message").ifEmpty { "服务端返回异常" },
            )
        }

        // ---- 第二层：data.code ----
        val data = json.optJSONObject("data")
            ?: return AiSummaryState.ServerError(-1, "响应缺少 data")

        when (val dataCode = data.optInt("code", Int.MIN_VALUE)) {
            DATA_OK -> {
                // 有内容才算成功；解析不出内容 = ParseError（不是"没权限"）
                val summary = SummaryParser.parseOfficial(json)
                return if (summary != null) {
                    AiSummaryState.Success(summary)
                } else {
                    AiSummaryState.ParseError
                }
            }

            DATA_NOT_READY -> {
                // `data.code=1` 有两种情况，用 `stid` 区分：
                //   有 stid → 服务端正在跑这个任务（可稍后刷新）
                //   无 stid → 没识别到语音（刷新也没用）
                //
                // ⚠️ 实测本项目**没有拿到过 `data.code=1` 的样本**
                //    （未登录时根级就 -101 了），所以这条判定按公开结构写。
                //    它对 `stid` 的用法与社区实现一致，但**未在真实数据上验证**。
                val stid = data.optString("stid").trim()
                return if (stid.isNotEmpty()) {
                    AiSummaryState.Generating
                } else {
                    AiSummaryState.NoSpeech
                }
            }

            DATA_UNSUPPORTED -> return AiSummaryState.Unsupported

            else -> return AiSummaryState.ServerError(
                code = dataCode,
                message = data.optString("message").ifEmpty { "AI 总结服务返回异常" },
            )
        }
    }

    /**
     * 把一个**异常**分类成状态（网络 / 其它）。
     *
     * 与 [classify] 分开：那个处理"HTTP 成功但业务失败"，
     * 这个处理"请求本身就没发出去 / 发出去了但断了"。
     */
    fun classifyException(e: Throwable): AiSummaryState {
        val s = (e.message ?: "").lowercase()
        return when {
            s.contains("unable to resolve host") ||
                s.contains("timeout") ||
                s.contains("enotfound") ||
                s.contains("failed to connect") ||
                s.contains("connection refused") ||
                s.contains("econnrefused") ||
                s.contains("network") ->
                AiSummaryState.NetworkError("网络不可用，请检查连接后重试")

            else -> AiSummaryState.ServerError(-1, e.message ?: "未知错误")
        }
    }
}
