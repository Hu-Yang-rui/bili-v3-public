package com.example.biliv3.data.model

/**
 * 一条评论。
 *
 * 来自 `x/v2/reply/wbi/main`（实测 `code=0` 可用）。
 *
 * ## 为什么回复内嵌在主评论里
 *
 * B 站评论接口把前几条回复直接放在主评论的 `replies` 字段里（而非单独请求）。
 * 这样一次请求就能渲染出"主评论 + 若干条回复"的完整样子，
 * 省掉 N 次请求。更多回复才需要点开二级。
 */
data class CommentItem(
    val rpid: Long,
    val oid: Long,
    val mid: Long,
    val userName: String,
    val userFace: String,
    /** 评论内容。 */
    val content: String,
    /** 点赞数。 */
    val likeCount: Int,
    /** 回复数（总数，不只内嵌的）。 */
    val replyCount: Int,
    /** 发布时间（Unix 秒）。 */
    val ctime: Long,
    /** 楼层。 */
    val floor: Int,
    /** 是否 UP 主本人的评论。 */
    val isUp: Boolean,
    /**
     * 当前用户是否已点赞这条评论。
     *
     * 来自接口的 `action` 字段（1 = 已赞）。未登录恒为 false。
     */
    val liked: Boolean = false,
    /**
     * 是否是**当前用户自己**发的评论。
     *
     * 用于决定要不要显示「删除」入口 —— 删除别人的评论没有意义
     * （接口也会拒绝），显示出来只会让用户困惑。
     *
     * 判定方式：`mid == 当前登录用户 mid`（见 CommentRepository）。
     */
    val isOwn: Boolean = false,
    /** 内嵌的前几条回复。 */
    val replies: List<CommentItem> = emptyList(),
    /**
     * IP 属地，如 `陕西`。
     *
     * ## 数据来源
     *
     * 接口的 `reply_control.location` 字段。实测**只在登录态返回**：
     *
     * ```
     * 未登录：reply_control = {"max_line":6,"time_desc":"1小时前发布",...}
     * 已登录：reply_control = {...,"location":"IP属地：陕西",...}
     * ```
     *
     * ## ⚠️ 接口给的是**已格式化的整串**，不是纯省份名
     *
     * 原始值形如 `"IP属地：陕西"`（含前缀和全角冒号）。
     * 这里**剥掉前缀只存省份**，理由：
     * - 前缀属于**展示层**，不该固化进数据模型
     * - 若将来 B 站改文案（如改成「IP：陕西」），模型层不用动
     * - 空值判断更干净（空串 = 无属地）
     *
     * 展示时由 UI 重新拼前缀，见 `CommentSection`。
     *
     * 空串 = 接口未返回（未登录 / 该条评论无属地），UI 应**隐藏**该字段。
     */
    val ipLocation: String = "",
) {
    /** 头像地址。走统一构造（含 http→https + 剥离已有后缀防双后缀）。 */
    fun faceUrl(size: Int = 96): String = CoverUrls.avatar(userFace, size)

    /** 是否有可见回复。 */
    val hasReplies: Boolean get() = replies.isNotEmpty()

    /** 是否有可展示的 IP 属地。 */
    val hasIpLocation: Boolean get() = ipLocation.isNotBlank()
}

/** 评论列表 + 分页信息。 */
data class CommentPage(
    val comments: List<CommentItem>,
    /** 总数（接口给的近似值）。 */
    val total: Int,
    /** 下一页游标。0 表示没有下一页。 */
    val nextCursor: Long,
    /** 是否已到底。 */
    val isEnd: Boolean,
)
