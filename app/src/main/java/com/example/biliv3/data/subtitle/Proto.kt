package com.example.biliv3.data.subtitle

import java.io.ByteArrayOutputStream

/**
 * 极简 protobuf 编解码器。
 *
 * ## 为什么不引入 protobuf-javalite / grpc-java
 *
 * B 站的 AI 字幕走 gRPC（`bilibili.subtitle.Subtitle/SubtitleView`），
 * 官方用 protobuf 序列化。但引入完整 protobuf 运行时 + gRPC 客户端会：
 * - 增加约 2~3MB 体积（release 包现在只有 2.7MB）
 * - 需要 `.proto` 源文件（我们没有，只能从反编译结果反推）
 * - gRPC 栈依赖大量 `io.grpc.*`（官方 APK 里有 20+ dex 的规模）
 *
 * 而这里的场景**只需要两个固定消息**（一个请求、一个响应），
 * 字段少且结构稳定。手写编解码不到 200 行，零依赖，且完全可控。
 *
 * ## 已逆向确认的 schema（来自官方 APK 反编译）
 *
 * ```
 * SubtitleViewReq:
 *   1: pid                  (int64)   cid
 *   2: oid                  (int64)   aid（type=0）/ epId（type=1）
 *   3: type                 (int32)   0=UGC 1=PGC
 *   4: spmid                (string)
 *   5: is_hard_boot         (int32)
 *   6: context_ext          (string)
 *   7: cur_language         (string)
 *   8: cur_production_type  (int32)
 *   9: preferred_language   (string)
 *  10: preferred2_language  (string)
 *  11: arc_video_language   (string)
 *  12: arc_hardcoded_language (string)
 *  13: playlist_switch      (int32)
 *
 * SubtitleViewReply:
 *   1: subtitle (VideoSubtitle)
 *
 * VideoSubtitle:
 *   1: lan               (string)
 *   2: lan_doc           (string)
 *   3: subtitles         (repeated SubtitleItem)
 *   4: subtitle_position (SubtitlePosition)
 *   5: font_size_type    (FontSizeType)
 *
 * SubtitleItem:
 *   1: id            2: id_str         3: lan
 *   4: lan_doc       5: subtitle_url   6: author
 *   7: type          8: lan_doc_brief  9: ai_type
 *  10: ai_status    11: role          12: subtitle_height
 *  13: format
 * ```
 */
object Proto {

    // ---------------- 编码 ----------------

    /** varint 编码（无符号，protobuf 的 int32/int64 都用它）。 */
    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        // protobuf 用无符号 varint；负数会被当作 64 位补码，走 10 字节
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                break
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())

    /** 写入 int32/int64 字段。 */
    fun writeVarint(out: ByteArrayOutputStream, field: Int, value: Long) {
        out.write(tag(field, WIRE_VARINT))
        out.write(varint(value))
    }

    /** 写入 string 字段。 */
    fun writeString(out: ByteArrayOutputStream, field: Int, value: String) {
        if (value.isEmpty()) return
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(tag(field, WIRE_LENGTH))
        out.write(varint(bytes.size.toLong()))
        out.write(bytes)
    }

    // ---------------- 解码 ----------------

    /**
     * 解析 protobuf 为「字段号 → 值」的映射。
     *
     * 值类型按 wire type 区分：
     * - varint → [Long]
     * - length-delimited → [ByteArray]（调用方自行决定当字符串还是嵌套消息）
     *
     * 重复字段（repeated）会累积成 [List]。
     */
    fun decode(bytes: ByteArray): Map<Int, Any> {
        val result = HashMap<Int, Any>()
        var p = 0
        while (p < bytes.size) {
            val t = readVarint(bytes, p)
            if (t == null) break
            p = t.next
            val field = (t.value ushr 3).toInt()
            val wire = (t.value and 7L).toInt()

            when (wire) {
                WIRE_VARINT -> {
                    val v = readVarint(bytes, p) ?: break
                    p = v.next
                    result.putRepeated(field, v.value)
                }

                WIRE_LENGTH -> {
                    val len = readVarint(bytes, p) ?: break
                    p = len.next
                    val n = len.value.toInt()
                    if (n < 0 || p + n > bytes.size) break
                    result.putRepeated(field, bytes.copyOfRange(p, p + n))
                    p += n
                }

                WIRE_FIXED64 -> p += 8
                WIRE_FIXED32 -> p += 4

                else -> break // 未知 wire type，停止解析（容错）
            }
        }
        return result
    }

    private fun MutableMap<Int, Any>.putRepeated(field: Int, value: Any) {
        val old = this[field]
        when (old) {
            null -> this[field] = value
            is MutableList<*> -> @Suppress("UNCHECKED_CAST") (old as MutableList<Any>).add(value)
            else -> this[field] = mutableListOf(old, value)
        }
    }

    private class Varint(val value: Long, val next: Int)

    private fun readVarint(bytes: ByteArray, start: Int): Varint? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return Varint(result, i)
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5
}

// ---------------- 便捷取值（顶层扩展，便于 import）----------------
//
// 放顶层而非 Proto 内部：**成员扩展函数无法被 import**，
// 放在 object 里就只能 `with(Proto) { ... }` 调用，很别扭。

/** 取 varint 字段为 Int。 */
fun Map<Int, Any>.pbInt(field: Int): Int =
    (this[field] as? Long)?.toInt() ?: 0

/** 取 length-delimited 字段为 String。 */
fun Map<Int, Any>.pbString(field: Int): String =
    (this[field] as? ByteArray)?.toString(Charsets.UTF_8).orEmpty()

/** 取嵌套消息（单条）。 */
fun Map<Int, Any>.pbMessage(field: Int): Map<Int, Any>? =
    (this[field] as? ByteArray)?.let { Proto.decode(it) }

/** 取重复嵌套消息。 */
fun Map<Int, Any>.pbMessages(field: Int): List<Map<Int, Any>> {
    val raw = this[field] ?: return emptyList()
    return when (raw) {
        is ByteArray -> listOf(Proto.decode(raw))
        is List<*> -> raw.filterIsInstance<ByteArray>().map { Proto.decode(it) }
        else -> emptyList()
    }
}
