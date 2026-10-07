package com.example.biliv3.data.skin

import androidx.compose.ui.graphics.Color

/**
 * 一套本地装扮（Fake Skin，**未发版**）。
 *
 * ---
 *
 * # 这是什么
 *
 * **客户端本地的视觉模拟** —— 把 B 站个性装扮资源映射到本项目 UI。
 *
 * 🔴 **它不是**：
 * - ❌ 官方装扮系统（UI 上写「Fake Skin」/「本地装扮」）
 * - ❌ 会修改账号真实装扮（**绝不调任何官方写接口**）
 * - ❌ 会改变视频清晰度 / 会员权限（那与装扮**完全无关**）
 *
 * # 字段来源（实测）
 *
 * 见 `_probe/fake-skin-format.md`。实测两版 JSON：
 *
 * | 版本 | 文件 | 字段 |
 * |---|---|---|
 * | 精简 | `<名称>.json` | `id` / `name` / `preview` / `package_url` + `data.*` |
 * | 完整 | `个性装扮.json` | 上述 + `properties.*`（40 个字段）|
 *
 * ⚠️ 两版**字段不完全一致**（如 `side_bg_color` 只在精简版出现），
 * 所以解析器两边都要兼容。
 *
 * # 所有资源都可缺失
 *
 * 实测有的装扮**没有** `side_bg.*`（只有 `side_bg_color`）。
 * 缺失时用**当前 App 默认值**，**不显示空白**（任务书第十条）。
 *
 * @param id 装扮 id（**字符串** —— 实测 JSON 里就是字符串）
 * @param name 主题名称
 * @param previewUrl 预览图地址（远程，仅用于**导入时下载**）
 * @param colorMode 亮/暗模式提示（`light` / `dark`），来自 `color_mode`
 * @param color 主颜色（`color`）
 * @param secondaryColor 第二页面颜色（`color_second_page`）
 * @param tailColor 底部导航未选中色（`tail_color`）
 * @param tailColorSelected 底部导航选中色（`tail_color_selected`）
 * @param sideBgColor 侧栏背景色（`side_bg_color`）
 * @param hasAnimation 是否带动画（`tail_icon_ani == "true"`）
 * @param animationMode 动画模式（`tail_icon_ani_mode`，实测 `once`）
 * @param resources **本地**资源文件名映射（相对装扮目录）
 * @param source 来源（内置 / 用户导入）
 */
data class FakeSkin(
    val id: String,
    val name: String,
    val previewUrl: String = "",
    val colorMode: String = "",
    val color: String = "",
    val secondaryColor: String = "",
    val tailColor: String = "",
    val tailColorSelected: String = "",
    val sideBgColor: String = "",
    val hasAnimation: Boolean = false,
    val animationMode: String = "",
    /**
     * 本地资源：逻辑名 → 文件名。
     *
     * 逻辑名用 [SkinResource] 的常量（`head_bg` / `tail_bg` / `tail_icon_main` …）。
     *
     * ⚠️ **只存文件名，不存绝对路径** —— 装扮目录由 [SkinRepository]
     * 按 `id` 推导。这样换设备 / 换存储位置不会失效。
     */
    val resources: Map<String, String> = emptyMap(),
    val source: SkinSource = SkinSource.Builtin,
) {
    /** 是否为「默认」（无装扮）。 */
    val isDefault: Boolean get() = id == DEFAULT_ID

    /**
     * 解析颜色为 Compose [Color]。
     *
     * ## 为什么返回可空
     *
     * 装扮里的颜色是**字符串**（`#ffffff`），可能：
     * - 空串（实测 `side_bg_color` 就常常是空的）
     * - 格式不对
     *
     * 这时必须回退到**默认主题色**，而不是崩或显示黑色。
     * 调用方（`SkinThemeAdapter`）负责兜底。
     */
    fun parseColor(hex: String): Color? {
        val s = hex.trim()
        if (s.isEmpty()) return null
        return runCatching {
            val body = s.removePrefix("#")
            when (body.length) {
                6 -> Color(body.toLong(16) or 0xFF000000L)
                8 -> Color(body.toLong(16))
                else -> null
            }
        }.getOrNull()
    }

    /** 主颜色（解析失败返回 null）。 */
    val primary: Color? get() = parseColor(color)

    /** 第二页面颜色。 */
    val secondary: Color? get() = parseColor(secondaryColor)

    /** 底部导航未选中色。 */
    val navColor: Color? get() = parseColor(tailColor)

    /** 底部导航选中色。 */
    val navColorSelected: Color? get() = parseColor(tailColorSelected)

    /** 侧栏背景色。 */
    val sideColor: Color? get() = parseColor(sideBgColor)

    /** 取某个逻辑资源的文件名（没有则 null）。 */
    fun resource(name: String): String? = resources[name]?.takeIf { it.isNotEmpty() }

    companion object {
        /**
         * 「默认」装扮的 id。
         *
         * 它不是一套真的装扮，而是**恢复默认**的标记 ——
         * 选中它等于清除 SkinState。
         */
        const val DEFAULT_ID = "__default__"

        /** 内置的「默认」（无装扮）。 */
        val DEFAULT = FakeSkin(id = DEFAULT_ID, name = "默认")
    }
}

