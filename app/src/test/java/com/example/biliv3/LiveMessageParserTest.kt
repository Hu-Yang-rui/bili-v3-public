package com.example.biliv3

import com.example.biliv3.data.live.LiveMessage
import com.example.biliv3.data.live.LiveMessageParser
import com.example.biliv3.data.live.LivePermissions
import com.example.biliv3.data.live.LiveRole
import com.example.biliv3.data.live.LiveRoleResolver
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 直播间消息解析 + 权限模型测试。
 *
 * ## 为什么这组测试重要
 *
 * ### 1. 解析错误是"静默"的
 * 字段读错不会抛异常，只会让弹幕**没有用户名**、进场消息**没有头像**。
 * 用户看到的是"这个功能做得糙"，而排查时没有任何线索。
 *
 * ### 2. 权限判断错误有**安全后果**
 * 把普通用户误判成房管 → 显示不该有的管理入口；
 * 需求明确要求"不能只根据 UI 判断权限"，所以判定逻辑必须被钉死。
 *
 * 下面的 JSON 都是**实测原样**（2026-10-06，房间 545068），
 * 只裁掉了与断言无关的部分。
 */
class LiveMessageParserTest {

    private val anchorUid = 8739477L

    // ---------------- DANMU_MSG ----------------

    /**
     * 实测原样的弹幕（带粉丝牌 + 舰长）。
     *
     * 这是最常见的形态：`info[2]` 是元信息数组，`info[3]` 是粉丝牌。
     */
    @Test
    fun `解析实测弹幕`() {
        val json = """
        {"cmd":"DANMU_MSG","info":[
          [0,1,25,16777215,1791268949244,1161909413,0,"9f5ba64f",0,0,0,"",0,"{}","{}",
           {"extra":"{}","user":{"uid":32282280,"base":{"name":"k4M1d0N9","face":"https://i0.hdslb.com/bfs/face/x.jpg"}}}],
          "面包飞不过潲水",
          [32282280,"k4M1d0N9",0,0,0,10000,1,"#00D1F1"],
          [31,"德云色","老实憨厚的笑笑",545068,2951253,"",0],
          0,0,0,3
        ]}
        """.trimIndent()

        val m = LiveMessageParser.parse(json, anchorUid)
        assertThat(m).isNotNull()
        assertThat(m!!.kind).isEqualTo(LiveMessage.Kind.DANMAKU)
        assertThat(m.text).isEqualTo("面包飞不过潲水")
        assertThat(m.uid).isEqualTo(32282280L)
        assertThat(m.uname).isEqualTo("k4M1d0N9")
        assertThat(m.face).isEqualTo("https://i0.hdslb.com/bfs/face/x.jpg")
        // 粉丝牌
        assertThat(m.medalLevel).isEqualTo(31)
        assertThat(m.medalName).isEqualTo("德云色")
        // 大航海（info[7] = 3 = 舰长）
        assertThat(m.guardLevel).isEqualTo(3)
        // 时间戳（info[0][4]）
        assertThat(m.tsMs).isEqualTo(1791268949244L)
    }

    /** `cmd` 常带后缀（`DANMU_MSG:4:0:2:2:2:0`），必须按前缀识别。 */
    @Test
    fun `cmd 带后缀也能识别`() {
        val json = """{"cmd":"DANMU_MSG:4:0:2:2:2:0","info":[[0,1,25,0,0,0,0,"",0,0,0,"",0,"{}","{}",
            {"user":{"uid":1,"base":{"name":"n","face":"f"}}}],"你好",[1,"n",0,0,0,10000,1,""],[],
            0,0,0,0]}"""
        val m = LiveMessageParser.parse(json, anchorUid)
        assertThat(m?.text).isEqualTo("你好")
    }

