package dev.aegis.browser.settings

import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 通过 GitHub Releases 检查更新。
 *
 * 首选 REST latest 接口；公开仓库匿名请求有每小时 60 次的限流，被限流或接口异常时
 * 自动回退到 releases.atom（无 API 限流），保证在网络受限或共享 IP 下也能检查。
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
    private const val ATOM_URL = "https://github.com/dahn8/Aegis-Browser/releases.atom"
    private const val FALLBACK_RELEASES = "https://github.com/dahn8/Aegis-Browser/releases"

    fun check(currentVersion: String): Result {
        val api = checkApi(currentVersion)
        if (api is Result.Success) return api
        val atom = checkAtom(currentVersion)
        return if (atom is Result.Success) atom else api
    }

    private fun checkApi(currentVersion: String): Result {
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

    /** 解析 releases.atom 的第一条 entry：标题、tag 链接与更新说明 */
    private fun checkAtom(currentVersion: String): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ATOM_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                setRequestProperty("User-Agent", "AegisBrowser/$currentVersion")
            }
            val code = connection.responseCode
            if (code != 200) {
                return Result.Failure("atom HTTP $code")
            }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
            }
            val doc = factory.newDocumentBuilder().parse(connection.inputStream)
            val entries = doc.getElementsByTagName("entry")
            if (entries.length == 0) {
                return Result.Failure("atom empty")
            }
            val entry = entries.item(0) as? Element ?: return Result.Failure("atom entry")
            val title = entry.textOf("title")
            val content = entry.textOf("content")
            val linkNodes = entry.getElementsByTagName("link")
            var link = ""
            for (i in 0 until linkNodes.length) {
                val node = linkNodes.item(i) as? Element ?: continue
                val href = node.getAttribute("href")
                if (href.contains("/releases/tag/")) {
                    link = href
                    break
                }
            }
            val tag = link.substringAfterLast("/tag/").ifBlank {
                Regex("""v?[0-9]+(?:\.[0-9]+)+""").find(title)?.value.orEmpty()
            }
            if (tag.isBlank()) {
                return Result.Failure("atom tag missing")
            }
            Result.Success(
                Release(
                    version = normalizeVersion(tag),
                    tag = tag,
                    pageUrl = link.ifBlank { FALLBACK_RELEASES },
                    apkUrl = null,
                    notes = stripHtml(content)
                )
            )
        } catch (e: Exception) {
            Result.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun Element.textOf(tag: String): String {
        val nodes = getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim().orEmpty() else ""
    }

    private fun stripHtml(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        }.getOrDefault(raw)
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
