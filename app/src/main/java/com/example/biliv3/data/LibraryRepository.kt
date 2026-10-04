package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.api.BiliException
import com.example.biliv3.data.model.VideoItem
import org.json.JSONObject

/**
 * 「我的」相关数据：历史记录 / 稍后再看 / 收藏夹。
 *
 * ## 全部需要登录
 *
 * 实测未登录一律返回 `-101 账号未登录`：
 * ```
 * history/cursor          -> -101
 * x/v2/history/toview     -> -101
 * ```
 * 所以这些方法在未登录时**直接返回空**，不发无谓请求，
 * 由 UI 显示"登录后可用"。
 */
class LibraryRepository(
    private val api: BiliApi,
    private val authStore: com.example.biliv3.data.auth.AuthStore,
) {

    val isLoggedIn: Boolean get() = authStore.isLoggedIn

    // ---------------- 历史记录 ----------------

    /**
     * 观看历史。
     *
     * ## 为什么用 `history/cursor` 而不是 `history/search`
     *
     * `cursor` 接口返回**游标**（`max`/`view_at`），适合无限滚动；
     * `search` 是给历史页搜索用的。
     *
     * @param max 游标（上页最后一条的 aid）
     * @param business 空 = 全部；`archive` = 仅视频
     */
    suspend fun history(
        max: Long = 0L,
        viewAt: Long = 0L,
        pageSize: Int = 20,
    ): HistoryPage {
        if (!isLoggedIn) return HistoryPage(emptyList(), 0, 0, true)

        val json = api.getOk(
            path = "x/web-interface/history/cursor",
            query = buildMap {
                put("ps", pageSize.toString())
                put("business", "archive")
                if (max > 0) put("max", max.toString())
                if (viewAt > 0) put("view_at", viewAt.toString())
            },
            signed = false,
        )

        val data = json.optJSONObject("data")
            ?: return HistoryPage(emptyList(), 0, 0, true)

        val cursor = data.optJSONObject("cursor")
        val nextMax = cursor?.optLong("max", 0L) ?: 0L
        val nextViewAt = cursor?.optLong("view_at", 0L) ?: 0L

        val arr = data.optJSONArray("list") ?: return HistoryPage(emptyList(), 0, 0, true)
        val out = ArrayList<HistoryEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseHistory(o)?.let { out.add(it) }
        }

        return HistoryPage(
            items = out,
            nextMax = nextMax,
            nextViewAt = nextViewAt,
            // 返回空说明到底了
            isEnd = out.isEmpty(),
        )
    }

    private fun parseHistory(o: JSONObject): HistoryEntry? {
        val bvid = o.optString("bvid")
        val history = o.optJSONObject("history") ?: return null
        val cid = history.optLong("cid", 0L)
        if (cid <= 0L) return null

        // 历史记录里有 progress（上次看到哪），这是"继续播放"的关键
        val progress = history.optLong("progress", 0L)
        val duration = history.optLong("duration", 0L)

        val video = VideoItem(
            bvid = bvid,
            title = o.optString("title"),
            cover = o.optString("cover"),
            authorName = o.optString("author_name"),
            authorFace = o.optString("author_face"),
            playCount = 0,
            danmakuCount = 0,
            durationSeconds = duration.toInt(),
            publishedAt = o.optLong("pubdate", 0L).takeIf { it > 0 },
        )

        return HistoryEntry(
            video = video,
            cid = cid,
            /** 上次播放位置（秒）。0 表示没看过。 */
            progressSeconds = (progress / 1000).toInt(),
            /** 观看时刻（Unix 秒）。 */
            viewAt = o.optLong("view_at", 0L),
            /** 是否看完。 */
            isFinished = o.optInt("progress", -1) == -1,
        )
    }

    // ---------------- 稍后再看 ----------------

    /** 稍后再看列表。 */
    suspend fun toView(pageSize: Int = 20): List<VideoItem> {
        if (!isLoggedIn) return emptyList()

        val json = try {
            api.getRaw(
                path = "x/v2/history/toview",
                query = mapOf("ps" to pageSize.toString()),
                signed = false,
            )
        } catch (_: Exception) {
            return emptyList()
        }
        if (json.optInt("code", -1) != 0) return emptyList()

        val arr = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                VideoItem(
                    bvid = o.optString("bvid"),
                    title = o.optString("title"),
                    cover = o.optString("pic"),
                    authorName = o.optJSONObject("owner")?.optString("name").orEmpty(),
                    authorFace = o.optJSONObject("owner")?.optString("face").orEmpty(),
                    playCount = optIntLoose(o.optJSONObject("stat"), "view"),
                    danmakuCount = optIntLoose(o.optJSONObject("stat"), "danmaku"),
                    durationSeconds = optIntLoose(o, "duration"),
                    publishedAt = optLongLoose(o, "pubdate").takeIf { it > 0 },
                ),
            )
        }
        return out
    }

    /** 加入稍后再看。需要 csrf。 */
    suspend fun addToView(aid: Long): Boolean {
        if (!isLoggedIn) return false
        val csrf = authStore.biliJct
        if (csrf.isEmpty()) return false
        return runCatching {
            val json = api.postPassportForm(
                path = "x/v2/history/toview/add",
                form = mapOf("aid" to aid.toString(), "csrf" to csrf),
            )
            json.optInt("code", -1) == 0
        }.getOrDefault(false)
    }

    /**
     * 从稍后再看移除。
     *
     * ## ⚠️ 传 `aid` 而不是 `bvid`
     *
     * `x/v2/history/toview/del` 的 `aid` 是必填且**只认 aid**；
     * 传 bvid 会返回 `code=0` 但什么都没删掉（静默无效）——
     * 与收藏删除的 `del_media_ids` 坑是同一类。
     */
    suspend fun removeFromToView(aid: Long): Boolean {
        if (!isLoggedIn || aid <= 0) return false
        val csrf = authStore.biliJct
        if (csrf.isEmpty()) return false
        return runCatching {
            val json = api.postPassportForm(
                path = "x/v2/history/toview/del",
                form = mapOf("aid" to aid.toString(), "csrf" to csrf),
            )
            json.optInt("code", -1) == 0
        }.getOrDefault(false)
    }

    /**
     * 该视频是否已在稍后再看列表里。
     *
     * 用列表接口判断（`x/v2/history/toview` 一次返回全部，通常几十条）。
     * 没有单独的"查询单条"接口 —— 官方 App 也是本地比对。
     */
    suspend fun isInToViewByBvid(bvid: String): Boolean {
        if (!isLoggedIn || bvid.isEmpty()) return false
        return toView().any { it.bvid == bvid }
    }

    /**
     * 上报播放进度（写入服务端历史，实现多端续播）。
     *
     * ## 为什么要单独一个方法
     *
     * `x/v2/history/report` 与 `history/cursor` 是一对：
     * 一个写、一个读。首版只做了读（历史列表），**没有写** ——
     * 所以本项目播放的视频不会出现在用户的历史里，
     * 换设备也续不上进度。
     *
     * @param aid 视频 aid
     * @param cid 分P cid
     * @param progressSeconds 已看到第几秒
     */
    suspend fun reportProgress(
        aid: Long,
        cid: Long,
        progressSeconds: Int,
    ): Boolean {
        if (!isLoggedIn || aid <= 0 || cid <= 0) return false
        val csrf = authStore.biliJct
        if (csrf.isEmpty()) return false

        return runCatching {
            val json = api.postPassportForm(
                path = "x/v2/history/report",
                form = mapOf(
                    "aid" to aid.toString(),
                    "cid" to cid.toString(),
                    // 接口要的是**秒**
                    "progress" to progressSeconds.toString(),
                    "csrf" to csrf,
                ),
            )
            json.optInt("code", -1) == 0
        }.getOrDefault(false)
    }

    // ---------------- 收藏夹 ----------------

    /** 收藏夹列表（含封面与数量）。 */
    suspend fun favoriteFolders(mid: Long): List<FavFolder> {
        if (!isLoggedIn || mid <= 0) return emptyList()

        val json = try {
            api.getRaw(
                path = "x/v3/fav/folder/created/list-all",
                query = mapOf("up_mid" to mid.toString()),
                signed = false,
            )
        } catch (e: Exception) {
            // ⚠️ 失败抛异常（v1.2.5）：返回空列表会让"请求失败"
            // 与"还没有收藏夹"在 UI 上一样（§7.8-44 同类错误）。
            // 调用方 `LibraryViewModels` 已用 runCatching 接住。
            throw e
        }
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(code, json.optString("message", "收藏夹加载失败"))
        }

        val arr = json.optJSONObject("data")?.optJSONArray("list")
            ?: throw BiliException(-1, "收藏夹响应缺少 list")
        val out = ArrayList<FavFolder>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // ⚠️ `attr` 是**位标志**，不是枚举值。
            // 原实现写 `attr == 1` 只在"恰好只有默认位"时成立；
            // 一旦同时带私密位（attr = 3），默认夹会被判成非默认。
            val attr = o.optInt("attr", 0)
            out.add(
                FavFolder(
                    id = o.optLong("id"),
                    title = o.optString("title"),
                    mediaCount = o.optInt("media_count", 0),
                    cover = o.optString("cover"),
                    isDefault = (attr and ATTR_DEFAULT) != 0,
                    isPrivate = (attr and ATTR_PRIVATE) != 0,
                ),
            )
        }
        return out
    }

    /**
     * 当前用户的 mid。未登录返回 0。
     *
     * 「我的收藏」需要它来拉收藏夹列表（`up_mid` 是必填参数）。
     */
    val currentMid: Long get() = authStore.mid

    /** 某个收藏夹里的视频。 */
    suspend fun favoriteItems(
        mediaId: Long,
        page: Int = 1,
        pageSize: Int = 20,
    ): FavPage {
        if (!isLoggedIn || mediaId <= 0) return FavPage(emptyList(), true)

        val json = try {
            api.getRaw(
                path = "x/v3/fav/resource/list",
                query = mapOf(
                    "media_id" to mediaId.toString(),
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                    // 只取视频分区
                    "type" to "0",
                    // ⚠️ 必须带平台标识：不带时该接口对**稍后再看/默认收藏夹**
                    // 可能返回精简结构（缺 upper / cnt_info），导致卡片没有 UP 头像
                    "platform" to "web",
                ),
                signed = false,
            )
        } catch (e: Exception) {
            // ⚠️ 失败抛异常（v1.2.5）：返回空页会让"请求失败"
            // 与"这个收藏夹是空的"在 UI 上一样（§7.8-44 同类错误）。
            // 调用方 `LibraryViewModels` 已用 runCatching 接住。
            throw e
        }
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(code, json.optString("message", "收藏内容加载失败"))
        }

        val data = json.optJSONObject("data")
            ?: throw BiliException(-1, "收藏内容响应缺少 data")
        val hasMore = data.optBoolean("has_more", false)
        val arr = data.optJSONArray("medias")
            ?: throw BiliException(-1, "收藏内容响应缺少 medias")

        val out = ArrayList<FavoriteEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue

            val upper = o.optJSONObject("upper")
            val cnt = o.optJSONObject("cnt_info")

            val video = VideoItem(
                bvid = o.optString("bvid"),
                title = o.optString("title"),
                cover = o.optString("cover"),
                authorName = upper?.optString("name").orEmpty(),
                authorFace = upper?.optString("face").orEmpty(),
                playCount = optIntLoose(cnt, "play"),
                danmakuCount = optIntLoose(cnt, "danmaku"),
                durationSeconds = optIntLoose(o, "duration"),
                publishedAt = optLongLoose(o, "pubtime").takeIf { it > 0 },
            )

            // 失效视频（已删除/私有）attr != 0。
            // ⚠️ **不丢弃** —— 用户能看到"这个视频失效了"并主动清理，
            // 直接隐藏会让收藏数量对不上，用户以为丢数据。
            out.add(
                FavoriteEntry(
                    video = video,
                    // 收藏条目 id：取消收藏时用作 del_media_ids。
                    // 用条目 id 而不是收藏夹 id —— 同一视频可能在多个收藏夹里。
                    favItemId = o.optLong("id"),
                    aid = optLongLoose(o, "aid"),
                    isInvalid = o.optInt("attr", 0) != 0,
                    favoritedAt = optLongLoose(o, "fav_time").takeIf { it > 0 },
                ),
            )
        }
        return FavPage(out, hasMore)
    }

    /**
     * 取消收藏单条。
     *
     * ## 为什么用 `favItemId` 而不是 `bvid`
     *
     * `x/v3/fav/resource/deal` 的 `del_media_ids` 要的是**收藏条目 id**
     * （`resource/list` 返回的 `media.id`），不是 bvid、也不是收藏夹 id。
     *
     * 传错的表现是：接口返回 `code=0` **但什么都没删掉**（静默无效），
     * 列表刷新后视频还在 —— 属于最难查的一类。
     *
     * @param favItemId `resource/list` 里该条的 `id`
     * @param aid 该条视频的 aid（`rid` 参数）
     */
    suspend fun removeFavorite(favItemId: Long, aid: Long): Result<Unit> {
        if (!isLoggedIn) return Result.failure(NotLoggedInException())
        val csrf = authStore.biliJct
        if (csrf.isEmpty()) return Result.failure(NotLoggedInException())
        if (favItemId <= 0) {
            return Result.failure(IllegalStateException("收藏条目信息不完整，请下拉刷新"))
        }

        return runCatching {
            val json = api.postPassportForm(
                path = "x/v3/fav/resource/deal",
                form = mapOf(
                    "rid" to aid.toString(),
                    "type" to "2",
                    "add_media_ids" to "",
                    "del_media_ids" to favItemId.toString(),
                    "csrf" to csrf,
                ),
            )
            if (json.optInt("code", -1) != 0) {
                throw IllegalStateException(
                    json.optString("message").ifEmpty { "取消收藏失败" },
                )
            }
        }
    }

    // ---------------- 批量整理（v1.3.0） ----------------

    /**
     * 一条收藏记录的**操作标识**。
     *
     * ## ⚠️ 为什么批量操作需要三个 id 而不是一个 bvid
     *
     * B 站收藏接口（`x/v3/fav/resource/deal`）需要的是：
     *
     * | 参数 | 含义 | 从哪来 |
     * |---|---|---|
     * | `rid` | **视频 aid**（不是 bvid！） | `resource/list` 的 `id`/`aid` |
     * | `add_media_ids` / `del_media_ids` | **收藏夹 id** | `FavFolder.id` |
     *
     * 而 `removeFavorite` 还需要该条记录的 **favItemId**（`media.id`）——
     * 它**不等于 aid**。传错的表现是 `code=0` 但什么都没发生。
     *
     * 所以批量操作必须携带完整的三个标识，不能只传 bvid。
     */
    data class FavRef(
        val bvid: String,
        val aid: Long,
        /** `resource/list` 里该条的 `id`（取消收藏用）。 */
        val favItemId: Long,
        /** 当前所在收藏夹 id。 */
        val folderId: Long,
    )

    /**
     * 批量移动到指定收藏夹（**B 站服务器操作**）。
     *
     * ## 实现方式：一次请求处理一条
     *
     * `resource/deal` 支持 `add_media_ids` + `del_media_ids` 同时传 ——
     * 所以"移动"是**一个请求**完成的（先加新夹、再从旧夹删），
     * 不是"先删后加"两次（那样中途失败会丢收藏）。
     *
     * ## 为什么不用 `resources/deal`（复数）批量接口
     *
     * 那个接口一次要传多个 `rid`，但**所有 rid 只能进同一个目标夹**，
     * 且返回结构更复杂。逐条调用更容易做**部分失败**的精确报告：
     * 用户可以知道"20 个里成功了 18 个，哪 2 个失败了"。
     *
     * @param onProgress 每完成一条回调（done, total），用于进度显示
     * @return 每条的结果
     */
    suspend fun batchMove(
        refs: List<FavRef>,
        targetFolderId: Long,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<BatchResult> {
        if (refs.isEmpty()) return emptyList()
        if (!isLoggedIn) return refs.map { BatchResult(it.bvid, false, "未登录") }
        val csrf = authStore.biliJct
        if (csrf.isEmpty()) return refs.map { BatchResult(it.bvid, false, "未登录") }
        if (targetFolderId <= 0) return refs.map { BatchResult(it.bvid, false, "目标收藏夹无效") }

        val out = ArrayList<BatchResult>(refs.size)
        refs.forEachIndexed { i, ref ->
            // 已在目标夹里 → 跳过（不算失败，也不发请求）
            if (ref.folderId == targetFolderId) {
                out.add(BatchResult(ref.bvid, true, "已在目标收藏夹"))
            } else {
                val r = runCatching {
                    val json = api.postPassportForm(
                        path = "x/v3/fav/resource/deal",
                        form = mapOf(
                            "rid" to ref.aid.toString(),
                            "type" to "2",
                            "add_media_ids" to targetFolderId.toString(),
                            // 从原夹移除 —— 与添加在同一个请求里完成
                            "del_media_ids" to ref.folderId.toString(),
                            "csrf" to csrf,
                        ),
                    )
                    val code = json.optInt("code", -1)
                    if (code != 0) {
                        throw IllegalStateException(
                            json.optString("message").ifEmpty { "code=$code" },
                        )
                    }
                }
                out.add(
                    r.fold(
                        onSuccess = { BatchResult(ref.bvid, true, null) },
                        onFailure = { BatchResult(ref.bvid, false, it.message ?: "失败") },
                    ),
                )
            }
            onProgress?.invoke(i + 1, refs.size)
        }
        return out
    }

    /**
     * 批量取消收藏（**B 站服务器操作**，破坏性）。
     *
     * ⚠️ UI 必须二次确认（任务书 §4.4）。
     */
    suspend fun batchRemoveFavorite(
        refs: List<FavRef>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<BatchResult> {
        if (refs.isEmpty()) return emptyList()
        if (!isLoggedIn) return refs.map { BatchResult(it.bvid, false, "未登录") }
        val csrf = authStore.biliJct
        if (csrf.isEmpty()) return refs.map { BatchResult(it.bvid, false, "未登录") }

        val out = ArrayList<BatchResult>(refs.size)
        refs.forEachIndexed { i, ref ->
            val r = runCatching {
                if (ref.favItemId <= 0) {
                    throw IllegalStateException("条目信息不完整，请下拉刷新")
                }
                val json = api.postPassportForm(
                    path = "x/v3/fav/resource/deal",
                    form = mapOf(
                        "rid" to ref.aid.toString(),
                        "type" to "2",
                        "add_media_ids" to "",
                        "del_media_ids" to ref.favItemId.toString(),
                        "csrf" to csrf,
                    ),
                )
                val code = json.optInt("code", -1)
                if (code != 0) {
                    throw IllegalStateException(
                        json.optString("message").ifEmpty { "code=$code" },
                    )
                }
            }
            out.add(
                r.fold(
                    onSuccess = { BatchResult(ref.bvid, true, null) },
                    onFailure = { BatchResult(ref.bvid, false, it.message ?: "失败") },
                ),
            )
            onProgress?.invoke(i + 1, refs.size)
        }
        return out
    }

    /**
     * 批量加入稍后再看（**B 站服务器操作**）。
     *
     * ⚠️ 该接口**每条一个请求**（`x/v2/history/toview/add` 只收单个 aid）。
     */
    suspend fun batchAddToView(
        aids: List<Long>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<BatchResult> {
        if (aids.isEmpty()) return emptyList()
        val out = ArrayList<BatchResult>(aids.size)
        aids.forEachIndexed { i, aid ->
            val ok = runCatching { addToView(aid) }.getOrDefault(false)
            out.add(BatchResult(aid.toString(), ok, if (ok) null else "加入失败"))
            onProgress?.invoke(i + 1, aids.size)
        }
        return out
    }

    /**
     * 单条批量操作结果。
     *
     * @param ok 是否成功
     * @param message 失败原因 / 跳过说明
     */
    data class BatchResult(
        val bvid: String,
        val ok: Boolean,
        val message: String?,
    )

    /**
     * 汇总批量结果（UI 用一句话展示）。
     *
     * 区分"成功"与"跳过" —— "已在目标夹"不是失败，
     * 若混在失败里报"2 个失败"，用户会以为出了问题。
     */
    fun summarize(results: List<BatchResult>): String {
        val ok = results.count { it.ok && it.message == null }
        val skipped = results.count { it.ok && it.message != null }
        val failed = results.count { !it.ok }
        return buildString {
            append("成功 $ok")
            if (skipped > 0) append("，跳过 $skipped")
            if (failed > 0) append("，失败 $failed")
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

    companion object {
        /**
         * `fav/folder/created/list-all` 的 `attr` **位标志**。
         *
         * 它是位组合而不是枚举 —— 一个夹可以同时是「默认」且「私密」
         * （此时 `attr = 3`）。用 `== 1` 判断默认夹会在这种组合下失效。
         */
        private const val ATTR_DEFAULT = 1

        /**
         * bit1 = 私密。
         *
         * ⚠️ 按公开资料实现，**未实测** —— 该接口只返回本人收藏夹，
         * 未登录拿到空列表，无法匿名验证。详见 `FavFolder.isPrivate`。
         */
        private const val ATTR_PRIVATE = 2
    }
}

/** 历史记录条目。 */
data class HistoryEntry(
    val video: VideoItem,
    val cid: Long,
    /** 上次播放位置（秒）。用于"继续播放"。 */
    val progressSeconds: Int,
    /** 观看时刻（Unix 秒）。 */
    val viewAt: Long,
    val isFinished: Boolean,
) {
    /** 观看进度百分比 0f~1f。 */
    val progressRatio: Float
        get() {
            val total = video.durationSeconds
            if (total <= 0) return 0f
            return (progressSeconds.toFloat() / total).coerceIn(0f, 1f)
        }
}

/** 历史分页。 */
data class HistoryPage(
    val items: List<HistoryEntry>,
    val nextMax: Long,
    val nextViewAt: Long,
    val isEnd: Boolean,
)

/** 收藏夹。 */
data class FavFolder(
    val id: Long,
    val title: String,
    val mediaCount: Int,
    val cover: String,
    val isDefault: Boolean,
    /**
     * 是否私密。
     *
     * ⚠️ **语义未实测**：接口的 `attr` 是位标志，按公开资料
     * `bit0(1)=默认夹`、`bit1(2)=私密`。
     * 但 `fav/folder/created/list-all` **只返回本人收藏夹** ——
     * 实测 `up_mid=1` / `up_mid=2` 均 `code=0` 但 `list=[]`，
     * 所以匿名无法验证这个位。
     *
     * 代码按文档语义实现，**登录后需实测确认**。
     * 若判断反了，只改 [LibraryRepository.favoriteFolders] 一处。
     */
    val isPrivate: Boolean = false,
)

/** 收藏夹内容分页。 */
data class FavPage(
    val items: List<FavoriteEntry>,
    val hasMore: Boolean,
)

/**
 * 收藏条目。
 *
 * ## 为什么不是裸的 [VideoItem]
 *
 * 取消收藏需要 `del_media_ids`，而它的值是 `resource/list` 返回的
 * **收藏条目 id**（`media.id`），不是 bvid、也不是收藏夹 id。
 * 这个 id 只在该接口里出现，`VideoItem` 装不下，所以包一层。
 *
 * @param favItemId 收藏条目 id（`media.id`）
 * @param aid 视频 aid（`rid` 参数）
 * @param isInvalid 失效视频（已删除 / 已私有）。**仍然展示**，
 *                  让用户能看见并清理，而不是以为数据丢了
 * @param favoritedAt 收藏时间（Unix 秒）
 */
data class FavoriteEntry(
    val video: VideoItem,
    val favItemId: Long,
    val aid: Long,
    val isInvalid: Boolean,
    val favoritedAt: Long?,
)
