package dev.aegis.browser.bookmarks

import android.content.ContentValues
import android.content.Context
import dev.aegis.browser.data.AegisDatabaseHelper
import org.json.JSONArray

/**
 * 书签存储：基于原生 SQLite 持久化（自动迁移旧版 SharedPreferences JSON 数据），
 * 按 URL 去重，新书签置顶，支持排序与快速关键词检索。
 */
class BookmarkStore(context: Context) {

    companion object {
        const val SORT_MANUAL = "manual"
        const val SORT_NEWEST = "newest"
        const val SORT_OLDEST = "oldest"
        const val SORT_LAST_OPENED = "lastOpened"
        const val SORT_AZ = "az"
        const val SORT_ZA = "za"
        private const val KEY_SORT = "sort_mode"
        private const val KEY_MIGRATED = "sqlite_migrated"
        private const val PREFS_NAME = "aegis_bookmarks"
        private const val KEY_BOOKMARKS = "bookmarks"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dbHelper = AegisDatabaseHelper.get(context)

    init {
        migrateIfNeeded()
    }

    private fun migrateIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val raw = prefs.getString(KEY_BOOKMARKS, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(raw)
                val base = System.currentTimeMillis()
                val db = dbHelper.writableDatabase
                db.beginTransaction()
                try {
                    for (index in 0 until array.length()) {
                        val obj = array.getJSONObject(index)
                        val created = obj.optLong("createdAt", 0L).let {
                            if (it > 0L) it else base - index * 1000L
                        }
                        val lastOpened = obj.optLong("lastOpenedAt", 0L)
                        val cv = ContentValues().apply {
                            put("url", obj.getString("url"))
                            put("title", obj.getString("title"))
                            put("created_at", created)
                            put("last_opened_at", lastOpened)
                            put("sort_order", index)
                        }
                        db.insertWithOnConflict("bookmarks", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_BOOKMARKS).apply()
    }

    fun all(): List<Bookmark> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<Bookmark>()
        db.rawQuery(
            "SELECT title, url, created_at, last_opened_at FROM bookmarks ORDER BY sort_order ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    Bookmark(
                        title = cursor.getString(0),
                        url = cursor.getString(1),
                        createdAt = cursor.getLong(2),
                        lastOpenedAt = cursor.getLong(3)
                    )
                )
            }
        }
        return list
    }

    fun search(query: String, limit: Int = 3): List<Bookmark> {
        val q = "%${query.trim()}%"
        val list = mutableListOf<Bookmark>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT title, url, created_at, last_opened_at FROM bookmarks " +
                "WHERE title LIKE ? OR url LIKE ? ORDER BY last_opened_at DESC, created_at DESC LIMIT ?",
            arrayOf(q, q, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    Bookmark(
                        title = cursor.getString(0),
                        url = cursor.getString(1),
                        createdAt = cursor.getLong(2),
                        lastOpenedAt = cursor.getLong(3)
                    )
                )
            }
        }
        return list
    }

    /** 当前排序模式：manual / newest / oldest / lastOpened / az / za */
    fun sortMode(): String = prefs.getString(KEY_SORT, SORT_MANUAL) ?: SORT_MANUAL

    fun setSortMode(mode: String) {
        prefs.edit().putString(KEY_SORT, mode).apply()
    }

    fun sorted(): List<Bookmark> {
        val orderBy = when (sortMode()) {
            SORT_NEWEST -> "created_at DESC"
            SORT_OLDEST -> "created_at ASC"
            SORT_LAST_OPENED -> "last_opened_at DESC, created_at DESC"
            SORT_AZ -> "LOWER(title) ASC"
            SORT_ZA -> "LOWER(title) DESC"
            else -> "sort_order ASC"
        }
        val list = mutableListOf<Bookmark>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT title, url, created_at, last_opened_at FROM bookmarks ORDER BY $orderBy",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    Bookmark(
                        title = cursor.getString(0),
                        url = cursor.getString(1),
                        createdAt = cursor.getLong(2),
                        lastOpenedAt = cursor.getLong(3)
                    )
                )
            }
        }
        return list
    }

    /** 记录书签被打开的时间，供「按上次打开时间排序」使用 */
    fun markOpened(url: String) {
        val cv = ContentValues().apply { put("last_opened_at", System.currentTimeMillis()) }
        dbHelper.writableDatabase.update("bookmarks", cv, "url = ?", arrayOf(url))
    }

    fun contains(url: String): Boolean {
        dbHelper.readableDatabase.rawQuery(
            "SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1",
            arrayOf(url)
        ).use { return it.moveToFirst() }
    }

    fun add(title: String, url: String) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE bookmarks SET sort_order = sort_order + 1")
            val cv = ContentValues().apply {
                put("url", url)
                put("title", title.ifBlank { url })
                put("created_at", System.currentTimeMillis())
                put("last_opened_at", 0L)
                put("sort_order", 0)
            }
            db.insertWithOnConflict("bookmarks", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun remove(url: String) {
        dbHelper.writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
    }

    fun removeAll(urls: Collection<String>) {
        if (urls.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            urls.forEach { db.delete("bookmarks", "url = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 手动排序：直接以给定顺序覆盖存储 */
    fun saveOrder(list: List<Bookmark>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("bookmarks", null, null)
            list.forEachIndexed { index, b ->
                val cv = ContentValues().apply {
                    put("url", b.url)
                    put("title", b.title)
                    put("created_at", b.createdAt)
                    put("last_opened_at", b.lastOpenedAt)
                    put("sort_order", index)
                }
                db.insert("bookmarks", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
