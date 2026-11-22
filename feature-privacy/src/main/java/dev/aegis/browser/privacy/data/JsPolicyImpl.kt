package dev.aegis.browser.privacy.data

import dev.aegis.browser.core.web.JsPolicy

/**
 * 按站点 JavaScript 策略：默认允许，仅对黑名单中的 host 禁用。
 */
class JsPolicyImpl(
    private val settings: PrivacySettingsStore
) : JsPolicy {

    override fun isJsAllowed(host: String): Boolean = !settings.isJsBlocked(host)
}
