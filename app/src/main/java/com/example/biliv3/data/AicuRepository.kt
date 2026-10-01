package com.example.biliv3.data

import com.example.biliv3.data.api.AicuApi
import com.example.biliv3.data.api.AicuException
import com.example.biliv3.data.model.CoverUrls

/**
 * aicu.cc（B 站查成分）仓库。
 *
 * 数据来源：第三方站点 [aicu.cc](https://www.aicu.cc/)，其数据来自 B 站公开数据。
 * **本项目个人自用、不公开分发**，且遵守 aicu 的排队机制（本身就是一种礼貌限速）。
 *
 * ## 能力
 *
 * | 能力 | 接口 | 备注 |
 * |---|---|---|
 * | 评论 | `/api/v4/search/getreply` | 含楼中楼标记 |
 * | 视频弹幕 | `/api/v4/search/getvideodm` | |
 * | 直播弹幕 | `/api/v4/search/getlivedm` | |
 * | 用户成分标记 | `/api/v4/user/getusermark` | |
 * | 用户资料 | `worker.aicu.cc/api/bili/space` | B 站 `card` 结构 |
 *
 * ## ⚠️ `getlivebyup` 已关闭，不做入口
 *
 * 实测返回 `{"code":-403,"message":"暂时关闭 不调好不发布"}`。
 * 给它挂 UI 就是**死入口**（看着能点、点了报错），
 * 所以本仓库不提供该方法，UI 也不出现对应按钮。
 *
 * ## 字段坑（全部实测，见 `AGENTS.md` §10.4）
 *
 * 这些字段的类型/语义都与直觉不同，**照抄直觉写法会得到恒为 0 或跳错站**：
 *
 * | 字段 | 直觉 | 实际 |
 * |---|---|---|
 * | 评论 `rank` | 热度 | **1 = 一级评论，2 = 楼中楼回复** |
 * | 评论 `time` | 毫秒 | **秒级**时间戳 |
 * | 弹幕 `progress` | 秒 | **毫秒**（20639 ≈ 20.6s） |
 * | 弹幕 `oid` | bvid | **av 号**（纯数字） |
 * | 直播 `roomid` / `upuid` / `uid` | 数字 | **全是字符串**，`optLong` 会得 0 |
 * | 评论 `dyn.type` | — | 决定跳转站点，**写死一种会跳错站** |
 */
