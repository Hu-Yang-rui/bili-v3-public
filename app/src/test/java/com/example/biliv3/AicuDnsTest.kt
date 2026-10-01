package com.example.biliv3

import com.example.biliv3.data.AicuRepository
import com.example.biliv3.data.api.AicuApi
import com.example.biliv3.data.api.AicuDns
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * aicu 接入的纯函数测试。
 *
 * ## 为什么这些必须测
 *
 * 这一层有两个"静默失效"高发区，两者都**不报错、只是结果不对**：
 *
 * 1. **DNS 解析** —— 投毒 IP 与真实 IP 都是合法的 IPv4 地址。
 *    不校验就会拿着 `199.59.150.12` 去连，表现为连接超时，
 *    报错完全不指向"DNS 被投毒"。这是本次接入最核心的逻辑。
 * 2. **跳转 URL 映射** —— `dyn.type` 写死一种会把专栏/动态的评论
 *    跳到不存在的视频页（用户看到 404）。类型分流表必须钉死。
 *
 * 另外 `progress`（毫秒）/ `rank`（1/2 不是热度）/ `roomid`（字符串）
 * 三个字段坑也在这里钉住 —— 它们的错误形态是"显示 0"或"显示离谱的值"。
 */
class AicuDnsTest {

    // ================= DoH JSON 解析 =================

    @Test
    fun `解析腾讯 DoH 的 Answer 数组`() {
        // 真实结构（实测 doh.pub 对 api.aicu.cc 的返回形态）
        val json = """
            {"Status":0,"TC":false,"RD":true,"RA":true,"AD":false,"CD":false,
             "Question":[{"name":"api.aicu.cc","type":1}],
             "Answer":[
               {"name":"api.aicu.cc","type":1,"TTL":300,"data":"104.26.11.4"},
               {"name":"api.aicu.cc","type":1,"TTL":300,"data":"104.26.10.4"},
               {"name":"api.aicu.cc","type":1,"TTL":300,"data":"172.67.72.100"}
             ]}
        """.trimIndent()

        val ips = AicuDns.parseDohIps(json)
        assertThat(ips).containsExactly("104.26.11.4", "104.26.10.4", "172.67.72.100")
    }

    @Test
    fun `Question 段不会被误当成答案`() {
        // Question 里也有 name/type，但**没有 data 字段** ——
        // 若按"扫到 type=1 就算一条"的写法，这里会解析出 0 条（正确），
        // 但若按 name 取就会把域名当 IP。这条用来钉住取值来源是 data。
        val json = """{"Question":[{"name":"api.aicu.cc","type":1}]}"""
        assertThat(AicuDns.parseDohIps(json)).isEmpty()
    }

    @Test
    fun `AAAA 与 CNAME 记录被跳过`() {
        val json = """
            {"Answer":[
              {"name":"api.aicu.cc","type":28,"data":"2606:4700::6812:b04"},
              {"name":"api.aicu.cc","type":5,"data":"some.cdn.net"},
              {"name":"api.aicu.cc","type":1,"data":"104.26.11.4"}
            ]}
        """.trimIndent()

        // 只保留 A 记录：IPv6 与 CNAME 都跳过（本项目只连 IPv4）
        assertThat(AicuDns.parseDohIps(json)).containsExactly("104.26.11.4")
    }

    @Test
    fun `空响应与非法响应返回空列表而不是抛异常`() {
        assertThat(AicuDns.parseDohIps("")).isEmpty()
        assertThat(AicuDns.parseDohIps("   ")).isEmpty()
        // DoH 服务异常时可能返回 HTML 404 页 —— 不能崩
        assertThat(AicuDns.parseDohIps("<html><body>404</body></html>")).isEmpty()
        assertThat(AicuDns.parseDohIps("{}")).isEmpty()
    }

