package com.example.biliv3

import com.example.biliv3.data.LiveStreamParser
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * 直播取流解析测试。
 *
 * ## 为什么这组测试重要
 *
 * 真正要用的地址是**三段拼接**：`host + base_url + extra`。
 * 少拼任何一段都**不报错**，只表现为黑屏：
 *
 * | 漏了 | 现象 |
 * |---|---|
 * | `host` | `base_url` 以 `/` 开头 → 非法 URI / 相对路径解析失败 |
 * | `extra` | 没有签名参数 → CDN 返回 **403** |
 * | `?` 重复插 | `...flv??expires=` → 同样 403 |
 *
 * 三种都无法从"黑屏"反推到"拼错了"，所以必须在这里钉死。
 *
 * ## 测试数据来源
 *
 * 下面的 JSON 是**实测响应结构的缩减版**（2026-10-06，`room_id=6`，
 * 未登录直连），保留了真实的字段名与嵌套层级，只裁剪了体积。
 */
class LiveStreamTest {

    /** 实测的完整形状（6 种组合）。 */
    private fun realShape(): JSONObject = JSONObject(
        """
        {
          "room_id": 6,
          "live_status": 1,
          "playurl_info": {
            "playurl": {
              "cid": 966624,
              "g_qn_desc": [
                {"qn": 30000, "desc": "杜比"},
                {"qn": 10000, "desc": "原画"},
                {"qn": 250, "desc": "超清"},
                {"qn": 150, "desc": "高清"}
              ],
              "stream": [
                {
                  "protocol_name": "http_stream",
                  "format": [{
                    "format_name": "flv",
                    "codec": [
                      {
                        "codec_name": "avc",
                        "current_qn": 250,
                        "accept_qn": [10000, 250, 150],
                        "base_url": "/live-bvc/966624/live_x_2500.flv?",
                        "url_info": [{
                          "host": "https://d1--cn-gotcha04.bilivideo.com",
                          "extra": "expires=1791268362&sign=abc&qn=250"
                        }]
                      },
                      {
                        "codec_name": "hevc",
                        "current_qn": 250,
                        "base_url": "/live-bvc/966624/live_x_2500_hevc.flv?",
                        "url_info": [{
                          "host": "https://d1--cn-gotcha04.bilivideo.com",
                          "extra": "expires=1791268362&sign=def"
                        }]
                      }
                    ]
                  }]
                },
                {
                  "protocol_name": "http_hls",
                  "format": [{
                    "format_name": "ts",
                    "codec": [
                      {
                        "codec_name": "hevc",
                        "current_qn": 250,
                        "accept_qn": [10000, 250, 150],
                        "base_url": "/live-bvc/676772/live_x_2500_hevc.m3u8?",
                        "url_info": [{
                          "host": "https://d1--cn-gotcha104.bilivideo.com",
                          "extra": "expires=1791268371&sign=xyz"
                        }]
                      },
                      {
                        "codec_name": "avc",
                        "current_qn": 250,
                        "accept_qn": [10000, 250, 150],
                        "base_url": "/live-bvc/676772/live_x_2500.m3u8?",
                        "url_info": [{
                          "host": "https://d1--cn-gotcha104.bilivideo.com",
                          "extra": "expires=1791268371&sign=0b0a"
                        }]
                      }
                    ]
                  }]
                }
              ]
            }
          }
        }
        """.trimIndent(),
    )

    // ---------------- 三段拼接（核心）----------------

    /**
     * ⚠️ 本文件最核心的断言：地址必须三段拼全。
     *
     * 漏 `host` 会得到以 `/` 开头的相对地址；漏 `extra` 会丢掉签名
     * → CDN 403。两种都是黑屏，且看不出是拼接问题。
     */
    @Test
    fun `地址由 host base_url extra 三段拼成`() {
        val s = LiveStreamParser.parse(realShape())
        assertThat(s.hlsUrl).isEqualTo(
            "https://d1--cn-gotcha104.bilivideo.com" +
                "/live-bvc/676772/live_x_2500.m3u8?" +
                "expires=1791268371&sign=0b0a",
        )
        assertThat(s.flvUrl).isEqualTo(
            "https://d1--cn-gotcha04.bilivideo.com" +
                "/live-bvc/966624/live_x_2500.flv?" +
                "expires=1791268362&sign=abc&qn=250",
        )
    }

    /** 地址必须是完整的 https URL（否则 ExoPlayer 解析失败）。 */
    @Test
    fun `地址是完整可用的 https 地址`() {
        val s = LiveStreamParser.parse(realShape())
        assertThat(s.hlsUrl).startsWith("https://")
        assertThat(s.hlsUrl).contains(".m3u8")
        assertThat(s.flvUrl).startsWith("https://")
        assertThat(s.flvUrl).contains(".flv")
    }

    /**
     * ⚠️ 不能重复插 `?`。
     *
     * `extra` **自带**查询串（`expires=...`），它对应 `base_url` 结尾的
     * 那个 `?`。再插一个会拼出 `...flv??expires=` → 403。
     */
    @Test
    fun `不重复插入问号`() {
        val s = LiveStreamParser.parse(realShape())
        assertThat(s.hlsUrl).doesNotContain("??")
        assertThat(s.flvUrl).doesNotContain("??")
    }

    // ---------------- 编码优选 ----------------

