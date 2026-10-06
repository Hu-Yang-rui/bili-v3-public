package com.example.biliv3.data.meme

/**
 * 烂梗库的数据模型（v1.6.5）。
 *
 * ## 内容来源
 *
 * 全部内容由**本项目整理**，见 [MemeLibrary.BUILT_IN]。
 * 不来自任何第三方站点，也不是 B 站官方内容。
 */

/**
 * 一条梗。
 *
 * @param text 梗正文（**唯一必填字段**）
 * @param category 分类（空 = 未分类，UI 归到"其它"）
 * @param note 备注 / 解释（可为空）
 */
data class Meme(
    val text: String,
    val category: String = "",
    val note: String = "",
) {
    /**
     * 稳定 key。
     *
     * ⚠️ 用 `分类 + 正文` 而不是 `hashCode()`：两条不同的梗可能
     * 恰好同 hash（概率低但存在），而 LazyColumn 的 key 冲突会**崩**。
     * 文本本身唯一且可读，更适合做 key。
     */
    val key: String get() = "$category\u0000$text"
}

/**
 * 一个分类及其梗。
 *
 * @param name 分类名
 * @param memes 该分类下的梗
 */
data class MemeCategory(
    val name: String,
    val memes: List<Meme>,
) {
    val count: Int get() = memes.size
}
