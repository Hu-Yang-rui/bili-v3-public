package com.example.biliv3

import com.example.biliv3.data.live.DanmakuHost
import com.example.biliv3.data.live.LiveDanmakuProtocol
import com.example.biliv3.data.live.LiveProtobuf
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 直播间弹幕**协议层**测试。
 *
 * ## 为什么这组测试重要
 *
 * 这一层的错误**全部表现为"连上了但什么都没有"** ——
 * 不崩、不报错、日志里只有"认证通过"，然后聊天区一直空着。
 * 而"聊天区空着"与"直播间没人说话"长得**一模一样**，
 * 靠人工点测几乎不可能区分。
 *
 * 三个真实的坑（都在下面钉死）：
 * 1. `protover=2` 的 body 解压后**仍是一批帧**，不是一条 JSON
 * 2. 帧头的 `total` 是**含头**的总长，不是 body 长度
 * 3. `INTERACT_WORD_V2`（进入直播间）的数据在 **base64 protobuf** 里，
 *    不在 JSON 字段里
 */
class LiveDanmakuProtocolTest {

    // ---------------- 帧解析 ----------------

    /** 一个明文帧能被原样解出。 */
    @Test
    fun `解析单个明文帧`() {
        val body = """{"cmd":"DANMU_MSG"}""".toByteArray()
        val frame = LiveDanmakuProtocol.buildFrame(
            operation = LiveDanmakuProtocol.OP_MESSAGE,
            body = body,
            protover = LiveDanmakuProtocol.PROTO_PLAIN,
        )

        val frames = LiveDanmakuProtocol.parseFrames(frame)
        assertThat(frames).hasSize(1)
        assertThat(frames[0].operation).isEqualTo(LiveDanmakuProtocol.OP_MESSAGE)
        assertThat(frames[0].protover).isEqualTo(LiveDanmakuProtocol.PROTO_PLAIN)
        assertThat(String(frames[0].body)).isEqualTo("""{"cmd":"DANMU_MSG"}""")
    }

    /**
     * ⚠️ 一帧里可以塞多条（服务端批量下发）。
     *
     * 只解第一条的实现会丢掉后面所有消息 —— 表现为"偶尔少几条弹幕"。
     */
    @Test
    fun `解析同一批里的多个帧`() {
        val a = LiveDanmakuProtocol.buildFrame(
            operation = LiveDanmakuProtocol.OP_MESSAGE,
            body = """{"cmd":"A"}""".toByteArray(),
        )
        val b = LiveDanmakuProtocol.buildFrame(
            operation = LiveDanmakuProtocol.OP_MESSAGE,
            body = """{"cmd":"B"}""".toByteArray(),
        )

        val frames = LiveDanmakuProtocol.parseFrames(a + b)
        assertThat(frames).hasSize(2)
        assertThat(String(frames[0].body)).isEqualTo("""{"cmd":"A"}""")
        assertThat(String(frames[1].body)).isEqualTo("""{"cmd":"B"}""")
    }

    /**
     * ⚠️ `total` 是**含 16 字节头**的总长。
     *
     * 若误当成 body 长度，第一帧就会把第二帧的头一起吃进去，
     * 后面全部错位 → 表现为"只有第一条消息能解析"。
     */
    @Test
    fun `帧头记录的是含头的总长度`() {
        val body = ByteArray(10)
        val frame = LiveDanmakuProtocol.buildFrame(LiveDanmakuProtocol.OP_MESSAGE, body)
        // 总长 = 16 头 + 10 body
        assertThat(frame.size).isEqualTo(26)
        // 前 4 字节（大端）= 26
        assertThat(frame[3].toInt() and 0xFF).isEqualTo(26)
    }

    /** 半包（长度不够）不抛异常，返回已解出的部分。 */
    @Test
    fun `半包不抛异常`() {
        val frame = LiveDanmakuProtocol.buildFrame(
            LiveDanmakuProtocol.OP_MESSAGE,
            """{"cmd":"X"}""".toByteArray(),
        )
        // 砍掉最后 5 字节 → 这一帧不完整
        val truncated = frame.copyOfRange(0, frame.size - 5)
        assertThat(LiveDanmakuProtocol.parseFrames(truncated)).isEmpty()
    }

