package dev.aegis.browser.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网站 favicon 加载器：直接向对应站点请求，不经过第三方，保护隐私。
 * 优先解析首页 HTML 中的 apple-touch-icon / 大尺寸 icon（高清），
 * 再回退 /apple-touch-icon.png、/favicon.ico，避免小图放大模糊。
 * 两级缓存：内存 LruCache + 应用内部存储磁盘缓存，冷启动直接读本地显示。
 * 全部在后台线程执行，零三方依赖。
 */
object FaviconLoader {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 特殊站点高清源：这些站点公开 favicon 只有 16/32px，
     * 直接用其官方 App 图标（512px，mzstatic CDN 国内可达）。
     */
    private val specialSources = mapOf(
        "douyin.com" to "https://is1-ssl.mzstatic.com/image/thumb/Purple221/v4/fe/63/e5/fe63e5c3-5629-ff2e-6485-eb08bb3c4c62/AppIcon-0-0-1x_U007emarketing-0-0-0-7-0-0-85-220.png/512x512bb.jpg",
        "www.douyin.com" to "https://is1-ssl.mzstatic.com/image/thumb/Purple221/v4/fe/63/e5/fe63e5c3-5629-ff2e-6485-eb08bb3c4c62/AppIcon-0-0-1x_U007emarketing-0-0-0-7-0-0-85-220.png/512x512bb.jpg",
        "m.douyin.com" to "https://is1-ssl.mzstatic.com/image/thumb/Purple221/v4/fe/63/e5/fe63e5c3-5629-ff2e-6485-eb08bb3c4c62/AppIcon-0-0-1x_U007emarketing-0-0-0-7-0-0-85-220.png/512x512bb.jpg"
    )

    private val cache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** 首字母占位的底色板（用于首字母文字着色） */
    private val placeholderColors = intArrayOf(
        0xFF3B5BDB.toInt(),
        0xFFE8590C.toInt(),
        0xFF2B8A3E.toInt(),
        0xFF9C36B5.toInt(),
        0xFFC2255C.toInt(),
        0xFF0C8599.toInt(),
        0xFFE67700.toInt(),
        0xFF5F3DC4.toInt()
    )

    fun colorFor(key: String): Int =
        placeholderColors[(key.hashCode() and 0x7FFFFFFF) % placeholderColors.size]

    /**
     * 加载图标到 [icon]；[placeholder] 用于显示首字母。
     * 顺序：内存 → 磁盘 → 网络（高清源优先）；成功后写入两级缓存。
     */
    fun load(
        icon: ImageView,
        placeholder: TextView,
        pageUrl: String,
        title: String,
        context: Context,
        onReady: ((width: Int, height: Int) -> Unit)? = null,
        /** 小于该像素宽高的图标视为不可用，保留首字母占位 */
        minSide: Int = 0
    ) {
        val appContext = context.applicationContext
        val host = hostOf(pageUrl)
        val initial = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        placeholder.text = initial
        placeholder.visibility = View.VISIBLE
        icon.visibility = View.INVISIBLE

        if (host == null) return

        cache.get(host)?.let {
            if (it.width >= minSide && it.height >= minSide) {
                showBitmap(icon, placeholder, it)
                onReady?.invoke(it.width, it.height)
                return
            }
            cache.remove(host)
        }

        icon.tag = host
        Thread {
            // 1. 磁盘缓存：命中即显示，冷启动无需等待网络
            val diskBitmap = readDisk(appContext, host)
            // 磁盘缓存是高清图（>=120px）才直接采用；低清旧缓存继续走网络抓高清
            if (diskBitmap != null && diskBitmap.width >= 120 && diskBitmap.height >= 120) {
                cache.put(host, diskBitmap)
                mainHandler.post {
                    if (icon.tag == host && diskBitmap.width >= minSide && diskBitmap.height >= minSide) {
                        showBitmap(icon, placeholder, diskBitmap)
                        onReady?.invoke(diskBitmap.width, diskBitmap.height)
                    }
                }
                return@Thread
            }
            // 2. 网络请求（高清源优先），成功后回写磁盘与内存，覆盖低清旧缓存
            val fetched = fetch(host) ?: diskBitmap
            if (fetched != null) {
                cache.put(host, fetched)
                writeDisk(appContext, host, fetched)
                mainHandler.post {
                    if (icon.tag == host && fetched.width >= minSide && fetched.height >= minSide) {
                        showBitmap(icon, placeholder, fetched)
                        onReady?.invoke(fetched.width, fetched.height)
                    }
                }
            }
            // 失败：保持首字母占位
        }.start()
    }

    private fun showBitmap(icon: ImageView, placeholder: TextView, bitmap: Bitmap) {
        icon.setImageBitmap(bitmap)
        icon.visibility = View.VISIBLE
        placeholder.visibility = View.INVISIBLE
    }

    private fun hostOf(pageUrl: String): String? =
        runCatching { Uri.parse(pageUrl).host }.getOrNull()

    // ---- 网络：高清源优先 ----

