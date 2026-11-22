package dev.aegis.browser.translate

import android.content.Context

/** 记住哪些站点勾选了「自动翻译该网站」（按域名保存） */
class AutoTranslateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aegis_auto_translate", Context.MODE_PRIVATE)

    fun isEnabled(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return prefs.getStringSet(KEY_HOSTS, null)?.contains(host) == true
    }

    fun setEnabled(host: String?, enabled: Boolean) {
        if (host.isNullOrBlank()) return
        val hosts = (prefs.getStringSet(KEY_HOSTS, null) ?: emptySet()).toMutableSet()
        if (enabled) hosts.add(host) else hosts.remove(host)
        prefs.edit().putStringSet(KEY_HOSTS, hosts).apply()
    }

    private companion object {
        const val KEY_HOSTS = "hosts"
    }
}
