package dev.aegis.browser.privacy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 规则手动更新器与订阅管理器：
 * 支持常见内置规则订阅（StevenBlack、AdGuard DNS、EasyList China、EasyPrivacy、Peter Lowe）
 * 以及用户自定义 URL 订阅；兼容 Hosts 格式与 Adblock `||domain^` 域名规则格式。
 */
object RuleUpdater {

    data class Subscription(
        val id: String,
        val name: String,
        val url: String,
        val isCustom: Boolean = false
    )

    val BUILTIN_SUBSCRIPTIONS = listOf(
        Subscription(
            id = "stevenblack",
            name = "StevenBlack 统一广告拦截",
            url = "https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts"
        ),
        Subscription(
            id = "adguard_dns",
            name = "AdGuard DNS Filter",
            url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
        ),
        Subscription(
            id = "easylist_china",
            name = "EasyList China (中文区规则)",
            url = "https://anti-ad.net/easylist.txt"
        ),
        Subscription(
            id = "easyprivacy",
            name = "EasyPrivacy (反追踪规则)",
            url = "https://easylist.to/easylist/easyprivacy.txt"
        ),
        Subscription(
            id = "peter_lowe",
            name = "Peter Lowe's 广告追踪列表",
            url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
        )
    )

    /** 默认 StevenBlack 多镜像兜底 */
    private val STEVENBLACK_MIRRORS = listOf(
        "https://ghfast.top/https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts",
        "https://raw.gitmirror.com/StevenBlack/hosts/master/hosts",
        "https://ghproxy.net/https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    )

    private const val DEVICE_UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    sealed class Result {
        data class Success(val count: Int) : Result()
        data class Failure(val message: String) : Result()
    }

    private const val PREFS_NAME = "aegis_privacy"
    private const val KEY_UPDATED_AT = "rules_update_at"
    private const val KEY_UPDATED_COUNT = "rules_update_count"
    private const val KEY_ENABLED_SUBS = "enabled_rule_subs"
    private const val KEY_CUSTOM_SUBS = "custom_rule_subs"

    fun updatedAt(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_UPDATED_AT, 0L)

    fun updatedCount(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_UPDATED_COUNT, 0)

    fun enabledSubscriptionIds(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_ENABLED_SUBS, null)
        return saved?.toSet() ?: setOf("stevenblack")
    }

    fun setSubscriptionEnabled(context: Context, id: String, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val next = enabledSubscriptionIds(context).toMutableSet()
        if (enabled) next.add(id) else next.remove(id)
        prefs.edit().putStringSet(KEY_ENABLED_SUBS, next).apply()
    }

