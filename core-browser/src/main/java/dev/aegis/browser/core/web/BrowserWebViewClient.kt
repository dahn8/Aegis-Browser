package dev.aegis.browser.core.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

class BrowserWebViewClient(
    private val onStarted: (String) -> Unit,
    private val onFinished: (String) -> Unit,
    private val requestInterceptor: RequestInterceptor? = null,
    private val scriptProvider: ScriptProvider? = null,
    private val httpsPolicy: HttpsPolicy? = null,
    private val jsPolicy: JsPolicy? = null,
    private val trackingPolicy: TrackingPolicy? = null,
    private val blobDownloader: BlobDownloader? = null,
    private val upgradeCallback: HttpsUpgradeCallback? = null,
    private val onMainFrameError: ((errorCode: Int, description: String, failingUrl: String) -> Unit)? = null,
    /** 发生一次站外跳转拦截时的回调，用于统计 */
    private val onExternalBlocked: (() -> Unit)? = null
) : WebViewClient() {

    private var lastStartedUrl: String? = null

    /**
     * 缓存在 UI 线程更新的当前页面 host。
     * shouldInterceptRequest 在 WebView IO 线程回调，不能直接访问 view.url。
     */
    @Volatile
    private var currentPageHost: String? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val scheme = uri.scheme ?: return false

        if (scheme != "http" && scheme != "https") {
            // 页面内协议（data:/blob:/about:）交回 WebView 自行处理
            if (scheme == "data" || scheme == "blob" || scheme == "about") return false
            // 电话、短信、邮件这类明确的通信意图始终交给系统
            if (scheme == "tel" || scheme == "sms" || scheme == "mailto") {
                openExternal(view, uri)
                return true
            }
            // 隐私优先：按站点权限决定是否放行站外应用跳转
            val policy = SitePermissions.state(view.context, SitePermissions.OPEN_APP)
            if (policy == SitePermissions.ASK) {
                SitePermissions.decide(view.context, SitePermissions.OPEN_APP, uri.host ?: "") { granted ->
                    if (granted) openExternal(view, uri)
                }
                return true
            }
            if (policy != SitePermissions.ALLOW) {
                onExternalBlocked?.invoke()
                return true
            }
            openExternal(view, uri)
            return true
        }

        val original = uri.toString()
        var target = original

        // 先剥离跟踪参数
        if (trackingPolicy != null && trackingPolicy.isEnabled()) {
            target = TrackingParamStripper.strip(target)
        }
        // 再做 HTTPS-Only 升级
        if (httpsPolicy != null && httpsPolicy.isEnabled() && scheme == "http") {
            target = httpsPolicy.navigateUrl(target)
        }

        if (request.isForMainFrame) {
            currentPageHost = runCatching { Uri.parse(target).host }.getOrNull()
        }

        if (target != original) {
            view.loadUrl(target)
            return true
        }
        return false
    }

    /** 把链接交给系统处理，失败时静默 */
    private fun openExternal(view: WebView, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            view.context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (!request.isForMainFrame) {
            runCatching { ResourceLog.add(request.url.toString(), "") }
        }
        val interceptor = requestInterceptor ?: return null
        val decision = try {
            interceptor.intercept(
                uri = request.url,
                pageHost = currentPageHost,
                isForMainFrame = request.isForMainFrame
            )
        } catch (_: Throwable) {
            null
        } ?: return null

        // 返回空响应体，请求不会离开设备
        return try {
            WebResourceResponse(
                decision.mimeType ?: "text/plain",
                "utf-8",
                ByteArrayInputStream(ByteArray(0))
            )
        } catch (_: Throwable) {
            null
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        currentPageHost = host

        // 新页面开始：资源日志从这一页重新记（同页内跳转不清，避免重定向把日志清空）
        if (url != null && url != lastStartedUrl) {
            lastStartedUrl = url
            ResourceLog.startPage(url)
        }

        // 第三方 Cookie：按当前站点策略设置（策略是 WebView 实例级的，切站点时重设）
        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(
                view,
                !CookiePolicy.shouldBlock(view.context, host)
            )
        } catch (_: Throwable) {
        }

        // 按站点设置 JavaScript 开关，需在文档脚本执行前生效
        try {
            view.settings.javaScriptEnabled = if (jsPolicy == null || host == null) {
                true
            } else {
                jsPolicy.isJsAllowed(host)
            }
        } catch (_: Throwable) {
        }

        try {
            // blob/data 下载拦截脚本，每个页面都注入
            blobDownloader?.hookScript?.let { view.evaluateJavascript(it, null) }
        } catch (_: Throwable) {
        }

        try {
            scriptProvider?.scriptFor(host)?.let { view.evaluateJavascript(it, null) }
        } catch (_: Throwable) {
        }
        url?.let(onStarted)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        // 页面结构稳定后再注入一次隐藏规则：onPageStarted 时文档可能尚未就绪，
        // 部分站点（如必应）滚动后才挂出的元素需要这次补注入才能覆盖。
        try {
            val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
            currentPageHost = host
            scriptProvider?.scriptFor(host)?.let { view.evaluateJavascript(it, null) }
        } catch (_: Throwable) {
        }
        url?.let(onFinished)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError?
    ) {
        if (!request.isForMainFrame) return
        val failedUrl = request.url.toString()

        // 先判断是否为 HTTPS 升级失败，沿用原有询问流程
        val original = httpsPolicy?.mainFrameFailed(failedUrl)
        if (original != null) {
            upgradeCallback?.onUpgradeFailed(original) { accept ->
                if (accept) {
                    view.loadUrl(httpsPolicy.acceptHttp(original))
                } else {
                    httpsPolicy.decline(original)
                }
            }
            return
        }

        // 普通主框架错误：回调显示自定义错误页
        val code = error?.errorCode ?: ERROR_UNKNOWN
        val desc = error?.description?.toString()?.takeIf { it.isNotBlank() }.orEmpty()
        onMainFrameError?.invoke(code, desc, failedUrl)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) {
        // 安全优先：默认不降级；宿主注册了提示器时交给它决定
        val host = runCatching { error?.url?.let { java.net.URI(it).host } }.getOrNull() ?: ""
        val code = runCatching { error?.primaryError }.getOrNull() ?: 0
        val warn = SslWarning.handler
        if (warn != null) {
            warn.onWarning(view, handler, host, code)
        } else {
            handler.cancel()
        }
    }
}
