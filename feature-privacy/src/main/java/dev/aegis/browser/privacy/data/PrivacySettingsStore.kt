package dev.aegis.browser.privacy.data

import android.content.Context

/**
 * 隐私设置存储：退出自动清除、HTTPS-Only 开关，
 * 以及禁用 JavaScript 的站点集合。与拦截器共用同一 prefs 文件。
 */
class PrivacySettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var clearOnExit: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_ON_EXIT, DEFAULT_CLEAR_ON_EXIT)
        set(value) {
            prefs.edit().putBoolean(KEY_CLEAR_ON_EXIT, value).apply()
        }

    var httpsOnly: Boolean
        get() = prefs.getBoolean(KEY_HTTPS_ONLY, DEFAULT_HTTPS_ONLY)
        set(value) {
            prefs.edit().putBoolean(KEY_HTTPS_ONLY, value).apply()
        }

    var trackingStrip: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_STRIP, DEFAULT_TRACKING_STRIP)
        set(value) {
            prefs.edit().putBoolean(KEY_TRACKING_STRIP, value).apply()
        }

    /**
     * 反指纹基线：统一 navigator 的平台/厂商/硬件并发等取值，
     * 让 WebView 指纹与 UA 自洽，不与真实设备特征拼出独一无二组合。
     */
    var antiFingerprint: Boolean
        get() = prefs.getBoolean(KEY_ANTI_FINGERPRINT, DEFAULT_ANTI_FINGERPRINT)
        set(value) {
            prefs.edit().putBoolean(KEY_ANTI_FINGERPRINT, value).apply()
        }

    fun jsBlockedHosts(): Set<String> =
        prefs.getStringSet(KEY_JS_BLOCKED, emptySet())?.toSet() ?: emptySet()

    fun isJsBlocked(host: String): Boolean =
        jsBlockedHosts().contains(host.lowercase())

    fun blockJs(host: String) {
        val updated = jsBlockedHosts().toMutableSet().apply { add(host.lowercase()) }
        prefs.edit().putStringSet(KEY_JS_BLOCKED, updated).apply()
    }

    fun unblockJs(host: String) {
        val updated = jsBlockedHosts().toMutableSet().apply { remove(host.lowercase()) }
        prefs.edit().putStringSet(KEY_JS_BLOCKED, updated).apply()
    }

    companion object {
        private const val PREFS_NAME = "aegis_privacy"

        private const val KEY_CLEAR_ON_EXIT = "clear_on_exit"
        private const val KEY_HTTPS_ONLY = "https_only"
        private const val KEY_JS_BLOCKED = "js_blocked"
        private const val KEY_TRACKING_STRIP = "tracking_strip"
        private const val KEY_ANTI_FINGERPRINT = "anti_fingerprint"

        private const val DEFAULT_CLEAR_ON_EXIT = false
        private const val DEFAULT_HTTPS_ONLY = false
        private const val DEFAULT_TRACKING_STRIP = true
        private const val DEFAULT_ANTI_FINGERPRINT = true
    }
}
