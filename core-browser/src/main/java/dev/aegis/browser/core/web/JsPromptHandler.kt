package dev.aegis.browser.core.web

import android.webkit.WebView

/**
 * 网页 JS 通过 window.prompt 向原生发送消息的拦截器。
 * 用于替代在部分 ROM 上不生效的 addJavascriptInterface。
 */
interface JsPromptHandler {

    /**
     * 处理一次 window.prompt 调用。
     *
     * @param view 触发的 WebView
     * @param message prompt 的完整消息内容（含约定前缀）
     * @return 非 null 表示已消费，该字符串作为 prompt 返回值；null 表示放行给默认行为
     */
    fun handlePrompt(view: WebView, message: String): String?
}
