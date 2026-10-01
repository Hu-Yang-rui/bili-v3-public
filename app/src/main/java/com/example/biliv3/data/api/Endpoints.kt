package com.example.biliv3.data.api

/**
 * 所有端点集中在此。**接口一旦变动，只改这个文件。**
 */
object Endpoints {
    const val API_HOST = "api.bilibili.com"
    const val BASE_URL = "https://api.bilibili.com/"
    const val WEB_ORIGIN = "https://www.bilibili.com"

    // ---- 无需签名 ----

    /** 取 wbi_img。未登录返回 code=-101，但 data.wbi_img 照样有。 */
    const val NAV = "x/web-interface/nav"

    /** 首次启动拿 buvid3/buvid4，显著降低风控命中率。 */
    const val FINGER_SPI = "x/frontend/finger/spi"

    /** 热门榜（无需签名）。 */
    const val RANKING = "x/web-interface/ranking/v2"

    /** 分区排行榜（rid：1=动画 3=音乐 4=游戏 …）。 */
    const val REGION_RANKING = "x/web-interface/ranking/region"

    /** 热搜榜。 */
    const val SEARCH_SQUARE = "x/web-interface/search/square"

    /**
     * 搜索联想词。
     *
     * ## ⚠️ 这里**只写路径**，不能带域名
     *
     * `BiliApi.getRaw` 会自行拼 `https://{host}/{path}`，
     * 而调用方已经把 `host` 传成 `s.search.bilibili.com`。
     *
     * 曾经写成 `"s.search.bilibili.com/main/suggest"`，结果拼出：
     * ```
     * https://s.search.bilibili.com/s.search.bilibili.com/main/suggest
     * ```
     * 该 URL 返回 HTTP 200 但 body 是 `{}` —— **不报错，静默无结果**，
     * 极难排查。实测对比：
     * ```
     * 错误 URL  -> len=2     "{}"
     * 正确 URL  -> len=1503  10 条真实联想词
     * ```
     */
    const val SEARCH_SUGGEST = "main/suggest"

    /** 搜索联想的域名。 */
    const val SEARCH_SUGGEST_HOST = "s.search.bilibili.com"

    /** 首页 Banner 运营位。 */
    const val BANNER = "x/web-show/res/loc"

    // ---- 需要 WBI 签名 ----

    /** 首页推荐流。 */
    const val RCMD_FEED = "x/web-interface/wbi/index/top/feed/rcmd"

    /** 视频详情。 */
    const val VIDEO_VIEW = "x/web-interface/wbi/view"

    /**
     * UP 主粉丝数。
     *
     * ⚠️ `view` 接口的 `owner` 只有 mid/name/face，**没有粉丝数**。
     * 粉丝数要单独打这个接口（实测 data.follower 可用）。
     */
    const val RELATION_STAT = "x/relation/stat"

    /** 播放器信息（含 data.online_count = 当前在看人数）。 */
    const val PLAYER_V2 = "x/player/v2"

    /** 取流。 */
    const val PLAY_URL = "x/player/wbi/playurl"

    /** 综合搜索。 */
    const val SEARCH_TYPE = "x/web-interface/wbi/search/type"

    /** 相关推荐（详情页下方）。无需签名。 */
    const val RELATED = "x/web-interface/archive/related"

    // ---- 互动（写操作：POST 表单 + csrf）----

    /** 互动状态：是否已点赞/投币/收藏。未登录返回 -101。 */
    const val RELATION = "x/web-interface/archive/relation"

    /** 点赞 / 取消点赞。 */
    const val LIKE = "x/web-interface/archive/like"

    /** 投币。 */
    const val COIN_ADD = "x/web-interface/coin/add"

    /** 收藏 / 取消收藏。 */
    const val FAV_DEAL = "x/v3/fav/resource/deal"

    /** 收藏夹列表（取默认收藏夹 id）。 */
    const val FAV_FOLDERS = "x/v3/fav/folder/created/list-all"

    /** 分享上报（仅埋点）。 */
    const val SHARE_ADD = "x/web-interface/share/add"

    // ---- 关注 / 用户主页 ----

