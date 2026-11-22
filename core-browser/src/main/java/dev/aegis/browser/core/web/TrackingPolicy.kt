package dev.aegis.browser.core.web

/**
 * 跟踪参数剥离策略：由上层（feature-privacy）提供开关状态。
 */
interface TrackingPolicy {
    fun isEnabled(): Boolean
}
