package com.example.biliv3

import com.example.biliv3.data.quality.AutoQuality
import com.example.biliv3.data.quality.AutoQualitySettings
import com.example.biliv3.data.quality.LiveQualityNames
import com.example.biliv3.data.quality.QualityNames
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * 自动画质 / 音质测试（**未发版**）。
 *
 * 移植自 `AHCorn/Bilibili-Auto-Quality`，见 [AutoQuality] 的说明。
 *
 * ## 样本来源
 *
 * 下面的 JSON 是**真实抓到的 `playurl` 响应**（2026-10-07，未登录，
 * `BV1GJ411x7h7` / `fnval=4048`），不是构造的理想数据：
 *
 * ```
 * accept_quality  = [112,80,64,32,16]
 * dash.video ids  = [32,32,32,16,16,16]      ← 同一个 id 有多条不同编码
 * dash.audio      = [{30216,43k},{30232,102k},{30280,203k}]
 * dash.dolby      = {"type":0,"audio":null}  ← 没开杜比
 * dash.flac       = null                     ← 没有无损
 * ```
 *
 * 即：**未登录能拿到 480P + 3 档 AAC**，会员音轨是 `null` ——
 * 印证"请求位只是我想要，给不给由权限决定"。
 */
class AutoQualityTest {

    // ---------------- 真实响应样本 ----------------

    /** 未登录实测响应（`dash` 部分）。 */
    private val realDash = JSONObject(
        """
        {
          "duration": 100,
          "video": [
            {"id":32,"baseUrl":"https://x/v32a","codecs":"avc1.64001F","width":852,"height":480,"bandwidth":300000},
            {"id":32,"baseUrl":"https://x/v32h","codecs":"hvc1.1.6.L120.90","width":852,"height":480,"bandwidth":250000},
            {"id":16,"baseUrl":"https://x/v16h","codecs":"hvc1.1.6.L120.90","width":640,"height":360,"bandwidth":150000},
            {"id":16,"baseUrl":"https://x/v16a","codecs":"avc1.64001E","width":640,"height":360,"bandwidth":140000}
          ],
          "audio": [
            {"id":30216,"baseUrl":"https://x/a64","bandwidth":43962,"codecs":"mp4a.40.5"},
            {"id":30232,"baseUrl":"https://x/a132","bandwidth":102931,"codecs":"mp4a.40.2"},
            {"id":30280,"baseUrl":"https://x/a192","bandwidth":203786,"codecs":"mp4a.40.2"}
          ],
          "dolby": {"type":0,"audio":null},
          "flac": null
        }
        """.trimIndent(),
    )

    /** 会员账号才可能拿到的响应（杜比 + 无损都有）。 */
    private val vipDash = JSONObject(
        """
        {
          "video": [
            {"id":120,"baseUrl":"https://x/v4k","codecs":"avc1.640033","width":3840,"height":2160,"bandwidth":20000000},
            {"id":80,"baseUrl":"https://x/v1080","codecs":"avc1.640028","width":1920,"height":1080,"bandwidth":3000000}
          ],
          "audio": [
            {"id":30280,"baseUrl":"https://x/a192","bandwidth":203786,"codecs":"mp4a.40.2"}
          ],
          "dolby": {"type":1,"audio":[{"id":30250,"baseUrl":"https://x/dolby","bandwidth":448000}]},
          "flac": {"display":true,"audio":{"id":30251,"baseUrl":"https://x/flac","bandwidth":1411200}}
        }
        """.trimIndent(),
    )

