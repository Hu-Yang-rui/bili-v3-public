package com.example.biliv3.data.quality

import com.example.biliv3.data.model.QualityAvailability
import com.example.biliv3.data.model.QualityOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * 清晰度档位解析（**未发版**，纯函数，可单测）。
 *
 * ---
 *
 * # 三份数据交叉（见 `QualityOption` 的说明）
 *
 * ```
 * accept_quality / accept_description   → 理论上有哪些档 + 名称
 * support_formats                       → 每档的权限原因（**唯一真实信号**）
 * dash.video                            → 实际能播的档 + 真实分辨率/帧率
 * ```
 *
 * # 🔴 `limit_watch_reason` 是实测确认的会员限制标记
 *
 * 真实登录账号（非大会员）实测：
 *
 * | qn | 名称 | limit_watch_reason | dash.video 里有吗 |
 * |---|---|---|---|
 * | 120 | 4K 超高清 | **1** | ❌ |
 * | 112 | 1080P 高码率 | **1** | ❌ |
 * | 80 | 1080P 高清 | 0 | ✅ |
 * | 64/32/16 | 720P/480P/360P | 0 | ✅ |
 *
 * 即：**`limit=1` 的档位确实拿不到流** —— 两者一致，标记可信。
 *
 * # ⚠️ `can_watch_qn_reason` 实测全为 0
 *
 * 它在当前场景下不携带信息。**仍然解析它**（可能有其它限制类型），
 * 但**不单独依赖它**判断会员 —— 那会得出"没有任何限制"的错误结论。
 */
object QualityParser {

    /**
     * 解析档位列表。
     *
     * @param data `playurl` 响应的 `data` 对象；null 视为请求失败
     * @param loggedIn 当前是否登录（决定 [QualityAvailability.NotLoggedIn]）
     * @param failure 请求失败时的原因（非 null 时优先返回 [QualityAvailability.Failed]）
     */
    fun parse(
        data: JSONObject?,
        loggedIn: Boolean = true,
        failure: String? = null,
    ): QualityAvailability {
        // ---- 失败优先：不能把"请求失败"说成"没有档位" ----
        if (failure != null) return QualityAvailability.Failed(failure)
        if (data == null) {
            return QualityAvailability.Failed("响应缺少 data")
        }

        // ---- 实际能播的档（dash.video）----
        val playable = playableQualities(data)

        // ---- 每档的权限原因（support_formats）----
        val limits = limitReasons(data)

        // ---- 角标（同样来自 support_formats）----
        val sups = superscripts(data)

        // ---- 理论档位 + 名称（accept_quality / accept_description）----
        val ids = data.optJSONArray("accept_quality").toIntList()
        val descs = data.optJSONArray("accept_description").toStringList()
        val names = formatNames(data)

        // 三份数据都空 → 这个视频确实没有档位
        if (ids.isEmpty() && playable.isEmpty()) {
            // 未登录且什么都没有 → 更可能是"登录后才可见"
            return if (!loggedIn) {
                QualityAvailability.NotLoggedIn
            } else {
                QualityAvailability.Unsupported
            }
        }

        // ---- 合并 ----
        //
        // 以 accept_quality 为主（保持服务端给的顺序：高→低），
        // 并补上 dash 里有但 accept 里没有的档（实测可能出现）。
        val allIds = LinkedHashSet<Int>()
        allIds.addAll(ids)
        allIds.addAll(playable.keys)
        // 兜底：连 accept 都没有时，用 support_formats 的档
        if (allIds.isEmpty()) allIds.addAll(limits.keys)

        val options = allIds.map { qn ->
            val p = playable[qn]
            val idx = ids.indexOf(qn)
            val label = names[qn]
                ?: descs.getOrNull(idx)
                ?: "清晰度 $qn"
            QualityOption(
                id = qn,
                label = label,
                superscript = sups[qn].orEmpty(),
                // 受限 = 有权限原因（且实际拿不到流 —— 两者实测一致）
                limited = (limits[qn] ?: 0) != 0 && p == null,
                playable = p != null,
                width = p?.width ?: 0,
                height = p?.height ?: 0,
                frameRate = p?.frameRate ?: 0f,
            )
        }

        // 只保留「能播」或「受限」的档 ——
        // 既不能播又没限制原因的档位是噪音（实测 112 在 fnval=16 时
        // 既不在 dash 里、limit 又是 1，属于"受限"；而完全无信息的档
        // 显示出来只会让用户点了没反应）。
        val useful = options.filter { it.playable || it.limited }

        return if (useful.isEmpty()) {
            QualityAvailability.Unsupported
        } else {
            QualityAvailability.Ok(useful)
        }
    }

