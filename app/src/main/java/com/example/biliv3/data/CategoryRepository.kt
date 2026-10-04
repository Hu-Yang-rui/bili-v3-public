package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.api.Endpoints
import com.example.biliv3.data.model.VideoItem
import org.json.JSONObject

/**
 * 分区页仓库。
 *
 * ## 为什么首版是占位页
 *
 * `category/{rid}` 路由早就通了，但页面是 `PlaceholderScreen("rid = X，
 * 分区列表将在后续阶段接入")` —— 首页 12 个分区入口点进去全是这一张图。
 * 这是「分区页」被判为完全缺失的直接原因。
 *
 * ## 接口选型
 *
 * | 用途 | 路径 | 说明 |
 * |---|---|---|
 * | 最新 | `x/web-interface/dynamic/region` | 无需签名，风控宽松 |
 * | 热门 | `x/web-interface/ranking/region` | 分区榜，已有实现 |
 *
 * 用「最新 / 热门」两档而不是更多筛选项：
 * 官方分区页的筛选维度极多（时间/播放量/时长/子分区），
 * 但**接口层能稳定拿到的只有这两个**。与其做一堆点了没反应的下拉，
 * 不如只给两个真实可用的档位。
 */
class CategoryRepository(
    private val api: BiliApi,
) {

    /** 分区「最新」投稿。 */
    suspend fun latest(rid: Int, page: Int = 1, pageSize: Int = 20): List<VideoItem> {
        if (rid <= 0) return emptyList()

        val json = try {
            api.getRaw(
                path = Endpoints.REGION_LATEST,
                query = mapOf(
                    "rid" to rid.toString(),
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                    // ⚠️ `newlist` 需要 `type`：0 = 全部投稿类型。
                    // 见 Endpoints.REGION_LATEST 的说明（换源原因）。
                    "type" to "0",
                ),
                signed = false,
            )
        } catch (e: Exception) {
            // ⚠️ 失败必须抛（v1.2.4）：返回空列表会让"接口报错"
            // 与"这个分区没有内容"在 UI 上完全一样（§7.8-44 同类错误）。
            throw e
        }
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(code, json.optString("message", "分区内容加载失败"))
        }

        val arr = json.optJSONObject("data")?.optJSONArray("archives")
            ?: throw BiliException(-1, "分区响应缺少 archives")
        val out = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseVideo(o)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 分区「热门」。
     *
     * 复用 `ranking/region`（与首页右侧栏的降级路径同一个接口）。
     * 注意它返回的 `data` 是**数组**，不是 `{ list: [...] }`。
     */
    suspend fun hot(rid: Int, pageSize: Int = 20): List<VideoItem> {
        if (rid <= 0) return emptyList()

        val json = try {
            api.getRaw(
                path = Endpoints.REGION_HOT,
                query = mapOf("rid" to rid.toString(), "ps" to pageSize.toString()),
                signed = false,
            )
        } catch (e: Exception) {
            throw e
        }
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(code, json.optString("message", "分区热门加载失败"))
        }

        val arr = json.optJSONArray("data")
            ?: throw BiliException(-1, "分区热门响应缺少 data")
        val out = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseVideo(o)?.let { out.add(it) }
        }
        return out
    }

    private fun parseVideo(o: JSONObject): VideoItem? {
        val bvid = o.optString("bvid")
        if (bvid.isEmpty()) return null
        val owner = o.optJSONObject("owner")
        val stat = o.optJSONObject("stat")
        return VideoItem(
            bvid = bvid,
            title = o.optString("title"),
            cover = o.optString("pic"),
            authorName = owner?.optString("name").orEmpty(),
            authorFace = owner?.optString("face").orEmpty(),
            playCount = optIntLoose(stat, "view"),
            danmakuCount = optIntLoose(stat, "danmaku"),
            durationSeconds = optIntLoose(o, "duration"),
            publishedAt = optLongLoose(o, "pubdate").takeIf { it > 0 },
        )
    }

    private fun optIntLoose(o: JSONObject?, key: String): Int {
        if (o == null) return 0
        return when (val v = o.opt(key)) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun optLongLoose(o: JSONObject?, key: String): Long {
        if (o == null) return 0
        return when (val v = o.opt(key)) {
            is Long -> v
            is Int -> v.toLong()
            is Double -> v.toLong()
            is String -> v.toLongOrNull() ?: 0
            else -> 0
        }
    }
}
