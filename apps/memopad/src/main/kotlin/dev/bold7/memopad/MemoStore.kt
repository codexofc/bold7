package dev.bold7.memopad

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The memos, in the app's own SQLite database (the framework's, nothing else: no dependency, no
 * network, no backup). Every call reads or writes the disk: off the main thread (the graph's io).
 */
class MemoStore(context: Context) : SQLiteOpenHelper(context, "memopad.db", null, 1), Memos {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memos (_id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, modified INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Every memo, in no order of use: the list sorts them ([ordered]). */
    override fun all(): List<Memo> = readableDatabase.query("memos", null, null, null, null, null, "_id").use { c ->
        buildList { while (c.moveToNext()) add(Memo(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
    }

    override fun get(id: Long): Memo? = readableDatabase.query("memos", null, "_id = ?", arrayOf(id.toString()), null, null, null).use { c ->
        if (c.moveToFirst()) Memo(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)) else null
    }

    /** Inserts a memo ([id] null) or rewrites it; the id, or null when the database refused. */
    override fun save(id: Long?, title: String, body: String): Long? = save(id, title, body, System.currentTimeMillis())

    fun save(id: Long?, title: String, body: String, now: Long): Long? {
        val v = ContentValues().apply {
            put("title", title)
            put("body", body)
            put("modified", now)
        }
        return if (id == null) writableDatabase.insert("memos", null, v).takeIf { it >= 0 }
        else id.takeIf { writableDatabase.update("memos", v, "_id = ?", arrayOf(id.toString())) == 1 }
    }

    override fun delete(id: Long): Boolean = writableDatabase.delete("memos", "_id = ?", arrayOf(id.toString())) == 1
}
