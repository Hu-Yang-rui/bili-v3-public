package com.example.biliv3.data.live

import com.example.biliv3.data.MiniJson

/**
 * 直播间里的一条消息。
 *
 * ## 为什么用**一个**模型而不是每种消息一个类
 *
 * 聊天区是一条**混合时间线**：弹幕、进场、礼物、系统通知交替出现，
 * 顺序本身就是信息。拆成多个列表会丢掉顺序，UI 还得再合并一次。
 *
 * 所以：一个模型 + 一个 [Kind]，UI 按 Kind 决定怎么渲染那一行。
 *
 * @param kind 消息类型（决定渲染方式）
 * @param uid 发送者 uid；0 = 无发送者（系统消息）
 * @param uname 发送者昵称
 * @param face 头像 URL（**相对/协议相对形态原样保留**，由 UI 走 `CoverUrls.normalize`）
 * @param text 正文（弹幕内容 / 系统文案）
 * @param role 发送者身份（由 [LiveRoleResolver] 判定）
 * @param medalName 粉丝牌名（空 = 无牌子）
 * @param medalLevel 粉丝牌等级
 * @param guardLevel 大航海等级（0=无 1=总督 2=提督 3=舰长）
 * @param userLevel 用户等级
 * @param tsMs 服务端时间戳（毫秒）；0 = 未知
 */
data class LiveMessage(
    val kind: Kind,
    val uid: Long,
    val uname: String,
    val face: String,
    val text: String,
    val role: LiveRole = LiveRole.NORMAL,
    val medalName: String = "",
    val medalLevel: Int = 0,
    val guardLevel: Int = 0,
    val userLevel: Int = 0,
    val tsMs: Long = 0L,
) {
    /** 消息类型。 */
    enum class Kind {
        /** 普通弹幕。 */
        DANMAKU,

        /** 进入直播间。 */
        ENTER,

        /** 关注了主播。 */
        FOLLOW,

        /** 为主播点赞。 */
        LIKE,

        /** 进场特效（高价值用户，比普通进场更醒目）。 */
        ENTRY_EFFECT,

        /** 礼物。 */
        GIFT,

        /** 系统消息（房间公告、被禁言提示、连接状态等）。 */
        SYSTEM,
    }

    /** 是否是一条"用户发言"（决定能不能点开用户菜单）。 */
    val hasUser: Boolean get() = uid > 0L && kind != Kind.SYSTEM

    /**
     * 渲染用的稳定 key。
     *
     * ⚠️ 不能用 `hashCode()`：同一个人连发两条一样的弹幕会撞 key，
     * LazyColumn 会把它当成同一条（表现为"少了一条"）。
     * 用 序号 + uid + 时间戳 组合。
     */
    fun stableKey(seq: Long): String = "$seq:$uid:$tsMs"
}

/**
 * 直播间用户身份。
 *
 * ## 与"权限"分开（重要）
 *
 * 身份是**展示**用的（徽章），权限是**行为**用的（能不能禁言）。
 * 两者相关但不是一回事 —— 比如"自己"也可能是房管。
 * 合并成一个枚举会让"自己是房管"这种组合表达不出来。
 */
enum class LiveRole {
    /** 主播（房间所有者）。 */
    ANCHOR,

    /** 房管。 */
    ADMIN,

    /** 普通观众。 */
    NORMAL,
    ;

    /** 徽章文案（空 = 不显示徽章）。 */
    val label: String
        get() = when (this) {
            ANCHOR -> "主播"
            ADMIN -> "房管"
            NORMAL -> ""
        }
}

/**
 * 弹幕消息解析（**纯函数**，可单测）。
 *
 * ---
 *
 * # 实测字段（2026-10-06，真实房间 545068）
 *
 * ## DANMU_MSG
 * ```
 * info[1]      = 弹幕正文
 * info[2][0]   = uid
 * info[2][1]   = uname
 * info[2][2]   = 疑似 isAdmin（0/1）⚠️ 未在真实房管账号上验证过
 * info[2][5]   = 用户等级
 * info[3]      = 粉丝牌 [level, name, anchor, roomid, ...]（空数组 = 无牌子）
 * info[7]      = 大航海等级（0/1/2/3）✅ 实测 3
 * info[0][15].user.base.face = 头像
 * ```
 *
 * ## INTERACT_WORD_V2（进入直播间）—— **不是 JSON**
 * ```
 * data.pb = base64(protobuf):
 *   field 1  = uid
 *   field 2  = uname
 *   field 5  = msg_type（1=进入 2=关注 3=分享）
 *   field 22 = { field1=uid, field2={field1=uname, field2=face} }
 * ```
 * 只读 `data.uid` / `data.uname` 会**全部拿到 0 / 空** ——
 * 因为新协议把它们挪进了 pb。这是最容易踩的一个坑。
 *
 * ## ENTRY_EFFECT
 * ```
 * data.uid / data.face / data.copy_writing（含 <%...%> 模板）
 * data.uinfo.base.name
 * ```
 *
 * ## LIKE_INFO_V3_CLICK
 * ```
 * data.uid / data.uname / data.like_text / data.uinfo.base.face
 * ```
 */
