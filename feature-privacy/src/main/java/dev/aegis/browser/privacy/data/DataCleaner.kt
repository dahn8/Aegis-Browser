package dev.aegis.browser.privacy.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import java.io.File

/**
 * 浏览数据清除器。清除 Cookie、缓存、历史、表单与网页存储，
 * 不触碰规则文件、白名单及搜索引擎设置（这些存于 filesDir / prefs）。
 */
object DataCleaner {

    fun clear(context: Context, webViews: List<WebView>) {
        // 每个 WebView 实例上的缓存、历史、表单
        webViews.forEach { webView ->
            runCatching {
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
            }
        }

        // 全局 Cookie
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                removeSessionCookies(null)
                flush()
            }
        }

        // 全局网页存储（localStorage / IndexedDB 等）
        runCatching {
            WebStorage.getInstance().deleteAllData()
        }

        // 清理应用缓存目录（规则与设置不在此目录）
        runCatching {
            context.cacheDir?.listFiles()?.forEach { deleteRecursivelySafe(it) }
        }
    }

    private fun deleteRecursivelySafe(file: File) {
        runCatching { file.deleteRecursively() }
    }
}
