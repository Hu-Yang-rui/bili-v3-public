package com.example.biliv3.data

import com.example.biliv3.data.model.VideoItem
import org.json.JSONObject

/**
 * 历史记录解析（纯 Kotlin，可单测）。
 *
 * ## 🔴 为什么单独成文件（v1.5.1）
 *
 * 「历史记录里的视频点开播不了」的根因就是**这里读错了字段层级**。
 * 解析逻辑原本写在 `LibraryRepository` 里，而那个类构造需要
 * `BiliApi` + `AuthStore`（后者要 `Context`）—— 本地 JVM 单测**拿不到**，
 * 所以这条 bug 一直没有测试能挡住。
 *
 * 抽出来之后：`HistoryParseTest` 有 15 个用例直接跑，零 Android 依赖。
 *
 * ## 🔴 字段层级（这是 bug 的核心，改之前先读完）
 *
 * `x/web-interface/history/cursor` 把**视频标识全部嵌在 `history` 子对象里**，
 * 顶层**没有** `bvid` / `cid` / `page`：
 *
 * ```json
 * {
 *   "title": "…", "cover": "…", "author_name": "…",
 *   "duration": 75,        // 时长只在这里
 *   "progress": 12000,     // 毫秒；-1 = 已看完
 *   "view_at": 1730000000,
 *   "videos": 1,
 *   "history": {
 *     "oid": 116838629378379,
 *     "bvid": "BV134TF6gEnG",   // ← bvid 在这里
 *     "cid": 39534134840,        // ← cid 也在这里
 *     "page": 1,
 *     "part": "P1",
 *     "business": "archive",
 *     "epid": 0,
 *     "dt": 3
 *   }
 * }
 * ```
 *
 * **实测确认**（真实账号，脚本直连 `api.bilibili.com`）：
 * - 顶层有 `bvid` 吗 → **False**；`history` 里有 `bvid` 吗 → **True**
 * - `history` 子对象的**完整**字段集恰好是这 8 个：
 *   `business` / `bvid` / `cid` / `dt` / `epid` / `oid` / `page` / `part`
 *   —— **没有 `duration`**，时长只在顶层
 *
 * 原实现写 `o.optString("bvid")`（顶层）→ 恒为空串 → 列表里每条 bvid 都是空
 * → 点进详情页用空 bvid 请求 → **视频加载失败**。
 *
 * > ⚠️ 我第一版 KDoc 曾写"`history.duration` 是当前 P 的时长"——
 * > **那是猜的，实测推翻**：`history` 里根本没有 `duration`。
 * > 教训：字段层级这种事**必须脚本直连确认**，不能凭"看起来应该有"下笔。
 */
object HistoryParser {

    /** 解析一条历史记录。结构不符时返回 null（跳过该条，不塞空条目进列表）。 */
    fun parse(o: JSONObject): HistoryEntry? {
        // ⚠️ bvid / cid 都在 history 子对象里，**不在顶层**
        val history = o.optJSONObject("history") ?: return null
        val bvid = history.optString("bvid")
        val cid = history.optLong("cid", 0L)

        // bvid 为空说明这条不是普通稿件（直播 / 番剧 / 专栏）。
        // 本项目只支持 archive —— 跳过，而不是塞一条空 bvid 进列表
        // （塞进去的后果就是"点开播不了"，正是这条 bug 的表象）。
        if (bvid.isEmpty() || cid <= 0L) return null

        // progress 在**顶层**（毫秒）；-1 = 已看完
        val progressMs = o.optLong("progress", 0L)

        // 时长只在**顶层**（实测：history 子对象里没有 duration）。
        // 先试 history 内是**防御性**写法 —— 万一 B 站以后加进来，
        // 那时它才是"当前 P 的时长"，比顶层总时长更准。
        val durationSeconds = history.optLong("duration", 0L)
            .takeIf { it > 0L }
            ?: o.optLong("duration", 0L)

        val video = VideoItem(
            bvid = bvid,
            title = o.optString("title"),
            cover = o.optString("cover"),
            authorName = o.optString("author_name"),
            authorFace = o.optString("author_face"),
            // 历史接口**不返回**播放量/弹幕数 —— 一律 0，不拿别的字段冒充
            playCount = 0,
            danmakuCount = 0,
            durationSeconds = durationSeconds.toInt(),
            publishedAt = o.optLong("pubdate", 0L).takeIf { it > 0 },
        )

        return HistoryEntry(
            video = video,
            cid = cid,
            progressSeconds = if (progressMs > 0L) (progressMs / 1000).toInt() else 0,
            viewAt = o.optLong("view_at", 0L),
            isFinished = o.optInt("progress", -1) == -1,
        )
    }
}
