package dev.aegis.browser.search

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索引擎持久化：当前选择、自定义引擎、搜索建议开关。
 * 全部存本地 SharedPreferences，不涉及任何网络。
 */
class SearchEngineStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun allEngines(): List<SearchEngine> = DefaultEngines.ALL + customEngines()

    fun selected(): SearchEngine {
        val id = prefs.getString(KEY_SELECTED, DefaultEngines.DEFAULT_ID)
        return allEngines().firstOrNull { it.id == id } ?: DefaultEngines.default()
    }

    fun select(id: String) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    fun addCustom(name: String, searchTemplate: String, suggestTemplate: String?): SearchEngine {
        val seq = prefs.getInt(KEY_CUSTOM_SEQ, 0) + 1
        prefs.edit().putInt(KEY_CUSTOM_SEQ, seq).apply()

        val engine = SearchEngine(
            id = "custom_$seq",
            name = name,
            searchTemplate = searchTemplate,
            suggestTemplate = suggestTemplate,
            builtIn = false
        )
        val updated = customEngines() + engine
        saveCustom(updated)
        return engine
    }

    fun removeCustom(id: String) {
        saveCustom(customEngines().filterNot { it.id == id })
    }

    fun isSuggestEnabled(): Boolean = prefs.getBoolean(KEY_SUGGEST, false)

    fun setSuggestEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUGGEST, enabled).apply()
    }

    private fun customEngines(): List<SearchEngine> {
        val raw = prefs.getString(KEY_CUSTOM, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SearchEngine(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    searchTemplate = obj.getString("search"),
                    suggestTemplate = if (obj.isNull("suggest")) null else obj.getString("suggest"),
                    builtIn = false
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCustom(engines: List<SearchEngine>) {
        val array = JSONArray()
        engines.forEach { engine ->
            array.put(JSONObject().apply {
                put("id", engine.id)
                put("name", engine.name)
                put("search", engine.searchTemplate)
                put("suggest", engine.suggestTemplate ?: JSONObject.NULL)
            })
        }
        prefs.edit().putString(KEY_CUSTOM, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "aegis_search"
        private const val KEY_SELECTED = "selected_id"
        private const val KEY_CUSTOM = "custom_engines"
        private const val KEY_CUSTOM_SEQ = "custom_seq"
        private const val KEY_SUGGEST = "suggest_enabled"
    }
}
