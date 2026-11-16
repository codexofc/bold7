package dev.bold7.tasks

import java.time.LocalDateTime
import dev.bold7.ui.textActs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskEditorTest {
    private val paris = java.time.ZoneId.of("Europe/Paris")
    private val due = LocalDateTime.of(2026, 11, 20, 17, 0)
    private val now = LocalDateTime.of(2026, 11, 16, 4, 0)
    private val stored = Task(7, "Pay rent", Status.IN_PROGRESS, Priority.HIGH, due, "by transfer", 5, Reminder.Before(15))

    @Test fun aNewTaskIsNotStartedNormalWithNothingDueAndUnchanged() {
        val e = TaskEditor.of(null, paris)
        assertEquals(Status.NOT_STARTED, e.status)
        assertEquals(Priority.NORMAL, e.priority)
        assertNull(e.due)
        assertEquals(ReminderKind.NONE, e.reminderKind)
        assertTrue(e.blank)
        assertFalse(e.changed)
    }

    @Test fun byDateOpensOnTheNextDayAt17() {
        // sim-273 and sim-279, on Monday the 16th: "Tue 17 Nov 2026 17:00".
        assertEquals("Tue 17 Nov 2026 17:00", dateTimeLabel(defaultDue(now)))
    }

    @Test fun anyEditedFieldMakesItChangedAndSettingItBackUndoesIt() {
        val e = TaskEditor.of(stored, paris)
        assertFalse(e.changed)
        assertTrue(e.withTitle("x").changed)
        assertTrue(e.withStatus(Status.COMPLETED).changed)
        assertTrue(e.withPriority(Priority.LOW).changed)
        assertTrue(e.withDue(due.plusDays(1)).changed)
        assertTrue(e.withReminder(null).changed)
        assertTrue(e.withRecurrence(Recurrence.DAILY).changed)
        assertTrue(e.withNotes("").changed)
        assertFalse(e.withStatus(Status.COMPLETED).withStatus(Status.IN_PROGRESS).changed)
    }

    @Test fun dueNoneTakesTheRelativeReminderAndTheRecurrenceButKeepsByDate() {
        val e = TaskEditor.of(stored, paris).withRecurrence(Recurrence.WEEKLY).withDue(null)
        assertNull(e.reminder)
        assertEquals(Recurrence.NONE, e.recurrence)
        val at = Reminder.At(due)
        assertEquals(at, TaskEditor.of(stored, paris).withReminder(at).withDue(null).reminder)
        assertEquals(listOf(ReminderKind.NONE, ReminderKind.BY_DATE), e.reminderKinds)
        assertEquals(ReminderKind.entries, TaskEditor.of(stored, paris).reminderKinds)
    }

    @Test fun choosingAReminderKindStartsFromTheDueDateOrTheNextHour() {
        val e = TaskEditor.of(stored, paris)
        assertEquals(Reminder.At(due), e.withReminderKind(ReminderKind.BY_DATE, now).reminder)
        assertEquals(Reminder.At(LocalDateTime.of(2026, 11, 16, 5, 0)), TaskEditor.of(null, paris).withReminderKind(ReminderKind.BY_DATE, now.plusMinutes(20)).reminder)
        assertEquals(Reminder.Before(DEFAULT_BEFORE), e.withReminder(null).withReminderKind(ReminderKind.RELATIVE, now).reminder)
        assertEquals(e, e.withReminderKind(ReminderKind.RELATIVE, now))
        assertNull(e.withReminderKind(ReminderKind.NONE, now).reminder)
    }

    @Test fun savingChecksInTheFirmwaresOrder() {
        val e = TaskEditor.of(null, paris)
        assertEquals(listOf(Check.NO_TITLE), e.withNotes("x").checks(now))
        assertEquals(listOf(Check.COMPLETE_RECURRING), e.withTitle("A").withDue(due).withRecurrence(Recurrence.DAILY).withStatus(Status.COMPLETED).checks(now))
        assertEquals(listOf(Check.REMINDER_AFTER_DUE), e.withTitle("A").withDue(due).withReminder(Reminder.At(due.plusHours(1))).checks(now))
        assertEquals(listOf(Check.REMINDER_PASSED, Check.DUE_PASSED), e.withTitle("A").withDue(now.minusDays(1)).withReminder(Reminder.Before(15)).checks(now))
        assertEquals(emptyList(), e.withTitle("A").withDue(due).withReminder(Reminder.Before(15)).checks(now))
    }

    @Test fun aReminderOrDueDateNotChangedIsNotQuestionedAgain() {
        val passed = stored.copy(due = now.minusDays(1))
        assertEquals(emptyList(), TaskEditor.of(passed, paris).withTitle("Other").checks(now))
    }

    @Test fun savingBuildsTheTaskFromTheFields() {
        val t = TaskEditor.of(stored, paris).withTitle(" Pay the rent ").withStatus(Status.COMPLETED).toTask(99, now)
        // A task without a zone is written with the phone's.
        assertEquals(stored.copy(title = "Pay the rent", status = Status.COMPLETED, modified = 99, zone = paris), t)
        assertEquals(0L, TaskEditor.of(null, paris).withTitle("A").toTask(1, now).id)
    }

    @Test fun aReminderAlreadyPassedIsSavedAsShownSoThatItDoesNotRing() {
        val t = TaskEditor.of(null, paris).withTitle("A").withDue(now.plusMinutes(5)).withReminder(Reminder.Before(15)).toTask(1, now)
        assertEquals(now.minusMinutes(10), t.reminded)
        assertNull(ringAt(t, java.time.ZoneId.of("Europe/Paris")))
    }

    @Test fun aMovedReminderDropsTheOldSnoozeAndRingsAgain() {
        val snoozed = stored.shown().copy(snoozedUntil = 1_000)
        val t = TaskEditor.of(snoozed, paris).withReminder(Reminder.Before(30)).toTask(1, now)
        assertNull(t.snoozedUntil)
        assertEquals(LocalDateTime.of(2026, 11, 20, 16, 30), t.remindAt)
        assertEquals(snoozed.copy(zone = paris), TaskEditor.of(snoozed, paris).toTask(snoozed.modified, now))
    }

    @Test fun theFormMenuHasTheSelectionEntriesBeforeClose() {
        assertEquals(
            listOf(TaskAct.SWITCH, TaskAct.SAVE, TaskAct.START_SELECTION, TaskAct.CLOSE),
            taskMenu(stored = false, text = textActs(selected = false, canPaste = false)).entries.map { it.action },
        )
        val stored = taskMenu(stored = true, text = textActs(selected = true, canPaste = true))
        assertEquals(listOf(TaskAct.SWITCH, TaskAct.SAVE, TaskAct.DELETE, TaskAct.CUT, TaskAct.COPY, TaskAct.PASTE, TaskAct.CLOSE), stored.entries.map { it.action })
        // On a row of the form, no field: none of them; Save stays the focused entry.
        val row = taskMenu(stored = true, text = emptyList())
        assertEquals(listOf(TaskAct.SWITCH, TaskAct.SAVE, TaskAct.DELETE, TaskAct.CLOSE), row.entries.map { it.action })
        assertEquals(TaskAct.SAVE, row.entries[row.highlighted!!].action)
    }

    // Time Zone (sim-268, sim-319): the phone's by default; the due date and the reminder are wall
    // times there, compared with the phone's now by their instants.
    private val tokyo = java.time.ZoneId.of("Asia/Tokyo")

    @Test fun aTaskIsInThePhonesZoneUnlessItHasItsOwn() {
        assertEquals(paris, TaskEditor.of(null, paris).zone)
        assertEquals(tokyo, TaskEditor.of(stored.copy(zone = tokyo), paris).zone)
        // A task without a stored zone is at the phone's.
        assertEquals(paris, TaskEditor.of(stored, paris).zone)
        assertEquals(paris, TaskEditor.of(null, paris).withTitle("A").toTask(1, now).zone)
    }

    @Test fun anotherZoneIsAChangeAndIsWritten() {
        val e = TaskEditor.of(stored.copy(zone = paris), paris).withZone(tokyo)
        assertTrue(e.changed)
        assertEquals(tokyo, e.toTask(1, now).zone)
        assertFalse(e.withZone(paris).changed)
    }

    @Test fun aReminderPassedInItsZoneIsAskedAboutThoughNotYetHere() {
        // 10:00 on the 16th in Tokyo is 02:00 in Paris: gone at 04:00 here.
        val at = Reminder.At(LocalDateTime.of(2026, 11, 16, 10, 0))
        val e = TaskEditor.of(null, paris).withTitle("A").withZone(tokyo).withDue(due).withReminder(at)
        assertEquals(listOf(Check.REMINDER_PASSED), e.checks(now))
        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 0), e.toTask(1, now).reminded)
        assertEquals(emptyList(), e.withZone(paris).checks(now))
        // Due at 11:00 in Tokyo on the 16th: passed (03:00 here).
        assertEquals(listOf(Check.DUE_PASSED), TaskEditor.of(null, paris).withTitle("A").withZone(tokyo).withDue(LocalDateTime.of(2026, 11, 16, 11, 0)).checks(now))
    }

    @Test fun movingTheZoneMovesTheReminderSoItRingsAgain() {
        val shown = stored.copy(zone = paris).shown()
        val t = TaskEditor.of(shown, paris).withZone(tokyo).toTask(1, now)
        assertEquals(due.minusMinutes(15).atZone(tokyo).toInstant().toEpochMilli(), ringAt(t, paris))
    }
}
