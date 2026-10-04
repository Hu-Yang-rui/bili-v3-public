package com.example.biliv3

import com.example.biliv3.data.auth.CookieCodec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cookie 导入 / 导出解析测试。
 *
 * ## 为什么这些用例值得写
 *
 * 导入导出最容易出的两类事故，都能在这里钉死：
 *
 * 1. **导出的内容导不回去** —— [CookieCodec.serialize] 的输出必须能被
 *    [CookieCodec.parse] 原样读回（往返一致性，见"往返"组）
 * 2. **缺 SESSDATA 却当成功** —— `SESSDATA` 才是登录凭据（`AuthStore.isLoggedIn`
 *    的判据同此）。缺它必须**失败**，不能"导入成功但实际没登录"
 *
 * 测试数据全是**假值**（`FAKE_*`），不含任何真实凭据。
 */
class CookieCodecTest {

    private val fake = linkedMapOf(
        "SESSDATA" to "FAKE_SESSDATA_VALUE",
        "bili_jct" to "FAKE_JCT_VALUE",
        "DedeUserID" to "12345678",
    )

    private fun ok(raw: String): LinkedHashMap<String, String> {
        val r = CookieCodec.parse(raw)
        assertThat(r).isInstanceOf(CookieCodec.Result.Ok::class.java)
        return (r as CookieCodec.Result.Ok).pairs
    }

    private fun fail(raw: String): String {
        val r = CookieCodec.parse(raw)
        assertThat(r).isInstanceOf(CookieCodec.Result.Fail::class.java)
        return (r as CookieCodec.Result.Fail).reason
    }

    // ---------------- 往返一致性（最关键的一组） ----------------

    @Test
    fun `导出的内容必须能被重新导入`() {
        val exported = CookieCodec.serialize(fake)
        assertThat(ok(exported)).isEqualTo(fake)
    }

    @Test
    fun `往返两次结果稳定`() {
        val once = CookieCodec.serialize(ok(CookieCodec.serialize(fake)))
        val twice = CookieCodec.serialize(ok(once))
        assertThat(twice).isEqualTo(once)
    }

    // ---------------- 各种输入格式 ----------------

    @Test
    fun `裸 cookie 串`() {
        assertThat(ok("SESSDATA=a; bili_jct=b; DedeUserID=1"))
            .isEqualTo(linkedMapOf("SESSDATA" to "a", "bili_jct" to "b", "DedeUserID" to "1"))
    }

    @Test
    fun `带 Cookie 前缀`() {
        assertThat(ok("Cookie: SESSDATA=a; bili_jct=b")).containsKey("SESSDATA")
    }

    @Test
    fun `小写 cookie 前缀`() {
        assertThat(ok("cookie: SESSDATA=a")).containsKey("SESSDATA")
    }

    @Test
    fun `换行分隔`() {
        val p = ok("SESSDATA=a\nbili_jct=b\nDedeUserID=1")
        assertThat(p).hasSize(3)
    }

    @Test
    fun `回车换行分隔`() {
        val p = ok("SESSDATA=a\r\nbili_jct=b")
        assertThat(p).hasSize(2)
    }

    @Test
    fun `and 号分隔`() {
        val p = ok("SESSDATA=a&bili_jct=b")
        assertThat(p).hasSize(2)
    }

    @Test
    fun `JSON 对象`() {
        assertThat(ok("""{"SESSDATA":"a","bili_jct":"b"}"""))
            .isEqualTo(linkedMapOf("SESSDATA" to "a", "bili_jct" to "b"))
    }

    @Test
    fun `JSON 数组 浏览器扩展形态`() {
        val raw = """[{"name":"SESSDATA","value":"a"},{"name":"bili_jct","value":"b"}]"""
        assertThat(ok(raw)).isEqualTo(linkedMapOf("SESSDATA" to "a", "bili_jct" to "b"))
    }

    @Test
    fun `值带双引号`() {
        assertThat(ok("""SESSDATA="a"""")["SESSDATA"]).isEqualTo("a")
    }

    @Test
    fun `值带单引号`() {
        assertThat(ok("SESSDATA='a'")["SESSDATA"]).isEqualTo("a")
    }

    @Test
    fun `多余空格与空段`() {
        val p = ok("  SESSDATA = a ;;  bili_jct=b ; ")
        assertThat(p["SESSDATA"]).isEqualTo("a")
        assertThat(p["bili_jct"]).isEqualTo("b")
    }

    // ---------------- 重复字段 ----------------

    @Test
    fun `重复字段后者覆盖前者`() {
        // 与浏览器"同名 cookie 取最后一条"一致 —— 用户从 DevTools
        // 复制时，后面那条通常才是最新的
        assertThat(ok("SESSDATA=old; SESSDATA=new")["SESSDATA"]).isEqualTo("new")
    }

    @Test
    fun `重复字段不产生两个键`() {
        assertThat(ok("SESSDATA=a; SESSDATA=b")).hasSize(1)
    }

    // ---------------- 失败路径 ----------------

    @Test
    fun `空内容失败`() {
        assertThat(fail("")).contains("空")
    }

    @Test
    fun `纯空白失败`() {
        assertThat(fail("   \n  ")).contains("空")
    }

    @Test
    fun `缺 SESSDATA 失败`() {
        // 这是最关键的一条：没有 SESSDATA 就不是登录态
        assertThat(fail("bili_jct=b; DedeUserID=1")).contains("SESSDATA")
    }

    @Test
    fun `SESSDATA 为空值也失败`() {
        assertThat(fail("SESSDATA=; bili_jct=b")).contains("SESSDATA")
    }

    @Test
    fun `没有等号失败`() {
        assertThat(fail("hello world")).isNotEmpty()
    }

    @Test
    fun `JSON 里没有 cookie 字段失败`() {
        assertThat(fail("""{"foo":"bar"}""")).isNotEmpty()
    }

    // ---------------- 校验辅助 ----------------

    @Test
    fun `缺失字段检测`() {
        val p = ok("SESSDATA=a")
        assertThat(CookieCodec.missingEssentials(p)).containsExactly("bili_jct", "DedeUserID")
    }

    @Test
    fun `字段齐全时无缺失`() {
        assertThat(CookieCodec.missingEssentials(fake)).isEmpty()
    }

    // ---------------- 脱敏（安全红线） ----------------

    @Test
    fun `脱敏摘要不含任何真实值`() {
        val s = CookieCodec.maskedSummary(fake)
        // 任何一个真实值都不得出现
        for (v in fake.values) {
            assertThat(s).doesNotContain(v)
        }
    }

    @Test
    fun `脱敏摘要含字段名与长度`() {
        val s = CookieCodec.maskedSummary(fake)
        assertThat(s).contains("SESSDATA")
        assertThat(s).contains("${fake["SESSDATA"]!!.length} 字符")
    }

    @Test
    fun `脱敏摘要标注凭据字段`() {
        assertThat(CookieCodec.maskedSummary(fake)).contains("凭据")
    }
}
