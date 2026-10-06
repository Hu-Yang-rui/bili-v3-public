package com.example.biliv3.data.live

import android.util.Log
import com.example.biliv3.data.api.BiliHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

/**
 * 直播间弹幕 WebSocket 客户端（v1.6.4）。
 *
 * ---
 *
 * # 实测确认（2026-10-06）
 *
 * ```
 * wss://{host}:{wss_port}/sub        host/port 来自 getDanmuInfo.host_list
 * 认证: op=7 {uid, roomid, protover:2, platform:"web", type:2, key:token, buvid}
 * 回应: op=8 {"code":0}              ✅ 认证通过
 * ```
 *
 * 真实房间 545068 在 20 秒内收到 **72 条**消息（弹幕 9 / 进入 38 / 特效 10…），
 * 说明这条链路是**真实可用**的，不是纸面方案。
 *
 * ## ⚠️ 冷门房间收不到消息是正常的
 *
 * 实测房间 6 / 21452505 在 20 秒内 **0 条** —— 没人说话而已。
 * 所以 [ConnectionState] 的判定**必须看 `op=8` 认证回应**，
 * **不能**看"有没有收到消息"。后者会把"直播间很安静"误报成"连接失败"。
 *
 * ## 为什么用 OkHttp 的 WebSocket 而不是引第三方库
 *
 * 项目已经有 OkHttp（`BiliApi` 共用），OkHttp 自带 `WebSocket` 支持。
 * 引 okhttp-sse / Scarlet 之类只会多一个依赖，没有收益。
 *
 * ## 线程模型
 *
 * OkHttp 在**自己的线程**回调 `onMessage`。这里只做"解析 + 投递到
 * 回调"，所有状态变更由调用方（ViewModel）在协程里做 ——
 * 避免在 OkHttp 线程上碰 Compose 状态。
 */
