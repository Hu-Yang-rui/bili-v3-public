package com.example.biliv3.data

import org.json.JSONObject

/**
 * 直播取流结果（v1.6.3）。
 *
 * ## 为什么要分开存 HLS 与 FLV 两个地址
 *
 * 实测（2026-10-06，`room_id=6`）B 站一次返回 **6 种组合**：
 *
 * ```
 * proto=http_stream fmt=flv  codec=avc   ← progressive，可直接播
 * proto=http_stream fmt=flv  codec=hevc
 * proto=http_hls    fmt=ts   codec=avc   ← 兼容性最好
 * proto=http_hls    fmt=ts   codec=hevc
 * proto=http_hls    fmt=fmp4 codec=avc
 * proto=http_hls    fmt=fmp4 codec=hevc
 * ```
 *
 * 两条协议各有取舍，**不能只留一条**：
 * - **HLS**：`HlsMediaSource` 支持最好，且分片天然适配直播（断线续拉）。
 * - **FLV**：Media3 的 `ProgressiveMediaSource` 能播（`FlvExtractor` 内置），
 *   但**没有直播语义** —— 它按"渐进式文件"处理，直播流永不结束，
 *   缓冲会一直增长。作为 HLS 失败时的**兜底**可用。
 *
 * 所以两个都取，由播放侧决定用哪个。
 */
data class LiveStream(
    /** HLS（`.m3u8`）地址；空串 = 该直播间没给 HLS。 */
    val hlsUrl: String,
    /** FLV（`.flv`）地址；空串 = 没给 FLV。 */
    val flvUrl: String,
    /** 实际拿到的画质码（如 `250`）。 */
    val quality: Int,
    /** 画质中文名（如「超清」）。 */
    val qualityLabel: String,
    /** 可选画质码（用于 UI 显示"最高可得"，本项目不提供切换）。 */
    val acceptQuality: List<Int>,
) {
    /** 是否拿到了可播的地址。 */
    val playable: Boolean get() = hlsUrl.isNotEmpty() || flvUrl.isNotEmpty()
}

/**
 * 直播取流解析（**纯函数**，可单测）。
 *
 * ## 为什么必须抽出来
 *
 * 这个响应是**四层嵌套**（`stream[] → format[] → codec[] → url_info[]`），
 * 且真正要用的地址是**三段拼接**：
 *
 * ```
 * url = url_info[0].host + codec.base_url + url_info[0].extra
 * ```
 *
 * 少拼一段的表现是：
 * - 缺 `host` → `base_url` 以 `/` 开头，直接交给 ExoPlayer 会因
 *   非法 URI 抛异常（或当成相对路径解析失败）
 * - 缺 `extra` → URL 没有签名参数，CDN 返回 **403**
 *
 * 两种都不报"拼错了"，只表现为**黑屏**。所以这段逻辑必须有单测
 * （`LiveStreamTest`），而不是靠人工点测发现。
 *
 * ## 为什么用 org.json 而不是 MiniJson
 *
 * `MiniJson` 是为**扁平/固定形状**的第三方响应写的（见其类文档）。
 * 这里要按数组下标逐层取值（`stream[i].format[j].codec[k]`），
 * 用正则式的 MiniJson 会非常别扭且易错。
 * 而 `org.json` 在本项目的单测里**有真实实现**
 * （`testImplementation(libs.json)`，见 `build.gradle.kts`），
 * 所以用它写的解析**照样能被单测覆盖** —— 这是它可用的前提。
 */
object LiveStreamParser {

    /** 优先选用的编码（H.264 兼容性最好，与项目的 `preferH264` 一致）。 */
    private const val CODEC_AVC = "avc"

    private const val PROTO_HLS = "http_hls"
    private const val PROTO_FLV = "http_stream"

