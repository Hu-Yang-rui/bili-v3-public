package com.example.biliv3.data.skin

import java.io.File
import java.util.zip.ZipInputStream

/**
 * 装扮 ZIP 导入（**未发版**）。
 *
 * ---
 *
 * # 实测 ZIP 结构
 *
 * `*_package.zip`（实测 `一猫人_package.zip`，16 个文件）是**扁平结构**：
 *
 * ```
 * tail_icon_selected_myself.png
 * tail_icon_selected_main.png
 * head_bg.jpg
 * head_tab_bg.jpg
 * head_myself_squared_bg.jpg
 * tail_bg.png
 * tail_icon_main.png
 * tail_icon_dynamic.png
 * tail_icon_channel.png
 * tail_icon_myself.png
 * tail_icon_pub_btn_bg.png
 * tail_icon_selected_*.png
 * ```
 *
 * 所以导入逻辑 = **按文件名匹配**，不需要解析目录树。
 *
 * # 🔴 不兼容时必须明说，不能强解
 *
 * 任务书第十四条：
 * > 如果 ZIP 内结构与当前 App 不兼容：显示「当前装扮格式暂不支持」，
 * > 不要强行解析造成崩溃。
 *
 * 所以 [extract] 会先**校验**：
 * 1. 是不是合法 ZIP（不是则 [ImportResult.NotZip]）
 * 2. 里面有没有**至少一个**本项目认识的资源
 *    （没有则 [ImportResult.Unsupported]）
 *
 * # 🔴 安全：Zip Slip
 *
 * ZIP 条目名可能是 `../../etc/passwd` 这种**路径穿越**。
 * 本实现**只取文件名**（`substringAfterLast('/')`），
 * 并且**只提取白名单内的名字** —— 双重防护。
 */
object SkinImporter {

    /** 单个文件大小上限（防 zip bomb）。 */
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024

    /** 解压后总大小上限。 */
    const val MAX_TOTAL_BYTES = 40L * 1024 * 1024

    /**
     * 解压 ZIP 到目标目录。
     *
     * @param zip 用户选中的 `.zip`
     * @param destDir 装扮资源目录（会创建）
     * @return 逻辑资源名 → 落盘文件名
     */
    fun extract(zip: File, destDir: File): ImportResult {
        if (!zip.isFile || zip.length() == 0L) {
            return ImportResult.Invalid("文件为空或不存在")
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            return ImportResult.Invalid("无法创建装扮目录")
        }

        val found = HashMap<String, String>()
        var total = 0L

        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 🔴 只取文件名，丢掉任何目录部分（防 Zip Slip）
                        val rawName = entry.name.substringAfterLast('/')
                        val base = rawName.substringBeforeLast('.', rawName)
                        val ext = rawName.substringAfterLast('.', "").lowercase()

                        val logical = base.lowercase()
                        // 只提取**白名单**内的资源
                        if (logical in SkinResource.SUPPORTED &&
                            ext in SkinResource.IMAGE_EXTENSIONS
                        ) {
                            val bytes = zis.readBytes()
                            total += bytes.size
                            if (bytes.size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                                return ImportResult.Invalid("装扮包过大，已中止导入")
                            }
                            // 用**逻辑名 + 原扩展名**落盘 ——
                            // 避免原文件名里的奇怪字符
                            val outName = "$logical.$ext"
                            File(destDir, outName).writeBytes(bytes)
                            found[logical] = outName
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            // ZIP 损坏 / 不是 ZIP
            return ImportResult.NotZip(e.message ?: "无法读取该文件")
        }

        if (found.isEmpty()) {
            return ImportResult.Unsupported
        }
        return ImportResult.Ok(found)
    }

    /** 导入结果。 */
    sealed interface ImportResult {
        /** 成功，返回逻辑名 → 文件名。 */
        data class Ok(val resources: Map<String, String>) : ImportResult

        /**
         * **不是 ZIP / ZIP 损坏**。
         *
         * 与 [Unsupported] 区分：这个是"文件本身有问题"，
         * 那个是"文件没问题但格式不认"。
         */
        data class NotZip(val detail: String) : ImportResult

        /**
         * 是合法 ZIP，但**里面没有本项目认识的资源**。
         *
         * UI 显示：「当前装扮格式暂不支持」
         */
        data object Unsupported : ImportResult

        /** 其它输入问题（空文件 / 目录创建失败 / 过大）。 */
        data class Invalid(val detail: String) : ImportResult
    }
}