class AicuRepository(
    private val api: AicuApi,
) {

    // ---------------- 评论 ----------------

    /**
     * 查某 UID 在 B 站的评论。
     *
     * @param needCount true 时接口返回真实 `all_count`（更慢）；
     *   false 时 `all_count` 恒为 -1。首屏默认 false 以求快。
     */
    suspend fun replies(
        uid: Long,
        page: Int = 1,
        pageSize: Int = 20,
        needCount: Boolean = false,
        keyword: String? = null,
    ): AicuReplyPage {
        val query = linkedMapOf(
            "uid" to uid.toString(),
            "pn" to page.toString(),
            "ps" to pageSize.toString(),
            "need_count" to if (needCount) "1" else "0",
        )
        if (!keyword.isNullOrBlank()) query["keyword"] = keyword

        val json = api.getJson("/api/v4/search/getreply", query)
        if (api.intField(json, "code") != 0) {
            throw AicuException(api.intField(json, "code") ?: -1, api.stringField(json, "message").orEmpty())
        }

        val data = api.jsonField(json, "data") ?: return AicuReplyPage(emptyList(), 0)
        val items = api.splitArray(api.jsonArray(data, "replies")).mapNotNull { parseReply(it) }
        val allCount = api.intField(data, "all_count") ?: -1

        return AicuReplyPage(items, allCount)
    }

    private fun parseReply(o: String): AicuReply? {
        val rpid = api.stringField(o, "rpid")?.takeIf { it.isNotEmpty() } ?: return null
        val message = api.stringField(o, "message").orEmpty()

        // rank: 1 = 一级评论，2 = 楼中楼回复（**不是热度**）
        val rank = api.intField(o, "rank") ?: 1

        // dyn.type 决定跳转站点；oid 是该类型下的 id
        val dyn = api.jsonField(o, "dyn")
        val dynType = dyn?.let { api.intField(it, "type") } ?: 0
        val oid = dyn?.let { api.stringField(it, "oid") }.orEmpty()

        // parent 仅 rank=2 时才有 {rootid, parentid}
        val parent = api.jsonField(o, "parent")
        val rootId = parent?.let { api.stringField(it, "rootid") }.orEmpty()

        return AicuReply(
            rpid = rpid,
            message = message,
            // time 是**秒级**时间戳
            timeSeconds = api.longField(o, "time") ?: 0L,
            isNested = rank == 2,
            dynType = dynType,
            oid = oid,
            rootId = rootId,
            // 跳转链接在这里一次算好 —— UI 层不再重复实现这套映射
            targetUrl = replyTargetUrl(dynType, oid, rpid),
        )
    }

    // ---------------- 视频弹幕 ----------------

    /**
     * 查某 UID 发出的视频弹幕。
     *
     * ⚠️ `progress` 是**毫秒**，不是秒 —— 直接当秒用会得到
     * "20 分钟"（实际 20.6 秒）这种离谱的时间轴。
     */
    suspend fun videoDanmaku(
        uid: Long,
        page: Int = 1,
        pageSize: Int = 20,
        needCount: Boolean = false,
    ): AicuVideoDanmakuPage {
        val query = linkedMapOf(
            "uid" to uid.toString(),
            "pn" to page.toString(),
            "ps" to pageSize.toString(),
            "need_count" to if (needCount) "1" else "0",
        )

        val json = api.getJson("/api/v4/search/getvideodm", query)
        if (api.intField(json, "code") != 0) {
            throw AicuException(api.intField(json, "code") ?: -1, api.stringField(json, "message").orEmpty())
        }

        val data = api.jsonField(json, "data") ?: return AicuVideoDanmakuPage(emptyList(), 0)
        val items = api.splitArray(api.jsonArray(data, "videodmlist")).mapNotNull { parseVideoDm(it) }
        return AicuVideoDanmakuPage(items, api.intField(data, "all_count") ?: -1)
    }

    private fun parseVideoDm(o: String): AicuVideoDanmaku? {
        val content = api.stringField(o, "content") ?: return null

        // oid 是 **av 号**（纯数字），不是 bvid
        val oid = api.stringField(o, "oid").orEmpty()

        return AicuVideoDanmaku(
            id = api.stringField(o, "id").orEmpty(),
            content = content,
            // progress 是**毫秒**
            progressMs = api.longField(o, "progress") ?: 0L,
            // ctime 是秒级发送时间
            ctimeSeconds = api.longField(o, "ctime") ?: 0L,
            oid = oid,
            // 跳转用 av 号（B 站对 av 号兼容良好）
            targetUrl = if (oid.isNotEmpty()) "https://www.bilibili.com/video/av$oid" else "",
        )
    }

    // ---------------- 直播弹幕 ----------------

    /**
     * 查某 UID 发出的直播弹幕。
     *
     * ⚠️ `roomid` / `upuid` / `uid` **全是字符串**，用强类型 `optLong`
     * 会恒得 0（"房间号永远是 0"的典型成因）。本项目的
     * [AicuApi.longField] 已兼容两种形态。
     */
    suspend fun liveDanmaku(
        uid: Long,
        page: Int = 1,
        pageSize: Int = 20,
    ): AicuLiveDanmakuPage {
        val query = linkedMapOf(
            "uid" to uid.toString(),
            "pn" to page.toString(),
            "ps" to pageSize.toString(),
        )

        val json = api.getJson("/api/v4/search/getlivedm", query)
        if (api.intField(json, "code") != 0) {
            throw AicuException(api.intField(json, "code") ?: -1, api.stringField(json, "message").orEmpty())
        }

        val data = api.jsonField(json, "data") ?: return AicuLiveDanmakuPage(emptyList())
        val items = api.splitArray(api.jsonArray(data, "list")).mapNotNull { parseLiveDm(it) }
        return AicuLiveDanmakuPage(items)
    }

    private fun parseLiveDm(o: String): AicuLiveDanmaku? {
        val room = api.jsonField(o, "roominfo") ?: return null

        val roomId = api.longField(room, "roomid") ?: 0L
        val upUid = api.longField(room, "upuid") ?: 0L
        val upName = api.stringField(room, "upname").orEmpty()
        val roomName = api.stringField(room, "roomname").orEmpty()

        val danmu = api.splitArray(api.jsonArray(o, "danmu")).mapNotNull { d ->
            val text = api.stringField(d, "text") ?: return@mapNotNull null
            AicuLiveDanmuLine(
                uid = api.longField(d, "uid") ?: 0L,
                uname = api.stringField(d, "uname").orEmpty(),
                text = text,
                // ts 是秒级时间戳
                tsSeconds = api.longField(d, "ts") ?: 0L,
            )
        }

        if (danmu.isEmpty() && roomId <= 0L) return null

        return AicuLiveDanmaku(
            roomId = roomId,
            roomName = roomName,
            upName = upName,
            upUid = upUid,
            lines = danmu,
            targetUrl = if (roomId > 0L) "https://live.bilibili.com/$roomId" else "",
        )
    }

    // ---------------- 用户成分标记 ----------------

    /** 用户成分标记（`{gh, gh2, device[], text, tag[], hname[]}`）。失败返回 null。 */
    suspend fun userMark(uid: Long): AicuUserMark? {
        val json = runCatching {
            api.getJson("/api/v4/user/getusermark", mapOf("uid" to uid.toString()))
        }.getOrNull() ?: return null

        if (api.intField(json, "code") != 0) return null
        val data = api.jsonField(json, "data") ?: return null

        return AicuUserMark(
            gh = api.stringField(data, "gh").orEmpty(),
            gh2 = api.stringField(data, "gh2").orEmpty(),
            text = api.stringField(data, "text").orEmpty(),
            devices = api.splitArray(api.jsonArray(data, "device"))
                .mapNotNull { api.stringField(it, "name") ?: api.stringField(it, "device") },
            tags = splitStringArray(api.jsonArray(data, "tag")),
            hnames = splitStringArray(api.jsonArray(data, "hname")),
        )
    }

    /**
     * 用户资料（走 `worker.aicu.cc`，返回 B 站 `card` 结构）。
     *
     * 失败返回 null —— 资料卡是增强信息，拿不到不该阻断整个页面。
     */
    suspend fun userCard(uid: Long): AicuUserCard? {
        val json = runCatching {
            // ⚠️ 用户资料在 **worker.aicu.cc**，不是主 API 域。
            // 走 api.aicu.cc 会 404 或返回空 body（静默无资料卡）。
            api.getRaw(
                path = "/api/bili/space",
                query = mapOf("mid" to uid.toString()),
                withTicket = false,
                baseUrl = AicuApi.WORKER_BASE_URL,
            )
        }.getOrNull() ?: return null

        // 该接口的 data 里既有 card 也有 archive_count
        val data = api.jsonField(json, "data") ?: return null
        val card = api.jsonField(data, "card") ?: return null

        return AicuUserCard(
            mid = api.longField(card, "mid") ?: uid,
            name = api.stringField(card, "name").orEmpty(),
            face = api.stringField(card, "face").orEmpty(),
            sign = api.stringField(card, "sign").orEmpty(),
            fans = api.longField(card, "fans") ?: 0L,
            archiveCount = api.intField(data, "archive_count") ?: 0,
        )
    }

    /** 站点公告。失败返回空串。 */
    suspend fun notice(): String {
        val json = runCatching { api.getRaw("/api/v4/home/getnotice", withTicket = false) }.getOrNull()
            ?: return ""
        if (api.intField(json, "code") != 0) return ""
        val data = api.jsonField(json, "data") ?: return ""
        return api.stringField(data, "text").orEmpty()
    }

    // ---------------- 纯函数（必须单测）----------------

    private fun splitStringArray(arrayJson: String?): List<String> {
        if (arrayJson.isNullOrEmpty()) return emptyList()
        return STRING_ELEMENT.findAll(arrayJson)
            .map { it.groupValues[1].let(api::unescapeJson) }
            .filter { it.isNotEmpty() }
            .toList()
    }

    companion object {
        private val STRING_ELEMENT = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")

        /**
         * 评论跳转 URL。
         *
         * ## ⚠️ 必须按 `dyn.type` 分流
         *
         * 评论可能挂在视频 / 专栏 / 动态上，**id 空间完全不同**。
         * 写死一种（比如永远拼 `/video/av{oid}`）会把专栏和动态的评论
         * 跳到**不存在的视频页**，表现为"点进去 404"。
         *
         * | type | 含义 | 跳转 |
         * |---|---|---|
         * | 1 | 视频 | `www.bilibili.com/video/av{oid}#reply{rpid}` |
         * | 12 | 专栏 | `www.bilibili.com/read/cv{oid}#reply{rpid}` |
         * | 17 | 动态 | `t.bilibili.com/{oid}#reply{rpid}` |
         * | 其他 | 兜底 | `t.bilibili.com/{oid}?type={pageType}#reply{rpid}` |
         *
         * `oid` 为空时返回空串（UI 据此禁用点击，而不是跳到坏链接）。
         */
        fun replyTargetUrl(dynType: Int, oid: String, rpid: String): String {
            if (oid.isEmpty()) return ""
            val anchor = if (rpid.isNotEmpty()) "#reply$rpid" else ""
            return when (dynType) {
                1 -> "https://www.bilibili.com/video/av$oid$anchor"
                12 -> "https://www.bilibili.com/read/cv$oid$anchor"
                17 -> "https://t.bilibili.com/$oid$anchor"
                else -> "https://t.bilibili.com/$oid?type=${pageTypeFor(dynType)}$anchor"
            }
        }

        /**
         * 楼中楼详情页链接（h5 版）。
         *
         * 用于 `rank = 2` 的回复 —— 点它跳到该条回复所在的楼中楼上下文，
         * 而不是一级评论。
         */
        fun nestedReplyUrl(oid: String, dynType: Int, rpid: String): String {
            if (oid.isEmpty() || rpid.isEmpty()) return ""
            return "https://www.bilibili.com/h5/comment/sub" +
                "?oid=$oid&pageType=${pageTypeFor(dynType)}&root=$rpid"
        }

        /**
         * `dyn.type` → `pageType` 映射（楼中楼链接用）。
         *
         * 映射表实测来自 aicu 前端：
         * `11→2`、`1→8`、`12→64`、`14→256`。
         */
        fun pageTypeFor(dynType: Int): Int = when (dynType) {
            11 -> 2
            1 -> 8
            12 -> 64
            14 -> 256
            else -> dynType
        }

        /** 弹幕时间轴：毫秒 → `mm:ss`。 */
        fun formatDanmakuTime(progressMs: Long): String {
            val total = (progressMs / 1000).coerceAtLeast(0)
            val m = total / 60
            val s = total % 60
            return "%d:%02d".format(m, s)
        }
    }
}

