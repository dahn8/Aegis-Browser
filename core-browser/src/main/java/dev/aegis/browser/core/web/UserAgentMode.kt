package dev.aegis.browser.core.web

import android.content.Context

/**
 * 浏览器 UA 标识：Android / iPhone / 电脑。
 * 与容量拦截、下载等共用 aegis_browser 配置文件，供设置页与内核共同读写。
 */
object UserAgentMode {

    const val ANDROID = "android"
    const val IPHONE = "iphone"
    const val DESKTOP = "desktop"

    const val PREFS_NAME = "aegis_browser"
    private const val KEY_UA_MODE = "ua_mode"
    private const val KEY_LEGACY_DESKTOP = "desktop_mode"

    fun get(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_UA_MODE, null)
        if (stored == ANDROID || stored == IPHONE || stored == DESKTOP) return stored
        // 兼容旧的“桌面版网站”开关
        return if (prefs.getBoolean(KEY_LEGACY_DESKTOP, false)) DESKTOP else ANDROID
    }

    fun set(context: Context, mode: String) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_UA_MODE, mode)
            .putBoolean(KEY_LEGACY_DESKTOP, mode == DESKTOP)
            .apply()
    }
}