    // ---------------------------------------------------------------------
    // 三份数据的提取
    // ---------------------------------------------------------------------

    /**
     * 实际能播的档：`qn → 该档的真实分辨率与帧率`。
     *
     * ⚠️ **同一 qn 可能有多条**（不同编码 avc/hevc/av01）。
     * 取**分辨率最高**的那条代表该档，这样 4K 档不会被同 id 的低分辨率覆盖。
     */
    private fun playableQualities(data: JSONObject): Map<Int, PlayableInfo> {
        val out = HashMap<Int, PlayableInfo>()
        val arr = data.optJSONObject("dash")?.optJSONArray("video") ?: return emptyMap()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val id = v.optInt("id", 0)
            if (id <= 0) continue
            val w = v.optInt("width", 0)
            val h = v.optInt("height", 0)
            // 帧率字段有两种写法（实测都有）
            val fr = when {
                v.has("frameRate") -> v.optDouble("frameRate", 0.0).toFloat()
                v.has("frame_rate") -> v.optDouble("frame_rate", 0.0).toFloat()
                else -> 0f
            }
            val cur = out[id]
            // 保留像素更多的
            if (cur == null || w * h > cur.width * cur.height) {
                out[id] = PlayableInfo(width = w, height = h, frameRate = fr)
            }
        }
        return out
    }

    /** 某个档位实际拿到的流的属性。 */
    private data class PlayableInfo(
        val width: Int,
        val height: Int,
        val frameRate: Float,
    )

    /**
     * 每档的权限原因：`qn → limit_watch_reason`。
     *
     * 这是**唯一的真实会员限制信号**（实测确认，见类文档）。
     */
    private fun limitReasons(data: JSONObject): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        val arr = data.optJSONArray("support_formats") ?: return emptyMap()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val qn = f.optInt("quality", 0)
            if (qn <= 0) continue
            // 两个字段任一非 0 都算受限（不单独依赖某一个 —— 见类文档）
            val limit = f.optInt("limit_watch_reason", 0)
            val canWatch = f.optInt("can_watch_qn_reason", 0)
            out[qn] = if (limit != 0) limit else canWatch
        }
        return out
    }

    /** 档位名（`support_formats[].new_description`，比 accept_description 更准）。 */
    private fun formatNames(data: JSONObject): Map<Int, String> {
        val out = HashMap<Int, String>()
        val arr = data.optJSONArray("support_formats") ?: return emptyMap()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val qn = f.optInt("quality", 0)
            val name = f.optString("new_description").ifEmpty {
                f.optString("display_desc")
            }
            if (qn > 0 && name.isNotEmpty()) out[qn] = name
        }
        return out
    }

    /** 角标（如"高码率"）。 */
    private fun superscripts(data: JSONObject): Map<Int, String> {
        val out = HashMap<Int, String>()
        val arr = data.optJSONArray("support_formats") ?: return emptyMap()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val qn = f.optInt("quality", 0)
            val sup = f.optString("superscript")
            if (qn > 0 && sup.isNotEmpty()) out[qn] = sup
        }
        return out
    }

    // ---------------------------------------------------------------------
    // 容错读数组（`MiniJson` 不处理扁平标量数组，这里自己读）
    // ---------------------------------------------------------------------

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optInt(i, 0).takeIf { it > 0 } }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { i -> optString(i) }
    }
}
