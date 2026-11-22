package dev.aegis.browser.search

import android.util.LruCache
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 搜索建议请求器。阻塞调用，必须在工作线程执行。
 * 支持标准 Firefox/OpenSearch 风格 JSON：["query",["suggestion1",...]]
 * 内置 LRU 内存缓存（5 分钟有效），大幅降低重复输入与退格时的网络延迟。
 */
object SuggestionFetcher {

    private const val CACHE_SIZE = 64
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private data class CacheEntry(val items: List<String>, val timestamp: Long)

    private val cache = object : LruCache<String, CacheEntry>(CACHE_SIZE) {}

    fun fetch(engine: SearchEngine, query: String): List<String> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()

        val cacheKey = "${engine.id}|${normalized.lowercase()}"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            val hit = cache.get(cacheKey)
            if (hit != null && now - hit.timestamp < CACHE_TTL_MS) {
                return hit.items
            }
        }

        val url = engine.buildSuggestUrl(normalized) ?: return emptyList()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                useCaches = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Aegis)")
                setRequestProperty("Connection", "keep-alive")
            }
            val charset = parseCharset(connection.contentType)
            val text = connection.inputStream.bufferedReader(charset).use { it.readText() }
            val root = JSONArray(text)
            val suggestions = root.getJSONArray(1)
            val list = ArrayList<String>(suggestions.length())
            for (i in 0 until suggestions.length()) {
                val item = suggestions.optString(i).trim()
                if (item.isNotEmpty()) list.add(item)
            }
            if (list.isNotEmpty()) {
                synchronized(cache) {
                    cache.put(cacheKey, CacheEntry(list, System.currentTimeMillis()))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    fun clearCache() {
        synchronized(cache) { cache.evictAll() }
    }

    private fun parseCharset(contentType: String?): Charset {
        if (contentType != null) {
            val idx = contentType.indexOf("charset=", ignoreCase = true)
            if (idx >= 0) {
                val name = contentType.substring(idx + 8).substringBefore(';').trim()
                runCatching { return Charset.forName(name) }
            }
        }
        return Charsets.UTF_8
    }
}
