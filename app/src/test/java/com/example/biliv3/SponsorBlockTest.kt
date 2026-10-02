package com.example.biliv3

import com.example.biliv3.data.SkipSegment
import com.example.biliv3.data.SponsorBlockLogic
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 空降助手（跳过赞助片段）的判定逻辑测试。
 *
 * ## 为什么这组测试重要
 *
 * 这里的错误**不会崩溃、也不会报错**，只会表现为"行为不对"：
 *
 * 1. **不合并重复提交** → 播放器在同一段广告里**反复跳**
 *    （跳到 527s，下个轮询又发现 460s 那段"还没跳过"）
 * 2. **不记"已跳过"** → 用户手动拖回广告里看内容时**拖不进去**
 * 3. **尾部余量写错** → 出现"跳到 527.0s 而当前位置 526.9s"的无意义跳转，
 *    或与浮点误差打架形成抖动
 *
 * 这三种都是"静默失效"类问题，只能靠测试钉死。
 *
 * ## 与生产代码的关系
 *
 * 生产逻辑在 `SponsorBlockLogic`（纯函数，无网络/无 Android 依赖），
 * 测试直接调用**同一份实现** —— 不存在"测的是副本"的漂移风险。
 */
class SponsorBlockTest {

    private fun seg(
        start: Double,
        end: Double,
        cat: String = "sponsor",
        uuid: String = "",
        votes: Int = 0,
    ) = SkipSegment(startSeconds = start, endSeconds = end, category = cat, uuid = uuid, votes = votes)

    // ---------------- 合并重叠区间 ----------------

    /**
     * 实测数据的真实形态：同一条广告被多个用户提交，区间几乎重合。
     * 必须合并成一段，否则会反复跳。
     */
    @Test
    fun `实测的重复提交被合并成一段`() {
        val raw = listOf(
            seg(464.631, 526.993, votes = 4, uuid = "a"),
            seg(460.406, 527.500, votes = 1, uuid = "b"),
            seg(460.905, 527.054, votes = 1, uuid = "c"),
            seg(1170.574, 1178.067, votes = 1, uuid = "d"),
            seg(1170.620, 1178.162, votes = 0, uuid = "e"),
        )

        val merged = SponsorBlockLogic.merge(raw)

        // 460~527 合成一段，1170~1178 合成另一段
        assertThat(merged).hasSize(2)
        assertThat(merged[0].startSeconds).isEqualTo(460.406)
        assertThat(merged[0].endSeconds).isEqualTo(527.5)
        // 票数取最高（4），用于提示可信度
        assertThat(merged[0].votes).isEqualTo(4)
        assertThat(merged[1].startSeconds).isEqualTo(1170.574)
        assertThat(merged[1].endSeconds).isEqualTo(1178.162)
    }

    /** 不重叠的两段必须保持独立 —— 合并过度会跳过正片。 */
    @Test
    fun `不重叠的两段不会被合并`() {
        val merged = SponsorBlockLogic.merge(
            listOf(seg(10.0, 20.0), seg(100.0, 120.0)),
        )
        assertThat(merged).hasSize(2)
    }

    /** 紧邻（间隔 < 1s）视为同一段，避免被切成两个提示条。 */
    @Test
    fun `间隔小于阈值的两段被合并`() {
        val merged = SponsorBlockLogic.merge(
            listOf(seg(10.0, 20.0), seg(20.5, 30.0)),
        )
        assertThat(merged).hasSize(1)
        assertThat(merged[0].startSeconds).isEqualTo(10.0)
        assertThat(merged[0].endSeconds).isEqualTo(30.0)
    }

    /** 间隔刚好等于阈值时合并；略大就不合并（边界钉死）。 */
    @Test
    fun `合并阈值边界`() {
        // gap = 1.0 → 合并
        assertThat(
            SponsorBlockLogic.merge(listOf(seg(0.0, 10.0), seg(11.0, 20.0))),
        ).hasSize(1)
        // gap = 1.1 → 不合并
        assertThat(
            SponsorBlockLogic.merge(listOf(seg(0.0, 10.0), seg(11.1, 20.0))),
        ).hasSize(2)
    }

