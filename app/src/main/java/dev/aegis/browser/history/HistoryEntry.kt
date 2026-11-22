package dev.aegis.browser.history

/**
 * 一条浏览历史：标题、网址、访问时间（毫秒）。
 */
data class HistoryEntry(
    val title: String,
    val url: String,
    val visitedAt: Long
)
