package dev.aegis.browser.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主页快捷方式存储：用内置 org.json 序列化到 SharedPreferences，零三方依赖。
 * 按 URL 去重，新增追加到末尾；同时保存"是否显示快捷方式"开关。
 */
class ShortcutStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<Shortcut> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                Shortcut(
                    title = obj.getString("title"),
                    url = obj.getString("url")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun contains(url: String): Boolean = all().any { it.url == url }

    /** 添加快捷方式；超过上限时保留最新的 MAX 个 */
    fun add(title: String, url: String) {
        val updated = all().filter { it.url != url }.toMutableList()
        updated.add(Shortcut(title.ifBlank { url }, url))
        save(if (updated.size > MAX) updated.takeLast(MAX) else updated)
    }

    fun remove(url: String) {
        save(all().filter { it.url != url })
    }

    /** 修改快捷方式：按原 URL 定位，更新名称与网址；网址变化时按新网址去重 */
    fun update(oldUrl: String, newTitle: String, newUrl: String) {
        val updated = all().filter { it.url != oldUrl }.toMutableList()
        // 若新网址已存在于其他项，则不再重复添加
        if (updated.none { it.url == newUrl }) {
            updated.add(Shortcut(newTitle.ifBlank { newUrl }, newUrl))
        }
        save(updated)
    }

    /** 拖拽换位：把 from 位置的项移动到 to 位置 */
    fun move(from: Int, to: Int) {
        val list = all().toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        save(list)
    }

    fun isShown(): Boolean = prefs.getBoolean(KEY_SHOWN, true)

    fun setShown(shown: Boolean) {
        prefs.edit().putBoolean(KEY_SHOWN, shown).apply()
    }

    private fun save(list: List<Shortcut>) {
        val array = JSONArray()
        list.forEach { shortcut ->
            array.put(
                JSONObject()
                    .put("title", shortcut.title)
                    .put("url", shortcut.url)
            )
        }
        prefs.edit().putString(KEY_SHORTCUTS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "aegis_home"
        private const val KEY_SHORTCUTS = "shortcuts"
        private const val KEY_SHOWN = "show_shortcuts"
        private const val MAX = 12
    }
}
