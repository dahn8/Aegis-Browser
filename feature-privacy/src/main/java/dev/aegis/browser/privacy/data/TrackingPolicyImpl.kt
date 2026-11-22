package dev.aegis.browser.privacy.data

import dev.aegis.browser.core.web.TrackingPolicy

/**
 * 跟踪参数剥离策略实现，开关来自隐私设置（默认开启）。
 */
class TrackingPolicyImpl(
    private val settings: PrivacySettingsStore
) : TrackingPolicy {

    override fun isEnabled(): Boolean = settings.trackingStrip
}
