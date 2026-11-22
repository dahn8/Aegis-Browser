package dev.aegis.browser.core.tab

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import dev.aegis.browser.core.web.BlobDownloader
import dev.aegis.browser.core.web.BrowserWebChromeClient
import dev.aegis.browser.core.web.BrowserWebViewClient
import dev.aegis.browser.core.web.CookiePolicy
import dev.aegis.browser.core.web.DesktopMode
import dev.aegis.browser.core.web.DownloadHandler
import dev.aegis.browser.core.web.HttpsPolicy
import dev.aegis.browser.core.web.HttpsUpgradeCallback
import dev.aegis.browser.core.web.IncognitoMode
import dev.aegis.browser.core.web.JsPolicy
import dev.aegis.browser.core.web.JsPromptHandler
import dev.aegis.browser.core.web.PrivacyWebView
import dev.aegis.browser.core.web.RequestInterceptor
import dev.aegis.browser.core.web.ScriptProvider
import dev.aegis.browser.core.web.TrackingPolicy

/**
 * 多标签管理器：每个标签持有一个独立 WebView，
 * 通过 visibility 切换实现标签页复用，避免反复创建销毁。
 */
class TabManager(
    private val context: Context,
    private val container: ViewGroup,
    private val requestInterceptor: RequestInterceptor? = null,
    private val scriptProvider: ScriptProvider? = null,
    private val httpsPolicy: HttpsPolicy? = null,
    private val jsPolicy: JsPolicy? = null,
    private val trackingPolicy: TrackingPolicy? = null,
    private val downloadHandler: DownloadHandler? = null,
    private val blobDownloader: BlobDownloader? = null,
    private val upgradeCallback: HttpsUpgradeCallback? = null,
    private val promptHandler: JsPromptHandler? = null,
    private val mainFrameErrorCallback: ((errorCode: Int, description: String, failingUrl: String) -> Unit)? = null,
    private val onShowCustomView: ((view: View, callback: WebChromeClient.CustomViewCallback) -> Unit)? = null,
    private val onHideCustomView: (() -> Unit)? = null,
    /** 站外跳转拦截计数回调 */
    private val onExternalBlocked: (() -> Unit)? = null
) {
    private val tabs = mutableListOf<BrowserTab>()
    private var currentIndex = -1

    val tabCount: Int get() = tabs.size
    val currentTab: BrowserTab? get() = tabs.getOrNull(currentIndex)
    val currentTabIndex: Int get() = currentIndex

    /** 切换 UA 标识后，把所有已打开标签的 UA 同步并重载，使设置立即生效 */
    fun refreshUserAgent() {
        tabs.forEach { tab ->
            DesktopMode.applyTo(tab.webView, tab.webView.context)
            if (tab.url != BLANK_URL) tab.webView.reload()
        }
    }

    var listener: Listener? = null

    interface Listener {
        fun onTabChanged(tab: BrowserTab)
        fun onTabUpdated(tab: BrowserTab)
        fun onTabsChanged()

        // 页面真正开始加载（onPageStarted），带默认空实现
        fun onPageStarted(tab: BrowserTab) {}

        // 页面加载完成（onPageFinished），带默认空实现
        fun onPageFinished(tab: BrowserTab) {}
    }

    fun newTab(url: String = BLANK_URL): BrowserTab {
        val webView = PrivacyWebView(context)
        val tab = BrowserTab(webView)
        runCatching {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(
                webView,
                !CookiePolicy.shouldBlock(context, null)
            )
        }

        webView.webViewClient = BrowserWebViewClient(
            onStarted = { u ->
                tab.url = u
                if (currentTab === tab) {
                    listener?.onPageStarted(tab)
                    listener?.onTabUpdated(tab)
                }
            },
            onFinished = { u ->
                tab.url = u
                tab.title = webView.title ?: u
                if (currentTab === tab) {
                    listener?.onTabUpdated(tab)
                    listener?.onPageFinished(tab)
                }
            },
            requestInterceptor = requestInterceptor,
            scriptProvider = scriptProvider,
            httpsPolicy = httpsPolicy,
            jsPolicy = jsPolicy,
            trackingPolicy = trackingPolicy,
            blobDownloader = blobDownloader,
            upgradeCallback = upgradeCallback,
            onMainFrameError = mainFrameErrorCallback,
            onExternalBlocked = onExternalBlocked
        )
        webView.webChromeClient = BrowserWebChromeClient(
            onProgress = { p ->
                tab.progress = p
                if (currentTab === tab) listener?.onTabUpdated(tab)
            },
            onTitle = { t ->
                tab.title = t
                if (currentTab === tab) listener?.onTabUpdated(tab)
            },
            promptHandler = promptHandler,
            onShowCustomView = onShowCustomView,
            onHideCustomView = onHideCustomView
        )
        webView.setDownloadListener { dlUrl, userAgent, disposition, mimeType, _ ->
            downloadHandler?.handle(dlUrl, userAgent, disposition, mimeType)
        }
        blobDownloader?.attach(webView)
        // 应用桌面版网站开关（UA 与视口）
        DesktopMode.applyTo(webView, context)
        // 应用无痕模式（缓存与 Cookie 策略）
        IncognitoMode.applyTo(webView, context)

        container.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        tabs.add(tab)
        if (url != BLANK_URL) tab.url = url
        selectTab(tabs.lastIndex)
        if (url != BLANK_URL) webView.loadUrl(url)
        listener?.onTabsChanged()
        return tab
    }

    fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        currentIndex = index
        tabs.forEachIndexed { i, tab ->
            tab.webView.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        listener?.onTabChanged(tabs[index])
    }

    fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        container.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            currentIndex = -1
            listener?.onTabsChanged()
            return
        }

        val newIndex = when {
            currentIndex > index -> currentIndex - 1
            currentIndex == index -> index.coerceAtMost(tabs.lastIndex)
            else -> currentIndex
        }
        selectTab(newIndex)
        listener?.onTabsChanged()
    }

    fun tabsSnapshot(): List<BrowserTab> = tabs.toList()

    fun destroy() {
        tabs.forEach {
            container.removeView(it.webView)
            it.webView.destroy()
        }
        tabs.clear()
        currentIndex = -1
    }
}
