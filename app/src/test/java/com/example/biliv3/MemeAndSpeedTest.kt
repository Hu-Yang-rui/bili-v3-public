package com.example.biliv3

import com.example.biliv3.data.SpeedTiers
import com.example.biliv3.data.meme.Meme
import com.example.biliv3.data.meme.MemeLibrary
import com.example.biliv3.data.meme.MemeSearch
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 倍速档位 + 烂梗库测试。
 *
 * ## 为什么这组测试重要
 *
 * ### 倍速
 * 档位此前**写死在三处**（播放器弹层 / 设置页 / 各自一个 `formatSpeedLabel`）。
 * 扩到 9 档时如果漏改一处，用户会看到"弹层里有 3.0×、设置页里没有"——
 * 而这类不一致**不会报错**。
 *
 * 另外 `nearest()` 是"当前档位高亮"的唯一依据：
 * 匹配不上会导致**UI 上没有任何档位被选中**（用户看不出当前倍速）。
 *
 * ### 烂梗库
 * 搜索要覆盖正文 / 备注 / 分类名三处 —— 只搜正文的话，用户按分类词搜
 * （如"称赞"）会得到 0 结果，而那正是他找梗的方式。
 */
class MemeAndSpeedTest {

    // ---------------- 倍速档位 ----------------

    /** 需求要求的 9 档必须全部存在。 */
    @Test
    fun `包含需求要求的九档`() {
        val required = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        for (v in required) {
            assertThat(SpeedTiers.VALUES).contains(v)
        }
        assertThat(SpeedTiers.VALUES).hasSize(9)
    }

    /** 档位必须**升序**（UI 按此顺序渲染）。 */
    @Test
    fun `档位升序`() {
        val v = SpeedTiers.VALUES
        assertThat(v).isEqualTo(v.sorted())
    }

    /** 1.0 必须在列表里（否则用户回不到原速）。 */
    @Test
    fun `包含原速`() {
        assertThat(SpeedTiers.VALUES).contains(SpeedTiers.DEFAULT)
    }

    /**
     * ⚠️ 超过 2× 的档位必须标 `verified = false`。
     *
     * 因为 ExoPlayer 对高倍速**静默接受**（不抛异常），
     * "设了 3.0×" ≠ "它在以 3.0× 播放"。标注出来才对用户诚实。
     */
    @Test
    fun `高倍速标记为未确认`() {
        val high = SpeedTiers.ALL.filter { it.value > 2.0f }
        assertThat(high).isNotEmpty()
        high.forEach { assertThat(it.verified).isFalse() }
    }

    /** 2× 及以下的常用档位是已确认的。 */
    @Test
    fun `常用档位标记为已确认`() {
        SpeedTiers.ALL.filter { it.value <= 2.0f }.forEach {
            assertThat(it.verified).isTrue()
        }
    }

    /**
     * ⚠️ 直播档位**不含**未确认的高倍速。
     *
     * 直播链路本来就更脆弱（缓冲模型不同、没有"快进到底"），
     * 再叠加音频失真档位只会更容易出问题。
     */
    @Test
    fun `直播档位不含未确认项`() {
        assertThat(SpeedTiers.LIVE).isNotEmpty()
        SpeedTiers.LIVE.forEach {
            assertThat(it.verified).isTrue()
            assertThat(it.value).isAtMost(2.0f)
        }
    }

    /** 直播档位是全部档位的子集（不出现"直播专有档位"）。 */
    @Test
    fun `直播档位是子集`() {
        assertThat(SpeedTiers.VALUES).containsAtLeastElementsIn(SpeedTiers.LIVE.map { it.value })
    }

    // ---------------- nearest()：当前档位高亮 ----------------

    /** 精确值直接命中。 */
    @Test
    fun `精确值匹配`() {
        assertThat(SpeedTiers.nearest(1.5f)).isEqualTo(1.5f)
        assertThat(SpeedTiers.nearest(0.75f)).isEqualTo(0.75f)
    }

    /** 浮点误差要能被吸收（0.7500001 应匹配 0.75）。 */
    @Test
    fun `吸收浮点误差`() {
        assertThat(SpeedTiers.nearest(0.7500001f)).isEqualTo(0.75f)
        assertThat(SpeedTiers.nearest(1.4999999f)).isEqualTo(1.5f)
    }

    /**
     * ⚠️ 差异过大时**回退到默认值**，而不是"就近高亮"。
     *
     * 例如旧版本存过 1.1×：高亮 1.0× 会让用户以为自己在用 1.0×，
     * 而实际播放器是 1.1× —— 显示与实际不符。
     */
    @Test
    fun `差异过大回退默认`() {
        assertThat(SpeedTiers.nearest(1.1f)).isEqualTo(SpeedTiers.DEFAULT)
        assertThat(SpeedTiers.nearest(7.0f)).isEqualTo(SpeedTiers.DEFAULT)
    }

