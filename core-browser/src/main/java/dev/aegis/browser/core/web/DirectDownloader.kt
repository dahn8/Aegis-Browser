package dev.aegis.browser.core.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 自研直链下载器：HttpURLConnection 手动跟随重定向（含跨协议），
 * 流式写入下载目录并实时回调进度，不依赖系统 DownloadManager。
 * 支持按任务 id 暂停 / 继续 / 取消。
 */
class DirectDownloader(
    context: Context,
    private val progressListener: DownloadProgressListener? = null
) {

    private val appContext = context.applicationContext
    private val fileStore = DownloadFileStore(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 异步下载。[userAgent] 由 WebView 传入，cookie 自动从 CookieManager 取。
     * 返回本次下载任务 id。
     */
    fun download(
        pageUrl: String,
        userAgent: String,
        contentDisposition: String? = null,
        suggestedMime: String? = null
    ): String {
        val id = UUID.randomUUID().toString()
        val task = Task(id)
        controllers[id] = task
        Thread {
            var connection: HttpURLConnection? = null
            var filename = "download"
            try {
                toast("正在下载…")
                connection = openConnection(pageUrl, userAgent)
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IOException("服务器返回 $status")
                }

                val finalUrl = connection.url?.toString() ?: pageUrl
                val disposition = connection.getHeaderField("Content-Disposition")
                    ?: contentDisposition
                val contentType = connection.contentType
                val mime = parseMime(contentType) ?: suggestedMime ?: "application/octet-stream"
                filename = URLUtil.guessFileName(finalUrl, disposition, contentType)
                val totalBytes = connection.contentLengthLong.takeIf { it >= 0 } ?: -1L

                progressListener?.onStart(id, filename, mime, totalBytes)

                val saved = fileStore.write(filename, mime) { output ->
                    streamWithProgress(connection, task, totalBytes, output)
                }
                progressListener?.onComplete(id, saved)
                toast(saved.name)
            } catch (t: Throwable) {
                if (task.cancelled) {
                    // 用户主动取消：不再写失败记录，记录也会被一并删除
                    progressListener?.onFail(id, filename, "已取消")
                } else {
                    progressListener?.onFail(id, filename, t.message ?: "未知错误")
                    toast("下载失败：${t.message ?: "未知错误"}")
                }
            } finally {
                controllers.remove(id)
                connection?.disconnect()
            }
        }.start()
        return id
    }

    /** 读取连接输入流并写入 output，限频回调进度；暂停时保持连接、等待继续 */
    private fun streamWithProgress(
        connection: HttpURLConnection,
        task: Task,
        totalBytes: Long,
        output: java.io.OutputStream
    ): Long {
        val input = connection.inputStream ?: throw IOException("无数据")
        val buffer = ByteArray(BUFFER_SIZE)
        var downloaded = 0L
        var lastCallback = 0L
        input.use {
            while (true) {
                if (task.cancelled) throw IOException("已取消")
                if (task.paused) {
                    // 暂停：连接保持打开，等待用户点继续
                    Thread.sleep(PAUSE_POLL_MS)
                    continue
                }
                val read = it.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                downloaded += read
                val now = System.currentTimeMillis()
                if (now - lastCallback >= PROGRESS_INTERVAL_MS) {
                    progressListener?.onProgress(task.id, downloaded, totalBytes)
                    lastCallback = now
                }
            }
        }
        output.flush()
        progressListener?.onProgress(task.id, downloaded, totalBytes)
        return downloaded
    }

    /** 手动跟随重定向，最多 [MAX_REDIRECTS] 次，支持 http/https 跨协议跳转 */
    private fun openConnection(targetUrl: String, userAgent: String): HttpURLConnection {
        var current = targetUrl
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(current)?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("重定向地址为空")
                current = URL(URL(current), location).toExternalForm()
                connection.disconnect()
            } else {
                return connection
            }
        }
        throw IOException("重定向次数过多")
    }

    private fun parseMime(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        return contentType.substringBefore(';').trim().takeIf { it.isNotEmpty() }
    }

    private fun toast(message: String) {
        mainHandler.post {
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_LONG)
                .show()
        }
    }

    /** 运行中的任务控制块 */
    private class Task(val id: String) {
        @Volatile
        var paused = false

        @Volatile
        var cancelled = false
    }

    companion object {
        private const val MAX_REDIRECTS = 8
        private const val TIMEOUT_MS = 20000
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_INTERVAL_MS = 120L
        private const val PAUSE_POLL_MS = 60L

        private val controllers = ConcurrentHashMap<String, Task>()

        /** 暂停任务；任务不存在或已结束时返回 false */
        fun pauseTask(id: String): Boolean {
            val task = controllers[id] ?: return false
            task.paused = true
            return true
        }

        /** 继续任务；任务不存在或已结束时返回 false */
        fun resumeTask(id: String): Boolean {
            val task = controllers[id] ?: return false
            task.paused = false
            return true
        }

        fun isPaused(id: String): Boolean = controllers[id]?.paused == true

        /** 取消任务（删除记录时调用），下载线程会在下一次检查后退出 */
        fun cancelTask(id: String) {
            controllers[id]?.cancelled = true
        }
    }
}