    /** 没有粉丝牌时 `info[3]` 是空数组 —— 不能崩，也不能显示"0 级牌子"。 */
    @Test
    fun `无粉丝牌时等级为零`() {
        val json = """{"cmd":"DANMU_MSG","info":[[0,1,25,0,0,0,0,"",0,0,0,"",0,"{}","{}",
            {"user":{"uid":1,"base":{"name":"n","face":"f"}}}],"hi",[1,"n",0,0,0,10000,1,""],[],
            0,0,0,0]}"""
        val m = LiveMessageParser.parse(json, anchorUid)!!
        assertThat(m.medalLevel).isEqualTo(0)
        assertThat(m.medalName).isEmpty()
        assertThat(m.guardLevel).isEqualTo(0)
    }

    /** 弹幕正文里的引号/emoji 要原样保留。 */
    @Test
    fun `弹幕正文保留特殊字符`() {
        val json = """{"cmd":"DANMU_MSG","info":[[0,1,25,0,0,0,0,"",0,0,0,"",0,"{}","{}",
            {"user":{"uid":1,"base":{"name":"n","face":"f"}}}],"主播能说一下机制吗[哇]",[1,"n",0,0,0,10000,1,""],[],
            0,0,0,0]}"""
        val m = LiveMessageParser.parse(json, anchorUid)!!
        assertThat(m.text).isEqualTo("主播能说一下机制吗[哇]")
    }

    /** 空正文的弹幕直接丢弃（不产生一条空行）。 */
    @Test
    fun `空正文被丢弃`() {
        val json = """{"cmd":"DANMU_MSG","info":[[0,1,25,0,0,0,0,"",0,0,0,"",0,"{}","{}",
            {"user":{"uid":1,"base":{"name":"n","face":"f"}}}],"",[1,"n",0,0,0,10000,1,""],[],
            0,0,0,0]}"""
        assertThat(LiveMessageParser.parse(json, anchorUid)).isNull()
    }

    // ---------------- 身份判定 ----------------

    /** 主播的弹幕 → ANCHOR。 */
    @Test
    fun `主播弹幕被识别为主播`() {
        val json = """{"cmd":"DANMU_MSG","info":[[0,1,25,0,0,0,0,"",0,0,0,"",0,"{}","{}",
            {"user":{"uid":$anchorUid,"base":{"name":"主播","face":"f"}}}],"大家好",
            [$anchorUid,"主播",0,0,0,10000,1,""],[],
            0,0,0,0]}"""
        val m = LiveMessageParser.parse(json, anchorUid)!!
        assertThat(m.role).isEqualTo(LiveRole.ANCHOR)
    }

    /** `info[2][2] == 1` → 房管（**尽力而为的信号**，见 LiveRoleResolver 说明）。 */
    @Test
    fun `admin 标记被识别为房管`() {
        val json = """{"cmd":"DANMU_MSG","info":[[0,1,25,0,0,0,0,"",0,0,0,"",0,"{}","{}",
            {"user":{"uid":999,"base":{"name":"房管","face":"f"}}}],"注意秩序",
            [999,"房管",1,0,0,10000,1,""],[],
            0,0,0,0]}"""
        val m = LiveMessageParser.parse(json, anchorUid)!!
        assertThat(m.role).isEqualTo(LiveRole.ADMIN)
    }

    /** 主播优先于房管标记（主播 uid 命中时就是主播）。 */
    @Test
    fun `主播优先于房管标记`() {
        val r = LiveRoleResolver.role(uid = anchorUid, anchorUid = anchorUid, isAdmin = true)
        assertThat(r).isEqualTo(LiveRole.ANCHOR)
    }

    /**
     * ⚠️ 主播 uid 未知（0）时**不能**把 uid=0 的人当成主播。
     *
     * 否则一条解析失败的消息（uid 读成 0）会显示成"主播"。
     */
    @Test
    fun `主播 uid 未知时不误判`() {
        assertThat(LiveRoleResolver.role(uid = 0L, anchorUid = 0L, isAdmin = false))
            .isEqualTo(LiveRole.NORMAL)
        assertThat(LiveRoleResolver.role(uid = 0L, anchorUid = anchorUid, isAdmin = false))
            .isEqualTo(LiveRole.NORMAL)
        // 反向：anchorUid 未知但 uid 有值 → 也不能判定为主播
        assertThat(LiveRoleResolver.role(uid = 123L, anchorUid = 0L, isAdmin = false))
            .isEqualTo(LiveRole.NORMAL)
    }

