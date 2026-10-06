package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.api.Endpoints
import org.json.JSONObject

/**
 * 直播列表仓库。
 *
 * ## 为什么首版完全没有
 *
 * `Endpoints.LIVE_LIST` 常量**早就定义了**（`api.live.bilibili.com`），
 * 但从未被任何代码调用；首页右侧栏的「正在直播」模块里
 * `HomeRepository` 恒传 `emptyList()`，整块永不渲染。
 * 这是「直播」被判为完全缺失的直接原因。
 *
 * ## ⚠️ 直播接口在独立域名
 *
 * `api.live.bilibili.com`，不在 `api.bilibili.com` ——
 * 走错域名得到 404 或空 body。这是本项目接入直播最容易踩的坑。
 *
 * ## 范围
 *
 * 只做**直播列表 + 直播间信息**。
 * 直播流播放（HTTP-FLV / HLS）需要另一套播放路径（ExoPlayer 的
 * FLV 扩展或 Media3 的 HLS），本项目当前只把「有哪些直播」呈现出来，
 * 点击进入后显示房间信息并给跳转系统浏览器的出口 ——
 * 不做半成品的播放（那是"点了没画面"的糟糕体验）。
 */
class LiveRepository(
    private val api: BiliApi,
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
     * @return 可播地址集合；`playable == false` 表示**确实没流**
     *         （例如主播未开播），这是**正常状态**，由 UI 显示空态。
     */
    suspend fun stream(roomId: Long): LiveStream {
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
                // 10000 = 原画（服务端按账号权限降级，未登录会给到 250）
                "qn" to "10000",
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