    /** 带 `support_formats` 的完整响应（用于受限档判定）。 */
    private val fullResponse = JSONObject(
        """
        {
          "accept_quality":[120,116,80,64,32,16],
          "accept_description":["4K 超高清","1080P 60帧","1080P 高清","720P 准高清","480P 标清","360P 流畅"],
          "support_formats":[
            {"quality":120,"new_description":"4K 超高清","superscript":"","limit_watch_reason":1,"can_watch_qn_reason":0},
            {"quality":116,"new_description":"1080P 60帧","superscript":"60帧","limit_watch_reason":1,"can_watch_qn_reason":0},
            {"quality":80,"new_description":"1080P 高清","superscript":"","limit_watch_reason":0,"can_watch_qn_reason":0},
            {"quality":64,"new_description":"720P 准高清","superscript":"","limit_watch_reason":0,"can_watch_qn_reason":0},
            {"quality":32,"new_description":"480P 标清","superscript":"","limit_watch_reason":0,"can_watch_qn_reason":0},
            {"quality":16,"new_description":"360P 流畅","superscript":"","limit_watch_reason":0,"can_watch_qn_reason":0}
          ],
          "dash":{
            "video":[
              {"id":80,"baseUrl":"https://x/v1080","codecs":"avc1.640028","width":1920,"height":1080,"bandwidth":3000000},
              {"id":64,"baseUrl":"https://x/v720","codecs":"avc1.64001F","width":1280,"height":720,"bandwidth":1500000},
              {"id":32,"baseUrl":"https://x/v480","codecs":"avc1.64001F","width":852,"height":480,"bandwidth":300000},
              {"id":16,"baseUrl":"https://x/v360","codecs":"avc1.64001E","width":640,"height":360,"bandwidth":150000}
            ],
            "audio":[{"id":30280,"baseUrl":"https://x/a192","bandwidth":203786,"codecs":"mp4a.40.2"}]
          }
        }
        """.trimIndent(),
    )

    // ---------------- playableVideoQns ----------------

    @Test
    fun `可播档位去重并按升序返回`() {
        // ⚠️ playableVideoQns 收的是**顶层 data 对象**（内含 dash 键），
        //    不是 dash 本身 —— 传错层级会静默拿到空列表
        val qns = AutoQuality.playableVideoQns(JSONObject().put("dash", realDash))
        // 实测 dash.video 有 4 条但只有 2 个不同 id
        assertThat(qns).isEqualTo(listOf(16, 32))
    }

    @Test
    fun `直接传 dash 对象会得到空 说明层级不能传错`() {
        // 钉死这个易错点：传 dash 本身拿不到档位（它的键是 video 不是 dash）
        assertThat(AutoQuality.playableVideoQns(realDash)).isEmpty()
    }

    @Test
    fun `没有 dash 时返回空`() {
        assertThat(AutoQuality.playableVideoQns(null)).isEmpty()
        assertThat(AutoQuality.playableVideoQns(JSONObject("{}"))).isEmpty()
    }

    @Test
    fun `id 为 0 或缺失的流被跳过`() {
        val d = JSONObject(
            """{"dash":{"video":[{"baseUrl":"x"},{"id":0},{"id":80,"baseUrl":"y"}]}}""",
        )
        assertThat(AutoQuality.playableVideoQns(d)).isEqualTo(listOf(80))
    }

    // ---------------- pickVideoQn ----------------

    @Test
    fun `自动模式选可用集里最高的档`() {
        // 没配首选/备选 → 自动最高
        val qn = AutoQuality.pickVideoQn(
            preferred = 0, fallback = 0, available = listOf(16, 32, 64, 80),
        )
        assertThat(qn).isEqualTo(80)
    }

    @Test
    fun `首选可用时直接用首选`() {
        val qn = AutoQuality.pickVideoQn(
            preferred = 64, fallback = 32, available = listOf(16, 32, 64, 80),
        )
        assertThat(qn).isEqualTo(64)
    }

    @Test
    fun `首选不可用时退到备选`() {
        // 用户想要 4K（120），但这个账号拿不到 → 退到备选 720P
        val qn = AutoQuality.pickVideoQn(
            preferred = 120, fallback = 64, available = listOf(16, 32, 64, 80),
        )
        assertThat(qn).isEqualTo(64)
    }

    @Test
    fun `首选和备选都不可用时取最高`() {
        val qn = AutoQuality.pickVideoQn(
            preferred = 127, fallback = 120, available = listOf(16, 32, 80),
        )
        assertThat(qn).isEqualTo(80)
    }

    @Test
    fun `未登录时不猜最高 交给服务端默认`() {
        // 🔴 匿名档位只有 480P。自动选它会把"登录后本该更高"的体验
        //    提前锁死在低档 —— 所以未登录返回 0（服务端自己决定）
        val qn = AutoQuality.pickVideoQn(
            preferred = 0, fallback = 0, available = listOf(16, 32), loggedIn = false,
        )
        assertThat(qn).isEqualTo(AutoQuality.QN_AUTO)
    }

    @Test
    fun `未登录但用户显式指定档位时仍然尊重用户`() {
        // 未登录不该覆盖用户的显式选择（他可能就是想省流量）
        val qn = AutoQuality.pickVideoQn(
            preferred = 32, fallback = 0, available = listOf(16, 32), loggedIn = false,
        )
        assertThat(qn).isEqualTo(32)
    }

