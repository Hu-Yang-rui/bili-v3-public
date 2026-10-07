package com.example.biliv3

import androidx.compose.ui.graphics.Color
import com.example.biliv3.data.skin.FakeSkin
import com.example.biliv3.data.skin.SkinParser
import com.example.biliv3.data.skin.SkinResource
import com.example.biliv3.data.skin.SkinThemeAdapter
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * 装扮解析与主题适配测试（**未发版**）。
 *
 * ## 样本来源
 *
 * 下面的 JSON 是**从 `Rovniced/bilibili-skin` 实测抓到的真实内容**
 * （`一猫人.json` / `test/个性装扮.json`），不是构造的理想数据。
 */
class SkinParserTest {

    // ---------------- 真实样本 ----------------

    /** 精简版（`一猫人.json`，实测原样）。 */
    private val compact = """
        {"id":"44858","name":"一猫人",
         "preview":"https://i0.hdslb.com/bfs/garb/item/0cbb5f4d35f1289796a9c0399da08231e32d7a16.jpg",
         "ver":"1672307795",
         "package_url":"https://i0.hdslb.com/bfs/garb/zip/68390abe0b8f90c1b5e445b3b93f0891db8e54c5.zip",
         "data":{"color_mode":"dark","color":"#ffffff","color_second_page":"#104057",
                 "tail_color":"#bbe2ff","tail_color_selected":"#ffe800",
                 "tail_icon_ani":"true","tail_icon_ani_mode":"once",
                 "head_myself_mp4_play":"once","tail_icon_mode":"img",
                 "side_bg_color":""}}
    """.trimIndent()

    /** 完整版（`test/个性装扮.json` 的形状，字段在 `data.properties`）。 */
    private val full = """
        {"code":0,"message":"0","ttl":1,
         "data":{"item_id":49871,"name":"test","group_id":45,
           "properties":{"color":"#212121","color_mode":"light","color_second_page":"#d2cfce",
             "gray_rule":"true","head_bg":"https://i0.hdslb.com/bfs/garb/item/c909.png",
             "head_tab_bg":"https://i0.hdslb.com/bfs/garb/item/709b.png",
             "head_myself_squared_bg":"https://i0.hdslb.com/bfs/garb/item/5c11.jpg",
             "tail_bg":"https://i0.hdslb.com/bfs/garb/item/86c8.png",
             "tail_color":"#0b325e","tail_color_selected":"#e24672",
             "tail_icon_ani":"true","tail_icon_ani_mode":"once",
             "tail_icon_main":"https://i0.hdslb.com/bfs/garb/item/68cf.png",
             "tail_icon_selected_main":"https://i0.hdslb.com/bfs/garb/item/807d.png",
             "tail_icon_mode":"img","ver":"1678959110"}}}
    """.trimIndent()

    private fun parse(s: String, fallbackName: String = "") =
        SkinParser.parse(JSONObject(s), fallbackName)

    // ---------------- 精简版 ----------------

    /** 精简版：`data.*` 里的字段被读到。 */
    @Test
    fun `解析精简版`() {
        val s = parse(compact)!!
        assertThat(s.id).isEqualTo("44858")
        assertThat(s.name).isEqualTo("一猫人")
        assertThat(s.colorMode).isEqualTo("dark")
        assertThat(s.color).isEqualTo("#ffffff")
        assertThat(s.secondaryColor).isEqualTo("#104057")
        assertThat(s.tailColor).isEqualTo("#bbe2ff")
        assertThat(s.tailColorSelected).isEqualTo("#ffe800")
    }

    /**
     * 🔴 **回归测试**：`tail_icon_ani` 在 JSON 里是**字符串** `"true"`。
     *
     * 用 `optBoolean` 读会**静默拿到 false** ——
     * 于是"带动画的装扮"被判成不带动画。
     */
    @Test
    fun `动画标记是字符串`() {
        val s = parse(compact)!!
        assertThat(s.hasAnimation).isTrue()
        assertThat(s.animationMode).isEqualTo("once")
    }

    /** `id` 是字符串（不是数字）。 */
    @Test
    fun `id 按字符串读`() {
        val s = parse(compact)!!
        assertThat(s.id).isEqualTo("44858")
        // 不能变成 "44858.0" 或数字
        assertThat(s.id).doesNotContain(".")
    }

    /** `side_bg_color` 为空串时不崩，解析为 null 颜色。 */
    @Test
    fun `空颜色解析为 null`() {
        val s = parse(compact)!!
        assertThat(s.sideBgColor).isEmpty()
        assertThat(s.sideColor).isNull()
    }

    // ---------------- 完整版 ----------------

    /** 完整版：字段在 `data.properties` 里，也要读到。 */
    @Test
    fun `解析完整版`() {
        val s = parse(full, fallbackName = "test")!!
        assertThat(s.name).isEqualTo("test")
        assertThat(s.color).isEqualTo("#212121")
        assertThat(s.colorMode).isEqualTo("light")
        assertThat(s.secondaryColor).isEqualTo("#d2cfce")
        assertThat(s.tailColor).isEqualTo("#0b325e")
        assertThat(s.tailColorSelected).isEqualTo("#e24672")
    }

