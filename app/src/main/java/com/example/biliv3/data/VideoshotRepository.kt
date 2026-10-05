package com.example.biliv3.data

import com.example.biliv3.data.api.BiliApi
import com.example.biliv3.data.model.CoverUrls
import org.json.JSONObject

/**
 * 进度条拖动时的**画面预览**（videoshot）。
 *
 * ## 实测确认的接口（v1.5.3）
 *
 * ```
 * GET x/player/videoshot?bvid=<bvid>&cid=<cid>&index=1
 * ```
 *
 * 真实返回（案例视频 BV1kyYP6eE8E / cid=42357555942）：
 * ```json
 * {
 *   "image": ["//bimp.hdslb.com/videoshotpvhdboss/42357555942_91qpzb-0001.jpg"],
 *   "index": [0, 0, 5, 10, 15, 25, ...],       // 68 个，单位：秒
 *   "img_x_len": 10, "img_y_len": 10,          // 10x10 网格
 *   "img_x_size": 480, "img_y_size": 270       // 单帧 480x270
 * }
 * ```
 *
 * ## 关键事实（决定了怎么实现）
 *
 * 1. **`image` 是一张「精灵图」**，不是逐帧图片 ——
 *    68 帧平铺在 10×10 网格里（`img_x_len` × `img_y_len`）。
 *    要取第 i 帧，必须按网格坐标裁剪，**不能直接当单张图用**。
 * 2. **`index[i]` 是"第 i 帧对应的秒数"**，不是均匀间隔 ——
 *    实测 `[0, 0, 5, 10, 15, 25, 30, 35, ...]` 开头有重复与跳变。
 *    所以要按**时间就近查找**，不能假设 `frameIndex = t / step`。
 * 3. 案例视频 343 秒 / 68 帧 ≈ 每帧 5 秒 —— 拖动时精度约 5 秒，
 *    这是接口给的粒度上限，不是实现缺陷。
 *
 * ## ⚠️ 不是所有视频都有
 *
 * 未生成预览的视频返回 `image: []` 或 code != 0 ——
 * 此时 [Videoshot] 为 null，UI 必须**优雅降级**（只显示时间文字），
 * 不能伪造画面。
 */
class VideoshotRepository(
    private val api: BiliApi,
) {

    /**
     * 拉一个视频的预览精灵图。
     *
     * @return null = 该视频**没有**预览资源（UI 降级为只显示时间）。
     *         注意与"请求失败"同形 —— 这是**有意**的：
     *         进度条预览是增强功能，失败时静默降级即可，
     *         弹错误反而打断拖动。
     */
    suspend fun fetch(bvid: String, cid: Long): Videoshot? {
        if (bvid.isEmpty() || cid <= 0L) return null

        val json = runCatching {
            api.getRaw(
                path = "x/player/videoshot",
                query = mapOf(
                    "bvid" to bvid,
                    "cid" to cid.toString(),
                    // ⚠️ index 是**必填**：不传时 index 数组为空
                    //（实测 index 省略 → "index":[]，长度 0）
                    "index" to "1",
                ),
                signed = false,
            )
        }.getOrNull() ?: return null

        if (json.optInt("code", -1) != 0) return null
        return parse(json.optJSONObject("data") ?: return null)
    }

    companion object {

        /**
         * 解析 videoshot 响应。
         *
         * ⚠️ 放在 `companion` 而不是实例方法 —— 它是**纯函数**（无 IO、
         * 不碰 `api`），这样 JVM 单测可以直接调，**不需要构造 `BiliApi`**
         * （而 `BiliApi` 构造需要 `Context`，单测拿不到）。
         *
         * 这是本项目反复出现的模式：**把能测的部分从"需要 Context 的类"里
         * 拿出来**（同 §7.17-96 的 `HistoryParser`、§7.16 的 `CookieCodec`）。
         */
        internal fun parse(d: JSONObject): Videoshot? {
            val images = d.optJSONArray("image") ?: return null
            if (images.length() == 0) return null
            val sheetUrl = CoverUrls.normalize(images.optString(0))
            if (sheetUrl.isEmpty()) return null

            val indexArr = d.optJSONArray("index") ?: return null
            val seconds = ArrayList<Int>(indexArr.length())
            for (i in 0 until indexArr.length()) {
                seconds.add(indexArr.optInt(i, 0))
            }
            if (seconds.isEmpty()) return null

            val cols = d.optInt("img_x_len", 0)
            val rows = d.optInt("img_y_len", 0)
            val frameW = d.optInt("img_x_size", 0)
            val frameH = d.optInt("img_y_size", 0)
            // 网格与单帧尺寸都必须是正数，否则裁不出来
            if (cols <= 0 || rows <= 0 || frameW <= 0 || frameH <= 0) return null

            return Videoshot(
                sheetUrl = sheetUrl,
                seconds = seconds,
                cols = cols,
                rows = rows,
                frameWidth = frameW,
                frameHeight = frameH,
            )
        }
    }
}

/**
 * 一个视频的预览精灵图。
 *
 * @param sheetUrl 精灵图地址（10×10 平铺）
 * @param seconds 每帧对应的秒数（与精灵图顺序一致）
 * @param cols 横向帧数
 * @param rows 纵向帧数
 * @param frameWidth 单帧宽（像素）
 * @param frameHeight 单帧高（像素）
 */
data class Videoshot(
    val sheetUrl: String,
    val seconds: List<Int>,
    val cols: Int,
    val rows: Int,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    /** 总帧数（受网格容量与 seconds 长度双重限制）。 */
    val frameCount: Int get() = minOf(seconds.size, cols * rows)

    /**
     * 找到**最接近** [positionSeconds] 的帧下标。
     *
     * ## 为什么不是 `t / step`
     *
     * 实测 `index = [0, 0, 5, 10, 15, 25, ...]` ——
     * 开头两帧都是 0，之后间隔也不完全均匀。
     * 用除法会在这些地方错位，必须按数组**就近查找**。
     *
     * 用线性扫描而不是二分：帧数最多几十个（实测 68），
     * 线性足够快，且不必假设 `seconds` 单调（实测开头有重复值，
     * 二分在有重复时行为不确定）。
     *
     * @return 帧下标；没有可用帧时返回 -1
     */
    fun frameIndexAt(positionSeconds: Int): Int {
        if (frameCount <= 0) return -1
        val t = positionSeconds.coerceAtLeast(0)

        var best = 0
        var bestDiff = Int.MAX_VALUE
        for (i in 0 until frameCount) {
            val diff = kotlin.math.abs(seconds[i] - t)
            if (diff < bestDiff) {
                bestDiff = diff
                best = i
            }
        }
        return best
    }

    /** 第 [index] 帧在精灵图里的左上角坐标（像素）。 */
    fun frameOrigin(index: Int): Pair<Int, Int> {
        val i = index.coerceIn(0, (frameCount - 1).coerceAtLeast(0))
        val col = i % cols
        val row = i / cols
        return (col * frameWidth) to (row * frameHeight)
    }
}
