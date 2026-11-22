package dev.aegis.browser.translate

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Microsoft 免费翻译源（免 API Key，高并发优化版）：
 * 1. 主通道：Edge 免费翻译接口（edge.microsoft.com/translate/auth + api-edge.cognitive.microsofttranslator.com/translate）
 *    - 省略 from= 参数以自动检测源语言
 *    - 若 Edge 通道首次连接失败/超时，自动进入 5 分钟熔断冷却，后续批次直连 Bing 通道，避免每次等待超时
 * 2. 备用通道：Bing 网页翻译接口（cn.bing.com / www.bing.com /ttranslatev3）
 * 3. 多分片并发请求（最多 3 线程），大幅缩短整页翻译耗时。
 */
object MicrosoftTranslator {

    private const val TAG = "MicrosoftTranslator"
    private const val AUTH_URL = "https://edge.microsoft.com/translate/auth"
    private const val TRANSLATE_URL = "https://api-edge.cognitive.microsofttranslator.com/translate"
    private const val EDGE_AUTH_TIMEOUT_MS = 3_000
    private const val EDGE_TRANS_TIMEOUT_MS = 4_500
    private const val BING_TIMEOUT_MS = 5_000
    private const val TOKEN_TTL_MS = 8 * 60 * 1000L
    private const val EDGE_COOLDOWN_MS = 5 * 60 * 1000L
    private const val MAX_BATCH_ITEMS = 20
    private const val MAX_BATCH_CHARS = 3_200
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenFetchedAt: Long = 0L

    @Volatile
    private var edgeBlockedUntil: Long = 0L

    private data class BingSession(
        val host: String,
        val ig: String,
        val key: String,
        val token: String,
        val cookie: String,
        val fetchedAt: Long
    )

    @Volatile
    private var cachedBing: BingSession? = null

    fun translateBatch(texts: List<String>, target: String = TranslateLanguages.DEFAULT): List<String>? {
        if (texts.isEmpty()) return emptyList()
        val toCode = mapTargetCode(target)
        val chunks = chunkTexts(texts)

        val now = System.currentTimeMillis()
        if (now >= edgeBlockedUntil) {
            val edgeOut = runChunksParallel(chunks) { chunk -> translateChunkViaEdge(chunk, toCode) }
            if (edgeOut != null && edgeOut.size == texts.size) return edgeOut
            // Edge 通道不可用时进入 5 分钟冷却，避免每批重复等待超时
            edgeBlockedUntil = System.currentTimeMillis() + EDGE_COOLDOWN_MS
        }

        val bingOut = runChunksParallel(chunks) { chunk -> translateChunkViaBing(chunk, toCode) }
        return if (bingOut != null && bingOut.size == texts.size) bingOut else null
    }

    private fun runChunksParallel(
        chunks: List<List<String>>,
        worker: (List<String>) -> List<String>?
    ): List<String>? {
        if (chunks.size == 1) return worker(chunks[0])
        val pool = Executors.newFixedThreadPool(minOf(3, chunks.size)) { r ->
            Thread(r, "ms-trans").apply { isDaemon = true }
        }
        return try {
            val futures = chunks.map { chunk -> pool.submit(Callable { worker(chunk) }) }
            val merged = ArrayList<String>()
            for (f in futures) {
                val res = f.get() ?: return null
                merged.addAll(res)
            }
            merged
        } catch (_: Exception) {
            null
        } finally {
            pool.shutdownNow()
        }
    }

