package dev.aegis.browser.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.aegis.browser.data.AegisDatabaseHelper
import org.json.JSONArray

/**
 * 历史记录存储：基于原生 SQLite 持久化（自动迁移旧版 SharedPreferences JSON 数据），
 * 同一 URL 再次访问时更新标题与时间并置顶，最多保留 [MAX_ENTRIES] 条。
 */
class HistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dbHelper = AegisDatabaseHelper.get(context)

    init {
        migrateIfNeeded()
    }

    private fun migrateIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val raw = prefs.getString(KEY_HISTORY, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(raw)
                val db = dbHelper.writableDatabase
                db.beginTransaction()
                try {
                    for (index in 0 until array.length()) {
                        val obj = array.getJSONObject(index)
                        val cv = ContentValues().apply {
                            put("url", obj.getString("url"))
                            put("title", obj.getString("title"))
                            put("visited_at", obj.getLong("time"))
                        }
                        db.insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_HISTORY).apply()
    }

    fun all(): List<HistoryEntry> {
        val list = mutableListOf<HistoryEntry>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT title, url, visited_at FROM history ORDER BY visited_at DESC LIMIT ?",
            arrayOf(MAX_ENTRIES.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    HistoryEntry(
                        title = cursor.getString(0),
                        url = cursor.getString(1),
                        visitedAt = cursor.getLong(2)
                    )
                )
            }
        }
        return list
    }

    fun search(query: String, limit: Int = 3): List<HistoryEntry> {
        val q = "%${query.trim()}%"
        val list = mutableListOf<HistoryEntry>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT title, url, visited_at FROM history " +
                "WHERE title LIKE ? OR url LIKE ? ORDER BY visited_at DESC LIMIT ?",
            arrayOf(q, q, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    HistoryEntry(
                        title = cursor.getString(0),
                        url = cursor.getString(1),
                        visitedAt = cursor.getLong(2)
                    )
                )
            }
        }
        return list
    }

    fun add(title: String, url: String) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("url", url)
                put("title", title.ifBlank { url })
                put("visited_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL(
                "DELETE FROM history WHERE url NOT IN (SELECT url FROM history ORDER BY visited_at DESC LIMIT ?)",
                arrayOf(MAX_ENTRIES.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun remove(url: String) {
        dbHelper.writableDatabase.delete("history", "url = ?", arrayOf(url))
    }

    fun removeAll(urls: Collection<String>) {
        if (urls.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            urls.forEach { db.delete("history", "url = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        dbHelper.writableDatabase.delete("history", null, null)
    }

    companion object {
        private const val PREFS_NAME = "aegis_history"
        private const val KEY_HISTORY = "history"
        private const val KEY_MIGRATED = "sqlite_migrated"
        private const val MAX_ENTRIES = 1000
    }
}