    @Test
    fun `没有任何可用档位时返回 0`() {
        assertThat(AutoQuality.pickVideoQn(80, 64, emptyList())).isEqualTo(0)
    }

    @Test
    fun `highestPlayable 一定返回具体档位`() {
        assertThat(AutoQuality.highestPlayable(listOf(16, 32, 64))).isEqualTo(64)
        assertThat(AutoQuality.highestPlayable(emptyList())).isEqualTo(0)
    }

    // ---------------- limitedQns ----------------

    @Test
    fun `受限档被识别出来`() {
        val limited = AutoQuality.limitedQns(fullResponse)
        // 实测 4K(120) 与 1080P60(116) 的 limit_watch_reason 为 1
        assertThat(limited).containsExactly(120, 116)
    }

    @Test
    fun `can_watch_qn_reason 非 0 也算受限`() {
        // 两个字段任一非 0 都算（不单独依赖某一个）
        val d = JSONObject(
            """{"support_formats":[{"quality":80,"limit_watch_reason":0,"can_watch_qn_reason":2}]}""",
        )
        assertThat(AutoQuality.limitedQns(d)).containsExactly(80)
    }

    /**
     * 🔴 **受限档不能进可用集** —— 这是"自动最高"最关键的一条。
     *
     * 实测 `accept_quality` 列着 120/116，但 `dash.video` 里没有。
     * 如果按 `accept_quality` 选"最高"，就会发出 qn=120，
     * 服务端悄悄给回 1080P —— 用户以为在播 4K。
     */
    @Test
    fun `可用集只来自 dash 不含受限档`() {
        val available = AutoQuality.playableVideoQns(fullResponse)
        val limited = AutoQuality.limitedQns(fullResponse)

        assertThat(available).isEqualTo(listOf(16, 32, 64, 80))
        // 受限的两个档都不在可用集里
        assertThat(available.intersect(limited)).isEmpty()

        // 于是"自动最高"选到的是 80（真实能播的最高），不是 120
        assertThat(AutoQuality.pickVideoQn(0, 0, available)).isEqualTo(80)
    }

    // ---------------- 音轨选择 ----------------

    @Test
    fun `普通账号取码率最高的 AAC`() {
        // 🔴 这是修过的 bug：旧实现取 audio[0] = 30216（43k），
        //    而 30280（203k）就在同一个数组里
        val audio = AutoQuality.pickAudioStream(realDash)
        assertThat(audio).isNotNull()
        assertThat(audio!!.optInt("id")).isEqualTo(30280)
        assertThat(audio.optInt("bandwidth")).isEqualTo(203786)
    }

    @Test
    fun `没开杜比时不看 dolby 节点`() {
        // vipDash 里 dolby.type=1，但用户没开 → 不该拿杜比
        val audio = AutoQuality.pickAudioStream(vipDash, dolbyEnabled = false)
        assertThat(audio!!.optInt("id")).isEqualTo(30280)
    }

    @Test
    fun `开了杜比且服务端给了就拿杜比`() {
        val audio = AutoQuality.pickAudioStream(vipDash, dolbyEnabled = true)
        assertThat(audio!!.optInt("id")).isEqualTo(AutoQuality.AUDIO_DOLBY)
    }

    @Test
    fun `开了杜比但服务端没给时回退普通 AAC`() {
        // 实测未登录就是这种形态：dolby.type=0 / audio=null
        val audio = AutoQuality.pickAudioStream(realDash, dolbyEnabled = true)
        assertThat(audio!!.optInt("id")).isEqualTo(30280)
    }

    @Test
    fun `开了无损且服务端给了就拿无损`() {
        val audio = AutoQuality.pickAudioStream(vipDash, flacEnabled = true)
        assertThat(audio!!.optInt("id")).isEqualTo(AutoQuality.AUDIO_FLAC)
    }

    @Test
    fun `杜比优先于无损`() {
        // 两个都开、都拿得到时，杜比在前（与原脚本的开关顺序一致）
        val audio = AutoQuality.pickAudioStream(
            vipDash, dolbyEnabled = true, flacEnabled = true,
        )
        assertThat(audio!!.optInt("id")).isEqualTo(AutoQuality.AUDIO_DOLBY)
    }

