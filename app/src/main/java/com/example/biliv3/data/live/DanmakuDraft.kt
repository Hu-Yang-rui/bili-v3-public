package com.example.biliv3.data.live

/**
 * 弹幕草稿的长度规则（v1.6.6，**纯函数**）。
 *
 * ---
 *
 * # 为什么要限制
 *
 * B 站直播弹幕有长度上限。**不拦**的后果是：用户辛苦打完一长段，
 * 点发送 → 服务端拒绝 → 只看到一句"发送失败"，还得自己删字重试。
 * 在输入时就截断是更好的体验。
 *
 * # ⚠️ 上限值未经实测
 *
 * 本项目**没有可用登录账号**，无法实测服务端的真实上限，
 * 所以 [MAX_LEN] 是按公开网页端的常见值取的 **20 字符**。
 *
 * 它是**常量**，就是为了将来实测后一处调整。若服务端更严，
 * 以服务端为准（UI 会如实显示服务端返回的错误，不会假装成功）。
 *
 * # 为什么按"字符"而不是"字节"
 *
 * B 站的长度限制按**字符**算（中文一个字算一个）。
 * 用字节算会把 7 个中文当成 21 字节直接截掉 —— 那明显不对。
 *
 * ⚠️ Kotlin 的 `String.length` 是 **UTF-16 code unit** 数，
 * 不是"用户感知的字符"：emoji（如 😂）占 2 个 code unit。
 * 所以用 `length` 计数时，10 个 emoji 会被算成 20 —— 偏保守。
 * 这里**刻意不引入 codePoint 计数**：保守的方向是安全的
 * （少让用户打几个字），而引入码点处理会让这个纯函数复杂到
 * 不值得，且与服务端口径未必一致。
 */
object DanmakuDraft {

    /** 弹幕长度上限（字符）。⚠️ 未经实测，见类文档。 */
    const val MAX_LEN = 20

    /**
     * 把输入钳到上限内。
     *
     * @return 长度不超过 [MAX_LEN] 的文本
     */
    fun clamp(input: String): String =
        if (input.length <= MAX_LEN) input else input.substring(0, MAX_LEN)

    /** 是否已达上限（UI 用来提示）。 */
    fun atLimit(input: String): Boolean = input.length >= MAX_LEN

    /**
     * 剩余可输入字符数（UI 显示 `12/20` 用）。
     *
     * 已超长时返回 0（而不是负数）—— 负数会让 UI 显示 `-3`。
     */
    fun remaining(input: String): Int = (MAX_LEN - input.length).coerceAtLeast(0)

    /**
     * 是否可发送。
     *
     * 规则：**去掉首尾空白后非空**。
     * 纯空格的草稿发出去是一条空弹幕（服务端会拒），
     * 所以这里直接判为不可发送。
     */
    fun canSend(input: String): Boolean = input.isNotBlank()
}
