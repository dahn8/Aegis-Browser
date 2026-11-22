package dev.aegis.browser.downloads

data class DownloadRecord(
    val id: String,
    var filename: String,
    var mime: String,
    var downloadedBytes: Long,
    var totalBytes: Long,
    var status: Status,
    var uriString: String? = null,
    var error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    enum class Status { DOWNLOADING, PAUSED, COMPLETED, FAILED }

    /** 总大小已知时返回 0-100，未知返回 -1 */
    fun progressPercent(): Int {
        if (totalBytes <= 0) return -1
        return ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }
}
