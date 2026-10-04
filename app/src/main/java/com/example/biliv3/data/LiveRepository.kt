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
