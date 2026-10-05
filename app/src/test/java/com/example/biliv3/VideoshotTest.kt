package com.example.biliv3

import com.example.biliv3.data.Videoshot
import com.example.biliv3.data.VideoshotRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度条预览（videoshot）解析测试（v1.5.3）。
 *
 * ## 为什么必须有这组测试
 *
 * 预览有两个**极易写错**的地方，且都是真实接口的形态决定的：
 *
 * 1. **`image` 是精灵图（10×10 平铺），不是逐帧图片** ——
 *    当成单张图会得到"68 帧叠在一张里"的乱图。
 * 2. **`index` 不是均匀间隔** —— 实测 `[0, 0, 5, 10, 15, 25, ...]`
 *    开头有重复、之后有跳变。用 `frameIndex = t / step` 会错位。
 *
 * 下面用**真实接口返回的结构**（案例视频 BV1kyYP6eE8E / cid=42357555942）
 * 钉死这两点，只把 URL 换成假值。
 */
class VideoshotTest {

    /** 真实响应结构（值取自实测，URL 换成假域名）。 */
    private fun realJson(): JSONObject = JSONObject(
        """
        {
          "code": 0,
          "data": {
            "image": ["//bimp.example.com/videoshot/123_abc-0001.jpg"],
            "index": [0, 0, 5, 10, 15, 25, 30, 35],
            "img_x_len": 10,
            "img_y_len": 10,
            "img_x_size": 480,
            "img_y_size": 270
          }
        }
        """.trimIndent(),
    )

    // ---- 解析 ----