    /**
     * 同协议下必须**优先 avc**。
     *
     * hevc 在部分设备上没有硬解，会直接黑屏（项目已有同类结论：
     * `preferH264` 就是为这个问题设的）。
     * 实测响应里 hevc 排在 avc **前面**，所以"取第一个"是错的。
     */
    @Test
    fun `同协议下优先 avc 而不是第一个`() {
        val s = LiveStreamParser.parse(realShape())
        // hevc 的 base_url 带 _hevc 后缀；选对了就不该出现它
        assertThat(s.hlsUrl).doesNotContain("_hevc")
        assertThat(s.flvUrl).doesNotContain("_hevc")
    }

    // ---------------- 画质 ----------------

    /** 画质码与中文名（来自 `g_qn_desc`）。 */
    @Test
    fun `解析画质码与中文名`() {
        val s = LiveStreamParser.parse(realShape())
        assertThat(s.quality).isEqualTo(250)
        assertThat(s.qualityLabel).isEqualTo("超清")
        assertThat(s.acceptQuality).containsExactly(10000, 250, 150).inOrder()
    }

    /** 画质码不在 `g_qn_desc` 里时回退成"清晰度 N"，而不是空串。 */
    @Test
    fun `未知画质码回退为可读文案`() {
        val j = realShape()
        // 把 g_qn_desc 清空，制造"未知画质码"
        j.getJSONObject("playurl_info").getJSONObject("playurl")
            .put("g_qn_desc", org.json.JSONArray())
        val s = LiveStreamParser.parse(j)
        assertThat(s.qualityLabel).isEqualTo("清晰度 250")
    }

    // ---------------- 容错 ----------------

    /** 没有 `playurl_info` → 空结果，不抛异常。 */
    @Test
    fun `缺少 playurl_info 返回空结果`() {
        val s = LiveStreamParser.parse(JSONObject("""{"live_status":1}"""))
        assertThat(s.playable).isFalse()
        assertThat(s.hlsUrl).isEmpty()
        assertThat(s.flvUrl).isEmpty()
    }

    @Test
    fun `null 输入返回空结果`() {
        val s = LiveStreamParser.parse(null)
        assertThat(s.playable).isFalse()
    }

    /** `stream` 为空数组 → 空结果（未开播/无权限时会这样）。 */
    @Test
    fun `空的 stream 数组返回空结果`() {
        val s = LiveStreamParser.parse(
            JSONObject(
                """{"playurl_info":{"playurl":{"stream":[]}}}""",
            ),
        )
        assertThat(s.playable).isFalse()
    }

    /** 缺 `url_info` 的 codec 被跳过，不影响其它 codec。 */
    @Test
    fun `缺少 url_info 的条目被跳过`() {
        val s = LiveStreamParser.parse(
            JSONObject(
                """
                {"playurl_info":{"playurl":{"stream":[
                  {"protocol_name":"http_hls","format":[{"format_name":"ts","codec":[
                    {"codec_name":"avc","base_url":"/a.m3u8?"},
                    {"codec_name":"avc","base_url":"/b.m3u8?",
                     "url_info":[{"host":"https://h","extra":"s=1"}]}
                  ]}]}
                ]}}}
                """.trimIndent(),
            ),
        )
        // 第一条没有 url_info 被跳过，第二条被采用
        assertThat(s.hlsUrl).isEqualTo("https://h/b.m3u8?s=1")
    }

    /** 只有 FLV、没有 HLS 时，HLS 为空但整体仍可播（走 FLV 兜底）。 */
    @Test
    fun `只有 flv 时仍可播`() {
        val s = LiveStreamParser.parse(
            JSONObject(
                """
                {"playurl_info":{"playurl":{"stream":[
                  {"protocol_name":"http_stream","format":[{"format_name":"flv","codec":[
                    {"codec_name":"avc","current_qn":250,"base_url":"/x.flv?",
                     "url_info":[{"host":"https://h","extra":"s=1"}]}
                  ]}]}
                ]}}}
                """.trimIndent(),
            ),
        )
        assertThat(s.hlsUrl).isEmpty()
        assertThat(s.flvUrl).isEqualTo("https://h/x.flv?s=1")
        assertThat(s.playable).isTrue()
    }

    /** `host` 缺失时退化为直接用 `base_url`（少数情况下它是绝对地址）。 */
    @Test
    fun `host 缺失时使用 base_url 原值`() {
        val s = LiveStreamParser.parse(
            JSONObject(
                """
                {"playurl_info":{"playurl":{"stream":[
                  {"protocol_name":"http_hls","format":[{"format_name":"ts","codec":[
                    {"codec_name":"avc","base_url":"https://abs.example.com/a.m3u8?k=1",
                     "url_info":[{"host":"","extra":""}]}
                  ]}]}
                ]}}}
                """.trimIndent(),
            ),
        )
        assertThat(s.hlsUrl).isEqualTo("https://abs.example.com/a.m3u8?k=1")
    }

    /**
     * ⚠️ 解析器必须**无状态**。
     *
     * 若把"已选到 avc"做成单例字段，第一次解析后它一直为 true，
     * 第二次解析就会跳过 avc 候选 —— 表现为"第一次进直播有画面、
     * 退出来再进就黑屏"。这条测试专门防这个。
     */
    @Test
    fun `重复解析结果一致`() {
        val a = LiveStreamParser.parse(realShape())
        val b = LiveStreamParser.parse(realShape())
        assertThat(b.hlsUrl).isEqualTo(a.hlsUrl)
        assertThat(b.flvUrl).isEqualTo(a.flvUrl)
        assertThat(b.quality).isEqualTo(a.quality)
    }
}
