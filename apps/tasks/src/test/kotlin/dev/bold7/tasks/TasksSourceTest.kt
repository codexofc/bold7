package dev.bold7.tasks

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TasksSourceTest {
    private val zone = ZoneId.of("America/Toronto")
    private val now = ZonedDateTime.of(2026, 11, 16, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
    private val showcase = tasksFor(showcase = true, now = now, zone = zone) { error("real source opened") }

    @Test
    fun theShowcaseMapsDueDatesStatusesPrioritiesAndReminders() {
        val files = showcase.all().single { it.title == "Send final files to Olivia" }
        assertEquals(LocalDateTime.of(LocalDate.of(2026, 11, 16), LocalTime.of(18, 0)), files.due)
        assertEquals(Status.IN_PROGRESS, files.status)
        assertEquals(Priority.HIGH, files.priority)
        assertEquals(Reminder.Before(30), files.reminder)
        assertEquals(zone, files.zone)
        val books = showcase.all().single { it.title == "Return library books" }
        assertTrue(books.overdue(LocalDateTime.of(2026, 11, 16, 9, 30), zone))
        assertEquals(null, showcase.all().single { it.title == "Plan the cottage weekend" }.due)
        assertEquals(Status.DEFERRED, showcase.all().single { it.title == "Renew passport" }.status)
    }

    @Test
    fun aTaskOpensFromTheShowcase() {
        val first = showcase.all().first()
        assertEquals(first, showcase.get(first.id))
        assertEquals(null, showcase.get(999))
    }

    @Test
    fun writesDoNotEscape() {
        val before = showcase.all()
        val t = before.first()
        assertEquals(t.id, showcase.save(t.copy(status = Status.COMPLETED)))
        assertEquals(null, showcase.save(t.copy(id = 0)))
        assertFalse(showcase.delete(t.id))
        assertEquals(0, showcase.deleteCompleted())
        assertEquals(before, showcase.all())
    }

    @Test
    fun withoutTheShowcaseTheRealSourceIsUsed() {
        assertEquals(showcase.all(), tasksFor(showcase = false, now = now, zone = zone) { showcase }.all())
    }
}
