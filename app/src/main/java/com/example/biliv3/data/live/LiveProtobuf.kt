package com.example.biliv3.data.live

/**
 * 直播消息里的**嵌套 protobuf** 解码（纯逻辑，可单测）。
 *
 * ---
 *
 * # 为什么需要它（这是一个真实的坑）
 *
 * `INTERACT_WORD_V2`（**进入直播间**事件）在 2024 年之后**不再是纯 JSON**：
 *
 * ```json
 * {"cmd":"INTERACT_WORD_V2","data":{"dmscore":20,"pb":"CNql88gB..."}}
 * ```
 *
 * `data` 里**只有 `dmscore` 与 `pb`** —— 没有 `uid`、没有 `uname`。
 * 直接读 `data.uid` 会恒得 0，表现为「进入消息全部没有用户名和头像」。
 *
 * 真实数据在 `pb` 里：base64 编码的 protobuf。实测解出的字段：
 *
 * ```
 * field 1  (varint) = uid
 * field 2  (string) = uname
 * field 4  (bytes)  = identities
 * field 5  (varint) = msg_type（1=进入 2=关注 3=分享）
 * field 6  (varint) = room_id
 * field 7  (varint) = 时间戳（秒）
 * field 22 (message) = { field1=uid, field2={field1=uname, field2=face} }
 * ```
 *
 * 其中 **field 22 是拿头像的唯一途径**（实测顶层没有 face 字段）。
 *
 * ## 🔴 为什么 base64 也要自己写（不能用 `android.util.Base64`）
 *
 * `android.util.Base64` 在**本地 JVM 单测里是 stub** —— 调用即抛
 * `RuntimeException: Method encodeToString in android.util.Base64 not mocked`。
 * 这与 `org.json` 当年是同一类问题（见 `build.gradle.kts` 里
 * `testImplementation(libs.json)` 的注释）。
 *
 * 本项目对 `org.json` 的解法是"测试时引入真实实现"；
 * 但 base64 没有等价的轻量替代，而且**自己写只要 20 行** ——
 * 于是选择纯 Kotlin 实现，测试与生产跑同一份代码。
 *
 * ## 为什么自己写 protobuf 而不是引 protobuf-javalite
 *
 * 与项目既有决策一致（`Proto.kt` 手写 protobuf，省 2~3 MB）：
 * 这里只需要读 3 个字段，引一个完整的 protobuf 运行时不合算。
 *
 * ## 能力边界
 *
 * 只实现 **varint / length-delimited / 跳过其余**。
 * 不处理 groups（wire type 3/4），遇到就停 —— 服务端加字段不会让解析失败。
 */
object LiveProtobuf {

    /**
     * 从 `INTERACT_WORD_V2` 的 `pb` 解出进入者信息。
     *
     * @param base64Pb `data.pb` 的原文（base64，可含 `+/=` 与换行）
     * @return 解出的信息；解析失败时返回 [InteractWord.EMPTY]（**不抛异常** ——
     *   一条消息解析失败不该让整个聊天断掉）
     */
    fun decodeInteractWord(base64Pb: String): InteractWord {
        val bytes = decodeBase64(base64Pb) ?: return InteractWord.EMPTY
        return decodeInteractWordBytes(bytes)
    }

    /**
     * 同上，但直接吃字节（**便于单测**，不依赖 Android 的 Base64）。
     */
    fun decodeInteractWordBytes(bytes: ByteArray): InteractWord {
        var uid = 0L
        var uname = ""
        var face = ""
        var msgType = 1

        var o = 0
        while (o < bytes.size) {
            val (tag, next) = readVarint(bytes, o) ?: break
            o = next
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()

            when (wire) {
                0 -> {
                    val (v, n2) = readVarint(bytes, o) ?: break
                    o = n2
                    when (field) {
                        1 -> uid = v
                        5 -> msgType = v.toInt()
                    }
                }

                2 -> {
                    val (len, n2) = readVarint(bytes, o) ?: break
                    val start = n2
                    val end = start + len.toInt()
                    if (end > bytes.size || end < start) break
                    val sub = bytes.copyOfRange(start, end)
                    o = end

                    when (field) {
                        2 -> uname = String(sub, Charsets.UTF_8)
                        // field 22 = 嵌套结构，头像在里面
                        22 -> {
                            val inner = decodeNestedUser(sub)
                            if (inner.first > 0L) uid = inner.first
                            if (inner.second.isNotEmpty()) uname = inner.second
                            if (inner.third.isNotEmpty()) face = inner.third
                        }
                    }
                }

                // 5 = 32-bit, 1 = 64-bit：跳过
                5 -> o += 4
                1 -> o += 8
                // 3/4 是已废弃的 group，遇到就停（避免死循环）
                else -> break
            }
        }

        if (uid <= 0L && uname.isEmpty()) return InteractWord.EMPTY
        return InteractWord(uid = uid, uname = uname, face = face, msgType = msgType)
    }

