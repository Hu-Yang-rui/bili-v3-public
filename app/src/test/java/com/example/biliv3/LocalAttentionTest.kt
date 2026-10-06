package com.example.biliv3

import com.example.biliv3.data.MiniJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 「特别关注」本地序列化的往返测试。
 *
 * ## 为什么这组测试重要
 *
 * 存的是 JSON 数组，读写两侧是**手写的**（不能用 `org.json` ——
 * 它在本地单测里是 stub，调用即抛 `not mocked`）。
 * 手写序列化最容易出的错是**转义**：UP 主名字里带引号或反斜杠时，
 * 写进去能存、读出来就断了 —— 表现为"关注的人莫名其妙消失"。
 *
 * 与 `LocalAttentionStore` 内部的实现**共用同一套 MiniJson 读侧**，
 * 所以这里验证的解析语义就是生产语义。
 */
class LocalAttentionTest {

    /**
     * 复刻 `LocalAttentionStore.serialize` 的写法。
     *
     * ⚠️ 这里**故意重写一份**而不是调用生产代码：生产方法是 private，
     * 而把它改成 public 只为测试会扩大 API 面。
     * 代价是"测的是副本"—— 所以本测试的断言只覆盖
     * **格式约定**（字段名、转义规则），不覆盖实现细节。
     */
    private fun serialize(list: List<Triple<Long, String, String>>): String =
        list.joinToString(prefix = "[", postfix = "]", separator = ",") { (mid, name, face) ->
            """{"mid":$mid,"name":${escape(name)},"face":${escape(face)}}"""
        }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 解析（与生产同规则：MiniJson 读侧）。 */
    private fun parse(raw: String): List<Triple<Long, String, String>> =
        MiniJson.elements(raw).mapNotNull { obj ->
            val mid = MiniJson.long(obj, "mid") ?: return@mapNotNull null
            if (mid <= 0L) return@mapNotNull null
            Triple(
                mid,
                MiniJson.string(obj, "name").orEmpty(),
                MiniJson.string(obj, "face").orEmpty(),
            )
        }

    @Test
    fun `空列表往返`() {
        assertThat(parse(serialize(emptyList()))).isEmpty()
    }

    @Test
    fun `普通条目往返`() {
        val src = listOf(Triple(12345L, "某个UP主", "//i0.hdslb.com/bfs/face/a.jpg"))
        val out = parse(serialize(src))
        assertThat(out).hasSize(1)
        assertThat(out[0].first).isEqualTo(12345L)
        assertThat(out[0].second).isEqualTo("某个UP主")
        assertThat(out[0].third).isEqualTo("//i0.hdslb.com/bfs/face/a.jpg")
    }

    /** 多个条目保持顺序（最新在前由写入侧保证，解析不得打乱）。 */
    @Test
    fun `多条保持顺序`() {
        val src = listOf(
            Triple(3L, "C", "c.jpg"),
            Triple(2L, "B", "b.jpg"),
            Triple(1L, "A", "a.jpg"),
        )
        val out = parse(serialize(src))
        assertThat(out.map { it.first }).containsExactly(3L, 2L, 1L).inOrder()
    }

    /**
     * ⚠️ 这是本文件的核心用例：**名字里带引号**。
     *
     * 不转义的话写出来是 `{"name":"a"b"}` —— 读的时候会在
     * 第二个引号处截断，条目要么丢失、要么名字被截成 `a`。
     */
    @Test
    fun `名字里的引号被正确转义`() {
        val src = listOf(Triple(1L, """说"引号"的UP""", "f.jpg"))
        val out = parse(serialize(src))
        assertThat(out).hasSize(1)
        assertThat(out[0].second).isEqualTo("""说"引号"的UP""")
    }

    /** 名字里带反斜杠（Windows 路径风格）也必须往返。 */
    @Test
    fun `名字里的反斜杠被正确转义`() {
        val src = listOf(Triple(1L, """back\slash""", "f.jpg"))
        val out = parse(serialize(src))
        assertThat(out).hasSize(1)
        assertThat(out[0].second).isEqualTo("""back\slash""")
    }

    /** 头像地址通常带 `//`（协议相对），必须原样保留。 */
    @Test
    fun `协议相对的头像地址原样保留`() {
        val src = listOf(Triple(1L, "U", "//i2.hdslb.com/bfs/face/x.webp"))
        val out = parse(serialize(src))
        assertThat(out[0].third).isEqualTo("//i2.hdslb.com/bfs/face/x.webp")
    }

    /**
     * 非法 mid 的条目被丢弃，而不是产生一个 mid=0 的幽灵用户。
     *
     * mid=0 在 UI 上会渲染成一行点不动的空条目。
     */
    @Test
    fun `非法 mid 被丢弃`() {
        assertThat(parse("""[{"mid":0,"name":"x","face":""}]""")).isEmpty()
        assertThat(parse("""[{"mid":-5,"name":"x","face":""}]""")).isEmpty()
        assertThat(parse("""[{"name":"x","face":""}]""")).isEmpty()
    }

    /** 空 / 损坏输入返回空列表，不抛异常（读侧必须容错）。 */
    @Test
    fun `空与损坏输入不抛异常`() {
        assertThat(parse("")).isEmpty()
        assertThat(parse("   ")).isEmpty()
        assertThat(parse("not json at all")).isEmpty()
        assertThat(parse("[]")).isEmpty()
    }

    /** 缺 name / face 时降级为空串（UI 自己兜底显示 UID）。 */
    @Test
    fun `缺失字段降级为空串`() {
        val out = parse("""[{"mid":7}]""")
        assertThat(out).hasSize(1)
        assertThat(out[0].first).isEqualTo(7L)
        assertThat(out[0].second).isEmpty()
        assertThat(out[0].third).isEmpty()
    }
}
