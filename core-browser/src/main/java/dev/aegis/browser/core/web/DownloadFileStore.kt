package dev.aegis.browser.core.web

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/** 保存结果：文件名、可用于打开的 URI 字符串、字节数 */
data class SavedFile(
    val name: String,
    val uriString: String,
    val length: Long
)

/**
 * 下载文件写入：API 29+ 通过 MediaStore 写公共 Downloads，
 * 低版本写应用私有外部目录，全程无需存储权限。
 *
 * [write] 由调用方提供 writer，在写入循环中可回调下载进度。
 */
class DownloadFileStore(context: Context) {

    private val appContext = context.applicationContext

    fun write(filename: String, mime: String, writer: (OutputStream) -> Long): SavedFile {
        val safeName = filename.ifBlank { "download" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(safeName, mime, writer)
        } else {
            writeToPrivateDir(safeName, writer)
        }
    }

    /**
     * 删除下载的本地文件。content URI 走 ContentResolver，
     * file URI 走 File.delete。文件不存在也视为成功。
     */
    fun delete(uriString: String): Boolean {
        if (uriString.isBlank()) return true
        return try {
            if (uriString.startsWith("content://")) {
                appContext.contentResolver.delete(Uri.parse(uriString), null, null) > 0
            } else {
                val path = Uri.parse(uriString).path
                if (path.isNullOrBlank()) true else File(path).delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun writeViaMediaStore(
        filename: String,
        mime: String,
        writer: (OutputStream) -> Long
    ): SavedFile {
        val resolver = appContext.contentResolver
        val uniqueName = ensureUniqueMediaName(filename)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, uniqueName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法创建下载记录")
        try {
            val length = resolver.openOutputStream(uri)?.use { output ->
                writer(output)
            } ?: throw IllegalStateException("无法打开输出流")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedFile(uniqueName, uri.toString(), length)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun writeToPrivateDir(
        filename: String,
        writer: (OutputStream) -> Long
    ): SavedFile {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: appContext.filesDir
        val target = File(dir, ensureUniqueFileName(dir, filename))
        val length = target.outputStream().use { output -> writer(output) }
        return SavedFile(target.name, Uri.fromFile(target).toString(), length)
    }

    private fun ensureUniqueMediaName(filename: String): String {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val base = filename.substringBeforeLast('.', filename)
        val ext = if (filename.contains('.')) ".${filename.substringAfterLast('.')}" else ""
        var candidate = filename
        var counter = 1
        while (nameExists(resolver, collection, candidate)) {
            candidate = "$base($counter)$ext"
            counter++
        }
        return candidate
    }

    private fun nameExists(
        resolver: android.content.ContentResolver,
        collection: Uri,
        name: String
    ): Boolean {
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        resolver.query(collection, projection, selection, arrayOf(name), null)?.use {
            return it.count > 0
        }
        return false
    }

    private fun ensureUniqueFileName(dir: File, filename: String): String {
        if (!File(dir, filename).exists()) return filename
        val base = filename.substringBeforeLast('.', filename)
        val ext = if (filename.contains('.')) ".${filename.substringAfterLast('.')}" else ""
        var counter = 1
        while (File(dir, "$base($counter)$ext").exists()) counter++
        return "$base($counter)$ext"
    }
}
