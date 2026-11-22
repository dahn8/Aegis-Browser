package dev.aegis.browser.core.tab

import dev.aegis.browser.core.web.PrivacyWebView

const val BLANK_URL = "about:blank"

class BrowserTab(
    val webView: PrivacyWebView,
    var title: String = "",
    var url: String = BLANK_URL,
    var progress: Int = 0
)
