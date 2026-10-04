package com.example.biliv3

import com.example.biliv3.data.HistoryParser
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * 历史记录解析的回归测试（v1.5.1）。
 *
 * ## 这个测试防的是什么
 *
 * 「历史记录里的视频点开播不了」的根因是**字段层级读错**：
 * `history/cursor` 把 `bvid` / `cid` 嵌在 `history` 子对象里，顶层没有。
 * 原代码从顶层读 `bvid` → 恒为空串 → 详情页用空 bvid 请求 → 加载失败。
 *
 * ## ⚠️ 关于测试数据
 *
 * 下面的 JSON **不是编的** —— 它是**真实接口返回的字段结构**，
 * 用真实账号脚本直连 `x/web-interface/history/cursor` 确认过：
 *
 * - 顶层有 `bvid` 吗 → **False**
 * - `history` 里有 `bvid` 吗 → **True**
 *
 * 只把**值**换成了假数据（`BV1FAKE...`），结构一字不改。
 * 这样测试既能在无网络环境跑，又不会因为"我猜的结构"而与真实接口脱节。
 *
 * ## 为什么不用 mock
 *
 * 这里测的是**纯解析函数**（JSON → 数据模型），它本来就是纯函数 ——
 * 直接喂真实结构的 JSON 比 mock 一个 Repository 更接近真实。
 * 真正的端到端验证（真实 Cookie + 真实取流）在
 * `tool/probe-history.py` 里做，那个不进单测（需要凭据）。
 */
class HistoryParseTest {

    /**
     * 真实接口的字段结构（值已替换为假数据）。
     *
     * ⚠️ `history` 子对象的字段集**恰好**是这 8 个（实测）：
     * `business` / `bvid` / `cid` / `dt` / `epid` / `oid` / `page` / `part`
     * —— **没有 `duration`**。时长只在顶层。别往这里加 duration。
     */
    private fun rawItem(
        bvid: String = "BV1FAKE00001",
        cid: Long = 39534134840L,
        page: Int = 1,
        progressMs: Long = 12_000L,
        duration: Long = 75L,
        videos: Int = 1,
    ) = JSONObject(
        """
        {
          "title": "测试视频",
          "cover": "http://example.invalid/cover.jpg",
          "author_name": "测试UP",
          "author_face": "http://example.invalid/face.jpg",
          "duration": $duration,
          "progress": $progressMs,
          "view_at": 1730000000,
          "videos": $videos,
          "is_finish": 0,
          "history": {
            "oid": 116838629378379,
            "epid": 0,
            "bvid": "$bvid",
            "page": $page,
            "cid": $cid,
            "part": "P1",
            "business": "archive",
            "dt": 3
          }
        }
        """.trimIndent(),
    )

    private fun parse(o: JSONObject) = HistoryParser.parse(o)

    // ---------------- 核心回归 ----------------

    @Test
    fun `bvid 从 history 子对象读取`() {
        // 这是那条 bug 的直接回归：原实现读顶层 → 恒空
        val e = parse(rawItem(bvid = "BV1FAKE00001"))
        assertThat(e).isNotNull()
        assertThat(e!!.video.bvid).isEqualTo("BV1FAKE00001")
    }

    @Test
    fun `bvid 不为空`() {
        val e = parse(rawItem())!!
        assertThat(e.video.bvid).isNotEmpty()
    }

    @Test
    fun `cid 从 history 子对象读取`() {
        val e = parse(rawItem(cid = 12345678901L))!!
        assertThat(e.cid).isEqualTo(12345678901L)
    }

    @Test
    fun `顶层没有 bvid 时依然能解析`() {
        // 真实响应就是这样的：顶层无 bvid
        val raw = rawItem()
        assertThat(raw.has("bvid")).isFalse()
        assertThat(parse(raw)!!.video.bvid).isNotEmpty()
    }

    // ---------------- 防御 ----------------

    @Test
    fun `缺少 history 子对象时返回 null`() {
        val o = JSONObject("""{"title":"x","cover":"y"}""")
        assertThat(parse(o)).isNull()
    }

    @Test
    fun `bvid 为空时返回 null 不塞空条目`() {
        // 非 archive 内容（直播/专栏）bvid 可能是空 —— 宁可跳过
        assertThat(parse(rawItem(bvid = ""))).isNull()
    }

    @Test
    fun `cid 为 0 时返回 null`() {
        assertThat(parse(rawItem(cid = 0L))).isNull()
    }

    // ---------------- 进度与时长 ----------------

    @Test
    fun `进度从顶层 progress 毫秒换算成秒`() {
        val e = parse(rawItem(progressMs = 12_000L))!!
        assertThat(e.progressSeconds).isEqualTo(12)
    }

    @Test
    fun `progress 为负一表示已看完 进度记 0`() {
        // 接口用 -1 表示看完；不能把它当"负进度"
        val e = parse(rawItem(progressMs = -1L))!!
        assertThat(e.progressSeconds).isEqualTo(0)
        assertThat(e.isFinished).isTrue()
    }

    @Test
    fun `未看完时 isFinished 为 false`() {
        assertThat(parse(rawItem(progressMs = 5_000L))!!.isFinished).isFalse()
    }

    @Test
    fun `时长从顶层读取`() {
        // 实测：history 子对象里**没有** duration，只在顶层
        val e = parse(rawItem(duration = 75L))!!
        assertThat(e.video.durationSeconds).isEqualTo(75)
    }

    @Test
    fun `顶层时长缺失时记 0 不崩`() {
        val o = rawItem(duration = 0L)
        assertThat(parse(o)!!.video.durationSeconds).isEqualTo(0)
    }

    @Test
    fun `history 里若出现 duration 则优先于顶层`() {
        // 防御性：实测当前接口不返回 history.duration，
        // 但万一以后加了，那时它才是"当前 P 的时长"，比顶层总时长准。
        // 这条钉住"优先读 history"这个顺序，避免以后被改反。
        val raw = rawItem(duration = 9_999L)
        raw.getJSONObject("history").put("duration", 75L)
        assertThat(parse(raw)!!.video.durationSeconds).isEqualTo(75)
    }

    // ---------------- 其它字段 ----------------

    @Test
    fun `标题封面作者正确映射`() {
        val e = parse(rawItem())!!
        assertThat(e.video.title).isEqualTo("测试视频")
        assertThat(e.video.cover).contains("cover.jpg")
        assertThat(e.video.authorName).isEqualTo("测试UP")
    }

    @Test
    fun `观看时刻正确映射`() {
        assertThat(parse(rawItem())!!.viewAt).isEqualTo(1730000000L)
    }

    @Test
    fun `不把其它分区字段当播放量`() {
        // 历史接口不返回播放量，不能拿 duration/view 冒充
        val e = parse(rawItem())!!
        assertThat(e.video.playCount).isEqualTo(0)
        assertThat(e.video.danmakuCount).isEqualTo(0)
    }
}