    @Test
    fun `重复 IP 去重`() {
        val json = """
            {"Answer":[
              {"name":"x","type":1,"data":"104.26.11.4"},
              {"name":"x","type":1,"data":"104.26.11.4"}
            ]}
        """.trimIndent()
        assertThat(AicuDns.parseDohIps(json)).containsExactly("104.26.11.4")
    }

    // ================= IP 校验（本次接入的核心）=================

    @Test
    fun `Cloudflare 段被识别为真实 IP`() {
        assertThat(AicuDns.isCloudflareAicuIp("104.26.11.4")).isTrue()
        assertThat(AicuDns.isCloudflareAicuIp("104.26.10.4")).isTrue()
        assertThat(AicuDns.isCloudflareAicuIp("172.67.72.100")).isTrue()
    }

    @Test
    fun `实测到的投毒 IP 全部被识别`() {
        // 这三个是实测本机 DNS 解析 aicu.cc 时拿到的结果，
        // 分属 Dropbox / Facebook / Twitter / Verio 的网段。
        assertThat(AicuDns.isPoisonedIp("75.126.164.178")).isTrue()
        assertThat(AicuDns.isPoisonedIp("69.63.176.59")).isTrue()
        assertThat(AicuDns.isPoisonedIp("199.59.150.12")).isTrue()
    }

    @Test
    fun `投毒 IP 不被当作真实 IP`() {
        // 关键：投毒 IP 既不合法也不是 Cloudflare 段
        assertThat(AicuDns.isCloudflareAicuIp("199.59.150.12")).isFalse()
        assertThat(AicuDns.isCloudflareAicuIp("75.126.164.178")).isFalse()
    }

    @Test
    fun `validAicuIps 过滤掉投毒结果`() {
        // 模拟"备用 DoH 返回污染结果"这一实测场景：
        // 混着真实 IP 与投毒 IP 时，只能留下真实的。
        val mixed = listOf("199.59.150.12", "104.26.11.4", "75.126.164.178")
        assertThat(AicuDns.validAicuIps(mixed)).containsExactly("104.26.11.4")
    }

    @Test
    fun `validAicuIps 在全是投毒结果时返回空`() {
        // 返回空 → 调用方会走到 KNOWN_GOOD 兜底。
        // 如果这里"放行"了投毒 IP，就会拿污染地址去连（连接超时）。
        val allPoisoned = listOf("199.59.150.12", "69.63.176.59")
        assertThat(AicuDns.validAicuIps(allPoisoned)).isEmpty()
    }

    @Test
    fun `validAicuIps 去重并清理空白`() {
        val withDupes = listOf(" 104.26.11.4 ", "104.26.11.4", "104.26.10.4")
        assertThat(AicuDns.validAicuIps(withDupes))
            .containsExactly("104.26.11.4", "104.26.10.4")
            .inOrder()
    }

    // ================= 兜底 =================

    @Test
    fun `KNOWN_GOOD 全部是 Cloudflare 段且不含投毒 IP`() {
        // 兜底列表本身必须干净 —— 它是最后一道防线，
        // 里面混进一个投毒 IP 就等于"所有 DoH 都失败时必然连不上"。
        assertThat(AicuDns.KNOWN_GOOD).isNotEmpty()
        AicuDns.KNOWN_GOOD.forEach { ip ->
            assertThat(AicuDns.isCloudflareAicuIp(ip)).isTrue()
            assertThat(AicuDns.isPoisonedIp(ip)).isFalse()
        }
    }

    @Test
    fun `兜底列表与实测三台一致`() {
        // 实测这三台都返回 HTTP 200，可轮询
        assertThat(AicuDns.KNOWN_GOOD)
            .containsExactly("104.26.11.4", "104.26.10.4", "172.67.72.100")
    }

    @Test
    fun `只接管 aicu 域名`() {
        assertThat(AicuDns.AICU_HOSTS).containsExactly(
            "api.aicu.cc",
            "www.aicu.cc",
            "worker.aicu.cc",
        )
        // B 站域名绝不能被接管 —— 走 DoH 会徒增延迟且无收益
        assertThat(AicuDns.AICU_HOSTS).doesNotContain("api.bilibili.com")
    }

