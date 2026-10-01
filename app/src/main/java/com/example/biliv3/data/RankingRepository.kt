package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.model.VideoItem

/**
 * 排行榜仓库。
 *
 * ## 两个接口，两种风控表现（实测）
 *
 * | 接口 | 实测 |
 * |---|---|
 * | `ranking/v2`（全站榜） | **`-352` 风控**，间歇性失败 |
 * | `ranking/region`（分区榜） | `code=0` 稳定 |
 *
 * `-352` 是"请求过于频繁"，同一 IP 短时间多次请求就会触发。
 * 早期曾误判为"接口失效"（只测一两次就下结论），复测 6/6 成功
 * 证明是临时风控。
 *
 * 因此这里**不换接口**，而是做降级：全站榜失败时退到分区榜。
 */
class RankingRepository(
    private val api: BiliApi,
) {

    /**
     * 全站热门榜。
     *
     * @param rid 0 = 全站；其它为分区 id
     */
    suspend fun ranking(rid: Int = 0, pageSize: Int = 20): List<VideoItem> =
        runCatching { api.ranking(pageSize) }
            .getOrDefault(emptyList())
            .ifEmpty {
                // 全站榜失败/为空 → 退到分区榜（风控更宽松）
                runCatching { api.regionRanking(rid = if (rid == 0) 1 else rid, pageSize = pageSize) }
                    .getOrDefault(emptyList())
            }

    /**
     * 分区榜。
     *
     * @param rid 分区 id（1=动画 3=音乐 4=游戏 36=知识 181=影视 …）
     */
    suspend fun regionRanking(rid: Int, pageSize: Int = 20): List<VideoItem> =
        runCatching { api.regionRanking(rid, pageSize) }
            .getOrDefault(emptyList())
}

/**
 * 排行榜分区。
 *
 * rid 取自 `CategoryEntry.defaults()` 的子集 —— 排行榜只覆盖部分分区，
 * 且这几个是实测可用的。
 */
data class RankingTab(
    val rid: Int,
    val name: String,
) {
    companion object {
        fun defaults(): List<RankingTab> = listOf(
            RankingTab(0, "全站"),
            RankingTab(1, "动画"),
            RankingTab(3, "音乐"),
            RankingTab(4, "游戏"),
            RankingTab(36, "知识"),
            RankingTab(188, "科技"),
            RankingTab(181, "影视"),
        )
    }
}
