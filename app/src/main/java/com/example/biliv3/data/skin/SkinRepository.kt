package com.example.biliv3.data.skin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 装扮数据用的 DataStore。 */
private val Context.skinDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fake_skin",
)

/**
 * 本地装扮仓库（Fake Skin，**未发版**）。
 *
 * ---
 *
 * # 🔴 只改本地 UI，绝不碰账号
 *
 * 本类**不调用任何网络接口**。它只做：
 * - 读 / 写本地装扮目录
 * - 记住"用户选了哪套"（`selectedSkinId`）
 *
 * **绝不**修改 B 站账号的真实装扮（任务书第二十八条）。
 *
 * # 目录结构（任务书第二十五条）
 *
 * ```
 * files/
 * └─ fake_skin/
 *    ├─ builtin/<id>/         内置示例（随 APK 打包，只读）
 *    ├─ imported/<id>/        用户导入
 *    │  ├─ preview.jpg
 *    │  ├─ head_bg.jpg
 *    │  ├─ tail_bg.png
 *    │  └─ tail_icon_main.png
 *    └─ skin.json             每套装扮的描述
 * ```
 *
 * # 持久化只存 id（任务书第二十四条）
 *
 * **不把整个 Skin 对象序列化进 Preferences** —— 只存 `selectedSkinId`。
 * 启动时用它去目录里加载。
 *
 * ⚠️ **装扮已被删除时自动恢复默认**（任务书第二十四条）——
 * 否则会卡在一个"选中了但不存在的装扮"的状态。
 */
class SkinRepository(private val context: Context) {

    private val store = context.applicationContext.skinDataStore

    /** 装扮根目录。 */
    val rootDir: File
        get() = File(context.filesDir, DIR_ROOT).apply { if (!exists()) mkdirs() }

    private fun dirOf(source: SkinSource, id: String): File =
        File(File(rootDir, if (source == SkinSource.Builtin) DIR_BUILTIN else DIR_IMPORTED), id)

    // ---------------------------------------------------------------------
    // 读取
    // ---------------------------------------------------------------------

    /**
     * 列出所有**已安装**的装扮（内置 + 导入）。
     *
     * 读目录里的 `skin.json`。解析失败的目录**跳过**
     * （一个坏装扮不该让整个列表空掉）。
     */
    suspend fun list(): List<FakeSkin> = withContext(Dispatchers.IO) {
        val out = ArrayList<FakeSkin>()

        // ---- 内置示例：从 APK assets 读（只读，不复制到 files/）----
        //
        // 任务书第二十六条：**不把整个装扮仓库塞进 APK**。
        // 这里只有本项目自制的 3 套示例（约 197 KB）。
        runCatching { listBuiltin() }.getOrDefault(emptyList()).forEach { out.add(it) }

        // ---- 用户导入：从 files/fake_skin/imported 读 ----
        val parent = File(rootDir, DIR_IMPORTED)
        val dirs = parent.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (d in dirs) {
            readSkin(d, SkinSource.Imported)?.let { out.add(it) }
        }

        // 名字排序（稳定、可预期）
        out.sortedBy { it.name }
    }

