package dev.aegis.browser.core.web

import android.net.Uri

/**
 * 资源拦截策略接口，由隐私模块实现并注入。
 * shouldInterceptRequest 在非 UI 线程回调，实现必须保证线程安全。
 */
interface RequestInterceptor {

    /**
     * @param uri 待加载资源的完整 URI
     * @param pageHost 当前页面主文档域名
     * @param isForMainFrame 是否为主框架请求
     * @return 非 null 表示拦截
     */
    fun intercept(uri: Uri, pageHost: String?, isForMainFrame: Boolean): BlockDecision?
}

data class BlockDecision(
    val reason: String,
    val mimeType: String? = null
)
