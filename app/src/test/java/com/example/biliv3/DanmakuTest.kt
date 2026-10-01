package com.example.biliv3

import com.example.biliv3.data.danmaku.DanmakuItem
import com.example.biliv3.ui.video.formatTime
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 弹幕模型测试。
 *
 * ## 为什么这组测试重要
 *
 * 弹幕的 `mode` 决定渲染方式，写错会表现为：
 * - 顶部弹幕被当成滚动弹幕 → 位置完全错乱
 * - 高级弹幕（带定位脚本）被当普通弹幕 → 显示成乱码
 *
 * 这些都不报错，只是"看起来不对"，所以用测试锁死。
 *
 * `mode` 取值来自官方 APK 反编译的 `DanmakuElem` / 社区共识：
 * 1/2/3=滚动、4=底部、5=顶部、6=逆向、7=高级、8=代码
 */
class DanmakuTest {

    private fun dm(mode: Int, color: Int = 16777215) = DanmakuItem(
        id = 1L,
        idStr = "1",
        progressMs = 1000,
        mode = mode,
        fontSize = 25,
        color = color,
        content = "测试弹幕",
    )

    // ---------------- mode 分类 ----------------

    @Test
    fun `mode 1 2 3 都是滚动弹幕`() {
        assertThat(dm(1).isScroll).isTrue()
        assertThat(dm(2).isScroll).isTrue()
        assertThat(dm(3).isScroll).isTrue()
    }

    @Test
    fun `mode 4 是底部固定`() {
        assertThat(dm(4).isBottom).isTrue()
        assertThat(dm(4).isScroll).isFalse()
        assertThat(dm(4).isTop).isFalse()
    }

    @Test
    fun `mode 5 是顶部固定`() {
        assertThat(dm(5).isTop).isTrue()
        assertThat(dm(5).isScroll).isFalse()
        assertThat(dm(5).isBottom).isFalse()
    }

    @Test
    fun `mode 7 8 是高级弹幕(需跳过)`() {
        assertThat(dm(7).isAdvanced).isTrue()
        assertThat(dm(8).isAdvanced).isTrue()
    }

    @Test
    fun `普通弹幕不是高级弹幕`() {
        assertThat(dm(1).isAdvanced).isFalse()
        assertThat(dm(4).isAdvanced).isFalse()
        assertThat(dm(5).isAdvanced).isFalse()
    }

    // ---------------- 颜色 ----------------

    @Test
    fun `白色弹幕 16777215 转 ARGB`() {
        // 16777215 = 0xFFFFFF
        assertThat(dm(1, 16777215).argb).isEqualTo(0xFFFFFFFF.toInt())
    }

    @Test
    fun `红色弹幕 16711680 转 ARGB`() {
        // 16711680 = 0xFF0000
        assertThat(dm(1, 16711680).argb).isEqualTo(0xFFFF0000.toInt())
    }

    @Test
    fun `彩色弹幕值正确还原`() {
        // 实测数据里出现的值：15138834 = 0xE70012
        val argb = dm(1, 15138834).argb
        assertThat(argb ushr 24 and 0xFF).isEqualTo(0xFF) // alpha 恒不透明
        assertThat(argb and 0xFFFFFF).isEqualTo(15138834)
    }

    @Test
    fun `零颜色是黑色而不是透明`() {
        // color=0 表示黑色弹幕；若直接把 0 当颜色会变成全透明看不见
        assertThat(dm(1, 0).argb).isEqualTo(0xFF000000.toInt())
    }

    // ---------------- 常量对齐 ----------------

    @Test
    fun `mode 常量值与官方一致`() {
        assertThat(DanmakuItem.MODE_SCROLL).isEqualTo(1)
        assertThat(DanmakuItem.MODE_BOTTOM).isEqualTo(4)
        assertThat(DanmakuItem.MODE_TOP).isEqualTo(5)
        assertThat(DanmakuItem.MODE_ADVANCED).isEqualTo(7)
        assertThat(DanmakuItem.MODE_CODE).isEqualTo(8)
    }

    // ---------------- 播放时间格式化（返回栈/字幕共用） ----------------

    /**
     * `formatTime` 的入参是**毫秒**。
     *
     * ## 为什么锁这条
     *
     * 第一版把毫秒当秒用，315 秒的视频显示成 `315:09:32` ——
     * 它"看起来像个时间"，所以肉眼很难第一眼发现。用测试钉死。
     */
    @Test
    fun `formatTime 按毫秒解释`() {
        assertThat(formatTime(0L)).isEqualTo("00:00")
        assertThat(formatTime(1000L)).isEqualTo("00:01")
        assertThat(formatTime(65_000L)).isEqualTo("01:05")
        // 315 秒 = 5 分 15 秒
        assertThat(formatTime(315_000L)).isEqualTo("05:15")
    }

    @Test
    fun `formatTime 超过一小时带小时段`() {
        assertThat(formatTime(3_661_000L)).isEqualTo("1:01:01")
    }

    @Test
    fun `formatTime 负数与零统一为 00 00`() {
        // 播放器未就绪时 duration 可能是 0 或负值，不能显示 "-1:59"
        assertThat(formatTime(-1L)).isEqualTo("00:00")
        assertThat(formatTime(0L)).isEqualTo("00:00")
    }
}
