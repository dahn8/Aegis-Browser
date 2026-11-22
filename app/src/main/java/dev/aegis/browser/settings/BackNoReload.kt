package dev.aegis.browser.settings

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import dev.aegis.browser.core.web.SitePermissions

/**
 * 返回不重载：后退时把加载策略切到"优先用缓存"，让上一页从本地缓存恢复，
 * 不再重新走网络；可对指定站点例外（例外站点仍按正常方式重新加载）。
 *
 * Android WebView 不提供真正的页面快照缓存，这里用缓存优先 + 短暂保持实现，
 * 对多数静态页面能避免白屏与重新拉资源。
 */
object BackNoReload {

    private const val PREFS = "aegis_back_reload"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_EXCEPTIONS = "exceptions"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun exceptions(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCEPTIONS, emptySet())?.sorted().orEmpty()

    fun addException(context: Context, host: String) {
        val clean = normalize(host)
        if (clean.isEmpty()) return
        val set = exceptions(context).toMutableSet()
        set.add(clean)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_EXCEPTIONS, set).apply()
    }

    fun removeException(context: Context, host: String) {
        val set = exceptions(context).toMutableSet()
        set.remove(host)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_EXCEPTIONS, set).apply()
    }

    /** 后退前调用：命中策略时临时切缓存优先，1 秒后还原 */
    fun prepare(context: Context, view: WebView) {
        if (!enabled(context)) return
        val host = SitePermissions.hostOf(view.url)
        if (host.isNotEmpty() && exceptions(context).any { it.equals(host, true) }) return
        // 1 = LOAD_CACHE_ELT_ONLY（该常量在新版本被隐藏，直接用数值）
        @Suppress("DEPRECATION")
        view.settings.cacheMode = 1
        view.postDelayed({
            runCatching { view.settings.cacheMode = WebSettings.LOAD_DEFAULT }
        }, 900)
    }

    private fun normalize(raw: String): String {
        var host = raw.trim().lowercase()
        host = host.removePrefix("http://").removePrefix("https://")
        host = host.substringBefore('/').substringBefore('?')
        return host
    }
}
