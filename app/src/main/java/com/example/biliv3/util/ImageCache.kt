package com.example.biliv3.util

import android.content.Context
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片缓存统计与清理。
 *
 * ## 为什么单独一个文件
 *
 * 「清理缓存」是设置页的一项功能，但它的**实现属于应用级**：
 * 缓存由 Coil 的单例 `ImageLoader` 持有，设置页只应触发、不应持有 ImageLoader。
 * 拆出来之后设置页保持"零 IO"（见 `SettingsScreen` 的参数说明）。
 *
 * ## 统计口径
 *
 * `ImageLoader.diskCache` 是 Coil 的**磁盘**缓存（封面、头像）。
 * 内存缓存（`memoryCache`）不计入占用 —— 它对用户没有意义
 * （进程一退就没了，显示出来只会让人困惑"为什么清理后没变小"）。
 *
 * ## ⚠️ 缓存可能是 null
 *
 * `ImageLoader` 允许不配 diskCache（默认配了，但类型上可为空），
 * 所以所有访问都走 `?.`，拿不到就返回 0 而不是崩。
 */
object ImageCache {

    /** 磁盘缓存占用字节数。拿不到返回 0。 */
    suspend fun sizeBytes(context: Context): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.imageLoader.diskCache?.size ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * 清理磁盘缓存。
     *
     * ## 为什么同时清内存缓存
     *
     * 只清磁盘的话，**已经显示在屏幕上的图仍在内存里** ——
     * 用户看到"占用变成 0 了但图片还在"，会以为没清干净。
     * 两个都清，行为才自洽（代价是当前页面图片重新加载一次，可接受）。
     *
     * @return 是否清理成功（失败由调用方决定是否提示）
     */
    suspend fun clear(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val loader = context.imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            true
        }.getOrDefault(false)
    }

    /**
     * 字节数 → 可读文案（`12.4 MB` / `820 KB` / `0 B`）。
     *
     * 用 1024 进制 —— Android 存储界普遍如此（虽然严格说是 MiB）。
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1) return "$bytes B"
        if (kb < 1024) return "${"%.0f".format(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${"%.1f".format(mb)} MB"
        return "${"%.2f".format(mb / 1024.0)} GB"
    }
}