    @Test
    fun `DoH 引导 IP 是硬编码的而不是域名`() {
        // ⚠️ 这是整个方案的命门：引导必须用 IP，
        // 写域名就又被投毒了，等于绕了个圈子回到起点。
        val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        AicuDns.DOH_PRIMARY_IPS.forEach {
            assertThat(ipv4.matches(it)).isTrue()
        }
        AicuDns.DOH_BACKUP_IPS.forEach {
            assertThat(ipv4.matches(it)).isTrue()
        }
    }

    @Test
    fun `两个 DoH 服务商的路径不同且都写对`() {
        // 实测：腾讯是 /dns-query，阿里是 /resolve。
        // 写错路径会拿到 HTML 404 → 解析出 0 个 IP → 静默失效。
        assertThat(AicuDns.DOH_PRIMARY_PATH).isEqualTo("/dns-query")
        assertThat(AicuDns.DOH_BACKUP_PATH).isEqualTo("/resolve")
    }

    // ================= 跳转 URL 映射（第二个静默失效高发区）=================

    @Test
    fun `视频评论跳视频页`() {
        val url = AicuRepository.replyTargetUrl(dynType = 1, oid = "116968233375300", rpid = "310971854512")
        assertThat(url).isEqualTo("https://www.bilibili.com/video/av116968233375300#reply310971854512")
    }

    @Test
    fun `专栏评论跳专栏页而不是视频页`() {
        val url = AicuRepository.replyTargetUrl(dynType = 12, oid = "12345", rpid = "999")
        // ⚠️ 写死 /video/av 是典型错误：专栏 id 拼进视频路径 → 404
        assertThat(url).isEqualTo("https://www.bilibili.com/read/cv12345#reply999")
        assertThat(url).doesNotContain("/video/")
    }

    @Test
    fun `动态评论跳 t 站而不是主站视频页`() {
        val url = AicuRepository.replyTargetUrl(dynType = 17, oid = "67890", rpid = "111")
        assertThat(url).isEqualTo("https://t.bilibili.com/67890#reply111")
        assertThat(url).doesNotContain("www.bilibili.com")
    }

    @Test
    fun `未知类型走兜底并带 pageType`() {
        val url = AicuRepository.replyTargetUrl(dynType = 11, oid = "555", rpid = "666")
        // 11 → pageType 2
        assertThat(url).isEqualTo("https://t.bilibili.com/555?type=2#reply666")
    }

    @Test
    fun `oid 为空时返回空串而不是坏链接`() {
        // UI 据此禁用点击 —— 返回坏链接会让用户点进 404
        assertThat(AicuRepository.replyTargetUrl(dynType = 1, oid = "", rpid = "1")).isEmpty()
        assertThat(AicuRepository.replyTargetUrl(dynType = 12, oid = "", rpid = "1")).isEmpty()
    }

    @Test
    fun `rpid 为空时不带锚点但仍可跳转`() {
        val url = AicuRepository.replyTargetUrl(dynType = 1, oid = "100", rpid = "")
        assertThat(url).isEqualTo("https://www.bilibili.com/video/av100")
        assertThat(url).doesNotContain("#reply")
    }

    @Test
    fun `pageType 映射表与实测一致`() {
        // 实测来自 aicu 前端：11→2、1→8、12→64、14→256
        assertThat(AicuRepository.pageTypeFor(11)).isEqualTo(2)
        assertThat(AicuRepository.pageTypeFor(1)).isEqualTo(8)
        assertThat(AicuRepository.pageTypeFor(12)).isEqualTo(64)
        assertThat(AicuRepository.pageTypeFor(14)).isEqualTo(256)
    }

    @Test
    fun `楼中楼链接用 h5 子评论页`() {
        val url = AicuRepository.nestedReplyUrl(oid = "100", dynType = 1, rpid = "200")
        assertThat(url).isEqualTo("https://www.bilibili.com/h5/comment/sub?oid=100&pageType=8&root=200")
    }

