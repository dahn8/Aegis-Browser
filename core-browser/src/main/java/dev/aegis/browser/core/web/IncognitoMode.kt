package dev.aegis.browser.core.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * 无痕模式（会话级，不持久化，重启即关闭）。
 * 开启后：不写入缓存（LOAD_NO_STORE）、不接受 Cookie，
 * 配合主界面跳过历史记录，并在退出时清理会话数据。
 */
object IncognitoMode {

    private var enabledCache = false

    fun isEnabled(context: Context): Boolean = enabledCache

    fun setEnabled(context: Context, enabled: Boolean) {
        enabledCache = enabled
    }

    /** 把无痕/正常的缓存与 Cookie 策略应用到指定 WebView */
    fun applyTo(webView: WebView, context: Context) {
        webView.settings.cacheMode = if (enabledCache)
            WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        // 第三方 Cookie 始终拒绝；无痕时第一方 Cookie 由全局开关一并关闭
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }
}