    /** 非法输入不崩。 */
    @Test
    fun `非法输入回退默认`() {
        assertThat(SpeedTiers.nearest(Float.NaN)).isEqualTo(SpeedTiers.DEFAULT)
        assertThat(SpeedTiers.nearest(Float.POSITIVE_INFINITY)).isEqualTo(SpeedTiers.DEFAULT)
        assertThat(SpeedTiers.nearest(1.0f, emptyList())).isEqualTo(SpeedTiers.DEFAULT)
    }

    /** 文案：整数不带小数点，小数保留。 */
    @Test
    fun `倍速文案`() {
        assertThat(SpeedTiers.label(2.0f)).isEqualTo("2")
        assertThat(SpeedTiers.label(1.0f)).isEqualTo("1")
        assertThat(SpeedTiers.label(0.75f)).isEqualTo("0.75")
        assertThat(SpeedTiers.label(1.25f)).isEqualTo("1.25")
        assertThat(SpeedTiers.label(2.5f)).isEqualTo("2.5")
    }

    // ---------------- 烂梗库：内置 ----------------

    /** 内置库非空，且每条都有正文。 */
    @Test
    fun `内置库条目都有正文`() {
        assertThat(MemeLibrary.BUILT_IN).isNotEmpty()
        MemeLibrary.BUILT_IN.forEach {
            assertThat(it.text).isNotEmpty()
            assertThat(it.category).isNotEmpty()
        }
    }

    /** 内置条目**不重复**（key 唯一，否则 LazyColumn 会崩）。 */
    @Test
    fun `内置条目 key 唯一`() {
        val keys = MemeLibrary.BUILT_IN.map { it.key }
        assertThat(keys).containsNoDuplicates()
    }

    /** 分类分组：未分类排最后。 */
    @Test
    fun `未分类排在最后`() {
        val memes = listOf(
            Meme("a", ""),
            Meme("b", "情绪"),
            Meme("c", "打招呼"),
        )
        val cats = MemeLibrary.categories(memes)
        assertThat(cats.last().name).isEqualTo(MemeLibrary.UNCATEGORIZED)
    }

    /** 分组不丢条目（每条恰好落在一个分类里）。 */
    @Test
    fun `分组不丢条目`() {
        val cats = MemeLibrary.categories()
        val total = cats.sumOf { it.count }
        assertThat(total).isEqualTo(MemeLibrary.BUILT_IN.size)
    }

    // ---------------- 烂梗库：搜索 ----------------

    /** 空查询返回全部（否则清空输入框列表会突然空）。 */
    @Test
    fun `空查询返回全部`() {
        assertThat(MemeSearch.search(MemeLibrary.BUILT_IN, "")).hasSize(MemeLibrary.BUILT_IN.size)
        assertThat(MemeSearch.search(MemeLibrary.BUILT_IN, "   "))
            .hasSize(MemeLibrary.BUILT_IN.size)
    }

    /** 搜正文。 */
    @Test
    fun `搜索正文`() {
        val r = MemeSearch.search(MemeLibrary.BUILT_IN, "哈哈")
        assertThat(r).isNotEmpty()
        assertThat(r.any { it.text.contains("哈哈") }).isTrue()
    }

    /**
     * ⚠️ 搜索**忽略大小写** —— 梗里有 `Orz`、`666` 这类，
     * 区分大小写会让"搜 orz 搜不到 Orz"。
     */
    @Test
    fun `搜索忽略大小写`() {
        val memes = listOf(Meme("Orz", "情绪"), Meme("abc", "情绪"))
        assertThat(MemeSearch.search(memes, "orz")).hasSize(1)
        assertThat(MemeSearch.search(memes, "ORZ")).hasSize(1)
        assertThat(MemeSearch.search(memes, "ABC")).hasSize(1)
    }

    /** 查询前后空格被忽略（手滑很常见）。 */
    @Test
    fun `搜索忽略首尾空格`() {
        val memes = listOf(Meme("哈哈", "情绪"))
        assertThat(MemeSearch.search(memes, "  哈哈  ")).hasSize(1)
    }

    /**
     * ⚠️ 搜索也覆盖**分类名与备注**。
     *
     * 只搜正文的话，用户按分类词搜（如"称赞"）会得到 0 结果 ——
     * 而那正是他找梗的方式。
     */
    @Test
    fun `搜索覆盖分类与备注`() {
        val memes = listOf(
            Meme("内容A", "称赞", ""),
            Meme("内容B", "情绪", "这是备注词"),
        )
        // 按分类名搜
        assertThat(MemeSearch.search(memes, "称赞").map { it.text }).contains("内容A")
        // 按备注搜
        assertThat(MemeSearch.search(memes, "备注词").map { it.text }).contains("内容B")
    }

    /** 搜不到返回空（不是全部）。 */
    @Test
    fun `搜不到返回空`() {
        assertThat(MemeSearch.search(MemeLibrary.BUILT_IN, "zzzz不可能存在的词"))
            .isEmpty()
    }

}
