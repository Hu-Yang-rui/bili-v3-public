package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.model.VideoItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 竖屏观看模式的数据源。
 *
 * ## ⚠️ 为什么必须"先探测再筛选"（这是本模块的核心约束）
 *
 * 需求要求**只推送竖屏视频，不混入横屏**。但实测（2026-10-02）：
 *
 * | 接口 | 是否返回分辨率 |
 * |---|---|
 * | `rcmd` 推荐流（wbi） | ❌ 无 `dimension` 字段 |
 * | `app.bilibili.com/x/v2/feed/index` | ❌ 无 |
 * | story / 竖屏模式变体 | ❌ 404 或同样没有 |
 * | `x/web-interface/view?bvid=` | ✅ `dimension.width/height` |
 *
 * 也就是说**没有任何接口能直接给"竖屏视频列表"**，只能在客户端
 * 逐条查详情后过滤。
 *
 * 而实测竖屏占比只有约 **20%**（40 条推荐流里 8 条竖屏）——
 * 意味着要凑够一屏 10 条，平均要探测 **50 条**候选。
 *
 * ## 因此的设计
 *
 * 1. **并发探测**：一次取 20 条候选，用 `async` 并发查（限流到
 *    [PROBE_CONCURRENCY]）—— 串行 50 条 × 300ms = 15 秒，不可接受
 * 2. **边探测边交付**：探测结果按批回调，UI 不必等全部探完
 * 3. **缓存**：同一 bvid 的探测结果缓存，翻回上一页不重复请求
 * 4. **节流**：推荐流本身有 ≥1s 间隔限制（`BiliApi.feed` 已内置），
 *    探测详情额外限流，避免触发风控
 *
 * ## 判定标准
 *
 * `height > width` 即竖屏。用**严格大于**而不是"宽高比 < 1"——
 * 正方形（实测有 `1080x1080`）不算竖屏，按横屏处理。
 */
class VerticalFeedRepository(
    private val api: BiliApi,
    private val videoRepository: VideoRepository,
) {

    /** 探测结果缓存：bvid -> 是否竖屏。避免翻页重复请求。 */
    private val verticalCache = mutableMapOf<String, Boolean>()

    /** 缓存访问锁（并发探测时会多协程写）。 */
    private val cacheLock = Mutex()

    /** 已用过的 fresh_idx，保证每次拉到的推荐流不重复。 */
    private var freshIdx = 0

    /**
     * 拉一批**竖屏**视频。
     *
     * @param minCount 期望至少返回多少条竖屏（不够会继续翻推荐流）。
     * @param onProgress 每探测出一批竖屏就回调（UI 可增量渲染，不必等全部完成）。
     * @return 竖屏视频列表（可能少于 [minCount]，说明推荐流暂时探不出更多）
     */
    suspend fun loadVertical(
        minCount: Int = PAGE_TARGET,
        onProgress: suspend (List<VideoItem>) -> Unit = {},
    ): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val seen = HashSet<String>()
        var rounds = 0

        // 最多翻 MAX_ROUNDS 轮推荐流 —— 防止"推荐流里竖屏极少"时无限拉取
        while (result.size < minCount && rounds < MAX_ROUNDS) {
            rounds++
            freshIdx++

            val candidates = runCatching { api.feed(freshIdx, PAGE_SIZE) }
                .getOrDefault(emptyList())
                .filter { it.bvid.isNotEmpty() && it.bvid !in seen }

            if (candidates.isEmpty()) break
            candidates.forEach { seen += it.bvid }

            // 并发探测本批候选，探到竖屏就立刻交付
            val batch = probeVertical(candidates)
            if (batch.isNotEmpty()) {
                result += batch
                onProgress(batch)
            }
        }

        return result
    }

    /**
     * 并发探测一批候选里哪些是竖屏。
     *
     * 用 `async` + `awaitAll`，并发度由 [PROBE_CONCURRENCY] 限制
     * （不是一次性开 20 个请求 —— 那会直接触发风控）。
     */
    private suspend fun probeVertical(candidates: List<VideoItem>): List<VideoItem> =
        coroutineScope {
            candidates
                .chunked(PROBE_CONCURRENCY)
                .flatMap { chunk ->
                    chunk.map { item ->
                        async { item.takeIf { isVertical(it.bvid) } }
                    }.awaitAll().filterNotNull()
                }
        }

    /**
     * 判断单个视频是否竖屏。
     *
     * 先查缓存；未命中则调详情接口取真实分辨率。
     * 查询失败时**返回 false**（宁可不收，也不把横屏混进竖屏流 ——
     * 这是需求明确禁止的）。
     */
    private suspend fun isVertical(bvid: String): Boolean {
        cacheLock.withLock { verticalCache[bvid] }?.let { return it }

        val detail = runCatching { videoRepository.detail(bvid) }.getOrNull()
        val info = detail?.let { d ->
            runCatching { videoRepository.playInfo(d.bvid, d.cid) }.getOrNull()
        }

        // 取流信息里有真实宽高；拿不到时视为"不确定" → 不收
        val w = info?.width ?: 0
        val h = info?.height ?: 0
        val isV = w > 0 && h > 0 && h > w

        cacheLock.withLock { verticalCache[bvid] = isV }
        return isV
    }

    /** 已缓存的判定（供测试与调试）。 */
    suspend fun cachedDecision(bvid: String): Boolean? =
        cacheLock.withLock { verticalCache[bvid] }

    companion object {
        /** 单批候选数（与推荐流 ps 一致）。 */
        const val PAGE_SIZE = 20

        /** 目标竖屏条数（一屏约 10 条，多备一些）。 */
        const val PAGE_TARGET = 10

        /** 最多翻几轮推荐流。 */
        const val MAX_ROUNDS = 6

        /** 并发探测数。太高会被风控（推荐流本身就有 ≥1s 限流）。 */
        const val PROBE_CONCURRENCY = 4
    }
}
