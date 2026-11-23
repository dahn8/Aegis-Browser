package dev.aegis.browser.settings

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通过 GitHub Releases 的 latest 接口检查更新。
 * 仓库为公开仓库，匿名请求即可（未登录时 GitHub 限流为每小时 60 次，足够手动检查）。
 */
object UpdateChecker {

    data class Release(
        val version: String,
        val tag: String,
        val pageUrl: String,
        val apkUrl: String?,
        val notes: String
    )

    sealed class Result {
        data class Success(val release: Release) : Result()
        data class Failure(val message: String) : Result()
    }

    private const val API_URL = "https://api.github.com/repos/dahn8/Aegis-Browser/releases/latest"
    private const val FALLBACK_RELEASES = "https://github.com/dahn8/Aegis-Browser/releases"

    fun check(currentVersion: String): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AegisBrowser/$currentVersion")
            }
            val code = connection.responseCode
            if (code != 200) {
                return Result.Failure("HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty()) {
                return Result.Failure("empty tag")
            }
            val version = normalizeVersion(tag)
            val pageUrl = json.optString("html_url").ifBlank { FALLBACK_RELEASES }
            val notes = json.optString("body").trim()
            val apkUrl = pickApk(json.optJSONArray("assets"))
            Result.Success(Release(version, tag, pageUrl, apkUrl, notes))
        } catch (e: Exception) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /** 优先取名字里带 release 的 APK，其次取第一个 APK 资产 */
    private fun pickApk(assets: JSONArray?): String? {
        if (assets == null) return null
        var fallback: String? = null
        for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val url = item.optString("browser_download_url")
            if (url.isBlank()) continue
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.contains("release", ignoreCase = true)) return url
            if (fallback == null) fallback = url
        }
        return fallback
    }

    /** 逐段比较版本号，忽略 v 前缀、预发布后缀，缺失段按 0 处理 */
    fun isNewer(candidate: String, current: String): Boolean {
        val left = versionNumbers(candidate)
        val right = versionNumbers(current)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun normalizeVersion(raw: String): String =
        raw.trim().trimStart('v', 'V').substringBefore('-').substringBefore('+')

    private fun versionNumbers(raw: String): List<Int> =
        normalizeVersion(raw).split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
}
