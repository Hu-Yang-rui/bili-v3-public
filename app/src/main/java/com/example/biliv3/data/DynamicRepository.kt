package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.api.Endpoints
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动态流仓库。
 *
 * ## 为什么首版没有
 *
 * 底部导航第 2 个 Tab 一直是 `PlaceholderScreen`（"动态页依赖登录态，
 * 将在后续阶段接入"）。而动态是 B 站的核心 Tab 之一 ——
 * 一个底部 Tab 点进去是占位页，是很显眼的缺口。
 *
 * ## 接口
 *
 * `x/polymer/web-dynamic/v1/feed/all`（主站 Web 接口）。
 *
 * ## ⚠️ 必须登录
 *
 * 动态流的内容来自**关注列表**，未登录返回 `-101`。
 * 这不是接口不可用，而是语义上就要求登录 ——
 * UI 必须显示登录引导，而不是"加载失败"。
 *
 * ## 动态类型的处理范围
 *
 * 动态接口的 `type` 有 8+ 种（视频投稿 / 图文 / 转发 / 直播开播 /
 * 专栏 / 音频 / 番剧更新 / 合集更新…）。本项目**只渲染两类**：
 *
 * | type | 形态 | 处理 |
 * |---|---|---|
 * | `DYNAMIC_TYPE_AV` | 视频投稿 | ✅ 卡片 + 跳视频详情 |
 * | `DYNAMIC_TYPE_DRAW` / `WORD` | 图文 / 纯文本 | ✅ 卡片 + 展开正文 |
 * | 其它 | 转发 / 直播 / 专栏 … | 显示"暂不支持的类型"，不假装能点 |
 *
 * 不做假数据、也不留死入口：不支持的卡片明确标注，点击给出提示。
 */
class DynamicRepository(
    private val api: BiliApi,
    private val store: com.example.biliv3.data.auth.AuthStore,
) {

    val isLoggedIn: Boolean get() = store.isLoggedIn

    /**
     * 关注动态流（"全部"）。
     *
     * @param offset 游标。接口返回的 `offset` 原样回传即可翻页。
     */
    suspend fun feed(offset: String = ""): DynamicPage {
        if (!isLoggedIn) return DynamicPage(emptyList(), "", false)

        val query = mutableMapOf(
            "type" to "all",
            "page" to "1",
            "features" to "itemOpusStyle",
        )
        if (offset.isNotEmpty()) query["offset"] = offset

        val json = try {
            api.getRaw(
                path = Endpoints.DYNAMIC_FEED,
                query = query,
                signed = false,
            )
        } catch (e: Exception) {
            // ⚠️ 失败抛异常（v1.2.5）：返回空页会让"未登录/请求失败"
            // 与"没有关注任何人"在 UI 上一样（§7.8-44 同类错误）。
            // 实测未登录返回 `code=-101`。
            // 调用方 `DynamicViewModel` 已用 runCatching 接住。
            throw e
        }
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(code, json.optString("message", "动态加载失败"))
        }

        return parsePage(json.optJSONObject("data"))
    }

    /**
     * 某个用户的动态（用户主页的「动态」Tab）。
     *
     * ⚠️ 这里**保持"失败返回空页"**，与 [feed] 的处理**故意不同**。
     *
     * 理由：`space` 接口对空间动态的可见性有额外限制 ——
     * 未登录、或对方设置了隐私时，**返回空是正常结果**而不是故障。
     * 若也抛异常，用户主页的「动态」Tab 会对"这个人没发动态"
     * 显示成错误态，那是**反向的谎报**。
     *
     * 判据：**"空"是接口的正常语义**（隐私/无内容）时返回空；
     * "空"只可能由失败造成时才抛。
     */
    suspend fun spaceFeed(mid: Long, offset: String = ""): DynamicPage {
        if (mid <= 0) return DynamicPage(emptyList(), "", false)

        val query = mutableMapOf(
            "host_mid" to mid.toString(),
            "timezone_offset" to "-480",
            "features" to "itemOpusStyle",
        )
        if (offset.isNotEmpty()) query["offset"] = offset

        val json = try {
            api.getRaw(
                path = Endpoints.SPACE_DYNAMIC,
                query = query,
                signed = false,
            )
        } catch (_: Exception) {
            return DynamicPage(emptyList(), "", false)
        }
        if (json.optInt("code", -1) != 0) return DynamicPage(emptyList(), "", false)

        return parsePage(json.optJSONObject("data"))
    }

    // ---------------- 解析 ----------------

    private fun parsePage(data: JSONObject?): DynamicPage {
        if (data == null) return DynamicPage(emptyList(), "", false)

        val arr = data.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<DynamicItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseItem(o)?.let { out.add(it) }
        }

        val offset = data.optString("offset")
        val hasMore = data.optBoolean("has_more", false)

        return DynamicPage(out, offset, hasMore)
    }

    /**
     * 解析一条动态。
     *
     * ## 结构说明（实测）
     *
     * ```json
     * {
     *   "id_str": "123...",
     *   "type": "DYNAMIC_TYPE_AV",
     *   "modules": {
     *     "module_author": { "mid": 1, "name": "...", "face": "...", "pub_time": "..." },
     *     "module_dynamic": {
     *       "desc":  { "text": "动态正文" },
     *       "major": { "archive": { "bvid": "...", "title": "...", "cover": "..." } }
     *     },
     *     "module_stat": { "comment": {"count":1}, "like": {"count":2} }
     *   }
     * }
     * ```
     *
     * ⚠️ 视频信息在 `major.archive`（**不是** `major.draw`）——
     * 一开始按 `draw.items` 找封面会永远拿不到视频卡片的图。
     */
    private fun parseItem(o: JSONObject): DynamicItem? {
        val id = o.optString("id_str").ifEmpty { o.optLong("id").toString() }
        if (id.isEmpty()) return null

        val type = o.optString("type")
        val modules = o.optJSONObject("modules") ?: return null

        val author = modules.optJSONObject("module_author")
        val dynamic = modules.optJSONObject("module_dynamic")
        val stat = modules.optJSONObject("module_stat")

        val authorMid = author?.optLong("mid", 0L) ?: 0L
        val authorName = author?.optString("name").orEmpty()
        val authorFace = author?.optString("face").orEmpty()

        // 正文：`module_dynamic.desc.text`；部分类型在 `major.opus.summary.text`
        val text = dynamic?.optJSONObject("desc")?.optString("text").orEmpty()
            .ifEmpty {
                dynamic?.optJSONObject("major")
                    ?.optJSONObject("opus")
                    ?.optJSONObject("summary")
                    ?.optString("text").orEmpty()
            }

        // 视频投稿
        val archive = dynamic?.optJSONObject("major")?.optJSONObject("archive")
        val bvid = archive?.optString("bvid").orEmpty()
        val videoTitle = archive?.optString("title").orEmpty()
        val videoCover = archive?.optString("cover").orEmpty()
        val durationLabel = archive?.optString("duration_text").orEmpty()

        // 图文：取第一张图
        val drawItems = dynamic?.optJSONObject("major")?.optJSONObject("draw")
            ?.optJSONArray("items")
        val firstImage = drawItems?.optJSONObject(0)?.optString("src").orEmpty()

        val likeCount = stat?.optJSONObject("like")?.optInt("count", 0) ?: 0
        val commentCount = stat?.optJSONObject("comment")?.optInt("count", 0) ?: 0
        val forwardCount = stat?.optJSONObject("forward")?.optInt("count", 0) ?: 0

        // 发布时间：`module_author.pub_ts` 是 Unix 秒（pub_time 是展示串）
        val pubTs = author?.optLong("pub_ts", 0L) ?: 0L

        return DynamicItem(
            id = id,
            type = type,
            authorMid = authorMid,
            authorName = authorName,
            authorFace = authorFace,
            text = text,
            bvid = bvid,
            videoTitle = videoTitle,
            videoCover = videoCover,
            durationLabel = durationLabel,
            imageUrl = firstImage,
            likeCount = likeCount,
            commentCount = commentCount,
            forwardCount = forwardCount,
            publishedAt = pubTs,
        )
    }
}

