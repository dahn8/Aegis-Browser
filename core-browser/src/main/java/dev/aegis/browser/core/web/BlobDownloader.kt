package dev.aegis.browser.core.web

import android.webkit.WebView

/**
 * blob/data 下载能力：网页按钮生成的 blob: URL 系统 DownloadManager 无法访问，
 * 需要在页面内拦截并把内容转 base64 回传原生保存。
 */
interface BlobDownloader {

    /** 注入 JS 桥接对象，每个 WebView 调用一次 */
    fun attach(webView: WebView)

    /** 页面开始加载时注入的拦截脚本 */
    val hookScript: String
}