    /**
     * 解 field 22 的嵌套结构。
     *
     * 实测形态：
     * ```
     * field 22 = {
     *   field 1 = uid
     *   field 2 = { field 1 = uname, field 2 = face }
     * }
     * ```
     *
     * @return (uid, uname, face)
     */
    private fun decodeNestedUser(bytes: ByteArray): Triple<Long, String, String> {
        var uid = 0L
        var uname = ""
        var face = ""

        var o = 0
        while (o < bytes.size) {
            val (tag, next) = readVarint(bytes, o) ?: break
            o = next
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()

            if (wire == 0) {
                val (v, n2) = readVarint(bytes, o) ?: break
                o = n2
                if (field == 1) uid = v
            } else if (wire == 2) {
                val (len, n2) = readVarint(bytes, o) ?: break
                val start = n2
                val end = start + len.toInt()
                if (end > bytes.size || end < start) break
                val sub = bytes.copyOfRange(start, end)
                o = end

                if (field == 2) {
                    // 再嵌一层：uname / face
                    var oo = 0
                    while (oo < sub.size) {
                        val (t2, n3) = readVarint(sub, oo) ?: break
                        oo = n3
                        val f2 = (t2 ushr 3).toInt()
                        val w2 = (t2 and 0x7).toInt()
                        if (w2 != 2) {
                            if (w2 == 0) { val (_, n4) = readVarint(sub, oo) ?: break; oo = n4 }
                            else break
                            continue
                        }
                        val (l2, n4) = readVarint(sub, oo) ?: break
                        val s2 = n4
                        val e2 = s2 + l2.toInt()
                        if (e2 > sub.size || e2 < s2) break
                        val v = String(sub.copyOfRange(s2, e2), Charsets.UTF_8)
                        oo = e2
                        when (f2) {
                            1 -> uname = v
                            2 -> face = v
                        }
                    }
                }
            } else if (wire == 5) {
                o += 4
            } else if (wire == 1) {
                o += 8
            } else break
        }

        return Triple(uid, uname, face)
    }

    /**
     * 读一个 varint。
     *
     * protobuf 的 varint 最多 10 字节（64 位）。超过就判为脏数据 ——
     * 不设上限的话一个坏字节就能让循环跑很久。
     *
     * @return (值, 下一个偏移)；数据不合法返回 null
     */
    private fun readVarint(b: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < b.size && shift < 64) {
            val x = b[i].toInt() and 0xFF
            i++
            result = result or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    // ---------------------------------------------------------------------
    // 纯 Kotlin base64 解码
    // ---------------------------------------------------------------------

    /** base64 字母表（标准，`+/`）。 */
    private val B64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 反查表：字符 → 6 位值；-1 = 非法字符（换行/空格/填充会被单独跳过）。 */
    private val B64_LOOKUP = IntArray(128) { -1 }.also { t ->
        B64_ALPHABET.forEachIndexed { i, c -> t[c.code] = i }
    }

    /**
     * 解码 base64（标准字母表，容忍换行与空格）。
     *
     * ## 为什么不用 `java.util.Base64`
     *
     * `java.util.Base64` 需要 API 26+ —— 本项目 `minSdk = 26`，**刚好够**。
     * 但仍然自己写，理由有两条：
     * 1. 单测跑在 JVM 上时，`java.util.Base64` 可用，而
     *    `android.util.Base64` 不可用 —— 用前者会让"测试通过、真机可用"
     *    的路径与"生产"分叉（生产走 Android 实现）。**同一份实现**才可靠。
     * 2. 纯 Kotlin 版本可以直接对 `ByteArray` 做边界测试。
     *
     * @return 解出的字节；输入非法（长度不合法、含非法字符）返回 null
     */
    fun decodeBase64(s: String): ByteArray? {
        if (s.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        var padding = 0

        for (ch in s) {
            if (ch == '=') {
                padding++
                continue
            }
            // 跳过换行 / 空格 / 制表（服务端有时按 76 列折行）
            if (ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') continue
            val code = ch.code
            val v = if (code in 0..127) B64_LOOKUP[code] else -1
            if (v < 0) return null

            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        // 长度不合法（余下 1 个 6 位组无法构成一个字节）→ 判为非法
        if (bits >= 6) return null
        // 填充数不得超过 2
        if (padding > 2) return null
        return out.toByteArray()
    }
}

/**
 * `INTERACT_WORD_V2` 解出的进入者信息。
 *
 * @param msgType 1=进入直播间 2=关注 3=分享
 */
data class InteractWord(
    val uid: Long,
    val uname: String,
    val face: String,
    val msgType: Int,
) {
    companion object {
        val EMPTY = InteractWord(uid = 0L, uname = "", face = "", msgType = 1)
    }
}
