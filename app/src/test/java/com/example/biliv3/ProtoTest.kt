package com.example.biliv3

import com.example.biliv3.data.subtitle.Proto
import com.example.biliv3.data.subtitle.pbInt
import com.example.biliv3.data.subtitle.pbMessage
import com.example.biliv3.data.subtitle.pbMessages
import com.example.biliv3.data.subtitle.pbString
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * protobuf 手写编解码测试。
 *
 * ## 为什么这组测试重要
 *
 * AI 字幕走 gRPC + protobuf，而我们**没有引入 protobuf 运行时**（手写编解码）。
 * 手写编解码一旦有偏差，表现为"请求发出去了但服务端返回 -400"
 * 或"响应解析出空列表"——**没有任何报错指向编解码**，极难排查。
 *
 * 所以这里用**已知的标准向量**锁死行为。
 */
class ProtoTest {

    // ---------------- varint 编码 ----------------

    @Test
    fun `varint 单字节边界`() {
        assertThat(Proto.varint(0)).isEqualTo(byteArrayOf(0x00))
        assertThat(Proto.varint(1)).isEqualTo(byteArrayOf(0x01))
        assertThat(Proto.varint(127)).isEqualTo(byteArrayOf(0x7F))
    }

    @Test
    fun `varint 多字节`() {
        // 128 = 0x80 -> [0x80, 0x01]
        assertThat(Proto.varint(128)).isEqualTo(byteArrayOf(0x80.toByte(), 0x01))
        // 300 = 0xAC 0x02
        assertThat(Proto.varint(300)).isEqualTo(byteArrayOf(0xAC.toByte(), 0x02))
        // 16384 = 0x80 0x80 0x01
        assertThat(Proto.varint(16384))
            .isEqualTo(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01))
    }

    @Test
    fun `varint 大数值(真实 aid 量级)`() {
        // B 站 aid 已达 1.17e14 量级
        val aid = 117336593996321L
        val enc = Proto.varint(aid)
        // 编码后再解码应还原
        val decoded = decodeSingleVarint(enc)
        assertThat(decoded).isEqualTo(aid)
    }

    // ---------------- 编码 + 解码往返 ----------------

    @Test
    fun `编码后能原样解回`() {
        val out = ByteArrayOutputStream().apply {
            Proto.writeVarint(this, 1, 12345L)      // pid
            Proto.writeVarint(this, 2, 67890L)      // oid
            Proto.writeVarint(this, 3, 0L)          // type
            Proto.writeString(this, 4, "spmid")     // spmid
            Proto.writeString(this, 9, "ai-zh")     // preferred_language
        }.toByteArray()

        val m = Proto.decode(out)
        assertThat(m.pbInt(1)).isEqualTo(12345)
        assertThat(m.pbInt(2)).isEqualTo(67890)
        assertThat(m.pbInt(3)).isEqualTo(0)
        assertThat(m.pbString(4)).isEqualTo("spmid")
        assertThat(m.pbString(9)).isEqualTo("ai-zh")
    }

    @Test
    fun `中文字符串按 UTF-8 编解码`() {
        val out = ByteArrayOutputStream().apply {
            Proto.writeString(this, 2, "中文(自动翻译)")
        }.toByteArray()

        val m = Proto.decode(out)
        assertThat(m.pbString(2)).isEqualTo("中文(自动翻译)")
    }

    @Test
    fun `空字符串字段被跳过而不是写入空值`() {
        val out = ByteArrayOutputStream().apply {
            Proto.writeString(this, 4, "")
        }.toByteArray()
        // 空串不写，长度应为 0
        assertThat(out.size).isEqualTo(0)
    }

    // ---------------- 嵌套消息（真实响应结构）----------------

    @Test
    fun `解析 SubtitleViewReply 的嵌套结构`() {
        // 手工构造一个真实形状的响应：
        // Reply{1: VideoSubtitle{1:lan,2:lan_doc,3:[SubtitleItem{3:lan,4:lan_doc,5:url,9:ai_type}]}}
        val item = ByteArrayOutputStream().apply {
            Proto.writeVarint(this, 1, 123L)                 // id
            Proto.writeString(this, 3, "ai-zh")              // lan
            Proto.writeString(this, 4, "中文(自动翻译)")        // lan_doc
            Proto.writeString(this, 5, "//i0.hdslb.com/a.json") // subtitle_url
            Proto.writeVarint(this, 7, 1L)                   // type = AI
            Proto.writeVarint(this, 9, 1L)                   // ai_type = Translate
        }.toByteArray()

        val videoSubtitle = ByteArrayOutputStream().apply {
            Proto.writeString(this, 1, "ai-zh")
            Proto.writeString(this, 2, "中文(自动翻译)")
            // 字段 3 是 repeated：写 length-delimited 嵌套消息
            writeBytes(this, 3, item)
        }.toByteArray()

        val reply = ByteArrayOutputStream().apply {
            writeBytes(this, 1, videoSubtitle)
        }.toByteArray()

        // 解析
        val r = Proto.decode(reply)
        val vs = r.pbMessage(1)
        assertThat(vs).isNotNull()
        assertThat(vs!!.pbString(1)).isEqualTo("ai-zh")

        val items = vs.pbMessages(3)
        assertThat(items).hasSize(1)
        val it = items[0]
        assertThat(it.pbInt(1)).isEqualTo(123)
        assertThat(it.pbString(3)).isEqualTo("ai-zh")
        assertThat(it.pbString(4)).isEqualTo("中文(自动翻译)")
        assertThat(it.pbString(5)).isEqualTo("//i0.hdslb.com/a.json")
        assertThat(it.pbInt(7)).isEqualTo(1)   // type = AI
        assertThat(it.pbInt(9)).isEqualTo(1)   // ai_type = Translate
    }

    @Test
    fun `repeated 字段多条能全部解析`() {
        val mkItem = { lan: String ->
            ByteArrayOutputStream().apply {
                Proto.writeString(this, 3, lan)
            }.toByteArray()
        }
        val video = ByteArrayOutputStream().apply {
            writeBytes(this, 3, mkItem("ai-zh"))
            writeBytes(this, 3, mkItem("ai-en"))
            writeBytes(this, 3, mkItem("zh-CN"))
        }.toByteArray()

        val reply = ByteArrayOutputStream().apply { writeBytes(this, 1, video) }.toByteArray()
        val items = Proto.decode(reply).pbMessage(1)!!.pbMessages(3)
        assertThat(items).hasSize(3)
        assertThat(items.map { it.pbString(3) }).containsExactly("ai-zh", "ai-en", "zh-CN").inOrder()
    }

    @Test
    fun `未知 wire type 不会崩溃(容错)`() {
        // 构造一个含 wire type 7(非法)的字节流
        val bad = byteArrayOf(0x3F, 0x01) // field=7, wire=7
        val m = Proto.decode(bad)
        assertThat(m).isEmpty()
    }

    @Test
    fun `截断的输入不会崩溃`() {
        // 声明长度 100 但实际没有数据
        val truncated = byteArrayOf(0x0A, 0x64.toByte())
        val m = Proto.decode(truncated)
        assertThat(m).isEmpty()
    }

    // ---------------- 辅助 ----------------

    /** 写入一个 length-delimited 的嵌套消息（手工，避免依赖被测量的写方法）。 */
    private fun writeBytes(out: ByteArrayOutputStream, field: Int, payload: ByteArray) {
        val tag = (field shl 3) or 2
        out.write(Proto.varint(tag.toLong()))
        out.write(Proto.varint(payload.size.toLong()))
        out.write(payload)
    }

    /** 解一个单独的 varint。 */
    private fun decodeSingleVarint(bytes: ByteArray): Long {
        // 用 decode 的语义：把 bytes 当 varint 解出来
        var result = 0L
        var shift = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            result = result or ((v and 0x7F).toLong() shl shift)
            if (v and 0x80 == 0) break
            shift += 7
        }
        return result
    }
}
