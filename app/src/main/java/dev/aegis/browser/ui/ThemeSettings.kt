package dev.aegis.browser.ui

import android.content.Context

/**
 * 外观设置存储：浅色 / 深色 / 跟随系统，默认跟随系统。
 */
class ThemeSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, ThemeManager.MODE_SYSTEM)
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "aegis_app"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
