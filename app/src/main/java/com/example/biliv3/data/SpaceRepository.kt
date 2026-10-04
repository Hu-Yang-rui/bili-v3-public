package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.api.Endpoints
import com.example.biliv3.data.model.VideoItem
import org.json.JSONObject

/**
 * 用户主页（UP 主空间）。
 *
 * ## 为什么这个仓库存在
 *
 * 首版**没有 `space/{mid}` 路由** —— 视频详情页的 UP 头像、
 * 评论区头像、搜索结果里的 UP 条目、私信会话头像**全部不可点**。
 * 这是「用户主页」被判为完全缺失的直接原因。
 *
 * ## 接口选型（踩过的坑都在这里）
 *
 * | 用途 | 路径 | 实测 |
 * |---|---|---|
 * | 资料 | `x/web-interface/card?mid=` | ✅ 可用，未登录也可读 |
 * | 资料（备选） | `x/space/wbi/acc/info` | ❌ `-352` 风控 |
 * | 投稿 | `x/space/wbi/arc/search` | ⚠️ 需签名，风控敏感 → 失败静默 |
 * | 关注态 | `x/relation?fid=` | 需登录 |
 * | 关注/取关 | `x/relation/modify` | 需 csrf |
 *
 * 「最标准」的 `space/acc/info` 反而不可用，这是本仓库最值得记的一条。
 */
