package dev.bold7.tasks

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The database's upgrades ([upgrades]) run on a real SQLite (sqlite-jdbc, tests only), as
 * SQLiteOpenHelper runs them on the phone: every task is kept, every column as it was, and a task of
 * before version 3 takes the phone's zone.
 */
class MigrationTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun Connection.run(statements: List<Pair<String, Array<Any>>>) = statements.forEach { (sql, args) ->
        prepareStatement(sql).use { s -> args.forEachIndexed { i, a -> s.setObject(i + 1, a) }; s.executeUpdate() }
    }

    private fun Connection.rows(): List<Map<String, Any?>> = createStatement().use { s ->
        s.executeQuery("SELECT * FROM tasks ORDER BY _id").use { r ->
            val n = r.metaData.columnCount
            buildList { while (r.next()) add((1..n).associate { r.metaData.getColumnName(it) to r.getObject(it) }) }
        }
    }

    private fun Connection.version2() = apply {
        run(listOf(CREATE_TASKS to emptyArray<Any>()) + upgrades(1, 2, "unused"))
        run(
            listOf(
                "INSERT INTO tasks (title, status, priority, due, notes, modified, reminder, recurrence, reminded, snoozed) VALUES ('Pay rent', 1, 2, '2026-11-20T17:00', 'by transfer', 5, '-15', 2, '2026-11-20T16:45', 1795000000000)" to emptyArray<Any>(),
                "INSERT INTO tasks (title, status, priority, due, notes, modified) VALUES ('Old', 0, 1, '2026-11-20', '', 3)" to emptyArray<Any>(),
                "INSERT INTO tasks (title, status, priority, due, notes, modified, reminder) VALUES ('Call', 2, 0, NULL, 'ä é ''quoted''', 9, '2026-11-18T09:00')" to emptyArray<Any>(),
            ),
        )
    }

    @Test fun version3KeepsEveryTaskAndGivesItThePhonesZone() {
        val c = db().version2()
        val before = c.rows()
        c.run(upgrades(2, DB_VERSION, "Europe/Paris"))
        val after = c.rows()
        assertEquals(3, after.size)
        assertEquals(before, after.map { it - "zone" })
        assertEquals(listOf("Europe/Paris", "Europe/Paris", "Europe/Paris"), after.map { it["zone"] })
    }

    @Test fun aDatabaseOfB4TasksComesUpToVersion3() {
        val c = db()
        c.run(listOf(CREATE_TASKS to emptyArray<Any>()))
        c.run(listOf("INSERT INTO tasks (title, status, priority, due, notes, modified) VALUES ('Old', 0, 1, '2026-11-20', 'n', 3)" to emptyArray<Any>()))
        c.run(upgrades(1, DB_VERSION, "America/New_York"))
        val row = c.rows().single()
        assertEquals("Old", row["title"])
        assertEquals("2026-11-20", row["due"])
        assertEquals(0, row["recurrence"])
        assertEquals("America/New_York", row["zone"])
    }

    @Test fun aNewDatabaseIsCreatedAtVersion3WithNoTask() {
        val c = db()
        c.run(listOf(CREATE_TASKS to emptyArray<Any>()) + upgrades(1, DB_VERSION, "Europe/Paris"))
        assertEquals(emptyList(), c.rows())
        c.run(listOf("INSERT INTO tasks (title, status, priority, notes, modified, zone) VALUES ('N', 0, 1, '', 1, 'Asia/Tokyo')" to emptyArray<Any>()))
        assertEquals("Asia/Tokyo", c.rows().single()["zone"])
    }
}
