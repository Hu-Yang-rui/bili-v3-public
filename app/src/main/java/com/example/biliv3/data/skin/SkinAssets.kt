package com.example.biliv3.data.skin

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 已解码的装扮资源（**未发版**）。
 *
 * ---
 *
 * # 为什么单独一层
 *
 * `FakeSkin.resources` 只有**文件名**。UI 要的是能直接画的 [ImageBitmap]。
 * 这层负责"文件名 → 解码后的位图"，并且**只解码一次**
 * （在装扮切换时），而不是每次重组都读盘 + 解码。
 *
 * # 所有字段都可空
 *
 * 任务书第十条：某套装扮没有某个资源时**用当前 App 默认资源，不显示空白**。
 * 所以每个字段为 null 时，UI 会走原来的默认渲染。
 *
 * @param tailBg 底部导航背景
 * @param headBg 首页顶部背景
 * @param headTabBg 首页 Tab 背景
 * @param myselfBg 「我的」页背景
 * @param navIcons 底部导航图标：逻辑名 → 位图
 *   （key 用 [SkinResource.TAIL_ICON_MAIN] 等）
 */
data class SkinAssets(
    val tailBg: ImageBitmap? = null,
    val headBg: ImageBitmap? = null,
    val headTabBg: ImageBitmap? = null,
    val myselfBg: ImageBitmap? = null,
    val navIcons: Map<String, ImageBitmap> = emptyMap(),
) {
    /** 是否什么都没有（用于跳过渲染分支）。 */
    val isEmpty: Boolean
        get() = tailBg == null && headBg == null && headTabBg == null &&
            myselfBg == null && navIcons.isEmpty()

    /** 取某个导航图标。 */
    fun navIcon(name: String): ImageBitmap? = navIcons[name]

    companion object {
        val EMPTY = SkinAssets()

        /**
         * 从装扮加载资源。
         *
         * ## 两个来源
         *
         * | 来源 | 读法 |
         * |---|---|
         * | 内置 | APK assets |
         * | 导入 | `files/fake_skin/imported/<id>/` |
         *
         * ## ⚠️ 解码失败**不抛**
         *
         * 一个损坏的 PNG 不该让整个装扮不可用 —— 那一项留 null，
         * UI 用默认资源。
         */
        suspend fun load(
            context: Context,
            skin: FakeSkin,
        ): SkinAssets = withContext(Dispatchers.IO) {
            if (skin.isDefault) return@withContext EMPTY

            fun read(name: String): ByteArray? {
                val fname = skin.resource(name) ?: return null
                return if (skin.source == SkinSource.Builtin) {
                    runCatching {
                        context.assets.open("fake_skin/${skin.id}/$fname")
                            .use { it.readBytes() }
                    }.getOrNull()
                } else {
                    val dir = File(
                        File(File(context.filesDir, "fake_skin"), "imported"),
                        skin.id,
                    )
                    runCatching { File(dir, fname).takeIf { it.isFile }?.readBytes() }
                        .getOrNull()
                }
            }

            fun decode(name: String): ImageBitmap? {
                val bytes = read(name) ?: return null
                return runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }

            // 导航图标（3 个 Tab × 选中/未选中）
            val icons = HashMap<String, ImageBitmap>()
            for (name in listOf(
                SkinResource.TAIL_ICON_MAIN,
                SkinResource.TAIL_ICON_DYNAMIC,
                SkinResource.TAIL_ICON_MYSELF,
                SkinResource.TAIL_ICON_SELECTED_MAIN,
                SkinResource.TAIL_ICON_SELECTED_DYNAMIC,
                SkinResource.TAIL_ICON_SELECTED_MYSELF,
            )) {
                decode(name)?.let { icons[name] = it }
            }

            SkinAssets(
                tailBg = decode(SkinResource.TAIL_BG),
                headBg = decode(SkinResource.HEAD_BG),
                headTabBg = decode(SkinResource.HEAD_TAB_BG),
                myselfBg = decode(SkinResource.HEAD_MYSELF_SQUARED_BG),
                navIcons = icons,
            )
        }
    }
}