    /**
     * 用户主页资料。
     *
     * ## ⚠️ 三条路径的实测差异（这个坑必须记下来）
     *
     * | 路径 | 实测结果 |
     * |---|---|
     * | `x/space/wbi/acc/info` | ❌ `-352` 风控（需签名，且风控最严） |
     * | `x/space/acc/info` | ❌ `-799` 需要签名 |
     * | `x/web-interface/card` | ✅ 可用，且**未登录也能读** |
     *
     * 前两条是"看起来最标准"的路径，但都会失败。
     * 所以用户主页资料走 [USER_CARD]（`x/web-interface/card?mid=`）。
     */
    const val USER_CARD = "x/web-interface/card"

    /**
     * UP 主投稿列表。
     *
     * `x/space/wbi/arc/search` 需要签名且风控敏感；
     * `x/space/arc/search` 同样要签名。这里用签名版并做失败静默
     * （投稿列表是增强模块，失败时页面仍显示资料与关注按钮）。
     */
    const val SPACE_ARC_SEARCH = "x/space/wbi/arc/search"

    /**
     * 关注 / 取关。
     *
     * `act=1` 关注、`act=2` 取关。需要 csrf。
     */
    const val RELATION_MODIFY = "x/relation/modify"

    /** 与某用户的关系（是否已关注）。 */
    const val RELATION_QUERY = "x/relation"

    // ---- 动态 ----

    /**
     * 动态流。
     *
     * `x/polymer/web-dynamic/v1/feed/all` 是**主站 Web 接口**，
     * 未登录返回 `-101`（动态依赖关注列表，必须登录）。
     */
    const val DYNAMIC_FEED = "x/polymer/web-dynamic/v1/feed/all"

    /** 用户空间动态（用户主页的「动态」Tab）。 */
    const val SPACE_DYNAMIC = "x/polymer/web-dynamic/v1/feed/space"

    // ---- 分区 / 排行榜 ----

    /**
     * 分区最新/热门视频。
     *
     * `x/web-interface/dynamic/region` 返回该分区**最新**投稿，
     * 无需签名、风控宽松 —— 适合做分区页主内容源。
     */
    const val REGION_DYNAMIC = "x/web-interface/dynamic/region"

    /**
     * 分区「热门」列表。
     *
     * 与 [REGION_DYNAMIC] 互补：一个按最新、一个按热度。
     * 分区页顶部提供切换。
     */
    const val REGION_HOT = "x/web-interface/ranking/region"

    // ---- 番剧详情 ----

    /**
     * 番剧详情（含分集）。
     *
     * `pgc/view/web/season?season_id=` 是 Web 端番剧详情接口，
     * 返回 `episodes[]`（每集有独立 `ep_id` 与 `cid`）与 `season_id`。
     *
     * ## ⚠️ 番剧取流与 UGC 是两套体系
     *
     * 番剧走 `pgc/player/web/playurl?ep_id=`，**不接受 bvid**。
     * 这就是为什么番剧条目在首版里不可点（硬跳 UGC 详情页会因为
     * bvid 非法而报错）。
     */
    const val PGC_SEASON = "pgc/view/web/season"

    /** 追番 / 取消追番（`season_id` + `status`）。 */
    const val PGC_FOLLOW = "pgc/app/follow"

    /**
     * 某条评论的全部回复（楼中楼）。
     *
     * `x/v2/reply/reply?oid=&type=1&root={rpid}&ps=20&pn=1`
     *
     * ## 为什么需要它（「查看全部 N 条回复」此前是半死入口）
     *
     * `reply/wbi/main` 每条评论**最多内嵌 3 条回复**，超过的部分
     * 只能靠这个接口拉。首版点「查看全部」只是把已加载的 3 条展开
     * （注释里也承认了这一点）—— 用户点完还是只有 3 条，
     * 而标题写着"全部 N 条"，属于信息不实。
     */
    const val REPLY_DETAIL = "x/v2/reply/reply"

    /** 番剧取流（`ep_id`）。 */
    const val PGC_PLAY_URL = "pgc/player/web/playurl"

    // ---- 直播 ----

    /**
     * 直播间播放地址。
     *
     * `api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo`
     * 返回多协议多清晰度的流地址。第三方客户端**只取 HTTP-FLV / HLS**
     * （RTMP 需要另一套播放器）。
     */
    const val LIVE_PLAY_INFO = "xlive/web-room/v2/index/getRoomPlayInfo"

