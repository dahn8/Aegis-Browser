package dev.aegis.browser.core.web

/**
 * 页面启动时注入的 JS 脚本策略，由隐私模块实现。
 */
interface ScriptProvider {

    /**
     * @param pageHost 主文档域名
     * @return 待执行的 JavaScript，null 表示不注入
     */
    fun scriptFor(pageHost: String?): String?
}