object LiveMessageParser {

    /**
     * 解析一条 JSON 消息。
     *
     * @param anchorUid 主播 uid（用于判定 [LiveRole.ANCHOR]）
     * @return 无法识别 / 与聊天无关的消息返回 null（由调用方丢弃）
     */
    fun parse(json: String, anchorUid: Long = 0L): LiveMessage? {
        val cmd = MiniJson.string(json, "cmd") ?: return null
        // 有些 cmd 带后缀，如 "DANMU_MSG:4:0:2:2:2:0"
        val base = cmd.substringBefore(':')

        return when (base) {
            "DANMU_MSG" -> parseDanmaku(json, anchorUid)
            "INTERACT_WORD", "INTERACT_WORD_V2" -> parseInteract(json, anchorUid)
            "ENTRY_EFFECT" -> parseEntryEffect(json, anchorUid)
            "LIKE_INFO_V3_CLICK" -> parseLike(json, anchorUid)
            else -> null
        }
    }

    // ---------------- 弹幕 ----------------

    private fun parseDanmaku(json: String, anchorUid: Long): LiveMessage? {
        // ⚠️ `info` 的元素**既有对象也有数组**（`[[...], "正文", [...], [...]]`），
        //    而 `MiniJson.elements` 只收 `{...}` 对象 —— 用它切 `info` 会得到
        //    **0 个元素**（实测：`elems.size = 0`），表现为"所有弹幕都解析不出来"。
        //
        //    所以这里用本文件自己的 [splitTopLevel]，它能同时切出数组与标量。
        val infoText = MiniJson.arrayText(json, "info") ?: return null
        val elems = splitTopLevel(infoText)
        if (elems.size < 3) return null

        // ⚠️ elems[1] 是**裸字符串**（形如 `"面包怎么你了"`，带引号），
        //    不是对象 —— 所以不能走 MiniJson.string（那个要 key）。
        //    直接剥引号即可。
        val text = stripQuotes(elems[1])
        if (text.isEmpty()) return null

        // info[2] = [uid, uname, isAdmin, ...]
        //
        // ⚠️ 这里也**必须用 [splitTopLevel]**，不能用 `MiniJson.elements`：
        //    后者对**顶层裸字符串**有缺陷 —— 引号一翻 `inString`，
        //    后面的 `depth == 0 && !isWhitespace() && start < 0` 就再也进不去，
        //    于是 `"n"` 这种元素被整个吞掉，数组下标全部错位
        //    （实测表现为 `uname` 读成 `0`、`userLevel` 读成 `1`）。
        val meta = splitTopLevel(elems.getOrNull(2).orEmpty())
        val uid = meta.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
        val uname = stripQuotes(meta.getOrNull(1))
        val isAdmin = (meta.getOrNull(2)?.trim()?.toIntOrNull() ?: 0) == 1

        // info[7] = 大航海等级
        val guard = elems.getOrNull(7)?.trim()?.toIntOrNull() ?: 0

        // info[3] = 粉丝牌 [level, name, ...]（空数组表示没有）
        val medalElems = splitTopLevel(elems.getOrNull(3).orEmpty())
        val medalLevel = medalElems.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val medalName = stripQuotes(medalElems.getOrNull(1))

        // info[2][5] = 用户等级
        val userLevel = meta.getOrNull(5)?.trim()?.toIntOrNull() ?: 0

        // 头像在 info[0][15].user.base.face —— 嵌套较深，用 objectText 逐层取
        val face = extractDanmakuFace(elems.getOrNull(0).orEmpty())

        // info[0][4] = 发送时间（毫秒）
        val ts = splitTopLevel(elems.getOrNull(0).orEmpty())
            .getOrNull(4)?.trim()?.toLongOrNull() ?: 0L

        return LiveMessage(
            kind = LiveMessage.Kind.DANMAKU,
            uid = uid,
            uname = uname,
            face = face,
            text = text,
            role = LiveRoleResolver.role(uid = uid, anchorUid = anchorUid, isAdmin = isAdmin),
            medalName = medalName,
            medalLevel = medalLevel,
            guardLevel = guard,
            userLevel = userLevel,
            tsMs = ts,
        )
    }