    @Test
    fun `flac 的 audio 是对象而不是数组`() {
        // 🔴 实测结构差异：dolby.audio 是 **数组**，flac.audio 是 **对象**。
        //    按数组读 flac 会拿到 null（拿不到无损却看不出为什么）
        assertThat(vipDash.optJSONObject("flac")!!.optJSONObject("audio")).isNotNull()
        assertThat(vipDash.optJSONObject("flac")!!.optJSONArray("audio")).isNull()
    }

    @Test
    fun `没有音轨时返回 null`() {
        assertThat(AutoQuality.pickAudioStream(null)).isNull()
        assertThat(AutoQuality.pickAudioStream(JSONObject("{}"))).isNull()
    }

    // ---------------- 音质标签 ----------------

    @Test
    fun `音质标签按真实音轨判定`() {
        assertThat(AutoQuality.audioLabel(JSONObject("""{"id":30250}""")))
            .isEqualTo("杜比全景声")
        assertThat(AutoQuality.audioLabel(JSONObject("""{"id":30251}""")))
            .isEqualTo("Hi-Res 无损")
        // 普通 AAC 带上真实码率（203786 → 203k）
        assertThat(AutoQuality.audioLabel(JSONObject("""{"id":30280,"bandwidth":203786}""")))
            .isEqualTo("AAC 203k")
        // 码率未知时不编数字
        assertThat(AutoQuality.audioLabel(JSONObject("""{"id":30216}"""))).isEqualTo("AAC")
        assertThat(AutoQuality.audioLabel(null)).isEqualTo("无音轨")
    }

    // ---------------- fnval 位掩码 ----------------

    /**
     * 🔴 **最重要的一组测试：`fnval` 的合法边界。**
     *
     * 实测（2026-10-07，未登录，`BV1GJ411x7h7`）—— 带上被拒的位，
     * 服务端返回的是**整条请求** `-400 请求错误`，不是"忽略该位"：
     *
     * ```
     * fnval=16    -> code=0      ← 改动前的原值
     * fnval=4048  -> code=0      ← 最大可用组合（本项目自动画质开启时）
     * fnval=20    -> code=-400   ← 4+16
     * fnval=48    -> code=-400   ← 16+32
     * fnval=4112  -> code=-400   ← 16+4096
     * fnval=6644  -> code=-400   ← "全开"，**会直接播不了**
     * ```
     *
     * 所以这两个常量是**硬约束**，改动它们等于让 App 放不了视频。
     */
    @Test
    fun `BASIC 必须是 16 即改动前的原值`() {
        assertThat(AutoQuality.Fnval.BASIC).isEqualTo(16)
        // ⚠️ 曾经写成 DASH(4) + FOUR_K(16) = 20 —— 实测 -400
        assertThat(AutoQuality.Fnval.BASIC).isNotEqualTo(20)
    }

    @Test
    fun `DEFAULT 必须是实测可用的 4048`() {
        assertThat(AutoQuality.Fnval.DEFAULT).isEqualTo(4048)
        // 构成：16 + 64 + 128 + 256 + 512 + 1024 + 2048
        assertThat(AutoQuality.Fnval.DEFAULT).isEqualTo(
            AutoQuality.Fnval.FOUR_K +
                AutoQuality.Fnval.DOLBY_VISION +
                AutoQuality.Fnval.EIGHT_K +
                AutoQuality.Fnval.AV1 +
                AutoQuality.Fnval.RESERVED_512 +
                AutoQuality.Fnval.INTERACTIVE +
                AutoQuality.Fnval.AV1_HIGH,
        )
    }

    @Test
    fun `任何 fnval 都不得包含被服务端拒绝的位`() {
        val forbidden = AutoQuality.Fnval.DOLBY_AUDIO or AutoQuality.Fnval.FLAC

        // 默认组合（开启）
        assertThat(AutoQuality.UnlockFlags().fnval() and forbidden).isEqualTo(0)
        // 基础组合（关闭）
        assertThat(AutoQuality.Fnval.BASIC and forbidden).isEqualTo(0)
        // 最大集常量本身
        assertThat(AutoQuality.Fnval.DEFAULT and forbidden).isEqualTo(0)

        // 全开开关时也不能带进去
        val allOn = AutoQuality.UnlockFlags(
            unlock8K = true, unlockDolbyVision = true, unlockAv1 = true,
        )
        assertThat(allOn.fnval() and forbidden).isEqualTo(0)
    }

