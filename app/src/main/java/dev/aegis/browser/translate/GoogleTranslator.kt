package dev.aegis.browser.translate

import android.util.Log
import org.json.JSONArray
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Google 翻译源（免 key）：translate.googleapis.com 的 translate_a/single 接口。
 * 逐段请求，失败自动重试；超长段落按空格切成 <=320 字符再拼接。
 * 该域名在中国大陆需代理才能访问，全部失败时由调用方切回腾讯。
 */
object GoogleTranslator {

    private const val TAG = "GoogleTranslator"
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val MAX_CONCURRENCY = 3
    private const val TIMEOUT_MS = 8_000
    private const val MAX_ATTEMPTS = 3
    private const val CHUNK_CHARS = 320
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    /** 逐段翻译，返回与入参等长的列表；失败段落为 null，由调用方补翻或保留原文 */
    fun translateAll(texts: List<String>, target: String = TranslateLanguages.DEFAULT): List<String?> {
        if (texts.isEmpty()) return emptyList()

        val results = arrayOfNulls<String>(texts.size)
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENCY)
        val latch = CountDownLatch(texts.size)
        texts.forEachIndexed { index, text ->
            pool.execute {
                try {
                    // 轻微错峰，降低被限流的概率
                    if (index % MAX_CONCURRENCY != 0) {
                        Thread.sleep((index % MAX_CONCURRENCY) * 60L)
                    }
                    results[index] = translateOne(text, target)
                } catch (e: Exception) {
                    results[index] = null
                } finally {
                    latch.countDown()
                }
            }
        }
        val waitMs = ((texts.size / MAX_CONCURRENCY) + 1) * (TIMEOUT_MS / 2 + 3_000L)
        runCatching { latch.await(waitMs, TimeUnit.MILLISECONDS) }
        pool.shutdownNow()
        return results.toList()
    }

    private fun translateOne(text: String, target: String): String? {
        val source = text.trim()
        if (source.isEmpty()) return null
        if (source.length <= CHUNK_CHARS) {
            return requestWithRetry(source, target)
        }
        val parts = splitIntoChunks(source)
        if (parts.isEmpty()) return null
        val builder = StringBuilder()
        for (part in parts) {
            val translated = requestWithRetry(part, target) ?: return null
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(translated)
        }
        return builder.toString()
    }

    private fun splitIntoChunks(text: String): List<String> {
        val chunks = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest.length <= CHUNK_CHARS) {
                chunks.add(rest)
                break
            }
            var cut = rest.lastIndexOf(' ', CHUNK_CHARS)
            if (cut <= 40) cut = CHUNK_CHARS
            chunks.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        return chunks.filter { it.isNotEmpty() }
    }

    private fun requestWithRetry(text: String, target: String): String? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = requestOnce(text, target)
            if (!result.isNullOrBlank()) return result
            if (attempt < MAX_ATTEMPTS - 1) {
                runCatching { Thread.sleep(250L * (attempt + 1)) }
            }
        }
        return null
    }

    private fun requestOnce(text: String, target: String): String? {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val targetCode = TranslateLanguages.byCode(target).googleCode
        val url = URL("$ENDPOINT?client=gtx&sl=auto&tl=$targetCode&dt=t&q=$encoded")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
        }
        return try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "http $code")
                null
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val translated = parseSingle(body)
                // 与原文完全一致通常意味着这次没真正翻（被限流返回占位等），留给重试/补翻
                if (translated != null && translated.trim().equals(text.trim(), ignoreCase = true)) {
                    null
                } else {
                    translated
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "translate failed: ${e.message}")
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 响应形如 [[["译文","原文",null,null,10]], null, "en", ...]，拼接首层所有译文片段 */
    private fun parseSingle(body: String): String? {
        val root = runCatching { JSONTokener(body).nextValue() as? JSONArray }.getOrNull() ?: return null
        val segments = root.optJSONArray(0) ?: return null
        val builder = StringBuilder()
        for (i in 0 until segments.length()) {
            val segment = segments.optJSONArray(i) ?: continue
            builder.append(segment.optString(0, ""))
        }
        val result = builder.toString().trim()
        return result.ifBlank { null }
    }
}
