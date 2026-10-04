package com.example.biliv3.plugin

/**
 * 插件权限。
 *
 * ## 为什么权限是**枚举**而不是字符串
 *
 * 字符串权限会有拼写错误（`"PLAYER_CONTROL"` vs `"PLAYER_CONTROLL"`）——
 * 而且错误是**静默**的：插件申请了错的权限名，检查时不匹配，
 * 表现是"插件功能不工作"而没有任何报错。
 *
 * 枚举让编译器帮忙：写错就是编译错误。
 *
 * ## 危险等级
 *
 * [level] 用于插件安装页的**风险提示** ——
 * 任务书 §18 要求"用户可以拒绝非必要权限"，
 * 那用户首先得知道哪个权限危险。
 */
enum class PluginPermission(
    /** 展示名（中文，给用户看）。 */
    val displayName: String,

    /** 说明（这个权限能做什么）。 */
    val description: String,

    /** 危险等级：越高越需要用户注意。 */
    val level: RiskLevel,
) {
    PLAYER_READ("读取播放状态", "读取当前播放项、进度、播放/暂停状态", RiskLevel.LOW),
    PLAYER_CONTROL("控制播放", "播放、暂停、切歌、跳转进度", RiskLevel.LOW),
    QUEUE_READ("读取播放队列", "读取队列内容与顺序", RiskLevel.LOW),
    QUEUE_WRITE("修改播放队列", "添加、删除、排序队列", RiskLevel.MEDIUM),
    FAVORITES_READ("读取收藏", "读取收藏夹列表与内容", RiskLevel.MEDIUM),
    FAVORITES_WRITE("修改收藏", "移动、取消收藏（会改动 B 站账号数据）", RiskLevel.HIGH),
    HISTORY_READ("读取观看历史", "读取历史记录与进度", RiskLevel.MEDIUM),
    LYRICS_READ("读取歌词", "读取歌词/字幕内容", RiskLevel.LOW),
    DANMAKU_READ("读取弹幕", "读取弹幕内容（可用于过滤/增强）", RiskLevel.MEDIUM),
    NETWORK("网络访问", "发起网络请求（第三方站点）", RiskLevel.HIGH),
    UI_EXTENSION("界面扩展", "在界面中插入自定义入口", RiskLevel.MEDIUM),
    STORAGE("本地存储", "读写插件自己的存储空间（不接触 App 数据）", RiskLevel.LOW),
    ;

    companion object {
        /** 从字符串解析（未知权限返回 null，由调用方决定是拒绝还是忽略）。 */
        fun from(name: String): PluginPermission? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** 风险等级。 */
enum class RiskLevel(val label: String) {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
}

/**
 * 插件类型。
 *
 * ## 四种类型的**能力边界**（任务书 §13–16）
 *
 * | 类型 | 能否执行代码 | 能否联网 | 来源 |
 * |---|---|---|---|
 * | [BUILTIN] | ✅（随 App 编译） | ✅ | App 自身 |
 * | [RULE] | ❌ **只解释 JSON** | ❌ | 用户导入 |
 * | [NATIVE] | ✅（Kotlin 接口） | 按权限 | 开发者 |
 * | [EXTERNAL] | ❌（仅解析预览） | ❌ | 外部包 |
 *
 * ⚠️ [RULE] **绝不能执行任意代码** —— 这是任务书 §15 的硬要求。
 * 规则插件只做"匹配 → 动作"，动作用枚举表达（见 `RuleAction`）。
 */
enum class PluginType(val label: String) {
    /** 内置：随 App 编译，最高性能与权限。 */
    BUILTIN("内置"),

    /** JSON 规则：不执行代码，只做声明式匹配与动作。 */
    RULE("规则"),

    /** 源码级原生插件。 */
    NATIVE("原生"),

    /** 外部插件包（当前阶段仅预览，不执行）。 */
    EXTERNAL("外部"),
}

/**
 * 插件元数据。
 *
 * 字段与任务书 §12 一致，外加 [apiVersion] 与 [abi] ——
 * 它们是**兼容性检查**的依据（§17 要求检查 API 版本与 ABI）。
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    val type: PluginType,
    val permissions: Set<PluginPermission> = emptySet(),

    /**
     * 插件针对的 App 版本范围（如 `">=1.3.0"`）。
     *
     * 空串 = 不限制。解析失败视为"不兼容"（**保守**：
     * 宁可不让装，也不要装上一个会崩的插件）。
     */
    val compatibleAppVersion: String = "",

    /**
     * 插件使用的**插件 API 版本**。
     *
     * 与 [com.example.biliv3.plugin.PluginApi.VERSION] 比较 ——
     * 插件声明的 API 版本高于 App 支持的上限时**必须拒绝加载**
     * （否则插件调用了不存在的 API 会崩）。
     */
    val apiVersion: Int = PluginApi.VERSION,

    /** 是否包含原生代码（`.so`）—— 外部包预览时用于风险提示。 */
    val hasNativeCode: Boolean = false,

    /** 入口标识（原生插件的类名 / 规则插件的文件名）。 */
    val entry: String = "",
)

/**
 * 插件生命周期接口（任务书 §12 的 `BiliV3Plugin`）。
 *
 * ## ⚠️ 所有回调都**不允许抛异常**
 *
 * 实现方若抛异常，宿主会捕获并**自动禁用该插件**（见 `PluginManager`）。
 * 接口注释里写清楚这一点，是为了让插件作者知道
 * "抛异常 = 自己的插件被禁用"，而不是"整个 App 崩"。
 */
interface BiliV3Plugin {

    val metadata: PluginMetadata

    /** 安装（首次导入时调用一次）。 */
    fun onInstall(context: PluginContext) {}

    /** 启用。 */
    fun onEnable(context: PluginContext) {}

    /** 禁用。 */
    fun onDisable(context: PluginContext) {}

    /** 卸载。 */
    fun onUninstall(context: PluginContext) {}

    /**
     * 更新（从旧版本升级时）。
     *
     * @param fromVersion 旧版本号
     */
    fun onUpdate(context: PluginContext, fromVersion: String) {}
}

/**
 * 插件 API 版本常量。
 *
 * 每次**破坏性**改动插件 API 时 +1 —— 插件通过
 * [PluginMetadata.apiVersion] 声明它需要的版本。
 */
object PluginApi {
    /** 当前插件 API 版本。 */
    const val VERSION = 1

    /**
     * 支持的**最低** API 版本。
     *
     * 插件声明低于这个值时也拒绝 —— 例如 v1 移除了 v0 的某个方法，
     * 老插件调用它会崩。
     */
    const val MIN_VERSION = 1
}

/**
 * 插件运行时上下文（**唯一**的宿主能力入口）。
 *
 * ## 🔴 为什么必须隔离（任务书 §15 的红线）
 *
 * **禁止**把下列对象直接暴露给插件：
 *
 * | 禁止暴露 | 原因 |
 * |---|---|
 * | `Context` | 能拿到任意系统服务、文件系统 |
 * | `SESSDATA` / `bili_jct` | 登录凭据，泄露即账号失守（§28） |
 * | Android Keystore | 同上 |
 * | `OkHttpClient`（共享实例） | 挂着 B 站 CookieJar → cookie 外泄给第三方（§4.2） |
 * | 内部 Repository | 绕过权限系统直接读写数据 |
 * | 数据库句柄 | 同上 |
 *
 * 所以插件只能通过本接口的**受控方法**访问能力，
 * 且每个方法都会**先检查权限**。
 *
 * ## 权限检查失败的行为
 *
 * 返回 `null` / `Result.failure` / 空列表 ——
 * **绝不"虽然没申请但内部偷偷给"**（任务书 §18 明确禁止）。
 */
interface PluginContext {

    /** 本插件的元数据。 */
    val metadata: PluginMetadata

    /** 插件自己的私有存储目录（**不是** App 的数据目录）。 */
    val storageDir: String

    /** 已授予的权限。 */
    val grantedPermissions: Set<PluginPermission>

    /** 是否拥有某权限。 */
    fun has(permission: PluginPermission): Boolean =
        permission in grantedPermissions

    /** 写插件日志（会显示在插件详情的"日志"里）。 */
    fun log(message: String)

    // ---- 播放（按权限返回） ----

    /** 读播放状态。需要 [PluginPermission.PLAYER_READ]。 */
    fun playbackSnapshot(): PluginPlaybackSnapshot?

    /** 控制播放。需要 [PluginPermission.PLAYER_CONTROL]。 */
    fun controlPlayback(command: PluginPlaybackCommand): Boolean

    // ---- 队列 ----

    /** 读队列。需要 [PluginPermission.QUEUE_READ]。 */
    fun queueSnapshot(): List<PluginQueueItem>

    /** 修改队列。需要 [PluginPermission.QUEUE_WRITE]。 */
    fun modifyQueue(action: PluginQueueAction): Boolean

    // ---- 弹幕 Hook ----

    /**
     * 弹幕处理前 Hook（对应 pakku 的 `beforeDanmakuProcess`）。
     *
     * 需要 [PluginPermission.DANMAKU_READ]。
     *
     * ⚠️ 必须在**投放前**过滤 —— 否则被屏蔽的弹幕仍占轨道
     * （项目既有原则，见 §3.2）。
     */
    fun onBeforeDanmaku(handler: DanmakuHook)

    /** 弹幕处理后 Hook。 */
    fun onAfterDanmaku(handler: DanmakuHook)
}

/** 播放状态快照（只读，不含任何敏感字段）。 */
data class PluginPlaybackSnapshot(
    val title: String,
    val author: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
)

/** 播放控制命令。 */
enum class PluginPlaybackCommand {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    STOP,
}

/** 队列项（插件视图，**不含** URL 等内部字段）。 */
data class PluginQueueItem(
    val key: String,
    val title: String,
    val author: String,
)

/** 队列修改动作。 */
sealed interface PluginQueueAction {
    data class Add(val item: PluginQueueItem, val toNext: Boolean = false) : PluginQueueAction
    data class Remove(val key: String) : PluginQueueAction
    data object Clear : PluginQueueAction
}

/**
 * 弹幕 Hook。
 *
 * ## 设计依据（pakku.js 的"处理前/处理后"思想）
 *
 * pakku 允许在弹幕处理**前后**执行回调。本接口对应：
 *
 * - `beforeDanmakuProcess` → [PluginContext.onBeforeDanmaku]
 * - `afterDanmakuProcess` → [PluginContext.onAfterDanmaku]
 * - `beforeDanmakuRender` → 由 [DanmakuHook] 的返回值表达
 *   （返回 null = 丢弃，返回改过的文本 = 修改显示）
 */
fun interface DanmakuHook {
    /**
     * 处理一条弹幕。
     *
     * @return 处理后的文本；**返回 null 表示屏蔽该弹幕**
     */
    fun process(danmaku: PluginDanmaku): String?
}

/** 弹幕（插件视图）。 */
data class PluginDanmaku(
    val text: String,
    val timeMs: Long,
    val mode: Int,
    val colorArgb: Int,
    val fontSize: Int,
)
