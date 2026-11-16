package dev.bold7.tasks

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.ZoneId

/** Version 3 adds the task's Time Zone. */
const val DB_VERSION = 3

/** The table as first created (version 1); [upgrades] bring it to [DB_VERSION]. */
const val CREATE_TASKS = "CREATE TABLE tasks (_id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, status INTEGER NOT NULL, " +
    "priority INTEGER NOT NULL, due TEXT, notes TEXT NOT NULL, modified INTEGER NOT NULL)"

/**
 * The statements and their arguments that bring the table from version [from] to [to], pure, run on a
 * real SQLite by MigrationTest. Only columns are added, nothing is rewritten nor dropped: a task of
 * before version 3 takes [phoneZone], the phone's zone when it is upgraded (its wall times read as
 * they did). SQLiteOpenHelper runs them in its transaction: all of them, or none.
 */
fun upgrades(from: Int, to: Int, phoneZone: String): List<Pair<String, Array<Any>>> = buildList {
    if (from < 2 && to >= 2) {
        add("ALTER TABLE tasks ADD COLUMN reminder TEXT" to emptyArray())
        add("ALTER TABLE tasks ADD COLUMN recurrence INTEGER NOT NULL DEFAULT 0" to emptyArray())
        add("ALTER TABLE tasks ADD COLUMN reminded TEXT" to emptyArray())
        add("ALTER TABLE tasks ADD COLUMN snoozed INTEGER" to emptyArray())
    }
    if (from < 3 && to >= 3) {
        add("ALTER TABLE tasks ADD COLUMN zone TEXT" to emptyArray())
        add("UPDATE tasks SET zone = ?" to arrayOf(phoneZone))
    }
}

/**
 * The tasks, in the app's own SQLite database (the framework's, nothing else: no dependency, no
 * network, no backup), in the credential encrypted storage: nothing of it is read before the first
 * unlock. Every call reads or writes the disk: off the main thread (the
 * graph's io). Version 2 adds the reminder, the recurrence, the reminder shown and the
 * snooze; the due date becomes a day and a time ([readDue] still reads a day alone). Version 3
 * adds the Time Zone ([upgrades]).
 */
class TaskStore(context: Context) : SQLiteOpenHelper(context, "tasks.db", null, DB_VERSION), Tasks {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TASKS)
        onUpgrade(db, 1, DB_VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        upgrades(oldVersion, newVersion, ZoneId.systemDefault().id).forEach { (sql, args) -> db.execSQL(sql, args) }

    private val columns = arrayOf("_id", "title", "status", "priority", "due", "notes", "modified", "reminder", "recurrence", "reminded", "snoozed", "zone")

    private fun Cursor.task() = Task(
        getLong(0),
        getString(1),
        Status.entries.getOrElse(getInt(2)) { Status.NOT_STARTED },
        Priority.entries.firstOrNull { it.cell == getInt(3) } ?: Priority.NORMAL,
        readDue(getString(4)),
        getString(5),
        getLong(6),
        readReminder(getString(7)),
        Recurrence.entries.getOrElse(getInt(8)) { Recurrence.NONE },
        readDue(getString(9)),
        if (isNull(10)) null else getLong(10),
        getString(11)?.let { runCatching { ZoneId.of(it) }.getOrNull() },
    )

    override fun all(): List<Task> = readableDatabase.query("tasks", columns, null, null, null, null, null).use { c ->
        buildList { while (c.moveToNext()) add(c.task()) }
    }

    override fun get(id: Long): Task? = readableDatabase.query("tasks", columns, "_id = ?", arrayOf(id.toString()), null, null, null).use { c ->
        if (c.moveToFirst()) c.task() else null
    }

    /** Inserts a task (id 0) or rewrites it; its id, or null when the database refused. */
    override fun save(t: Task): Long? {
        val v = ContentValues().apply {
            put("title", t.title)
            put("status", t.status.ordinal)
            put("priority", t.priority.cell)
            put("due", t.due?.toString())
            put("notes", t.notes)
            put("modified", t.modified)
            put("reminder", writeReminder(t.reminder))
            put("recurrence", t.recurrence.ordinal)
            put("reminded", t.reminded?.toString())
            put("snoozed", t.snoozedUntil)
            put("zone", t.zone?.id)
        }
        return if (t.id == 0L) writableDatabase.insert("tasks", null, v).takeIf { it >= 0 }
        else t.id.takeIf { writableDatabase.update("tasks", v, "_id = ?", arrayOf(t.id.toString())) == 1 }
    }

    override fun delete(id: Long): Boolean = writableDatabase.delete("tasks", "_id = ?", arrayOf(id.toString())) == 1

    /** Delete Completed (A); how many went. */
    override fun deleteCompleted(): Int = writableDatabase.delete("tasks", "status = ?", arrayOf(Status.COMPLETED.ordinal.toString()))
}
