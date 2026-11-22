package dev.aegis.browser.menu

import android.content.Context

/**
 * 菜单定制存储：
 * - 记录所有操作项的排列顺序（拖拽排序）
 * - 记录被隐藏的操作项（显示/隐藏）
 * 全部以轻量 SharedPreferences 存储，零三方依赖。
 */
class MenuCustomizationStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 返回全部操作项的当前顺序：已保存的有效顺序在前，新出现的项追加到末尾 */
    fun orderedActions(): List<String> {
        val raw = prefs.getString(KEY_ORDER, null)
        val saved = raw?.split(SEPARATOR)?.filter { it in ALL_ACTIONS } ?: emptyList()
        return saved + ALL_ACTIONS.filter { it !in saved }
    }

    /** 保存新的排列，自动补齐任何缺失项，保证所有操作都在 */
    fun setOrder(actions: List<String>) {
        val complete = actions.filter { it in ALL_ACTIONS } +
            ALL_ACTIONS.filter { it !in actions }
        prefs.edit()
            .putString(KEY_ORDER, complete.joinToString(SEPARATOR.toString()))
            .apply()
    }

    /** 当前被隐藏的操作项集合 */
    fun hiddenActions(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()

    /** 设置某项是否隐藏 */
    fun setHidden(actionId: String, hidden: Boolean) {
        val updated = hiddenActions().toMutableSet()
        if (hidden) updated.add(actionId) else updated.remove(actionId)
        prefs.edit().putStringSet(KEY_HIDDEN, updated).apply()
    }

    /** 主菜单实际显示的操作项：按顺序排列，排除被隐藏的项 */
    fun visibleActions(): List<String> {
        val hidden = hiddenActions()
        return orderedActions().filter { it !in hidden }
    }

    companion object {
        const val ACTION_RELOAD = "reload"
        const val ACTION_TRANSLATE = "translate"
        const val ACTION_ADD_BOOKMARK = "add_bookmark"
        const val ACTION_WHITELIST = "whitelist"
        const val ACTION_SHARE = "share"
        const val ACTION_DESKTOP = "desktop"
        const val ACTION_DARK_MODE = "dark_mode"
        const val ACTION_FONT_SIZE = "font_size"
        const val ACTION_INCOGNITO = "incognito"
        const val ACTION_HOME = "home"
        const val ACTION_EXIT = "exit"
        const val ACTION_ALL_MENU = "all_menu"
        const val ACTION_SNIFF = "sniff"
        const val ACTION_ORIENTATION = "orientation"
        const val ACTION_ADD_HOME = "add_home"
        const val ACTION_WEB_SETTINGS = "web_settings"
        const val ACTION_FIND_IN_PAGE = "find_in_page"

        /** 全部操作项（默认顺序） */
        val ALL_ACTIONS = listOf(
            ACTION_RELOAD,
            ACTION_FIND_IN_PAGE,
            ACTION_TRANSLATE,
            ACTION_SNIFF,
            ACTION_ORIENTATION,
            ACTION_WEB_SETTINGS,
            ACTION_ADD_HOME,
            ACTION_ADD_BOOKMARK,
            ACTION_WHITELIST,
            ACTION_SHARE,
            ACTION_DESKTOP,
            ACTION_DARK_MODE,
            ACTION_FONT_SIZE,
            ACTION_INCOGNITO,
            ACTION_HOME,
            ACTION_EXIT,
            ACTION_ALL_MENU
        )

        private const val PREFS_NAME = "aegis_menu"
        private const val KEY_ORDER = "action_order"
        private const val KEY_HIDDEN = "action_hidden"
        private const val SEPARATOR = ','
    }
}
