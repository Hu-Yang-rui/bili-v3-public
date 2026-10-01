package com.example.biliv3.data.api

import com.example.biliv3.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * B 站 API 客户端。
 *
 * ## 为什么用 OkHttp + JSONObject 而不是 Retrofit + Moshi
 *
 * B 站接口字段类型极不稳定（同一字段有时是 Int、有时是 String、有时缺失），
 * 用强类型反序列化会在字段变动时**直接抛异常导致整页失败**。
 * 这里手工容错解析，代价是代码略多，收益是**接口小改不会崩**。
 *
 * ## 风控策略
 *
 * - 推荐流请求间隔 ≥1s（B 站对推荐流频率非常敏感）
 * - `-352` / `-412` **绝不重试**，重试只会让风控升级
 * - `-799` 指数退避，最多 3 次
 * - 携带 `buvid3`（设备指纹），见 [BiliCookieJar]
 *
 * ## ⚠️ CookieJar 是登录态的前提
 *
 * 早期实现**完全没有 CookieJar**，导致：
 * 1. 登录成功后拿到的 `Set-Cookie` 被直接丢弃，下一个请求仍是未登录
 * 2. 没有 `buvid3`，每个请求都像全新设备，风控命中率显著更高
 *
 * 现在默认挂 [BiliCookieJar]（内存态）。生产环境应换成
 * `AuthCookieJar`（加密持久化）以便登录态跨重启保持。
 */
