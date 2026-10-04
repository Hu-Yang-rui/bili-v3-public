package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.auth.AuthStore
import com.example.biliv3.data.model.PmMessage
import com.example.biliv3.data.model.PmMessagePage
import com.example.biliv3.data.model.PmSession
import com.example.biliv3.data.model.PmSessionPage
import com.example.biliv3.data.model.PmUnread
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 私信仓库。
 *
 * ## 接口（实测确认）
 *
 * | 用途 | 路径 | 域名 |
 * |---|---|---|
 * | 会话列表 | `session_svr/v1/session_svr/get_sessions` | `api.vc.bilibili.com` |
 * | 消息列表 | `svr_sync/v1/svr_sync/fetch_session_msgs` | 同上 |
 * | 未读数 | `session_svr/v1/session_svr/single_unread` | 同上 |
 * | 发送 | `web_im/v1/web_im/send_msg` | 同上 |
 *
 * ⚠️ **私信接口在 `api.vc.bilibili.com`，不在 `api.bilibili.com`** ——
 * 走错域名会得到 404 或空 body。这是本项目接入私信时最容易踩的坑，
 * 所以 [HOST] 单独定义、不共用主 API 域名。
 *
 * 四个接口实测未登录均返回 `-101 账号未登录`，说明**接口本身可用**，
 * 只差登录态（不是风控、不是接口下线）。
 */
