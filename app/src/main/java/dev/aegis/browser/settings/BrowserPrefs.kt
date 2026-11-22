package dev.aegis.browser.settings

import android.content.Context

/**
 * 浏览器杂项设置与安全统计。
 */
class BrowserPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 网页安全防护：拦截站外应用跳转（intent:/market:/自定义 scheme） */
    var blockExternalEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_EXTERNAL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCK_EXTERNAL, value).apply()
        }

    /** 累计拦截站外跳转次数 */
    var externalBlockedCount: Int
        get() = prefs.getInt(KEY_EXTERNAL_COUNT, 0)
        set(value) {
            prefs.edit().putInt(KEY_EXTERNAL_COUNT, value).apply()
        }

    fun addExternalBlocked() {
        externalBlockedCount = externalBlockedCount + 1
    }

    fun resetExternalBlocked() {
        externalBlockedCount = 0
    }

    /** 启动时打开上次页面/恢复多标签 */
    var openLastPage: Boolean
        get() = prefs.getBoolean(KEY_OPEN_LAST_PAGE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OPEN_LAST_PAGE, value).apply()
        }

    /** 上次离开时正在浏览的页面网址（主页离开则为空） */
    var lastPageUrl: String?
        get() = prefs.getString(KEY_LAST_PAGE_URL, null)
        set(value) {
            val edit = prefs.edit()
            if (value.isNullOrBlank()) edit.remove(KEY_LAST_PAGE_URL)
            else edit.putString(KEY_LAST_PAGE_URL, value)
            edit.apply()
        }

    /** 发送「请勿追踪 (Do Not Track / GPC)」标识 */
    var doNotTrack: Boolean
        get() = prefs.getBoolean(KEY_DNT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DNT, value).apply()
        }

    /** 支持双指缩放网页 */
    var zoomEnabled: Boolean
        get() = prefs.getBoolean(KEY_ZOOM_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ZOOM_ENABLED, value).apply()
        }

    /** 启动时默认开启无痕模式 */
    var startInIncognito: Boolean
        get() = prefs.getBoolean(KEY_START_INCOGNITO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_START_INCOGNITO, value).apply()
        }

    /** 滑动屏幕左右边缘前进后退 */
    var swipeNavEnabled: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_NAV, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SWIPE_NAV, value).apply()
        }

    /** 使用音量键上下翻页 */
    var volumeKeyPageTurn: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_PAGE_TURN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_VOLUME_PAGE_TURN, value).apply()
        }

    /** 启用视频播放器手势 */
    var videoPlayerGestures: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_GESTURES, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VIDEO_GESTURES, value).apply()
        }

    /** 启用外接键盘快捷键 */
    var keyboardShortcuts: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD_SHORTCUTS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_KEYBOARD_SHORTCUTS, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "aegis_browser_prefs"
        private const val KEY_BLOCK_EXTERNAL = "block_external"
        private const val KEY_EXTERNAL_COUNT = "external_blocked_count"
        private const val KEY_OPEN_LAST_PAGE = "open_last_page"
        private const val KEY_LAST_PAGE_URL = "last_page_url"
        private const val KEY_DNT = "do_not_track"
        private const val KEY_ZOOM_ENABLED = "zoom_enabled"
        private const val KEY_START_INCOGNITO = "start_in_incognito"
        private const val KEY_SWIPE_NAV = "swipe_nav_enabled"
        private const val KEY_VOLUME_PAGE_TURN = "volume_key_page_turn"
        private const val KEY_VIDEO_GESTURES = "video_player_gestures"
        private const val KEY_KEYBOARD_SHORTCUTS = "keyboard_shortcuts"
    }
}
