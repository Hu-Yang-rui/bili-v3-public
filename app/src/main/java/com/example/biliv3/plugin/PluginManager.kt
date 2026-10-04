package com.example.biliv3.plugin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 已安装插件的运行时状态。
 */
data class PluginRuntime(
    val metadata: PluginMetadata,
    /** 是否启用（用户可切换）。 */
    val enabled: Boolean = false,
    /** 来源（"内置" / 文件名）。 */
    val source: String = "内置",

    /** 最近一次执行时间（毫秒时间戳，0 = 从未）。 */
    val lastRunAt: Long = 0L,
    /** 累计执行次数。 */
    val runCount: Long = 0L,
    /** 最近一次执行耗时（毫秒）。 */
    val lastDurationMs: Long = 0L,
    /** 最近一次错误（null = 无错误）。 */
    val lastError: String? = null,
    /** 是否因异常被**自动禁用**。 */
    val autoDisabled: Boolean = false,
    /** 日志（最新的在前，有上限）。 */
    val logs: List<String> = emptyList(),
    /** 解析出的规则（仅 RULE 类型）。 */
    val rules: List<Rule> = emptyList(),
    /** 规则解析错误（显示给用户）。 */
    val ruleErrors: List<String> = emptyList(),
) {
    /** 权限的风险等级（取最高的那个）。 */
    val riskLevel: RiskLevel
        get() = metadata.permissions.maxByOrNull { it.level.ordinal }?.level ?: RiskLevel.LOW

    /**
     * 风险原因（逐条列出，不只是给个等级）。
     *
     * ## ⚠️ 为什么风险原因必须**只有一个来源**
     *
     * 早期版本 UI 里自己重算了一遍风险原因，结果漏掉了 MEDIUM 权限 ——
     * 装机实测出现「风险（中）」下面写着「未发现明显风险」的自相矛盾。
     *
     * 所以风险原因的**唯一真值**在这里，UI 只负责展示。
     */
    val riskReasons: List<String>
        get() = buildList {
            if (metadata.hasNativeCode) {
                add("包含原生代码（.so），可执行机器码 —— 这是最高的风险来源")
            }
            metadata.permissions
                .filter { it.level == RiskLevel.HIGH }
                .forEach { add("高风险权限：${it.displayName}（${it.description}）") }
            metadata.permissions
                .filter { it.level == RiskLevel.MEDIUM }
                .forEach { add("中风险权限：${it.displayName}（${it.description}）") }
            if (metadata.permissions.isEmpty() && !metadata.hasNativeCode) {
                add("未申请任何权限，也不含原生代码")
            }
        }
}

/**
 * 插件管理器。
 *
 * ## 🔴 三条硬性职责（任务书 §20）
 *
 * ### 1. 异常隔离 —— 单个插件崩**不能**让 App 崩
 *
 * 所有插件回调都包在 [guarded] 里：
 * - 捕获 `Throwable`（不只是 `Exception` —— 插件可能抛 `StackOverflowError`）
 * - 记录错误 + 累加计数
 * - **连续失败 N 次自动禁用**（避免每次操作都卡在同一个坏插件上）
 *
 * ### 2. 权限强制 —— 没申请的权限**必须拿不到**
 *
 * 权限检查在 [PluginContext] 的实现里（`HostPluginContext`），
 * 而不是在插件里"自觉遵守"。任务书 §18：
 * 「不能出现『manifest 没声明，但内部偷偷拿到了』」。
 *
 * ### 3. 生命周期管理
 *
 * `install` / `enable` / `disable` / `uninstall` / `update` 五个动作
 * 都必须走这里，不能由插件自己触发。
 *
 * ## 持久化
 *
 * 用**独立的** SharedPreferences（`biliv3_plugins`）——
 * 不与主设置混在一起，避免插件数据污染用户设置。
 * 只存"元数据 + 启用状态"，**不存任何 App 数据**。
 */