    private fun chunkTexts(texts: List<String>): List<List<String>> {
        val result = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var chars = 0
        for (t in texts) {
            if (current.isNotEmpty() && (current.size >= MAX_BATCH_ITEMS || chars + t.length > MAX_BATCH_CHARS)) {
                result.add(current)
                current = ArrayList()
                chars = 0
            }
            current.add(t)
            chars += t.length
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    private fun translateChunkViaEdge(texts: List<String>, toCode: String): List<String>? {
        val firstToken = getEdgeToken(forceRefresh = false) ?: return null
        val firstTry = requestEdgeTranslate(texts, toCode, firstToken)
        if (firstTry != null) return firstTry
        val freshToken = getEdgeToken(forceRefresh = true) ?: return null
        return requestEdgeTranslate(texts, toCode, freshToken)
    }

    private fun mapTargetCode(code: String): String = when (code) {
        "zh-CN" -> "zh-Hans"
        "zh-TW" -> "zh-Hant"
        else -> TranslateLanguages.byCode(code).googleCode
    }

    @Synchronized
    private fun getEdgeToken(forceRefresh: Boolean): String? {
        val now = System.currentTimeMillis()
        val current = cachedToken
        if (!forceRefresh && !current.isNullOrBlank() && now - tokenFetchedAt < TOKEN_TTL_MS) {
            return current
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(AUTH_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = EDGE_AUTH_TIMEOUT_MS
                readTimeout = EDGE_AUTH_TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "keep-alive")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            }
            if (conn.responseCode == 200) {
                val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val token = raw.trim().removeSurrounding("\"").trim()
                if (token.isNotBlank() && token.contains('.')) {
                    cachedToken = token
                    tokenFetchedAt = System.currentTimeMillis()
                    token
                } else {
                    null
                }
            } else {
                Log.w(TAG, "auth http ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "auth failed: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun requestEdgeTranslate(texts: List<String>, toCode: String, token: String): List<String>? {
        var conn: HttpURLConnection? = null
        return try {
            val payload = JSONArray().apply {
                texts.forEach { put(JSONObject().put("Text", it)) }
            }.toString().toByteArray(Charsets.UTF_8)

            val url = URL("$TRANSLATE_URL?to=$toCode&api-version=3.0&includeSentenceLength=true")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = EDGE_TRANS_TIMEOUT_MS
                readTimeout = EDGE_TRANS_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "keep-alive")
            }
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "edge translate http $code")
                return null
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() != texts.size) return null
            val out = ArrayList<String>(texts.size)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                val translations = item?.optJSONArray("translations")
                val translated = translations?.optJSONObject(0)?.optString("text", "") ?: ""
                out.add(translated.ifBlank { texts[i] })
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "edge translate failed: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun translateChunkViaBing(texts: List<String>, toCode: String): List<String>? {
        val session = getBingSession(forceRefresh = false) ?: return null
        val firstTry = requestBingTranslate(texts, toCode, session)
        if (firstTry != null) return firstTry
        val fresh = getBingSession(forceRefresh = true) ?: return null
        return requestBingTranslate(texts, toCode, fresh)
    }

    @Synchronized
    private fun getBingSession(forceRefresh: Boolean): BingSession? {
        val now = System.currentTimeMillis()
        val current = cachedBing
        if (!forceRefresh && current != null && now - current.fetchedAt < TOKEN_TTL_MS) {
            return current
        }
        for (host in listOf("cn.bing.com", "www.bing.com")) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("https://$host/translator").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = BING_TIMEOUT_MS
                    readTimeout = BING_TIMEOUT_MS
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    setRequestProperty("Connection", "keep-alive")
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                }
                if (conn.responseCode == 200) {
                    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val cookies = conn.headerFields["Set-Cookie"]
                        ?.mapNotNull { it.substringBefore(';').trim().takeIf { c -> c.isNotEmpty() } }
                        ?.joinToString("; ")
                        .orEmpty()
                    val ig = Regex("""IG\s*:\s*"([A-Za-z0-9_-]+)"""").find(html)?.groupValues?.getOrNull(1)
                        ?: Regex(""""IG"\s*:\s*"([A-Za-z0-9_-]+)"""").find(html)?.groupValues?.getOrNull(1)
                    val abuse = Regex("""params_AbusePreventionHelper\s*=\s*\[\s*(\d+)\s*,\s*"([^"]+)"""").find(html)
                    val key = abuse?.groupValues?.getOrNull(1)
                    val token = abuse?.groupValues?.getOrNull(2)
                    if (!ig.isNullOrBlank() && !key.isNullOrBlank() && !token.isNullOrBlank()) {
                        val session = BingSession(host, ig, key, token, cookies, System.currentTimeMillis())
                        cachedBing = session
                        return session
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "bing session ($host) failed: ${e.message}")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return null
    }

    private fun requestBingTranslate(texts: List<String>, toCode: String, session: BingSession): List<String>? {
        val sep = "\n\uE000\n"
        val joined = texts.joinToString(sep)
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://${session.host}/ttranslatev3?isVertical=1&IG=${session.ig}&IID=translator.5028.1")
            val form = "fromLang=auto-detect&to=${URLEncoder.encode(toCode, "UTF-8")}" +
                "&text=${URLEncoder.encode(joined, "UTF-8")}" +
                "&token=${URLEncoder.encode(session.token, "UTF-8")}" +
                "&key=${URLEncoder.encode(session.key, "UTF-8")}" +
                "&tryFetchingGenderDebiasedTranslations=true"
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = BING_TIMEOUT_MS
                readTimeout = BING_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Origin", "https://${session.host}")
                setRequestProperty("Referer", "https://${session.host}/translator")
                setRequestProperty("Connection", "keep-alive")
                if (session.cookie.isNotBlank()) setRequestProperty("Cookie", session.cookie)
            }
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body)
            val translated = arr.optJSONObject(0)
                ?.optJSONArray("translations")
                ?.optJSONObject(0)
                ?.optString("text", "")
                .orEmpty()
            if (translated.isBlank()) return null
            val parts = translated.split(Regex("""\n?\s*\uE000\s*\n?"""))
            if (parts.size == texts.size) {
                parts.mapIndexed { idx, s -> s.trim().ifBlank { texts[idx] } }
            } else if (texts.size == 1) {
                listOf(translated.trim())
            } else {
                // 分隔符被改写时并发逐条请求，避免串行卡顿
                val pool = Executors.newFixedThreadPool(minOf(4, texts.size))
                try {
                    val futures = texts.map { single ->
                        pool.submit(Callable { requestBingSingle(single, toCode, session) ?: single })
                    }
                    futures.map { it.get() }
                } finally {
                    pool.shutdownNow()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "bing translate failed: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun requestBingSingle(text: String, toCode: String, session: BingSession): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://${session.host}/ttranslatev3?isVertical=1&IG=${session.ig}&IID=translator.5028.1")
            val form = "fromLang=auto-detect&to=${URLEncoder.encode(toCode, "UTF-8")}" +
                "&text=${URLEncoder.encode(text, "UTF-8")}" +
                "&token=${URLEncoder.encode(session.token, "UTF-8")}" +
                "&key=${URLEncoder.encode(session.key, "UTF-8")}"
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = BING_TIMEOUT_MS
                readTimeout = BING_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Origin", "https://${session.host}")
                setRequestProperty("Referer", "https://${session.host}/translator")
                setRequestProperty("Connection", "keep-alive")
                if (session.cookie.isNotBlank()) setRequestProperty("Cookie", session.cookie)
            }
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONArray(body).optJSONObject(0)
                ?.optJSONArray("translations")
                ?.optJSONObject(0)
                ?.optString("text", "")
                ?.trim()
                ?.ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