    @Test
    fun `解析真实响应结构`() {
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))
        assertNotNull(shot)
        shot!!
        assertEquals(10, shot.cols)
        assertEquals(10, shot.rows)
        assertEquals(480, shot.frameWidth)
        assertEquals(270, shot.frameHeight)
        assertEquals(8, shot.seconds.size)
        // 协议相对地址必须补全成 https
        assertTrue("必须是 https 开头: ${shot.sheetUrl}", shot.sheetUrl.startsWith("https://"))
    }

    @Test
    fun `image 为空数组时返回 null —— 该视频没有预览`() {
        val d = JSONObject("""{"image":[],"index":[],"img_x_len":10,"img_y_len":10,"img_x_size":480,"img_y_size":270}""")
        assertNull(VideoshotRepository.parse(d))
    }

    @Test
    fun `网格尺寸为 0 时返回 null —— 裁不出帧`() {
        val d = JSONObject("""{"image":["https://x/a.jpg"],"index":[0,5],"img_x_len":0,"img_y_len":0,"img_x_size":480,"img_y_size":270}""")
        assertNull(VideoshotRepository.parse(d))
    }

    @Test
    fun `index 为空时返回 null`() {
        val d = JSONObject("""{"image":["https://x/a.jpg"],"index":[],"img_x_len":10,"img_y_len":10,"img_x_size":480,"img_y_size":270}""")
        assertNull(VideoshotRepository.parse(d))
    }

    @Test
    fun `缺 image 字段时返回 null`() {
        val d = JSONObject("""{"index":[0,5],"img_x_len":10,"img_y_len":10,"img_x_size":480,"img_y_size":270}""")
        assertNull(VideoshotRepository.parse(d))
    }

    // ---- 帧数上限 ----

    @Test
    fun `帧数取 index 长度与网格容量的较小值`() {
        // 网格 10x10 = 100 容量，index 只有 8 → 取 8
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))!!
        assertEquals(8, shot.frameCount)

        // index 比网格还多 → 取网格容量
        val d = JSONObject(
            """{"image":["https://x/a.jpg"],"index":[0,1,2,3,4,5,6,7,8,9,10,11],
                "img_x_len":2,"img_y_len":2,"img_x_size":10,"img_y_size":10}""",
        )
        val s2 = VideoshotRepository.parse(d)!!
        assertEquals("网格 2x2=4，index 12 → 取 4", 4, s2.frameCount)
    }

    // ---- 时间 → 帧下标（核心：必须就近查找）----

    @Test
    fun `时间 0 秒命中第一帧`() {
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))!!
        assertEquals(0, shot.frameIndexAt(0))
    }

    @Test
    fun `时间 6 秒应命中 5 秒那帧（第 2 帧），不是 10 秒那帧`() {
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))!!
        // index = [0, 0, 5, 10, 15, 25, 30, 35]
        // 6 秒离 5 秒（差 1）比离 10 秒（差 4）更近
        assertEquals(2, shot.frameIndexAt(6))
    }

    @Test
    fun `时间 8 秒应命中 10 秒那帧`() {
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))!!
        // 8 离 10（差 2）比离 5（差 3）更近
        assertEquals(3, shot.frameIndexAt(8))
    }

    /**
     * 🔴 回归测试：`index` **不是均匀间隔**。
     *
     * 若用 `t / step` 的写法，20 秒会算成某个固定下标；
     * 而实测 `index` 里 20 秒附近只有 15 和 25，应命中 15（差 5 < 差 5? 相等取先者）
     * —— 关键是与"均匀间隔假设"算出来的结果**不同**。
     */
    @Test
    fun `不均匀 index 下必须就近查找而不是除法`() {
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))!!
        // 20 秒：离 15 差 5，离 25 差 5 → 相等，取下标更小的（15，下标 4）
        assertEquals(4, shot.frameIndexAt(20))
        // 若按"每帧 5 秒"的均匀假设：20/5 = 4 → 恰好也是 4。
        // 换一个点区分：22 秒 → 均匀假设 22/5=4（第 4 帧=15 秒，差 7）；
        // 就近查找：离 25（下标 5）差 3 → 应得 5。
        assertEquals("22 秒应命中 25 秒那帧（下标 5）", 5, shot.frameIndexAt(22))
    }

    @Test
    fun `超出末尾时间命中最后一帧`() {
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))!!
        assertEquals(7, shot.frameIndexAt(9999))
    }

    @Test
    fun `负时间按 0 处理`() {
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))!!
        assertEquals(0, shot.frameIndexAt(-100))
    }

    @Test
    fun `没有帧时 frameIndexAt 返回 -1`() {
        val empty = Videoshot(
            sheetUrl = "https://x/a.jpg",
            seconds = emptyList(),
            cols = 10, rows = 10, frameWidth = 480, frameHeight = 270,
        )
        assertEquals(-1, empty.frameIndexAt(10))
    }

    // ---- 精灵图坐标（核心：必须按网格裁）----

    @Test
    fun `第 0 帧在左上角`() {
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))!!
        assertEquals(0 to 0, shot.frameOrigin(0))
    }

    @Test
    fun `第 1 帧在第二列 —— 横向偏移一个帧宽`() {
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))!!
        assertEquals(480 to 0, shot.frameOrigin(1))
    }

    @Test
    fun `第 10 帧换行到第二行第一列`() {
        // ⚠️ 真实响应只有 8 帧，用不足 10 帧的数据测"换行"会走夹取路径
        // （夹到第 7 帧），测不到换行本身。所以这里用**够 10 帧的数据**。
        val d = JSONObject(
            """{"image":["https://x/a.jpg"],
                "index":[0,1,2,3,4,5,6,7,8,9,10,11],
                "img_x_len":10,"img_y_len":10,"img_x_size":480,"img_y_size":270}""",
        )
        val shot = VideoshotRepository.parse(d)!!
        assertEquals(12, shot.frameCount)
        // cols=10 → 下标 10 是第 1 行（从 0 数）第 0 列
        assertEquals(0 to 270, shot.frameOrigin(10))
    }

    @Test
    fun `越界下标被夹到合法范围`() {
        val shot = VideoshotRepository.parse(realJson().getJSONObject("data"))!!
        // 只有 8 帧，要第 99 帧 → 夹到第 7 帧
        assertEquals(shot.frameOrigin(7), shot.frameOrigin(99))
        assertEquals(shot.frameOrigin(0), shot.frameOrigin(-5))
    }
}