class PluginManager(
    private val context: Context,
    /** 宿主能力提供者（由 AppContainer 注入，避免插件直接拿到 Repository）。 */
    private val host: PluginHost,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _plugins = MutableStateFlow<List<PluginRuntime>>(emptyList())
    val plugins: StateFlow<List<PluginRuntime>> = _plugins.asStateFlow()

    /** 已加载的原生插件实例（id → 实例）。 */
    private val instances = LinkedHashMap<String, BiliV3Plugin>()

    /** 每个插件的 `PluginContext`。 */
    private val contexts = LinkedHashMap<String, PluginContext>()

    /** 每个插件的连续失败次数（达阈值自动禁用）。 */
    private val failureCounts = HashMap<String, Int>()

    /**
     * 弹幕 Hook 链（供播放器取用）。
     *
     * 存 `(插件id, hook)` 对而不是裸 `List<DanmakuHook>` ——
     * 因为异常处理与日志需要知道**是哪个插件**的 Hook 出错。
     */
    private val _danmakuHooks = mutableListOf<Pair<String, DanmakuHook>>()

    /** 当前生效的弹幕 Hook（只读）。 */
    val danmakuHooks: List<Pair<String, DanmakuHook>> get() = _danmakuHooks.toList()

    /**
     * 注册内置插件。
     *
     * 内置插件随 App 编译，**默认启用**（它们是 App 自身能力的一部分）。
     * 但仍走同一套权限系统 —— 内置插件也不该有"隐式全权限"。
     */
    fun registerBuiltin(plugin: BiliV3Plugin) {
        val meta = plugin.metadata
        instances[meta.id] = plugin

        val enabled = prefs.getBoolean(keyEnabled(meta.id), true)   // 内置默认开
        val granted = loadGranted(meta.id) ?: meta.permissions

        contexts[meta.id] = HostPluginContext(meta, granted, host, ::appendLog)

        val rt = PluginRuntime(
            metadata = meta,
            enabled = enabled,
            source = "内置",
        )
        _plugins.value = _plugins.value.filterNot { it.metadata.id == meta.id } + rt

        if (enabled) {
            guarded(meta.id) { plugin.onEnable(contexts.getValue(meta.id)) }
        }
    }

    /**
     * 安装规则插件（从 JSON）。
     *
     * @param json 插件 JSON（含 `rules` 数组）
     * @param source 来源描述（文件名）
     * @return 错误信息；null = 成功
     */
    fun installRulePlugin(json: JSONObject, source: String): String? {
        val id = json.optString("id").trim()
        if (id.isEmpty()) return "插件缺少 id"
        if (instances.containsKey(id)) return "插件 $id 已存在"

        val name = json.optString("name").ifEmpty { id }
        val version = json.optString("version").ifEmpty { "1.0.0" }

        // 权限：JSON 里的 permissions 数组
        val permArr = json.optJSONArray("permissions")
        val perms = if (permArr == null) {
            emptySet()
        } else {
            (0 until permArr.length()).mapNotNull { i ->
                PluginPermission.from(permArr.optString(i))
            }.toSet()
        }

        // 兼容性检查（任务书 §17）
        val apiVersion = json.optInt("apiVersion", PluginApi.VERSION)
        if (apiVersion > PluginApi.VERSION) {
            return "插件需要插件 API v$apiVersion，当前 App 只支持到 v${PluginApi.VERSION}"
        }
        if (apiVersion < PluginApi.MIN_VERSION) {
            return "插件 API v$apiVersion 已不受支持"
        }

        val meta = PluginMetadata(
            id = id,
            name = name,
            version = version,
            author = json.optString("author"),
            description = json.optString("description"),
            type = PluginType.RULE,
            permissions = perms,
            compatibleAppVersion = json.optString("appVersion"),
            apiVersion = apiVersion,
            entry = "rules.json",
        )

        // 规则条数上限（防"规则炸弹"）
        val rulesArr = json.optJSONArray("rules")
        if ((rulesArr?.length() ?: 0) > RuleEngine.MAX_RULES) {
            return "规则条数超过上限 ${RuleEngine.MAX_RULES}"
        }

        val parsed = RuleParser.parse(rulesArr)

        val rt = PluginRuntime(
            metadata = meta,
            enabled = false,        // ⚠️ 导入后**默认不启用** ——
                                    // 用户必须先看权限再手动开（§17 的确认流程）
            source = source,
            rules = parsed.rules,
            ruleErrors = parsed.errors,
        )
        _plugins.value = _plugins.value + rt
        contexts[id] = HostPluginContext(meta, perms, host, ::appendLog)
        persist(rt)

        // ⚠️ 必须把**插件定义本身**也存下来。
        // 只存 enabled 标志的话，App 重启后插件就消失了 ——
        // 装机实测确认过这个 bug（重装后插件列表为空）。
        saveDefinition(id, json.toString())
        saveSource(id, source)

        return if (parsed.rules.isEmpty()) "插件已安装，但没有任何有效规则" else null
    }

    /**
     * 从持久化恢复已安装的插件（App 启动时调用）。
     *
     * 与 [installRulePlugin] 的区别：这里**保留**原有的启用状态，
     * 不会把 enabled 重置为 false。
     */
    fun restoreInstalled() {
        val all = prefs.all
        for ((k, v) in all) {
            if (!k.startsWith(DEF_PREFIX)) continue
            val jsonText = v as? String ?: continue
            val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
            val id = json.optString("id").trim()
            if (id.isEmpty() || instances.containsKey(id)) continue

            val name = json.optString("name").ifEmpty { id }
            val permArr = json.optJSONArray("permissions")
            val perms = if (permArr == null) {
                emptySet()
            } else {
                (0 until permArr.length())
                    .mapNotNull { i -> PluginPermission.from(permArr.optString(i)) }
                    .toSet()
            }
            val rulesArr = json.optJSONArray("rules")
            val parsed = RuleParser.parse(rulesArr)

            val meta = PluginMetadata(
                id = id,
                name = name,
                version = json.optString("version").ifEmpty { "1.0.0" },
                author = json.optString("author"),
                description = json.optString("description"),
                type = PluginType.RULE,
                permissions = perms,
                compatibleAppVersion = json.optString("appVersion"),
                apiVersion = json.optInt("apiVersion", PluginApi.VERSION),
                entry = "rules.json",
            )

            val rt = PluginRuntime(
                metadata = meta,
                enabled = prefs.getBoolean(keyEnabled(id), false),
                source = prefs.getString(keySource(id), null) ?: "导入的插件",
                rules = parsed.rules,
                ruleErrors = parsed.errors,
            )
            _plugins.value = _plugins.value + rt
            contexts[id] = HostPluginContext(meta, perms, host, ::appendLog)
        }
        rebuildDanmakuHooks()
    }

    // ---------------- 生命周期 ----------------

    /**
     * 启用插件。
     *
     * @param granted 用户实际授予的权限（**可以少于**申请列表 ——
     *        任务书 §18 要求"用户可以拒绝非必要权限"）
     */
    fun enable(id: String, granted: Set<PluginPermission>? = null): Boolean {
        val rt = find(id) ?: return false
        val perms = granted ?: loadGranted(id) ?: rt.metadata.permissions

        contexts[id] = HostPluginContext(rt.metadata, perms, host, ::appendLog)
        saveGranted(id, perms)

        // ⚠️ `guarded` 返回 `T?`，这里要显式给 Boolean，
        // 否则 `ok` 会被推成 `Any?`（`?: true` 的右操作数是 Boolean）
        val ok: Boolean = instances[id]?.let { plugin ->
            guarded(id) { plugin.onEnable(contexts.getValue(id)); true }
        } ?: true   // 规则插件没有实例，启用总是成功

        update(id) {
            it.copy(
                enabled = ok,
                autoDisabled = false,
                lastError = if (ok) null else "启用失败（详见日志）",
            )
        }
        failureCounts[id] = 0
        persist(find(id))
        return ok
    }

    /** 禁用插件。 */
    fun disable(id: String): Boolean {
        val rt = find(id) ?: return false
        instances[id]?.let { plugin ->
            guarded(id) { plugin.onDisable(contexts[id] ?: return@guarded) }
        }
        update(id) { it.copy(enabled = false) }
        persist(find(id))
        return true
    }

    /** 重新加载（规则插件重新解析；原生插件重新走 disable → enable）。 */
    fun reload(id: String): Boolean {
        val rt = find(id) ?: return false
        if (rt.enabled) {
            disable(id)
            return enable(id)
        }
        return true
    }

    /** 卸载。 */
    fun uninstall(id: String): Boolean {
        val rt = find(id) ?: return false
        instances[id]?.let { plugin ->
            guarded(id) { plugin.onUninstall(contexts[id] ?: return@guarded) }
        }
        instances.remove(id)
        contexts.remove(id)
        failureCounts.remove(id)
        _plugins.value = _plugins.value.filterNot { it.metadata.id == id }
        // 卸载要清掉**全部**持久化痕迹，包括插件定义 ——
        // 漏掉定义的话，下次启动会"复活"一个已卸载的插件
        prefs.edit()
            .remove(keyEnabled(id))
            .remove(keyGranted(id))
            .remove(keySource(id))
            .remove("$DEF_PREFIX$id")
            .apply()
        rebuildDanmakuHooks()
        return true
    }

    /**
     * 更新插件（同 id，新版本）。
     *
     * @return 错误信息；null = 成功
     */
    fun updatePlugin(id: String, json: JSONObject, source: String): String? {
        val old = find(id) ?: return "插件 $id 未安装"
        val newVersion = json.optString("version").ifEmpty { old.metadata.version }
        if (newVersion == old.metadata.version) return "版本号相同，无需更新"

        // 保留用户的启用状态与已授权限（不要因为更新就重置成"全部权限"）
        val wasEnabled = old.enabled
        val granted = loadGranted(id) ?: old.metadata.permissions

        uninstall(id)
        val err = installRulePlugin(json, source)
        if (err != null && !err.startsWith("插件已安装")) return err

        instances[id]?.let { plugin ->
            guarded(id) { plugin.onUpdate(contexts.getValue(id), old.metadata.version) }
        }
        if (wasEnabled) enable(id, granted)
        return null
    }

    // ---------------- 执行 ----------------

    /**
     * 对一条视频执行所有**已启用规则插件**的动作。
     *
     * @return 命中的动作（调用方在**权限检查后**执行）
     */
    fun evaluateRules(subject: RuleEngine.Subject): List<Pair<String, RuleAction>> {
        val out = ArrayList<Pair<String, RuleAction>>()
        for (rt in _plugins.value) {
            if (!rt.enabled) continue
            if (rt.metadata.type != PluginType.RULE) continue
            if (rt.rules.isEmpty()) continue

            val started = System.currentTimeMillis()
            val actions: List<RuleAction>? = guarded(rt.metadata.id) {
                RuleEngine(rt.rules).evaluate(subject)
            }
            if (actions == null) continue

            val cost = System.currentTimeMillis() - started
            recordRun(rt.metadata.id, cost)
            actions.forEach { out.add(rt.metadata.id to it) }
        }
        return out
    }

    /**
     * 运行弹幕 Hook 链。
     *
     * ## ⚠️ 必须在**投放前**调用
     *
     * 项目既有原则（§3.2）：被屏蔽的弹幕若在渲染阶段才过滤，
     * 它**仍然占用轨道** —— 表现为"屏幕上有个空位没有弹幕飘过"。
     *
     * ## 异常语义（重要）
     *
     * Hook 抛异常时**保留原文本**，而不是屏蔽弹幕：
     * - "屏蔽"是插件**明确表达**的意图（返回 null）
     * - "抛异常"是插件**坏了** —— 坏插件不该导致弹幕全部消失
     *
     * 两者混淆会让"插件写错一个空指针 → 视频一条弹幕都没有"。
     * 所以这里用 [runHook] 明确区分"返回 null"与"抛异常"，
     * **不能**靠"结果是不是 null"来推断。
     *
     * @return 处理后的文本；null = 该弹幕被**明确**屏蔽
     */
    fun applyDanmakuHooks(d: PluginDanmaku): String? {
        var text: String? = d.text
        for ((ownerId, hook) in _danmakuHooks) {
            val current = text ?: return null   // 已被前面某个 hook 明确屏蔽
            val started = System.currentTimeMillis()
            val outcome = runHook(ownerId) { hook.process(d.copy(text = current)) }
            recordRun(ownerId, System.currentTimeMillis() - started)

            text = when (outcome) {
                is HookOutcome.Ok -> outcome.value      // 可能是 null = 明确屏蔽
                is HookOutcome.Failed -> current        // 插件坏了 → 保留原弹幕
            }
        }
        return text
    }

    /** Hook 执行结果：区分"正常返回 null"与"抛异常"。 */
    private sealed interface HookOutcome {
        data class Ok(val value: String?) : HookOutcome
        data object Failed : HookOutcome
    }

    private inline fun runHook(id: String, block: () -> String?): HookOutcome = try {
        HookOutcome.Ok(block())
    } catch (t: Throwable) {
        val msg = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(200)}"
        appendLog(id, "弹幕 Hook 失败 — $msg")
        update(id) { it.copy(lastError = msg) }
        bumpFailure(id)
        HookOutcome.Failed
    }

    // ---------------- 内部 ----------------

    /**
     * **异常隔离包装器**（本类最核心的方法）。
     *
     * 任务书 §20：「单个插件崩溃不能导致整个 App 崩溃」。
     *
     * 注意捕获的是 `Throwable` 而不是 `Exception`：
     * 插件可能抛 `StackOverflowError`（递归）或 `OutOfMemoryError`
     * （构造大数组），它们都不是 `Exception`。
     * 虽然 `OOM` 通常救不回来，但**至少记录**，
     * 让用户知道是哪个插件干的。
     *
     * @return 回调结果；失败返回 null（调用方应提供 fallback）
     */
    private fun <T> guarded(id: String, fallback: T? = null, block: () -> T): T? {
        return try {
            block()
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(200)}"
            appendLog(id, "执行失败 — $msg")
            update(id) { it.copy(lastError = msg) }
            bumpFailure(id)
            fallback
        }
    }

    /**
     * 累加失败次数，达阈值自动禁用。
     *
     * 抽成独立方法：`guarded` 与 [runHook] 共用同一套阈值逻辑 ——
     * 两处各写一份的话，改阈值时会漏掉一处。
     */
    private fun bumpFailure(id: String) {
        val count = (failureCounts[id] ?: 0) + 1
        failureCounts[id] = count
        if (count >= MAX_FAILURES) {
            appendLog(id, "连续失败 $count 次，已自动禁用")
            update(id) { it.copy(enabled = false, autoDisabled = true) }
        }
    }

    private fun recordRun(id: String, durationMs: Long) {
        update(id) {
            it.copy(
                runCount = it.runCount + 1,
                lastRunAt = System.currentTimeMillis(),
                lastDurationMs = durationMs,
            )
        }
    }

    private fun appendLog(id: String, message: String) {
        update(id) {
            // 日志有上限：否则长时间运行会把内存吃满
            val next = (listOf(message) + it.logs).take(MAX_LOGS)
            it.copy(logs = next)
        }
    }

    private fun find(id: String): PluginRuntime? =
        _plugins.value.firstOrNull { it.metadata.id == id }

    private fun update(id: String, transform: (PluginRuntime) -> PluginRuntime) {
        _plugins.value = _plugins.value.map { if (it.metadata.id == id) transform(it) else it }
    }

    /**
     * 重建弹幕 Hook 链。
     *
     * 在插件启用 / 禁用 / 卸载后调用 ——
     * 移除**已不启用**插件注册的 Hook。
     *
     * ⚠️ 必须在这些时机调用：否则禁用插件后它的 Hook 还在跑，
     * 表现为"我明明关了这个插件，弹幕还是被它改了"。
     */
    fun rebuildDanmakuHooks() {
        val alive = _plugins.value.filter { it.enabled }.map { it.metadata.id }.toSet()
        _danmakuHooks.removeAll { (ownerId, _) -> ownerId !in alive }
    }

    /** 供 `HostPluginContext` 注册 Hook。 */
    internal fun registerDanmakuHook(id: String, hook: DanmakuHook) {
        // 同一插件重复注册时替换（避免启用两次后 Hook 跑两遍）
        _danmakuHooks.removeAll { (ownerId, _) -> ownerId == id }
        _danmakuHooks.add(id to hook)
    }

    // ---------------- 持久化 ----------------

    private fun persist(rt: PluginRuntime?) {
        rt ?: return
        prefs.edit().putBoolean(keyEnabled(rt.metadata.id), rt.enabled).apply()
    }

    /** 存插件定义（原始 JSON），用于 App 重启后恢复。 */
    private fun saveDefinition(id: String, json: String) {
        prefs.edit().putString("$DEF_PREFIX$id", json).apply()
    }

    /** 存来源（文件名），用于重启后恢复展示。 */
    private fun saveSource(id: String, source: String) {
        prefs.edit().putString(keySource(id), source).apply()
    }

    private fun saveGranted(id: String, perms: Set<PluginPermission>) {
        prefs.edit().putStringSet(keyGranted(id), perms.map { it.name }.toSet()).apply()
    }

    private fun loadGranted(id: String): Set<PluginPermission>? {
        val raw = prefs.getStringSet(keyGranted(id), null) ?: return null
        return raw.mapNotNull { PluginPermission.from(it) }.toSet()
    }

    private fun keyEnabled(id: String) = "enabled_$id"
    private fun keyGranted(id: String) = "granted_$id"
    private fun keySource(id: String) = "source_$id"

    companion object {
        private const val PREFS = "biliv3_plugins"

        /** 插件定义的键前缀（存原始 JSON，用于重启恢复）。 */
        private const val DEF_PREFIX = "def_"

        /** 连续失败多少次自动禁用。 */
        const val MAX_FAILURES = 3

        /** 每个插件保留的日志条数。 */
        const val MAX_LOGS = 50
    }
}