    /** 从 `info[0]` 里挖出头像 URL（`...{"user":{"base":{"face":"..."}}}`）。 */
    private fun extractDanmakuFace(info0: String): String {
        // ⚠️ `info[0]` 里混着标量与对象，必须用 splitTopLevel
        //    （MiniJson.elements 只收 `{...}`，会把那个 user 对象之前
        //     的标量全部丢掉，且遇到 `[` 嵌套会提前终止）
        val arr = splitTopLevel(info0)
        for (e in arr) {
            if (!e.trimStart().startsWith("{")) continue
            val user = MiniJson.objectText(e, "user") ?: continue
            val base = MiniJson.objectText(user, "base") ?: continue
            val face = MiniJson.string(base, "face").orEmpty()
            if (face.isNotEmpty()) return face
        }
        return ""
    }

    // ---------------- 进入直播间（protobuf！）----------------

    private fun parseInteract(json: String, anchorUid: Long): LiveMessage? {
        // ⚠️ 新协议把数据放在 data.pb（base64 protobuf）里，
        //    直接读 data.uid / data.uname 会全部拿到空。
        val pb = MiniJson.string(json, "pb")

        var uid = 0L
        var uname = ""
        var face = ""
        var msgType = 1

        if (!pb.isNullOrEmpty()) {
            val decoded = LiveProtobuf.decodeInteractWord(pb)
            uid = decoded.uid
            uname = decoded.uname
            face = decoded.face
            msgType = decoded.msgType
        }

        // 兜底：老版 INTERACT_WORD 是纯 JSON
        if (uid <= 0L) uid = MiniJson.long(json, "uid") ?: 0L
        if (uname.isEmpty()) uname = MiniJson.string(json, "uname").orEmpty()
        if (face.isEmpty()) face = MiniJson.string(json, "face").orEmpty()
        if (msgType == 1) msgType = MiniJson.int(json, "msg_type") ?: 1

        if (uid <= 0L && uname.isEmpty()) return null

        val kind = when (msgType) {
            2 -> LiveMessage.Kind.FOLLOW
            3 -> LiveMessage.Kind.LIKE
            else -> LiveMessage.Kind.ENTER
        }

        return LiveMessage(
            kind = kind,
            uid = uid,
            uname = uname,
            face = face,
            text = "",
            role = LiveRoleResolver.role(uid = uid, anchorUid = anchorUid, isAdmin = false),
            tsMs = (MiniJson.long(json, "timestamp") ?: 0L) * 1000L,
        )
    }

    // ---------------- 进场特效 ----------------

    private fun parseEntryEffect(json: String, anchorUid: Long): LiveMessage? {
        val uid = MiniJson.long(json, "uid") ?: 0L
        if (uid <= 0L) return null

        val face = MiniJson.string(json, "face").orEmpty()
        // copy_writing 里带 <%...%> 模板标记，渲染前要剥掉
        val raw = MiniJson.string(json, "copy_writing")
            ?: MiniJson.string(json, "copy_writing_v2").orEmpty()

        // 用户名在 data.uinfo.base.name
        val uinfo = MiniJson.objectText(json, "uinfo") ?: ""
        val base = MiniJson.objectText(uinfo, "base") ?: ""
        val uname = MiniJson.string(base, "name").orEmpty()

        return LiveMessage(
            kind = LiveMessage.Kind.ENTRY_EFFECT,
            uid = uid,
            uname = uname,
            face = face,
            text = cleanEntryText(raw, uname),
            role = LiveRoleResolver.role(uid = uid, anchorUid = anchorUid, isAdmin = false),
        )
    }

    /**
     * 清理进场文案。
     *
     * 实测原样是 `<%春风得意_马蹄疾-_-%> 来了` —— 尖括号里的 `%...%`
     * 是**服务端模板标记**（前端用它替换用户名）。直接渲染会显示成一堆符号。
     */
    fun cleanEntryText(raw: String, uname: String): String {
        if (raw.isEmpty()) return if (uname.isNotEmpty()) "$uname 来了" else "来了"
        // 去掉 <%...%> 整段
        var t = raw.replace(Regex("<%[^%]*%>"), "").trim()
        if (t.isEmpty()) t = "来了"
        return t
    }

    // ---------------- 点赞 ----------------

    private fun parseLike(json: String, anchorUid: Long): LiveMessage? {
        val uid = MiniJson.long(json, "uid") ?: 0L
        if (uid <= 0L) return null
        val uname = MiniJson.string(json, "uname").orEmpty()
        val text = MiniJson.string(json, "like_text").orEmpty().ifEmpty { "为主播点赞了" }
        val uinfo = MiniJson.objectText(json, "uinfo") ?: ""
        val base = MiniJson.objectText(uinfo, "base") ?: ""
        val face = MiniJson.string(base, "face").orEmpty()

        return LiveMessage(
            kind = LiveMessage.Kind.LIKE,
            uid = uid,
            uname = uname,
            face = face,
            text = text,
            role = LiveRoleResolver.role(uid = uid, anchorUid = anchorUid, isAdmin = false),
        )
    }