    /**
     * 读 APK 内的内置装扮。
     *
     * ## 为什么内置的**不复制**到 `files/`
     *
     * 复制会：占用户存储、且 App 升级后旧副本不会更新
     * （用户看到的是上一版的内置装扮）。
     * 直接从 assets 读则始终跟着 APK 走。
     *
     * ## 资源路径约定
     *
     * `assets/fake_skin/<id>/skin.json` + 同目录的图片。
     */
    private fun listBuiltin(): List<FakeSkin> {
        val assets = context.assets
        val ids = assets.list(DIR_ASSETS)?.filter { it.isNotBlank() } ?: return emptyList()
        val out = ArrayList<FakeSkin>()
        for (id in ids) {
            val metaPath = "$DIR_ASSETS/$id/$FILE_META"
            val json = runCatching {
                assets.open(metaPath).bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val parsed = SkinParser.parse(
                runCatching { JSONObject(json) }.getOrNull(),
                fallbackName = id,
            ) ?: continue

            // 扫 assets 目录，记下**真实存在**的资源文件名
            //
            // ⚠️ `assets.list()` 返回**可空**列表 —— 目录不存在时是 null，
            //    直接 `.contains` 编译不过（这正是任务书要求的"缺失不崩"）。
            val files: List<String> = runCatching { assets.list("$DIR_ASSETS/$id") }
                .getOrNull()?.toList().orEmpty()
            val resources = HashMap<String, String>()
            for (name in SkinResource.SUPPORTED) {
                val hit = SkinResource.IMAGE_EXTENSIONS
                    .firstNotNullOfOrNull { ext ->
                        val f = "$name.$ext"
                        if (files.contains(f)) f else null
                    }
                if (hit != null) resources[name] = hit
            }
            out.add(parsed.copy(id = id, resources = resources, source = SkinSource.Builtin))
        }
        return out
    }

    /** 读一个装扮目录。 */
    private fun readSkin(dir: File, source: SkinSource): FakeSkin? {
        val meta = File(dir, FILE_META)
        if (!meta.isFile) return null
        val json = runCatching { JSONObject(meta.readText()) }.getOrNull() ?: return null
        val parsed = SkinParser.parse(json, fallbackName = dir.name) ?: return null

        // 资源文件可能被用户删掉 → 重新扫一遍，只保留**真实存在**的
        val resources = parsed.resources.toMutableMap()
        for (name in SkinResource.SUPPORTED) {
            val f = resources[name]?.let { File(dir, it) }
            if (f == null || !f.isFile) {
                // 没记录就试着按常见扩展名找
                val found = SkinResource.IMAGE_EXTENSIONS
                    .map { File(dir, "$name.$it") }
                    .firstOrNull { it.isFile }
                if (found != null) resources[name] = found.name else resources.remove(name)
            }
        }
        return parsed.copy(resources = resources, source = source)
    }

    /** 取某套装扮的完整信息（含真实存在的资源）。 */
    suspend fun get(id: String): FakeSkin? = withContext(Dispatchers.IO) {
        if (id.isEmpty() || id == FakeSkin.DEFAULT_ID) return@withContext null
        // 导入的优先（用户可能覆盖同名内置）—— 但内置 id 有 `builtin_` 前缀，
        // 实际不会撞名。
        val imported = dirOf(SkinSource.Imported, id)
        if (imported.isDirectory) readSkin(imported, SkinSource.Imported)?.let {
            return@withContext it
        }
        listBuiltin().firstOrNull { it.id == id }
    }

    /**
     * 某个资源文件。
     *
     * ⚠️ 内置装扮的资源在 **APK assets 里**，不是普通 File ——
     * 返回 null 时调用方要改用 [openBuiltinResource]。
     */
    suspend fun resourceFile(id: String, name: String): File? = withContext(Dispatchers.IO) {
        val skin = get(id) ?: return@withContext null
        if (skin.source == SkinSource.Builtin) return@withContext null
        val fname = skin.resource(name) ?: return@withContext null
        File(dirOf(skin.source, id), fname).takeIf { it.isFile }
    }

    /**
     * 打开内置装扮的资源流（**调用方负责 close**）。
     *
     * 内置装扮从 assets 读，不能用 File 表示。
     */
    fun openBuiltinResource(id: String, name: String): java.io.InputStream? {
        val ext = SkinResource.IMAGE_EXTENSIONS
        for (e in ext) {
            val r = runCatching { context.assets.open("$DIR_ASSETS/$id/$name.$e") }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    // ---------------------------------------------------------------------
    // 选中态
    // ---------------------------------------------------------------------

    /**
     * 当前选中的装扮 id（空 = 默认）。
     *
     * ⚠️ **装扮已被删除时自动恢复默认**（任务书第二十四条）：
     * 这里不直接返回存的值，而是**校验它还存在**。
     */
    suspend fun selectedId(): String {
        val saved = runCatching { store.data.first()[KEY_SELECTED] }.getOrNull().orEmpty()
        if (saved.isEmpty() || saved == FakeSkin.DEFAULT_ID) return ""
        // 校验存在性 —— 不存在就当作默认（并顺手清掉脏值）
        val exists = get(saved) != null
        if (!exists) {
            runCatching { store.edit { it.remove(KEY_SELECTED) } }
            return ""
        }
        return saved
    }

    /** 选中一套装扮（传空串或 [FakeSkin.DEFAULT_ID] = 恢复默认）。 */
    suspend fun select(id: String) {
        store.edit { prefs ->
            if (id.isEmpty() || id == FakeSkin.DEFAULT_ID) prefs.remove(KEY_SELECTED)
            else prefs[KEY_SELECTED] = id
        }
    }

    // ---------------------------------------------------------------------
    // 导入 / 删除
    // ---------------------------------------------------------------------

    /**
     * 导入一套装扮。
     *
     * ## 流程（任务书第十四条）
     *
     * 1. 校验 JSON（`skin.json` / 目录里的 `<名称>.json`）
     * 2. 解压 `*_package.zip` 到 `imported/<id>/`
     * 3. 写 `skin.json` 作为**统一元数据**
     *
     * @param metaJson 装扮描述 JSON 的文本（调用方读文件后传入）
     * @param zipFile 资源 ZIP；null = 只有 JSON（资源全缺，仍可导入，
     *   只是所有背景都会回退默认 —— 任务书第十条）
     */
    suspend fun import(
        metaJson: String,
        fallbackName: String,
        zipFile: File?,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val json = runCatching { JSONObject(metaJson) }.getOrNull()
            ?: return@withContext ImportOutcome.Failed("装扮描述文件不是合法 JSON")

        val parsed = SkinParser.parse(json, fallbackName = fallbackName)
            ?: return@withContext ImportOutcome.Failed("装扮描述缺少名称")

        // 用 id 建目录；同名冲突时加后缀（不覆盖已有的）
        var id = parsed.id
        var dir = dirOf(SkinSource.Imported, id)
        var n = 1
        while (dir.exists()) {
            id = "${parsed.id}_$n"
            dir = dirOf(SkinSource.Imported, id)
            n++
            if (n > 50) return@withContext ImportOutcome.Failed("同名装扮过多")
        }

        val resources: Map<String, String>
        if (zipFile != null) {
            when (val r = SkinImporter.extract(zipFile, dir)) {
                is SkinImporter.ImportResult.Ok -> resources = r.resources
                SkinImporter.ImportResult.Unsupported -> {
                    dir.deleteRecursively()
                    return@withContext ImportOutcome.Unsupported
                }
                is SkinImporter.ImportResult.NotZip -> {
                    dir.deleteRecursively()
                    return@withContext ImportOutcome.Failed("无法读取资源包：${r.detail}")
                }
                is SkinImporter.ImportResult.Invalid -> {
                    dir.deleteRecursively()
                    return@withContext ImportOutcome.Failed(r.detail)
                }
            }
        } else {
            // 没有 ZIP：建目录并接受"所有资源缺失"
            if (!dir.exists()) dir.mkdirs()
            resources = emptyMap()
        }

        // 写统一元数据（含**实际**落盘的资源名）
        val meta = JSONObject().apply {
            put("id", id)
            put("name", parsed.name)
            put("preview", parsed.previewUrl)
            put("color", parsed.color)
            put("color_mode", parsed.colorMode)
            put("color_second_page", parsed.secondaryColor)
            put("tail_color", parsed.tailColor)
            put("tail_color_selected", parsed.tailColorSelected)
            put("side_bg_color", parsed.sideBgColor)
            put("tail_icon_ani", parsed.hasAnimation.toString())
            put("tail_icon_ani_mode", parsed.animationMode)
            put("resources", JSONObject(resources as Map<*, *>))
        }
        File(dir, FILE_META).writeText(meta.toString())

        val saved = readSkin(dir, SkinSource.Imported)
            ?: return@withContext ImportOutcome.Failed("装扮写入后无法读回")
        ImportOutcome.Ok(saved)
    }

    /** 删除一套**导入的**装扮（内置的不可删）。 */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val d = dirOf(SkinSource.Imported, id)
        if (!d.isDirectory) return@withContext false
        // 如果删的正是当前选中的 → 顺手恢复默认
        if (selectedId() == id) select("")
        d.deleteRecursively()
    }

    private companion object {
        const val DIR_ROOT = "fake_skin"
        const val DIR_BUILTIN = "builtin"
        const val DIR_IMPORTED = "imported"

        /** APK assets 里的内置装扮目录。 */
        const val DIR_ASSETS = "fake_skin"
        const val FILE_META = "skin.json"
        val KEY_SELECTED = stringPreferencesKey("selectedSkinId")
    }
}

/** 导入结果。 */
sealed interface ImportOutcome {
    data class Ok(val skin: FakeSkin) : ImportOutcome

    /**
     * ZIP 合法但**结构不兼容**。
     *
     * UI 文案：「当前装扮格式暂不支持」（任务书第十四条）
     */
    data object Unsupported : ImportOutcome

    data class Failed(val message: String) : ImportOutcome
}