/**
 * 一条动态。
 *
 * ## 为什么用一个"宽"数据类而不是密封类层次
 *
 * 动态的 8 种类型字段差异大，但**卡片布局只有三种形态**
 * （视频卡 / 图文卡 / 不支持提示）。用密封类要写 8 个分支再映射回 3 种，
 * 多一层没有收益的抽象；这里用可空字段 + [kind] 派生属性，
 * UI 只按 [kind] 分支。
 */
data class DynamicItem(
    val id: String,
    /** 原始 `type`，如 `DYNAMIC_TYPE_AV`。 */
    val type: String,
    val authorMid: Long,
    val authorName: String,
    val authorFace: String,
    /** 动态正文（可能为空）。 */
    val text: String,
    /** 视频投稿的 bvid（非视频动态为空）。 */
    val bvid: String,
    val videoTitle: String,
    val videoCover: String,
    /** 视频时长标签，如 `12:34`。 */
    val durationLabel: String,
    /** 图文动态的第一张图。 */
    val imageUrl: String,
    val likeCount: Int,
    val commentCount: Int,
    val forwardCount: Int,
    /** 发布时间（Unix 秒）。 */
    val publishedAt: Long,
) {
    /** 卡片形态。UI 只按这个分支渲染。 */
    val kind: Kind
        get() = when {
            bvid.isNotEmpty() -> Kind.Video
            imageUrl.isNotEmpty() -> Kind.Image
            text.isNotEmpty() -> Kind.Text
            else -> Kind.Unsupported
        }

    /** 头像地址（统一构造）。 */
    fun faceUrl(size: Int = 96): String =
        com.example.biliv3.data.model.CoverUrls.avatar(authorFace, size)

    /** 动态封面（统一 https 升级）。 */
    fun coverUrl(width: Int = 480): String =
        com.example.biliv3.data.model.CoverUrls.cover(videoCover.ifEmpty { imageUrl }, width)

    enum class Kind {
        /** 视频投稿：可跳详情页。 */
        Video,

        /** 图文动态：有图可看。 */
        Image,

        /** 纯文本动态。 */
        Text,

        /**
         * 暂不支持的类型（转发 / 直播开播 / 专栏 / 音频…）。
         *
         * ⚠️ **明确标注而不是隐藏** —— 隐藏会让用户以为"这个人没发过这条"，
         * 标注出来至少信息是完整的。
         */
        Unsupported,
    }
}

/** 动态分页。 */
data class DynamicPage(
    val items: List<DynamicItem>,
    /** 下一页游标。空串表示没有更多。 */
    val offset: String,
    val hasMore: Boolean,
)