// ---------------- 模型 ----------------

/**
 * 一条评论。
 *
 * @param isNested `rank == 2` 即楼中楼回复。**不是热度** ——
 *   按热度理解会把所有回复当成一级评论。
 * @param targetUrl 已按 `dynType` 分流的跳转链接，空串表示不可跳。
 */
data class AicuReply(
    val rpid: String,
    val message: String,
    val timeSeconds: Long,
    val isNested: Boolean,
    val dynType: Int,
    val oid: String,
    val rootId: String,
    val targetUrl: String,
)

/** 评论分页。[allCount] 为 -1 表示"未请求真实总数"。 */
data class AicuReplyPage(
    val items: List<AicuReply>,
    val allCount: Int,
)

/**
 * 一条视频弹幕。
 *
 * @param progressMs **毫秒**（接口原样，未换算）。展示用
 *   [AicuRepository.formatDanmakuTime]。
 * @param oid **av 号**（纯数字），不是 bvid。
 */
data class AicuVideoDanmaku(
    val id: String,
    val content: String,
    val progressMs: Long,
    val ctimeSeconds: Long,
    val oid: String,
    val targetUrl: String,
)

data class AicuVideoDanmakuPage(
    val items: List<AicuVideoDanmaku>,
    val allCount: Int,
)

