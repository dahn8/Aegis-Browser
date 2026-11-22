package dev.aegis.browser.bookmarks

data class Bookmark(
    val title: String,
    val url: String,
    val createdAt: Long = 0L,
    val lastOpenedAt: Long = 0L
)
