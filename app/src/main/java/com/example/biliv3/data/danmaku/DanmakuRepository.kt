package com.example.biliv3.data.danmaku

import android.util.Log
import com.example.biliv3.data.subtitle.Proto
import com.example.biliv3.data.subtitle.pbInt
import com.example.biliv3.data.subtitle.pbMessage
import com.example.biliv3.data.subtitle.pbMessages
import com.example.biliv3.data.subtitle.pbString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 弹幕拉取。
 *
 * ## 接口
 *
 * `x/v2/dm/web/seg.so?type=1&oid={cid}&segment_index={n}`
 *
 * 返回 **protobuf**（`DmSegMobileReply`），**未压缩**（实测确认，
 * 不是网上常说的 deflate —— 直接按 protobuf 解析即可）。
 *
 * ## 已逆向确认的 schema（来自官方 APK 反编译）
 *
 * ```
 * DmSegMobileReply:
 *   1: elems (repeated DanmakuElem)
 *   2: state   3: ai_flag   4: segment_rules   5: colorful_src
 *
 * DanmakuElem:
 *   1: id        2: progress(毫秒)   3: mode       4: fontsize
 *   5: color(十进制 RGB)             6: mid_hash   7: content
 *   8: ctime     9: weight          12: id_str    13: attr
 *  15: like_count 22: animation     23: extra     24: colorful
 *  25: type     26: oid             27: dm_from
 * ```
 *
 * ## 分片
 *
 * 弹幕按**6 分钟一片**（`segment_index` 从 1 开始）。
 * 一部 30 分钟的视频有 5 片。播放器只需拉"当前进度所在片 + 预取下一片"，
 * 不必一次拉全 —— 全量拉取既慢又浪费流量。
 */