/** 装扮来源。 */
enum class SkinSource {
    /** 随 App 打包的内置示例。 */
    Builtin,

    /** 用户从本地导入的。 */
    Imported,
}

/**
 * 逻辑资源名（**与 ZIP 内文件名一致**）。
 *
 * 实测 `*_package.zip` 是**扁平结构**，文件名就是这些。
 * 用常量而不是裸字符串，避免拼错（拼错会静默地"没有背景"）。
 */
object SkinResource {
    const val HEAD_BG = "head_bg"
    const val HEAD_TAB_BG = "head_tab_bg"
    const val HEAD_MYSELF_SQUARED_BG = "head_myself_squared_bg"
    const val TAIL_BG = "tail_bg"

    const val TAIL_ICON_MAIN = "tail_icon_main"
    const val TAIL_ICON_DYNAMIC = "tail_icon_dynamic"
    const val TAIL_ICON_CHANNEL = "tail_icon_channel"
    const val TAIL_ICON_MYSELF = "tail_icon_myself"
    const val TAIL_ICON_SHOP = "tail_icon_shop"
    const val TAIL_ICON_PUB_BTN_BG = "tail_icon_pub_btn_bg"

    const val TAIL_ICON_SELECTED_MAIN = "tail_icon_selected_main"
    const val TAIL_ICON_SELECTED_DYNAMIC = "tail_icon_selected_dynamic"
    const val TAIL_ICON_SELECTED_CHANNEL = "tail_icon_selected_channel"
    const val TAIL_ICON_SELECTED_MYSELF = "tail_icon_selected_myself"
    const val TAIL_ICON_SELECTED_SHOP = "tail_icon_selected_shop"
    const val TAIL_ICON_SELECTED_PUB_BTN_BG = "tail_icon_selected_pub_btn_bg"

    const val PREVIEW = "preview"

    /**
     * 本项目**支持**的资源（导入时只提取这些）。
     *
     * ⚠️ 不含 `tail_icon_shop` 系列 —— 本项目底部导航**没有「会员购」**，
     * 导入它没有意义（任务书第十八条：没有对应 Tab 就不要硬塞）。
     */
    val SUPPORTED = listOf(
        HEAD_BG, HEAD_TAB_BG, HEAD_MYSELF_SQUARED_BG, TAIL_BG,
        TAIL_ICON_MAIN, TAIL_ICON_DYNAMIC, TAIL_ICON_CHANNEL, TAIL_ICON_MYSELF,
        TAIL_ICON_PUB_BTN_BG,
        TAIL_ICON_SELECTED_MAIN, TAIL_ICON_SELECTED_DYNAMIC,
        TAIL_ICON_SELECTED_CHANNEL, TAIL_ICON_SELECTED_MYSELF,
        TAIL_ICON_SELECTED_PUB_BTN_BG,
        PREVIEW,
    )

    /** 支持的图片扩展名（实测 ZIP 里是 png 与 jpg）。 */
    val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
}
