package dev.aegis.browser.data

import android.content.ContentValues
import android.content.Context

/**
 * 多标签会话持久化：保存所有打开的标签 URL、标题与当前激活索引，
 * 支持应用重启后完整恢复多标签浏览状态。
 */
class TabSessionStore(context: Context) {

    data class SavedTab(val url: String, val title: String, val isCurrent: Boolean)

    private val dbHelper = AegisDatabaseHelper.get(context)

    fun saveTabs(tabs: List<Pair<String, String>>, currentIndex: Int) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("tab_sessions", null, null)
            tabs.forEachIndexed { index, (url, title) ->
                val cv = ContentValues().apply {
                    put("tab_index", index)
                    put("url", url)
                    put("title", title)
                    put("is_current", if (index == currentIndex) 1 else 0)
                }
                db.insert("tab_sessions", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadTabs(): List<SavedTab> {
        val db = dbHelper.readableDatabase
        val result = mutableListOf<SavedTab>()
        db.rawQuery(
            "SELECT url, title, is_current FROM tab_sessions ORDER BY tab_index ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    SavedTab(
                        url = cursor.getString(0),
                        title = cursor.getString(1),
                        isCurrent = cursor.getInt(2) == 1
                    )
                )
            }
        }
        return result
    }

    fun clear() {
        dbHelper.writableDatabase.delete("tab_sessions", null, null)
    }
}
