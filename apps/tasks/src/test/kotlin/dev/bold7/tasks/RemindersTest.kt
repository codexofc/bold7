package dev.bold7.tasks

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemindersTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun ms(t: LocalDateTime, zone: ZoneId = paris) = t.atZone(zone).toInstant().toEpochMilli()
    private val due = LocalDateTime.of(2026, 11, 17, 17, 0)
    private fun task(id: Long, reminder: Reminder?, status: Status = Status.NOT_STARTED, recurrence: Recurrence = Recurrence.NONE) =
        Task(id, "T$id", status, Priority.NORMAL, due, "", 0, reminder, recurrence)

    @Test fun relativeCountsBackFromTheDueDateAndByDateIsItsOwnTime() {
        assertEquals(LocalDateTime.of(2026, 11, 17, 16, 45), task(1, Reminder.Before(15)).remindAt)
        assertEquals(LocalDateTime.of(2026, 11, 16, 9, 0), task(1, Reminder.At(LocalDateTime.of(2026, 11, 16, 9, 0))).remindAt)
        assertNull(task(1, Reminder.Before(15)).copy(due = null).remindAt)
    }

    @Test fun aReminderRingsOnceAtItsTimeAndNotWhenCompleted() {
        val t = task(1, Reminder.Before(15))
        assertEquals(ms(LocalDateTime.of(2026, 11, 17, 16, 45)), ringAt(t, paris))
        assertNull(ringAt(t.shown(), paris))
        assertNull(ringAt(t.copy(status = Status.COMPLETED), paris))
        assertNull(ringAt(task(2, null), paris))
    }

    @Test fun aChangedReminderOrDueDateRingsAgain() {
        val shown = task(1, Reminder.Before(15)).shown()
        assertEquals(ms(LocalDateTime.of(2026, 11, 18, 16, 45)), ringAt(shown.copy(due = due.plusDays(1)), paris))
        assertEquals(ms(LocalDateTime.of(2026, 11, 17, 16, 30)), ringAt(shown.copy(reminder = Reminder.Before(30)), paris))
    }

    @Test fun aSnoozedReminderComesBackAfterTheSnoozeEvenOnceShown() {
        val t = task(1, Reminder.Before(15)).shown().snoozed(1_000, 5)
        assertEquals(1_000 + 300_000L, ringAt(t, paris))
        assertNull(ringAt(t.shown(), paris))
    }

    @Test fun everyReminderWhoseTimeHasComeRingsInOrderAndTheAlarmWaitsForTheNextOne() {
        val a = task(1, Reminder.Before(0))
        val b = task(2, Reminder.Before(60))
        val c = task(3, Reminder.At(LocalDateTime.of(2026, 11, 20, 8, 0)))
        val now = ms(LocalDateTime.of(2026, 11, 17, 17, 30))
        // Both missed (the phone was off): both ring now, the earlier first.
        assertEquals(listOf(b, a), ringing(listOf(a, b, c), now, paris))
        assertEquals(ms(LocalDateTime.of(2026, 11, 20, 8, 0)), nextRing(listOf(a, b, c), now, paris))
        assertNull(nextRing(listOf(a.shown(), b.shown(), c.shown()), now, paris))
        assertEquals(emptyList(), ringing(listOf(c), now, paris))
    }

    @Test fun theReminderIsAWallClockTimeThatAZoneChangeMoves() {
        val t = task(1, Reminder.Before(15))
        val london = ZoneId.of("Europe/London")
        assertEquals(3_600_000L, ringAt(t, london)!! - ringAt(t, paris)!!)
    }

    @Test fun aReminderOnTheSummerTimeDayRingsAtTheWallTime() {
        val t = task(1, Reminder.At(LocalDateTime.of(2026, 3, 29, 9, 0)))
        assertEquals(ms(LocalDateTime.of(2026, 3, 29, 9, 0)), ringAt(t, paris))
        assertEquals(LocalDateTime.of(2026, 3, 29, 7, 0), java.time.Instant.ofEpochMilli(ringAt(t, paris)!!).atZone(ZoneId.of("UTC")).toLocalDateTime())
    }

    @Test fun completingARecurringTaskMovesItToItsNextOccurrence() {
        val at = Reminder.At(LocalDateTime.of(2026, 11, 17, 9, 0))
        val weekly = task(1, at, Status.IN_PROGRESS, Recurrence.WEEKLY).shown().completed(5)
        assertEquals(Status.NOT_STARTED, weekly.status)
        assertEquals(due.plusWeeks(1), weekly.due)
        assertEquals(Reminder.At(LocalDateTime.of(2026, 11, 24, 9, 0)), weekly.reminder)
        // Its reminder rings again, at the next occurrence.
        assertEquals(ms(LocalDateTime.of(2026, 11, 24, 9, 0)), ringAt(weekly, paris))
        assertEquals(LocalDateTime.of(2026, 12, 17, 17, 0), task(1, null, recurrence = Recurrence.MONTHLY).completed(0).due)
        assertEquals(LocalDateTime.of(2027, 11, 17, 17, 0), task(1, null, recurrence = Recurrence.YEARLY).completed(0).due)
        assertEquals(LocalDateTime.of(2026, 11, 18, 17, 0), task(1, null, recurrence = Recurrence.DAILY).completed(0).due)
        assertEquals(Status.COMPLETED, task(1, null).completed(0).status)
        assertEquals(LocalDateTime.of(2026, 2, 28, 10, 0), Recurrence.MONTHLY.step(LocalDateTime.of(2026, 1, 31, 10, 0)))
    }

    @Test fun remindersAndDueDatesAreStoredAndReadBack() {
        for (r in listOf(null, Reminder.Before(0), Reminder.Before(300), Reminder.At(LocalDateTime.of(2026, 11, 17, 9, 5)))) assertEquals(r, readReminder(writeReminder(r)))
        assertEquals(due, readDue(due.toString()))
        // A due date that is a day alone: at 17:00.
        assertEquals(LocalDate.of(2026, 11, 20).atTime(17, 0), readDue("2026-11-20"))
        assertNull(readDue("soon"))
        assertNull(readReminder("x"))
    }

    // A task's own Time Zone (sim-268, sim-319). Its wall times are in it, whatever the phone's.
    private val newYork = ZoneId.of("America/New_York")

    @Test fun aTaskWithItsOwnZoneRingsAtThatZonesWallTimeWhereverThePhoneIs() {
        val t = task(1, Reminder.Before(15)).copy(zone = newYork)
        assertEquals(ms(LocalDateTime.of(2026, 11, 17, 16, 45), newYork), ringAt(t, paris))
        assertEquals(ringAt(t, paris), ringAt(t, ZoneId.of("Europe/London")))
        // Without a zone (no task has none after the migration, but a row that cannot be read): the phone's.
        assertEquals(ms(LocalDateTime.of(2026, 11, 17, 16, 45)), ringAt(task(1, Reminder.Before(15)), paris))
    }

    @Test fun relativeCountsBackFromTheDueInstantAcrossTheSummerTimeChange() {
        // Paris springs forward on 29/03/2026 at 02:00: an hour before 03:00 is 01:00 (00:00 UTC), not the missing 02:00.
        val spring = task(1, Reminder.Before(60)).copy(due = LocalDateTime.of(2026, 3, 29, 3, 0), zone = paris)
        assertEquals(java.time.Instant.parse("2026-03-29T00:00:00Z").toEpochMilli(), ringAt(spring, paris))
        // And back on 25/10/2026: an hour before 03:00 (02:00 UTC) is the second 02:00, 01:00 UTC.
        val autumn = task(1, Reminder.Before(60)).copy(due = LocalDateTime.of(2026, 10, 25, 3, 0), zone = paris)
        assertEquals(java.time.Instant.parse("2026-10-25T01:00:00Z").toEpochMilli(), ringAt(autumn, paris))
        // New York changes three weeks before Paris: 09:00 there on 10/03/2026 is 13:00 UTC, 14:00 here.
        val early = task(1, Reminder.At(LocalDateTime.of(2026, 3, 10, 9, 0))).copy(zone = newYork)
        assertEquals(java.time.Instant.parse("2026-03-10T13:00:00Z").toEpochMilli(), ringAt(early, paris))
    }
}
