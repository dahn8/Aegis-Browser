package dev.aegis.browser.ui

import android.content.Context

/**
 * 字体大小设置：以 WebView 的 textZoom（百分比，标准为 100）表示，
 * 提供四档：小 / 标准 / 大 / 特大。
 */
class FontSizeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前文字缩放百分比 */
    var textZoom: Int
        get() = prefs.getInt(KEY_TEXT_ZOOM, DEFAULT_ZOOM)
        set(value) {
            prefs.edit().putInt(KEY_TEXT_ZOOM, value).apply()
        }

    companion object {
        const val ZOOM_SMALL = 85
        const val ZOOM_NORMAL = 100
        const val ZOOM_LARGE = 120
        const val ZOOM_HUGE = 150
        const val DEFAULT_ZOOM = ZOOM_NORMAL

        /** 可选档位（顺序） */
        val LEVELS = listOf(ZOOM_SMALL, ZOOM_NORMAL, ZOOM_LARGE, ZOOM_HUGE)

        private const val PREFS_NAME = "aegis_app"
        private const val KEY_TEXT_ZOOM = "text_zoom"
    }
}
