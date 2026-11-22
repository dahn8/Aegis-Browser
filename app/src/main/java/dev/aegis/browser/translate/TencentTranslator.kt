package dev.aegis.browser.translate

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 页面翻译：腾讯交互翻译（transmart）的免 key 接口。
 * 国内可直连、支持一次提交多条文本（text_list），适合把整页段落分批翻译后原地替换。
 */
object TencentTranslator {

    private const val ENDPOINT = "https://transmart.qq.com/api/imt"
    private const val CLIENT_KEY = "browser-chrome-110.0.0.0"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 15000
    private const val MAX_BATCH_TEXTS = 18
    private const val MAX_BATCH_CHARS = 1500

    /** 把长文本列表切成适合单次请求的小批（条数与总字符双限制） */
    fun chunk(texts: List<String>): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var chars = 0
        for (text in texts) {
            if (current.isNotEmpty() &&
                (current.size >= MAX_BATCH_TEXTS || chars + text.length > MAX_BATCH_CHARS)
            ) {
                batches.add(current)
                current = mutableListOf()
                chars = 0
            }
            current.add(text)
            chars += text.length
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    /** 翻译一批文本；失败返回 null（调用方保留原文继续下一批） */
    fun translateBatch(texts: List<String>, from: String = "en", to: String = "zh"): List<String>? {
        if (texts.isEmpty()) return emptyList()
        val payload = JSONObject().apply {
            put(
                "header",
                JSONObject().apply {
                    put("fn", "auto_translation")
                    put("client_key", CLIENT_KEY)
                }
            )
            put("type", "plain")
            put(
                "source",
                JSONObject().apply {
                    put("language", from)
                    put("text_list", JSONArray(texts))
                }
            )
            put("target", JSONObject().put("language", to))
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/131.0.0.0 Mobile Safari/537.36"
                )
            }

            connection.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(responseText)
            if (json.optJSONObject("header")?.optString("ret_code") != "succ") return null
            val translated = json.optJSONArray("auto_translation") ?: return null
            val result = ArrayList<String>(texts.size)
            for (i in texts.indices) {
                val value = translated.optString(i, "")
                result.add(if (value.isBlank()) texts[i] else value)
            }
            result
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
