package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.model.CategoryEntry
import com.example.biliv3.data.model.HomeData
import com.example.biliv3.data.model.LiveItem
import com.example.biliv3.data.model.NoticeItem
import com.example.biliv3.data.model.RankItem
import com.example.biliv3.data.model.TopicItem
import com.example.biliv3.data.model.VideoItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 主页数据仓库。
 *
 * ## 设计要点
 *
 * 1. **推荐流是主链路，其余是增强**。推荐流失败 → 整页错误态；
 *    榜单/直播/话题/公告失败 → 该模块整块不渲染，不影响主链路。
 * 2. **不做假数据占位**。拿不到就不渲染，让「失败」和「真的没有」可区分。
 * 3. **并行拉取**，但推荐流单独走（它有 ≥1s 节流，不能被其他请求拖慢）。
 */
class HomeRepository(
    private val api: BiliApi,
    /** 直播仓库。传 null 时「正在直播」模块不渲染（预览环境）。 */
    private val liveRepo: com.example.biliv3.data.LiveRepository? = null,
) {

    /** 推荐流。失败抛异常（主链路）。 */
    suspend fun feed(freshIdx: Int): List<VideoItem> = api.feed(freshIdx)

    /**
     * 右侧栏内容。
     *
     * 任一模块失败都只返回空列表，不抛异常 —— 右侧栏是增强信息，
     * 不该因为它拿不到就让整个主页报错。
     */
    suspend fun sidePanels(): SidePanels = coroutineScope {
        val ranksDeferred = async { runCatching { loadRanks() }.getOrDefault(emptyList()) }
        // ⚠️ 「正在直播」此前**恒为空** —— `lives = emptyList()` 是硬编码的，
        // 于是 `SidePanel` 里那一整块永不渲染。现在接上真实数据源
        // （`api.live.bilibili.com`，与主 API 不同域名）。
        val livesDeferred = async {
            runCatching { liveRepo?.list(pageSize = 6)?.rooms ?: emptyList() }
                .getOrDefault(emptyList())
        }

        SidePanels(
            ranks = ranksDeferred.await(),
            lives = livesDeferred.await().map { room ->
                LiveItem(
                    roomId = room.roomId,
                    title = room.title,
                    cover = room.cover,
                    // ⚠️ 字段名是 `anchorName`（HomeModels 里的定义），
                    // 不是 `uname` —— 写错会编译不过，这里记一下避免下次又踩。
                    anchorName = room.uname,
                    // 接口返回的是 `"1.2万"` 这类展示串，已在仓库里解析成整数
                    online = room.online,
                )
            },
            // 话题 / 公告 / Banner 暂无稳定公开接口：
            // 按"不做假数据"原则返回空，UI 侧整块不渲染。
            topics = emptyList(),
            notices = emptyList(),
        )
    }

    /**
     * 热门榜，带降级。
     *
     * ## 为什么要有降级
     *
     * `ranking/v2` 实测本身正常（连续 6 次 `code=0`，返回 100 条），
     * 但**会间歇性触发 `-352` 风控**。榜单是右侧栏的增强模块，
     * 触发风控就整块消失的体验很差。
     *
     * 降级顺序：
     * 1. `ranking/v2`（全站榜，100 条）
     * 2. `ranking/region`（分区榜，风控更宽松）
     *
     * 两级都失败才返回空。
     */
    private suspend fun loadRanks(): List<RankItem> {
        val videos = runCatching { api.ranking(pageSize = 10) }
            .getOrDefault(emptyList())
            .ifEmpty {
                // 全站榜失败/为空 → 退到动画分区榜
                runCatching { api.regionRanking(rid = 1, pageSize = 10) }
                    .getOrDefault(emptyList())
            }

        return videos.mapIndexed { i, v ->
            RankItem(
                rank = i + 1,
                bvid = v.bvid,
                title = v.title,
                cover = v.cover,
                // 热度值用播放量近似（B 站榜单未返回独立热度分）
                hotScore = v.playCount.toLong(),
            )
        }
    }

    /**
     * 首屏聚合。
     *
     * 推荐流失败会抛出 → ViewModel 显示整页错误态；
     * 右侧栏失败静默降级 → 对应模块不渲染。
     */
    suspend fun loadHome(freshIdx: Int): HomeData = coroutineScope {
        val sideDeferred = async { sidePanels() }
        val feedDeferred = async { feed(freshIdx) }

        val videos = feedDeferred.await()
        val side = sideDeferred.await()

        HomeData(
            // Banner 恒为空 —— 见 sidePanels() 的说明，无可用公开接口。
            // UI 侧对空列表的处理是"整块不渲染"，所以这里不需要特判。
            banners = emptyList(),
            categories = CategoryEntry.defaults(),
            videos = videos,
            ranks = side.ranks,
            lives = side.lives,
            topics = side.topics,
            notices = side.notices,
        )
    }

    /** 搜索联想（转发，便于 UI 只依赖仓库层）。 */
    suspend fun suggest(keyword: String): List<String> = api.suggest(keyword)

    /** 热搜（搜索页用，主页暂不用）。 */
    suspend fun hotSearch(): List<String> = api.hotSearch()

    data class SidePanels(
        val ranks: List<RankItem> = emptyList(),
        val lives: List<LiveItem> = emptyList(),
        val topics: List<TopicItem> = emptyList(),
        val notices: List<NoticeItem> = emptyList(),
    )
}