class PmRepository(
    private val api: BiliApi,
    private val store: AuthStore,
) {

    /** 私信接口专用域名。 */
    private val host = HOST

    val isLoggedIn: Boolean get() = store.isLoggedIn

    /**
     * 拉会话列表。
     *
     * @param sessionType 1 = 私信会话（不含粉丝分组等）
     */
    suspend fun sessions(
        sessionType: Int = 1,
        offset: Long = 0L,
    ): PmSessionPage {
        if (!isLoggedIn) return PmSessionPage(emptyList(), false)

        val json = api.getRaw(
            path = "session_svr/v1/session_svr/get_sessions",
            query = mapOf(
                "session_type" to sessionType.toString(),
                "group_fold" to "1",
                "unfollow_fold" to "0",
                "sort_rule" to "2",
                "build" to "0",
                "mobi_app" to "android",
                "offset" to offset.toString(),
            ),
            signed = false,
            host = host,
        )

        val code = json.optInt("code", -1)
        if (code != 0) {
            // ⚠️ 失败抛异常（v1.2.5）：返回空页会让"未登录/请求失败"
            // 与"没有任何会话"在 UI 上一样（§7.8-44 同类错误）。
            // 调用方 `MessageViewModel` 已用 runCatching 接住。
            throw BiliException(code, json.optString("message", "会话列表加载失败"))
        }

        val data = json.optJSONObject("data")
            ?: throw BiliException(-1, "会话响应缺少 data")
        val arr = data.optJSONArray("session_list") ?: JSONArray()
        val out = ArrayList<PmSession>(arr.length())

        // ⚠️ 诊断结论（实测确认）：`get_sessions` **不返回昵称与头像**。
        //
        // 原始返回里每个会话只有 `talker_id` + `last_msg`，而
        // `last_msg.content` 是**字符串化 JSON**，里面**也只有 content**：
        // ```
        // "content":"{\"content\":\"你的账号在新设备...\"}"
        // ```
        //
        // 所以"从 last_msg 里取 name/face"这条路是**走不通的** ——
        // 之前那样写导致所有会话都显示"用户{mid}"且头像为空。
        //
        // 昵称/头像必须另查（见 [resolveUser]）。

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parseSession(o) ?: continue)
        }

        // `has_more` 在部分版本是 0/1，部分没有 —— 取不到就认为没有更多
        val hasMore = data.optInt("has_more", 0) == 1

        // 补齐昵称/头像（本接口不返回，必须另查）
        val resolved = resolveUsers(out)
        return PmSessionPage(resolved, hasMore)
    }

    /**
     * 拉与某人的消息列表。
     *
     * ## ⚠️ 路径是 `svr_sync/v1/svr_sync/fetch_session_msgs`（实测选型）
     *
     * 网上常见的 `session_svr/v1/session_svr/get_msgs` **已经下线** ——
     * 实测返回 **404 + HTML 错误页**（不是 JSON、也没有错误码，
     * 表现为"消息列表永远为空"，极难排查）。
     *
     * 候选路径实测：
     *
     * | 路径 | 结果 |
     * |---|---|
     * | `session_svr/.../get_msgs` | ❌ 404 HTML |
     * | `session_svr/.../session_msgs` | ❌ 404 HTML |
     * | **`svr_sync/v1/svr_sync/fetch_session_msgs`** | ✅ `-101`（只差登录） |
     *
     * @param talkerId 对方 mid
     * @param beginSeqno 起始序号。首页传 0（拿最新一批）
     */
    suspend fun messages(
        talkerId: Long,
        beginSeqno: Long = 0L,
    ): PmMessagePage {
        if (!isLoggedIn) return PmMessagePage(emptyList(), 0L, false)

        val json = api.getRaw(
            path = "svr_sync/v1/svr_sync/fetch_session_msgs",
            query = mapOf(
                "talker_id" to talkerId.toString(),
                "session_type" to "1",
                "size" to "20",
                "begin_seqno" to beginSeqno.toString(),
                "build" to "0",
                "mobi_app" to "android",
            ),
            signed = false,
            host = host,
        )

        val code = json.optInt("code", -1)
        if (code != 0) {
            // ⚠️ 失败抛异常（v1.2.5）：与 sessions() 同理。
            // 调用方 `MessageViewModel` 已用 runCatching 接住。
            throw BiliException(code, json.optString("message", "聊天记录加载失败"))
        }

        val data = json.optJSONObject("data")
            ?: throw BiliException(-1, "聊天记录响应缺少 data")
        val arr = data.optJSONArray("messages") ?: JSONArray()
        val myMid = store.mid

        val out = ArrayList<PmMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parseMessage(o, myMid))
        }

        // 接口给的是倒序（新的在前），UI 需要正序（时间从旧到新）
        val sorted = out.sortedBy { it.timestamp }

        return PmMessagePage(
            messages = sorted,
            minSeqno = data.optLong("min_seqno", 0L),
            hasMore = data.optInt("has_more", 0) == 1,
        )
    }

    /**
     * 未读数汇总（用于红点）。
     *
     * `unread_type=0` 返回全部四类（私信/回复/@/赞）。
     */
    suspend fun unread(): PmUnread {
        if (!isLoggedIn) return PmUnread(0, 0, 0, 0)

        return runCatching {
            val json = api.getRaw(
                path = "session_svr/v1/session_svr/single_unread",
                query = mapOf(
                    "unread_type" to "0",
                    "build" to "0",
                    "mobi_app" to "android",
                ),
                signed = false,
                host = host,
            )
            if (json.optInt("code", -1) != 0) return@runCatching PmUnread(0, 0, 0, 0)

            val d = json.optJSONObject("data") ?: return@runCatching PmUnread(0, 0, 0, 0)
            PmUnread(
                message = d.optInt("unread", 0),
                reply = d.optInt("unread_reply", 0),
                at = d.optInt("unread_at", 0),
                like = d.optInt("unread_like", 0),
            )
        }.getOrDefault(PmUnread(0, 0, 0, 0))
    }

    /**
     * 发送私信。
     *
     * ## 必须用**签名**请求
     *
     * 发送是写操作：需要 `csrf`（cookie 里的 bili_jct），
     * 且实测必须走 `wbi` 签名，否则返回 `-403 访问权限不足`。
     *
     * @param content 纯文本内容
     */
    suspend fun send(talkerId: Long, content: String): Result<Unit> {
        val csrf = store.biliJct
        if (csrf.isEmpty()) return Result.failure(IllegalStateException("请先登录"))
        if (content.isBlank()) return Result.failure(IllegalArgumentException("内容不能为空"))

        return runCatching {
            // ⚠️ 私信的 content 需要是**字符串化的 JSON**（与读接口对称）
            val inner = JSONObject().put("content", content).toString()

            val json = api.postFormSigned(
                path = "web_im/v1/web_im/send_msg",
                form = mapOf(
                    "msg[sender_uid]" to store.mid.toString(),
                    "msg[receiver_id]" to talkerId.toString(),
                    "msg[receiver_type]" to "1",
                    "msg[msg_type]" to "1",
                    "msg[msg_status]" to "0",
                    "msg[content]" to inner,
                    "msg[timestamp]" to (System.currentTimeMillis() / 1000).toString(),
                    "msg[dev_id]" to "0",
                    "csrf" to csrf,
                    "csrf_token" to csrf,
                    "build" to "0",
                    "mobi_app" to "android",
                ),
                host = host,
            )

            val code = json.optInt("code", -1)
            if (code != 0) {
                throw IllegalStateException(
                    json.optString("message").ifEmpty { "发送失败（$code）" },
                )
            }
        }
    }

    // ---------------- 解析 ----------------

    /**
     * 解析一个会话。
     *
     * ## ⚠️ 昵称/头像**不在本接口里**（实测确认）
     *
     * `get_sessions` 每个会话只有 `talker_id` + `last_msg`，而
     * `last_msg.content` 是字符串化 JSON、**里面也只有 content**：
     * ```
     * "content":"{\"content\":\"你的账号在新设备...\"}"
     * ```
     *
     * 所以昵称/头像必须另查 —— 见 [resolveUser]。
     * 这里先填占位，由调用方批量补齐（避免 N 次串行请求）。
     */
    private fun parseSession(o: JSONObject): PmSession? {
        val talkerId = o.optLong("talker_id", 0L)
        if (talkerId == 0L) return null

        val lastMsg = o.optJSONObject("last_msg")
        val rawContent = lastMsg?.optString("content").orEmpty()

        // 二次解析：content 是字符串化 JSON（真实坑，不解析则预览恒为空）
        val inner = runCatching { JSONObject(rawContent) }.getOrNull()

        return PmSession(
            talkerId = talkerId,
            // 占位：由 resolveUsers 批量补真实昵称
            talkerName = "用户$talkerId",
            talkerFace = "",
            lastMessage = inner?.optString("content").orEmpty(),
            lastTime = lastMsg?.optLong("timestamp", 0L) ?: 0L,
            unreadCount = o.optInt("unread_count", 0),
            isPinned = o.optInt("is_pinned", 0) == 1,
        )
    }

    /**
     * 批量补齐会话的昵称与头像。
     *
     * ## 为什么用 `x/web-interface/card`（实测选型）
     *
     * | 接口 | 结果 |
     * |---|---|
     * | `x/space/wbi/acc/info` | ❌ `-352` 风控 |
     * | `x/space/acc/info` | ❌ `-799` 需要签名 |
     * | **`x/web-interface/card`** | ✅ `code=0`，含 `name` + `face` |
     *
     * 并发拉取（`async`）而不是串行：一屏 10+ 个会话串行会明显卡顿。
     * 单个失败**不影响其它会话**（保持占位昵称）。
     */
    suspend fun resolveUsers(sessions: List<PmSession>): List<PmSession> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (sessions.isEmpty()) return@withContext sessions

            val results = sessions.map { s ->
                async {
                    runCatching {
                        val json = api.getRaw(
                            path = "x/web-interface/card",
                            query = mapOf("mid" to s.talkerId.toString()),
                            signed = false,
                        )
                        if (json.optInt("code", -1) != 0) return@runCatching null
                        val card = json.optJSONObject("data")?.optJSONObject("card")
                        val name = card?.optString("name").orEmpty()
                        val face = card?.optString("face").orEmpty()
                        if (name.isEmpty()) null else name to face
                    }.getOrNull()
                }
            }.awaitAll()

            sessions.mapIndexed { i, s ->
                val r = results[i]
                if (r == null) s else s.copy(talkerName = r.first, talkerFace = r.second)
            }
        }

    /**
     * 解析一条消息。
     *
     * ## ⚠️ `content` 是字符串化 JSON（真实坑）
     *
     * 形如 `{"content":"你好"}` 的**字符串**，不是嵌套对象。
     * 直接 `optJSONObject("content")` 会得到 null，消息全空。
     */
    private fun parseMessage(o: JSONObject, myMid: Long): PmMessage {
        val raw = o.optString("content").orEmpty()
        val inner = runCatching { JSONObject(raw) }.getOrNull()

        return PmMessage(
            msgKey = o.optLong("msg_key", 0L),
            senderId = o.optLong("sender_uid", 0L),
            text = inner?.optString("content").orEmpty(),
            timestamp = o.optLong("timestamp", 0L),
            isMine = myMid > 0 && o.optLong("sender_uid", 0L) == myMid,
        )
    }

    companion object {
        /**
         * 私信接口域名。
         *
         * ⚠️ 与主 API 域名不同，这是接入私信时最容易踩的坑。
         */
        const val HOST = "api.vc.bilibili.com"
    }
}
