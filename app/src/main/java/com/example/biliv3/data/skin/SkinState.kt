package com.example.biliv3.data.skin

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局装扮状态（Fake Skin，**未发版**）。
 *
 * ---
 *
 * # 为什么是"全应用唯一"的
 *
 * 装扮影响**底部导航 / 首页顶部 / 我的页 / 强调色** ——
 * 跨多个页面。如果每个页面各自持有，切页就会出现"有的页换了、有的没换"。
 *
 * 所以与 `FavoritesSync` / `PendingShare` 一样：
 * **一个实例挂在 `AppContainer` 上**。
 *
 * # 🔴 它只影响本地 UI
 *
 * - 不调用任何网络接口
 * - 不修改账号真实装扮
 * - **不改变**视频清晰度 / 会员权限（任务书第二十九条：两者完全分离）
 *
 * # 用法
 *
 * ```kotlin
 * val state by container.skinState.state.collectAsStateWithLifecycle()
 * BiliTheme(colors = state.adapter.applyTo(DarkColors)) { … }
 * ```
 */
@Immutable
data class SkinUiState(
    /** 当前装扮（null = 默认）。 */
    val skin: FakeSkin? = null,
    /** 已安装的装扮列表。 */
    val installed: List<FakeSkin> = emptyList(),
    /** 是否正在加载。 */
    val loading: Boolean = true,
    /** 错误（null = 正常）。 */
    val error: String? = null,
    /** 适配器（由 skin 计算，供主题使用）。 */
    val adapter: SkinThemeAdapter = SkinThemeAdapter.DEFAULT,
    /**
     * 已解码的装扮资源（背景图 / 导航图标，**未发版**）。
     *
     * ⚠️ 与 [adapter] 分工不同：
     * - [adapter] 管**颜色**
     * - [assets] 管**图片**
     *
     * 两者都可为空 → UI 走默认渲染（任务书第十条：不显示空白）。
     */
    val assets: SkinAssets = SkinAssets.EMPTY,
) {
    /** 当前装扮名（默认时返回"默认"）。 */
    val currentName: String get() = skin?.name ?: "默认"
}

/**
 * 装扮状态持有者。
 *
 * ⚠️ **启动流程**（任务书第二十四条）：
 * ```
 * selectedSkinId → SkinRepository → 加载本地装扮 → SkinState
 * ```
 * 装扮已被删除时 [SkinRepository.selectedId] 会自动回退默认。
 */
class SkinState(
    private val repo: SkinRepository,
    private val scope: CoroutineScope,
    /** 用于计算适配器的背景色（默认取深色底）。 */
    private val backgroundColor: androidx.compose.ui.graphics.Color,
    /**
     * 用于解码装扮图片资源（背景 / 导航图标）。
     *
     * ⚠️ 由 `AppContainer` 注入 `applicationContext` ——
     * 持有 Activity 会泄漏。
     */
    private val context: android.content.Context,
) {

    private val _state = MutableStateFlow(SkinUiState())
    val state: StateFlow<SkinUiState> = _state.asStateFlow()

    /** 初始化：读选中态 + 列出已安装。 */
    fun load() {
        scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val installed = runCatching { repo.list() }.getOrElse { emptyList() }
            val id = runCatching { repo.selectedId() }.getOrDefault("")
            val skin = installed.firstOrNull { it.id == id }
            _state.value = SkinUiState(
                skin = skin,
                installed = installed,
                loading = false,
                adapter = SkinThemeAdapter.from(skin, backgroundColor),
                // 解码图片资源（失败不抛，各项留 null → UI 用默认）
                assets = runCatching { SkinAssets.load(context, skin ?: FakeSkin.DEFAULT) }
                    .getOrDefault(SkinAssets.EMPTY),
            )
        }
    }

    /**
     * 应用一套装扮（**立即生效**，不需重启）。
     *
     * 任务书第十六条要求"不要要求重启 App"。
     * 因为 `BiliTheme` 的 `colors` 是参数，重组时会自动跟着变。
     */
    fun apply(id: String) {
        scope.launch {
            runCatching { repo.select(id) }
            val installed = runCatching { repo.list() }.getOrElse { emptyList() }
            val skin = installed.firstOrNull { it.id == id }
            _state.value = _state.value.copy(
                skin = skin,
                installed = installed,
                adapter = SkinThemeAdapter.from(skin, backgroundColor),
                assets = runCatching { SkinAssets.load(context, skin ?: FakeSkin.DEFAULT) }
                    .getOrDefault(SkinAssets.EMPTY),
            )
        }
    }

    /** 恢复默认（任务书第二十三条）。 */
    fun restoreDefault() {
        scope.launch {
            runCatching { repo.select("") }
            _state.value = _state.value.copy(
                skin = null,
                adapter = SkinThemeAdapter.DEFAULT,
                // 图片资源一并清掉 → 回到 App 默认背景/图标
                assets = SkinAssets.EMPTY,
            )
        }
    }

    /**
     * 导入一套装扮。
     *
     * @param metaJson 描述 JSON 文本
     * @param fallbackName 目录名兜底
     * @param zip 资源 ZIP（可空）
     */
    suspend fun import(
        metaJson: String,
        fallbackName: String,
        zip: java.io.File?,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val r = repo.import(metaJson, fallbackName, zip)
        if (r is ImportOutcome.Ok) {
            val installed = runCatching { repo.list() }.getOrElse { emptyList() }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(installed = installed)
            }
        }
        r
    }

    /** 删除一套导入的装扮。 */
    fun delete(id: String) {
        scope.launch {
            runCatching { repo.delete(id) }
            // 删的可能是当前选中的 → 重新读一遍（repo 会回退默认）
            val installed = runCatching { repo.list() }.getOrElse { emptyList() }
            val cur = runCatching { repo.selectedId() }.getOrDefault("")
            val skin = installed.firstOrNull { it.id == cur }
            _state.value = _state.value.copy(
                skin = skin,
                installed = installed,
                adapter = SkinThemeAdapter.from(skin, backgroundColor),
            )
        }
    }

    /** 取某套装扮的资源文件（预览用）。 */
    suspend fun resourceFile(id: String, name: String): java.io.File? =
        withContext(Dispatchers.IO) { repo.resourceFile(id, name) }
}
