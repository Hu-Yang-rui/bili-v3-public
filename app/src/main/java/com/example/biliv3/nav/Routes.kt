package com.example.biliv3.nav

import androidx.navigation.NavType

/**
 * 路由表 —— 对应 `AGENTS.md` §3.3。
 *
 * ## 为什么集中在一个文件
 *
 * 路由字符串散落在各页面里时，改名一定会漏改某处，且编译器不报错
 * （拼错的路由在运行时才炸）。集中定义 + 用 `object` 持有参数名，
 * 至少保证**构造路由**和**解析路由**用的是同一份常量。
 *
 * ## 参数约定
 *
 * 路径参数一律 `String`，数字型在目的地内部自行 `toIntOrNull()` 并处理失败 ——
 * 导航层的类型转换失败会直接抛异常，不如自己兜。
 */
object Routes {

    // ---- 主 Tab（底部导航 3 项，AGENTS.md §3.3）----

    const val HOME = "home"
    const val DYNAMIC = "dynamic"
    const val PROFILE = "profile"

    // ---- 二级页 ----

    /** 视频详情 + 播放。参数：bvid。 */
    const val VIDEO_ARG_BVID = "bvid"
    const val VIDEO = "video/{$VIDEO_ARG_BVID}"

    fun video(bvid: String): String = "video/$bvid"

    /** 搜索页。 */
    const val SEARCH = "search"

    /**
     * 分区页。参数：rid + 名称。
     *
     * 名称也要传：分区接口只回 rid，而页面标题需要显示中文名。
     * 让调用方把已知的名称带过来，省一次请求。
     */
    const val CATEGORY_ARG_RID = "rid"
    const val CATEGORY_ARG_NAME = "name"
    const val CATEGORY = "category/{$CATEGORY_ARG_RID}?$CATEGORY_ARG_NAME={$CATEGORY_ARG_NAME}"

    fun category(rid: Int, name: String): String =
        "category/$rid?$CATEGORY_ARG_NAME=${android.net.Uri.encode(name)}"

    /** 扫码登录页。 */
    const val LOGIN = "login"

    /** 历史记录。 */
    const val HISTORY = "history"

    /** 排行榜（全站 + 分区）。 */
    const val RANKING = "ranking"

    /** 番剧 / 影视索引。 */
    const val BANGUMI = "bangumi"

    /**
     * 番剧详情（选集）。
     *
     * ## 为什么番剧需要独立详情页
     *
     * 番剧走 `pgc/player/web/playurl?ep_id=` 取流，与 UGC 的 `bvid`
     * 是**两套体系** —— 硬跳视频详情页会因为 bvid 非法而报错。
     * 这就是首版番剧条目做成 `onItemClick = { }` 空 lambda 的原因
     * （明确的死入口）。
     */
    const val BANGUMI_ARG_SEASON = "seasonId"
    const val BANGUMI_DETAIL = "bangumi/{$BANGUMI_ARG_SEASON}"

    fun bangumiDetail(seasonId: Long): String = "bangumi/$seasonId"

    /** 我的收藏（总览：收藏夹分组）。 */
    const val FAVORITES = "favorites"

    // ---- 收藏夹详情 ----

    const val FAV_FOLDER_ARG_ID = "folderId"
    const val FAV_FOLDER_ARG_TITLE = "folderTitle"
    const val FAV_FOLDER =
        "favorites/{$FAV_FOLDER_ARG_ID}?$FAV_FOLDER_ARG_TITLE={$FAV_FOLDER_ARG_TITLE}"

    /**
     * 构造收藏夹详情路由。
     *
     * 标题也要传：收藏夹接口只回 id，而详情页顶栏要显示名字。
     * 让调用方把已知的名字带过来，省一次请求。
     * 用 `Uri.encode` 编码 —— 收藏夹名字允许含 `/`、`?` 等字符，
     * 不编码会把路径切坏。
     */
    fun favFolder(id: Long, title: String): String =
        "favorites/$id?$FAV_FOLDER_ARG_TITLE=${android.net.Uri.encode(title)}"

