package com.example.biliv3.util

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * 从**精灵图**里裁出一帧（v1.5.3）。
 *
 * ## 为什么需要它
 *
 * 进度条预览（`x/player/videoshot`）返回的 `image` 是一张
 * **10×10 平铺的精灵图**，不是逐帧图片：
 *
 * ```
 * 68 帧全部平铺在 4800×2700 的大图里，每帧 480×270
 * 第 i 帧的左上角 = (i % cols * 480, i / cols * 270)
 * ```
 *
 * 直接把 `sheetUrl` 交给 `AsyncImage` 会显示**整张大图**
 * （几十帧叠在一起），完全没法看。必须先裁。
 *
 * ## 为什么用 Coil 的 Transformation 而不是 `BitmapPainter`
 *
 * `BitmapPainter` 要自己持有 Bitmap 并管理回收，且**绕开了 Coil 的缓存** ——
 * 每次拖动都要重新解码整张精灵图（实测这张图约 1.5 MB），拖动会明显卡。
 *
 * 走 `Transformation` 则完全复用 Coil 的解码与缓存：
 * **同一张精灵图只解码一次**，之后每次裁帧都是在内存位图上开窗，
 * 没有额外 IO。这正是"快速连续拖动不卡顿"的关键。
 *
 * ## ⚠️ 尺寸夹取
 *
 * 越界坐标（帧下标算错、图比预期小）时**夹到合法范围**而不是崩 ——
 * 裁出来可能是边缘一帧，但不会抛 `IllegalArgumentException` 打断拖动。
 */
class CropToFrame(
    private val x: Int,
    private val y: Int,
    private val w: Int,
    private val h: Int,
) : Transformation {

    override val cacheKey: String = "crop:$x:$y:$w:$h"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // 越界保护：源图可能比预期小（接口换图 / 尺寸字段不准）
        val left = x.coerceIn(0, (input.width - 1).coerceAtLeast(0))
        val top = y.coerceIn(0, (input.height - 1).coerceAtLeast(0))
        val width = w.coerceIn(1, input.width - left)
        val height = h.coerceIn(1, input.height - top)

        return Bitmap.createBitmap(input, left, top, width, height)
    }
}