    /** 脏数据（长度 < 头长）不抛异常。 */
    @Test
    fun `脏数据不抛异常`() {
        assertThat(LiveDanmakuProtocol.parseFrames(ByteArray(0))).isEmpty()
        assertThat(LiveDanmakuProtocol.parseFrames(ByteArray(8))).isEmpty()
        // total 声明为 3（< 16）→ 应当停止而不是越界
        val bad = ByteArray(32)
        bad[3] = 3
        assertThat(LiveDanmakuProtocol.parseFrames(bad)).isEmpty()
    }

    /** 心跳回应的人气值是 4 字节大端整数。 */
    @Test
    fun `解析心跳回应的人气值`() {
        // 0x0001E240 = 123456
        val body = byteArrayOf(0x00, 0x01, 0xE2.toByte(), 0x40)
        assertThat(LiveDanmakuProtocol.popularity(body)).isEqualTo(123456)
    }

    /** 人气值 body 不足 4 字节时返回 0（不越界）。 */
    @Test
    fun `人气值字节不足时返回零`() {
        assertThat(LiveDanmakuProtocol.popularity(ByteArray(0))).isEqualTo(0)
        assertThat(LiveDanmakuProtocol.popularity(byteArrayOf(1, 2))).isEqualTo(0)
    }

    // ---------------- 认证帧 ----------------

    /**
     * 认证 body 必须包含实测要求的全部字段。
     *
     * 缺 `key` / `roomid` / `protover` 任一项服务端都会拒绝，
     * 而拒绝的表现就是"连上了但收不到消息"。
     */
    @Test
    fun `认证 body 含必需字段`() {
        val body = LiveDanmakuProtocol.authBody(
            roomId = 545068,
            token = "abc123",
            buvid = "BUV",
        )
        assertThat(body).contains("\"roomid\":545068")
        assertThat(body).contains("\"key\":\"abc123\"")
        // protover=2 = 要 zlib 压缩的消息
        assertThat(body).contains("\"protover\":2")
        assertThat(body).contains("\"platform\":\"web\"")
        assertThat(body).contains("\"type\":2")
        assertThat(body).contains("\"buvid\":\"BUV\"")
    }

    /** token 里的引号/反斜杠必须转义，否则 body 不是合法 JSON。 */
    @Test
    fun `认证 body 转义特殊字符`() {
        val body = LiveDanmakuProtocol.authBody(1L, "a\"b\\c", "x")
        assertThat(body).contains("\\\"")
        assertThat(body).contains("\\\\")
    }

    /** 心跳 body 是官方网页端的原样写法。 */
    @Test
    fun `心跳 body`() {
        assertThat(LiveDanmakuProtocol.heartbeatBody()).isEqualTo("[object Object]")
    }

    // ---------------- 接入点 ----------------

    @Test
    fun `接入点拼成 wss 地址`() {
        val h = DanmakuHost("zj-cn-live-comet.chat.bilibili.com", 2245)
        assertThat(h.wssUrl())
            .isEqualTo("wss://zj-cn-live-comet.chat.bilibili.com:2245/sub")
    }

    // ---------------- INTERACT_WORD_V2 的 protobuf ----------------

    /**
     * ⚠️ 这是**最容易漏的一条**：进入直播间的数据在 base64 protobuf 里。
     *
     * 只读 `data.uid` / `data.uname` 会全部拿到空 ——
     * 表现为"弹幕有用户名，但进入消息全是空白"。
     */
    @Test
    fun `解析进入消息的 protobuf`() {
        // 手工构造与实测同构的 protobuf：
        //   field1(uid)=263149397
        //   field2(uname)="C罗詹姆斯RNG"
        //   field5(msg_type)=1
        val uid = 263149397L
        val name = "C罗詹姆斯RNG"
        val bytes = buildInteractPb(uid, name, msgType = 1)

        val decoded = LiveProtobuf.decodeInteractWordBytes(bytes)
        assertThat(decoded.uid).isEqualTo(uid)
        assertThat(decoded.uname).isEqualTo(name)
        assertThat(decoded.msgType).isEqualTo(1)
    }

    /** msg_type 区分「进入 / 关注 / 分享」。 */
    @Test
    fun `解析 msg_type`() {
        assertThat(LiveProtobuf.decodeInteractWordBytes(buildInteractPb(1L, "a", 1)).msgType)
            .isEqualTo(1)
        assertThat(LiveProtobuf.decodeInteractWordBytes(buildInteractPb(1L, "a", 2)).msgType)
            .isEqualTo(2)
        assertThat(LiveProtobuf.decodeInteractWordBytes(buildInteractPb(1L, "a", 3)).msgType)
            .isEqualTo(3)
    }

