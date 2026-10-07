package com.example.biliv3.data.quality

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动画质 / 音质（**未发版**，纯函数，可单测）。
 *
 * ---
 *
 * # 来源
 *
 * 移植自 `AHCorn/Bilibili-Auto-Quality`（GPL-3.0，greasyfork 486151）的
 * 「自动选择最高音画质」能力。原脚本跑在**浏览器**里，靠模拟点击
 * `.bpx-player-ctrl-quality-menu-item` 等 DOM 节点工作；本项目是
 * **原生客户端**，没有 DOM，所以**不能照抄点击逻辑** ——
 * 那会得到一堆永远点不到的 `querySelector`。
 *
 * 所以这里只移植**策略**，落地方式是**在请求参数里做**：
 *
 * | 原脚本的做法 | 本项目等价做法 |
 * |---|---|
 * | 读取画质菜单里的候选档，挑最高 | 读 `dash.video` / `support_formats` 挑最高 |
 * | 点 `.bpx-player-ctrl-quality-menu-item` | `playurl` 带 `qn=` 目标档 |
 * | 点 `.bpx-player-ctrl-flac` | `playurl` 带 `fnval` 的 FLAC 位 + 选 `dash.flac` |
 * | 点 `.bpx-player-ctrl-dolby` | `playurl` 带 `fnval` 的杜比位 + 选 `dash.dolby.audio` |
 * | 读 `localStorage.bilibili_player_force_*` 解锁 | 请求参数（见 [UnlockFlags]） |
 *
 * ---
 *
 * # 原脚本的会员判定，本项目怎么替代
 *
 * 原脚本靠**读 DOM** 判断"这个档位是不是会员专属"（`q.isVipOnly`），
 * 然后对非会员用户**跳过会员档**，只选免费里最高的。
 *
 * 本项目有**更准的信号**：`support_formats[].limit_watch_reason`
 * （见 [QualityParser] 的实测表）。所以：
 *
 * - 会员档 **不发给服务端**（发了也是悄悄降档，UI 会与实际不符）
 * - 但**保留在列表里并标记**（用户看得见自己缺什么）
 *
 * 即：**自动选"你实际拿得到的最高档"，而不是"列表里最高的档"**。
 * 这与原脚本 README 的声明一致：
 * > 脚本实现的是自动选择当前用户可选的最高音画质，
 * > 而不是让非会员用户使用会员选项。
 */
object AutoQuality {

    /** 首选档位（`qn`）。见 [AutoQualitySettings]。 */
    const val QN_AUTO = 0

    // -----------------------------------------------------------------
    // 视频档位
    // -----------------------------------------------------------------

    /**
     * 从**可用**档位里按偏好挑一个。
     *
     * ## 规则（顺序很重要）
     *
     * 1. `preferred` 在可用集里 → 直接用（用户明确要的，不干预）
     * 2. 否则 `fallback` 在可用集里 → 用它
     * 3. 否则取**可用集里最高的**（"自动最高"的兜底）
     * 4. 可用集为空 → [QN_AUTO]（让服务端自己给默认档）
     *
     * ## 🔴 `available` 必须已经排除受限档
     *
     * 传进来的必须是**能真正拿到流**的档（`QualityOption.playable`）。
     * 把受限档混进来会让"自动最高"变成"自动选一个拿不到的档"，
     * 服务端悄悄降档 —— 用户以为在播 4K，实际是 1080P。
     *
     * @param preferred 首选档（设置里配的，`0` = 不指定）
     * @param fallback 备选档（首选不可用时用，`0` = 不指定）
     * @param available 实际可播的档（**不含受限档**）
     * @param loggedIn 未登录时不"自动最高" —— 匿名档位只有 480P，
     *   自动选它会把"登录后本该更高"的体验提前锁死在低档
     */
    fun pickVideoQn(
        preferred: Int,
        fallback: Int,
        available: List<Int>,
        loggedIn: Boolean = true,
    ): Int {
        if (available.isEmpty()) return QN_AUTO

        // 1 / 2：用户显式偏好优先，且不因为是"低档"就拒绝 ——
        // 用户选 480P 就是要在弱网下省流量，自动逻辑不该推翻它
        if (preferred > 0 && available.contains(preferred)) return preferred
        if (fallback > 0 && available.contains(fallback)) return fallback

        // 3：自动最高。未登录时不猜 —— 交给服务端默认档
        if (!loggedIn) return QN_AUTO

        return available.maxOrNull() ?: QN_AUTO
    }

