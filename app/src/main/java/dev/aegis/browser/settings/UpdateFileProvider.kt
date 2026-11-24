package dev.aegis.browser.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 仅用于把下载到应用私有目录的更新 APK 临时授权给系统安装器。
 * 不使用 AndroidX FileProvider，保持工程零三方依赖。
 */
class UpdateFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveFile(uri) ?: return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), 0)
        val columns = projection?.toList()?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            val row = columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }.toTypedArray()
            addRow(row)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only provider")
        val file = resolveFile(uri) ?: throw FileNotFoundException("update file not found")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("insert not supported")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("delete not supported")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("update not supported")

    /** 只允许访问 updates 目录下的单个 APK，拒绝路径穿越与目录访问 */
    private fun resolveFile(uri: Uri): File? {
        val name = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val ctx = context ?: return null
        val dir = File(ctx.filesDir, "updates")
        val file = File(dir, name)
        if (!file.isFile) return null
        val canonical = file.canonicalFile
        val root = dir.canonicalFile
        if (!canonical.path.startsWith(root.path + File.separator)) return null
        return canonical
    }
}