    fun customSubscriptions(context: Context): List<Subscription> {
        val raw = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_SUBS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<Subscription>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                val url = obj.optString("url")
                if (id.isNotBlank() && url.isNotBlank()) {
                    list.add(Subscription(id, name.ifBlank { url }, url, isCustom = true))
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    fun allSubscriptions(context: Context): List<Subscription> =
        BUILTIN_SUBSCRIPTIONS + customSubscriptions(context)

    fun addCustomSubscription(context: Context, name: String, url: String): Subscription {
        val cleanUrl = url.trim()
        val id = "custom_${cleanUrl.hashCode().toUInt()}"
        val cleanName = name.trim().ifBlank { cleanUrl.substringAfter("://").substringBefore('/') }
        val existing = customSubscriptions(context).filterNot { it.url == cleanUrl }.toMutableList()
        val added = Subscription(id, cleanName, cleanUrl, isCustom = true)
        existing.add(added)
        saveCustomSubscriptions(context, existing)
        setSubscriptionEnabled(context, id, true)
        return added
    }

    fun removeCustomSubscription(context: Context, id: String) {
        val next = customSubscriptions(context).filterNot { it.id == id }
        saveCustomSubscriptions(context, next)
        setSubscriptionEnabled(context, id, false)
    }

    private fun saveCustomSubscriptions(context: Context, list: List<Subscription>) {
        val arr = JSONArray()
        list.forEach { sub ->
            arr.put(JSONObject().apply {
                put("id", sub.id)
                put("name", sub.name)
                put("url", sub.url)
            })
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_SUBS, arr.toString()).apply()
    }

    private fun markUpdated(context: Context, count: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .putInt(KEY_UPDATED_COUNT, count).apply()
    }

    fun clearUpdatedState(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_UPDATED_AT).remove(KEY_UPDATED_COUNT).apply()
    }

    /**
     * 更新所有已启用的订阅规则并合并写入本地 updated.filter。
     */
    fun update(context: Context): Result {
        val enabled = enabledSubscriptionIds(context)
        val activeSubs = allSubscriptions(context).filter { it.id in enabled }
        val targets = if (activeSubs.isEmpty()) listOf(BUILTIN_SUBSCRIPTIONS.first()) else activeSubs

        val pool = Executors.newFixedThreadPool(minOf(4, targets.size + 1)) { r ->
            Thread(r, "rule-updater").apply { isDaemon = true }
        }
        return try {
            val merged = HashSet<String>(120_000)
            var successSubs = 0
            var lastError: String? = null

            val futures = targets.map { sub ->
                pool.submit(Callable {
                    if (sub.id == "stevenblack") {
                        fetchFirstAvailable(STEVENBLACK_MIRRORS)
                    } else {
                        fetchDomains(sub.url)
                    }
                })
            }
            for (future in futures) {
                try {
                    val domains = future.get()
                    if (domains.isNotEmpty()) {
                        merged.addAll(domains)
                        successSubs++
                    }
                } catch (e: Exception) {
                    lastError = e.cause?.message ?: e.message
                }
            }

            if (successSubs == 0 || merged.isEmpty()) {
                return Result.Failure(lastError ?: "所有订阅源均不可用")
            }

            File(context.filesDir, RuleLoader.UPDATED_NAME).printWriter().use { writer ->
                merged.forEach { writer.println(it) }
            }
            markUpdated(context, merged.size)
            Result.Success(merged.size)
        } catch (e: Exception) {
            Result.Failure(e.message ?: "unknown error")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fetchFirstAvailable(mirrors: List<String>): HashSet<String> {
        var lastEx: Exception? = null
        for (url in mirrors) {
            try {
                val res = fetchDomains(url)
                if (res.isNotEmpty()) return res
            } catch (e: Exception) {
                lastEx = e
            }
        }
        throw lastEx ?: java.io.IOException("all mirrors failed")
    }

    /** 下载并解析 Hosts 格式与 Adblock `||domain^` 格式的域名规则 */
    private fun fetchDomains(source: String): HashSet<String> {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", DEVICE_UA)
                setRequestProperty("Accept", "*/*")
            }
            if (connection.responseCode != 200) {
                throw java.io.IOException("HTTP ${connection.responseCode}")
            }

            val domains = HashSet<String>(60_000)
            connection.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith('#') || line.startsWith('!') || line.startsWith("@@")) {
                        return@forEachLine
                    }
                    // 1. Adblock / AdGuard 域名规则: ||example.com^ 或 ||example.com^$...
                    if (line.startsWith("||")) {
                        val rest = line.substring(2)
                        val cut = rest.indexOfAny(charArrayOf('^', '/', '$'))
                        val host = (if (cut > 0) rest.substring(0, cut) else rest).lowercase()
                        if (isValidFilterHost(host)) {
                            domains.add(host)
                        }
                        return@forEachLine
                    }
                    // 2. Hosts 格式: 0.0.0.0 example.com / 127.0.0.1 example.com
                    if (line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1")) {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val host = parts[1].lowercase()
                            if (isValidFilterHost(host)) {
                                domains.add(host)
                            }
                        }
                    }
                }
            }
            if (domains.isEmpty()) throw java.io.IOException("empty rules")
            return domains
        } finally {
            connection?.disconnect()
        }
    }

    private fun isValidFilterHost(host: String): Boolean {
        if (host.isEmpty() || !host.contains('.')) return false
        if (host == "localhost" || host == "localhost.localdomain" || host.endsWith(".local")) return false
        if (host.contains('*') || host.contains('/') || host.contains(':')) return false
        return true
    }
}
