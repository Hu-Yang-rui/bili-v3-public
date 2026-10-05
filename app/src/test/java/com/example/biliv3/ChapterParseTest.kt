package com.example.biliv3

import com.example.biliv3.data.ChapterParser
import com.example.biliv3.data.VideoChapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节解析测试（空降助手，v1.5.3）。
 *
 * ## ⚠️ 这组测试的性质要如实说明
 *
 * 实测扫了排行榜 + 热门共 **60 个视频，`view_points` 全部为空数组** ——
 * 章节是 UP 主投稿时**手动添加**的，属少数视频。
 *
 * 所以下面的"非空"用例是**按公开结构构造的**，不是从真实响应抄的。
 * 它们钉住的是**容错行为**（畸形输入不能崩、不能产出假章节），
 * 而不是"真实数据长这样"。
 *
 * 真正来自实测的是「空数组 → 空列表」这一条 —— 那是常态。
 */
class ChapterParseTest {

    private fun data(json: String) = JSONObject(json)

    // ---- 实测确认的常态：空 ----

    @Test
    fun `view_points 为空数组返回空列表 —— 这是实测 60 个视频的常态`() {
        val d = data("""{"online_count": 1, "view_points": []}""")
        assertTrue(ChapterParser.parse(d).isEmpty())
    }

    @Test
    fun `没有 view_points 字段返回空列表`() {
        val d = data("""{"online_count": 5}""")
        assertTrue(ChapterParser.parse(d).isEmpty())
    }

    @Test
    fun `data 为 null 返回空列表 —— 不抛异常`() {
        assertTrue(ChapterParser.parse(null).isEmpty())
    }

    // ---- 正常解析（按公开结构构造）----

    @Test
    fun `解析一个正常章节`() {
        val d = data(
            """{"view_points":[{"type":1,"from":0,"to":120,"content":"开场"}]}""",
        )
        val list = ChapterParser.parse(d)
        assertEquals(1, list.size)
        val ch = list[0]
        assertEquals(0, ch.fromSeconds)
        assertEquals(120, ch.toSeconds)
        assertEquals("开场", ch.title)
        assertEquals(0, ch.index)
    }

    @Test
    fun `多个章节按起点排序 —— 接口顺序未必有序`() {
        val d = data(
            """{"view_points":[
                {"type":1,"from":300,"to":400,"content":"第三段"},
                {"type":1,"from":0,"to":100,"content":"第一段"},
                {"type":1,"from":100,"to":300,"content":"第二段"}
            ]}""",
        )
        val list = ChapterParser.parse(d)
        assertEquals(3, list.size)
        assertEquals(listOf("第一段", "第二段", "第三段"), list.map { it.title })
        // index 按排序后的顺序重新编号
        assertEquals(listOf(0, 1, 2), list.map { it.index })
    }

    // ---- 容错：畸形输入必须被跳过，不能产出假章节 ----

    @Test
    fun `to 不大于 from 的条目被跳过`() {
        val d = data(
            """{"view_points":[
                {"type":1,"from":100,"to":100,"content":"零长"},
                {"type":1,"from":200,"to":100,"content":"倒挂"},
                {"type":1,"from":0,"to":50,"content":"正常"}
            ]}""",
        )
        val list = ChapterParser.parse(d)
        assertEquals("只应保留正常那条", 1, list.size)
        assertEquals("正常", list[0].title)
    }

    @Test
    fun `标题为空的条目被跳过`() {
        val d = data(
            """{"view_points":[
                {"type":1,"from":0,"to":50,"content":""},
                {"type":1,"from":0,"to":50,"content":"   "},
                {"type":1,"from":60,"to":90,"content":"有效"}
            ]}""",
        )
        val list = ChapterParser.parse(d)
        assertEquals(1, list.size)
        assertEquals("有效", list[0].title)
    }

    @Test
    fun `from 为负的条目被跳过`() {
        val d = data(
            """{"view_points":[{"type":1,"from":-5,"to":50,"content":"负起点"}]}""",
        )
        assertTrue(ChapterParser.parse(d).isEmpty())
    }

    @Test
    fun `缺字段的条目被跳过 —— 不抛异常`() {
        val d = data(
            """{"view_points":[
                {"content":"只有标题"},
                {"from":0,"to":10},
                {"from":20,"to":30,"content":"完整"}
            ]}""",
        )
        val list = ChapterParser.parse(d)
        assertEquals(1, list.size)
        assertEquals("完整", list[0].title)
    }

    @Test
    fun `数组里混入非对象元素时跳过该元素`() {
        val d = data(
            """{"view_points":[null, 123, {"from":0,"to":10,"content":"有效"}]}""",
        )
        val list = ChapterParser.parse(d)
        assertEquals(1, list.size)
        assertEquals("有效", list[0].title)
    }

    // ---- VideoChapter 的行为 ----

    @Test
    fun `时间标签格式为 mm ss`() {
        val ch = VideoChapter(0, 83, 200, "测试")
        assertEquals("01:23", ch.timeLabel)
    }

    @Test
    fun `时间标签对超过一小时仍按分钟显示`() {
        // 3661 秒 = 61 分 01 秒
        val ch = VideoChapter(0, 3661, 4000, "长视频")
        assertEquals("61:01", ch.timeLabel)
    }

    @Test
    fun `时长计算正确且不为负`() {
        assertEquals(120, VideoChapter(0, 0, 120, "a").durationSeconds)
        assertEquals(0, VideoChapter(0, 100, 50, "b").durationSeconds)
    }

    @Test
    fun `contains 左闭右开 —— 边界不重叠`() {
        val a = VideoChapter(0, 0, 100, "a")
        val b = VideoChapter(1, 100, 200, "b")
        // 0 属于 a；100 属于 b 而不属于 a
        assertTrue(a.contains(0))
        assertTrue(a.contains(99))
        assertFalse(a.contains(100))
        assertTrue(b.contains(100))
        assertFalse(b.contains(200))
    }

    @Test
    fun `chapterAt 语义在 ViewModel 侧靠 firstOrNull —— 章节不重叠时唯一命中`() {
        val d = data(
            """{"view_points":[
                {"type":1,"from":0,"to":100,"content":"A"},
                {"type":1,"from":100,"to":200,"content":"B"}
            ]}""",
        )
        val list = ChapterParser.parse(d)
        assertNull(list.firstOrNull { it.contains(-1) })
        assertEquals("A", list.firstOrNull { it.contains(50) }?.title)
        assertEquals("B", list.firstOrNull { it.contains(150) }?.title)
        assertNull(list.firstOrNull { it.contains(200) })
    }
}
