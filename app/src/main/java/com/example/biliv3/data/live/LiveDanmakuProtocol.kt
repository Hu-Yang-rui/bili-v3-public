package com.example.biliv3.data.live

/**
 * 直播间弹幕**协议**（纯 Kotlin，可单测）。
 *
 * ---
 *
 * # 协议（实测确认，非推测）
 *
 * ## 帧结构（大端）
 *
 * ```
 * 偏移  长度  含义
 *  0     4    包总长（含 16 字节头）
 *  4     2    头长（恒 16）
 *  6     2    protover
 *  8     4    operation
 * 12     4    sequence
 * 16    ..    body
 * ```
 *
 * ## protover / operation
 *
 * | protover | body |
 * |---|---|
 * | 0 | 明文 JSON |
 * | 1 | 明文 JSON（认证/心跳的**发送**用 1） |
 * | 2 | **zlib 压缩**，解压后**又是一批完整的帧** |
 * | 3 | brotli（本项目**不支持**，见 [UNSUPPORTED_PROTOVER]） |
 *
 * | operation | 方向 | 含义 |
 * |---|---|---|
 * | 2 | 客户端→服务端 | 心跳（30s 一次） |
 * | 3 | 服务端→客户端 | 心跳回应，body 是 4 字节人气值 |
 * | 7 | 客户端→服务端 | 认证（发送弹幕也是 7） |
 * | 8 | 服务端→客户端 | 认证回应 |
 * | 5 | 服务端→客户端 | **消息**（弹幕/进入/礼物…） |
 *
 * ## ⚠️ 实测踩到的两个点
 *
 * 1. **protover=2 解压后仍是"一批帧"**，必须**递归解析**，
 *    不能当成一条 JSON。当成一条会得到 `JSON.parse` 失败 →
 *    "连上了但一条消息都没有"。
 * 2. 房间没人说话时**本来就收不到消息**（实测房间 6 / 21452505 在 20 秒内
 *    0 条，而 545068 收到 72 条）。**"0 条"不等于"连不上"** ——
 *    判断连接状态必须看 `op=8` 的认证回应，不能看消息条数。
 */
object LiveDanmakuProtocol {

    /** 固定头长度。 */
    const val HEADER_LEN = 16

    // ---- operation ----
    const val OP_HEARTBEAT = 2
    const val OP_HEARTBEAT_REPLY = 3
    const val OP_MESSAGE = 5
    const val OP_AUTH = 7
    const val OP_AUTH_REPLY = 8

    // ---- protover ----
    const val PROTO_PLAIN = 0
    const val PROTO_AUTH = 1
    const val PROTO_ZLIB = 2
    const val PROTO_BROTLI = 3

    /** 心跳间隔。服务端约 30 秒无心跳会断开。 */
    const val HEARTBEAT_INTERVAL_MS = 30_000L

    /**
     * 一个解析出来的帧。
     *
     * @param protover 见上表
     * @param operation 见上表
     * @param body 原始 body（**未解压**）
     */
    data class Frame(
        val protover: Int,
        val operation: Int,
        val body: ByteArray,
    ) {
        // ByteArray 是引用类型，data class 自动生成的 equals 会比较引用，
        // 这里显式实现内容比较，避免测试与集合操作出现反直觉结果。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return protover == other.protover &&
                operation == other.operation &&
                body.contentEquals(other.body)
        }

        override fun hashCode(): Int =
            (protover * 31 + operation) * 31 + body.contentHashCode()
    }

    /**
     * 把一段字节切成若干帧。
     *
     * ## 容错
     *
     * - 长度不足一个头 → 停止（不抛异常）
     * - `total < 16` → 停止（脏数据，继续读会越界）
     * - `total` 超出剩余字节 → 停止（半包，等下一帧）
     *
     * ⚠️ **不抛异常**：WS 上偶发脏数据不该让整个连接崩掉。
     */
    fun parseFrames(data: ByteArray): List<Frame> {
        if (data.size < HEADER_LEN) return emptyList()

        val out = ArrayList<Frame>(4)
        var off = 0
        while (off + HEADER_LEN <= data.size) {
            val total = readInt(data, off)
            if (total < HEADER_LEN) break
            if (off + total > data.size) break

            val headerLen = readShort(data, off + 4)
            val protover = readShort(data, off + 6)
            val operation = readInt(data, off + 8)

            // headerLen 异常时回退到 16（服务端恒为 16）
            val bodyStart = off + if (headerLen in HEADER_LEN..total) headerLen else HEADER_LEN
            val body = data.copyOfRange(bodyStart, off + total)

            out.add(Frame(protover = protover, operation = operation, body = body))
            off += total
        }
        return out
    }

    /**
     * 构造一个待发送的帧。
     *
     * @param protover 发送时用 [PROTO_PLAIN]（1）；实测认证与心跳都可用
     */
    fun buildFrame(
        operation: Int,
        body: ByteArray = ByteArray(0),
        protover: Int = PROTO_AUTH,
    ): ByteArray {
        val total = HEADER_LEN + body.size
        val out = ByteArray(total)
        writeInt(out, 0, total)
        writeShort(out, 4, HEADER_LEN)
        writeShort(out, 6, protover)
        writeInt(out, 8, operation)
        writeInt(out, 12, 1) // sequence
        body.copyInto(out, HEADER_LEN)
        return out
    }

    /** 认证帧的 body（JSON）。 */
    fun authBody(
        roomId: Long,
        token: String,
        buvid: String,
        uid: Long = 0L,
    ): String = buildString {
        append("{\"uid\":").append(uid)
        append(",\"roomid\":").append(roomId)
        // protover=2 表示"我要 zlib 压缩的消息"（实测服务端会按此下发）
        append(",\"protover\":2")
        append(",\"platform\":\"web\"")
        // type=2 = 网页端
        append(",\"type\":2")
        append(",\"key\":\"").append(escape(token)).append('"')
        // 匿名观众也发 buvid；不带会被拒
        append(",\"buvid\":\"").append(escape(buvid)).append('"')
        append('}')
    }

    /** 心跳帧的 body（明文 `[object Object]` 是官方网页端的原样写法）。 */
    fun heartbeatBody(): String = "[object Object]"

    /** 心跳回应里的人气值（body 是 4 字节大端整数）。 */
    fun popularity(body: ByteArray): Int =
        if (body.size >= 4) readInt(body, 0) else 0

    // ---- 字节工具（避免依赖 java.nio，便于纯 JVM 单测）----

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun readShort(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun writeShort(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