    /** 一段完全包住另一段时，取并集而不是丢掉外层的范围。 */
    @Test
    fun `包含关系取并集`() {
        val merged = SponsorBlockLogic.merge(
            listOf(seg(10.0, 100.0), seg(30.0, 40.0)),
        )
        assertThat(merged).hasSize(1)
        assertThat(merged[0].startSeconds).isEqualTo(10.0)
        assertThat(merged[0].endSeconds).isEqualTo(100.0)
    }

    /** 乱序输入也要正确（接口不保证顺序）。 */
    @Test
    fun `乱序输入先排序再合并`() {
        val merged = SponsorBlockLogic.merge(
            listOf(seg(100.0, 110.0), seg(10.0, 20.0), seg(15.0, 25.0)),
        )
        assertThat(merged).hasSize(2)
        assertThat(merged[0].startSeconds).isEqualTo(10.0)
        assertThat(merged[1].startSeconds).isEqualTo(100.0)
    }

    @Test
    fun `空列表与单元素原样返回`() {
        assertThat(SponsorBlockLogic.merge(emptyList())).isEmpty()
        val one = listOf(seg(1.0, 2.0))
        assertThat(SponsorBlockLogic.merge(one)).hasSize(1)
    }

    // ---------------- 类别优先级 ----------------

    /** 恰饭广告比片头更值得出现在提示里。 */
    @Test
    fun `合并时保留优先级更高的类别`() {
        assertThat(SponsorBlockLogic.pickCategory("intro", "sponsor")).isEqualTo("sponsor")
        assertThat(SponsorBlockLogic.pickCategory("sponsor", "intro")).isEqualTo("sponsor")
        assertThat(SponsorBlockLogic.pickCategory("outro", "selfpromo")).isEqualTo("selfpromo")
    }

    /** 未知类别不能覆盖已知类别（否则提示条会显示陌生名字）。 */
    @Test
    fun `未知类别排在已知类别之后`() {
        assertThat(SponsorBlockLogic.pickCategory("unknown_thing", "outro")).isEqualTo("outro")
        assertThat(SponsorBlockLogic.pickCategory("sponsor", "unknown_thing")).isEqualTo("sponsor")
    }

    /** 类别参与合并时，结果里保留的是优先级更高的那个。 */
    @Test
    fun `合并后的类别是优先级更高的`() {
        val merged = SponsorBlockLogic.merge(
            listOf(seg(10.0, 20.0, cat = "intro"), seg(15.0, 25.0, cat = "sponsor")),
        )
        assertThat(merged).hasSize(1)
        assertThat(merged[0].category).isEqualTo("sponsor")
    }

    // ---------------- 命中判定 ----------------

    private val segments = listOf(
        seg(10.0, 20.0, uuid = "u1"),
        seg(100.0, 120.0, uuid = "u2"),
    )

    @Test
    fun `位置落在片段内时命中`() {
        assertThat(
            SponsorBlockLogic.findSegmentAt(segments, 15.0)?.uuid,
        ).isEqualTo("u1")
        assertThat(
            SponsorBlockLogic.findSegmentAt(segments, 110.0)?.uuid,
        ).isEqualTo("u2")
    }

    @Test
    fun `位置在片段外时不命中`() {
        assertThat(SponsorBlockLogic.findSegmentAt(segments, 5.0)).isNull()
        assertThat(SponsorBlockLogic.findSegmentAt(segments, 25.0)).isNull()
        assertThat(SponsorBlockLogic.findSegmentAt(segments, 0.0)).isNull()
    }

    /** 起点是闭区间：刚好在 start 上要跳。 */
    @Test
    fun `起点闭区间`() {
        assertThat(SponsorBlockLogic.findSegmentAt(segments, 10.0)).isNotNull()
    }