    /** 完整版没有顶层 `id` → 用名字兜底（否则无法建目录 / 持久化）。 */
    @Test
    fun `缺 id 时用名字兜底`() {
        val s = parse(full, fallbackName = "test")!!
        assertThat(s.id).isEqualTo("test")
    }

    /** 完整版没有顶层 `preview` → 回退到 `properties.image_preview`。 */
    @Test
    fun `预览图回退`() {
        val j = JSONObject(
            """{"id":"1","name":"x","data":{"properties":{
                 "image_preview":"https://i0.hdslb.com/a.jpg"}}}""",
        )
        assertThat(SkinParser.parse(j)!!.previewUrl).isEqualTo("https://i0.hdslb.com/a.jpg")
    }

    // ---------------- 容错 ----------------

    /** null / 缺名字 → null（调用方跳过该目录）。 */
    @Test
    fun `无效输入返回 null`() {
        assertThat(SkinParser.parse(null)).isNull()
        assertThat(SkinParser.parse(JSONObject("{}"))).isNull()
    }

    /** 缺 `data` 块不崩（字段全空但能解析）。 */
    @Test
    fun `缺 data 不崩`() {
        val s = parse("""{"id":"1","name":"裸装扮"}""")!!
        assertThat(s.name).isEqualTo("裸装扮")
        assertThat(s.color).isEmpty()
        assertThat(s.hasAnimation).isFalse()
    }

    /** 颜色解析：合法十六进制。 */
    @Test
    fun `颜色解析`() {
        val s = FakeSkin(id = "1", name = "x")
        assertThat(s.parseColor("#ffffff")).isEqualTo(Color(0xFFFFFFFF))
        assertThat(s.parseColor("#212121")).isEqualTo(Color(0xFF212121))
        // 带 alpha 的 8 位
        assertThat(s.parseColor("#80ff0000")).isEqualTo(Color(0x80FF0000))
    }

    /** 颜色解析：非法输入返回 null（调用方回退默认，不崩）。 */
    @Test
    fun `非法颜色返回 null`() {
        val s = FakeSkin(id = "1", name = "x")
        assertThat(s.parseColor("")).isNull()
        assertThat(s.parseColor("#xyz")).isNull()
        assertThat(s.parseColor("#12345")).isNull()
        assertThat(s.parseColor("red")).isNull()
    }

    /** 颜色前后空格被容忍。 */
    @Test
    fun `颜色容忍空格`() {
        val s = FakeSkin(id = "1", name = "x")
        assertThat(s.parseColor("  #ffffff  ")).isEqualTo(Color(0xFFFFFFFF))
    }

    // ---------------- 资源 URL ----------------

    /** 从完整版提取远程资源 URL（仅用于导入阶段）。 */
    @Test
    fun `提取资源 URL`() {
        val urls = SkinParser.parseResourceUrls(JSONObject(full))
        assertThat(urls).containsKey(SkinResource.HEAD_BG)
        assertThat(urls).containsKey(SkinResource.TAIL_BG)
        assertThat(urls).containsKey(SkinResource.TAIL_ICON_MAIN)
        // 没提供的资源不在里面
        assertThat(urls).doesNotContainKey(SkinResource.TAIL_ICON_SHOP)
    }

    /**
     * ⚠️ `tail_icon_shop` 是**本项目不支持的**资源 ——
     * 底部导航没有「会员购」，导入它没有意义。
     */
    @Test
    fun `shop 图标不在支持列表`() {
        assertThat(SkinResource.SUPPORTED).doesNotContain(SkinResource.TAIL_ICON_SHOP)
        assertThat(SkinResource.SUPPORTED).doesNotContain(SkinResource.TAIL_ICON_SELECTED_SHOP)
    }

    // ---------------- 默认装扮 ----------------

    /** 默认装扮：`isDefault` 为真、id 是特殊常量。 */
    @Test
    fun `默认装扮`() {
        assertThat(FakeSkin.DEFAULT.isDefault).isTrue()
        assertThat(FakeSkin.DEFAULT.id).isEqualTo(FakeSkin.DEFAULT_ID)
    }

    /** 普通装扮 `isDefault` 为假。 */
    @Test
    fun `普通装扮非默认`() {
        assertThat(parse(compact)!!.isDefault).isFalse()
    }
}

/**
 * 主题适配测试（**未发版**）。
 *
 * ## 为什么这组测试重要
 *
 * 任务书第二十条明确要求：
 * > 不允许直接让某套装扮覆盖所有 UI 颜色。必须经过 SkinThemeAdapter。
 *
 * 这里钉死两件事：
 * 1. **只覆盖强调色**，不动结构色（背景/文字）
 * 2. **对比度不足时回退默认**（否则浅色装扮会让文字看不见）
 */
