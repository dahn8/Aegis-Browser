package dev.aegis.browser.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 轻量级原生 SQLite 存储引擎（零三方依赖）：
 * 统一管理书签、历史记录与多标签会话状态，替代 SharedPreferences 全量 JSON 读写。
 */
class AegisDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
                url TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_opened_at INTEGER NOT NULL,
                sort_order INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS history (
                url TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                visited_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON history(visited_at DESC)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tab_sessions (
                tab_index INTEGER PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                is_current INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    companion object {
        private const val DB_NAME = "aegis_browser.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: AegisDatabaseHelper? = null

        fun get(context: Context): AegisDatabaseHelper =
            instance ?: synchronized(this) {
                instance ?: AegisDatabaseHelper(context.applicationContext).also { instance = it }
            }
    }
}