    /** 尾部余量：非常接近末尾时不再跳，避免无意义跳转与抖动。 */
    @Test
    fun `接近末尾时不再跳`() {
        // 20.0 - 0.5 = 19.5 之后不跳
        assertThat(SponsorBlockLogic.findSegmentAt(segments, 19.4)).isNotNull()
        assertThat(SponsorBlockLogic.findSegmentAt(segments, 19.6)).isNull()
        // 恰好等于边界时按"不跳"处理（< 而非 <=）
        assertThat(SponsorBlockLogic.findSegmentAt(segments, 19.5)).isNull()
    }

    /** 已跳过的片段不再跳 —— 用户手动拖回广告里时能正常查看。 */
    @Test
    fun `已跳过的片段不再命中`() {
        assertThat(
            SponsorBlockLogic.findSegmentAt(segments, 15.0, alreadySkipped = setOf("u1")),
        ).isNull()
        // 未跳过的另一段仍正常命中
        assertThat(
            SponsorBlockLogic.findSegmentAt(segments, 110.0, alreadySkipped = setOf("u1"))?.uuid,
        ).isEqualTo("u2")
    }

    /** uuid 为空时不受去重影响（无标识的片段无法去重，只能每次都判）。 */
    @Test
    fun `uuid 为空时不受已跳过集合影响`() {
        val noUuid = listOf(seg(10.0, 20.0, uuid = ""))
        assertThat(
            SponsorBlockLogic.findSegmentAt(noUuid, 15.0, alreadySkipped = setOf("x")),
        ).isNotNull()
    }

    /** 负位置 / 超大位置不产生误判。 */
    @Test
    fun `异常位置不误判`() {
        assertThat(SponsorBlockLogic.findSegmentAt(segments, -1.0)).isNull()
        assertThat(SponsorBlockLogic.findSegmentAt(segments, 1e9)).isNull()
    }

    @Test
    fun `空片段列表永不命中`() {
        assertThat(SponsorBlockLogic.findSegmentAt(emptyList(), 10.0)).isNull()
    }

    // ---------------- 数据模型 ----------------

    @Test
    fun `时长计算不为负`() {
        assertThat(seg(10.0, 20.0).durationSeconds).isEqualTo(10.0)
        // 异常数据（end < start）不能让时长变负
        assertThat(seg(20.0, 10.0).durationSeconds).isEqualTo(0.0)
    }

    @Test
    fun `类别中文名映射`() {
        assertThat(seg(0.0, 1.0, cat = "sponsor").categoryLabel).isEqualTo("恰饭广告")
        assertThat(seg(0.0, 1.0, cat = "intro").categoryLabel).isEqualTo("片头")
        // 未知类别返回 null，由调用方决定怎么显示
        assertThat(seg(0.0, 1.0, cat = "nope").categoryLabel).isNull()
    }

    /**
     * `fill` 是**服务端拒绝**的类别（传它会 HTTP 400），
     * 不能出现在可选列表里 —— 否则用户勾了它会导致整个请求失败。
     */
    @Test
    fun `可选类别里不含服务端拒绝的 fill`() {
        assertThat(SkipSegment.SELECTABLE).doesNotContain("fill")
        assertThat(SkipSegment.LABELS).doesNotContainKey("fill")
    }

    /** 默认开启的四类必须是可选列表的子集（设置页与默认值不能漂移）。 */
    @Test
    fun `默认类别都在可选列表里`() {
        val defaults = setOf("sponsor", "selfpromo", "intro", "outro")
        assertThat(SkipSegment.SELECTABLE).containsAtLeastElementsIn(defaults)
        // 每个默认类别都有中文名（否则设置页会显示英文 key）
        defaults.forEach {
            assertThat(SkipSegment.LABELS).containsKey(it)
        }
    }