    /**
     * 从 `dash.video` 里挑出**最高可播档**，不考虑用户偏好。
     *
     * 用途：自动画质总开关打开但**没配首选/备选**时 ——
     * 也就是"我要最高画质"这条最朴素的需求（原脚本的默认行为）。
     *
     * ⚠️ 与 [pickVideoQn] 的区别：那个会返回 `0`（让服务端决定），
     * 这个一定返回一个**具体档位**（在 [available] 非空时）。
     * 所以调用方要按场景选：
     * - 有用户偏好 → [pickVideoQn]
     * - 纯"自动最高" → 本函数
     */
    fun highestPlayable(available: List<Int>): Int = available.maxOrNull() ?: QN_AUTO

    /**
     * 从 `dash.video` 抽出**可播档位**（升序去重）。
     *
     * ⚠️ 与 [QualityParser.playableQualities] 是同一份数据，
     * 但那个是 `private`。这里重写一份是为了让本文件**可独立单测**，
     * 且语义不同：那边返回 `qn → 分辨率`（给 UI 显示用），
     * 这里只要 `qn` 集合（给策略用）。
     *
     * 🔴 **同一 qn 有多条（不同编码）** —— 实测：
     * ```
     * id=32: [0] avc1.64001F  [1] hvc1.1.6.L120.90
     * id=16: [0] hvc1.1.6.L120.90  [1] avc1.64001E   ← 第一条是 HEVC
     * ```
     * 所以这里**只收集 id、不去重分辨率** —— 编码选择由
     * `VideoRepository.pickVideoStream` 负责（它优先 avc1）。
     */
    fun playableVideoQns(data: JSONObject?): List<Int> {
        val arr = data?.optJSONObject("dash")?.optJSONArray("video") ?: return emptyList()
        val out = LinkedHashSet<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val id = v.optInt("id", 0)
            if (id > 0) out.add(id)
        }
        return out.sorted()
    }

    /**
     * 抽"受限档"（`limit_watch_reason != 0`）—— 用于**排除**。
     *
     * ⚠️ 与 [playableVideoQns] 取交集才算真正可用：
     * 实测存在"`limit=0` 但 `dash` 里也没有"的档（服务端只列不给），
     * 那种档选了照样降档。所以最终判据是**在 dash 里**，
     * 本函数只用于 UI 标记（见 [QualityParser]）。
     */
    fun limitedQns(data: JSONObject?): Set<Int> {
        val arr = data?.optJSONArray("support_formats") ?: return emptySet()
        val out = HashSet<Int>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val qn = f.optInt("quality", 0)
            if (qn <= 0) continue
            val limit = f.optInt("limit_watch_reason", 0)
            val canWatch = f.optInt("can_watch_qn_reason", 0)
            if (limit != 0 || canWatch != 0) out.add(qn)
        }
        return out
    }

    // -----------------------------------------------------------------
    // 音质
    // -----------------------------------------------------------------

    /**
     * 音轨 id（B 站的 `dash.audio[].id`）。
     *
     * | id | 码率 | 说明 |
     * |---|---|---|
     * | 30216 | 64k | 最低 |
     * | 30232 | 132k | 中 |
     * | 30280 | 192k | 高（默认给到的最高 AAC）|
     * | 30250 | — | **杜比全景声**（`dash.dolby.audio[]`，会员）|
     * | 30251 | — | **Hi-Res 无损**（`dash.flac.audio`，会员）|
     *
     * 🔴 实测（未登录，`fnval=4048`）：
     * ```
     * dash.audio = [{30216,43k},{30232,102k},{30280,203k}]
     * dash.dolby = {"type":0,"audio":null}     ← 没开杜比
     * dash.flac  = null                        ← 没有无损
     * ```
     * 即**会员音轨不是"没请求到"，是服务端按权限不给** ——
     * 与视频档位同一条规律。
     */
    const val AUDIO_DOLBY = 30250

    /** Hi-Res 无损音轨 id。 */
    const val AUDIO_FLAC = 30251

    /**
     * 挑音轨。
     *
     * ## 顺序
     *
     * 1. 开了杜比 且 `dash.dolby.audio` 非空 → 杜比
     * 2. 开了无损 且 `dash.flac.audio` 非空 → 无损
     * 3. 否则在 `dash.audio` 里取 **`bandwidth` 最大**的一条
     *
     * ## 🔴 第 3 条是修过的 bug，不是"顺手优化"
     *
     * 旧实现取 `audio[0]`，实测第一条是 **30216（43k）** —— 最低码率。
     * 用户听到的一直是 64k 音质，而 192k 就在同一个数组里。
     *
     * ## ⚠️ 会员音轨拿不到时**静默回退**，不报错
     *
     * 非会员请求 `fnval` 带杜比位，服务端照样只给普通 AAC。
     * 这不是失败（用户本来就拿不到），所以**不弹错误** ——
     * 由设置页如实标注"需要大会员"。
     *
     * @param dolbyEnabled 用户是否开启杜比全景声
     * @param flacEnabled 用户是否开启 Hi-Res 无损
     * @return 选中的音轨对象；没有任何音轨时 null
     */
    fun pickAudioStream(
        dash: JSONObject?,
        dolbyEnabled: Boolean = false,
        flacEnabled: Boolean = false,
    ): JSONObject? {
        if (dash == null) return null

        if (dolbyEnabled) {
            // 实测结构：dash.dolby = {type: 0, audio: null | [{...}]}
            // type != 0 才是"这个视频真的有杜比音轨"
            val dolby = dash.optJSONObject("dolby")
            if (dolby != null && dolby.optInt("type", 0) != 0) {
                dolby.optJSONArray("audio")?.optJSONObject(0)?.let { return it }
            }
        }

        if (flacEnabled) {
            // 实测结构：dash.flac = null | {display: true, audio: {...}}
            // ⚠️ flac.audio 是**对象**不是数组（与 dolby.audio 不同）
            val flac = dash.optJSONObject("flac")
            flac?.optJSONObject("audio")?.let { return it }
        }

        // 普通 AAC：取码率最高的
        val arr = dash.optJSONArray("audio") ?: return null
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        return all.maxByOrNull { it.optInt("bandwidth", 0) } ?: all.firstOrNull()
    }

    /**
     * 音轨的中文标签（给 UI 显示"现在播的是什么音质"）。
     *
     * 用**真实拿到的** `id` / `bandwidth` 判断，不看用户开了什么开关 ——
     * 开了杜比但没拿到，就该显示普通 AAC（否则又是一次"UI 与实际不符"）。
     */
    fun audioLabel(audio: JSONObject?): String {
        if (audio == null) return "无音轨"
        return when (audio.optInt("id", 0)) {
            AUDIO_DOLBY -> "杜比全景声"
            AUDIO_FLAC -> "Hi-Res 无损"
            else -> {
                val kbps = audio.optInt("bandwidth", 0) / 1000
                if (kbps > 0) "AAC ${kbps}k" else "AAC"
            }
        }
    }

    // -----------------------------------------------------------------
    // fnval 位掩码
    // -----------------------------------------------------------------

    /**
     * `fnval` 是**位掩码**，不是枚举 —— 想要多项能力就**按位或**。
     *
     * | 位 | 值 | 含义 | 实测 |
     * |---|---|---|---|
     * | 0 | 1 | mp4 | — |
     * | 1 | 2 | 请求 `durl` | — |
     * | 2 | 4 | 请求 `dash` | ❌ **单独带 → -400** |
     * | 3 | 8 | HDR | 未采用（本项目无 HDR 渲染链路）|
     * | 4 | 16 | **4K** | ✅ 本项目一直用它 |
     * | 5 | 32 | 杜比音频 | ❌ **→ -400** |
     * | 6 | 64 | 杜比视界 | ✅ |
     * | 7 | 128 | 8K | ✅ |
     * | 8 | 256 | AV1 | ✅ |
     * | 9 | 512 | — | ✅ |
     * | 10 | 1024 | 互动视频 | ✅ |
     * | 11 | 2048 | AV1（另一档）| ✅ |
     * | 12 | 4096 | 无损 FLAC | ❌ **→ -400** |
     *
     * ---
     *
     * # 🔴 实测（2026-10-07，未登录，`BV1GJ411x7h7`）
     *
     * **不是所有位都能带。** 带上被拒的位，服务端**整条请求**返回
     * `-400 请求错误`（不是"忽略该位"）：
     *
     * ```
     * fnval=16    -> code=0     video=[32,16]   ← 正常
     * fnval=4048  -> code=0     video=[32,16]   ← 正常（最大可用组合）
     * fnval=20    -> code=-400                  ← 4+16：单独带 DASH 位就被拒
     * fnval=48    -> code=-400                  ← 16+32：杜比音频位被拒
     * fnval=4080  -> code=-400                  ← 4048+32
     * fnval=4112  -> code=-400                  ← 16+4096：无损位被拒
     * fnval=6644  -> code=-400                  ← 我最初写的"全开"值，**会直接播不了**
     * ```
     *
     * ⚠️ **这个坑非常隐蔽**：位掩码看起来"多带几个只是多要点东西"，
     * 但实际是**全有或全无** —— 一个坏位让整条取流失败，
     * 表现是**所有视频都放不了**（而不是"画质没提升"）。
     *
     * ## 为什么 `4048` 是"全开"而不是 4095
     *
     * `4048 = 16+64+128+256+512+1024+2048` —— 恰好是**排除 32 与 4096**
     * 之后的全部有效位。这也是 B 站网页播放器实际用的值。
     *
     * ## 杜比 / 无损怎么办
     *
     * 它们**不能靠 `fnval` 请求**（位被拒）。但**解析仍然保留**：
     * 若服务端因为账号权限主动返回 `dash.dolby.audio` / `dash.flac.audio`，
     * [pickAudioStream] 照样会选中它们。见 [UnlockFlags] 的说明。
     */
    object Fnval {
        const val MP4 = 1
        const val DURL = 2

        /** ⚠️ 实测**不能单独带**（→ -400）。保留常量只为文档完整。 */
        const val DASH = 4

        const val HDR = 8

        /** 4K。本项目原本只带这一个。 */
        const val FOUR_K = 16

        /** ⚠️ 实测**不能带**（→ -400）。见类文档的实测表。 */
        const val DOLBY_AUDIO = 32

        const val DOLBY_VISION = 64
        const val EIGHT_K = 128
        const val AV1 = 256
        const val RESERVED_512 = 512
        const val INTERACTIVE = 1024
        const val AV1_HIGH = 2048

        /** ⚠️ 实测**不能带**（→ -400）。见类文档的实测表。 */
        const val FLAC = 4096

        /**
         * 本项目默认组合 —— **实测可用的最大集**。
         *
         * `16 + 64 + 128 + 256 + 512 + 1024 + 2048 = 4048`
         *
         * ⚠️ **刻意不含**：
         * - `32`（杜比音频）/ `4096`（无损）—— 实测带上是 `-400`
         * - `8`（HDR）—— 本项目没有 HDR 色调映射链路，拿了也是错色
         * - `4`（DASH）—— 实测单独带就 `-400`；`16` 已经隐含 DASH
         */
        const val DEFAULT = FOUR_K or DOLBY_VISION or EIGHT_K or AV1 or
            RESERVED_512 or INTERACTIVE or AV1_HIGH

        /**
         * 基础组合 —— 给"自动画质总开关关闭"时用。
         *
         * 🔴 **必须就是 `16`**：这是本项目改动前一直用的值，
         * 也是唯一被长期验证过的值。写成 `DASH + FOUR_K = 20` 会
         * 直接 `-400`（实测），把"关掉新功能"变成"整个 App 播不了"。
         */
        const val BASIC = FOUR_K
    }

    /**
     * 解锁开关（对应原脚本的「解锁设置」面板）。
     *
     * ## 🔴 原脚本靠改 `localStorage`，本项目靠改 `fnval` —— 但只有一部分能改
     *
     * 原脚本写的是：
     * ```js
     * localStorage['bilibili_player_force_DolbyAtmos&8K'] = 1
     * localStorage['bilibili_player_force_DolbyAtmos&8K&HDR'] = 1
     * localStorage.bilibili_player_force_hdr = 1
     * ```
     * 那是**网页播放器自己的开关**（决定播放器 UI 上要不要显示杜比按钮），
     * 不是 API 参数。原生客户端没有 localStorage，最接近的等价物是 `fnval` 位 ——
     * **但实测只有一部分位能被服务端接受**（见 [Fnval] 的实测表）。
     *
     * ## ⚠️ 因此这里只有 4 个开关，不是原面板的 6 个
     *
     * | 原脚本开关 | 本项目 | 为什么 |
     * |---|---|---|
     * | 8K | ✅ [unlock8K] | `fnval` 128 实测可用 |
     * | 杜比视界 | ✅ [unlockDolbyVision] | `fnval` 64 实测可用 |
     * | AV1 | ✅ [unlockAv1] | `fnval` 256 + 2048 实测可用 |
     * | **杜比全景声** | ❌ **没有开关** | `fnval` 32 实测 **-400**，带上整条请求失败 |
     * | **Hi-Res 无损** | ❌ **没有开关** | `fnval` 4096 实测 **-400**，同上 |
     * | HDR | ❌ 没有开关 | 位可用，但本项目没有 HDR 色调映射，拿了是错色显示 |
     *
     * **杜比 / 无损仍然会被解析和播放** —— 只要服务端因为账号权限
     * 主动返回 `dash.dolby.audio` / `dash.flac.audio`，
     * [pickAudioStream] 就会选中它们。只是**不能主动请求**。
     *
     * ## ⚠️ 能力边界（必须如实告知用户）
     *
     * 这些位**只是"请求"**。非会员账号带上它们，服务端**照样只给
     * 480P + 64k AAC**（本项目已实测，见 `项目核心规则.md` 坑 172）。
     * 所以 UI 上要写清"需要大会员"，而不是暗示"开了就能解锁"。
     */
    data class UnlockFlags(
        /** 请求 8K（`fnval` 128）。实测可用。 */
        val unlock8K: Boolean = true,
        /** 请求杜比视界（`fnval` 64）。实测可用。 */
        val unlockDolbyVision: Boolean = true,
        /** 请求 AV1（`fnval` 256 + 2048）。实测可用。 */
        val unlockAv1: Boolean = true,
    ) {
        /** 拼成 `fnval` 的额外位（不含基础的 4K 位）。 */
        fun extraBits(): Int {
            var v = 0
            if (unlock8K) v = v or Fnval.EIGHT_K
            if (unlockDolbyVision) v = v or Fnval.DOLBY_VISION
            if (unlockAv1) v = v or Fnval.AV1 or Fnval.AV1_HIGH
            return v
        }

        /**
         * 算出最终的 `fnval`。
         *
         * ## 🔴 全开时就是 [Fnval.DEFAULT]（4048）
         *
         * 这里的 `or` 组合**不是** [Fnval.BASIC] `or` 额外位 ——
         * 因为 `BASIC`(16) 已经含在 `DEFAULT`(4048) 里，而
         * `4048` 还额外带了 512 / 1024 两个"固定位"。
         * 写成 `BASIC or extraBits()` 会得到 2512，
         * **少掉 512 与 1024** —— 虽然 2512 也实测可用（`code=0`），
         * 但那不是 B 站网页播放器用的值，没必要少要。
         */
        fun fnval(): Int = when {
            // 三个开关全开 → 直接用实测验证过的最大集
            unlock8K && unlockDolbyVision && unlockAv1 -> Fnval.DEFAULT
            else -> Fnval.DEFAULT and
                (Fnval.FOUR_K or extraBits() or Fnval.RESERVED_512 or Fnval.INTERACTIVE)
        }
    }

    /**
     * 请求 `fnval`（含用户开关）。
     *
     * ## 🔴 无论开不开，结果都必须是**服务端接受的值**
     *
     * 关闭时 [Fnval.BASIC] = `16`（改动前的原值）；
     * 开启时 = `4048`（实测可用的最大集）。
     * 两者都实测过 `code=0`。
     *
     * ⚠️ **绝不要在这里拼进 32 / 4096** —— 见 [Fnval] 的实测表，
     * 那会让**所有视频都取不到流**。
     *
     * @param autoQualityEnabled 自动画质总开关
     */
    fun fnvalFor(autoQualityEnabled: Boolean, flags: UnlockFlags): Int =
        if (autoQualityEnabled) flags.fnval() else Fnval.BASIC
}