    /**
     * 头像只在 **field 22 的嵌套结构**里。
     *
     * 不解析它 → 进入消息有名字没头像，UI 上是一排灰色占位圆。
     */
    @Test
    fun `从嵌套结构里取出头像`() {
        val face = "https://i2.hdslb.com/bfs/face/abc.jpg"
        val bytes = buildInteractPbWithFace(
            uid = 349759974L,
            name = "春风得意",
            face = face,
        )
        val decoded = LiveProtobuf.decodeInteractWordBytes(bytes)
        assertThat(decoded.uid).isEqualTo(349759974L)
        assertThat(decoded.uname).isEqualTo("春风得意")
        assertThat(decoded.face).isEqualTo(face)
    }

    /** 空 / 损坏输入返回 EMPTY，不抛异常。 */
    @Test
    fun `protobuf 容错`() {
        assertThat(LiveProtobuf.decodeInteractWordBytes(ByteArray(0)).uid).isEqualTo(0L)
        assertThat(LiveProtobuf.decodeInteractWord("").uid).isEqualTo(0L)
        assertThat(LiveProtobuf.decodeInteractWord("!!!not base64!!!").uid).isEqualTo(0L)
        // 截断的字节流不该抛异常
        val full = buildInteractPb(123L, "名字", 1)
        assertThat(
            runCatching {
                LiveProtobuf.decodeInteractWordBytes(full.copyOfRange(0, full.size / 2))
            }.isSuccess,
        ).isTrue()
    }

    /** varint 超过 10 字节的脏数据不该让解析死循环。 */
    @Test
    fun `超长 varint 不死循环`() {
        // 12 个 0xFF（每个都带继续位）
        val bad = ByteArray(12) { 0xFF.toByte() }
        val done = runCatching { LiveProtobuf.decodeInteractWordBytes(bad) }
        assertThat(done.isSuccess).isTrue()
    }

    // ---------------- 构造测试用 protobuf ----------------

    /** 按实测结构拼一个 INTERACT_WORD_V2 的 pb（顶层字段）。 */
    private fun buildInteractPb(uid: Long, uname: String, msgType: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // field 1 (uid), wire 0
        out.write(varint((1L shl 3) or 0L))
        out.write(varint(uid))
        // field 2 (uname), wire 2
        out.write(varint((2L shl 3) or 2L))
        val nameBytes = uname.toByteArray(Charsets.UTF_8)
        out.write(varint(nameBytes.size.toLong()))
        out.write(nameBytes)
        // field 5 (msg_type), wire 0
        out.write(varint((5L shl 3) or 0L))
        out.write(varint(msgType.toLong()))
        return out.toByteArray()
    }

    /** 带 field 22 嵌套（含 face）的 pb。 */
    private fun buildInteractPbWithFace(uid: Long, name: String, face: String): ByteArray {
        // 内层：field1=uname, field2=face
        val inner = java.io.ByteArrayOutputStream()
        inner.write(varint((1L shl 3) or 2L))
        val nb = name.toByteArray(Charsets.UTF_8)
        inner.write(varint(nb.size.toLong()))
        inner.write(nb)
        inner.write(varint((2L shl 3) or 2L))
        val fb = face.toByteArray(Charsets.UTF_8)
        inner.write(varint(fb.size.toLong()))
        inner.write(fb)
        val innerBytes = inner.toByteArray()

        // 中层：field1=uid, field2=inner
        val mid = java.io.ByteArrayOutputStream()
        mid.write(varint((1L shl 3) or 0L))
        mid.write(varint(uid))
        mid.write(varint((2L shl 3) or 2L))
        mid.write(varint(innerBytes.size.toLong()))
        mid.write(innerBytes)
        val midBytes = mid.toByteArray()

        // 顶层：field22=mid
        val out = java.io.ByteArrayOutputStream()
        out.write(varint((22L shl 3) or 2L))
        out.write(varint(midBytes.size.toLong()))
        out.write(midBytes)
        return out.toByteArray()
    }

    /** protobuf varint 编码。 */
    private fun varint(v: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.write(x.toInt())
                break
            }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        return out.toByteArray()
    }
}
