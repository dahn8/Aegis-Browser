package dev.aegis.browser.translate

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 智谱官方翻译源，走 GLM-4-Flash（免费模型，OpenAI 兼容 chat/completions 接口）。
 *
 * 与硅基流动的差别：GLM-4-Flash 是通用对话模型，不是专门的翻译模型，
 * 所以靠 system 指令约束「只输出译文、多行输入保持行数一致」。
 * 速度策略同硅基：一批少段落 + 多路并发，合并返回行数对不上就并行逐条重发。
 */
object ZhipuTranslator {

    private const val TAG = "ZhipuTranslator"
    private const val ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    private const val MODEL = "glm-4-flash"
    private const val TIMEOUT_MS = 20_000
    private const val MAX_CONCURRENCY = 6
    private const val MAX_ATTEMPTS = 2
    private const val CHUNK_ITEMS = 6
    private const val CHUNK_CHARS = 1_400

    /** 逐段翻译，返回与入参等长的列表；失败段落为 null，由调用方保留原文或补翻 */
    fun translateAll(texts: List<String>, target: String, apiKey: String): List<String?> {
        if (texts.isEmpty()) return emptyList()
        if (apiKey.isBlank()) return List(texts.size) { null }
        val results = arrayOfNulls<String>(texts.size)

        val chunks = buildChunks(texts)
        val pool = Executors.newFixedThreadPool(minOf(MAX_CONCURRENCY, maxOf(1, chunks.size)))
        val retryPool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(chunks.size)
        chunks.forEachIndexed { order, indexes ->
            pool.execute {
                try {
                    translateChunk(texts, indexes, target, apiKey, results, retryPool)
                } catch (e: Exception) {
                    Log.w(TAG, "chunk failed: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
        }
        val waitMs = ((chunks.size / MAX_CONCURRENCY) + 1L) * (TIMEOUT_MS + 2_000L)
        runCatching { latch.await(waitMs, TimeUnit.MILLISECONDS) }
        pool.shutdownNow()
        retryPool.shutdownNow()
        return results.toList()
    }

    /** 测 Key 是否可用：拿一句最省 token 的话翻成目标语言 */
    fun testKey(apiKey: String, target: String = TranslateLanguages.DEFAULT): String {
        if (apiKey.isBlank()) return "Key 为空"
        val outcome = requestRaw("Hello, world.", target, apiKey)
        return when {
            outcome.content != null -> "测试通过：Hello, world. → ${outcome.content}"
            outcome.code == 401 -> "测试失败：Key 无效或已失效（401）"
            outcome.code == 403 -> "测试失败：该 Key 没有 glm-4-flash 权限（403）"
            outcome.code == 429 -> "Key 可用，但当前触发限流（429），稍后再试"
            outcome.code > 0 -> "测试失败：HTTP ${outcome.code}${outcome.detail?.let { "（$it）" } ?: ""}"
            else -> "测试失败：网络不通或请求超时"
        }
    }

    private fun buildChunks(texts: List<String>): List<List<Int>> {
        val chunks = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var chars = 0
        texts.forEachIndexed { index, raw ->
            val text = raw.trim()
            if (text.isBlank()) return@forEachIndexed
            if (current.size >= CHUNK_ITEMS || chars + text.length > CHUNK_CHARS) {
                if (current.isNotEmpty()) chunks.add(current)
                current = ArrayList()
                chars = 0
            }
            current.add(index)
            chars += text.length
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    private fun translateChunk(
        texts: List<String>,
        indexes: List<Int>,
        target: String,
        apiKey: String,
        results: Array<String?>,
        retryPool: java.util.concurrent.ExecutorService
    ) {
        // 先试合并请求：多段按行送入，返回行数一致才算成功
        if (indexes.size > 1) {
            val joined = indexes.joinToString("\n") { texts[it].trim() }
            val merged = requestWithRetry(joined, target, apiKey)
            val lines = merged?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (lines != null && lines.size == indexes.size) {
                indexes.forEachIndexed { k, idx ->
                    if (lines[k].isNotBlank()) results[idx] = lines[k]
                }
                return
            }
        }
        // 合并失败或只有一段：这组段落并行逐条重发，不串行排队
        val latch = CountDownLatch(indexes.size)
        indexes.forEach { idx ->
            retryPool.execute {
                try {
                    results[idx] = requestWithRetry(texts[idx].trim(), target, apiKey)
                } catch (e: Exception) {
                    Log.w(TAG, "item failed: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
        }
        runCatching { latch.await(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) }
    }

    private fun requestWithRetry(text: String, target: String, apiKey: String): String? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val outcome = requestRaw(text, target, apiKey)
            if (outcome.content != null) return outcome.content
            if (outcome.code == 401 || outcome.code == 403) return null
            if (attempt < MAX_ATTEMPTS - 1) {
                runCatching { Thread.sleep(if (outcome.code == 429) 1_200L else 400L) }
            }
        }
        return null
    }

    private data class Outcome(val code: Int, val content: String?, val detail: String? = null)

    private fun requestRaw(text: String, target: String, apiKey: String): Outcome {
        val body = JSONObject().apply {
            put("model", MODEL)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", instruction(target))
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", text)
                        }
                    )
                }
            )
            put("temperature", 0.1)
            put("stream", false)
            put("max_tokens", minOf(2048, maxOf(96, (text.length * 2.4f).toInt())))
        }.toString()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val detail = runCatching {
                    val stream = conn.errorStream ?: return@runCatching null
                    JSONObject(stream.bufferedReader().use { it.readText() })
                        .optJSONObject("error")?.optString("message")
                }.getOrNull()
                Log.w(TAG, "http $code ${detail.orEmpty()}")
                Outcome(code, null, detail)
            } else {
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                Outcome(200, parseContent(raw, text))
            }
        } catch (e: Exception) {
            Log.w(TAG, "translate failed: ${e.message}")
            Outcome(-1, null)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** GLM 是通用模型，指令里写死「只出译文、多行按行对应」，避免解释和串行 */
    private fun instruction(target: String): String {
        val name = TranslateLanguages.mtName(target)
        return buildString {
            append("You are a translation engine. Translate the user's text into $name. ")
            append("Output ONLY the translation, with no explanation, no notes and no quotes. ")
            append("If the input has multiple lines, translate each line independently ")
            append("and output exactly the same number of lines, one translation per line, ")
            append("preserving the original order.")
        }
    }

    private fun parseContent(raw: String, original: String): String? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val choices = root.optJSONArray("choices") ?: return null
        val first = choices.optJSONObject(0) ?: return null
        val message = first.optJSONObject("message") ?: return null
        var content = message.optString("content", "").trim()
        if (content.isBlank()) return null
        // 模型偶尔会加引号或前缀，去掉包裹
        content = content.trim().trim('"', '\'', '“', '”', '「', '」')
        if (content.isBlank()) return null
        if (original.isNotBlank() && content.trim().equals(original.trim(), ignoreCase = true)) return null
        return content
    }
}