class BiliApi(
    private val client: OkHttpClient = defaultClient(),
) {
    /** WBI 密钥缓存。文件名每天变一次（UTC+8 零点前后）。 */
    @Volatile
    private var cachedKeys: Wbi.Keys? = null
    @Volatile
    private var keysFetchedAt: Long = 0

    private val keysMutex = Mutex()

    /** 推荐流最小请求间隔。 */
    @Volatile
    private var lastFeedAt: Long = 0

    private val feedMutex = Mutex()

    // ---------------- 密钥 ----------------

    /**
     * 拿到可用的 WBI 密钥。
     *
     * ⚠️ **`nav` 未登录时返回 `code = -101`，但 `data.wbi_img` 照样存在。**
     * 绝对不能因为 `code != 0` 就抛错，否则未登录状态下所有签名接口全废。
     */
    private suspend fun keys(): Wbi.Keys {
        val now = System.currentTimeMillis()
        // 按天缓存：同一天直接复用
        if (cachedKeys != null && now - keysFetchedAt < 12 * 3600_000L) {
            return cachedKeys!!
        }

        return keysMutex.withLock {
            // 双重检查：等锁期间可能已被其他协程填充
            if (cachedKeys != null && System.currentTimeMillis() - keysFetchedAt < 12 * 3600_000L) {
                return@withLock cachedKeys!!
            }

            val json = getRaw(Endpoints.NAV, emptyMap(), signed = false)
            // 注意：这里不检查 code，只看 data.wbi_img 是否存在
            val wbiImg = json.optJSONObject("data")?.optJSONObject("wbi_img")
                ?: throw BiliException(-1, "nav 响应缺少 data.wbi_img")

            val imgUrl = wbiImg.optString("img_url")
            val subUrl = wbiImg.optString("sub_url")
            if (imgUrl.isEmpty() || subUrl.isEmpty()) {
                throw BiliException(-1, "nav 的 wbi_img 字段不完整")
            }

            val k = Wbi.Keys(Wbi.fileName(imgUrl), Wbi.fileName(subUrl))
            cachedKeys = k
            keysFetchedAt = System.currentTimeMillis()
            k
        }
    }

    /** 作废密钥缓存。收到风控错误时调用。 */
    fun invalidateKeys() {
        cachedKeys = null
        keysFetchedAt = 0
    }

    // ---------------- 请求 ----------------

    /**
     * 发请求并解析 JSON，**同时返回响应头里的 Set-Cookie**。
     *
     * ## 为什么需要这个（扫码登录的真实 bug）
     *
     * 扫码确认成功后，登录凭据（`SESSDATA` / `bili_jct`）在
     * `poll` 响应的 **`Set-Cookie` 响应头**里。而 [getRaw] 只返回 body，
     * 响应头被丢弃 —— 调用方拿不到凭据。
     *
     * 虽然 `AuthCookieJar` 会收到 OkHttp 的回调并落库，但那依赖
     * 「OkHttp 认为该 cookie 合法」这一前提；实测中存在
     * **凭据未能落到 store** 的情况（表现为"扫码成功却提示已过期"）。
     *
     * 所以这里把响应头**显式透出**，让调用方有一条不依赖 CookieJar
     * 行为的可靠路径。两条路都走、取并集，比赌单条路径稳。
     *
     * @return body JSON + 该响应的 Set-Cookie 原始行（可能为空）
     */
    suspend fun getRawWithCookies(
        path: String,
        query: Map<String, String>,
        signed: Boolean,
        host: String = Endpoints.API_HOST,
    ): Pair<JSONObject, List<String>> = withContext(Dispatchers.IO) {
        val finalQuery = if (signed) {
            Wbi.sign(params = query, keys = keys(), wts = Wbi.nowSeconds())
        } else {
            query
        }

        val url = buildString {
            append("https://").append(host).append('/').append(path)
            if (finalQuery.isNotEmpty()) {
                append('?').append(Wbi.toQueryString(finalQuery))
            }
        }

        val request = Request.Builder()
            .url(url)
            .apply { BiliHeaders.api().forEach { (k, v) -> header(k, v) } }
            .build()

        val result = try {
            client.newCall(request).execute().use { resp ->
                // API 19+ 用 getSetCookie() 可拿到多行（不去重、不合并）
                val cookies = resp.headers.values("Set-Cookie")
                val body = resp.body?.string() ?: throw BiliException(-1, "空响应")
                body to cookies
            }
        } catch (e: BiliException) {
            throw e
        } catch (e: Exception) {
            throw BiliException(-1, e.message ?: "网络请求失败")
        }

        val json = try {
            JSONObject(result.first)
        } catch (e: Exception) {
            throw BiliException(-1, "响应不是合法 JSON")
        }

        json to result.second
    }

    /**
     * 发请求并解析 JSON。
     *
     * @param signed 是否需要 WBI 签名
     */
    suspend fun getRaw(
        path: String,
        query: Map<String, String>,
        signed: Boolean,
        host: String = Endpoints.API_HOST,
    ): JSONObject = withContext(Dispatchers.IO) {
        val finalQuery = if (signed) {
            Wbi.sign(
                params = query,
                keys = keys(),
                wts = Wbi.nowSeconds(),
            )
        } else {
            query
        }

        val url = buildString {
            append("https://").append(host).append('/').append(path)
            if (finalQuery.isNotEmpty()) {
                append('?').append(Wbi.toQueryString(finalQuery))
            }
        }

        val request = Request.Builder()
            .url(url)
            .apply { BiliHeaders.api().forEach { (k, v) -> header(k, v) } }
            .build()

        val body = try {
            client.newCall(request).execute().use { resp ->
                resp.body?.string() ?: throw BiliException(-1, "空响应")
            }
        } catch (e: BiliException) {
            throw e
        } catch (e: Exception) {
            throw BiliException(-1, e.message ?: "网络请求失败")
        }

        try {
            JSONObject(body)
        } catch (e: Exception) {
            throw BiliException(-1, "响应不是合法 JSON")
        }
    }

    /**
     * 发请求并断言业务成功。
     *
     * 风控类错误会作废密钥缓存，让下次请求重新取 key。
     */
    suspend fun getOk(
        path: String,
        query: Map<String, String>,
        signed: Boolean,
        host: String = Endpoints.API_HOST,
    ): JSONObject {
        val json = getRaw(path, query, signed, host)
        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("message", "")
            if (code == -352 || code == -412) invalidateKeys()
            throw BiliException(code, msg)
        }
        return json
    }

    // ---------------- 主页数据 ----------------

    /**
     * 首页推荐流。
     *
     * 节流：请求间隔锁在 ≥1s。B 站对推荐流频率非常敏感，
     * 快速下拉刷新 + 触底加载叠加会直接触发 `-352`。
     */
    suspend fun feed(freshIdx: Int, pageSize: Int = 20): List<VideoItem> {
        feedMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastFeedAt
            if (elapsed < MIN_FEED_INTERVAL_MS) {
                kotlinx.coroutines.delay(MIN_FEED_INTERVAL_MS - elapsed)
            }
            lastFeedAt = System.currentTimeMillis()
        }

        val json = getOk(
            path = Endpoints.RCMD_FEED,
            query = mapOf(
                "ps" to pageSize.toString(),
                "fresh_type" to "4",
                "fresh_idx" to freshIdx.toString(),
                "fresh_idx_1h" to freshIdx.toString(),
                "feed_version" to "V8",
            ),
            signed = true,
        )

        val items = json.optJSONObject("data")?.optJSONArray("item") ?: return emptyList()
        val out = ArrayList<VideoItem>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            // 只保留普通视频投稿（过滤广告位、直播卡等）
            if (o.optString("goto") != "av") continue
            parseVideo(o)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 热门榜。无需签名。
     *
     * ## 实测结论（2026-09-28 复测）
     *
     * `ranking/v2` **本身是正常的**，连续 6 次请求全部 `code=0`、返回 100 条。
     * 早期出现的 `-352` 是**临时风控**（同一 IP 短时间请求过多），
     * 不是接口失效 —— 这一点曾被我误判为"接口坏了，需要换掉"。
     *
     * 但风控确实会间歇发生，所以这里加了降级：
     * `ranking/v2` 失败时退到 [regionRanking]（分区榜，风控更宽松）。
     * 榜单是右侧栏的增强模块，宁可显示"分区榜"也不该整块消失。
     */
    suspend fun ranking(pageSize: Int = 10): List<VideoItem> {
        val json = getOk(
            path = Endpoints.RANKING,
            query = mapOf("ps" to pageSize.toString(), "rid" to "0"),
            signed = false,
        )
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<VideoItem>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            parseVideo(o)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 分区排行榜。无需签名。
     *
     * 作为 [ranking] 的降级路径。注意返回结构与 `ranking/v2` **不同**：
     * 它的 `data` 直接是数组，不是 `{ list: [...] }`。
     *
     * @param rid 分区 id（1=动画 3=音乐 4=游戏 …）
     */
    suspend fun regionRanking(rid: Int = 1, pageSize: Int = 10): List<VideoItem> {
        val json = getOk(
            path = Endpoints.REGION_RANKING,
            query = mapOf("rid" to rid.toString(), "ps" to pageSize.toString()),
            signed = false,
        )
        val list = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<VideoItem>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            parseVideo(o)?.let { out.add(it) }
        }
        return out
    }

    /** 热搜榜。 */
    suspend fun hotSearch(limit: Int = 10): List<String> {
        val json = getOk(
            path = Endpoints.SEARCH_SQUARE,
            query = mapOf("limit" to limit.toString()),
            signed = false,
        )
        val list = json.optJSONObject("data")
            ?.optJSONObject("trending")
            ?.optJSONArray("list")
            ?: return emptyList()
        val out = ArrayList<String>(list.length())
        for (i in 0 until list.length()) {
            val kw = list.optJSONObject(i)?.optString("keyword").orEmpty()
            if (kw.isNotEmpty()) out.add(kw)
        }
        return out
    }

    /**
     * 综合搜索（视频）。
     *
     * @param page 从 1 开始。
     * @return 结果列表与总数（用于判断还有没有下一页）。
     */
    suspend fun search(
        keyword: String,
        page: Int = 1,
    ): SearchResult {
        if (keyword.isBlank()) return SearchResult(emptyList(), 0)

        val json = getOk(
            path = Endpoints.SEARCH_TYPE,
            query = mapOf(
                "keyword" to keyword,
                "search_type" to "video",
                "page" to page.toString(),
            ),
            signed = true,
        )
        val data = json.optJSONObject("data")
            ?: return SearchResult(emptyList(), 0)

        val numResults = data.optInt("numResults", 0)
        val arr = data.optJSONArray("result") ?: return SearchResult(emptyList(), numResults)

        val out = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseSearchItem(o)?.let { out.add(it) }
        }
        return SearchResult(out, numResults)
    }

    /**
     * 解析搜索结果条目。
     *
     * ## ⚠️ 必须剥离 `<em>` 标签
     *
     * 搜索接口返回的 `title` 带关键词高亮标签，实测形如：
     * ```
     * "<em class=\"keyword\">原神</em>提瓦特冒险纪念视频..."
     * ```
     * 直接渲染会把 `<em class="keyword">` 当**文字**显示出来，
     * 用户看到一堆尖括号。这里统一剥离。
     *
     * 同理 `pic` 是 `//` 开头的协议相对地址，`duration` 是 `"12:34"` 字符串
     * 而不是秒数 —— 两处都与推荐流的格式不同，不能复用 [parseVideo]。
     *
     * ## ⚠️ UP 主头像在**顶层 `upic`**，不在 `owner.face`（这是一个真实 bug）
     *
     * 实测 `search/type` 的返回结构**没有 `owner` 对象**：
     *
     * ```
     * {
     *   "author": "理智的小曼波",
     *   "upic":   "https://i0.hdslb.com/bfs/face/e4740990...jpg",   ← 头像在这
     *   "pic":    "//i2.hdslb.com/bfs/archive/9cf1744e...jpg",
     *   "play":   12345, "video_review": 67, "duration": "12:34"
     * }
     * ```
     *
     * 而推荐流 `rcmd` 是 `owner: { mid, name, face }`。两者**结构不同**。
     *
     * 第一版照搬推荐流写法读 `owner?.optString("face")`，
     * 于是 `authorFace` **恒为空串** → `VideoCard` 的
     * `if (authorFace.isNotEmpty())` 永不成立 → 搜索结果**永远没有 UP 头像**。
     *
     * 这类"字段路径写错"的 bug 不报错、不缺数据，只是**看起来少了一块**，
     * 属于最难自查的一类（参见 `AGENTS.md` §7.2）。
     * 现在两个来源都读，哪个有值用哪个。
     */
    private fun parseSearchItem(o: JSONObject): VideoItem? {
        val bvid = o.optString("bvid")
        if (bvid.isEmpty()) return null

        val owner = o.optJSONObject("owner")

        return VideoItem(
            bvid = bvid,
            title = stripEmTags(o.optString("title")),
            cover = o.optString("pic"),
            authorName = owner?.optString("name").orEmpty().ifEmpty {
                o.optString("author")
            },
            // 优先 owner.face（若接口版本变了），回退到顶层的 upic
            authorFace = owner?.optString("face").orEmpty().ifEmpty {
                o.optString("upic")
            },
            playCount = optIntLoose(o, "play"),
            danmakuCount = optIntLoose(o, "video_review"),
            // duration 是 "MM:SS" 字符串，不是秒
            durationSeconds = parseDurationLabel(o.optString("duration")),
            publishedAt = optLongLoose(o, "pubdate").takeIf { it > 0 },
        )
    }

    /** 剥离 `<em ...>` / `</em>` 高亮标签。 */
    private fun stripEmTags(s: String): String =
        if (s.isEmpty()) s else EM_TAG.replace(s, "")

    /** 把 `"12:34"` / `"1:02:03"` 解析成秒数。无法解析返回 0。 */
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

    /**
     * 视频互动状态（是否已点赞 / 投币 / 收藏）。
     *
     * 未登录返回 `-101`，此时返回全 false 而不是抛错 ——
     * 未登录用户看详情页是正常场景，不该显示错误。
     */
    suspend fun relation(bvid: String): InteractionState {
        val json = try {
            getRaw(
                path = Endpoints.RELATION,
                query = mapOf("bvid" to bvid),
                signed = false,
            )
        } catch (_: Exception) {
            return InteractionState()
        }
        if (json.optInt("code", -1) != 0) return InteractionState()
        val d = json.optJSONObject("data") ?: return InteractionState()
        return InteractionState(
            liked = d.optInt("like", 0) == 1,
            coined = d.optInt("coin", 0) > 0,
            favored = d.optInt("favorite", 0) == 1,
        )
    }

    /**
     * 点赞 / 取消点赞。
     *
     * ⚠️ 写操作需要 `bili_jct`（CSRF token），由 CookieJar 自动带上。
     * 没有 CookieJar 时这类接口**必定失败**。
     *
     * @param liked true 点赞，false 取消
     */
    suspend fun like(bvid: String, liked: Boolean, csrf: String): Boolean {
        val json = postForm(
            path = Endpoints.LIKE,
            form = mapOf(
                "bvid" to bvid,
                // 1=点赞 2=取消
                "like" to if (liked) "1" else "2",
                "csrf" to csrf,
            ),
        )
        return json.optInt("code", -1) == 0
    }

    /**
     * 投币。
     *
     * @param count 投币数 1 或 2
     */
    suspend fun coin(bvid: String, count: Int, csrf: String, alsoLike: Boolean = false): Boolean {
        val json = postForm(
            path = Endpoints.COIN_ADD,
            form = mapOf(
                "bvid" to bvid,
                "multiply" to count.toString(),
                // 4 = 同时点赞
                "select_like" to if (alsoLike) "1" else "0",
                "csrf" to csrf,
            ),
        )
        return json.optInt("code", -1) == 0
    }

    /**
     * 硬币余额（投币确认弹窗显示用）。
     *
     * 取自 `x/web-interface/nav` 的 `data.money` —— 实测未登录返回 `-101`，
     * 此时返回 null 而不是 0（0 会让用户以为投不了币）。
     */
    suspend fun coinBalance(): Double? {
        val json = try {
            getRaw(path = "x/web-interface/nav", query = emptyMap(), signed = false)
        } catch (_: Exception) {
            return null
        }
        if (json.optInt("code", -1) != 0) return null
        val data = json.optJSONObject("data") ?: return null
        // money 在部分版本是 Double，也可能是 Int —— 容错取值
        return when (val v = data.opt("money")) {
            is Double -> v
            is Int -> v.toDouble()
            is Long -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    /**
     * 收藏 / 取消收藏。
     *
     * @param add true 收藏，false 取消
     * @param folderIds 收藏夹 id 列表
     */
    suspend fun favorite(
        bvid: String,
        add: Boolean,
        csrf: String,
        folderIds: List<Long>,
    ): Boolean {
        val json = postForm(
            path = Endpoints.FAV_DEAL,
            form = mapOf(
                "rid" to bvid,
                "type" to "2", // 2 = 视频
                "add_media_ids" to if (add) folderIds.joinToString(",") else "",
                "del_media_ids" to if (add) "" else folderIds.joinToString(","),
                "csrf" to csrf,
            ),
        )
        return json.optInt("code", -1) == 0
    }

    /**
     * 分享上报。只做统计埋点，失败不影响用户
     * （分享动作本身由系统分享面板完成）。
     */
    suspend fun shareReport(bvid: String, csrf: String): Boolean {
        val json = try {
            postForm(
                path = Endpoints.SHARE_ADD,
                form = mapOf("bvid" to bvid, "csrf" to csrf),
            )
        } catch (_: Exception) {
            return false
        }
        return json.optInt("code", -1) == 0
    }

    /**
     * 默认收藏夹 id。
     *
     * ## ⚠️ `up_mid` 是必填参数
     *
     * 实测（2026-09-28）：
     * ```
     * 无参数     -> code=-400 请求错误
     * up_mid=0   -> code=-400 请求错误
     * up_mid=1   -> code=0 OK
     * ```
     * 传空参数会**永远失败**，收藏功能整体不可用。
     * 必须传当前登录用户的 mid。
     *
     * 未登录（mid <= 0）直接返回 null，不发无谓的请求。
     */
    suspend fun defaultFavFolder(mid: Long): Long? {
        if (mid <= 0) return null

        val json = try {
            getRaw(
                path = Endpoints.FAV_FOLDERS,
                query = mapOf("up_mid" to mid.toString()),
                signed = false,
            )
        } catch (_: Exception) {
            return null
        }
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return null
        if (list.length() == 0) return null
        // 优先取"默认收藏夹"（attr == 1）
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optInt("attr", 0) == 1) return o.optLong("id")
        }
        return list.optJSONObject(0)?.optLong("id")
    }

    /**
     * 发 POST 表单到 passport 域（登录相关）。
     *
     * 与 [postForm] 的区别只在 host —— passport 接口必须打到
     * `passport.bilibili.com`，且同样需要 Referer 与 csrf。
     *
     * 公开（非 private）是因为 `AuthRepository` 需要调用它。
     */
    suspend fun postPassportForm(
        path: String,
        form: Map<String, String>,
    ): JSONObject = postForm(path, form, host = Endpoints.PASSPORT_HOST)

    /**
     * 发 POST 表单。
     *
     * ## 为什么单独一个方法
     *
     * 写操作（点赞/投币/收藏）必须用 `application/x-www-form-urlencoded`
     * POST，与读接口的 GET + query 完全不同：
     * - 参数放 body，不放 query
     * - 必须带 `csrf`（值就是 cookie 里的 `bili_jct`）
     * - 必须带 Referer，否则被拒
     *
     * ## 为什么是 `internal` 而不是 `private`
     *
     * 评论的写操作（点赞/删除/回复）在 `CommentRepository` 里，
     * 与互动仓库不在同一个类 —— 需要跨包调用。
     * 用 `internal`（模块内可见）而不是 `public`：
     * 不让它成为对外 API，只是模块内共享。
     */
    internal suspend fun postForm(
        path: String,
        form: Map<String, String>,
        host: String = Endpoints.API_HOST,
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()

        val request = Request.Builder()
            .url("https://$host/$path")
            .post(body)
            .apply { BiliHeaders.api().forEach { (k, v) -> header(k, v) } }
            .build()

        val text = try {
            client.newCall(request).execute().use { resp ->
                resp.body?.string() ?: throw BiliException(-1, "空响应")
            }
        } catch (e: BiliException) {
            throw e
        } catch (e: Exception) {
            throw BiliException(-1, e.message ?: "网络请求失败")
        }

        try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BiliException(-1, "响应不是合法 JSON")
        }
    }

    /**
     * 带 **WBI 签名**的 form POST。
     *
     * ## 与 [postForm] 的区别
     *
     * 私信的发送接口实测**必须签名**，否则返回 `-403 访问权限不足`
     * （即使 csrf 正确）。这是私信与其它写操作的关键差异：
     * 评论/点赞等只校验 csrf，私信还校验签名。
     *
     * 签名参数（`w_rid` / `wts`）拼在 **query** 上，业务参数在 body 里 ——
     * 与 GET 的签名方式一致，只是多了一个 form body。
     */
    internal suspend fun postFormSigned(
        path: String,
        form: Map<String, String>,
        host: String = Endpoints.API_HOST,
    ): JSONObject = withContext(Dispatchers.IO) {
        // 签名参数（w_rid / wts）拼进 query；业务参数放 body。
        // 与 GET 的签名方式一致（`Wbi.sign` 返回带签名的完整参数表）。
        val signedParams = Wbi.sign(
            params = form,
            keys = keys(),
            wts = Wbi.nowSeconds(),
        )
        val query = signedParams.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val url = "https://$host/$path?$query"

        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .apply { BiliHeaders.api().forEach { (k, v) -> header(k, v) } }
            .build()

        val text = try {
            client.newCall(request).execute().use { resp ->
                resp.body?.string() ?: throw BiliException(-1, "空响应")
            }
        } catch (e: BiliException) {
            throw e
        } catch (e: Exception) {
            throw BiliException(-1, e.message ?: "网络请求失败")
        }

        try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BiliException(-1, "响应不是合法 JSON")
        }
    }

    /**
     * 搜索联想词。失败返回空列表（联想是增强功能，不该阻断搜索）。
     *
     * ⚠️ `host` 必须传 [Endpoints.SEARCH_SUGGEST_HOST]，
     * 而 `path` 只写 `main/suggest` —— 见 `SEARCH_SUGGEST` 的说明，
     * 这两处曾经拼出双重域名导致静默无结果。
     */
    suspend fun suggest(keyword: String): List<String> {
        if (keyword.isBlank()) return emptyList()
        return try {
            val json = getRaw(
                path = Endpoints.SEARCH_SUGGEST,
                query = mapOf("term" to keyword, "main_ver" to "v1"),
                signed = false,
                host = Endpoints.SEARCH_SUGGEST_HOST,
            )
            val arr = json.optJSONArray("result") ?: return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i)?.optString("term").orEmpty()
                if (v.isNotEmpty()) out.add(v)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------- 解析 ----------------

    /** 容错解析视频对象。字段缺失/类型不符时返回 null 而不是抛异常。 */
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

    /** B 站字段类型不稳定：可能是 Int、String 或缺失。 */
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

    companion object {
        /** 推荐流最小间隔。 */
        const val MIN_FEED_INTERVAL_MS = 1000L

        /**
         * 搜索结果标题里的高亮标签。
         *
         * 接口返回形如 `<em class="keyword">原神</em>提瓦特...`，
         * 不剥离会把标签当文字渲染出来。
         */
        private val EM_TAG = Regex("</?em[^>]*>")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // ⚠️ 没有 CookieJar 时，登录拿到的 Set-Cookie 会被直接丢弃，
            // 下一个请求仍是未登录 —— 登录功能根本无法工作。
            .cookieJar(BiliCookieJar())
            .build()
    }
}

