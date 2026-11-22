package dev.aegis.browser.core.web

/**
 * HTTPS-Only 策略。状态管理（升级映射、豁免）由实现维护，
 * 回调均发生在主线程。
 */
interface HttpsPolicy {

    fun isEnabled(): Boolean

    /**
     * 待导航 URL 实际应加载的地址。
     * http 且未豁免时返回升级后的 https，其余原样返回。
     */
    fun navigateUrl(url: String): String

    /**
     * 主框架加载失败时调用。返回非 null 表示这是一次升级失败，
     * 值为原始 http URL；返回 null 表示与升级无关。
     */
    fun mainFrameFailed(failedUrl: String): String?

    /** 用户接受继续访问 http：豁免 host，返回应加载的 http URL */
    fun acceptHttp(originalHttpUrl: String): String

    /** 用户拒绝，清理升级记录 */
    fun decline(originalHttpUrl: String)
}
