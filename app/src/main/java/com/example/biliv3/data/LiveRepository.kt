package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.api.Endpoints
import com.example.biliv3.data.live.DanmakuHost
import com.example.biliv3.data.live.LiveErrorMapper
import com.example.biliv3.data.live.ModerationResult
import org.json.JSONObject

/**
 * 直播仓库：列表 / 取流 / 入场上报 / 弹幕接入 / 房管操作。
 *
 * ## ⚠️ 直播接口在独立域名
 *
 * `api.live.bilibili.com`，不在 `api.bilibili.com` ——
 * 走错域名得到 404 或空 body。这是本项目接入直播最容易踩的坑。
 *
 * ## 🔴 直播接口需要 **WBI 签名**（v1.6.4 实测）
 *
 * 这是本项目此前"直播只能读列表"的根因之一：
 *
 * ```
 * getInfoByRoom  plain          -> -352
 * getInfoByRoom  + buvid cookie -> -352
 * getInfoByRoom  + buvid + WBI  -> 0   ✅
 * getDanmuInfo   + buvid + WBI  -> 0   ✅
 * ```
 *
 * `room_init` 不需要签名，所以它一直能通，**掩盖了这个问题** ——
 * 让人误以为"直播接口都能匿名直连"。
 *
 * ## 房管操作的**能力边界**（如实记录）
 *
 * 实测（未登录探测，`banned_service` 下的端点全部返回 `65530 invalid request`
 * 而非 `1000003 方法未找到`）→ **端点真实存在**。
 * 但**是否可用取决于账号权限**，本项目无法在未登录状态验证，因此：
 * - 客户端**不预判成功**，一律以服务端返回为准
 * - 权限在数据层先拦一道（省一次注定失败的请求），**不替代**服务端裁决
 * - 错误分类见 [LiveErrorMapper]
 *
 * ⚠️ 写 KDoc 时**不要出现「斜杠 + 星号」**（Kotlin 块注释会嵌套，
 * 一旦出现就 Unclosed comment）。这里第一版写成了 `banned_service` 加通配，
 * 结果整份文件编译失败 —— 见 `技术规范.md` §8。
 */