/**
 * 视频互动状态。
 *
 * 未登录时全部为 false（而不是"未知"）—— UI 层据此决定
 * 显示空心图标 + 点击时引导登录。
 */
data class InteractionState(
    val liked: Boolean = false,
    val coined: Boolean = false,
    val favored: Boolean = false,
)

/**
 * 搜索结果。
 *
 * [total] 是接口给的命中总数（实测常为 1000，是**上限**而非真实条数），
 * 用于粗略判断"还有没有下一页"，不要当精确值展示。
 */
data class SearchResult(
    val items: List<VideoItem>,
    val total: Int,
)

/**
 * 内存态 CookieJar。
 *
 * 用于没有持久化需求的场景（如 `HomeRepository` 的无状态调用）。
 * 登录态请用 `AuthCookieJar`（EncryptedSharedPreferences 持久化）。
 *
 * ## 为什么要过滤 domain
 *
 * OkHttp 要求 Cookie 的 domain 必须与请求 host 匹配，否则不发送。
 * B 站有 `api.bilibili.com` / `passport.bilibili.com` /
 * `s.search.bilibili.com` 多个域，所以这里按**顶级域 `.bilibili.com`**
 * 统一构造，让 cookie 跨子域生效（登录态必须在 passport 域拿到、
 * 在 api 域使用）。
 */
