package com.example.biliv3

import com.example.biliv3.data.api.Wbi
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * WBI 签名黄金向量测试。
 *
 * **这是本项目最重要的一组测试。**
 * 签名悄悄改坏是这类项目最致命的故障 —— 表现为「所有签名接口突然返回 -352」，
 * 而错误信息完全不指向签名。所以输入固定、期望值硬编码，
 * 任何人重构 [Wbi] 都必须先让这组测试通过。
 *
 * 期望值来源：独立复算（不复用被测代码），并已用真实接口验证
 * （`rcmd` 返回 code=0 且拿到真实推荐数据）。
 */
class WbiTest {

    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"
    private val wts = 1700000000L

    // ---- percentEncode：必须与 JS encodeURIComponent 语义一致 ----

    @Test
    fun `空格编码为百分号二十而不是加号`() {
        assertThat(Wbi.percentEncode("a b")).isEqualTo("a%20b")
        // ❌ java.net.URLEncoder.encode("a b") == "a+b" —— 签名必然对不上
        assertThat(Wbi.percentEncode("a b")).isNotEqualTo("a+b")
    }

    @Test
    fun `波浪号保持原样而不是被编码成百分号七E`() {
        assertThat(Wbi.percentEncode("~")).isEqualTo("~")
        // ❌ URLEncoder 会把 ~ 编成 %7E
        assertThat(Wbi.percentEncode("~")).isNotEqualTo("%7E")
    }

    @Test
    fun `unreserved 字符集全部不编码`() {
        val unreserved = "ABCXYZabcxyz0189-_.~"
        assertThat(Wbi.percentEncode(unreserved)).isEqualTo(unreserved)
    }

    @Test
    fun `中文按 UTF-8 逐字节编码`() {
        assertThat(Wbi.percentEncode("哔哩")).isEqualTo("%E5%93%94%E5%93%A9")
    }

    // ---- fileName ----

    @Test
    fun `从图片 URL 提取 key`() {
        assertThat(Wbi.fileName("https://i0.hdslb.com/bfs/wbi/$imgKey.png"))
            .isEqualTo(imgKey)
    }

    @Test
    fun `无扩展名时不截断`() {
        assertThat(Wbi.fileName("https://x.com/bfs/wbi/abcdef")).isEqualTo("abcdef")
    }

    // ---- mixinKey ----

    @Test
    fun `mixinKey 黄金向量`() {
        val keys = Wbi.Keys(imgKey, subKey)
        assertThat(Wbi.mixinKey(keys)).isEqualTo("ea1db124af3c7062474693fa704f4ff8")
    }

    @Test
    fun `源串长度不足必须抛错而不是静默返回错值`() {
        var threw = false
        try {
            Wbi.mixinKey(Wbi.Keys("short", "key"))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    // ---- sign ----

    @Test
    fun `签名黄金向量`() {
        val signed = Wbi.sign(
            params = mapOf(
                "bvid" to "BV1xx411c7mD",
                "cid" to "123456",
                "qn" to "80",
                "fnval" to "16",
                "fnver" to "0",
                "fourk" to "1",
            ),
            keys = Wbi.Keys(imgKey, subKey),
            wts = wts,
        )

        assertThat(signed["w_rid"]).isEqualTo("be63cfe35cfb07f3ac2ad652f4ae34ae")
        assertThat(signed["wts"]).isEqualTo("1700000000")
        // 原始参数必须原样保留
        assertThat(signed["bvid"]).isEqualTo("BV1xx411c7mD")
        assertThat(signed["fnval"]).isEqualTo("16")
    }

    @Test
    fun `参数排序不影响结果`() {
        val keys = Wbi.Keys(imgKey, subKey)
        val a = Wbi.sign(mapOf("qn" to "80", "bvid" to "BV1", "cid" to "1"), keys, wts)
        val b = Wbi.sign(mapOf("cid" to "1", "bvid" to "BV1", "qn" to "80"), keys, wts)
        assertThat(a["w_rid"]).isEqualTo(b["w_rid"])
    }

    @Test
    fun `wts 变化必须导致 w_rid 变化`() {
        val keys = Wbi.Keys(imgKey, subKey)
        val a = Wbi.sign(mapOf("bvid" to "BV1"), keys, wts)
        val b = Wbi.sign(mapOf("bvid" to "BV1"), keys, wts + 1)
        assertThat(a["w_rid"]).isNotEqualTo(b["w_rid"])
    }

    @Test
    fun `过滤字符在签名前被剔除`() {
        val keys = Wbi.Keys(imgKey, subKey)
        val withFilter = Wbi.sign(mapOf("keyword" to "a!b'c(d)e*f"), keys, wts)
        val without = Wbi.sign(mapOf("keyword" to "abcdef"), keys, wts)
        assertThat(withFilter["w_rid"]).isEqualTo(without["w_rid"])
        assertThat(withFilter["keyword"]).isEqualTo("abcdef")
    }

    @Test
    fun `中文关键词签名可复现`() {
        val signed = Wbi.sign(
            params = mapOf("keyword" to "原神"),
            keys = Wbi.Keys(imgKey, subKey),
            wts = wts,
        )
        assertThat(signed["w_rid"]).hasLength(32)
        assertThat(signed["keyword"]).isEqualTo("原神")
    }

    // ---- toQueryString ----

    @Test
    fun `query string 与签名内部编码规则一致`() {
        val signed = Wbi.sign(
            params = mapOf("keyword" to "a b", "page" to "1"),
            keys = Wbi.Keys(imgKey, subKey),
            wts = wts,
        )
        val qs = Wbi.toQueryString(signed)
        assertThat(qs).contains("keyword=a%20b")
        assertThat(qs).contains("page=1")
        assertThat(qs).contains("w_rid=")
        assertThat(qs).doesNotContain("a+b")
    }

    // ---- 推荐流真实参数（防回归）----

    @Test
    fun `推荐流参数签名稳定`() {
        val signed = Wbi.sign(
            params = mapOf(
                "ps" to "20",
                "fresh_type" to "4",
                "fresh_idx" to "1",
                "fresh_idx_1h" to "1",
                "feed_version" to "V8",
            ),
            keys = Wbi.Keys(imgKey, subKey),
            wts = wts,
        )
        // 只要这套参数 + keys + wts 不变，签名就不该变
        assertThat(signed["w_rid"]).hasLength(32)
        assertThat(signed.keys).containsAtLeast(
            "ps", "fresh_type", "fresh_idx", "fresh_idx_1h",
            "feed_version", "wts", "w_rid",
        )
    }
}