class SpaceRepository(
    private val api: BiliApi,
    private val store: com.example.biliv3.data.auth.AuthStore,
) {

    val isLoggedIn: Boolean get() = store.isLoggedIn

    /** 当前登录用户 mid（判断"这是我自己的主页"）。 */
    val currentMid: Long get() = store.mid

    /**
     * 用户资料。
     *
     * 用 `x/web-interface/card`：实测未登录可用，且返回字段足够
     * （`card.name` / `card.face` / `card.fans` / `card.friend` / `card.sign`）。
     */
    suspend fun profile(mid: Long): SpaceProfile? {
        if (mid <= 0) return null

        val json = try {
            api.getRaw(
                path = Endpoints.USER_CARD,
                query = mapOf(
                    "mid" to mid.toString(),
                    // 带上头像与粉丝数（这两个字段默认可能不返回）
                    "photo" to "true",
                ),
                signed = false,
            )
        } catch (e: Exception) {
            // ⚠️ 失败抛异常（v1.2.5）：返回 null 会让"请求失败"
            // 与"该用户不存在"在 UI 上一样（§7.8-44 同类错误）。
            // 调用方 `SpaceViewModel` 已用 runCatching 接住。
            throw e
        }
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(code, json.optString("message", "用户资料加载失败"))
        }

        val card = json.optJSONObject("data")?.optJSONObject("card")
            ?: throw BiliException(-1, "用户资料响应缺少 card")

        return SpaceProfile(
            mid = card.optLong("mid", mid),
            name = card.optString("name"),
            face = card.optString("face"),
            sign = card.optString("sign"),
            // 粉丝数在 card.fans（实测），不在 relation/stat 里重复请求
            fans = optIntLoose(card, "fans"),
            following = optIntLoose(card, "friend"),
            level = card.optJSONObject("level_info")?.let { optIntLoose(it, "current_level") } ?: 0,
        )
    }

    /**
     * UP 主的投稿列表。
     *
     * ## 失败静默
     *
     * `space/wbi/arc/search` 需要 WBI 签名且**风控敏感**
     * （本项目在其它 space 接口上实测到过 `-352`）。
     * 投稿列表是用户主页的**增强模块** —— 拿不到就整块不渲染，
     * 不影响资料卡与关注按钮（那才是主页的核心）。
     */
    suspend fun videos(mid: Long, page: Int = 1, pageSize: Int = 20): List<VideoItem> {
        if (mid <= 0) return emptyList()

        val json = try {
            api.getRaw(
                path = Endpoints.SPACE_ARC_SEARCH,
                query = mapOf(
                    "mid" to mid.toString(),
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                    // 按最新投稿排序
                    "order" to "pubdate",
                ),
                signed = true,
            )
        } catch (_: Exception) {
            return emptyList()
        }
        if (json.optInt("code", -1) != 0) return emptyList()

        val list = json.optJSONObject("data")
            ?.optJSONObject("list")
            ?.optJSONArray("vlist")
            ?: return emptyList()

        val out = ArrayList<VideoItem>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val bvid = o.optString("bvid")
            if (bvid.isEmpty()) continue
            out.add(
                VideoItem(
                    bvid = bvid,
                    title = o.optString("title"),
                    cover = o.optString("pic"),
                    authorName = o.optString("author"),
                    // 投稿列表接口**不返回头像** —— 由调用方用资料卡的头像补
                    authorFace = "",
                    playCount = optIntLoose(o, "play"),
                    danmakuCount = optIntLoose(o, "video_review"),
                    // 这里的 duration 是 `"12:34"` 字符串，不是秒
                    durationSeconds = parseDurationLabel(o.optString("length")),
                    publishedAt = optLongLoose(o, "created").takeIf { it > 0 },
                ),
            )
        }
        return out
    }

    /**
     * 是否已关注该用户。
     *
     * `x/relation?fid={mid}` 返回 `data.attribute`：
     * `0` = 未关注，`2`/`6` = 已关注（`6` 是互关）。
     * 未登录返回 `-101` → 返回 false（UI 显示"关注"，点击时引导登录）。
     */
    suspend fun isFollowing(mid: Long): Boolean {
        if (!isLoggedIn || mid <= 0) return false

        return runCatching {
            val json = api.getRaw(
                path = Endpoints.RELATION_QUERY,
                query = mapOf("fid" to mid.toString()),
                signed = false,
            )
            if (json.optInt("code", -1) != 0) return@runCatching false
            val attr = json.optJSONObject("data")?.optInt("attribute", 0) ?: 0
            // 2 = 已关注，6 = 互相关注
            attr == 2 || attr == 6
        }.getOrDefault(false)
    }

    /**
     * 关注 / 取关。
     *
     * @param follow true 关注，false 取关
     */
    suspend fun setFollow(mid: Long, follow: Boolean): Result<Unit> {
        if (!isLoggedIn) return Result.failure(NotLoggedInException())
        val csrf = store.biliJct
        if (csrf.isEmpty()) return Result.failure(NotLoggedInException())
        if (mid <= 0) return Result.failure(IllegalArgumentException("用户不存在"))

        return runCatching {
            val json = api.postForm(
                path = Endpoints.RELATION_MODIFY,
                form = mapOf(
                    "fid" to mid.toString(),
                    // 1 = 关注，2 = 取关
                    "act" to if (follow) "1" else "2",
                    // 私密关注（1）会让对方看不到；默认公开关注
                    "re_src" to "11",
                    "csrf" to csrf,
                ),
            )
            val code = json.optInt("code", -1)
            if (code != 0) {
                val msg = json.optString("message")
                throw IllegalStateException(
                    when (code) {
                        // 这两个码都表示"状态已经是目标值了"，不是真失败
                        22001 -> "已经关注了"
                        22002 -> "还没有关注"
                        else -> msg.ifEmpty { "操作失败（$code）" }
                    },
                )
            }
        }
    }

    // ---------------- 容错取值 ----------------

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

    /** 把 `"12:34"` / `"1:02:03"` 解析成秒数。 */
    private fun parseDurationLabel(s: String): Int {
        if (s.isEmpty()) return 0
        val parts = s.split(':')
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 +
                (parts[1].toIntOrNull() ?: 0) * 60 +
                (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    }
}

/**
 * 用户主页资料。
 *
 * 字段来自 `x/web-interface/card` 的 `data.card`。
 */
data class SpaceProfile(
    val mid: Long,
    val name: String,
    val face: String,
    /** 个性签名。可能为空。 */
    val sign: String,
    /** 粉丝数。 */
    val fans: Int,
    /** 关注数。 */
    val following: Int,
    /** 等级 0–6。 */
    val level: Int,
) {
    /** 头像地址（走统一构造，避免缓存分裂）。 */
    fun faceUrl(size: Int = 96): String =
        com.example.biliv3.data.model.CoverUrls.avatar(face, size)
}