    // ---------------- 进入直播间（protobuf）----------------

    /**
     * ⚠️ 进入消息的数据在 `data.pb`（base64 protobuf）里，不在 JSON 字段里。
     *
     * 这是"进入消息没有用户名"的根因。
     */
    @Test
    fun `解析进入直播间消息`() {
        // uid=263149397, uname="C罗詹姆斯RNG", msg_type=1
        val pb = buildInteractPb(263149397L, "C罗詹姆斯RNG", 1)
        val b64 = base64(pb)
        val json = """{"cmd":"INTERACT_WORD_V2","data":{"dmscore":20,"pb":"$b64"}}"""

        val m = LiveMessageParser.parse(json, anchorUid)
        assertThat(m).isNotNull()
        assertThat(m!!.kind).isEqualTo(LiveMessage.Kind.ENTER)
        assertThat(m.uid).isEqualTo(263149397L)
        assertThat(m.uname).isEqualTo("C罗詹姆斯RNG")
    }

    /** `msg_type=2` 是关注，不是进入。 */
    @Test
    fun `关注消息被区分`() {
        val pb = buildInteractPb(1L, "某人", 2)
        val json = """{"cmd":"INTERACT_WORD_V2","data":{"pb":"${base64(pb)}"}}"""
        assertThat(LiveMessageParser.parse(json, anchorUid)!!.kind)
            .isEqualTo(LiveMessage.Kind.FOLLOW)
    }

    /** 老版 `INTERACT_WORD` 是纯 JSON —— 要能兜底。 */
    @Test
    fun `兼容纯 JSON 的 INTERACT_WORD`() {
        val json = """{"cmd":"INTERACT_WORD","data":{"uid":555,"uname":"老格式","msg_type":1}}"""
        val m = LiveMessageParser.parse(json, anchorUid)
        assertThat(m).isNotNull()
        assertThat(m!!.uid).isEqualTo(555L)
        assertThat(m.uname).isEqualTo("老格式")
        assertThat(m.kind).isEqualTo(LiveMessage.Kind.ENTER)
    }

    // ---------------- 进场特效 ----------------

    /**
     * ⚠️ `copy_writing` 含服务端模板标记 `<%...%>`。
     *
     * 实测原样：`<%春风得意_马蹄疾-_-%> 来了`
     * 不清理会在聊天区显示成一堆尖括号百分号。
     */
    @Test
    fun `清理进场特效的模板标记`() {
        val json = """{"cmd":"ENTRY_EFFECT","data":{"uid":351663272,
            "face":"https://i2.hdslb.com/bfs/face/y.jpg",
            "copy_writing":"<%春风得意_马蹄疾-_-%> 来了",
            "uinfo":{"base":{"name":"春风得意_马蹄疾-_-"}}}}"""
        val m = LiveMessageParser.parse(json, anchorUid)!!
        assertThat(m.kind).isEqualTo(LiveMessage.Kind.ENTRY_EFFECT)
        assertThat(m.uid).isEqualTo(351663272L)
        assertThat(m.text).isEqualTo("来了")
        assertThat(m.text).doesNotContain("<%")
        assertThat(m.uname).isEqualTo("春风得意_马蹄疾-_-")
    }

    @Test
    fun `模板清理的边界`() {
        assertThat(LiveMessageParser.cleanEntryText("", "某人")).isEqualTo("某人 来了")
        assertThat(LiveMessageParser.cleanEntryText("<%X%>", "某人")).isEqualTo("来了")
        assertThat(LiveMessageParser.cleanEntryText("来了", "")).isEqualTo("来了")
        assertThat(LiveMessageParser.cleanEntryText("<%a%> 进入了直播间", "u"))
            .isEqualTo("进入了直播间")
    }

