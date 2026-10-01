package com.example.biliv3.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.biliv3.data.FavoritesSync
import com.example.biliv3.data.LibraryRepository

/**
 * 「我的」相关页面的 ViewModel 工厂。
 *
 * 几种页面共用同一个仓库，只是 ViewModel 类型不同。
 * 用 `when` 分发而不是写多个工厂类 —— 少几个文件，意图也更清楚。
 *
 * @param folderId 仅收藏夹**详情页**用（[FavoriteFolderViewModel]）。
 *                 总览页 [FavoriteViewModel] 用不到它。
 * @param sync 收藏状态广播，收藏夹详情取消收藏后通知总览刷新。
 */
class LibraryViewModelFactory(
    private val repo: LibraryRepository,
    private val folderId: Long = 0L,
    private val sync: FavoritesSync? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val vm: ViewModel = when {
            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(repo)

            modelClass.isAssignableFrom(ToViewViewModel::class.java) ->
                ToViewViewModel(repo)

            modelClass.isAssignableFrom(FavoriteViewModel::class.java) ->
                FavoriteViewModel(repo)

            modelClass.isAssignableFrom(FavoriteFolderViewModel::class.java) ->
                FavoriteFolderViewModel(repo, folderId, sync)

            else -> throw IllegalArgumentException("未知的 ViewModel: ${modelClass.name}")
        }
        return vm as T
    }
}
