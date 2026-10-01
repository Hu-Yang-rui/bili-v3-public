package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.model.CoverUrls
import org.json.JSONObject

/**
 * 番剧 / 影视仓库。
 *
 * ## 实测可用性（2026-09-28）
 *
 * | 接口 | 结果 |
 * |---|---|
 * | `pgc/season/index/result`（索引） | ✅ `code=0`，10 条真实数据 |
 * | `pgc/web/timeline`（时间表） | ✅ `code=0` |
 * | `pgc/view/web/season`（详情） | ⚠️ 需真实 season_id |
 *
 * 番剧索引按 `season_type` 区分内容：
 * - `1` = 番剧
 * - `2` = 电影
 * - `3` = 纪录片
 * - `4` = 国创
 * - `5` = 电视剧
 * - `7` = 综艺
 */
class BangumiRepository(
    private val api: BiliApi,
) {

    /**
     * 番剧索引（分类浏览）。
     *
     * @param seasonType 1=番剧 2=电影 3=纪录片 4=国创 5=电视剧 7=综艺
     * @param order 排序：3=追番人数 4=更新时间 5=播放量
     */
    suspend fun index(
        seasonType: Int = 1,
        page: Int = 1,
        pageSize: Int = 20,
        order: Int = 3,
    ): List<BangumiItem> {
        val json = try {
            api.getRaw(
                path = "pgc/season/index/result",
                query = mapOf(
                    "season_type" to seasonType.toString(),
                    "page" to page.toString(),
                    "pagesize" to pageSize.toString(),
                    "order" to order.toString(),
                    "st" to "1",
                    "sort" to "0",
                    "type" to "1",
                ),
                signed = false,
            )
        } catch (_: Exception) {
            return emptyList()
        }
        if (json.optInt("code", -1) != 0) return emptyList()

        val arr = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<BangumiItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseItem(o)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 番剧详情。
     *
     * ⚠️ 必须传真实的 `season_id` 或 `ep_id`，传 0/1 会返回 `-404`。
     */
    suspend fun season(seasonId: Long): BangumiDetail? {
        if (seasonId <= 0) return null

        val json = try {
            api.getRaw(
                path = "pgc/view/web/season",
                query = mapOf("season_id" to seasonId.toString()),
                signed = false,
            )
        } catch (_: Exception) {
            return null
        }
        if (json.optInt("code", -1) != 0) return null

        val d = json.optJSONObject("result") ?: json.optJSONObject("data") ?: return null

        // 剧集列表
        //
        // ⚠️ 实测字段语义（容易搞反）：
        //   title      = "1"                ← 集数序号，不是标题
        //   long_title = "为了消灭鬼舞辻无惨"  ← 真正的剧集标题
        // 展示标题要取 long_title，序号取 title。
        val epsArr = d.optJSONArray("episodes")
        val episodes = ArrayList<BangumiEpisode>()
        if (epsArr != null) {
            for (i in 0 until epsArr.length()) {
                val o = epsArr.optJSONObject(i) ?: continue
                val epIndex = o.optString("title")
                episodes.add(
                    BangumiEpisode(
                        epId = o.optLong("id"),
                        aid = o.optLong("aid"),
                        bvid = o.optString("bvid"),
                        title = o.optString("long_title").ifEmpty { epIndex },
                        cover = o.optString("cover"),
                        // duration 单位是**毫秒**
                        durationSeconds = (o.optLong("duration") / 1000).toInt(),
                        index = epIndex,
                    ),
                )
            }
        }

        return BangumiDetail(
            seasonId = d.optLong("season_id"),
            title = d.optString("title"),
            cover = d.optString("cover"),
            evaluate = d.optString("evaluate"),
            // 评分：接口在 `rating.score` 里给，顶层 `rating` 偶尔直接是数字
            score = parseScore(d.opt("rating")),
            totalEpisodes = episodes.size,
            episodes = episodes,
            // 追番态：`user_status.follow`（0=未追 1=已追）。未登录可能缺失。
            isFollowed = d.optJSONObject("user_status")?.optInt("follow", 0) == 1,
            // 追番人数：详情接口有 `stat.follow`（索引接口没有，见 parseItem）
            followCount = d.optJSONObject("stat")?.optInt("follow", 0) ?: 0,
        )
    }

    /**
     * 追番 / 取消追番。
     *
     * ## ⚠️ 这个接口的域名与参数都比较特殊
     *
     * `pgc/app/follow` 走主 API 域，参数是 `season_id` + `status`：
     * - `status=1` 追番
     * - `status=2` 取消追番
     *
     * 需要 csrf。未登录直接失败（不发无谓请求）。
     */
    suspend fun setFollow(
        seasonId: Long,
        follow: Boolean,
        store: com.example.biliv3.data.auth.AuthStore,
    ): Result<Unit> {
        if (seasonId <= 0) return Result.failure(IllegalArgumentException("剧集不存在"))
        if (!store.isLoggedIn) {
            return Result.failure(com.example.biliv3.data.NotLoggedInException())
        }
        val csrf = store.biliJct
        if (csrf.isEmpty()) {
            return Result.failure(com.example.biliv3.data.NotLoggedInException())
        }

        return runCatching {
            val json = api.postForm(
                path = com.example.biliv3.data.api.Endpoints.PGC_FOLLOW,
                form = mapOf(
                    "season_id" to seasonId.toString(),
                    "status" to if (follow) "1" else "2",
                    "csrf" to csrf,
                ),
            )
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw IllegalStateException(
                    json.optString("message").ifEmpty { "操作失败（$code）" },
                )
            }
        }
    }

    /**
     * 是否已追番。
     *
     * 从 `season` 详情的 `user_status.follow` 读（0=未追 1=已追）。
     * 未登录时该字段可能缺失 → 返回 false，UI 显示"追番"，点击引导登录。
     */
    fun isFollowed(detail: BangumiDetail): Boolean = detail.isFollowed

    /**
     * 解析评分。
     *
     * `rating` 字段类型不稳定：实测是对象 `{"score":9.6,...}`，
     * 但个别接口返回数字。统一容错。
     */
    private fun parseScore(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is JSONObject -> v.optDouble("score", 0.0)
        else -> 0.0
    }

    /**
     * 解析索引条目。
     *
     * ## ⚠️ 实测字段核对（2026-09-28）
     *
     * 索引接口（`pgc/season/index/result`）的实际字段：
     * ```
     * badge badge_info badge_type cover first_ep index_show is_finish
     * link media_id order order_type score season_id season_status
     * season_type subTitle title title_icon
     * ```
     *
     * 两个与原实现不符的地方：
     * 1. **评分在顶层 `score`（数字），不是 `rating.score`**
     *    —— 索引接口根本没有 `rating` 字段，读它会恒为 0
     * 2. **没有 `follow` 字段** —— 追番人数读不到，恒为 0
     *
     * 这两个都不会报错，只会让评分/人数永远显示 0。
     */
    private fun parseItem(o: JSONObject): BangumiItem? {
        val seasonId = o.optLong("season_id", 0L)
        if (seasonId == 0L) return null

        val title = o.optString("title")
        if (title.isEmpty()) return null

        return BangumiItem(
            seasonId = seasonId,
            title = title,
            cover = o.optString("cover"),
            // 索引接口的评分在顶层 score（数字）；详情接口才在 rating.score
            score = optDoubleLoose(o, "score"),
            // 更新状态，如「全8话」
            indexShow = o.optString("index_show"),
            // 索引接口不返回 follow —— 保持 0，UI 不展示
            followCount = 0,
            // 索引接口不返回 evaluate —— 保持空，UI 不展示
            evaluate = "",
        )
    }

    /** 数字字段容错（可能是 Int / Double / String）。 */
    private fun optDoubleLoose(o: JSONObject, key: String): Double = when (val v = o.opt(key)) {
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    companion object {
        /** 内容类型。 */
        const val TYPE_BANGUMI = 1
        const val TYPE_MOVIE = 2
        const val TYPE_DOCUMENTARY = 3
        const val TYPE_GUOCHUANG = 4
        const val TYPE_TV = 5
        const val TYPE_VARIETY = 7
    }
}

/** 番剧索引条目。 */
data class BangumiItem(
    val seasonId: Long,
    val title: String,
    val cover: String,
    val score: Double,
    /** 更新状态，如「更新至第 12 话」。 */
    val indexShow: String,
    val followCount: Int,
    val evaluate: String,
) {
    /**
     * 番剧海报地址。
     *
     * ⚠️ 番剧封面是 **3:2 竖版海报**，与视频封面的 16:9 不同 ——
     * 按 16:9 裁切会把人物头部切掉。所以这里传 [CoverUrls.BANGUMI_ASPECT_RATIO]。
     *
     * 同时走全应用统一的 [CoverUrls.cover]：剥掉已有 `@` 后缀再套新尺寸，
     * 避免拼出 `xxx.jpg@320w_480h.jpg@320w_480h` 这种双后缀非法地址（实测 HTTP 400）。
     */
    fun coverUrl(width: Int = 320): String =
        CoverUrls.cover(cover, width, CoverUrls.BANGUMI_ASPECT_RATIO)
}

/** 番剧详情。 */
data class BangumiDetail(
    val seasonId: Long,
    val title: String,
    val cover: String,
    val evaluate: String,
    val score: Double,
    val totalEpisodes: Int,
    val episodes: List<BangumiEpisode>,
    /** 是否已追番（`user_status.follow`）。未登录时为 false。 */
    val isFollowed: Boolean = false,
    /** 追番人数（`stat.follow`）。详情接口有，索引接口没有。 */
    val followCount: Int = 0,
)

/** 单集。 */
data class BangumiEpisode(
    val epId: Long,
    val aid: Long,
    val bvid: String,
    val title: String,
    val cover: String,
    val durationSeconds: Int,
    /** 集数标签，如「第 1 话」。 */
    val index: String,
)