class LiveDanmakuClient(
    private val scope: CoroutineScope,
    /**
     * 专用 OkHttpClient。
     *
     * 🔴 **不能复用 `BiliApi` 的共享 client**：
     * 那个 client 挂着 `AuthCookieJar`，而 WebSocket 握手会把 cookie
     * 带给 **`*.chat.bilibili.com`**（另一个域名）。虽然同属 B 站，
     * 但让凭据流到非 API 域名不是必要的行为 —— 认证靠 `token` 就够
     * （实测未登录也能连上）。
     *
     * 用独立 client 还有一个好处：WS 是**长连接**，
     * 它的超时/重试语义与 API 请求完全不同，混在一个 client 上
     * 会让 API 侧也继承 WS 的长超时。
     */
    private val client: OkHttpClient = defaultClient(),
) {

    /** 连接状态（UI 据此显示"已连接 / 重连中 / 失败"）。 */
    enum class ConnectionState {
        /** 还没开始 / 已主动停止。 */
        IDLE,

        /** 正在建立连接（含重连等待）。 */
        CONNECTING,

        /** **认证通过**（收到 `op=8 code=0`）。这是唯一可信的"已连上"信号。 */
        CONNECTED,

        /** 连接断了，正在自动重连。 */
        RECONNECTING,

        /** 重连次数用尽，放弃（UI 给手动重试入口）。 */
        FAILED,
    }

    /** 收到一条消息。在 OkHttp 线程回调 —— 实现方需自行切线程。 */
    var onMessage: ((LiveMessage) -> Unit)? = null

    /** 连接状态变化。 */
    var onStateChange: ((ConnectionState) -> Unit)? = null

    /** 人气值变化（`op=3` 心跳回应）。 */
    var onPopularity: ((Int) -> Unit)? = null

    private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var attempts = 0

    @Volatile
    private var stopped = false

    @Volatile
    private var state: ConnectionState = ConnectionState.IDLE

    /** 当前状态（同步读，供 UI 首帧用）。 */
    val currentState: ConnectionState get() = state

    private fun setState(s: ConnectionState) {
        if (state == s) return
        state = s
        onStateChange?.invoke(s)
    }

    /**
     * 开始连接。
     *
     * @param roomId 真实房间号（**不是短号** —— 短号会导致认证失败）
     * @param token `getDanmuInfo` 返回的 token
     * @param hosts 候选接入点（取第一个可用的）
     * @param buvid 设备指纹（匿名观众也发）
     * @param anchorUid 主播 uid（用于身份判定）
     */
    fun connect(
        roomId: Long,
        token: String,
        hosts: List<DanmakuHost>,
        buvid: String,
        anchorUid: Long,
    ) {
        stopped = false
        attempts = 0
        this.roomId = roomId
        this.token = token
        this.hosts = hosts
        this.buvid = buvid
        this.anchorUid = anchorUid
        openSocket()
    }

    private var roomId: Long = 0L
    private var token: String = ""
    private var hosts: List<DanmakuHost> = emptyList()
    private var buvid: String = ""
    private var anchorUid: Long = 0L

    private fun openSocket() {
        if (stopped) return

        val host = hosts.getOrNull(attempts % hosts.size.coerceAtLeast(1)) ?: run {
            setState(ConnectionState.FAILED)
            return
        }
        val url = host.wssUrl()
        setState(if (attempts == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING)

        val req = Request.Builder()
            .url(url)
            .apply { BiliHeaders.api().forEach { (k, v) -> header(k, v) } }
            .build()

        ws = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 连上 TCP 还不够 —— 必须发认证帧，等服务端 op=8
            val body = LiveDanmakuProtocol.authBody(
                roomId = roomId,
                token = token,
                buvid = buvid,
            )
            webSocket.send(
                LiveDanmakuProtocol.buildFrame(
                    operation = LiveDanmakuProtocol.OP_AUTH,
                    body = body.toByteArray(Charsets.UTF_8),
                ).toByteString(),
            )
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleFrame(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // 服务端不用文本帧；真收到就忽略（不崩）
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS 断开: ${t.message} (http=${response?.code})")
            heartbeatJob?.cancel()
            if (stopped) return
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            if (stopped) return
            Log.i(TAG, "WS 关闭 code=$code reason=$reason")
            scheduleReconnect()
        }
    }

    /**
     * 处理一批字节。
     *
     * ## ⚠️ 递归解析（实测必需）
     *
     * `protover=2` 的 body 是 zlib 压缩的，**解压后仍是一批完整的帧**
     * （不是一条 JSON）。必须递归 `parseFrames`。
     * 当成一条 JSON 会 `JSON.parse` 失败 → "连上了但一条消息都没有"。
     */
    private fun handleFrame(data: ByteArray) {
        for (f in LiveDanmakuProtocol.parseFrames(data)) {
            when (f.operation) {
                LiveDanmakuProtocol.OP_AUTH_REPLY -> {
                    // 认证回应是**唯一可信**的"已连上"信号
                    val ok = String(f.body, Charsets.UTF_8).contains("\"code\":0")
                    if (ok) {
                        attempts = 0
                        setState(ConnectionState.CONNECTED)
                        Log.i(TAG, "认证通过 room=$roomId")
                    } else {
                        Log.w(TAG, "认证失败: ${String(f.body, Charsets.UTF_8).take(120)}")
                        setState(ConnectionState.FAILED)
                    }
                }

                LiveDanmakuProtocol.OP_HEARTBEAT_REPLY -> {
                    onPopularity?.invoke(LiveDanmakuProtocol.popularity(f.body))
                }

                LiveDanmakuProtocol.OP_MESSAGE -> {
                    val payload: ByteArray? = when (f.protover) {
                        LiveDanmakuProtocol.PROTO_ZLIB -> inflate(f.body)
                        LiveDanmakuProtocol.PROTO_BROTLI -> {
                            // 不支持 brotli：如实记一条，不假装成功。
                            // （这是 continue 的替代写法 —— Kotlin 的
                            //  `when` 分支里不能用带标签的 continue 跳出外层 for。）
                            Log.w(TAG, "收到 brotli 帧（protover=3），本实现不支持")
                            null
                        }
                        else -> f.body
                    }
                    if (payload != null) dispatch(payload)
                }
            }
        }
    }

    /** 解压后的 payload 是一批帧，逐条解析 JSON。 */
    private fun dispatch(payload: ByteArray) {
        val frames = LiveDanmakuProtocol.parseFrames(payload)
        // 解压后可能还是"单条 JSON"（protover=0 的情况）—— 两种都试
        val jsons = if (frames.isEmpty()) {
            listOf(String(payload, Charsets.UTF_8))
        } else {
            frames.filter { it.operation == LiveDanmakuProtocol.OP_MESSAGE }
                .map { String(it.body, Charsets.UTF_8) }
        }
        for (j in jsons) {
            val msg = runCatching { LiveMessageParser.parse(j, anchorUid) }.getOrNull() ?: continue
            onMessage?.invoke(msg)
        }
    }

    /** zlib 解压（`protover=2`）。失败返回 null（不崩）。 */
    private fun inflate(body: ByteArray): ByteArray? = try {
        val inflater = Inflater()
        inflater.setInput(body)
        val out = ByteArrayOutputStream(body.size * 4)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n <= 0) {
                // needsInput 但没输入了 → 数据不完整
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(buf, 0, n)
            }
        }
        inflater.end()
        out.toByteArray().takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "zlib 解压失败: ${e.message}")
        null
    }

    /** 心跳：30 秒一次（服务端约 30 秒无心跳会断开）。 */
    private fun startHeartbeat(socket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // 帧内容恒定，只构造一次
            val frame = LiveDanmakuProtocol.buildFrame(
                operation = LiveDanmakuProtocol.OP_HEARTBEAT,
                body = LiveDanmakuProtocol.heartbeatBody().toByteArray(Charsets.UTF_8),
            ).toByteString()
            while (isActive) {
                delay(LiveDanmakuProtocol.HEARTBEAT_INTERVAL_MS)
                val ok = runCatching { socket.send(frame) }.getOrDefault(false)
                if (!ok) break
            }
        }
    }

    /**
     * 重连（指数退避）。
     *
     * ## 为什么必须限制次数
     *
     * 无上限重连会在"直播间不存在 / 被永久封禁"这类**不可恢复**的失败上
     * 无限打请求 —— 对服务端是压力，对用户是耗电。
     * 用尽后进 [ConnectionState.FAILED]，由 UI 给**手动重试**入口。
     */
    private fun scheduleReconnect() {
        if (stopped) return
        if (attempts >= MAX_ATTEMPTS) {
            setState(ConnectionState.FAILED)
            Log.w(TAG, "重连次数用尽（$MAX_ATTEMPTS）")
            return
        }
        val waitMs = BASE_BACKOFF_MS * (1L shl attempts.coerceAtMost(4))
        attempts++
        setState(ConnectionState.RECONNECTING)

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(waitMs)
            if (!stopped) openSocket()
        }
    }

    /** 手动重试（UI 的"重新连接"按钮）。 */
    fun retry() {
        attempts = 0
        reconnectJob?.cancel()
        openSocket()
    }

    /** 主动停止（离开页面时调用）。 */
    fun stop() {
        stopped = true
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        runCatching { ws?.close(1000, "bye") }
        runCatching { ws?.cancel() }
        ws = null
        setState(ConnectionState.IDLE)
    }

    companion object {
        private const val TAG = "BiliLiveDanmaku"

        /** 最多重连几次（之后交给用户手动重试）。 */
        private const val MAX_ATTEMPTS = 5

        /** 退避基数（1s → 2s → 4s → 8s → 16s）。 */
        private const val BASE_BACKOFF_MS = 1000L

        /**
         * 专用 client。
         *
         * - **不挂 CookieJar**（见构造参数的说明）
         * - `pingInterval` 让 OkHttp 自己做 WS 层保活（与业务心跳互补）
         * - `readTimeout(0)` = 不设读超时：WS 是长连接，
         *   设了会在空闲时被误杀（这正是"过一会儿就断"的常见成因）
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 一个弹幕接入点。
 *
 * @param host 形如 `zj-cn-live-comet.chat.bilibili.com`
 * @param wssPort 实测 `2245`
 */
data class DanmakuHost(
    val host: String,
    val wssPort: Int,
) {
    /** `wss://host:port/sub`。 */
    fun wssUrl(): String = "wss://$host:$wssPort/sub"
}
