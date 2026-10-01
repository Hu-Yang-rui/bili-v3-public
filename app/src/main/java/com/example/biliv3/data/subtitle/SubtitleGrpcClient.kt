package com.example.biliv3.data.subtitle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * B 站 AI 字幕 gRPC 客户端。
 *
 * ## 逆向来源
 *
 * 从官方 APK（`D:\deep\bilianalyze`）反编译确认：
 * ```
 * service : bilibili.subtitle.Subtitle
 * method  : SubtitleView          （UNARY）
 * host    : grpc.biliapi.net      （443）
 * full    : /bilibili.subtitle.Subtitle/SubtitleView
 * ```
 * 实测该端点可达（返回 `HTTP 200` + `grpc-status: 2`），
 * 未带正确参数时为 `grpc-message: -400`。
 *
 * ## 为什么用 OkHttp 手发 gRPC 帧而不是 grpc-java
 *
 * gRPC 在 HTTP/2 上的 wire format 很简单：
 * ```
 * [1 byte 压缩标志=0][4 byte 大端长度][protobuf payload]
 * ```
 * 响应同格式。OkHttp **原生支持 HTTP/2**，所以只要手工拼这 5 字节头，
 * 就能发 unary 调用 —— 不需要引入 grpc-java（省 2~3MB 体积 + 大量依赖）。
 *
 * 代价是不支持流式、不支持 grpc 的重试/负载均衡等高级特性，
 * 但字幕接口是**简单 unary 调用**，这些都用不上。
 *
 * ## 鉴权
 *
 * 走 App 端接口，需要 `authorization` 头。当前实现**先不带鉴权尝试**，
 * 失败则回退到 REST 路径（见 [SubtitleRepository]）。
 */
class SubtitleGrpcClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 拉字幕。
     *
     * @param aid 视频 aid（UGC）
     * @param cid 视频 cid
     * @param epId 剧集 id（PGC 番剧用，与 aid 二选一）
     * @param preferredLanguage 偏好语言，如 `ai-zh`（AI 中文）
     * @param authorization App 端鉴权串，形如 `identify_v1 <access_key>`；为空则不带头
     */
    suspend fun subtitleView(
        aid: Long,
        cid: Long,
        epId: Long = 0L,
        preferredLanguage: String = "ai-zh",
        authorization: String = "",
    ): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        val isPgc = epId > 0

        // ---- 构造 SubtitleViewReq ----
        val payload = ByteArrayOutputStream().apply {
            // 1: pid = cid
            Proto.writeVarint(this, FIELD_PID, cid)
            // 2: oid = aid 或 epId
            Proto.writeVarint(this, FIELD_OID, if (isPgc) epId else aid)
            // 3: type  0=UGC 1=PGC
            Proto.writeVarint(this, FIELD_TYPE, if (isPgc) 1L else 0L)
            // 4: spmid
            Proto.writeString(this, FIELD_SPMID, SPMID)
            // 5: is_hard_boot = 0
            Proto.writeVarint(this, FIELD_IS_HARD_BOOT, 0L)
            // 9: preferred_language
            Proto.writeString(this, FIELD_PREFERRED_LANGUAGE, preferredLanguage)
        }.toByteArray()

        val body = grpcFrame(payload).toRequestBody(GRPC_MEDIA_TYPE)

        val reqBuilder = Request.Builder()
            .url(ENDPOINT)
            .post(body)
            .header("Content-Type", "application/grpc")
            .header("TE", "trailers")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")

        if (authorization.isNotEmpty()) {
            reqBuilder.header("authorization", authorization)
        }

        val (grpcStatus, grpcMessage, bytes) = try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                Triple(
                    resp.header("grpc-status") ?: "",
                    resp.header("grpc-message") ?: "",
                    resp.body?.bytes() ?: ByteArray(0),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "gRPC 请求失败: ${e.message}")
            throw SubtitleException("字幕服务不可达：${e.message}")
        }

        if (grpcStatus.isNotEmpty() && grpcStatus != "0") {
            // grpc-status != 0 表示调用失败；grpc-message 里是业务错误码
            Log.w(TAG, "gRPC 失败 status=$grpcStatus msg=$grpcMessage")
            throw SubtitleException("字幕接口返回错误（$grpcMessage）")
        }

        val grpcPayload = ungrpcFrame(bytes) ?: run {
            Log.w(TAG, "响应不是合法 gRPC 帧（len=${bytes.size}）")
            throw SubtitleException("字幕响应格式异常")
        }

        parseReply(grpcPayload)
    }

    /**
     * 解析 `SubtitleViewReply`。
     *
     * ```
     * SubtitleViewReply: 1=subtitle(VideoSubtitle)
     * VideoSubtitle:     1=lan 2=lan_doc 3=subtitles[] 4=position 5=font_size
     * SubtitleItem:      1=id 2=id_str 3=lan 4=lan_doc 5=subtitle_url
     *                    6=author 7=type 8=lan_doc_brief 9=ai_type
     *                    10=ai_status 11=role 12=height 13=format
     * ```
     */
    private fun parseReply(bytes: ByteArray): List<SubtitleTrack> {
        val reply = Proto.decode(bytes)
        val video = reply.pbMessage(REPLY_SUBTITLE) ?: return emptyList()
        val items = video.pbMessages(VIDEO_SUBTITLE_SUBTITLES)

        // VideoSubtitle 自身也有 lan/lan_doc，作为兜底
        val fallbackLan = video.pbString(VIDEO_SUBTITLE_LAN)
        val fallbackDoc = video.pbString(VIDEO_SUBTITLE_LAN_DOC)

        return items.map { item ->
            SubtitleTrack(
                id = item.pbInt(ITEM_ID).toLong(),
                lan = item.pbString(ITEM_LAN).ifEmpty { fallbackLan },
                lanDoc = item.pbString(ITEM_LAN_DOC).ifEmpty { fallbackDoc },
                subtitleUrl = item.pbString(ITEM_SUBTITLE_URL),
                type = item.pbInt(ITEM_TYPE),
                aiType = item.pbInt(ITEM_AI_TYPE),
                aiStatus = item.pbInt(ITEM_AI_STATUS),
                author = item.pbString(ITEM_AUTHOR),
            )
        }
    }

    /** gRPC 帧：`[1B 压缩标志][4B 大端长度][payload]`。 */
    private fun grpcFrame(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 5)
        out.write(0) // 未压缩
        out.write((payload.size ushr 24) and 0xFF)
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /** 拆 gRPC 帧。返回 null 表示格式非法。 */
    private fun ungrpcFrame(bytes: ByteArray): ByteArray? {
        if (bytes.size < 5) return null
        val len = ((bytes[1].toInt() and 0xFF) shl 24) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 8) or
            (bytes[4].toInt() and 0xFF)
        if (len < 0 || 5 + len > bytes.size) return null
        return bytes.copyOfRange(5, 5 + len)
    }

    companion object {
        private const val TAG = "BiliSubtitle"

        /** 端点（反编译确认 + 实测可达）。 */
        const val ENDPOINT = "https://grpc.biliapi.net/bilibili.subtitle.Subtitle/SubtitleView"

        /**
         * Android 客户端 appkey。
         *
         * 从官方 APK 的 `classes11.dex` 字符串池搜到的唯一命中值。
         * 实测用它 + 公开 appsec 调 REST 接口返回 `code=0`，
         * 说明**签名有效**（签名错会返回 `-3`）。
         */
        const val APPKEY = "4409e2ce8ffd12b8"

        /**
         * ⚠️ gRPC 路径当前仍返回 `-400`，**尚未打通**。
         *
         * 已排除的原因（均实测）：
         * - **路径带 query** → 会导致 `grpc-status=12 unknown method`，已修正
         * - **参数不全** → 补齐 13 个字段的各种组合，结果一致
         * - **该视频无字幕** → 批量测 20 个不同类型视频（纪录片/知识/影视/动画）
         *   全部 `-400`，排除"无此资源"
         *
         * 剩余最可能原因：**缺 App 端登录态**。
         * gRPC 鉴权用的不是 appkey 签名，而是 `access_key`
         * （`authorization: identify_v1 <access_key>`），
         * 换它需要走 App 端登录，与 Web 端 Cookie 是两套体系。
         *
         * 因此当前实际可用的是 [SubtitleRepository] 的 **REST 回退路径**
         * （`x/player/wbi/v2`）—— 它复用 Web 登录态，登录后能拿到
         * CC / UP 上传字幕。**AI 翻译轨需 gRPC，暂时拿不到。**
         */
        const val GRPC_BLOCKED_REASON =
            "AI 翻译需 App 端鉴权（access_key），当前未实现；已回退到普通字幕"

        private const val SPMID = "main.ugc-video-detail.0.0.pv"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val GRPC_MEDIA_TYPE = "application/grpc".toMediaType()

        // ---- SubtitleViewReq 字段号（反编译确认）----
        private const val FIELD_PID = 1
        private const val FIELD_OID = 2
        private const val FIELD_TYPE = 3
        private const val FIELD_SPMID = 4
        private const val FIELD_IS_HARD_BOOT = 5
        private const val FIELD_PREFERRED_LANGUAGE = 9

        // ---- SubtitleViewReply ----
        private const val REPLY_SUBTITLE = 1

        // ---- VideoSubtitle ----
        private const val VIDEO_SUBTITLE_LAN = 1
        private const val VIDEO_SUBTITLE_LAN_DOC = 2
        private const val VIDEO_SUBTITLE_SUBTITLES = 3

        // ---- SubtitleItem ----
        private const val ITEM_ID = 1
        private const val ITEM_LAN = 3
        private const val ITEM_LAN_DOC = 4
        private const val ITEM_SUBTITLE_URL = 5
        private const val ITEM_AUTHOR = 6
        private const val ITEM_TYPE = 7
        private const val ITEM_AI_TYPE = 9
        private const val ITEM_AI_STATUS = 10

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            // gRPC 必须 HTTP/2；OkHttp 默认就会协商 h2，这里显式声明协议列表
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .build()
    }
}

/** 字幕相关错误。 */
class SubtitleException(override val message: String) : Exception(message)