class SkinThemeAdapterTest {

    private val darkBg = Color(0xFF0F1419)

    /** 无装扮 → 默认适配器（未激活）。 */
    @Test
    fun `无装扮为默认`() {
        assertThat(SkinThemeAdapter.from(null, darkBg)).isEqualTo(SkinThemeAdapter.DEFAULT)
        assertThat(SkinThemeAdapter.from(FakeSkin.DEFAULT, darkBg).active).isFalse()
    }

    /** 深色底 + 亮色强调 → 激活。 */
    @Test
    fun `亮色强调在深底上激活`() {
        val s = FakeSkin(id = "1", name = "x", color = "#ffe800")
        val a = SkinThemeAdapter.from(s, darkBg)
        assertThat(a.active).isTrue()
        assertThat(a.brandPrimary).isEqualTo(Color(0xFFFFE800))
    }

    /**
     * 🔴 **回归测试**：**近底色**必须被拦下。
     *
     * 一套装扮如果把强调色设成接近背景的黑（`#101418`），
     * 直接应用会导致按钮与背景**糊在一起**。
     */
    @Test
    fun `近底色被拦下`() {
        val s = FakeSkin(id = "1", name = "x", color = "#101418")
        val a = SkinThemeAdapter.from(s, darkBg)
        // 对比度不足 → 不激活
        assertThat(a.active).isFalse()
        assertThat(a.brandPrimary).isNull()
    }

    /** 所有颜色都不合格 → 当作未启用（而不是启用一个空壳）。 */
    @Test
    fun `全部不合格时不激活`() {
        val s = FakeSkin(
            id = "1", name = "x",
            color = "#101418", tailColor = "#0f1318", tailColorSelected = "#111519",
        )
        assertThat(SkinThemeAdapter.from(s, darkBg).active).isFalse()
    }

    /** 对比度公式：同色为 1.0。 */
    @Test
    fun `同色对比度为 1`() {
        assertThat(SkinThemeAdapter.contrast(darkBg, darkBg)).isWithin(0.001).of(1.0)
    }

    /** 黑白对比度约 21（WCAG 极值）。 */
    @Test
    fun `黑白对比度约 21`() {
        val c = SkinThemeAdapter.contrast(Color.White, Color.Black)
        assertThat(c).isWithin(0.5).of(21.0)
    }

    /** 对比度与参数顺序无关。 */
    @Test
    fun `对比度对称`() {
        val a = Color(0xFFFFE800)
        assertThat(SkinThemeAdapter.contrast(a, darkBg))
            .isEqualTo(SkinThemeAdapter.contrast(darkBg, a))
    }

    // ---------------- 受控映射（只改强调色）----------------

    /**
     * 🔴 **回归测试**：`applyTo` **只改强调色**，
     * 背景 / 文字 / 发丝线**必须原样保留**。
     *
     * 这是"装扮不破坏设计规范"的核心保证。
     */
    @Test
    fun `只改强调色不动结构色`() {
        val base = com.example.biliv3.design.tokens.DarkColors
        val s = FakeSkin(id = "1", name = "x", color = "#ffe800")
        val a = SkinThemeAdapter.from(s, base.bgBase)
        val out = a.applyTo(base)

        // 强调色被改
        assertThat(out.brandPrimary).isEqualTo(Color(0xFFFFE800))
        // 结构色**一个都没变**
        assertThat(out.bgBase).isEqualTo(base.bgBase)
        assertThat(out.bgCard).isEqualTo(base.bgCard)
        assertThat(out.surfaceElevated).isEqualTo(base.surfaceElevated)
        assertThat(out.textPrimary).isEqualTo(base.textPrimary)
        assertThat(out.borderHairline).isEqualTo(base.borderHairline)
    }

    /** 未激活的适配器 `applyTo` 返回原色板（不产生新对象差异）。 */
    @Test
    fun `未激活时色板不变`() {
        val base = com.example.biliv3.design.tokens.DarkColors
        val out = SkinThemeAdapter.DEFAULT.applyTo(base)
        assertThat(out.brandPrimary).isEqualTo(base.brandPrimary)
        assertThat(out.bgBase).isEqualTo(base.bgBase)
    }

    /** 派生色跟着主色走（hover/active/dim 不残留旧色）。 */
    @Test
    fun `派生色跟随主色`() {
        val base = com.example.biliv3.design.tokens.DarkColors
        val s = FakeSkin(id = "1", name = "x", color = "#ffe800")
        val out = SkinThemeAdapter.from(s, base.bgBase).applyTo(base)
        // 不应还是默认品牌色
        assertThat(out.brandPrimaryHover).isNotEqualTo(base.brandPrimaryHover)
        assertThat(out.brandPrimaryActive).isNotEqualTo(base.brandPrimaryActive)
    }
}
