package dev.aegis.browser.core.web

import android.content.Context

/**
 * 下载入口：WebView 的 DownloadListener 回调到这里，
 * 实际下载交给自研 [DirectDownloader]，并把进度/记录回传给
 * [DownloadProgressListener]（应用层下载管理器）。
 *
 * [onDownloadStarted] 在每次点击下载链接时同步触发，供应用层
 * 立即打开下载管理页，让用户看到实时进度。
 */
class DownloadHandler(
    context: Context,
    progressListener: DownloadProgressListener? = null,
    private val onDownloadStarted: (() -> Unit)? = null
) {

    private val downloader = DirectDownloader(context, progressListener)

    fun handle(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?
    ) {
        onDownloadStarted?.invoke()
        downloader.download(
            pageUrl = url,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            suggestedMime = mimeType
        )
    }
}
