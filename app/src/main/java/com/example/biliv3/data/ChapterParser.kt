package com.example.biliv3.data

import org.json.JSONObject

/**
 * 视频章节（空降助手）解析（v1.5.3）。
 *
 * ## 数据来源与实测
 *
 * `x/player/v2` 的 `data.view_points` 数组（与"在看人数"同一个响应，
 * **不需要额外请求**）。
 *
 * 实测（真实账号，扫了排行榜 + 热门共 60 个视频）：
 * **全部为空数组 `[]`** —— 章节是 UP 主在投稿时**手动添加**的，
 * 属少数视频。所以这个功能的**空态是常态**，必须诚实显示
 * 「该视频没有章节」，而不是造假数据或隐藏入口。
 *
 * ## 字段形态
 *
 * 非空时每个元素形如：
 * ```json
 * { "type": 1, "from": 0, "to": 120, "content": "开场", "imgUrl": "", "logoUrl": "" }
 * ```
 * - `from` / `to`：秒
 * - `content`：章节标题
 * - `type`：1 = 普通章节
 *
 * ⚠️ 因为**没有拿到真实非空样本**（60 个视频全空），
 * 解析按上面这个公开结构写，并做**防御性容错**：
 * 字段缺失、`to <= from`、`content` 为空都跳过该条，
 * 不抛异常。**未在真实数据上验证过非空分支** —— 这一点必须如实记录。
 *
 * ## 为什么要抽成纯 Kotlin
 *
 * 同 §7.17-96 / §7.16 的教训：解析写在需要 `Context` 的类里就**测不了**，
 * 测不了就会一直错下去。这里抽出来，`ChapterParserTest` 直接跑。
 */
object ChapterParser {

    /**
     * 从 `player/v2` 的 `data` 对象解析章节列表。
     *
     * @return 解析出的章节；没有 / 全不合法时返回**空列表**（不是 null）——
     *         调用方据此显示"该视频没有章节"的空态。
     */
    fun parse(data: JSONObject?): List<VideoChapter> {
        val arr = data?.optJSONArray("view_points") ?: return emptyList()
        if (arr.length() == 0) return emptyList()

        val out = ArrayList<VideoChapter>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val from = o.optInt("from", -1)
            val to = o.optInt("to", -1)
            val title = o.optString("content").trim()

            // 防御：起点非负、终点大于起点、标题非空 —— 缺一不可
            if (from < 0 || to <= from || title.isEmpty()) continue

            out.add(
                VideoChapter(
                    // ⚠️ index 先占位，**排序后再统一编号** ——
                    // 若在这里就用 out.size，排序后 index 会与顺序不一致
                    // （单测 `多个章节按起点排序` 抓到过这个 bug）。
                    index = 0,
                    fromSeconds = from,
                    toSeconds = to,
                    title = title,
                ),
            )
        }
        // 按起点排序（接口顺序未必有序），然后**重新编号**
        return out.sortedBy { it.fromSeconds }
            .mapIndexed { i, ch -> ch.copy(index = i) }
    }
}

/**
 * 一个视频章节。
 *
 * @param index 序号（0 起，用于展示 `01` / `02`）
 * @param fromSeconds 起始秒
 * @param toSeconds 结束秒（**不含**）
 * @param title 章节标题
 */
data class VideoChapter(
    val index: Int,
    val fromSeconds: Int,
    val toSeconds: Int,
    val title: String,
) {
    /** 展示用时间标签，如 `01:23`。 */
    val timeLabel: String
        get() {
            val m = fromSeconds / 60
            val s = fromSeconds % 60
            return "%02d:%02d".format(m, s)
        }

    /** 该章节时长（秒）。 */
    val durationSeconds: Int get() = (toSeconds - fromSeconds).coerceAtLeast(0)

    /** 判断某个播放位置是否落在本章节内。 */
    fun contains(positionSeconds: Int): Boolean =
        positionSeconds >= fromSeconds && positionSeconds < toSeconds
}