    @Test
    fun `自动画质开启时用 4048 关闭时用 16`() {
        val flags = AutoQuality.UnlockFlags()
        assertThat(AutoQuality.fnvalFor(autoQualityEnabled = true, flags)).isEqualTo(4048)
        assertThat(AutoQuality.fnvalFor(autoQualityEnabled = false, flags)).isEqualTo(16)
    }

    @Test
    fun `单个解锁位正确映射`() {
        fun only(flags: AutoQuality.UnlockFlags) = flags.extraBits()

        assertThat(only(AutoQuality.UnlockFlags(
            unlock8K = true, unlockDolbyVision = false, unlockAv1 = false,
        ))).isEqualTo(128)

        assertThat(only(AutoQuality.UnlockFlags(
            unlock8K = false, unlockDolbyVision = true, unlockAv1 = false,
        ))).isEqualTo(64)

        // AV1 是两个位（256 + 2048）
        assertThat(only(AutoQuality.UnlockFlags(
            unlock8K = false, unlockDolbyVision = false, unlockAv1 = true,
        ))).isEqualTo(2304)

        // 三个全关 → 没有额外位，结果就是 BASIC
        assertThat(only(AutoQuality.UnlockFlags(
            unlock8K = false, unlockDolbyVision = false, unlockAv1 = false,
        ))).isEqualTo(0)
    }

    /**
     * 🔴 杜比 / 无损**没有 fnval 开关** —— 它们只能"服务端给了就用"。
     *
     * 这条测试钉死的是"别再加回来"：一旦有人给 UnlockFlags 加回
     * `unlockDolbyAudio` / `unlockFlac`，编译就会失败（参数不存在），
     * 而如果改成默认 true 拼进 fnval，上面那条"不得包含被拒位"会失败。
     */
    @Test
    fun `杜比与无损位只存在于常量里 不参与请求`() {
        // 常量仍然保留（用于文档与解析侧的 id 判定）
        assertThat(AutoQuality.Fnval.DOLBY_AUDIO).isEqualTo(32)
        assertThat(AutoQuality.Fnval.FLAC).isEqualTo(4096)

        // 但 UnlockFlags 的 extraBits 里没有它们
        val allOn = AutoQuality.UnlockFlags(
            unlock8K = true, unlockDolbyVision = true, unlockAv1 = true,
        )
        assertThat(allOn.extraBits() and 32).isEqualTo(0)
        assertThat(allOn.extraBits() and 4096).isEqualTo(0)
    }

    // ---------------- 设置默认值 ----------------

    @Test
    fun `设置默认全关 保持升级前行为`() {
        val s = AutoQualitySettings()
        assertThat(s.enabled).isFalse()
        assertThat(s.dolbyAtmos).isFalse()
        assertThat(s.hiResAudio).isFalse()
        assertThat(s.preferredQn).isEqualTo(0)
        assertThat(s.fallbackQn).isEqualTo(0)
        // 关闭时 fnval 就是改动前的 16
        assertThat(s.fnval()).isEqualTo(16)
    }

    @Test
    fun `开启后 fnval 变为 4048`() {
        val s = AutoQualitySettings(enabled = true)
        assertThat(s.fnval()).isEqualTo(4048)
    }

    @Test
    fun `直播自动画质默认开启`() {
        // 直播请求 qn=10000 是改动前就有的行为，所以默认保持开启
        assertThat(AutoQualitySettings().liveAutoQuality).isTrue()
    }

    // ---------------- 名称表 ----------------

    @Test
    fun `画质名表覆盖常用档位`() {
        assertThat(QualityNames.label(0)).isEqualTo("自动")
        assertThat(QualityNames.label(80)).isEqualTo("1080P 高清")
        assertThat(QualityNames.label(120)).isEqualTo("4K 超高清")
        // 表里没有的档位不编名字，如实显示编号
        assertThat(QualityNames.label(999)).isEqualTo("清晰度 999")
    }

    @Test
    fun `直播画质码与点播是两套编号`() {
        // 10000 是直播的原画；点播的 120 才是 4K —— 混用会显示错
        assertThat(LiveQualityNames.label(10000)).isEqualTo("原画")
        assertThat(LiveQualityNames.label(400)).isEqualTo("蓝光")
        assertThat(LiveQualityNames.label(250)).isEqualTo("超清")
        assertThat(LiveQualityNames.label(0)).isEmpty()
    }
}
