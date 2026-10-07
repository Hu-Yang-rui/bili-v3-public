package com.example.biliv3.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次性「分享给站内好友」的待发送内容（v1.6.7）。
 *
 * ---
 *
 * # 为什么需要它（用户报告的真实交互问题）
 *
 * 原流程：
 * ```
 * 视频详情 → 分享 → B站好友 → 进私信列表 → 用户自己选人 → 自己粘贴 → 自己点发送
 * ```
 * 用户必须**手动完成三步**，而他的意图从点「B站好友」那一刻就已经明确了：
 * "把这个视频发给某个好友"。
 *
 * 正确流程：
 * ```
 * 视频详情 → 分享 → B站好友 → 选联系人 → 自动带上分享内容 → 自动发送 → 提示成功
 * ```
 *
 * # 为什么用"一次性挂载"而不是导航参数
 *
 * 分享内容（标题 + 链接）可能**很长**，塞进路由 query 会遇到：
 * - URL 编码膨胀，长标题容易超长
 * - 参数在返回栈里留存，用户返回再进会**重复发送**
 *
 * 所以用"**取走即清空**"的一次性容器：消费方读完立刻 `clear()`，
 * 天然防重复。
 *
 * # 🔴 为什么不用持久化存储
 *
 * 它是**瞬时意图**，不是状态：App 被杀掉后这个意图本来就该失效
 * （用户不会期望"上次分享的东西在我重开 App 后自动发出去"）。
 * 所以只放内存。
 *
 * # 线程
 *
 * 只在主线程读写（Compose 事件回调 + ViewModel），
 * 用 `MutableStateFlow` 是为了让消费方能 `collect` 到变化。
 */
class PendingShare {

    private val _pending = MutableStateFlow<Pending?>(null)

    /** 待发送内容；null = 没有。消费方 collect 它。 */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /**
     * 挂载一条待发送内容（分享面板点「B站好友」时调用）。
     *
     * @param title 视频标题（可为空 —— 空则不拼进正文）
     * @param url 视频链接
     */
    fun post(title: String, url: String) {
        if (url.isBlank()) return
        _pending.value = Pending(title = title.trim(), url = url.trim())
    }

    /**
     * 取走（读 + 清空）。
     *
     * ## 为什么是"取走"而不是"读"
     *
     * 防止**重复自动发送**：消费方处理完必须清掉，
     * 否则用户返回上一页再进来会又发一次。
     *
     * ⚠️ 只有**确认要发送**时才调用它。如果只是渲染预览就要清，
     *    那么在发送前用户切走再回来，内容就丢了。
     */
    fun take(): Pending? {
        val cur = _pending.value
        _pending.value = null
        return cur
    }

    /** 丢弃（用户取消了自动发送）。 */
    fun clear() {
        _pending.value = null
    }

    /**
     * 待发送内容。
     *
     * @param title 视频标题（空串 = 不拼标题）
     * @param url 视频链接
     */
    data class Pending(
        val title: String,
        val url: String,
    ) {
        /**
         * 组装成实际发送的正文。
         *
         * ## 格式
         *
         * ```
         * 【标题】
         * https://www.bilibili.com/video/BVxxxx
         * ```
         *
         * 无标题时只发链接（不留下一个空的 `【】`）。
         *
         * ⚠️ 与官方客户端的分享格式**不保证一致** —— 本项目没有抓过
         *    官方客户端发出的分享消息体。这里用的是通用可读格式，
         *    接收方在任何客户端都能正常看到标题与链接。
         */
        val body: String
            get() = if (title.isNotEmpty()) "【$title】\n$url" else url
    }
}