    @Test
    fun `楼中楼链接在参数缺失时返回空串`() {
        assertThat(AicuRepository.nestedReplyUrl("", 1, "200")).isEmpty()
        assertThat(AicuRepository.nestedReplyUrl("100", 1, "")).isEmpty()
    }

    // ================= 弹幕时间轴（毫秒坑）=================

    @Test
    fun `progress 毫秒换算成时间轴`() {
        // 实测值：progress=20639 是 20.6 秒，不是 20639 秒
        assertThat(AicuRepository.formatDanmakuTime(20639L)).isEqualTo("0:20")
    }

    @Test
    fun `超过一分钟的弹幕时间轴进位正确`() {
        assertThat(AicuRepository.formatDanmakuTime(65_000L)).isEqualTo("1:05")
        assertThat(AicuRepository.formatDanmakuTime(600_000L)).isEqualTo("10:00")
    }

    @Test
    fun `秒数补零`() {
        assertThat(AicuRepository.formatDanmakuTime(5_000L)).isEqualTo("0:05")
    }

    @Test
    fun `负值与零不产生负数时间轴`() {
        assertThat(AicuRepository.formatDanmakuTime(0L)).isEqualTo("0:00")
        assertThat(AicuRepository.formatDanmakuTime(-1000L)).isEqualTo("0:00")
    }

    // ================= 最小 JSON 解析（AicuApi）=================

    private val api = AicuApi()

    @Test
    fun `取顶层字符串字段`() {
        val json = """{"code":0,"message":"ok","ttl":1}"""
        assertThat(api.stringField(json, "message")).isEqualTo("ok")
    }

    @Test
    fun `取整数字段`() {
        val json = """{"code":-419,"message":"排队凭据无效"}"""
        assertThat(api.intField(json, "code")).isEqualTo(-419)
    }

    @Test
    fun `字符串形态的数字也能取到`() {
        // 实测：getlivedm 的 roomid / upuid / uid **全是字符串**，
        // 用强类型 optLong 会恒得 0（"房间号永远显示 0"的成因）
        val json = """{"roominfo":{"roomid":"662240","upuid":"548076"}}"""
        assertThat(api.longField(json, "roomid")).isEqualTo(662240L)
        assertThat(api.longField(json, "upuid")).isEqualTo(548076L)
    }

    @Test
    fun `字段缺失返回 null 而不是 0`() {
        // 必须区分"没有这个字段"和"值是 0" ——
        // 混同会让"未请求总数"(-1) 与"真的 0 条"无法区分
        val json = """{"code":0}"""
        assertThat(api.intField(json, "all_count")).isNull()
        assertThat(api.stringField(json, "ticket")).isNull()
        assertThat(api.longField(json, "time")).isNull()
    }

    @Test
    fun `嵌套对象按花括号配平截取`() {
        val json = """{"data":{"ticket":"abc","status":"ready"},"ttl":1}"""
        val data = api.jsonField(json, "data")
        assertThat(data).isNotNull()
        assertThat(api.stringField(data!!, "ticket")).isEqualTo("abc")
        assertThat(api.stringField(data, "status")).isEqualTo("ready")
    }

    @Test
    fun `字符串里的花括号不破坏配平`() {
        // 评论正文里出现 { } 是常态，配平必须跳过字符串区间
        val json = """{"data":{"message":"带 { 花括号 } 的评论","code":0}}"""
        val data = api.jsonField(json, "data")!!
        assertThat(api.stringField(data, "message")).isEqualTo("带 { 花括号 } 的评论")
    }

