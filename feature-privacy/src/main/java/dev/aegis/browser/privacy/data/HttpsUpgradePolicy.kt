package dev.aegis.browser.privacy.data

import android.net.Uri
import dev.aegis.browser.core.web.HttpsPolicy

/**
 * HTTPS-Only 策略实现。升级映射与豁免均为会话内内存状态，
 * 不持久化：下次访问仍会重新尝试升级。
 */
class HttpsUpgradePolicy(
    private val settings: PrivacySettingsStore
) : HttpsPolicy {

    // 升级后的 https URL -> 原始 http URL
    private val upgrades = HashMap<String, String>()

    // 本次会话豁免升级的 host
    private val exemptHosts = HashSet<String>()

    override fun isEnabled(): Boolean = settings.httpsOnly

    private fun isExempt(host: String): Boolean =
        exemptHosts.contains(host.lowercase())

    override fun navigateUrl(url: String): String {
        if (!isEnabled()) return url
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        if (uri.scheme != "http") return url
        val host = uri.host ?: return url
        if (isExempt(host)) return url

        val upgraded = buildUpgradedUrl(uri)
        upgrades[upgraded] = url
        return upgraded
    }

    override fun mainFrameFailed(failedUrl: String): String? = upgrades[failedUrl]

    override fun acceptHttp(originalHttpUrl: String): String {
        val host = runCatching { Uri.parse(originalHttpUrl).host }.getOrNull()
        if (host != null) {
            exemptHosts.add(host.lowercase())
        }
        removeMapping(originalHttpUrl)
        return originalHttpUrl
    }

    override fun decline(originalHttpUrl: String) {
        removeMapping(originalHttpUrl)
    }

    private fun removeMapping(originalHttpUrl: String) {
        val keys = upgrades.filterValues { it == originalHttpUrl }.keys.toList()
        keys.forEach { upgrades.remove(it) }
    }

    private fun buildUpgradedUrl(uri: Uri): String {
        val builder = uri.buildUpon().scheme("https")
        // 显式的 80 端口在 https 下无意义，移除
        if (uri.port == 80) {
            builder.authority(uri.host)
        }
        return builder.build().toString()
    }
}