class DanmakuRepository(
    /**
     * HTTP 客户端。
     *
     * ## ⚠️ 必须传 AppContainer 的**共享** client（含 CookieJar）
     *
     * 首版这里默认 `defaultClient()`（自己 new 一个不带 CookieJar 的 client），
     * 导致**发弹幕必定失败**：`x/v2/dm/post` 返回
     * `{"code":-101,"message":"账号未登录"}`。
     *
     * 原因：发弹幕需要 cookie 里的 `SESSDATA` 标识身份，
     * 而 `defaultClient()` 没有 CookieJar → 请求不带任何 cookie
     * → 服务端视为未登录。
     *
     * 注意 csrf 是从 `AuthStore` 直接读的（不依赖 cookie），
     * 所以**本地校验会通过、请求也确实发出去了**，
     * 只有服务端返回 `-101` —— 表现为"点了发送没反应"，
     * 很难从代码上看出来（这也是它一直没被发现的原因）。
     *
     * 拉弹幕（`seg.so`）是公开数据，不需要 cookie，
     * 所以这个 bug **只影响发送**，读取一直正常。
     */
    private val client: OkHttpClient,
    /** 凭据存储：发弹幕需要 csrf。null = 只读模式（预览/测试）。 */
    private val store: com.example.biliv3.data.auth.AuthStore? = null,
) {

    /**
     * 发送弹幕。
     *
     * ## 接口
     *
     * `x/v2/dm/post`（POST form）。实测未登录返回 `-101`，说明
     * 接口本身可用，只差登录态。
     *
     * ## 必填参数（缺一即失败）
     *
     * | 参数 | 值 | 说明 |
     * |---|---|---|
     * | `type` | 1 | 1 = 视频 |
     * | `oid` | cid | **注意是 cid 不是 aid**（弹幕挂在分P 上） |
     * | `msg` | 内容 | |
     * | `bvid` | bvid | 新接口要求 |
     * | `progress` | 毫秒 | 弹幕出现时间 |
     * | `mode` | 1/4/5 | 滚动/底部/顶部 |
     * | `color` | 十进制 RGB | |
     * | `fontsize` | 25 | 官方默认 25 |
     * | `csrf` | bili_jct | |
     *
     * @param progressMs 弹幕出现时间（**毫秒**，与播放器进度同单位）
     */
    suspend fun send(
        bvid: String,
        cid: Long,
        message: String,
        progressMs: Long,
        mode: Int,
        color: Int,
    ): Result<Unit> {
        val csrf = store?.biliJct.orEmpty()
        if (csrf.isEmpty()) return Result.failure(IllegalStateException("请先登录"))
        if (message.isBlank()) return Result.failure(IllegalArgumentException("弹幕内容不能为空"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val form = okhttp3.FormBody.Builder()
                    .add("type", "1")
                    .add("oid", cid.toString())
                    .add("msg", message)
                    .add("bvid", bvid)
                    .add("progress", progressMs.toString())
                    .add("mode", mode.toString())
                    .add("color", color.toString())
                    .add("fontsize", "25")
                    .add("csrf", csrf)
                    .build()

                val request = Request.Builder()
                    .url("https://api.bilibili.com/x/v2/dm/post")
                    .post(form)
                    .apply {
                        com.example.biliv3.data.api.BiliHeaders.api()
                            .forEach { (k, v) -> header(k, v) }
                    }
                    .build()

                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val json = runCatching { org.json.JSONObject(body) }.getOrNull()
                    val code = json?.optInt("code", -1) ?: -1
                    if (code != 0) {
                        val msg = json?.optString("message").orEmpty()
                        throw IllegalStateException(
                            when {
                                msg.isNotEmpty() -> msg
                                resp.code == 403 -> "发送被拒绝（可能触发风控）"
                                else -> "发送失败（HTTP ${resp.code}）"
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * 拉一片弹幕。
     *
     * @param segmentIndex 分片序号，从 1 开始（每片 6 分钟）
     */
    suspend fun segment(cid: Long, segmentIndex: Int): List<DanmakuItem> = withContext(Dispatchers.IO) {
        if (cid <= 0 || segmentIndex < 1) return@withContext emptyList()

        val url = "$BASE_URL?type=1&oid=$cid&segment_index=$segmentIndex"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/")
            .build()

        val bytes = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "弹幕 HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                resp.body?.bytes() ?: return@withContext emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "弹幕请求失败: ${e.message}")
            return@withContext emptyList()
        }

        // 空响应 / 无弹幕
        if (bytes.isEmpty()) return@withContext emptyList()

        runCatching { parse(bytes) }
            .onFailure { Log.w(TAG, "弹幕解析失败: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * 解析 `DmSegMobileReply`。
     *
     * 注意是**未压缩** protobuf，不要试图 inflate
     * （网上很多实现说要 deflate，实测当前接口不是）。
     */
    private fun parse(bytes: ByteArray): List<DanmakuItem> {
        val outer = Proto.decode(bytes)
        val elems = outer.pbMessages(FIELD_ELEMS)
        if (elems.isEmpty()) return emptyList()

        val out = ArrayList<DanmakuItem>(elems.size)
        for (e in elems) {
            val content = e.pbString(ELEM_CONTENT)
            if (content.isEmpty()) continue
            out.add(
                DanmakuItem(
                    id = e.pbInt(ELEM_ID).toLong(),
                    idStr = e.pbString(ELEM_ID_STR),
                    // progress 单位是毫秒
                    progressMs = e.pbInt(ELEM_PROGRESS),
                    mode = e.pbInt(ELEM_MODE),
                    fontSize = e.pbInt(ELEM_FONTSIZE),
                    color = e.pbInt(ELEM_COLOR),
                    content = content,
                ),
            )
        }
        // 按时间升序 —— 渲染层按进度取用
        out.sortBy { it.progressMs }
        return out
    }

    companion object {
        private const val TAG = "BiliDanmaku"

        /** 弹幕分片接口。 */
        private const val BASE_URL = "https://api.bilibili.com/x/v2/dm/web/seg.so"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        // ---- DmSegMobileReply ----
        private const val FIELD_ELEMS = 1

        // ---- DanmakuElem（反编译确认）----
        private const val ELEM_ID = 1
        private const val ELEM_PROGRESS = 2
        private const val ELEM_MODE = 3
        private const val ELEM_FONTSIZE = 4
        private const val ELEM_COLOR = 5
        private const val ELEM_CONTENT = 7
        private const val ELEM_ID_STR = 12

        /** 一片弹幕覆盖的时长。 */
        const val SEGMENT_DURATION_MS = 6 * 60 * 1000L

        // ⚠️ 这里曾有 `fun defaultClient()` —— 已删除。
        //
        // 它会 new 一个**不带 CookieJar** 的 OkHttpClient，
        // 用作构造参数的默认值。后果是发弹幕必定返回 `-101 账号未登录`
        // （详见类顶部 client 参数的说明）。
        //
        // 删除而不是保留，是为了防止有人再把它当默认值用回去 ——
        // 这个 bug 的表现是"点了发送没反应"，极难定位。
        //
        // 若将来确实需要独立 client（如拉弹幕不需要 cookie 的场景），
        // 请显式传入，不要在这里提供无 CookieJar 的默认实现。
    }
}

/**
 * 一条弹幕。
 *
 * `mode` 是显示方式（官方枚举）：
 * - `1` / `2` / `3` —— 滚动
 * - `4` —— 底部固定
 * - `5` —— 顶部固定
 * - `6` —— 逆向滚动
 * - `7` —— 高级弹幕（定位/动画，需要脚本，**当前不渲染**）
 * - `8` —— 代码弹幕（同上）
 *
 * `color` 是**十进制 RGB**（如 `16777215` = `#FFFFFF` 白）。
 */
data class DanmakuItem(
    val id: Long,
    val idStr: String,
    /** 出现时间（毫秒）。 */
    val progressMs: Int,
    val mode: Int,
    val fontSize: Int,
    val color: Int,
    val content: String,
) {
    /** 是否滚动弹幕。 */
    val isScroll: Boolean get() = mode == MODE_SCROLL || mode == MODE_SCROLL2 || mode == MODE_SCROLL3

    /** 是否顶部固定。 */
    val isTop: Boolean get() = mode == MODE_TOP

    /** 是否底部固定。 */
    val isBottom: Boolean get() = mode == MODE_BOTTOM

    /**
     * 是否为高级/代码弹幕。
     *
     * 这类弹幕带定位脚本，需要解析 JSON 后自绘。**当前不支持，直接跳过** ——
     * 硬渲染会显示成乱码。
     */
    val isAdvanced: Boolean get() = mode == MODE_ADVANCED || mode == MODE_CODE

    /** 颜色转 ARGB（弹幕不带 alpha，固定不透明）。 */
    val argb: Int get() = (0xFF shl 24) or (color and 0xFFFFFF)

    companion object {
        const val MODE_SCROLL = 1
        const val MODE_SCROLL2 = 2
        const val MODE_SCROLL3 = 3
        const val MODE_BOTTOM = 4
        const val MODE_TOP = 5
        const val MODE_REVERSE = 6
        const val MODE_ADVANCED = 7
        const val MODE_CODE = 8
    }
}
