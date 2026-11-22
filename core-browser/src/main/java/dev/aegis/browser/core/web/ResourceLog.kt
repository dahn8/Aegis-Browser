package dev.aegis.browser.core.web

/**
 * 资源日志：记录当前页面加载过程中 WebView 发出的全部请求。
 *
 * shouldInterceptRequest 在后台线程回调，所以用同步容器；主框架新页面开始时清空，
 * 保证日志始终对应"这次加载"。
 */
object ResourceLog {

    data class Entry(
        val time: Long,
        val url: String,
        val kind: String,
        val mime: String,
        val referer: String
    )

    private val items = java.util.Collections.synchronizedList(ArrayList<Entry>())
    private const val MAX = 200

    @Volatile
    var pageUrl: String? = null

    fun clear() {
        items.clear()
    }

    fun startPage(url: String?) {
        clear()
        pageUrl = url
    }

    fun add(url: String, mime: String) {
        if (url.isBlank()) return
        if (url.startsWith("data:") || url.startsWith("blob:")) return
        val kind = kindOf(url, mime)
        // 只留可直接播放的媒体：m3u8 / 视频 / 音频，其余请求不进列表
        if (kind != "m3u8" && kind != "video" && kind != "audio") return
        synchronized(items) {
            if (items.any { it.url == url }) return
            if (items.size >= MAX) items.removeAt(0)
            items.add(Entry(System.currentTimeMillis(), url, kind, mime, pageUrl ?: ""))
        }
    }

    fun snapshot(): List<Entry> = synchronized(items) { ArrayList(items) }

    private fun kindOf(url: String, mime: String): String {
        val lower = url.lowercase()
        return when {
            mime.contains("mpegurl") || lower.contains(".m3u8") -> "m3u8"
            mime.startsWith("video/") || listOf(".mp4", ".m4v", ".webm", ".mov", ".flv", ".mkv").any { lower.contains(it) } -> "video"
            mime.startsWith("audio/") || listOf(".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg").any { lower.contains(it) } -> "audio"
            mime.startsWith("image/") || listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg").any { lower.contains(it) } -> "img"
            mime.contains("css") || lower.contains(".css") -> "css"
            mime.contains("javascript") || lower.contains(".js") -> "js"
            mime.contains("json") || lower.contains(".json") -> "json"
            mime.startsWith("font") || listOf(".ttf", ".woff", ".otf").any { lower.contains(it) } -> "font"
            mime.startsWith("text/html") || lower.contains(".html") -> "html"
            else -> "load"
        }
    }
}