    /** 剥掉 JSON 字符串字面量的引号（[splitTopLevel] 对裸标量返回带引号原文）。 */
    private fun stripQuotes(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val t = s.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return MiniJson.unescape(t.substring(1, t.length - 1))
        }
        return t
    }

    /**
     * 切分**顶层**数组元素（数组 / 对象 / 标量**都收**）。
     *
     * ## 🔴 为什么不能用 `MiniJson.elements`
     *
     * `MiniJson.elements` 是为"数组里装对象"设计的（见其 KDoc）：
     * 它只在 `depth == 0` 且字符是 `}` 时收元素。
     *
     * 而 `DANMU_MSG.info` 是**混合**的：
     * ```json
     * [[0,1,25,...], "弹幕正文", [uid,"uname",...], [31,"牌子",...], 0,0,0,3]
     *    ↑ 数组        ↑ 标量      ↑ 数组            ↑ 数组
     * ```
     * 用 `MiniJson.elements` 切它 → **0 个元素**（实测），
     * 于是所有弹幕都解析失败、聊天区永远空的。
     *
     * 这里改为"按 depth 归零时收尾"，同时覆盖 `}` / `]` / 逗号三种结束符。
     *
     * ⚠️ 只用于**本项目自己解析的直播消息**，不动共享的 `MiniJson`
     * （它有 47 个测试，改动风险大于收益）。
     */
    internal fun splitTopLevel(arrText: String): List<String> {
        val open = arrText.indexOf('[')
        if (open < 0) return emptyList()

        val out = ArrayList<String>(8)
        var depth = 0
        var inString = false
        var escaped = false
        var start = -1

        for (i in (open + 1) until arrText.length) {
            val c = arrText[i]
            when {
                escaped -> escaped = false

                c == '\\' && inString -> escaped = true

                // ⚠️ 开引号必须**先于** `inString -> Unit` 判断，
                //    且在 depth==0 时把 start 记上 —— 否则顶层裸字符串
                //    （`info[1]` 就是弹幕正文）会被整个吞掉：
                //    引号把 inString 翻成 true，后面所有字符都走
                //    `inString -> Unit`，start 永远是 -1。
                c == '"' -> {
                    if (depth == 0 && !inString && start < 0) start = i
                    inString = !inString
                }

                inString -> Unit

                c == '[' || c == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }

                c == ']' || c == '}' -> {
                    depth--
                    if (depth == 0) {
                        // 收一个完整元素（数组或对象）
                        if (start >= 0) out.add(arrText.substring(start, i + 1))
                        start = -1
                    }
                    if (depth < 0) break
                }

                // 顶层标量（数字 / true / null）用逗号切分
                depth == 0 && c == ',' -> {
                    if (start >= 0) {
                        out.add(arrText.substring(start, i).trim())
                        start = -1
                    }
                }

                depth == 0 && !c.isWhitespace() && start < 0 -> start = i
            }
        }
        // 收尾：最后一个标量元素没有逗号结束
        if (start >= 0) {
            val close = arrText.indexOf(']', start)
            val end = if (close >= 0) close else arrText.length
            val tail = arrText.substring(start, end).trim()
            if (tail.isNotEmpty()) out.add(tail)
        }
        return out
    }
}

/**
 * 身份判定（**数据层**，不在 UI 里做）。
 *
 * ## 为什么必须放这里
 *
 * 需求明确要求"不能只根据 UI 判断权限"。身份判定是所有权限判断的**输入**，
 * 放在 UI 里意味着每个页面都要重算一遍，且必然出现两处不一致。
 *
 * ## 已知限制（如实说明）
 *
 * **房管身份无法可靠判定**：
 * - `DANMU_MSG.info[2][2]` 被社区广泛认为是 `isAdmin` 标记，
 *   但本项目**没有真实房管账号可验证**（未登录状态拿不到）
 * - 权威的"房管名单"接口**实测未找到**（试了 10 个路径全部 NOT FOUND）
 *
 * 所以这里把 `isAdmin` 作为**尽力而为的信号**：拿不到就退化成普通用户，
 * **绝不**因为"猜不出"就把所有人当房管。这个方向是安全的：
 * 误判成普通用户只是少显示一个徽章；误判成房管会显示不该有的管理入口。
 */
object LiveRoleResolver {

    fun role(uid: Long, anchorUid: Long, isAdmin: Boolean): LiveRole = when {
        uid > 0L && anchorUid > 0L && uid == anchorUid -> LiveRole.ANCHOR
        isAdmin -> LiveRole.ADMIN
        else -> LiveRole.NORMAL
    }
}
