package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.model.CommentItem
import com.example.biliv3.data.model.CommentPage
import org.json.JSONObject

/**
 * 评论仓库。
 *
 * ## 接口
 *
 * `x/v2/reply/wbi/main?oid={aid}&type=1&mode=3&next={cursor}&ps=20`
 *
 * 实测未登录 `code=0` 可用（评论是公开数据，不需要登录即可读）。
 *
 * `mode` 取值：
 * - `2` —— 按时间
 * - `3` —— 按热度（默认）
 *
 * ## 分页用游标而不是页码
 *
 * 接口用 `next` 游标（上一页最后一条的 rpid）而不是 page 参数 ——
 * 好处是翻页时新评论插入不会导致重复/漏读。
 * `cursor.is_end` 为 true 时到底。
 */
class CommentRepository(
    private val api: BiliApi,
    /** 凭据存储：写操作需要 csrf（cookie 里的 bili_jct）。 */
    private val store: com.example.biliv3.data.auth.AuthStore? = null,
) {

    val isLoggedIn: Boolean get() = store?.isLoggedIn == true

    /** 当前登录用户 mid（判断"自己的评论"用）。0 = 未登录。 */
    val currentMid: Long get() = store?.mid ?: 0L

    // ---------------- 写操作 ----------------
    //
    // ⚠️ 三个都返回 `-101 账号未登录`（实测），且都需要 csrf。
    // 未登录时**直接失败**而不发请求 —— 少一次无谓的风控计数。

    /**
     * 给评论点赞 / 取消点赞。
     *
     * `action` 语义（实测）：`1` = 点赞，`0` = 取消。
     */
    suspend fun likeComment(
        oid: Long,
        rpid: Long,
        like: Boolean,
    ): Result<Unit> {
        val csrf = store?.biliJct.orEmpty()
        if (csrf.isEmpty()) return Result.failure(IllegalStateException("请先登录"))

        return runCatching {
            val json = api.postForm(
                path = "x/v2/reply/action",
                form = mapOf(
                    "oid" to oid.toString(),
                    "type" to "1",
                    "rpid" to rpid.toString(),
                    "action" to if (like) "1" else "0",
                    "csrf" to csrf,
                ),
            )
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw IllegalStateException(json.optString("message").ifEmpty { "操作失败" })
            }
        }
    }

    /**
     * 删除自己的评论。
     *
     * 接口只允许删**自己**的；删别人的会返回错误码。
     * UI 层已按 `isOwn` 过滤入口，这里再兜一层。
     */
    suspend fun deleteComment(oid: Long, rpid: Long): Result<Unit> {
        val csrf = store?.biliJct.orEmpty()
        if (csrf.isEmpty()) return Result.failure(IllegalStateException("请先登录"))

        return runCatching {
            val json = api.postForm(
                path = "x/v2/reply/del",
                form = mapOf(
                    "oid" to oid.toString(),
                    "type" to "1",
                    "rpid" to rpid.toString(),
                    "csrf" to csrf,
                ),
            )
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw IllegalStateException(json.optString("message").ifEmpty { "删除失败" })
            }
        }
    }

    /**
     * 发表评论 / 回复评论。
     *
     * ## 回复的两种形态（实测确认）
     *
     * 同一个接口 `x/v2/reply/add`，靠参数区分：
     *
     * | 场景 | 参数 |
     * |---|---|
     * | 发主评论 | 只传 `message` |
     * | 回复某条评论 | 传 `root`（主评论 rpid）+ `parent`（被回复的 rpid） |
     *
     * `root == parent` 表示"直接回复主评论"；
     * 若回复的是**回复**，`root` 仍是主评论、`parent` 是该条回复 ——
     * 这就是 B 站楼中楼的结构，传错会导致回复挂错位置。
     *
     * @param root 主评论 rpid。发主评论时传 0
     * @param parent 被回复的 rpid。发主评论时传 0
     */
    suspend fun addComment(
        oid: Long,
        message: String,
        root: Long = 0L,
        parent: Long = 0L,
    ): Result<Long> {
        val csrf = store?.biliJct.orEmpty()
        if (csrf.isEmpty()) return Result.failure(IllegalStateException("请先登录"))
        if (message.isBlank()) return Result.failure(IllegalArgumentException("评论内容不能为空"))

        return runCatching {
            val form = mutableMapOf(
                "oid" to oid.toString(),
                "type" to "1",
                "message" to message,
                "csrf" to csrf,
            )
            if (root > 0) form["root"] = root.toString()
            if (parent > 0) form["parent"] = parent.toString()

            val json = api.postForm(path = "x/v2/reply/add", form = form)
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw IllegalStateException(
                    json.optString("message").ifEmpty { "发送失败" },
                )
            }
            // 返回新评论的 rpid（用于本地插入）
            json.optJSONObject("data")?.optJSONObject("reply")?.optLong("rpid", 0L) ?: 0L
        }
    }

    /**
     * 举报评论 / 弹幕（同一个接口，靠 `type` 区分）。
     *
     * ## 实测确认的坑：理由是**数字码**不是自由文本
     *
     * `x/v2/reply/report` 的 `reason` 是**预置理由的编号**（1–10），
     * 传自由文本会返回 `-400 请求错误`。编号含义（与官方 App 一致）：
     *
     * | 码 | 理由 |
     * |---|---|
     * | 1 | 垃圾广告 |
     * | 2 | 色情低俗 |
     * | 3 | 政治敏感 |
     * | 4 | 人身攻击 |
     * | 5 | 内容引战 |
     * | 6 | 违规刷屏 |
     * | 7 | 侵权盗用 |
     * | 8 | 不实信息 |
     * | 9 | 其他 |
     *
     * ## 举报后本地隐藏
     *
     * 接口不保证立即生效（要人工审核），所以**本地也要隐藏该条**，
     * 否则用户会以为"举报了但没反应"而重复举报。
     *
     * @param type 1=视频评论（本项目只用到这个；弹幕举报走另一接口）
     */
    suspend fun reportComment(
        oid: Long,
        rpid: Long,
        reason: Int,
        type: Int = 1,
    ): Result<Unit> {
        val csrf = store?.biliJct.orEmpty()
        if (csrf.isEmpty()) return Result.failure(IllegalStateException("请先登录"))
        if (reason !in 1..10) {
            return Result.failure(IllegalArgumentException("举报理由无效"))
        }

        return runCatching {
            val json = api.postForm(
                path = "x/v2/reply/report",
                form = mapOf(
                    "oid" to oid.toString(),
                    "type" to type.toString(),
                    "rpid" to rpid.toString(),
                    "reason" to reason.toString(),
                    "content" to "",
                    "csrf" to csrf,
                ),
            )
            val code = json.optInt("code", -1)
            if (code != 0) {
                // -404 是"已举报过"的常见表现，翻成人话
                val msg = json.optString("message")
                throw IllegalStateException(
                    when (code) {
                        -404 -> "你已经举报过了"
                        else -> msg.ifEmpty { "举报失败（$code）" }
                    },
                )
            }
        }
    }

    /**
     * 拉某条评论的**全部回复**（楼中楼）。
     *
     * ## 为什么需要它
     *
     * `reply/wbi/main` 每条评论**最多内嵌 3 条回复**。首版点
     * 「查看全部 N 条回复」只是把已加载的 3 条展开 —— 用户点完
     * 还是只有 3 条，而按钮写着"全部 N 条"，属于信息不实。
     *
     * 这个接口（`x/v2/reply/reply`）才是真正的分页来源。
     *
     * @param root 主评论的 rpid
     * @param page 从 1 开始
     */
    suspend fun replies(
        oid: Long,
        root: Long,
        upMid: Long = 0L,
        page: Int = 1,
        pageSize: Int = 20,
    ): CommentPage {
        if (oid <= 0 || root <= 0) return CommentPage(emptyList(), 0, 0, true)

        val json = try {
            api.getOk(
                path = "x/v2/reply/reply",
                query = mapOf(
                    "oid" to oid.toString(),
                    "type" to "1",
                    "root" to root.toString(),
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                ),
                signed = true,
            )
        } catch (_: Exception) {
            return CommentPage(emptyList(), 0, 0, true)
        }

        val data = json.optJSONObject("data")
            ?: return CommentPage(emptyList(), 0, 0, true)

        val pageObj = data.optJSONObject("page")
        val total = pageObj?.optInt("count", 0) ?: 0
        val pageCount = pageObj?.optInt("count", 0) ?: 0
        val hasMore = page * pageSize < pageCount

        val arr = data.optJSONArray("replies") ?: return CommentPage(emptyList(), total, 0, true)
        val out = ArrayList<CommentItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseComment(o, upMid)?.let { out.add(it) }
        }

        return CommentPage(
            comments = out,
            total = total,
            // 页码分页（不是游标）—— 用页码推下一页的游标值
            nextCursor = (page + 1).toLong(),
            isEnd = !hasMore,
        )
    }

    /**
     * 举报理由选项。
     *
     * 与官方 App 的列表一致（编号必须与接口一致，见 [reportComment]）。
     */
    val reportReasons: List<Pair<Int, String>> = listOf(
        1 to "垃圾广告",
        2 to "色情低俗",
        3 to "政治敏感",
        4 to "人身攻击",
        5 to "内容引战",
        6 to "违规刷屏",
        7 to "侵权盗用",
        8 to "不实信息",
        9 to "其他",
    )

    /**
     * 拉评论。
     *
     * @param oid 视频 aid（type=1 时）
     * @param upMid 视频 UP 主的 mid。用于判断哪条评论是 UP 主本人发的。
     * @param next 游标，首页传 0
     * @param mode 2=按时间 3=按热度
     */
    suspend fun comments(
        oid: Long,
        upMid: Long = 0L,
        next: Long = 0L,
        mode: Int = 3,
        pageSize: Int = 20,
    ): CommentPage {
        if (oid <= 0) return CommentPage(emptyList(), 0, 0, true)

        val json = api.getOk(
            path = "x/v2/reply/wbi/main",
            query = mapOf(
                "oid" to oid.toString(),
                "type" to "1", // 1 = 视频
                "mode" to mode.toString(),
                "next" to next.toString(),
                "ps" to pageSize.toString(),
            ),
            signed = true,
        )

        val data = json.optJSONObject("data")
            ?: return CommentPage(emptyList(), 0, 0, true)

        val cursor = data.optJSONObject("cursor")
        val total = cursor?.optInt("all_count", 0) ?: 0
        val isEnd = cursor?.optBoolean("is_end", true) ?: true
        val nextCursor = cursor?.optLong("next", 0L) ?: 0L

        val arr = data.optJSONArray("replies") ?: return CommentPage(emptyList(), total, 0, true)
        val out = ArrayList<CommentItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseComment(o, upMid)?.let { out.add(it) }
        }

        return CommentPage(
            comments = out,
            total = total,
            nextCursor = nextCursor,
            isEnd = isEnd,
        )
    }

    /**
     * 解析单条评论（含内嵌回复）。
     *
     * 字段是嵌套的：`member.uname` / `member.avatar` / `content.message`。
     * 回复在 `replies` 数组里，结构与主评论相同（可递归）。
     *
     * ## ⚠️ 两个实测确认的坑
     *
     * 1. **`up_action` 是对象不是布尔**
     *    实测返回 `{"like":false,"reply":false}`。用 `optBoolean("up_action")`
     *    会**恒为 false**，导致"UP 主评论"标记永远不显示。
     *    正确判据是 `member.mid == 视频 UP 的 mid`。
     *
     * 2. **接口不返回 `floor` 字段**
     *    实测顶层字段里没有 `floor`，读出来恒为 0。
     *    所以不展示楼层号（展示 0 楼没有意义）。
     */
    private fun parseComment(o: JSONObject, upMid: Long): CommentItem? {
        val rpid = o.optLong("rpid", 0L)
        if (rpid == 0L) return null

        val member = o.optJSONObject("member")
        val content = o.optJSONObject("content")
        val mid = member?.optLong("mid", 0L) ?: 0L

        // 内嵌回复：只解析一层（B 站最多给 3 条，且不再嵌套）
        val repliesArr = o.optJSONArray("replies")
        val replies = if (repliesArr != null) {
            (0 until repliesArr.length()).mapNotNull { i ->
                repliesArr.optJSONObject(i)?.let { parseComment(it, upMid) }
            }
        } else {
            emptyList()
        }

        return CommentItem(
            rpid = rpid,
            oid = o.optLong("oid", 0L),
            mid = mid,
            userName = member?.optString("uname").orEmpty(),
            userFace = member?.optString("avatar").orEmpty(),
            content = content?.optString("message").orEmpty(),
            likeCount = o.optInt("like", 0),
            replyCount = o.optInt("rcount", 0),
            ctime = o.optLong("ctime", 0L),
            // 接口不返回 floor，固定 0（UI 不展示楼层）
            floor = 0,
            // 用 mid 比对判断是否 UP 主（不能用 up_action，它是对象）
            isUp = upMid > 0 && mid == upMid,
            // `action == 1` 表示当前用户已点赞（实测字段名就是 action）
            liked = o.optInt("action", 0) == 1,
            // 自己的评论才显示删除入口
            isOwn = currentMid > 0 && mid == currentMid,
            replies = replies,
            ipLocation = parseIpLocation(o.optJSONObject("reply_control")),
        )
    }

    /**
     * 从 `reply_control.location` 解析 IP 属地。
     *
     * ## 实测形态（登录态）
     *
     * ```json
     * "reply_control": {
     *   "max_line": 6,
     *   "time_desc": "1小时前发布",
     *   "location": "IP属地：陕西",     ← 就是这个
     *   "translation_switch": 1,
     *   "support_share": true
     * }
     * ```
     *
     * ## 为什么做前缀剥离而不是直接存原串
     *
     * 接口给的是**已格式化的展示串**（含 `IP属地：` 前缀 + 全角冒号）。
     * 直接存会让数据模型带上展示层的文案 —— B 站一旦改文案
     * （如改成 `IP：陕西`），模型里的值就跟着变，而 UI 又要拼一遍前缀，
     * 结果会出现 `IP属地：IP：陕西` 这种双重前缀。
     *
     * 所以这里**只取冒号后的部分**。兼容三种前缀形态 + 无前缀。
     *
     * ## 兜底
     *
     * - 字段缺失 / 为 null → 返回空串（UI 隐藏该字段）
     * - 未登录时接口不返回该字段 → 同样返回空串（符合"无则隐藏"要求）
     *
     * @return 纯属地名（如 `陕西`）；无法解析时返回空串
     */
    private fun parseIpLocation(control: JSONObject?): String {
        val raw = control?.optString("location").orEmpty().trim()
        if (raw.isEmpty()) return ""

        // 剥掉可能的前缀。用 `substringAfterLast` 而不是 `removePrefix`，
        // 因为分隔符有全角 `：` 和半角 `:` 两种（实测是全角）。
        val body = when {
            raw.contains('：') -> raw.substringAfterLast('：')
            raw.contains(':') -> raw.substringAfterLast(':')
            else -> raw
        }.trim()

        // 剥完为空说明原串形如 "IP属地：" —— 无效，按无属地处理
        return body
    }
}