class LiveRepository(
    private val api: BiliApi,
    /**
     * 设备指纹（`buvid3`）。
     *
     * 直播接口实测**需要**它：不带时更容易命中 `-352`。
     * 由 `AppContainer` 从 `AuthStore` 取（未登录时也会有 —— 首次启动就下发了）。
     */
    private val buvidProvider: () -> String = { "" },
) {

    /**
     * 直播列表。
     *
     * @param areaId 分区 id。0 = 全部
     */
    suspend fun list(areaId: Int = 0, page: Int = 1, pageSize: Int = 20): LivePage {
        val query = mutableMapOf(
            "platform" to "web",
            "parent_area_id" to "0",
            "area_id" to areaId.toString(),
            "sort_type" to "online",
            "page" to page.toString(),
            "page_size" to pageSize.toString(),
        )

        // ⚠️ 失败一律**抛异常**，不返回空列表（v1.2.4 修）。
        //
        // 上一版这里有 5 个 `return LivePage(emptyList(), false)` 的静默出口
        // （异常 / `code != 0` / 缺 `data` / 缺 `list`）—— 于是**接口报错
        // 与"真的没有直播"长得一模一样**，页面显示「当前没有正在直播的房间」。
        //
        // 实测证据：本机 `getList` 返回 `code = -352`（风控），
        // 而 UI 平静地显示"没人直播"。这与 §7.8-44 是同一类错误 ——
        // **把失败伪装成空**，只是这次来源是**错误码**而不是异常。
        //
        // 空列表只能由"成功响应 + list 为空"产生。
        val json = api.getRaw(
            path = Endpoints.LIVE_LIST,
            query = query,
            signed = false,
            host = Endpoints.LIVE_LIST_HOST,
        )

        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(code, json.optString("message", "直播列表加载失败"))
        }

        val data = json.optJSONObject("data")
            ?: throw BiliException(-1, "直播响应缺少 data")
        val arr = data.optJSONArray("list")
            ?: throw BiliException(-1, "直播响应缺少 list")

        val out = ArrayList<LiveRoom>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseRoom(o)?.let { out.add(it) }
        }

        // 接口用 `has_more` 或"返回条数是否等于 page_size"表达是否还有更多。
        // 两者都判一下：实测 has_more 有时缺字段。
        val hasMore = data.optInt("has_more", -1).let {
            if (it >= 0) it == 1 else out.size >= pageSize
        }

        return LivePage(out, hasMore)
    }

    private fun parseRoom(o: JSONObject): LiveRoom? {
        val roomId = o.optLong("roomid", 0L)
        if (roomId <= 0L) return null

        return LiveRoom(
            roomId = roomId,
            title = o.optString("title"),
            cover = o.optString("system_cover").ifEmpty { o.optString("cover") },
            uname = o.optString("uname"),
            face = o.optString("face"),
            // 在线人数：接口给字符串（如 "1.2万"），也见过数字
            online = parseOnline(o.opt("online")),
            areaName = o.optString("area_name"),
            uid = o.optLong("uid", 0L),
        )
    }

    /**
     * 解析在线人数。
     *
     * 接口的 `online` 实测是**字符串**（`"1.2万"` / `"1234"`），
     * 用 `optInt` 会恒得 0 —— 这是"人数永远显示 0"的典型成因。
     * 数字型也兼容（老版本接口）。
     */
    private fun parseOnline(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> {
            val s = v.trim()
            when {
                s.contains('万') -> {
                    val n = s.substringBefore('万').toDoubleOrNull() ?: 0.0
                    (n * 10000).toInt()
                }
                else -> s.toIntOrNull() ?: 0
            }
        }
        else -> 0
    }

    /**
     * 直播间取流（v1.6.3，**应用内播放**）。
     *
     * ## 实测（2026-10-06，未登录直连 `room_id=6`）
     *
     * ```
     * code=0, live_status=1
     * stream[] 共 6 种组合：flv(avc/hevc) + hls(ts/fmp4 × avc/hevc)
     * 真正的地址 = url_info[0].host + codec.base_url + url_info[0].extra
     * ```
     *
     * ## ⚠️ 参数里为什么有 `protocol=0,1&format=0,1,2&codec=0,1`
     *
     * 这是"要哪些协议/封装/编码"的**位掩码**。不传的话服务端
     * 可能只回一种（实测传全量才拿到 6 种组合）。
     * 我们要 HLS 与 FLV 两条路，所以显式要全。
     *
     * ## 失败必须**抛异常**，不能返回空
     *
     * 与 [list] 同一条理由（§7.8-44）：返回空会让"直播间没开播"
     * 与"接口失败"长得一模一样，用户看到的是黑屏而没有任何提示。
     *
     * @param autoQuality 自动最高画质（未发版）。
     *   开启时请求 `qn=10000`（原画）—— 服务端按账号权限给到能给的最高档；
     *   关闭时请求 `qn=250`（超清，本项目改动前一直用的档）。
     *
     *   ## ⚠️ 为什么"关"是降到 250 而不是 0
     *
     *   传 `qn=0` 会让服务端按**默认策略**给（实测给到 250 或更低），
     *   行为不可预期。显式传 250 才是"稳定的非原画档"。
     *
     * @return 可播地址集合；`playable == false` 表示**确实没流**
     *         （例如主播未开播），这是**正常状态**，由 UI 显示空态。
     */
    suspend fun stream(
        roomId: Long,
        autoQuality: Boolean = true,
    ): LiveStream {
        if (roomId <= 0L) throw BiliException(-1, "直播间不存在")

        val json = api.getRaw(
            path = Endpoints.LIVE_PLAY_INFO,
            query = mapOf(
                "room_id" to roomId.toString(),
                // 0=FLV(progressive) 1=HLS —— 两条都要
                "protocol" to "0,1",
                // 0=flv 1=ts 2=fmp4
                "format" to "0,1,2",
                // 0=avc 1=hevc
                "codec" to "0,1",
                // 🔴 直播画质：10000 = 原画（服务端按账号权限降级，
                //    未登录会给到 250）。这就是"自动最高画质"的落点 ——
                //    与点播不同，直播**不需要**先探一遍再挑档：
                //    `qn` 只是"我想要多高"，给多少由服务端定。
                "qn" to if (autoQuality) "10000" else "250",
                "platform" to "web",
                "ptype" to "8",
                "dolby" to "5",
                "panorama" to "1",
            ),
            signed = false,
            host = Endpoints.LIVE_LIST_HOST,
        )

        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(code, json.optString("message", "直播取流失败"))
        }

        val data = json.optJSONObject("data")
            ?: throw BiliException(-1, "直播取流响应缺少 data")

        // live_status: 0=未开播 1=直播中 2=轮播
        val liveStatus = data.optInt("live_status", 0)
        if (liveStatus == 0) {
            // 未开播是**正常状态**，不是错误 —— 返回空流由 UI 显示空态
            return LiveStream("", "", 0, "", emptyList())
        }

        return LiveStreamParser.parse(data)
    }

    /**
     * 进入直播间上报（「隐身入场」的真实落点，v1.6.3）。
     *
     * ## 实测（2026-10-06，未登录直连）
     *
     * ```
     * POST roomEntryAction  room_id=1&ruid=0&platform=web
     *   -> code=0 / data=null        ✅ 接口真实可用
     * 缺 room_id -> code=-400          （必填参数）
     * GET        -> HTTP 405           （只接受 POST）
     * ```
     *
     * ## 调用与不调用的语义
     *
     * 这是**客户端主动上报**"我进了这个直播间"。所以：
     * - **调用** = 产生一次入场上报（与网页端行为一致）
     * - **不调用** = 本应用不产生这次上报 → 即用户要的"隐身入场"
     *
     * 控制权在本应用手里，所以这个开关**真实有效**，
     * 不是装饰性的（§1.6「空转设置项」的红线）。
     *
     * ## 与"应用内播放"的关系（v1.6.3 起）
     *
     * 直播已经改为**应用内播放**（不再跳浏览器），所以这个上报
     * 成为本应用进入直播间的**唯一**入口上报点 —— 开关的语义
     * 因此是完整、无歧义的：
     * - 关：进入直播间时上报一次（默认，与官方网页一致）
     * - 开：**完全不发**这个请求
     *
     * @return true = 上报成功（`code == 0`）；false = 未上报或失败。
     *   调用方**不应**因为 false 而阻断观看 —— 上报失败不影响播放。
     */
    suspend fun reportEntry(roomId: Long): Boolean {
        if (roomId <= 0L) return false
        return runCatching {
            val json = api.postForm(
                path = Endpoints.LIVE_ENTRY_ACTION,
                form = mapOf(
                    "room_id" to roomId.toString(),
                    // ruid=0 表示"不指定被邀请人"（实测可通）。
                    // 不要传自己的 mid —— 那会把入场上报变成一次
                    // "谁邀请了我"的关系上报，语义完全不同。
                    "ruid" to "0",
                    "platform" to "web",
                ),
                host = Endpoints.LIVE_LIST_HOST,
            )
            json.optInt("code", -1) == 0
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------------
    // 弹幕接入（v1.6.4）
    // ---------------------------------------------------------------------

    /**
     * 取弹幕 WebSocket 接入信息（`getDanmuInfo`）。
     *
     * ## 🔴 必须带 WBI 签名（实测）
     *
     * 不签名 → `-352`；签名 + buvid → `code=0`。
     * 返回 `token` 与 `host_list`（实测 `wss_port=2245`）。
     *
     * ## 失败必须抛异常
     *
     * 返回空的 host 列表会让上层"静默连不上"，而用户只看到
     * 聊天区一片空白 —— 分不清"没人说话"与"根本没连上"。
     */
    suspend fun danmakuInfo(roomId: Long): LiveDanmakuInfo {
        if (roomId <= 0L) throw BiliException(-1, "直播间不存在")

        val json = api.getLiveRaw(
            path = Endpoints.LIVE_DANMU_INFO,
            query = mapOf("id" to roomId.toString(), "type" to "0"),
            buvid = buvidProvider(),
        )

        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(code, json.optString("message", "弹幕服务不可用"))
        }

        val data = json.optJSONObject("data")
            ?: throw BiliException(-1, "弹幕接入信息缺少 data")

        val token = data.optString("token")
        if (token.isEmpty()) throw BiliException(-1, "弹幕接入信息缺少 token")

        val hosts = ArrayList<DanmakuHost>()
        val arr = data.optJSONArray("host_list")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val host = o.optString("host")
                val port = o.optInt("wss_port", 0)
                if (host.isNotEmpty() && port > 0) hosts.add(DanmakuHost(host, port))
            }
        }
        if (hosts.isEmpty()) throw BiliException(-1, "弹幕接入信息没有可用服务器")

        return LiveDanmakuInfo(token = token, hosts = hosts)
    }

    /**
     * 取直播间主播 uid（用于身份判定）。
     *
     * 用 `get_anchor_in_room` —— 实测**不需要 WBI 签名**（`code=0`），
     * 比 `getInfoByRoom` 少一层依赖，失败面更小。
     *
     * 失败返回 0（**增强信息，不阻断**）：拿不到主播 uid 只会
     * 少显示一个"主播"徽章，不该让整个直播间打不开。
     */
    suspend fun anchorUid(roomId: Long): Long {
        if (roomId <= 0L) return 0L
        return runCatching {
            val json = api.getRaw(
                path = Endpoints.LIVE_ANCHOR_IN_ROOM,
                query = mapOf("roomid" to roomId.toString()),
                signed = false,
                host = Endpoints.LIVE_LIST_HOST,
            )
            if (json.optInt("code", -1) != 0) return@runCatching 0L
            json.optJSONObject("data")
                ?.optJSONObject("info")
                ?.optLong("uid", 0L) ?: 0L
        }.getOrDefault(0L)
    }

    // ---------------------------------------------------------------------
    // 房管操作（v1.6.4）
    // ---------------------------------------------------------------------

    /**
     * 禁言。
     *
     * ## 参数（端点实测存在；参数集按官方网页端形态）
     *
     * `room_id` / `tuid` / `mobile_app=web` / `hour` / `min` / `csrf`
     *
     * ## ⚠️ 禁言时长范围**未知**
     *
     * 本项目**无法在未登录状态探测**服务端接受的范围，
     * 所以 UI **不写死**"支持 1 分钟"这类断言 —— 只提供常用档位，
     * 真实边界由服务端裁决（越界会返回错误码，UI 如实显示）。
     */
    suspend fun mute(
        roomId: Long,
        targetUid: Long,
        durationMinutes: Int,
        csrf: String,
    ): ModerationResult = moderation(
        path = Endpoints.LIVE_MUTE,
        form = mapOf(
            "room_id" to roomId.toString(),
            "tuid" to targetUid.toString(),
            "mobile_app" to "web",
            "hour" to (durationMinutes / 60).toString(),
            "min" to (durationMinutes % 60).toString(),
            "csrf" to csrf,
        ),
    )

    /** 解除禁言。 */
    suspend fun unmute(
        roomId: Long,
        targetUid: Long,
        csrf: String,
    ): ModerationResult = moderation(
        path = Endpoints.LIVE_UNMUTE,
        form = mapOf(
            "room_id" to roomId.toString(),
            "tuid" to targetUid.toString(),
            "mobile_app" to "web",
            "csrf" to csrf,
        ),
    )

    /** 踢出直播间。 */
    suspend fun kick(
        roomId: Long,
        targetUid: Long,
        csrf: String,
    ): ModerationResult = moderation(
        path = Endpoints.LIVE_KICK,
        form = mapOf(
            "room_id" to roomId.toString(),
            "tuid" to targetUid.toString(),
            "mobile_app" to "web",
            "csrf" to csrf,
        ),
    )

    /** 加入黑名单。 */
    suspend fun block(
        roomId: Long,
        targetUid: Long,
        csrf: String,
    ): ModerationResult = moderation(
        path = Endpoints.LIVE_BLOCK_ADD,
        form = mapOf(
            "room_id" to roomId.toString(),
            "tuid" to targetUid.toString(),
            "mobile_app" to "web",
            "csrf" to csrf,
        ),
    )

    /** 移出黑名单。 */
    suspend fun unblock(
        roomId: Long,
        targetUid: Long,
        csrf: String,
    ): ModerationResult = moderation(
        path = Endpoints.LIVE_BLOCK_DEL,
        form = mapOf(
            "room_id" to roomId.toString(),
            "tuid" to targetUid.toString(),
            "mobile_app" to "web",
            "csrf" to csrf,
        ),
    )

    // ---------------------------------------------------------------------
    // 发送弹幕（v1.6.5）
    // ---------------------------------------------------------------------

    /**
     * 发送一条直播弹幕。
     *
     * ## 🔴 与视频弹幕**不是**同一个接口
     *
     * | 用途 | 接口 |
     * |---|---|
     * | **直播**弹幕 | `msg/send`（`api.live.bilibili.com`）|
     * | **视频**弹幕 | `x/v2/dm/post`（`api.bilibili.com`）|
     *
     * 实测 `msg/send` 返回「账号未登录」→ **存在且需要登录**
     * （不是 404、不是 1000003）。
     *
     * ## ⚠️ 成功分支未验证
     *
     * 参数集按官方网页端形态写，但本项目**没有可用登录账号**验证成功分支。
     * 所以：
     * - 返回 [ModerationResult]，失败分类复用同一套（§7.22-138/142）
     * - **绝不**把失败显示成成功
     *
     * ## 为什么复用 [ModerationResult]
     *
     * 它与"房管操作"共用同一套错误语义（未登录 / 权限不足 / 限流 / 网络…），
     * 而发送弹幕的失败形态**完全一样**。新造一个结果类型只会让
     * 错误处理出现第二套判断（本项目明确禁止）。
     *
     * @param text 弹幕正文（调用方需先做长度校验 —— 服务端也会校验）
     * @param mode 弹幕模式：1=滚动 4=底部 5=顶部（与视频弹幕同一套枚举）
     */
    suspend fun sendDanmaku(
        roomId: Long,
        text: String,
        csrf: String,
        mode: Int = 1,
        color: Int = 0xFFFFFF,
        fontSize: Int = 25,
    ): ModerationResult {
        if (roomId <= 0L) {
            return ModerationResult.Failure(
                kind = ModerationResult.Failure.Kind.INVALID_TARGET,
                message = "直播间无效",
            )
        }
        if (text.isBlank()) {
            return ModerationResult.Failure(
                kind = ModerationResult.Failure.Kind.INVALID_TARGET,
                message = "弹幕内容不能为空",
            )
        }

        return try {
            val json = api.postForm(
                path = Endpoints.LIVE_SEND_MSG,
                form = mapOf(
                    "msg" to text,
                    "roomid" to roomId.toString(),
                    "csrf" to csrf,
                    // rnd 是防重放随机数；官方网页端每次都带
                    "rnd" to (System.currentTimeMillis() / 1000).toString(),
                    "color" to color.toString(),
                    "fontsize" to fontSize.toString(),
                    "mode" to mode.toString(),
                    // bubble=0：不带头像气泡（第三方客户端没有气泡资源）
                    "bubble" to "0",
                ),
                host = Endpoints.LIVE_LIST_HOST,
            )
            val code = json.optInt("code", -1)
            if (code == 0) {
                ModerationResult.Success
            } else {
                // 发送失败的错误码与房管操作同源，复用同一映射
                LiveErrorMapper.fromCode(code, json.optString("message"))
            }
        } catch (e: Exception) {
            LiveErrorMapper.fromException(e)
        }
    }

    /**
     * 房管操作的公共执行 + **错误分类**。
     *
     * 需求第 9 条：不能"请求失败 → 当成成功 → UI 显示操作成功"。
     * 这里把每个业务码都翻成明确的 [ModerationResult]，
     * 且**保留原始 message**（不吞）。
     *
     * ## 为什么不用 `runCatching{}.getOrDefault(Success)`
     *
     * 那正是项目踩过的"失败伪装成空/成功"的坑（§7.8-44）。
     * 这里网络异常也走 [LiveErrorMapper.fromException]，
     * 明确归为 NETWORK 失败。
     */
    private suspend fun moderation(
        path: String,
        form: Map<String, String>,
    ): ModerationResult = try {
        val json = api.postForm(
            path = path,
            form = form,
            host = Endpoints.LIVE_LIST_HOST,
        )
        val code = json.optInt("code", -1)
        if (code == 0) {
            ModerationResult.Success
        } else {
            LiveErrorMapper.fromCode(code, json.optString("message"))
        }
    } catch (e: Exception) {
        LiveErrorMapper.fromException(e)
    }

    /**
     * 弹幕接入信息。
     *
     * @param token 认证 token（约 244~252 字符，实测）
     * @param hosts 候选接入点；实测 `wss_port=2245`
     */
    data class LiveDanmakuInfo(
        val token: String,
        val hosts: List<DanmakuHost>,
    )
}

/**
 * 一个直播间条目。
 *
 * @param online 在线人数（已从 `"1.2万"` 这类展示串解析成整数）。
 */
data class LiveRoom(
    val roomId: Long,
    val title: String,
    val cover: String,
    val uname: String,
    val face: String,
    val online: Int,
    val areaName: String,
    val uid: Long,
) {
    fun coverUrl(width: Int = 480): String =
        com.example.biliv3.data.model.CoverUrls.cover(cover, width)

    fun faceUrl(size: Int = 96): String =
        com.example.biliv3.data.model.CoverUrls.avatar(face, size)
}

/** 直播列表分页。 */
data class LivePage(
    val rooms: List<LiveRoom>,
    val hasMore: Boolean,
)
