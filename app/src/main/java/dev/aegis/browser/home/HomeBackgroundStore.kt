package dev.aegis.browser.home

import android.content.Context
import android.content.SharedPreferences

/**
 * 主页自定义背景持久化：保存用户从相册选择的图片 URI 或内置壁纸资源。
 * 仅存本地，不涉及网络。
 */
class HomeBackgroundStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 自定义背景 URI；null 表示没有相片背景 */
    fun backgroundUri(): String? = prefs.getString(KEY_URI, null)

    /** 内置壁纸资源 id；0 表示未选择 */
    fun builtinRes(): Int = prefs.getInt(KEY_BUILTIN, 0)

    fun setBackground(uri: String?) {
        prefs.edit().apply {
            remove(KEY_BUILTIN)
            if (uri == null) remove(KEY_URI) else putString(KEY_URI, uri)
        }.apply()
    }

    fun setBuiltin(resId: Int) {
        prefs.edit().apply {
            remove(KEY_URI)
            putInt(KEY_BUILTIN, resId)
        }.apply()
    }

    fun hasCustomBackground(): Boolean = backgroundUri() != null || builtinRes() != 0

    companion object {
        private const val PREFS_NAME = "aegis_home_bg"
        private const val KEY_URI = "background_uri"
        private const val KEY_BUILTIN = "background_builtin"
    }
}