/**
 * 自动画质设置（**未发版**）。
 *
 * 对应原脚本的「设置音质和画质」面板：首选 / 备选画质、自动音质开关、
 * 解锁开关。**默认全部保守**（与项目「默认值保持改动前行为」的约定一致）。
 */
data class AutoQualitySettings(
    /** 总开关。关闭 = 完全按改动前的行为走（只带 `fnval=16`）。 */
    val enabled: Boolean = false,
    /**
     * 首选画质（`qn`）。`0` = 不指定 → 自动最高。
     *
     * 对应原脚本的「首选画质」。
     */
    val preferredQn: Int = AutoQuality.QN_AUTO,
    /**
     * 备选画质（`qn`）。首选拿不到时用它。
     *
     * 对应原脚本的「备选画质」。
     */
    val fallbackQn: Int = AutoQuality.QN_AUTO,
    /** 自动选最高音质（对应原脚本的「自动音质」）。 */
    val autoAudio: Boolean = true,
    /**
     * 优先杜比全景声（对应原脚本 `.bpx-player-ctrl-dolby`）。
     *
     * ⚠️ **不是"请求杜比"** —— `fnval` 的杜比位（32）实测会让请求 `-400`，
     * 所以本开关只控制**选择**：服务端主动返回了 `dash.dolby.audio` 时才选它。
     * 没返回就回退普通 AAC（见 [pickAudioStream]）。
     */
    val dolbyAtmos: Boolean = false,
    /**
     * 优先 Hi-Res 无损（对应原脚本 `.bpx-player-ctrl-flac`）。
     *
     * ⚠️ 同 [dolbyAtmos]：无损位（4096）实测 `-400`，
     * 所以只控制**选择**，不控制请求。
     */
    val hiResAudio: Boolean = false,
    /** 解锁开关（对应原脚本「解锁设置」面板）。 */
    val unlock: AutoQuality.UnlockFlags = AutoQuality.UnlockFlags(),
    /** 直播自动最高画质（对应原脚本的直播画质面板）。 */
    val liveAutoQuality: Boolean = true,
) {
    /** 实际使用的 `fnval`。 */
    fun fnval(): Int = AutoQuality.fnvalFor(enabled, unlock)
}