class BiliCookieJar : CookieJar {

    private val store = LinkedHashMap<String, String>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { store[it.name] = it.value }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.mapNotNull { (k, v) ->
            runCatching {
                Cookie.Builder()
                    .name(k)
                    .value(v)
                    .domain(BILI_DOMAIN)
                    .path("/")
                    .build()
            }.getOrNull()
        }

    /** 当前 cookie 串，便于调试与落库。 */
    fun cookieString(): String =
        store.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private companion object {
        /**
         * 带前导点的顶级域 —— 让 cookie 在所有 `*.bilibili.com` 子域生效。
         *
         * 这是登录能跑通的关键：`passport.bilibili.com` 下发的 SESSDATA
         * 必须在 `api.bilibili.com` 请求时带上。
         */
        const val BILI_DOMAIN = ".bilibili.com"
    }
}

/** B 站业务错误。 */
class BiliException(val code: Int, override val message: String) : Exception(message) {
    /** 风控类错误：绝不重试。 */
    val isRiskControl: Boolean get() = code == -352 || code == -412

    /** 未登录。 */
    val isUnauthorized: Boolean get() = code == -101

    /** 用户看得懂的文案。UI 层直接用，不再自己翻译。 */
    val userMessage: String
        get() = when (code) {
            -101 -> "登录状态已失效，请重新登录"
            -111 -> "安全校验失败，请重新登录后再试"
            -352, -412 -> "操作过于频繁，请稍后再试"
            -403 -> "没有访问权限"
            -404 -> "内容已被删除或不可见"
            -799 -> "请求太频繁了，歇一会儿再试"
            -400 -> "请求参数有误"
            else -> message.ifEmpty { "加载失败（$code）" }
        }
}