    /** 稍后再看。 */
    const val TO_VIEW = "toView"

    /**
     * 用户主页（UP 主空间）。
     *
     * ## 为什么这个路由必须存在
     *
     * 首版**没有它** —— 视频详情页的 UP 头像、评论区头像、
     * 搜索结果里的 UP 条目、私信会话头像**全部不可点**。
     * 没有目标路由，这些头像就只能做成死入口或干脆不挂点击。
     */
    const val SPACE_ARG_MID = "mid"
    const val SPACE = "space/{$SPACE_ARG_MID}"

    fun space(mid: Long): String = "space/$mid"

    /** 离线缓存管理页。 */
    const val DOWNLOADS = "downloads"

    /** 直播列表。 */
    const val LIVE = "live"

    /**
     * 楼中楼详情（某条评论的全部回复）。
     *
     * ## 为什么需要独立页面
     *
     * `reply/wbi/main` 每条评论**最多内嵌 3 条回复**。
     * 首版点「查看全部 N 条回复」只是把已加载的 3 条展开 ——
     * 按钮写着"全部 N 条"却只有 3 条，属于信息不实。
     *
     * 参数：oid（视频 aid）、root（主评论 rpid）。
     */
    const val REPLY_ARG_OID = "oid"
    const val REPLY_ARG_ROOT = "root"
    const val REPLY_ARG_UP = "upMid"
    const val REPLY_DETAIL = "reply/{$REPLY_ARG_OID}/{$REPLY_ARG_ROOT}?$REPLY_ARG_UP={$REPLY_ARG_UP}"

    fun replyDetail(oid: Long, root: Long, upMid: Long): String =
        "reply/$oid/$root?$REPLY_ARG_UP=$upMid"

    /** 设置。 */
    /** 私信会话列表。 */
const val MESSAGES = "messages"

/** 与某个用户的聊天。 */
const val CHAT_ARG_MID = "talkerId"
const val CHAT_ARG_NAME = "talkerName"
const val CHAT = "chat/{$CHAT_ARG_MID}?$CHAT_ARG_NAME={$CHAT_ARG_NAME}"

fun chat(mid: Long, name: String): String =
    "chat/$mid?$CHAT_ARG_NAME=${android.net.Uri.encode(name)}"

const val SETTINGS = "settings"

    /**
     * aicu 查成分（第三方站点 aicu.cc 的聚合查询）。
     *
     * ## 参数为什么做成可选查询参数
     *
     * 两个入口的形态不同：
     * - 用户主页「查成分」→ **带** mid 直达该用户
     * - 首页侧栏 / 我的页 → **不带** mid，进去自己输 UID
     *
     * 做成可选参数（而不是两个路由），是因为页面完全一样，
     * 只差"输入框是否预填"。两个路由会让 `MainShell` 里出现
     * 两份几乎相同的 `composable` 块。
     */
    const val AICU_ARG_UID = "uid"
    const val AICU = "aicu?$AICU_ARG_UID={$AICU_ARG_UID}"

    /** 构造 aicu 路由。[uid] 为 null 时进页面后由用户自己输入。 */
    fun aicu(uid: Long? = null): String =
        if (uid != null && uid > 0L) "aicu?$AICU_ARG_UID=$uid" else "aicu"

    /**
     * 切换账号（多账号管理）。
     *
     * 无参数：列表从加密存储读，页面自己管状态。
     */
    const val ACCOUNTS = "accounts"
}

/**
 * 路径参数声明。
 *
 * `CATEGORY` 里的 `name` 是**可选**查询参数，所以 `nullable = true` +
 * 默认值，否则导航时缺参数会抛 `IllegalArgumentException`。
 */
object RouteArgs {
    val bvid = NavType.StringType
    val rid = NavType.IntType

    val categoryName = NavType.StringType
    const val CATEGORY_NAME_DEFAULT = ""
}
