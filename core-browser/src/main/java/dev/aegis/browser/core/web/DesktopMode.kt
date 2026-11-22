package dev.aegis.browser.core.web

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * 浏览器 UA 标识与视口：
 * - Android：标准移动 Chrome 串（去掉 WebView 特征）
 * - iPhone：iOS Safari 串
 * - 电脑：Windows Chrome 桌面串（等价原“桌面版网站”）
 */
object DesktopMode {

    fun isEnabled(context: Context): Boolean =
        UserAgentMode.get(context) == UserAgentMode.DESKTOP

    fun setEnabled(context: Context, enabled: Boolean) {
        UserAgentMode.set(
            context,
            if (enabled) UserAgentMode.DESKTOP else UserAgentMode.ANDROID
        )
    }

    /** 把 UA 与视口设置应用到指定 WebView */
    fun applyTo(webView: WebView, context: Context) {
        webView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = currentUserAgent(context)
        }
    }

    /** 按当前 UA 标识返回应当使用的 UA 串 */
    fun currentUserAgent(context: Context): String = when (UserAgentMode.get(context)) {
        UserAgentMode.DESKTOP -> desktopUserAgent(context)
        UserAgentMode.IPHONE -> IPHONE_UA
        else -> mobileUserAgent(context)
    }

    /**
     * 标准移动 Chrome UA：不用系统 WebView 原始串，而是用同一内核版本号重建。
     * 系统串带 "wv"、"Version/4.0"、"Build/机型" 等 WebView 特征，部分站点（如搜狗）
     * 会据此判定为“非正常浏览器”，跳回首页并引导安装自家浏览器。
     * 重建后与普通 Chrome 移动端一致；机型统一为 K，也减少指纹暴露。
     */
    private fun mobileUserAgent(context: Context): String {
        val raw = WebSettings.getDefaultUserAgent(context)
        val chrome = Regex("Chrome/[0-9.]+").find(raw)?.value ?: "Chrome/120.0.0.0"
        val android = Regex("Android [0-9]+(?:\\.[0-9]+)?").find(raw)?.value ?: "Android 10"
        return "Mozilla/5.0 (Linux; $android; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) $chrome Mobile Safari/537.36"
    }

    /** 桌面 UA 下把网页压回手机宽度显示（“电脑网页转为手机版面”） */
    fun isPcToMobile(context: Context): Boolean = prefs(context)
        .getBoolean(KEY_PC_TO_MOBILE, false)

    fun setPcToMobile(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PC_TO_MOBILE, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(UserAgentMode.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 桌面模式下强制把页面 viewport 改为固定宽（桌面宽度），让响应式站点按桌面布局渲染；
     * 若开启“电脑网页转为手机版面”，改为 device-width，让桌面布局按手机宽度收缩。
     * 应在页面加载完成时调用；非桌面模式不处理。
     */
    fun enforceDesktopViewport(webView: WebView, context: Context) {
        if (!isEnabled(context)) return
        val viewport = if (isPcToMobile(context)) "width=device-width, initial-scale=1"
        else "width=1024, user-scalable=yes"
        val script =
            "(function(){" +
                "var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';" +
                "document.head.appendChild(m);}" +
                "m.setAttribute('content','" + viewport + "');" +
                "})();"
        webView.evaluateJavascript(script, null)
    }

    /** 基于系统 UA 的 Chrome 版本构造 Windows 桌面 UA，保持内核版本一致 */
    private fun desktopUserAgent(context: Context): String {
        val mobileUA = WebSettings.getDefaultUserAgent(context)
        val chrome = Regex("Chrome/[0-9.]+").find(mobileUA)?.value
            ?: "Chrome/120.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) $chrome Safari/537.36"
    }

    private const val KEY_PC_TO_MOBILE = "pc_to_mobile"

    private const val IPHONE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 " +
            "Mobile/15E148 Safari/604.1"
}
