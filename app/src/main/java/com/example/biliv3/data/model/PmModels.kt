package com.example.biliv3.data.model

/**
 * 私信会话（会话列表的一项）。
 *
 * 来自 `api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions`。
 * 实测未登录返回 `-101`，说明接口可用、只差登录态。
 *
 * ## 字段命名注意
 *
 * 接口里是 snake_case，且"对方"信息在 `last_msg` 里重复了一份。
 * 这里取 `talker_id` + 会话级字段，`last_msg` 只用于预览文本与时间。
 */
data class PmSession(
    /** 会话对方 mid。 */
    val talkerId: Long,
    /** 对方昵称。 */
    val talkerName: String,
    /** 对方头像。 */
    val talkerFace: String,
    /** 最后一条消息的预览文本。 */
    val lastMessage: String,
    /** 最后一条消息时间（Unix 秒）。 */
    val lastTime: Long,
    /** 未读数。 */
    val unreadCount: Int,
    /** 是否已置顶。 */
    val isPinned: Boolean = false,
) {
    /** 头像地址（走统一构造）。 */
    fun faceUrl(size: Int = 96): String = CoverUrls.avatar(talkerFace, size)
}

/**
 * 一条私信消息。
 *
 * 来自 `session_svr/v1/session_svr/get_msgs`。
 *
 * ## ⚠️ 内容藏在 JSON 字符串里
 *
 * 接口的 `content` 字段是**字符串化的 JSON**（形如
 * `{"content":"你好"}`），不是嵌套对象 —— 需要二次解析。
 * 这是 B 站私信接口的一个真实坑：直接当对象取会恒为空串。
 *
 * @param senderId 发送者 mid（与自己比对可判断消息方向）
 */
data class PmMessage(
    val msgKey: Long,
    val senderId: Long,
    /** 已解析出的纯文本内容（图片消息为空）。 */
    val text: String,
    /** 时间（Unix 秒）。 */
    val timestamp: Long,
    /** 自己发的（用于左右气泡对齐）。 */
    val isMine: Boolean,
    /**
     * 消息类型（接口原样保留）。v1.5.3 新增。
     *
     * ## 实测取值（真实账号，扫 20 个会话共 148 条）
     *
     * | 值 | 含义 | content 结构 |
     * |---|---|---|
     * | `1` | 文字 | `{"content":"你好"}` |
     * | `2` | **图片** | `{"url":"https://message.biliimg.com/...jpg","height":1138,"width":850}` |
     * | `10` | 系统通知 | 登录提醒等 |
     *
     * ⚠️ 官方还有 `5`（撤回）/ `6`（分享卡片）/ `7`（视频卡片）等，
     * 但**本次样本里没出现** —— 不猜，遇到未知类型按"不支持"处理并明示。
     */
    val msgType: Int = 1,
    /**
     * 图片消息的 URL（`msgType == 2` 时有效）。v1.5.3 新增。
     *
     * 实测域名 `message.biliimg.com`，**必须走 `CoverUrls` 统一处理**
     * （补 https: 前缀），否则 Coil 加载失败。
     */
    val imageUrl: String = "",
    /** 图片原始宽高（用于按比例占位，避免加载时布局跳动）。 */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
) {
    /**
     * 是否**真正不支持**的消息（既无文本也无图）。
     *
     * ⚠️ 原实现是 `text.isEmpty()` —— 这会把**图片消息也判成不支持**
     * （图片消息的 text 本来就是空的）。修法：按 `msgType` 判，
     * 并且**只有既没文本又没图**才算不支持。
     */
    val isUnsupported: Boolean get() = text.isEmpty() && imageUrl.isEmpty()

    /** 是否为图片消息。 */
    val isImage: Boolean get() = msgType == 2 && imageUrl.isNotEmpty()

    /**
     * 图片按比例算出的显示宽高比（宽/高）。
     *
     * 拿不到尺寸时返回 `4f/3f`（B 站图片常见比例），
     * 保证 UI 有确定的占位高度、不会加载后跳动。
     */
    val imageAspect: Float
        get() = if (imageWidth > 0 && imageHeight > 0) {
            imageWidth.toFloat() / imageHeight.toFloat()
        } else {
            4f / 3f
        }
}

/** 私信会话列表 + 分页。 */
data class PmSessionPage(
    val sessions: List<PmSession>,
    /** 是否还有更多（接口用 `has_more` 标志）。 */
    val hasMore: Boolean,
)

/** 私信消息列表 + 分页。 */
data class PmMessagePage(
    val messages: List<PmMessage>,
    /** 更早的消息游标（`min_seqno`）。用于上拉加载历史。 */
    val minSeqno: Long,
    /** 是否还有更早的消息。 */
    val hasMore: Boolean,
)

/** 未读数汇总。 */
data class PmUnread(
    /** 私信未读。 */
    val message: Int,
    /** 回复我的未读。 */
    val reply: Int,
    /** @我的未读。 */
    val at: Int,
    /** 收到的赞未读。 */
    val like: Int,
) {
    /** 私信总未读（用于红点）。 */
    val total: Int get() = message
}
