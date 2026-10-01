package com.example.biliv3.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 收藏状态变更的**全局广播**。
 *
 * ## 为什么需要它（"不要做成孤立功能"）
 *
 * 收藏这个动作会发生在多个地方：
 * - 视频详情页的互动栏
 * - 「我的收藏」列表里的取消收藏
 * - 将来可能的：长按卡片快速收藏、番剧页收藏
 *
 * 而收藏状态会**影响**这些地方：
 * - 「我的收藏」列表（要增删条目、更新计数）
 * - 「我的」页的收藏数
 * - 详情页互动栏的星标（已收藏 / 未收藏）
 *
 * 如果每个页面各自拉自己的数据、互不知情，就会出现：
 * - 在详情页收藏了，回到「我的收藏」列表**没有这条**
 * - 在收藏列表里取消了，回到详情页**还显示已收藏**
 *
 * 这就是用户说的"孤立功能、状态不同步"。
 *
 * ## 机制：版本号 + `StateFlow`
 *
 * 不传递具体条目（"收藏了哪个 bvid"），只传递**一个单调递增的版本号**。
 *
 * 理由：订阅方需要的语义统一是"**有什么变了，重新拉一次**"，
 * 而不是"如何增量地改我本地的列表"。
 * 增量合并逻辑要在每个订阅方各写一遍（且极易写错），
 * 而"重新拉一次"只有一句、且天然正确。
 *
 * 代价是多一次网络请求 —— 但收藏操作是低频人工动作，
 * 这个代价远小于状态不一致的困扰。
 *
 * ## 用法
 *
 * 写入方（任何改变收藏状态的地方）：
 * ```
 * favoritesSync.notifyChanged()
 * ```
 *
 * 订阅方（任何展示收藏状态的页面）：
 * ```
 * val version by favoritesSync.version.collectAsStateWithLifecycle()
 * LaunchedEffect(version) { if (version > 0) reload() }
 * ```
 */
class FavoritesSync {

    private val _version = MutableStateFlow(0L)

    /** 当前版本号。每次收藏状态变化 +1。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** 广播一次「收藏状态已变化」。 */
    fun notifyChanged() {
        _version.update { it + 1 }
    }
}
