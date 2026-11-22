package dev.aegis.browser.core.web

import android.view.View
import android.webkit.JsPromptResult
import android.os.Handler
import android.os.Looper
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

class BrowserWebChromeClient(
    private val onProgress: (Int) -> Unit,
    private val onTitle: (String) -> Unit,
    private val promptHandler: JsPromptHandler? = null,
    private val onShowCustomView: ((view: View, callback: WebChromeClient.CustomViewCallback) -> Unit)? = null,
    private val onHideCustomView: (() -> Unit)? = null
) : WebChromeClient() {

    /** 回调里拿不到 view，用最近一次进度回调里的 WebView 兜底 */
    private var lastView: WebView? = null

    /** 摄像头 / 麦克风：按站点权限策略放行，ask 时弹一次确认 */
    override fun onPermissionRequest(request: PermissionRequest?) {
        val req = request ?: return
        val context = lastView?.context ?: run { req.deny(); return }
        val origin = req.origin?.toString().orEmpty()
        val camera = req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val kind = if (camera) SitePermissions.CAMERA else SitePermissions.MIC
        SitePermissions.decide(context, kind, SitePermissions.hostOf(origin)) { granted ->
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    if (granted) req.grant(req.resources) else req.deny()
                }
            }
        }
    }

    /** 定位权限同样走站点策略 */
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        val context = lastView?.context
        if (context == null || callback == null) return
        SitePermissions.decide(context, SitePermissions.LOCATION, SitePermissions.hostOf(origin)) { granted ->
            Handler(Looper.getMainLooper()).post { callback.invoke(origin, granted, false) }
        }
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        if (view != null) lastView = view
        onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        title?.let(onTitle)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view != null && callback != null && onShowCustomView != null) {
            onShowCustomView.invoke(view, callback)
        } else {
            super.onShowCustomView(view, callback)
        }
    }

    override fun onHideCustomView() {
        if (onHideCustomView != null) {
            onHideCustomView.invoke()
        } else {
            super.onHideCustomView()
        }
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?
    ): Boolean {
        if (view != null && message != null) {
            val response = promptHandler?.handlePrompt(view, message)
            if (response != null) {
                result?.confirm(response)
                return true
            }
        }
        return super.onJsPrompt(view, url, message, defaultValue, result)
    }
}
