package com.example.biliv3.data

import com.example.biliv3.data.model.CoverUrls
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
     * 把某个会话标记为已读。
     *
     * ## 🔴 v1.5.3 新增（用户报告：进会话后红点不消）
     *
     * 原实现**根本没有已读接口** —— 会话页只拉消息、不回报已读，
     * 所以服务端未读数永远不变，红点自然不消。
     *
     * ## 端点与参数（实测确认）
     *
     * ```
     * POST api.vc.bilibili.com/session_svr/v1/session_svr/update_ack
     *   talker_id    = 对端 mid
     *   session_type = 1（私信）
     *   ack_seqno    = 要确认到的序列号（传会话的 max_seqno 即"全部已读"）
     *   csrf         = bili_jct
     * ```
     *
     * 实测返回 `code=0`（"入口节点已存在"是 `1000004`，只在参数非法时出现，
     * 说明端点存在且参数被接受）。
     *
     * ## ⚠️ 失败必须让调用方知道
     *
     * 返回 `Result` 而不是 `Boolean` —— 未读清零失败时**不能假装成功**
     * （否则 UI 红点消了、下次进页面又冒出来，用户以为"红点修好了"）。
     * 调用方据此决定是否重试 / 提示。
     *
     * @param ackSeqno 要确认到的序列号。传会话列表里的 `max_seqno` 表示全读。
     */
    suspend fun markRead(talkerId: Long, ackSeqno: Long): Result<Unit> {
        if (talkerId <= 0L) return Result.failure(IllegalArgumentException("会话 id 无效"))
        val csrf = store.biliJct
        if (csrf.isEmpty()) return Result.failure(IllegalStateException("请先登录"))

        return runCatching {
            val json = api.postForm(
                path = "session_svr/v1/session_svr/update_ack",
                form = mapOf(
                    "talker_id" to talkerId.toString(),
                    "session_type" to "1",
                    "ack_seqno" to ackSeqno.toString(),
                    "csrf" to csrf,
                    "csrf_token" to csrf,
                ),
                host = host,
            )
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw BiliException(code, json.optString("message", "标记已读失败"))
            }
        }
    }

    /**
     * 未读数汇总（用于红点）。
     *
     * `unread_type=0` 返回全部四类（私信/回复/@/赞）。
     *
     * ---
     *
     * # 🔴 v1.6.7 修：解析的字段名**全都不存在**，红点永远不亮
     *
     * ## 实测的真实响应（模拟器 + 真实账号）
     *
     * ```json
     * {"code":0,"data":{
     *    "unfollow_unread":0, "follow_unread":0,
     *    "unfollow_push_msg":0, "dustbin_push_msg":0, "dustbin_unread":0,
     *    "biz_msg_unfollow_unread":1, "biz_msg_follow_unread":1,
     *    "custom_unread":0}}
     * ```
     *
     * 旧实现读的是 `unread` / `unread_reply` / `unread_at` / `unread_like`
     * —— **这四个字段一个都不在响应里** → 全部 `optInt(..., 0)` 得到 0 →
     * **未读数恒为 0，首页铃铛红点永远不亮**。
     *
     * ## 为什么 `biz_msg_*` 才是私信未读
     *
     * 与会话列表**交叉验证**（同一次会话，`get_sessions` 20 个会话）：
     *
     * | 来源 | 值 |
     * |---|---|
     * | `get_sessions` 里有未读的会话数 | **2**（`biz_msg_unread_count=1`） |
     * | `single_unread` 的 `biz_msg_unfollow_unread` | **1** |
     * | `single_unread` 的 `biz_msg_follow_unread` | **1** |
     * | 两者相加 | **2** ✅ 吻合 |
     *
     * 所以私信未读 = `biz_msg_follow_unread + biz_msg_unfollow_unread`。
     *
     * ## 保留旧字段读取（兼容）
     *
     * 服务端可能按账号/版本返回不同字段集，所以旧字段仍然读 ——
     * 只是**不再单独依赖它们**，而是与真实字段取较大值。
     * 宁可多亮红点，也不要漏掉真实未读。
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

            // 🔴 真实字段（实测确认存在）
            val bizFollow = d.optInt("biz_msg_follow_unread", 0)
            val bizUnfollow = d.optInt("biz_msg_unfollow_unread", 0)
            val bizTotal = bizFollow + bizUnfollow

            // 旧字段（实测**不存在**，保留读取以防服务端按账号返回不同结构）
            val legacyMessage = d.optInt("unread", 0)

            PmUnread(
                // 取较大值：不漏报真实未读
                message = maxOf(bizTotal, legacyMessage, 0),
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
            unreadCount = sessionUnread(o),
            isPinned = o.optInt("is_pinned", 0) == 1,
        )
    }

    /**
     * 一个会话的**真实未读数**（v1.6.7 修的真实 bug）。
     *
     * ---
     *
     * # 🔴 根因：`unread_count` 恒为 0，真实未读在另一个字段
     *
     * 模拟器实测（真实账号，`get_sessions` 20 个会话）：
     *
     * ```
     * counts=[u=0/biz=0/newPush=0, u=0/biz=1/newPush=1, u=0/biz=1/newPush=1,
     *         u=0/biz=0/newPush=0, ... 其余 17 个全 0]
     * ```
     *
     * **全部 20 个会话的 `unread_count` 都是 0**，
     * 而其中 2 个的 `biz_msg_unread_count` 与 `new_push_msg` 是 **1**。
     *
     * 旧实现只读 `unread_count` → **未读数恒为 0** →
     * 首页铃铛红点**永远不亮**。
     *
     * 这与 `single_unread` 的返回**完全对得上**：
     * ```
     * {"biz_msg_unfollow_unread":1,"biz_msg_follow_unread":1}
     * ```
     * —— 同样只有 `biz_msg_*` 系列有值，没有 `unread` 字段。
     *
     * # 为什么是"三个字段取最大"而不是"只换一个字段"
     *
     * 三个字段语义不同、可能各自独立有值：
     *
     * | 字段 | 含义 |
     * |---|---|
     * | `unread_count` | 传统私信未读（老会话可能只有它有值） |
     * | `biz_msg_unread_count` | 业务消息未读（**当前账号的真实未读在这里**） |
     * | `new_push_msg` | 新推送消息数 |
     *
     * 取最大值：**宁可多亮红点，也不要漏掉真实未读**。
     * 红点"多亮"用户点进去就消了；"漏亮"则会让用户以为没有新消息
     * （而任务书明确要求"如果服务器真实仍有未读，不能强制隐藏红点"）。
     *
     * ⚠️ **不把它们相加** —— 这三个字段在 B 站服务端很可能是
     * **同一批未读的不同投影**（实测 `biz_msg_unread_count` 与
     * `new_push_msg` 值完全相同），相加会得到 2 倍虚高的数字。
     */
    private fun sessionUnread(o: JSONObject): Int {
        val legacy = o.optInt("unread_count", 0)
        val biz = o.optInt("biz_msg_unread_count", 0)
        val push = o.optInt("new_push_msg", 0)
        return maxOf(legacy, biz, push, 0)
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
     *
     * ## 🔴 v1.5.3：必须按 `msg_type` 分发（图片消息的真实结构）
     *
     * 原实现只读 `inner.content` —— **图片消息因此变成空文本**，
     * 然后被 `isUnsupported`（`text.isEmpty()`）判成"不支持"，
     * UI 只显示一句"暂不支持的消息类型"。
     *
     * 实测（真实账号，扫 20 个会话共 148 条消息）：
     *
     * | msg_type | 含义 | content 内层结构 |
     * |---|---|---|
     * | `1` | 文字 | `{"content":"你好"}` |
     * | `2` | **图片** | `{"url":"https://message.biliimg.com/...jpg","height":1138,"width":850}` |
     * | `10` | 系统通知 | 登录提醒等 |
     *
     * 图片的 `url` **不带协议**时要用 `CoverUrls` 统一补全
     * （实测带 `https:`，但 `//` 形态在其它接口常见，防御性处理）。
     *
     * ⚠️ 未知类型**不猜** —— 保留 `msgType` 原值，由 UI 明示"不支持"。
     */
    private fun parseMessage(o: JSONObject, myMid: Long): PmMessage {
        val raw = o.optString("content").orEmpty()
        val inner = runCatching { JSONObject(raw) }.getOrNull()
        val type = o.optInt("msg_type", 1)
        val senderId = o.optLong("sender_uid", 0L)

        // 按类型取正文 —— 图片消息没有 content，只有 url
        val text: String
        val imageUrl: String
        var imageW = 0
        var imageH = 0
        when (type) {
            // 图片：url + 宽高
            2 -> {
                text = ""
                imageUrl = CoverUrls.normalize(inner?.optString("url").orEmpty())
                imageW = inner?.optInt("width", 0) ?: 0
                imageH = inner?.optInt("height", 0) ?: 0
            }
            // 文字与其它：取 content（可能为空）
            else -> {
                text = inner?.optString("content").orEmpty()
                imageUrl = ""
            }
        }

        return PmMessage(
            msgKey = o.optLong("msg_key", 0L),
            senderId = senderId,
            text = text,
            timestamp = o.optLong("timestamp", 0L),
            isMine = myMid > 0 && senderId == myMid,
            msgType = type,
            imageUrl = imageUrl,
            imageWidth = imageW,
            imageHeight = imageH,
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
