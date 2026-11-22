package dev.aegis.browser.settings

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import java.io.File
import java.util.Locale

/**
 * 浏览数据的分类统计与清理。
 * 不触碰书签、下载文件与拦截规则。
 */
object BrowserDataCleaner {

    /** 应用临时缓存（cacheDir）字节数 */
    fun appCacheSize(context: Context): Long = dirSize(context.cacheDir)

    /** 网页临时缓存（WebView 的 Cache / GPUCache）字节数 */
    fun webCacheSize(context: Context): Long {
        var total = 0L
        total += dirSize(File(context.dataDir, "app_webview/Cache"))
        total += dirSize(File(context.dataDir, "app_webview/GPUCache"))
        total += dirSize(File(context.cacheDir, "WebView"))
        return total
    }

    /** 缓存总占用（网页缓存 + 应用缓存），用于设置页展示 */
    fun sizeBytes(context: Context): Long = appCacheSize(context) + webCacheSize(context)

    /** 把字节数格式化成 "12.1 MB" 这样的短文本 */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    /** 清空应用临时缓存 */
    fun clearAppCache(context: Context) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** 清空网页缓存 */
    fun clearWebCache(context: Context) {
        try {
            WebView(context).clearCache(true)
        } catch (_: Throwable) {
        }
        File(context.dataDir, "app_webview/Cache").deleteRecursively()
        File(context.dataDir, "app_webview/GPUCache").deleteRecursively()
        File(context.cacheDir, "WebView").deleteRecursively()
    }

    /** 清空 Cookie 与 WebStorage（站点登录状态会失效） */
    fun clearCookies() {
        try {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        } catch (_: Throwable) {
        }
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Throwable) {
        }
    }

    /** 清空自动填充表单数据 */
    @Suppress("DEPRECATION")
    fun clearFormData(context: Context) {
        try {
            WebViewDatabase.getInstance(context).clearFormData()
        } catch (_: Throwable) {
        }
    }

    /** 一并清理缓存与 Cookie（“全部清除”与无痕模式复用） */
    fun clear(context: Context) {
        clearWebCache(context)
        clearAppCache(context)
        clearCookies()
        clearFormData(context)
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (!dir.isDirectory) return dir.length()
        var total = 0L
        dir.listFiles()?.forEach { total += dirSize(it) }
        return total
    }
}
