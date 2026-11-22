package dev.aegis.browser.core.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.UUID

/**
 * blob/data 下载实现：JS 桥接对象接收 data URL，原生解码后
 * 通过统一的 [DownloadFileStore] 写入下载目录，并把记录回传给
 * [DownloadProgressListener]。blob 为一次性数据，无中间进度。
 */
class BlobDownloaderImpl(
    context: Context,
    private val progressListener: DownloadProgressListener? = null,
    private val onDownloadStarted: (() -> Unit)? = null
) : BlobDownloader {

    private val appContext = context.applicationContext
    private val fileStore = DownloadFileStore(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    override val hookScript: String = HOOK_JS

    override fun attach(webView: WebView) {
        webView.addJavascriptInterface(BlobBridge(), BRIDGE_NAME)
    }

    private inner class BlobBridge {

        @JavascriptInterface
        fun onBlobDownload(filename: String, mime: String, dataUrl: String) {
            val safeName = filename.ifBlank { "download" }.trim()
            val id = UUID.randomUUID().toString()
            onDownloadStarted?.invoke()
            try {
                val (resolvedMime, bytes) = parseDataUrl(dataUrl, mime)
                if (bytes.isEmpty()) {
                    progressListener?.onFail(id, safeName, "无数据")
                    toast("下载失败：无数据")
                    return
                }
                progressListener?.onStart(id, safeName, resolvedMime, bytes.size.toLong())
                val saved = fileStore.write(safeName, resolvedMime) { output ->
                    ByteArrayInputStream(bytes).use { it.copyTo(output) }
                }
                progressListener?.onComplete(id, saved)
                toast(saved.name)
            } catch (t: Throwable) {
                progressListener?.onFail(id, safeName, t.message ?: "未知错误")
                toast("下载失败：${t.message ?: "未知错误"}")
            }
        }
    }

    /** 解析 data:[mime][;base64],data，返回 MIME 与字节数组 */
    private fun parseDataUrl(dataUrl: String, fallbackMime: String): Pair<String, ByteArray> {
        val comma = dataUrl.indexOf(',')
        if (comma < 0 || !dataUrl.startsWith("data:")) {
            return fallbackMime.ifBlank { GENERIC_MIME } to ByteArray(0)
        }
        val meta = dataUrl.substring(5, comma)
        val rawData = dataUrl.substring(comma + 1)
        val isBase64 = meta.contains(";base64")
        val mime = meta.substringBefore(';').ifBlank {
            fallbackMime.ifBlank { GENERIC_MIME }
        }
        val bytes = if (isBase64) {
            Base64.decode(rawData, Base64.DEFAULT)
        } else {
            URLDecoder.decode(rawData, "UTF-8").toByteArray(Charsets.UTF_8)
        }
        return mime to bytes
    }

    private fun toast(message: String) {
        mainHandler.post {
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_LONG)
                .show()
        }
    }

    companion object {
        private const val BRIDGE_NAME = "AegisDownload"
        private const val GENERIC_MIME = "application/octet-stream"

        private val HOOK_JS = """
(function() {
  if (window.__aegisBlob) return;
  window.__aegisBlob = true;
  function isBlob(u) {
    return typeof u === 'string' && (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0);
  }
  function send(url, name) {
    try {
      fetch(url).then(function(r) { return r.blob(); }).then(function(b) {
        var reader = new FileReader();
        reader.onloadend = function() {
          try {
            AegisDownload.onBlobDownload(
              name || 'download',
              b.type || 'application/octet-stream',
              reader.result
            );
          } catch (e) {}
        };
        reader.readAsDataURL(b);
      }).catch(function() {});
    } catch (e) {}
  }
  function findAnchor(el) {
    while (el && el.tagName !== 'A') { el = el.parentNode; }
    return el && el.tagName === 'A' ? el : null;
  }
  document.addEventListener('click', function(e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : findAnchor(e.target);
    if (!a) return;
    var href = a.href;
    if (isBlob(href)) {
      e.preventDefault();
      e.stopPropagation();
      send(href, a.getAttribute('download') || a.download || 'download');
    }
  }, true);
  try {
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
      if (isBlob(this.href)) {
        send(this.href, this.getAttribute('download') || 'download');
        return;
      }
      return origClick.apply(this, arguments);
    };
  } catch (e) {}
})();
        """.trimIndent()
    }
}
