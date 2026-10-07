package com.example.biliv3.data.skin

import org.json.JSONObject

/**
 * 装扮 JSON 解析（**未发版**，纯函数，可单测）。
 *
 * ---
 *
 * # 实测支持两种形状
 *
 * | 版本 | 文件 | 字段位置 |
 * |---|---|---|
 * | **精简** | `<名称>.json` | 顶层 `id`/`name`/`preview`/`package_url` + **`data.*`** |
 * | **完整** | `个性装扮.json` | 上述 + **`data.properties.*`** |
 *
 * 实测样本（`一猫人.json`）：
 * ```json
 * {"id":"44858","name":"一猫人","preview":"https://…jpg",
 *  "ver":"1672307795","package_url":"https://…zip",
 *  "data":{"color_mode":"dark","color":"#ffffff","color_second_page":"#104057",
 *          "tail_color":"#bbe2ff","tail_color_selected":"#ffe800",
 *          "tail_icon_ani":"true","tail_icon_ani_mode":"once",
 *          "tail_icon_mode":"img","side_bg_color":""}}
 * ```
 *
 * # 🔴 类型陷阱（实测）
 *
 * `id` / `ver` / `tail_icon_ani` / `tail_icon_ani_mode` 在 JSON 里**都是字符串**：
 *
 * ```json
 * "id": "44858"          ← 字符串，不是数字
 * "tail_icon_ani": "true" ← 字符串，不是布尔
 * ```
 *
 * 用 `optInt` / `optBoolean` 读会**静默拿到默认值**（0 / false）——
 * 于是"带动画的装扮"被判成不带动画。所以一律用 `optString`。
 */
object SkinParser {

    /**
     * 解析一套装扮。
     *
     * @param json 装扮 JSON（精简版或完整版都可）
     * @param fallbackName 没有 `name` 时用的名字（通常是目录名）
     * @return 解析失败（缺 id/name）时返回 null
     */
    fun parse(json: JSONObject?, fallbackName: String = ""): FakeSkin? {
        if (json == null) return null

        // ---- 名称：顶层 name → fallback ----
        val name = json.optString("name").trim().ifEmpty { fallbackName.trim() }

        // ---- id：顶层 id → data.id → 用名字兜底 ----
        //
        // ⚠️ 实测 `id` 是**字符串**（`"44858"`）。用 optString 读，
        //    再兜底到名字 —— 没有 id 就没法在本地建目录、也没法持久化选中态。
        val id = json.optString("id").trim()
            .ifEmpty { dataOf(json).optString("id").trim() }
            .ifEmpty { name }
        if (id.isEmpty()) return null

        // ---- 属性块：完整版在 data.properties，精简版在 data ----
        //
        // 实测两版字段**不完全一致**（`side_bg_color` 只在精简版出现），
        // 所以先读 properties，再用 data 补齐。
        val data = dataOf(json)
        val props = data.optJSONObject("properties") ?: JSONObject()

        fun field(key: String): String =
            props.optString(key).ifEmpty { data.optString(key) }.trim()

        return FakeSkin(
            id = id,
            name = name.ifEmpty { id },
            // 预览图：顶层 preview → properties.image_preview
            previewUrl = json.optString("preview").trim()
                .ifEmpty { field("image_preview") },
            colorMode = field("color_mode"),
            color = field("color"),
            secondaryColor = field("color_second_page"),
            tailColor = field("tail_color"),
            tailColorSelected = field("tail_color_selected"),
            sideBgColor = field("side_bg_color"),
            // ⚠️ `tail_icon_ani` 是**字符串** "true"，不是布尔
            hasAnimation = field("tail_icon_ani").equals("true", ignoreCase = true),
            animationMode = field("tail_icon_ani_mode"),
            source = SkinSource.Imported,
        )
    }

    /** 取 `data` 块（完整版里 `data` 还套一层 `properties`）。 */
    private fun dataOf(json: JSONObject): JSONObject =
        json.optJSONObject("data") ?: json

    /**
     * 从完整版 JSON 的 `properties` 里提取**远程资源 URL**。
     *
     * ## 用途
     *
     * 导入时用它知道"这套装扮有哪些资源"，
     * 但**不会**用这些 URL 作为运行时来源（任务书第十三条：
     * 不要让 App 每次打开都请求 `i0.hdslb.com`）。
     *
     * 它们只用于**导入阶段**：判断哪些资源存在，
     * 然后由用户提供的 ZIP / 本地文件提供实际字节。
     *
     * @return 逻辑资源名 → 远程 URL
     */
    fun parseResourceUrls(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val props = dataOf(json).optJSONObject("properties") ?: return emptyMap()
        val out = HashMap<String, String>()
        for (name in SkinResource.SUPPORTED) {
            val url = props.optString(name).trim()
            if (url.isNotEmpty() && url.startsWith("http")) out[name] = url
        }
        return out
    }
}
