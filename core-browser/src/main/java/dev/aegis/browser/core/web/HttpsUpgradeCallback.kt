package dev.aegis.browser.core.web

/**
 * HTTPS 升级失败时的 UI 回调，由 Activity 实现以展示确认对话框。
 */
interface HttpsUpgradeCallback {

    /**
     * @param originalHttpUrl 原始 http URL
     * @param onDecision true 表示用户坚持以 http 继续，false 表示放弃访问
     */
    fun onUpgradeFailed(originalHttpUrl: String, onDecision: (acceptHttp: Boolean) -> Unit)
}