    // ---------------- 点赞 ----------------

    @Test
    fun `解析点赞消息`() {
        val json = """{"cmd":"LIKE_INFO_V3_CLICK","data":{"uid":1353806921,"uname":"没弄的人",
            "like_text":"为主播点赞了","uinfo":{"base":{"face":"https://i0.hdslb.com/bfs/face/z.jpg"}}}}"""
        val m = LiveMessageParser.parse(json, anchorUid)!!
        assertThat(m.kind).isEqualTo(LiveMessage.Kind.LIKE)
        assertThat(m.uname).isEqualTo("没弄的人")
        assertThat(m.text).isEqualTo("为主播点赞了")
        assertThat(m.face).isEqualTo("https://i0.hdslb.com/bfs/face/z.jpg")
    }

    // ---------------- 无关 / 损坏消息 ----------------

    /**
     * 与聊天无关的消息（在线人数、看过人数…）返回 null。
     *
     * 实测 20 秒 72 条里，`ONLINE_RANK_COUNT` / `WATCHED_CHANGE` 这类
     * 占了近一半 —— 全部渲染进聊天区会把真正的弹幕淹没。
     */
    @Test
    fun `无关消息被忽略`() {
        assertThat(
            LiveMessageParser.parse("""{"cmd":"ONLINE_RANK_COUNT","data":{"count":7894}}"""),
        ).isNull()
        assertThat(
            LiveMessageParser.parse("""{"cmd":"WATCHED_CHANGE","data":{"num":63141}}"""),
        ).isNull()
        assertThat(
            LiveMessageParser.parse("""{"cmd":"ONLINE_RANK_V3","data":{}}"""),
        ).isNull()
    }

    /** 损坏 JSON / 缺字段不抛异常。 */
    @Test
    fun `损坏消息不抛异常`() {
        assertThat(LiveMessageParser.parse("not json at all")).isNull()
        assertThat(LiveMessageParser.parse("{}")).isNull()
        assertThat(LiveMessageParser.parse("""{"cmd":"DANMU_MSG"}""")).isNull()
        assertThat(LiveMessageParser.parse("""{"cmd":"DANMU_MSG","info":[]}""")).isNull()
    }

    /** 稳定 key 必须区分"同一人连发两条一样的弹幕"。 */
    @Test
    fun `稳定 key 区分同内容消息`() {
        val json = """{"cmd":"DANMU_MSG","info":[[0,1,25,0,1791268949244,0,0,"",0,0,0,"",0,"{}","{}",
            {"user":{"uid":7,"base":{"name":"n","face":"f"}}}],"重复",[7,"n",0,0,0,10000,1,""],[],
            0,0,0,0]}"""
        val a = LiveMessageParser.parse(json, anchorUid)!!
        val b = LiveMessageParser.parse(json, anchorUid)!!
        // 内容相同但序号不同 → key 不同（否则 LazyColumn 会吞掉一条）
        assertThat(a.stableKey(1)).isNotEqualTo(b.stableKey(2))
        // 同一个序号 → key 相同（重组时稳定）
        assertThat(a.stableKey(1)).isEqualTo(b.stableKey(1))
    }

    private fun buildInteractPb(uid: Long, uname: String, msgType: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(varint((1L shl 3) or 0L)); out.write(varint(uid))
        out.write(varint((2L shl 3) or 2L))
        val nb = uname.toByteArray(Charsets.UTF_8)
        out.write(varint(nb.size.toLong())); out.write(nb)
        out.write(varint((5L shl 3) or 0L)); out.write(varint(msgType.toLong()))
        return out.toByteArray()
    }