    @Test
    fun `转义引号不被截断`() {
        // 用户会发带引号的评论 → 接口返回 \" 形态
        val json = """{"message":"他说\"你好\""}"""
        assertThat(api.stringField(json, "message")).isEqualTo("""他说"你好"""")
    }

    @Test
    fun `反转义换行与制表符`() {
        assertThat(api.unescapeJson("""a\nb""")).isEqualTo("a\nb")
        assertThat(api.unescapeJson("""a\tb""")).isEqualTo("a\tb")
        assertThat(api.unescapeJson("""a\\b""")).isEqualTo("""a\b""")
        assertThat(api.unescapeJson("""a\u4e2db""")).isEqualTo("a中b")
    }

    @Test
    fun `无转义字符时原样返回`() {
        assertThat(api.unescapeJson("普通文本")).isEqualTo("普通文本")
    }

    @Test
    fun `数组按顶层元素切分`() {
        val json = """{"replies":[{"rpid":"1","message":"a"},{"rpid":"2","message":"b"}]}"""
        val arr = api.jsonArray(json, "replies")
        val items = api.splitArray(arr)
        assertThat(items).hasSize(2)
        assertThat(api.stringField(items[0], "rpid")).isEqualTo("1")
        assertThat(api.stringField(items[1], "rpid")).isEqualTo("2")
    }

    @Test
    fun `空数组与缺失数组返回空列表`() {
        assertThat(api.splitArray(api.jsonArray("""{"replies":[]}""", "replies"))).isEmpty()
        assertThat(api.splitArray(api.jsonArray("""{"code":0}""", "replies"))).isEmpty()
        assertThat(api.splitArray(null)).isEmpty()
    }

    @Test
    fun `嵌套对象的数组元素不被拆开`() {
        // 每个评论里都可能有 parent / dyn 子对象，
        // 按最内层花括号切会把一条评论切成好几段
        val json = """
            {"replies":[
              {"rpid":"1","parent":{"rootid":"9","parentid":"8"},"dyn":{"oid":"100","type":1}},
              {"rpid":"2"}
            ]}
        """.trimIndent()

        val items = api.splitArray(api.jsonArray(json, "replies"))
        assertThat(items).hasSize(2)
        assertThat(api.stringField(api.jsonField(items[0], "parent")!!, "rootid")).isEqualTo("9")
        assertThat(api.intField(api.jsonField(items[0], "dyn")!!, "type")).isEqualTo(1)
    }

    // ================= ticket 失效判定 =================

    @Test
    fun `识别 -419 为排队凭据失效`() {
        // 实测：不带 ticket → {"code":-419,"message":"排队凭据无效或已过期，请重新发起查询。"}
        val json = """{"code":-419,"message":"排队凭据无效或已过期，请重新发起查询。"}"""
        assertThat(api.isTicketExpired(json)).isTrue()
    }

    @Test
    fun `正常响应不误判为失效`() {
        assertThat(api.isTicketExpired("""{"code":0,"data":{}}""")).isFalse()
        assertThat(api.isTicketExpired("""{"code":-403,"message":"暂时关闭"}""")).isFalse()
    }

    // ================= 查询值编码 =================

    @Test
    fun `中文关键词被百分号编码`() {
        // keyword 可能是中文，直接拼进 URL 会被 OkHttp 拒
        val encoded = api.encodeQueryValue("测试")
        assertThat(encoded).isEqualTo("%E6%B5%8B%E8%AF%95")
        assertThat(encoded).doesNotContain("测试")
    }

    @Test
    fun `空格编码为百分号二十而不是加号`() {
        // URLEncoder 会编成 `+`，但 query 里 `+` 是字面加号 —— 语义不同
        val encoded = api.encodeQueryValue("a b")
        assertThat(encoded).isEqualTo("a%20b")
        assertThat(encoded).isNotEqualTo("a+b")
    }

    @Test
    fun `纯数字与 UUID 不被改动`() {
        // UID 与 ticket 是高频参数，编码后必须仍是原值
        assertThat(api.encodeQueryValue("12345")).isEqualTo("12345")
        assertThat(api.encodeQueryValue("a8843d64-1234-5678-9abc-c8cecbe42608"))
            .isEqualTo("a8843d64-1234-5678-9abc-c8cecbe42608")
    }
}