    private fun fetch(host: String): Bitmap? {
        // 收集所有候选 URL（HTML 解析 + 常见路径），按优先级去重
        val candidates = linkedSetOf<String>()
        specialSources[host]?.let { candidates.add(it) }
        resolveIconCandidates(host).forEach { candidates.add(it) }
        candidates.add("https://$host/apple-touch-icon.png")
        candidates.add("https://$host/apple-touch-icon-precomposed.png")
        candidates.add("https://$host/favicon.ico")

        // 依次下载：优先返回实际像素足够大的高清图（设备高密度下 54dp 约需 120px+）
        var fallback: Bitmap? = null
        for (url in candidates) {
            val bmp = downloadBitmap(url) ?: continue
            if (bmp.width >= 120 && bmp.height >= 120) return bmp
            if (fallback == null) fallback = bmp
        }
        return fallback
    }

    /** 拉取首页 HTML 并解析其中的图标链接，返回按优先级排序的绝对 URL 列表 */
    private fun resolveIconCandidates(host: String): List<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://$host/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AegisBrowser")
                setRequestProperty("Accept", "text/html")
            }
            val html = connection.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var read: Int
                var total = 0
                while (reader.read(buf).also { read = it } != -1 && total < 200_000) {
                    sb.append(buf, 0, read)
                    total += read
                }
                sb.toString()
            }
            parseIconLinks(html, host)
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private data class IconCandidate(val url: String, val isApple: Boolean, val size: Int)

    private fun parseIconLinks(html: String, host: String): List<String> {
        val linkRegex = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        val candidates = mutableListOf<IconCandidate>()
        linkRegex.findAll(html).forEach { match ->
            val tag = match.value
            val rel = attr(tag, "rel")?.lowercase() ?: return@forEach
            if (!rel.contains("icon")) return@forEach
            val href = attr(tag, "href") ?: return@forEach
            val isApple = rel.contains("apple-touch-icon")
            val size = parseLargestSize(attr(tag, "sizes")).let { parsed ->
                // apple 未标注尺寸时按常见 180 处理，使其优先
                if (parsed == 0 && isApple) 180 else parsed
            }
            candidates.add(IconCandidate(resolveUrl(href, host), isApple, size))
        }
        if (candidates.isEmpty()) return emptyList()
        // apple-touch-icon 优先，其次尺寸最大；返回去重后的 URL 列表
        return candidates
            .sortedWith(
                compareByDescending<IconCandidate> { it.isApple }
                    .thenByDescending { it.size }
            )
            .map { it.url }
            .distinct()
    }

    private fun attr(tag: String, name: String): String? {
        val regex = Regex("\\b$name\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
        return regex.find(tag)?.groupValues?.get(2)?.trim()
    }

    private fun parseLargestSize(sizes: String?): Int {
        if (sizes.isNullOrBlank()) return 0
        if (sizes.contains("any", ignoreCase = true)) return 1
        val number = Regex("(\\d+)x\\d+", RegexOption.IGNORE_CASE)
        return number.findAll(sizes).maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } ?: 0
    }

    private fun resolveUrl(href: String, host: String): String {
        val value = href.trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> "https://$host$value"
            else -> "https://$host/$value"
        }
    }

    private fun downloadBitmap(urlString: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AegisBrowser")
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            decodeImageBytes(bytes)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 解码图片字节；ICO 文件解析目录，挑尺寸最大的 PNG 帧，没有 PNG 帧再整体解码 */
    private fun decodeImageBytes(bytes: ByteArray): Bitmap? {
        if (bytes.size > 22 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte()
            && bytes[2] == 1.toByte() && bytes[3] == 0.toByte()
        ) {
            val count = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
            var bestOffset = -1
            var bestSize = 0
            var bestArea = 0
            for (i in 0 until count) {
                val dir = 6 + i * 16
                if (dir + 16 > bytes.size) break
                val w = bytes[dir].toInt() and 0xFF
                val h = bytes[dir + 1].toInt() and 0xFF
                val width = if (w == 0) 256 else w
                val height = if (h == 0) 256 else h
                val size = readIntLE(bytes, dir + 8)
                val offset = readIntLE(bytes, dir + 12)
                if (offset + 4 > bytes.size || size <= 0) continue
                val isPng = bytes[offset] == 0x89.toByte() && bytes[offset + 1] == 0x50.toByte()
                if (isPng && width * height > bestArea) {
                    bestArea = width * height
                    bestOffset = offset
                    bestSize = size
                }
            }
            if (bestOffset >= 0 && bestOffset + bestSize <= bytes.size) {
                BitmapFactory.decodeByteArray(bytes, bestOffset, bestSize)?.let { return it }
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    // ---- 磁盘缓存 ----

    private fun cacheDir(context: Context): File {
        // v5：缩小卡片，重新获取最优图标
        val dir = File(context.filesDir, "favicon_cache_v5")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 用 host 生成安全文件名（保留字母数字、点、横线） */
    private fun fileName(host: String): String =
        host.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".png"

    private fun readDisk(context: Context, host: String): Bitmap? =
        runCatching {
            val file = File(cacheDir(context), fileName(host))
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }.getOrNull()

    private fun writeDisk(context: Context, host: String, bitmap: Bitmap) {
        runCatching {
            val file = File(cacheDir(context), fileName(host))
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }
}