/** 直播弹幕里的一条发言。 */
data class AicuLiveDanmuLine(
    val uid: Long,
    val uname: String,
    val text: String,
    val tsSeconds: Long,
)

/**
 * 一个直播间的弹幕集合。
 *
 * @param roomId 接口给的是**字符串**，已在仓库层转成 Long。
 */
data class AicuLiveDanmaku(
    val roomId: Long,
    val roomName: String,
    val upName: String,
    val upUid: Long,
    val lines: List<AicuLiveDanmuLine>,
    val targetUrl: String,
)

data class AicuLiveDanmakuPage(
    val items: List<AicuLiveDanmaku>,
)

/** 用户成分标记。 */
data class AicuUserMark(
    val gh: String,
    val gh2: String,
    val text: String,
    val devices: List<String>,
    val tags: List<String>,
    val hnames: List<String>,
) {
    /** 是否有任何可展示的内容。全空时 UI 不渲染这一块（不留空壳）。 */
    val hasContent: Boolean
        get() = gh.isNotEmpty() || gh2.isNotEmpty() || text.isNotEmpty() ||
            devices.isNotEmpty() || tags.isNotEmpty() || hnames.isNotEmpty()
}

/** aicu 返回的用户资料（B 站 `card` 结构）。 */
data class AicuUserCard(
    val mid: Long,
    val name: String,
    val face: String,
    val sign: String,
    val fans: Long,
    val archiveCount: Int,
) {
    fun faceUrl(size: Int = 96): String = CoverUrls.avatar(face, size)
}
