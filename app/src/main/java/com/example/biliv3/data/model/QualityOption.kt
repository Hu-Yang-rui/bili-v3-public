package com.example.biliv3.data.model

/**
 * 清晰度档位（未发版）。
 *
 * ---
 *
 * # 🔴 为什么不能用 `accept_quality` 单独建列表
 *
 * 旧实现直接渲染 `accept_quality` / `accept_description` 两个平行数组。
 * 实测（真实登录账号，非大会员）发现**两者会撒谎**：
 *
 * ```
 * accept_quality     = [112, 80, 64, 32, 16]     ← 列了 112
 * dash.video ids     = [ 80, 64, 32, 16]         ← 但不给 112 的流
 * support_formats:
 *   qn=112  limit_watch_reason=1   ← 会员限制
 *   qn=80   limit_watch_reason=0
 * ```
 *
 * 也就是说：**`accept_quality` 里有、但 `dash.video` 里没有**的档位，
 * 用户选中后实际拿到的是另一个档位 —— UI 与实际不符。
 *
 * 所以档位必须由**三份数据交叉**得出：
 *
 * | 来源 | 提供什么 |
 * |---|---|
 * | `accept_quality` | 这个视频**理论上**有哪些档 |
 * | `support_formats` | 每档的**名称 + 权限原因**（唯一真实的限制信号）|
 * | `dash.video` | **实际能播**的档 |
 *
 * # 三种状态，UI 必须区分（需求第四条）
 *
 * | 状态 | 判据 | UI |
 * |---|---|---|
 * | 可用 | 在 `dash.video` 里 | 正常选项 |
 * | **受限** | `limit_watch_reason != 0` | 🔒 标记 + 可点（弹提示）|
 * | 不可用 | 不在 `dash.video` 且无限制原因 | 不显示 |
 *
 * @param id 清晰度 id（qn，如 120=4K / 112=1080P+ / 80=1080P）
 * @param label 显示名（优先 `new_description`，回退 `accept_description`）
 * @param superscript 角标（如"高码率"），空串 = 无
 * @param limited 是否**因权限受限**（`limit_watch_reason != 0`）
 * @param playable 是否**实际拿到了流**（在 `dash.video` 里）
 * @param width 实际宽度（`playable` 时有效）
 * @param height 实际高度
 * @param frameRate 实际帧率（用于识别 60fps 档）
 */
data class QualityOption(
    val id: Int,
    val label: String,
    val superscript: String = "",
    val limited: Boolean = false,
    val playable: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
) {
    /**
     * 能否选中。
     *
     * - 可播 → 能
     * - **受限 → 也能**（需求第三条：保留选项、点击弹会员提示，
     *   而不是直接隐藏）
     * - 其它 → 不能
     */
    val selectable: Boolean get() = playable || limited

    /**
     * 是否应该显示"60 帧"标记。
     *
     * ⚠️ 用**实际帧率**判断，不用档位 id 猜。
     * 实测同一 id 在不同视频里帧率不同（有的 25，有的 60）。
     */
    val isHighFrameRate: Boolean get() = frameRate > 30f

    /** 是否 4K（按**实际分辨率**判断，不按 id 猜）。 */
    val is4K: Boolean get() = height >= 2160

    /**
     * 展示用完整名（含角标）。
     *
     * ## ⚠️ 必须去重 —— 实测角标常常**已经包含在名称里**
     *
     * 实测数据：
     * ```
     * new_description = "1080P 60帧"
     * superscript     = "60帧"
     * ```
     * 直接拼会得到 **「1080P 60帧 60帧」**（模拟器上真实出现过）。
     *
     * 所以名称已含角标时**不再追加**。
     */
    val fullLabel: String
        get() = when {
            superscript.isEmpty() -> label
            // 名称里已经带了同样的角标 → 不重复
            label.contains(superscript) -> label
            else -> "$label $superscript"
        }
}

/**
 * 清晰度列表的来源状态（需求第四条：**不能混成一个**）。
 *
 * 这些状态与 [QualityOption] 是**正交**的：
 * 列表本身可能成功拿到，但某几档受限。
 */
sealed interface QualityAvailability {
    /** 正常拿到了档位列表。 */
    data class Ok(val options: List<QualityOption>) : QualityAvailability

    /**
     * **未登录** —— 接口只给匿名档位，高级档位不可见。
     *
     * UI 文案：「登录后查看可用清晰度」（需求第四条）
     */
    data object NotLoggedIn : QualityAvailability

    /**
     * **网络 / 请求失败** —— 不知道有哪些档位。
     *
     * UI 文案：「获取清晰度失败」（需求第四条）
     *
     * ⚠️ 与"视频不支持高级清晰度"是**两件事**，不能混。
     */
    data class Failed(val message: String) : QualityAvailability

    /**
     * 接口成功，但**这个视频本身没有任何可选档位**。
     *
     * UI 文案：「当前视频不支持该清晰度」（需求第四条）
     */
    data object Unsupported : QualityAvailability
}
