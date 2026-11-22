package dev.aegis.browser.core.web

/**
 * 按站点 JavaScript 权限策略。
 */
interface JsPolicy {

    /** 该 host 是否允许运行 JavaScript */
    fun isJsAllowed(host: String): Boolean
}
