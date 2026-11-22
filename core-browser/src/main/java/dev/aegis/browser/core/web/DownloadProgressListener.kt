package dev.aegis.browser.core.web

/**
 * 下载生命周期回调：由应用层的下载管理器实现，
 * 用于驱动下载进度 UI 与下载记录持久化。
 */
interface DownloadProgressListener {

    fun onStart(id: String, filename: String, mime: String, totalBytes: Long)

    fun onProgress(id: String, downloadedBytes: Long, totalBytes: Long)

    /** 用户暂停了任务 */
    fun onPause(id: String) {}

    /** 用户继续了任务 */
    fun onResume(id: String) {}

    fun onComplete(id: String, saved: SavedFile)

    fun onFail(id: String, filename: String, error: String)
}