/**
 * 宿主能力提供者。
 *
 * ## 为什么用接口而不是直接传 AppContainer
 *
 * 任务书 §15 禁止把内部 Repository 直接暴露给插件。
 * 这里让 `AppContainer` 实现本接口的**受控子集** ——
 * 插件能做的事被**编译期**限制在这个接口的方法里，
 * 而不是靠"记得不要调那个方法"。
 */
interface PluginHost {
    /** 播放状态（由 PlaybackController 提供）。 */
    fun playbackSnapshot(): PluginPlaybackSnapshot?

    /** 执行播放控制。 */
    fun controlPlayback(command: PluginPlaybackCommand): Boolean

    /** 队列快照。 */
    fun queueSnapshot(): List<PluginQueueItem>

    /** 修改队列。 */
    fun modifyQueue(action: PluginQueueAction): Boolean

    /** 插件存储目录（每个插件独立子目录）。 */
    fun storageDirFor(pluginId: String): File
}

/**
 * 宿主的 [PluginContext] 实现 —— **权限检查的唯一关口**。
 *
 * ## 设计原则
 *
 * 每个方法**第一行**就是 `if (!has(...)) return ...`。
 * 没有"默认允许"，也没有"内置插件例外"。
 */
private class HostPluginContext(
    override val metadata: PluginMetadata,
    override val grantedPermissions: Set<PluginPermission>,
    private val host: PluginHost,
    private val logger: (String, String) -> Unit,
) : PluginContext {

    override val storageDir: String
        get() = host.storageDirFor(metadata.id).absolutePath

    override fun log(message: String) {
        logger(metadata.id, message)
    }

    override fun playbackSnapshot(): PluginPlaybackSnapshot? {
        if (!has(PluginPermission.PLAYER_READ)) return null
        return host.playbackSnapshot()
    }

    override fun controlPlayback(command: PluginPlaybackCommand): Boolean {
        if (!has(PluginPermission.PLAYER_CONTROL)) return false
        return host.controlPlayback(command)
    }

    override fun queueSnapshot(): List<PluginQueueItem> {
        if (!has(PluginPermission.QUEUE_READ)) return emptyList()
        return host.queueSnapshot()
    }

    override fun modifyQueue(action: PluginQueueAction): Boolean {
        if (!has(PluginPermission.QUEUE_WRITE)) return false
        return host.modifyQueue(action)
    }

    override fun onBeforeDanmaku(handler: DanmakuHook) {
        if (!has(PluginPermission.DANMAKU_READ)) return
        (host as? PluginManager)?.registerDanmakuHook(metadata.id, handler)
    }

    override fun onAfterDanmaku(handler: DanmakuHook) {
        onBeforeDanmaku(handler)
    }
}