    private fun varint(v: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); break }
            out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
        return out.toByteArray()
    }

    /**
     * 纯 Kotlin base64 编码。
     *
     * ⚠️ **不能用 `android.util.Base64`** —— 它在本地 JVM 单测里是 stub，
     * 调用即抛 `Method encodeToString in android.util.Base64 not mocked`。
     * 这与生产侧改用纯 Kotlin 解码器是同一个理由（见 `LiveProtobuf`）。
     */
    private fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(alphabet[b0 shr 2])
            sb.append(alphabet[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) {
                sb.append(alphabet[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            } else {
                sb.append('=')
            }
            if (b2 >= 0) sb.append(alphabet[b2 and 0x3F]) else sb.append('=')
            i += 3
        }
        return sb.toString()
    }
}

/**
 * 权限模型测试。
 *
 * ## 为什么这组测试重要
 *
 * 需求原文：**"不能通过隐藏按钮来代替真正的权限控制"**。
 * 也就是说权限判定本身必须是**数据层的、可测的**，而不是"UI 里 if 一下"。
 *
 * 这里的每一条断言都对应一个"如果错了会怎样"：
 * - 普通用户看到房管菜单 → 点了必然失败，用户以为功能坏了
 * - 房管看到主播菜单 → 越权入口（需求明确禁止）
 * - 能对自己/主播操作 → 服务端必拒，白费一次请求
 */
class LivePermissionsTest {

    private val selfMid = 1000L
    private val anchor = 8739477L
    private val other = 2000L

    // ---------------- 主播 ----------------

    /** 自己是主播 → 拥有房管能力 + 房管管理。 */
    @Test
    fun `主播拥有全部权限`() {
        val p = LivePermissions(
            loggedIn = true,
            selfMid = anchor,
            anchorUid = anchor,
            selfRole = LiveRole.ANCHOR,
        )
        assertThat(p.isAnchor).isTrue()
        assertThat(p.isAdmin).isTrue()
        assertThat(p.actions).contains(LivePermissions.Action.MUTE)
        assertThat(p.actions).contains(LivePermissions.Action.KICK)
        assertThat(p.actions).contains(LivePermissions.Action.BLOCK)
        assertThat(p.actions).contains(LivePermissions.Action.MANAGE_ADMIN)
    }

    // ---------------- 房管 ----------------

    /**
     * ⚠️ 需求明确要求：**不能让普通房管拥有主播才拥有的操作**。
     *
     * 所以房管**没有** [LivePermissions.Action.MANAGE_ADMIN]。
     */
    @Test
    fun `房管没有房管管理权限`() {
        val p = LivePermissions(
            loggedIn = true,
            selfMid = selfMid,
            anchorUid = anchor,
            selfRole = LiveRole.ADMIN,
        )
        assertThat(p.isAnchor).isFalse()
        assertThat(p.isAdmin).isTrue()
        // 有禁言/踢人/黑名单
        assertThat(p.actions).contains(LivePermissions.Action.MUTE)
        assertThat(p.actions).contains(LivePermissions.Action.UNMUTE)
        assertThat(p.actions).contains(LivePermissions.Action.KICK)
        assertThat(p.actions).contains(LivePermissions.Action.BLOCK)
        assertThat(p.actions).contains(LivePermissions.Action.UNBLOCK)
        // 但**没有**房管管理
        assertThat(p.actions).doesNotContain(LivePermissions.Action.MANAGE_ADMIN)
    }

    // ---------------- 普通用户 ----------------

    /** 普通用户没有任何管理权限。 */
    @Test
    fun `普通用户没有管理权限`() {
        val p = LivePermissions(
            loggedIn = true,
            selfMid = selfMid,
            anchorUid = anchor,
            selfRole = LiveRole.NORMAL,
        )
        assertThat(p.isAdmin).isFalse()
        assertThat(p.actions).isEmpty()
    }

    // ---------------- 未登录 ----------------