    // ---- 播放进度上报 ----

    /**
     * 上报播放进度（写入服务端历史）。
     *
     * `x/v2/history/report` 需要 csrf。B 站靠它实现"多端续播"。
     */
    const val HISTORY_REPORT = "x/v2/history/report"

    // ---- 稍后再看（删除）----

    /** 从稍后再看移除。 */
    const val TO_VIEW_DEL = "x/v2/history/toview/del"

    // ---- 登录 ----

    /**
     * 官方登录页（WebView 用）。
     *
     * ## 为什么用 WebView 而不是直连登录 API
     *
     * 实测（2026-09-28）`x/passport-login/captcha` 返回 **`geetest = true`**，
     * 即 B 站当前对短信 / 密码登录**强制要求极验图形验证**。
     *
     * 极验的 token 由极验服务端签发，本地算不出来 —— 想"绕过"只能复现
     * 其加密逻辑（每次改版失效）或接打码平台（付费且违反 ToS）。
     * 两条路都是"今天能用明天崩"。
     *
     * 而 WebView 加载官方页面是**用官方流程**，不是攻击它：
     * - B 站改风控**不受影响**（页面由官方维护）
     * - 扫码 / 手机号 / 密码 / 第三方登录**一次全覆盖**
     * - 用户自己在页面里过极验，我们只负责收割 cookie
     *
     * 这也是绝大多数第三方客户端的做法。
     */
    const val LOGIN_PAGE = "https://passport.bilibili.com/login"

    /**
     * 登录成功后的落点。
     *
     * B 站登录成功会跳到主站，用 URL 变化作为"登录完成"的信号之一
     * （另一个信号是 cookie 里出现了 `SESSDATA`）。
     */
    const val LOGIN_SUCCESS_HOST = "www.bilibili.com"

    /** 登录态查询（用于启动时判断是否已登录）。 */
    const val NAV_LOGIN = "x/web-interface/nav"

    /**
     * 扫码登录：生成二维码。域名 `passport.bilibili.com`。
     *
     * 保留 API 直连扫码的实现（实测可用、不受极验影响），
     * 作为 WebView 之外的一条独立路径 —— WebView 加载慢、
     * 且部分机型 WebView 内核异常时扫码 API 是可靠兜底。
     */
    const val PASSPORT_HOST = "passport.bilibili.com"
    const val QRCODE_GENERATE = "x/passport-login/web/qrcode/generate"
    const val QRCODE_POLL = "x/passport-login/web/qrcode/poll"

    /**
     * 以下三个为**实验性**保留。
     *
     * ⚠️ 实测它们当前会失败：B 站要求极验（`captcha` 返回 `geetest=true`），
     * 而极验 token 无法本地生成。参数与 cookie 落库链路本身是对的，
     * 一旦 B 站放宽风控即可生效。
     */
    const val LOGIN_KEY = "x/passport-login/web/key"
    const val SMS_SEND = "x/passport-login/web/sms/send"
    const val SMS_LOGIN = "x/passport-login/web/login/sms"
    const val PWD_LOGIN = "x/passport-login/web/login"

    // ---- 直播（不同域名，单独处理）----

    /** 直播列表。域名 `api.live.bilibili.com`。 */
    const val LIVE_LIST_HOST = "api.live.bilibili.com"
    const val LIVE_LIST = "xlive/web-interface/v1/second/getList"
}

/**
 * 统一请求头。
 *
 * ⚠️ **不要混用 App API（`app.bilibili.com`）和 Web API。**
 * App 接口用 `appkey + appsec` 另一套签名，混用会让指纹不一致、风控更易命中。
 * **全程 Web API + 桌面 UA。**
 */
object BiliHeaders {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 普通 API 请求头。 */
    fun api(): Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Referer" to "$WEB_ORIGIN/",
        "Origin" to WEB_ORIGIN,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    /**
     * 媒体请求头。
     *
     * **必须带 `Referer: https://www.bilibili.com`**，否则 CDN 直接 403。
     * 这是播放黑屏最常见的原因，且报错完全不指向 Referer。
     */
    fun media(): Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Referer" to WEB_ORIGIN,
        "Origin" to WEB_ORIGIN,
    )

    private const val WEB_ORIGIN = "https://www.bilibili.com"
}
