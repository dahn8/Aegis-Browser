package dev.aegis.browser.settings

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import dev.aegis.browser.history.HistoryStore

/**
 * 站点数据清理：按域名清掉 Cookie、localStorage / IndexedDB 等 WebView 侧存储。
 *
 * WebView 没有“枚举并删除某站点全部 Cookie”的接口，这里尽可能覆盖：
 * 1. 从历史记录里取出该站点访问过的所有 URL（含子路径），逐个读 Cookie 名；
 * 2. 每个 Cookie 名按 host-only / Domain=host / Domain=.host 三种归属、
 *    以及各访问路径的目录各发一次过期指令，尽量命中原始 Cookie 的 Path/Domain；
 * 3. 本地存储走 WebStorage.deleteOrigin。
 * 清理后记录时间戳，列表里不再显示该站点，主页对应标签会自动重载。
 */
object SiteDataCleaner {

    private const val PREFS = "aegis_site_data"
    private const val KEY_CLEARED = "cleared_at"

    /** 待主页消费的「需要重载的域名」，避免跨进程/跨页面传参 */
    @Volatile
    var pendingReloadHosts: MutableSet<String> = mutableSetOf()

    private fun clearedMap(context: Context): MutableMap<String, Long> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CLEARED, null) ?: return mutableMapOf()
        val map = mutableMapOf<String, Long>()
        raw.split('\n').forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val host = line.substring(0, idx)
                val at = line.substring(idx + 1).toLongOrNull() ?: 0L
                if (host.isNotBlank() && at > 0) map[host] = at
            }
        }
        return map
    }

    private fun saveCleared(context: Context, map: Map<String, Long>) {
        val text = map.entries.joinToString("\n") { "${it.key}=${it.value}" }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CLEARED, text).apply()
    }

    /** 该站点最后一次访问时间 */
    private fun lastVisit(context: Context, host: String): Long {
        var latest = 0L
        HistoryStore(context).all().forEach { entry ->
            val h = runCatching { Uri.parse(entry.url).host }.getOrNull()?.lowercase() ?: return@forEach
            if (h == host && entry.visitedAt > latest) latest = entry.visitedAt
        }
        return latest
    }

    fun hosts(context: Context): List<String> {
        val cleared = clearedMap(context)
        val set = LinkedHashMap<String, Long>()
        HistoryStore(context).all().forEach { entry ->
            val host = runCatching { Uri.parse(entry.url).host }.getOrNull()
            if (!host.isNullOrBlank()) {
                val h = host.lowercase()
                set.putIfAbsent(h, entry.visitedAt)
            }
        }
        return set.filter { (host, at) ->
            val clearedAt = cleared[host] ?: 0L
            // 清理之后又访问过的，重新出现
            at > clearedAt
        }.keys.toList()
    }

    fun clearHost(context: Context, host: String) {
        val names = HashSet<String>()
        val paths = HashSet<String>()

        val urls = HistoryStore(context).all().map { it.url }.filter {
            runCatching { Uri.parse(it).host?.lowercase() == host }.getOrDefault(false)
        }.toMutableList()
        urls.add("https://$host/")
        urls.add("http://$host/")

        val cm = CookieManager.getInstance()
        urls.forEach { url ->
            val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
            val dir = if (path.isBlank() || path == "/") "/" else path.substringBeforeLast('/', "/") + "/"
            paths.add(dir)
            val raw = runCatching { cm.getCookie(url) }.getOrNull()
            if (!raw.isNullOrBlank()) {
                raw.split(';').forEach { pair ->
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotEmpty()) names.add(name)
                }
            }
        }

        val schemes = listOf("https://$host", "http://$host")
        val domains = listOf("", "; Domain=$host", "; Domain=.$host")
        names.forEach { name ->
            schemes.forEach { base ->
                paths.forEach { path ->
                    domains.forEach { domain ->
                        runCatching {
                            cm.setCookie(base + path, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT$domain; Path=$path")
                        }
                    }
                }
            }
        }
        cm.flush()

        if (Build.VERSION.SDK_INT >= 29) {
            val storage = WebStorage.getInstance()
            runCatching { storage.deleteOrigin("https://$host") }
            runCatching { storage.deleteOrigin("http://$host") }
        }

        val map = clearedMap(context).toMutableMap()
        map[host] = System.currentTimeMillis()
        saveCleared(context, map)

        synchronized(pendingReloadHosts) { pendingReloadHosts.add(host) }
    }

    /** 全部站点存储：Cookie + 本地存储，保留历史与书签 */
    fun clearAll(context: Context) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        WebStorage.getInstance().deleteAllData()
        val map = clearedMap(context).toMutableMap()
        val now = System.currentTimeMillis()
        HistoryStore(context).all().forEach { entry ->
            runCatching { Uri.parse(entry.url).host }.getOrNull()?.lowercase()?.let { map[it] = now }
        }
        saveCleared(context, map)
        synchronized(pendingReloadHosts) {
            HistoryStore(context).all().forEach { entry ->
                runCatching { Uri.parse(entry.url).host }.getOrNull()?.lowercase()
                    ?.let { pendingReloadHosts.add(it) }
            }
        }
    }

    /** 主页取走待重载域名（取走即清空） */
    fun takePendingReloadHosts(): Set<String> {
        val set = HashSet<String>()
        synchronized(pendingReloadHosts) {
            set.addAll(pendingReloadHosts)
            pendingReloadHosts.clear()
        }
        return set
    }
}