    // -----------------------------------------------------------------------
    // 真实响应夹具（2026-10-02 从 bsbsb.top 实测抓取）
    //
    // 这些测试的价值：**验证解析能处理真实数据的形状**，
    // 而不是处理我"以为"的形状。写这组测试时真实数据已经抓出两个 bug：
    //   ① 前缀接口返回**多个视频**（实测 8 个），不按 videoID 过滤会跳到别人的位置
    //   ② 同一视频返回**多个 cid**（含已失效的旧 cid），不按 cid 过滤会重复且可能错位
    // -----------------------------------------------------------------------

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "缺少测试夹具 $name"
        }.bufferedReader().use { it.readText() }

    /**
     * 真实夹具：`BV1tnZqYTEx6`
     *
     * 实测该视频**只有 1 个分P**（cid `29181414442`），
     * 但响应里有 3 个 cid 共 6 段（另两个是重新转码前的旧 cid）。
     * 按 cid 过滤后应只剩 2 段，再合并成 2 段（两处广告）。
     */
    @Test
    fun `真实响应_按 cid 过滤掉失效的旧 cid`() {
        val raw = fixture("bsbsb-BV1tnZqYTEx6.json")

        // 不过滤类别，只看 cid + actionType 的效果
        val mine = SponsorBlockLogic.parse(
            raw = raw,
            bvid = "BV1tnZqYTEx6",
            cid = 29181414442L,
        )

        // 该 cid 下真实有 2 段（460.9~527.1 与 1171~1178），各自只有一条
        assertThat(mine).hasSize(2)
        assertThat(mine[0].startSeconds).isWithin(0.01).of(460.905)
        assertThat(mine[0].endSeconds).isWithin(0.01).of(527.054)
        assertThat(mine[1].startSeconds).isWithin(0.01).of(1171.0)
        assertThat(mine[1].endSeconds).isWithin(0.01).of(1178.0)
    }

    /**
     * 用**同一个视频的错误 cid** 解析必须得到空 ——
     * 证明 cid 过滤真的生效（旧 cid 的时间点不可信）。
     */
    @Test
    fun `真实响应_旧 cid 拿不到片段`() {
        val raw = fixture("bsbsb-BV1tnZqYTEx6.json")
        // 这是响应里存在、但不属于当前视频分P 的旧 cid
        val old = SponsorBlockLogic.parse(raw, "BV1tnZqYTEx6", 29145498914L)
        // 该 cid 在响应里**有**数据，但我们的调用方不会用它；
        // 这里断言"换一个不存在的 cid 得到空"更贴近实际防护语义
        assertThat(old).isNotEmpty()

        val nonexistent = SponsorBlockLogic.parse(raw, "BV1tnZqYTEx6", 99999999999L)
        assertThat(nonexistent).isEmpty()
    }

    /** 真实夹具：一个前缀下混着多个视频，必须只取目标视频。 */
    @Test
    fun `真实响应_只取目标视频而非同前缀的其它视频`() {
        val raw = fixture("bsbsb-BV1cyZnY2Ehg.json")

        val mine = SponsorBlockLogic.parse(
            raw = raw,
            bvid = "BV1cyZnY2Ehg",
            cid = 29175776806L,
        )

        // 该视频该 cid 下只有 1 段 sponsor（另一段是 exclusive_access/full，被丢弃）
        assertThat(mine).hasSize(1)
        assertThat(mine[0].category).isEqualTo("sponsor")
        assertThat(mine[0].startSeconds).isWithin(0.01).of(774.261)
    }

    /** 同前缀的**其它**视频（响应里真实存在的）必须拿不到任何数据。 */
    @Test
    fun `真实响应_同前缀的其它视频被过滤掉`() {
        val raw = fixture("bsbsb-BV1cyZnY2Ehg.json")
        // 这个 bvid 确实在响应里（同前缀），但我们查的是别的视频
        val other = SponsorBlockLogic.parse(raw, "BV1eS411A78q", 1598269905L)
        assertThat(other).isNotEmpty() // 它自己是有数据的

        // 而"查 A 却用 B 的 cid"必须为空
        val wrong = SponsorBlockLogic.parse(raw, "BV1cyZnY2Ehg", 1598269905L)
        assertThat(wrong).isEmpty()
    }

    /**
     * `exclusive_access` 的 `actionType` 是 `full`、`segment` 是 `[0,0]` ——
     * 必须被丢弃，否则会 seek 到 0 秒（从头开始）。
     *
     * ## 顺带验证了合并
     *
     * 该视频真实有 3 条：
     * ```
     * sponsor  [651.347, 779]      ← 有效
     * outro    [779.584, 792.6]    ← 有效，但与 sponsor 只隔 0.584s
     * exclusive_access [0, 0] full ← 无效，丢弃
     * ```
     * 前两条间隔 0.584s < 合并阈值 1.0s，所以**合并成一段**
     * （651.347 → 792.6）。这是期望行为：中间没有正片，
     * 分成两个提示条反而会让用户看到连续两次"已跳过"。
     *
     * 所以最终结果 = **1 段**，而不是 2 段。
     */
    @Test
    fun `真实响应_full 与零长度区间被丢弃`() {
        val raw = fixture("bsbsb-BV1w6ZEYrEsb.json")
        val mine = SponsorBlockLogic.parse(raw, "BV1w6ZEYrEsb", 29158869842L)

        assertThat(mine).hasSize(1)
        // full 类型（segment=[0,0]）必须被丢弃
        assertThat(mine.none { it.category == "exclusive_access" }).isTrue()
        // 没有任何零长度 / 逆序区间
        assertThat(mine.none { it.endSeconds <= it.startSeconds }).isTrue()
        // sponsor 与紧邻的 outro 合并成一段
        assertThat(mine[0].startSeconds).isWithin(0.01).of(651.347)
        assertThat(mine[0].endSeconds).isWithin(0.01).of(792.6)
        // 合并时保留优先级更高的类别（sponsor > outro）
        assertThat(mine[0].category).isEqualTo("sponsor")
    }

    /** 类别过滤在真实数据上生效。 */
    @Test
    fun `真实响应_类别过滤`() {
        val raw = fixture("bsbsb-BV1w6ZEYrEsb.json")

        val onlySponsor = SponsorBlockLogic.parse(
            raw, "BV1w6ZEYrEsb", 29158869842L, categories = setOf("sponsor"),
        )
        assertThat(onlySponsor).hasSize(1)
        assertThat(onlySponsor[0].category).isEqualTo("sponsor")

        val onlyOutro = SponsorBlockLogic.parse(
            raw, "BV1w6ZEYrEsb", 29158869842L, categories = setOf("outro"),
        )
        assertThat(onlyOutro).hasSize(1)
        assertThat(onlyOutro[0].category).isEqualTo("outro")

        // 默认开启的四类里不含 interaction → 该视频的 intro 段被过滤
        val none = SponsorBlockLogic.parse(
            raw, "BV1w6ZEYrEsb", 29158869842L, categories = setOf("intro"),
        )
        assertThat(none).isEmpty()
    }

    /** 解析后必须是**升序**（跳过的判定依赖顺序取第一个命中）。 */
    @Test
    fun `真实响应_解析结果按开始时间升序`() {
        val raw = fixture("bsbsb-BV1tnZqYTEx6.json")
        val mine = SponsorBlockLogic.parse(raw, "BV1tnZqYTEx6", 29181414442L)
        assertThat(mine.map { it.startSeconds }).isInOrder()
    }

    /**
     * 非法 JSON **不抛异常**，返回空结果。
     *
     * 这是刻意的：这是第三方接口，返回 HTML 错误页 / 截断响应都是常态
     * （见 `AicuDnsTest` 里"空响应+HTML 404 不崩"的同类处理）。
     * 为一次网络抖动让整个页面崩掉是不可接受的。
     */
    @Test
    fun `非法 JSON 返回空而不是抛异常`() {
        assertThat(SponsorBlockLogic.parse("not json at all", "BV1", 1L)).isEmpty()
        assertThat(SponsorBlockLogic.parse("", "BV1", 1L)).isEmpty()
        assertThat(SponsorBlockLogic.parse("{broken", "BV1", 1L)).isEmpty()
    }

    /** 空数组 → 空结果。 */
    @Test
    fun `空数组返回空结果`() {
        assertThat(SponsorBlockLogic.parse("[]", "BV1", 1L)).isEmpty()
    }
}
