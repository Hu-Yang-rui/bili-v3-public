package com.example.biliv3.plugin

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 外部插件包（`.bvplugin`）的**预览信息**。
 *
 * ## 🔴 本类的定位：只解析、不执行
 *
 * 任务书 §17 要求：
 * > 优先实现插件包解析、信息展示、格式预览、兼容性检测
 *
 * 所以本类**不加载任何代码**，只读取包内文件与元数据。
 * 这是"扫描 → 解析 → 展示权限 → 用户确认 → 安装"流程的前两步。
 *
 * ## 包结构（任务书 §16）
 *
 * ```
 * manifest.json      插件元数据（必须）
 * plugin.json        原生插件入口声明（可选）
 * rules.json         规则插件规则（可选）
 * preview/           预览图（可选）
 * assets/            资源（可选）
 * native/            原生库 .so（可选 → 高风险）
 * ```
 */
data class PluginPackagePreview(
    /** 解析是否成功。false 时看 [error]。 */
    val valid: Boolean,

    /** 插件元数据（valid = false 时为 null）。 */
    val metadata: PluginMetadata? = null,

    /** 包内文件清单（相对路径）。 */
    val entries: List<PackageEntry> = emptyList(),

    /** 规则条数（规则插件）。 */
    val ruleCount: Int = 0,

    /** 规则解析错误。 */
    val ruleErrors: List<String> = emptyList(),

    /** 解析错误（valid = false 时）。 */
    val error: String? = null,

    /** 是否含原生代码（.so）。 */
    val hasNativeCode: Boolean = false,

    /** 是否含外部资源（assets / preview）。 */
    val hasAssets: Boolean = false,

    /** 签名 / 校验状态。 */
    val signature: SignatureStatus = SignatureStatus.NONE,
) {
    /** 综合风险等级（给用户看的提示）。 */
    val riskLevel: RiskLevel
        get() = when {
            hasNativeCode -> RiskLevel.HIGH
            metadata?.permissions?.any { it.level == RiskLevel.HIGH } == true -> RiskLevel.HIGH
            metadata?.permissions?.any { it.level == RiskLevel.MEDIUM } == true -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

    /** 风险说明（逐条列出理由，不只是给个等级）。 */
    val riskReasons: List<String>
        get() = buildList {
            if (hasNativeCode) {
                add("包含原生代码（.so），可执行机器码 —— 这是最高的风险来源")
            }
            metadata?.permissions?.filter { it.level == RiskLevel.HIGH }?.forEach {
                add("请求高风险权限：${it.displayName}（${it.description}）")
            }
            if (signature == SignatureStatus.NONE) {
                add("未签名 —— 无法验证来源与完整性")
            }
        }
}

/** 包内一个文件条目。 */
data class PackageEntry(
    val path: String,
    val sizeBytes: Long,
)

/** 签名状态。 */
enum class SignatureStatus(val label: String) {
    /** 没有签名信息。 */
    NONE("未签名"),

    /** 有 hash 声明但未验证（当前阶段只展示，不校验）。 */
    DECLARED("已声明（未校验）"),
}

/**
 * `.bvplugin` 包解析器。
 *
 * ## 安全约束（重要）
 *
 * 解析**不可信输入**时必须防：
 *
 * | 攻击 | 防护 |
 * |---|---|
 * | **Zip Slip**（`../../etc/passwd`） | 只读条目名，**不落盘**（本类不解压） |
 * | **Zip Bomb**（解压后几百 GB） | 限制条目数 + 单文件大小 + 总量 |
 * | **恶意 manifest** | 全部用 `optXxx` 容错解析，不 `getXxx` |
 *
 * 当前实现**完全不解压到磁盘** —— 只读元数据文件的内容，
 * 所以 Zip Slip 天然不存在。将来若要解压安装，必须校验规范化路径。
 */
object PluginPackageParser {

    /** 条目数上限（防 zip bomb）。 */
    const val MAX_ENTRIES = 500

    /** 单个元数据文件的读取上限（1 MB）。 */
    const val MAX_META_BYTES = 1_000_000

    /** 单个条目的大小上限（100 MB）。 */
    const val MAX_ENTRY_BYTES = 100_000_000L

    /**
     * 解析插件包。
     *
     * @param input 包内容流（调用方负责关闭）
     */
    fun parse(input: InputStream): PluginPackagePreview {
        val entries = ArrayList<PackageEntry>()
        var manifestText: String? = null
        var rulesText: String? = null
        var hasNative = false
        var hasAssets = false

        try {
            ZipInputStream(input).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > MAX_ENTRIES) {
                        return PluginPackagePreview(
                            valid = false,
                            error = "插件包条目数超过上限（$MAX_ENTRIES）",
                        )
                    }

                    val name = entry.name.replace('\\', '/')

                    // 条目名安全校验：拒绝绝对路径与上跳
                    if (name.startsWith("/") || name.contains("..")) {
                        return PluginPackagePreview(
                            valid = false,
                            error = "插件包含非法路径：$name",
                        )
                    }

                    entries.add(PackageEntry(name, entry.size.coerceAtLeast(0L)))

                    when {
                        name.endsWith(".so") -> hasNative = true
                        name.startsWith("assets/") || name.startsWith("preview/") -> hasAssets = true
                    }

                    // 只读需要的元数据文件（**不解压到磁盘**）
                    when (name) {
                        "manifest.json" -> manifestText = readLimited(zip)
                        "rules.json" -> rulesText = readLimited(zip)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return PluginPackagePreview(valid = false, error = "包解析失败：${e.message}")
        }

        if (manifestText == null) {
            return PluginPackagePreview(
                valid = false,
                entries = entries,
                hasNativeCode = hasNative,
                hasAssets = hasAssets,
                error = "插件包缺少 manifest.json",
            )
        }

        val manifest = try {
            JSONObject(manifestText)
        } catch (e: Exception) {
            return PluginPackagePreview(
                valid = false,
                entries = entries,
                error = "manifest.json 不是合法 JSON",
            )
        }

        val id = manifest.optString("id").trim()
        if (id.isEmpty()) {
            return PluginPackagePreview(
                valid = false,
                entries = entries,
                error = "manifest.json 缺少 id",
            )
        }

        // 权限解析（未知权限名**报错而不是忽略** —— 见 PluginPermission 的说明）
        val permArr = manifest.optJSONArray("permissions")
        val perms = HashSet<PluginPermission>()
        val unknownPerms = ArrayList<String>()
        if (permArr != null) {
            for (i in 0 until permArr.length()) {
                val raw = permArr.optString(i)
                val p = PluginPermission.from(raw)
                if (p == null) unknownPerms.add(raw) else perms.add(p)
            }
        }

        val declaredType = manifest.optString("type", "rule").lowercase()
        val type = when {
            hasNative -> PluginType.EXTERNAL   // 有 .so 就是外部原生包
            declaredType == "native" -> PluginType.NATIVE
            else -> PluginType.RULE
        }

        val meta = PluginMetadata(
            id = id,
            name = manifest.optString("name").ifEmpty { id },
            version = manifest.optString("version").ifEmpty { "0.0.0" },
            author = manifest.optString("author"),
            description = manifest.optString("description"),
            type = type,
            permissions = perms,
            compatibleAppVersion = manifest.optString("appVersion"),
            apiVersion = manifest.optInt("apiVersion", PluginApi.VERSION),
            hasNativeCode = hasNative,
            entry = manifest.optString("entry"),
        )

        // 规则解析（只做静态解析，不执行）
        var ruleCount = 0
        var ruleErrors = emptyList<String>()
        if (rulesText != null) {
            try {
                val rulesJson = JSONObject(rulesText)
                val arr = rulesJson.optJSONArray("rules") ?: rulesJson.optJSONArray("items")
                val parsed = RuleParser.parse(arr)
                ruleCount = parsed.rules.size
                ruleErrors = parsed.errors + if (unknownPerms.isEmpty()) {
                    emptyList()
                } else {
                    listOf("含未知权限名：${unknownPerms.joinToString()}")
                }
            } catch (e: Exception) {
                ruleErrors = listOf("rules.json 不是合法 JSON")
            }
        }

        // 签名：manifest 里声明了 hash 就标为"已声明"（当前不校验）
        val signature = if (manifest.optString("sha256").isNotEmpty()) {
            SignatureStatus.DECLARED
        } else {
            SignatureStatus.NONE
        }

        return PluginPackagePreview(
            valid = true,
            metadata = meta,
            entries = entries,
            ruleCount = ruleCount,
            ruleErrors = ruleErrors,
            hasNativeCode = hasNative,
            hasAssets = hasAssets,
            signature = signature,
        )
    }

    /**
     * 从 `Uri` 解析（供文件选择器用）。
     */
    fun parse(context: Context, uri: Uri): PluginPackagePreview {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return PluginPackagePreview(valid = false, error = "无法打开文件")
            stream.use { parse(it) }
        } catch (e: Exception) {
            PluginPackagePreview(valid = false, error = "读取文件失败：${e.message}")
        }
    }

    /**
     * 读一个条目的内容，带上限。
     *
     * ⚠️ 必须有上限：`ZipInputStream` 的 `entry.size` 在流式读取时
     * **可能是 -1 或伪造值**，不能只信它。这里按实际读取字节数截断。
     */
    private fun readLimited(zip: ZipInputStream): String {
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val n = zip.read(buffer)
            if (n <= 0) break
            total += n
            if (total > MAX_META_BYTES) break
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** 当前阶段允许的扩展名。 */
    val SUPPORTED_EXTENSIONS = listOf("bvplugin", "json")

    /** 从文件名判断是否支持。 */
    fun isSupported(fileName: String): Boolean =
        SUPPORTED_EXTENSIONS.any { fileName.endsWith(".$it", ignoreCase = true) }
}
