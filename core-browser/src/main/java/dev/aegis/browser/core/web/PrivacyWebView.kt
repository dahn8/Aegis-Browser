package dev.aegis.browser.core.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * 隐私优先的 WebView 封装。
 * 必须使用 Activity Context 创建，不能使用 applicationContext。
 */
class PrivacyWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    init {
        applyPrivacySettings()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyPrivacySettings() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // 安全加固：关闭 file:// 访问，媒体播放需用户手势
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT

            // API 33+ 允许系统暗色时对网页内容做算法变暗（反射调用以兼容符号差异）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    WebSettings::class.java
                        .getMethod("setAlgorithmicDarkeningAllowed", Boolean::class.javaPrimitiveType)
                        .invoke(this, true)
                }
            }
        }

        // 隐私默认：接受第一方 Cookie，拒绝第三方 Cookie
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@PrivacyWebView, false)
        }

        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
}
