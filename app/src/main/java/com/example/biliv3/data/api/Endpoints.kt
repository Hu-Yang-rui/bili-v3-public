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
     * 分区最新视频。
     *
     * ## 🔴 2026-10 实测：`dynamic/region` 已下线（v1.4.2 换源）
     *
     * 原值 `x/web-interface/dynamic/region` 现在**恒定返回 `code=-404`**
     * （message「啥都木有」）—— 不是风控、不是参数错，是接口没了。
     * 后果：分区页「最新」**永久失败**，点重试也没用，
     * 用户看到「内容已被删除或不可见」（§11.1 已记录该缺口）。
     *
     * 实测对比（同一 rid=1）：
     * ```
     * x/web-interface/dynamic/region    -> code=-404   ❌ 已下线
     * x/web-interface/region/feed/rcmd  -> code=-400   ❌ 需签名
     * x/web-interface/newlist           -> code=0, 20 条  ✅ 采用
     * ```
     *
     * 换到 `newlist` 的依据（实测确认，不是猜的）：
     * - 返回结构**完全兼容**：`data.archives[]`，字段名与原来一致
     *   （`aid` / `bvid` / `title` / `pic` / `pubdate` / `stat.view` / `owner.*`）
     * - `duration` 是 **Int 秒数**（与 `dynamic/region` 同），
     *   不是 `space/arc/search` 那种 `"12:34"` 字符串 —— 不需要额外解析
     * - 无需签名、`rid` 语义相同
     *
     * ⚠️ 多一个参数：`newlist` 需要 `type=0`（0 = 全部投稿类型）。
     * 少了它也能通，但显式传更稳。
     */
    const val REGION_LATEST = "x/web-interface/newlist"

    /**
     * 分区「热门」列表。
     *
     * 与 [REGION_LATEST] 互补：一个按最新、一个按热度。
     * 分区页顶部提供切换。实测 `ranking/region` 正常（code=0）。
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

    /**
     * **进入直播间上报**（v1.6.3，「隐身入场」的真实落点）。
     *
     * ## 实测（2026-10-06，未登录直连）
     *
     * ```
     * POST xlive/web-room/v1/index/roomEntryAction
     *      room_id=1&ruid=0&platform=web
     *   -> {"code":0,"message":"OK","ttl":1,"data":null}
     *
     * 缺 room_id -> {"code":-400,"message":"请求错误"}
     * GET 方法    -> HTTP 405
     * ```
     *
     * 即：**这是一个真实存在、可用的 POST 接口**，且 `room_id` 是必填。
     *
     * ## 语义
     *
     * 这是「客户端告诉服务端"我进来了"」的**上报**动作：
     * 调用它 = 有入场上报；**不调用它 = 本应用不产生入场上报**。
     * 控制权在本应用手里，所以「隐身入场」是一个**真实可控**的开关。
     *
     * ## v1.6.3 起语义完整（不再有"浏览器"这个漏洞）
     *
     * 直播已改为**应用内播放**（见 `LiveRoomScreen`），不再跳系统浏览器。
     * 所以本应用进入直播间**只有这一个入口**，开关因此无歧义：
     *
     * | 设置 | 行为 |
     * |---|---|
     * | 关（默认） | 进入时上报一次（与官方网页一致） |
     * | 开 | **完全不发**这个请求 |
     *
     * > 此前（跳浏览器版本）这里必须声明"浏览器页面的上报不受控制" ——
     * > 那个限制现在**不存在了**，因为不再离开应用。
     */
    const val LIVE_ENTRY_ACTION = "xlive/web-room/v1/index/roomEntryAction"

    /**
     * 视频 AI 总结（B 站官方，v1.6.3）。
     *
     * ## 🔴 实测（2026-10-06，未登录直连）
     *
     * ```
     * GET x/web-interface/view/conclusion/get?bvid=...&cid=...&up_mid=...
     *   -> {"code":-403,"message":"访问权限不足","ttl":1}
     *
     * POST 同路径 -> HTTP 405（只接受 GET）
     * 只传 bvid    -> 同样 -403
     * ```
     *
     * 对照组：同一次会话里 `x/web-interface/view` 返回 `code=0`，
     * 说明网络与请求头都没问题 —— **-403 是接口本身的权限要求**，
     * 不是被风控拦了。
     *
     * ## 结论（必须如实记录，不猜测）
     *
     * 该接口**需要登录态**，且很可能还要求接口带 WBI 签名
     * （本项目其它 `x/web-interface` 下的接口中，带 `wbi` 段的都需要签名）。
     * 本项目**未登录时拿不到**；已登录时的实际行为**未验证**
     * （本机没有可用的登录态来测）。
     *
     * 因此本项目的策略是：
     * 1. **先试**这个接口（已登录时才有意义）
     * 2. 拿到非 0 code 就**如实降级**到第三方 AI，并把官方失败原因带出来
     * 3. **绝不伪造**"官方总结" —— 第三方生成的内容必须标明来源
     *
     * ## 关于官方 Prompt
     *
     * 已对官方 APK 做过完整的静态检索（见 `AGENTS-P2.md` 的 AI 章节）：
     * 32 个 dex 的 151 万条字符串里，`ai_summary` / `video_summary` /
     * `summary_prompt` / `view/conclusion` **全部 0 命中**，
     * 中英文 Prompt 特征词也全部 0 命中。
     *
     * **结论：该能力在服务端，客户端不含 Prompt。** 所以本项目
     * 自写的 Prompt 明确标注为"项目自定义"，不冒充官方。
     *
     * ⚠️ 写路径通配时**避开** `斜杠 星号` 组合 —— Kotlin 块注释会嵌套，
     * KDoc 里出现它会提前开启嵌套注释，导致整份文件 Unclosed comment
     * （本项目 §8 记录过这个坑，这里刚刚又踩了一次）。
     */
    const val AI_CONCLUSION = "x/web-interface/view/conclusion/get"

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

    // ---- 直播间弹幕 / 房管（v1.6.4）----

    /**
     * 弹幕 WebSocket 接入信息。
     *
     * ## 🔴 实测：**必须带 WBI 签名**（这是本项目此前的认知盲区）
     *
     * ```
     * plain          -> -352
     * + buvid cookie -> -352
     * + buvid + WBI  -> code=0   ✅ token len=244~252
     * ```
     *
     * 返回 `data.token` 与 `data.host_list`（实测 `wss_port=2245`）。
     *
     * ⚠️ 本项目此前以为"直播接口都能匿名直连" —— 那是因为只试过
     * `room_init`（它确实不需要签名）。**别再用单个接口的通断
     * 推断整个域的行为**。
     */
    const val LIVE_DANMU_INFO = "xlive/web-room/v1/index/getDanmuInfo"

    /**
     * 直播间主播 uid。
     *
     * 实测**不需要签名**（`code=0`），比 `getInfoByRoom` 少一层依赖。
     * 用于身份判定（当前 mid == 主播 uid → 主播）。
     */
    const val LIVE_ANCHOR_IN_ROOM = "live_user/v1/UserInfo/get_anchor_in_room"

    /**
     * 直播间信息（含 `room_info.uid` = 主播）。
     *
     * ⚠️ 实测**需要 WBI 签名**，否则 `-352`。
     * 优先用 [LIVE_ANCHOR_IN_ROOM]（不需要签名）。
     */
    const val LIVE_ROOM_INFO = "xlive/web-room/v1/index/getInfoByRoom"

    // ---- 房管操作（v1.6.4）----
    //
    // ## 🔴 能力边界的实测记录（必须保留，否则以后会重复踩）
    //
    // 未登录探测结果（2026-10-06）：
    //
    // | 路径 | 结果 | 判读 |
    // |---|---|---|
    // | `banned_service/v1|v2/Silent/add_silent` | `65530 invalid request` | **存在**，被鉴权拒 |
    // | `banned_service/v1|v2/Silent/del_silent` | 同上 | **存在** |
    // | `banned_service/v1|v2/Silent/kick` | 同上 | **存在** |
    // | `banned_service/v1|v2/Silent/add_black` | 同上 | **存在** |
    // | `banned_service/v1|v2/Silent/del_black` | 同上 | **存在** |
    // | `room/v1/Room/muteUser` / `kickUser` / `addAdmin` | `1000003 方法未找到` | **不存在** |
    //
    // 关键区分：`65530` = 方法存在但被拒；`1000003` = 方法根本不存在。
    // 混用会让排查完全失去方向。
    //
    // ## ⚠️ 未验证的部分（如实说明）
    //
    // - **是否真的能成功**：需要主播/房管账号，本项目**没有**
    // - **禁言时长范围**：未知，UI 不写死断言
    // - **房管名单接口**：试了 10 个路径全部 NOT FOUND → **不做该 UI**

    /** 禁言。 */
    const val LIVE_MUTE = "banned_service/v2/Silent/add_silent"

    /** 解除禁言。 */
    const val LIVE_UNMUTE = "banned_service/v2/Silent/del_silent"

    /** 踢出直播间。 */
    const val LIVE_KICK = "banned_service/v2/Silent/kick"

    /** 加入黑名单。 */
    const val LIVE_BLOCK_ADD = "banned_service/v1/Silent/add_black"

    /** 移出黑名单。 */
    const val LIVE_BLOCK_DEL = "banned_service/v1/Silent/del_black"

    /**
     * **发送直播弹幕**（v1.6.5）。
     *
     * ## 🔴 实测：直播弹幕发送**不是**视频弹幕那个接口
     *
     * | 用途 | 接口 | 说明 |
     * |---|---|---|
     * | **直播**弹幕 | `msg/send`（本常量） | 实测返回「账号未登录」= **存在** |
     * | **视频**弹幕 | `x/v2/dm/post`（主站域名） | 完全另一套 |
     *
     * 实测（2026-10-06，无凭据）：
     * ```
     * POST api.live.bilibili.com/msg/send           -> 账号未登录（存在，需登录）
     * POST api.live.bilibili.com/msg/sendMsg        -> invalid request
     * POST .../xlive/web-room/v1/dM/sendMsg         -> HTTP 404（不存在）
     * ```
     *
     * ## ⚠️ 参数集未在登录态验证
     *
     * 按官方网页端形态：`msg` / `roomid` / `csrf` / `rnd` / `color` /
     * `fontsize` / `mode` / `bubble`。
     * **成功分支本项目未验证过**（没有可用的登录账号）。
     * 所以 UI 只保证「失败时如实报错」，**绝不伪造发送成功**。
     */
    const val LIVE_SEND_MSG = "msg/send"
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
