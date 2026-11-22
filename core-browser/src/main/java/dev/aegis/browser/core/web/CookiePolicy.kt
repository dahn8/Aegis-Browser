package dev.aegis.browser.core.web

import android.content.Context

/**
 * 第三方 Cookie 策略（与主工程共用 aegis_privacy 偏好）。
 *
 * block_third_party 为 true 时默认拦截第三方 Cookie，third_party_allow 里的站点单独放行。
 * 放在 core-browser 里，WebView 创建与每次导航都能读到同一份设置，不需要额外参数传递。
 */
object CookiePolicy {

    private const val PREFS_NAME = "aegis_privacy"
    private const val KEY_BLOCK = "block_third_party"
    private const val KEY_ALLOW = "third_party_allow"

    fun blockThirdParty(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BLOCK, true)
    }

    fun setBlockThirdParty(context: Context, block: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BLOCK, block).apply()
    }

    fun allowList(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_ALLOW, emptySet()) ?: emptySet()
    }

    fun allow(context: Context, host: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = allowList(context).toMutableSet().apply { add(host.lowercase()) }
        prefs.edit().putStringSet(KEY_ALLOW, updated).apply()
    }

    fun removeAllow(context: Context, host: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = allowList(context).toMutableSet().apply { remove(host.lowercase()) }
        prefs.edit().putStringSet(KEY_ALLOW, updated).apply()
    }

    /** 该站点是否仍然拦第三方 Cookie（开关关闭时为 false） */
    fun shouldBlock(context: Context, host: String?): Boolean {
        if (!blockThirdParty(context)) return false
        val h = host?.lowercase()?.trimEnd('.') ?: return true
        return !allowList(context).contains(h)
    }
}