    /**
     * ⚠️ 未登录 → **一律没有权限**，即使 `selfRole` 被误设成 ADMIN。
     *
     * 这是最重要的一条防线：任何"没登录却能管理"的路径都必须堵死。
     */
    @Test
    fun `未登录没有任何权限`() {
        val p = LivePermissions(
            loggedIn = false,
            selfMid = selfMid,
            anchorUid = anchor,
            selfRole = LiveRole.ADMIN,
        )
        assertThat(p.isAdmin).isFalse()
        assertThat(p.actions).isEmpty()
    }

    /** 默认值是最小权限。 */
    @Test
    fun `默认值最小权限`() {
        assertThat(LivePermissions.NONE.actions).isEmpty()
        assertThat(LivePermissions.NONE.isAdmin).isFalse()
        assertThat(LivePermissions.NONE.isAnchor).isFalse()
    }

    // ---------------- 操作目标的限制 ----------------

    /** 可以对别人执行有权限的操作。 */
    @Test
    fun `可以对他人执行有权限的操作`() {
        val p = LivePermissions(true, selfMid, anchor, LiveRole.ADMIN)
        assertThat(p.canActOn(other, LivePermissions.Action.MUTE)).isTrue()
        assertThat(p.canActOn(other, LivePermissions.Action.KICK)).isTrue()
    }

    /** 不能对自己操作（服务端也会拒，客户端先拦掉）。 */
    @Test
    fun `不能对自己操作`() {
        val p = LivePermissions(true, selfMid, anchor, LiveRole.ADMIN)
        assertThat(p.canActOn(selfMid, LivePermissions.Action.MUTE)).isFalse()
        assertThat(p.canActOn(selfMid, LivePermissions.Action.KICK)).isFalse()
    }

    /**
     * 不能对主播操作。
     *
     * 房管禁言主播是无意义的，服务端必拒 —— 客户端不该给出这个入口。
     */
    @Test
    fun `不能对主播操作`() {
        val p = LivePermissions(true, selfMid, anchor, LiveRole.ADMIN)
        assertThat(p.canActOn(anchor, LivePermissions.Action.MUTE)).isFalse()
        assertThat(p.canActOn(anchor, LivePermissions.Action.KICK)).isFalse()
    }

    /** 无效 uid（0 / 负数）不能作为操作目标。 */
    @Test
    fun `无效目标被拒绝`() {
        val p = LivePermissions(true, selfMid, anchor, LiveRole.ADMIN)
        assertThat(p.canActOn(0L, LivePermissions.Action.MUTE)).isFalse()
        assertThat(p.canActOn(-1L, LivePermissions.Action.MUTE)).isFalse()
    }

    /**
     * 没有该操作权限时，即使目标合法也拒绝。
     *
     * 这条把"UI 隐藏"与"数据层拦截"区分开：
     * 即使 UI 误显示了按钮，数据层也必须拦住。
     */
    @Test
    fun `无权限时对合法目标也拒绝`() {
        val normal = LivePermissions(true, selfMid, anchor, LiveRole.NORMAL)
        assertThat(normal.canActOn(other, LivePermissions.Action.MUTE)).isFalse()
        assertThat(normal.canActOn(other, LivePermissions.Action.KICK)).isFalse()

        // 房管没有 MANAGE_ADMIN
        val admin = LivePermissions(true, selfMid, anchor, LiveRole.ADMIN)
        assertThat(admin.canActOn(other, LivePermissions.Action.MANAGE_ADMIN)).isFalse()
    }

    /** 主播 uid 未知（0）时，不能因为"目标 uid 也是 0"就误判成可操作。 */
    @Test
    fun `主播 uid 未知时的行为`() {
        val p = LivePermissions(true, selfMid, anchorUid = 0L, selfRole = LiveRole.ADMIN)
        // 目标 0 依旧被 uid<=0 挡掉
        assertThat(p.canActOn(0L, LivePermissions.Action.MUTE)).isFalse()
        // 正常目标仍可操作
        assertThat(p.canActOn(other, LivePermissions.Action.MUTE)).isTrue()
    }
}
