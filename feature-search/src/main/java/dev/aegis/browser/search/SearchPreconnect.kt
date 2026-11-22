package dev.aegis.browser.search

import android.net.Uri
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 搜索引擎连接预热：在进入地址编辑或启动时于后台线程提前解析 DNS 并建立 TLS/Keep-Alive 连接，
 * 缩短用户按下搜索后的首包等待时间。
 */
object SearchPreconnect {

    private const val COOLDOWN_MS = 30_000L
    private val lastWarmAt = ConcurrentHashMap<String, Long>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aegis-preconnect").apply { isDaemon = true }
    }

    fun warmUp(engine: SearchEngine) {
        val searchHost = runCatching { Uri.parse(engine.searchTemplate.replace("%s", "a")).host }.getOrNull()
        val suggestHost = engine.suggestTemplate?.let {
            runCatching { Uri.parse(it.replace("%s", "a")).host }.getOrNull()
        }
        val hosts = listOfNotNull(searchHost, suggestHost).distinct()
        if (hosts.isEmpty()) return

        val now = System.currentTimeMillis()
        for (host in hosts) {
            val prev = lastWarmAt[host] ?: 0L
            if (now - prev < COOLDOWN_MS) continue
            lastWarmAt[host] = now
            executor.execute {
                runCatching {
                    InetAddress.getByName(host)
                    val conn = (URL("https://$host/").openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 2000
                        readTimeout = 2000
                        instanceFollowRedirects = false
                        setRequestProperty("Connection", "keep-alive")
                    }
                    try {
                        conn.responseCode
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        }
    }
}