    /**
     * 解析 `getRoomPlayInfo` 的 `data` 对象。
     *
     * @return 解析结果；没有可播地址时 `playable` 为 false
     *         （调用方据此显示"该直播间暂不可播"，而不是黑屏）。
     */
    fun parse(data: JSONObject?): LiveStream {
        val playurl = data
            ?.optJSONObject("playurl_info")
            ?.optJSONObject("playurl")
            ?: return empty()

        val qnDesc = parseQualityDesc(playurl.optJSONArray("g_qn_desc"))
        val streams = playurl.optJSONArray("stream") ?: return empty()

        // ⚠️ 这两个标记是**局部变量**，不能做成 object 的字段 ——
        // `LiveStreamParser` 是单例，用可变字段会让上一次解析的
        // "已选到 avc" 泄漏到下一次，第二次解析就会错误地跳过 avc 候选。
        var hls: String? = null
        var hlsIsAvc = false
        var flv: String? = null
        var flvIsAvc = false
        var qn = 0
        var accept: List<Int> = emptyList()

        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val proto = stream.optString("protocol_name")
            val formats = stream.optJSONArray("format") ?: continue

            for (j in 0 until formats.length()) {
                val fmt = formats.optJSONObject(j) ?: continue
                val codecs = fmt.optJSONArray("codec") ?: continue

                for (k in 0 until codecs.length()) {
                    val c = codecs.optJSONObject(k) ?: continue
                    val url = buildUrl(c) ?: continue
                    if (url.isEmpty()) continue

                    // 优先 avc：hevc 在部分设备上无硬解，会黑屏。
                    // 已经是 avc 时**不覆盖**（第一次拿到就定下来）。
                    val isAvc = c.optString("codec_name") == CODEC_AVC

                    when {
                        proto == PROTO_HLS && (hls == null || (isAvc && !hlsIsAvc)) -> {
                            hls = url
                            hlsIsAvc = isAvc
                            if (qn == 0) qn = c.optInt("current_qn", 0)
                            if (accept.isEmpty()) accept = intArray(c, "accept_qn")
                        }
                        proto == PROTO_FLV && (flv == null || (isAvc && !flvIsAvc)) -> {
                            flv = url
                            flvIsAvc = isAvc
                            if (qn == 0) qn = c.optInt("current_qn", 0)
                            if (accept.isEmpty()) accept = intArray(c, "accept_qn")
                        }
                    }
                }
            }
        }

        return LiveStream(
            hlsUrl = hls.orEmpty(),
            flvUrl = flv.orEmpty(),
            quality = qn,
            qualityLabel = qnDesc[qn] ?: if (qn > 0) "清晰度 $qn" else "",
            acceptQuality = accept,
        )
    }

    private fun empty() = LiveStream(
        hlsUrl = "",
        flvUrl = "",
        quality = 0,
        qualityLabel = "",
        acceptQuality = emptyList(),
    )

    /**
     * 拼出完整可播地址：`host + base_url + extra`。
     *
     * ⚠️ 三段缺一不可（见类文档）。`extra` 是查询串（含签名），
     * 它**自带 `?` 之后的部分**，所以直接字符串相加即可，
     * 不要再插 `?` —— 那样会拼出 `...flv??expires=`。
     */
    private fun buildUrl(codec: JSONObject): String? {
        val base = codec.optString("base_url")
        if (base.isEmpty()) return null

        val infos = codec.optJSONArray("url_info") ?: return null
        if (infos.length() == 0) return null

        // 实测可取第一条；多条时后续的是备用 CDN。
        val info = infos.optJSONObject(0) ?: return null
        val host = info.optString("host")
        val extra = info.optString("extra")

        // host 缺失时 base_url 可能是绝对地址（少数情况），此时直接用
        if (host.isEmpty()) return base

        return host + base + extra
    }

    /** 解析 `g_qn_desc`（画质码 → 中文名）。 */
    private fun parseQualityDesc(arr: org.json.JSONArray?): Map<Int, String> {
        if (arr == null) return emptyMap()
        val out = HashMap<Int, String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val qn = o.optInt("qn", 0)
            val desc = o.optString("desc")
            if (qn > 0 && desc.isNotEmpty()) out[qn] = desc
        }
        return out
    }

    /** 取 `accept_qn`（可能是数组或字符串形态，B 站两种都出现过）。 */
    private fun intArray(codec: JSONObject, key: String): List<Int> {
        val arr = codec.optJSONArray(key)
        if (arr != null) {
            return (0 until arr.length()).mapNotNull { arr.optInt(it).takeIf { v -> v > 0 } }
        }
        // 字符串形态："10000,400,250"
        val s = codec.optString(key)
        if (s.isEmpty()) return emptyList()
        return s.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
    }
}
