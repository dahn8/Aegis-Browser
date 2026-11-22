package dev.aegis.browser.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.aegis.browser.core.web.DirectDownloader
import dev.aegis.browser.core.web.DownloadFileStore
import dev.aegis.browser.core.web.DownloadProgressListener
import dev.aegis.browser.core.web.SavedFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * 下载记录管理器：实现 core 层的进度回调，线程安全地维护记录，
 * 用 org.json 持久化历史。应用重启后未完成项标记为失败。
 */
class DownloadManagerStore private constructor(context: Context) : DownloadProgressListener {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val fileStore = DownloadFileStore(appContext)
    private val records = mutableListOf<DownloadRecord>()
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** UI 变更回调，始终在主线程触发 */
    @Volatile
    var onChange: (() -> Unit)? = null

    init {
        load()
    }

    fun snapshot(): List<DownloadRecord> = synchronized(lock) { records.toList() }

    override fun onStart(id: String, filename: String, mime: String, totalBytes: Long) {
        synchronized(lock) {
            records.removeAll { it.id == id }
            records.add(
                0,
                DownloadRecord(
                    id = id,
                    filename = filename,
                    mime = mime,
                    downloadedBytes = 0,
                    totalBytes = totalBytes,
                    status = DownloadRecord.Status.DOWNLOADING
                )
            )
        }
        notifyChanged()
    }

    override fun onProgress(id: String, downloadedBytes: Long, totalBytes: Long) {
        synchronized(lock) {
            val record = records.find { it.id == id } ?: return
            record.downloadedBytes = downloadedBytes
            if (totalBytes >= 0) record.totalBytes = totalBytes
        }
        notifyChanged()
    }

    override fun onPause(id: String) {
        synchronized(lock) {
            records.find { it.id == id }?.status = DownloadRecord.Status.PAUSED
        }
        notifyChanged()
    }

    override fun onResume(id: String) {
        synchronized(lock) {
            records.find { it.id == id }?.status = DownloadRecord.Status.DOWNLOADING
        }
        notifyChanged()
    }

    override fun onComplete(id: String, saved: SavedFile) {
        synchronized(lock) {
            val record = records.find { it.id == id }
            if (record == null) {
                records.add(
                    0,
                    DownloadRecord(
                        id = id,
                        filename = saved.name,
                        mime = "",
                        downloadedBytes = saved.length,
                        totalBytes = saved.length,
                        status = DownloadRecord.Status.COMPLETED,
                        uriString = saved.uriString
                    )
                )
            } else {
                record.status = DownloadRecord.Status.COMPLETED
                record.filename = saved.name
                record.downloadedBytes = saved.length
                record.totalBytes = saved.length
                record.uriString = saved.uriString
                record.error = null
            }
        }
        save()
        notifyChanged()
    }

    override fun onFail(id: String, filename: String, error: String) {
        synchronized(lock) {
            val record = records.find { it.id == id }
            if (record == null) {
                records.add(
                    0,
                    DownloadRecord(
                        id = id,
                        filename = filename,
                        mime = "",
                        downloadedBytes = 0,
                        totalBytes = -1,
                        status = DownloadRecord.Status.FAILED,
                        error = error
                    )
                )
            } else {
                record.status = DownloadRecord.Status.FAILED
                record.error = error
            }
        }
        save()
        notifyChanged()
    }

    fun remove(id: String) {
        DirectDownloader.cancelTask(id)
        val uri = synchronized(lock) {
            val record = records.find { it.id == id }
            records.removeAll { it.id == id }
            record?.uriString
        }
        // 一并删除本地文件，文件不存在也不影响记录移除
        if (!uri.isNullOrBlank()) fileStore.delete(uri)
        save()
        notifyChanged()
    }

    /** 暂停任务：仅运行中的下载可暂停 */
    fun pause(id: String): Boolean {
        if (!DirectDownloader.pauseTask(id)) return false
        synchronized(lock) {
            records.find { it.id == id }?.status = DownloadRecord.Status.PAUSED
        }
        save()
        notifyChanged()
        return true
    }

    /** 继续任务 */
    fun resume(id: String): Boolean {
        if (!DirectDownloader.resumeTask(id)) return false
        synchronized(lock) {
            records.find { it.id == id }?.status = DownloadRecord.Status.DOWNLOADING
        }
        save()
        notifyChanged()
        return true
    }

    /** 重命名已完成文件；成功返回 true */
    fun rename(id: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val record = synchronized(lock) { records.find { it.id == id } } ?: return false
        val uriString = record.uriString ?: return false
        val ok = try {
            if (uriString.startsWith("content://")) {
                appContext.contentResolver.update(
                    Uri.parse(uriString),
                    ContentValues().apply {
                        put("_display_name", trimmed)
                    },
                    null,
                    null
                ) > 0
            } else {
                val file = java.io.File(Uri.parse(uriString).path ?: return false)
                val target = java.io.File(file.parentFile, trimmed)
                file.renameTo(target).also { renamed ->
                    if (renamed) record.uriString = Uri.fromFile(target).toString()
                }
            }
        } catch (_: Exception) {
            false
        }
        if (ok) {
            record.filename = trimmed
            save()
            notifyChanged()
        }
        return ok
    }

    private fun notifyChanged() {
        val callback = onChange ?: return
        mainHandler.post(callback)
    }

    private fun save() {
        val array = JSONArray()
        synchronized(lock) {
            records.forEach { record ->
                array.put(
                    JSONObject()
                        .put("id", record.id)
                        .put("filename", record.filename)
                        .put("mime", record.mime)
                        .put("downloaded", record.downloadedBytes)
                        .put("total", record.totalBytes)
                        .put("status", record.status.name)
                        .put("uri", record.uriString)
                        .put("error", record.error)
                        .put("createdAt", record.createdAt)
                )
            }
        }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                val storedStatus = runCatching {
                    DownloadRecord.Status.valueOf(obj.getString("status"))
                }.getOrElse { DownloadRecord.Status.FAILED }
                // 重启后残留的进行中/已暂停任务视为失败（无法续传）
                val status = if (storedStatus == DownloadRecord.Status.DOWNLOADING ||
                    storedStatus == DownloadRecord.Status.PAUSED
                ) {
                    DownloadRecord.Status.FAILED
                } else {
                    storedStatus
                }
                records.add(
                    DownloadRecord(
                        id = obj.getString("id"),
                        filename = obj.getString("filename"),
                        mime = obj.optString("mime"),
                        downloadedBytes = obj.getLong("downloaded"),
                        totalBytes = obj.getLong("total"),
                        status = status,
                        uriString = if (obj.isNull("uri")) null else obj.getString("uri"),
                        error = if (obj.isNull("error")) null else obj.getString("error"),
                        createdAt = obj.optLong("createdAt", 0L)
                    )
                )
            }
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val PREFS_NAME = "aegis_downloads"
        private const val KEY_RECORDS = "records"

        @Volatile
        private var instance: DownloadManagerStore? = null

        /** 进程级单例：保证下载回调与下载管理页共享同一份记录与实时回调 */
        fun get(context: Context): DownloadManagerStore {
            return instance ?: synchronized(this) {
                instance ?: DownloadManagerStore(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