/**
 * 画质档位的中文名（`qn` → 名称）。
 *
 * ⚠️ **只作为兜底** —— 优先用服务端返回的 `new_description`
 * （见 [QualityParser.formatNames]），因为它更准（同 id 不同视频
 * 可能名称不同）。本表用于：设置页列候选档位（那时还没有请求响应）。
 *
 * 数值取自 B 站公开的 `qn` 约定，与 `SettingsScreen.QUALITY_OPTIONS`
 * 原本写死的 4 项一致并做了扩充。
 */
object QualityNames {
    const val Q360 = 16
    const val Q480 = 32
    const val Q720 = 64
    const val Q1080 = 80
    const val Q1080_PLUS = 112
    const val Q1080_60 = 116
    const val Q4K = 120
    const val Q8K = 127

    /** 设置页可选的档位（升序）。`0` 由 UI 单独加「自动」。 */
    val SELECTABLE: List<Pair<Int, String>> = listOf(
        Q360 to "360P 流畅",
        Q480 to "480P 标清",
        Q720 to "720P 准高清",
        Q1080 to "1080P 高清",
        Q1080_PLUS to "1080P 高码率",
        Q1080_60 to "1080P 60帧",
        Q4K to "4K 超高清",
        Q8K to "8K 超高清",
    )

    fun label(qn: Int): String =
        if (qn == 0) "自动" else SELECTABLE.firstOrNull { it.first == qn }?.second ?: "清晰度 $qn"
}

/**
 * 直播画质码（`qn`）→ 中文名。
 *
 * 与点播**完全不同的编号体系**（直播 10000 = 原画，点播 120 = 4K），
 * 所以必须独立一张表 —— 混用会显示成"清晰度 10000"。
 *
 * 实测来源：`getRoomPlayInfo` 的 `g_qn_desc[]`（`qn` + `desc`）。
 * 本表只作兜底（拿不到 `g_qn_desc` 时）。
 */
object LiveQualityNames {
    const val ORIGINAL = 10000
    const val BLU_RAY = 400
    const val ULTRA_HD = 250
    const val HD = 150
    const val SMOOTH = 80

    fun label(qn: Int): String = when (qn) {
        ORIGINAL -> "原画"
        BLU_RAY -> "蓝光"
        ULTRA_HD -> "超清"
        HD -> "高清"
        SMOOTH -> "流畅"
        else -> if (qn > 0) "清晰度 $qn" else ""
    }
}
