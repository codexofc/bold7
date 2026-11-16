package dev.bold7.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import dev.bold7.ui.textActs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 11, 13)
    private val now = day.atTime(17, 47)

    @Test fun aNewAppointmentIsAnHourLongFromTheFocusOrTheNextHalfHour() {
        assertEquals(day.atTime(10, 0), Draft.new(day, 10, now, 1, paris).start)
        assertEquals(day.atTime(11, 0), Draft.new(day, 10, now, 1, paris).end)
        assertEquals(day.atTime(18, 0), Draft.new(day, null, now, 1, paris).start)
        assertEquals(day.atTime(17, 30), Draft.new(day, null, day.atTime(17, 30), 1, paris).start)
        assertEquals(day.plusDays(2).atTime(9, 0), Draft.new(day.plusDays(2), null, now, 1, paris).start)
        assertEquals(DEFAULT_REMINDER, Draft.new(day, 10, now, 1, paris).reminder)
    }

    @Test fun theEndFollowsTheStartAndNeverPrecedesIt() {
        val d = Draft.new(day, 10, now, 1, paris)
        assertEquals(day.atTime(13, 0), d.withStart(day.atTime(12, 0)).end)
        val back = d.withEnd(day.atTime(9, 0))
        assertEquals(day.atTime(8, 0), back.start)
        assertTrue(back.valid)
        assertFalse(d.copy(end = day.atTime(9, 0)).valid)
    }

    @Test fun anAllDayAppointmentIsWrittenAtUtcMidnights() {
        val d = Draft.new(day, 10, now, 3, paris).withAllDay(true)
        val c = d.columns()
        assertEquals(1, c["allDay"])
        assertEquals("UTC", c["eventTimezone"])
        assertEquals(day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), c["dtstart"])
        assertEquals(day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), c["dtend"])
        assertEquals(3L, c["calendar_id"])
        assertEquals(day.atTime(9, 0), d.withAllDay(false).start)
    }

    @Test fun aRecurringAppointmentHasADurationInsteadOfAnEnd() {
        val d = Draft.new(day, 10, now, 1, paris).copy(recurrence = Recurrence.WEEKLY, reminder = null)
        val c = d.columns()
        assertNull(c["dtend"])
        assertEquals("P3600S", c["duration"])
        assertEquals("FREQ=WEEKLY", c["rrule"])
        assertEquals(0, c["hasAlarm"])
        assertEquals(day.atTime(10, 0).atZone(paris).toInstant().toEpochMilli(), c["dtstart"])
    }

    @Test fun aRuleOs7CannotShowIsKeptAsItIs() {
        val rule = "FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10"
        assertNull(Recurrence.of(rule))
        assertEquals(Recurrence.NONE, Recurrence.of(null))
        assertEquals(Recurrence.MONTHLY, Recurrence.of("FREQ=MONTHLY"))
        val stored = Stored(5, 1, "t", "", "", false, at(day.atTime(10, 0)), at(day.atTime(11, 0)), rule, 10)
        val d = stored.draft(paris)
        assertNull(d.recurrence)
        assertEquals(rule, d.columns()["rrule"])
        assertEquals(day.atTime(10, 0), d.start)
        assertEquals(10, d.reminder)
    }

    @Test fun anAllDayStoredEventEndsOnItsLastDay() {
        val begin = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val d = Stored(5, 1, "t", "", "", true, begin, begin + 2 * 86_400_000, null, null).draft(paris)
        assertEquals(day.atStartOfDay(), d.start)
        assertEquals(day.plusDays(1).atStartOfDay(), d.end)
        assertEquals(begin + 2 * 86_400_000, d.columns()["dtend"])
    }

    @Test fun androidDurationsAreRead() {
        assertEquals(3_600_000L, durationMs("P3600S"))
        assertEquals(86_400_000L, durationMs("P1D"))
        assertEquals(5_400_000L, durationMs("PT1H30M"))
        assertEquals(7 * 86_400_000L, durationMs("P1W"))
        assertNull(durationMs("P"))
        assertNull(durationMs("x"))
    }

    @Test fun anInvitationIsAMeetingSomeoneElseOrganises() {
        val base = Stored(1, 1, "t", "", "", false, 0, 1, null, null, organizer = "a@b", isOrganizer = false, attendees = listOf(Attendee("A", "a@b", ATTENDEE_ACCEPTED)))
        assertTrue(base.invitation)
        assertTrue(base.copy(selfStatus = ATTENDEE_ACCEPTED).invitation)
        assertFalse(base.copy(isOrganizer = true).invitation)
        assertFalse(base.copy(attendees = emptyList()).invitation)
    }

    @Test fun theFormsMenu() {
        // sim-236: Save under the focus, Close.
        assertEquals(listOf(FormAct.SWITCH, FormAct.SAVE, FormAct.CLOSE) to 1, formMenu(stored = false, invitation = false, readOnly = false).let { (e, f) -> e.map { it.action } to f })
        assertEquals(listOf(FormAct.SWITCH, FormAct.SAVE, FormAct.DELETE, FormAct.ACCEPT, FormAct.TENTATIVE, FormAct.DECLINE, FormAct.CLOSE), formMenu(stored = true, invitation = true, readOnly = false).first.map { it.action })
        assertEquals(listOf(FormAct.SWITCH, FormAct.ACCEPT, FormAct.TENTATIVE, FormAct.DECLINE, FormAct.CLOSE), formMenu(stored = true, invitation = true, readOnly = true).first.map { it.action })
    }

    @Test fun theFormsMenuHasTheSelectionEntriesAfterSaveAndDelete() {
        val start = textActs(selected = false, canPaste = false)
        assertEquals(listOf(FormAct.SWITCH, FormAct.SAVE, FormAct.START_SELECTION, FormAct.CLOSE), formMenu(false, false, false, start).first.map { it.action })
        val copied = textActs(selected = true, canPaste = true)
        assertEquals(
            listOf(FormAct.SWITCH, FormAct.SAVE, FormAct.DELETE, FormAct.CUT, FormAct.COPY, FormAct.PASTE, FormAct.ACCEPT, FormAct.TENTATIVE, FormAct.DECLINE, FormAct.CLOSE),
            formMenu(true, true, false, copied).first.map { it.action },
        )
        // Save stays under the focus; a row that is not a field adds none.
        assertEquals(1, formMenu(true, false, false, copied).second)
        assertEquals(formMenu(true, false, false).first, formMenu(true, false, false, emptyList()).first)
    }

    // Time Zone (sim-228): the start and the end are wall times in the appointment's zone; the
    // provider rings its reminder [reminder] minutes before dtstart, an instant (CalendarAlarmManager).
    private fun alarm(d: Draft) = d.columns().let { it["dtstart"] as Long - d.reminder!! * 60_000L }
    private val newYork = ZoneId.of("America/New_York")

    @Test fun aNewAppointmentIsInThePhonesZone() {
        val d = Draft.new(day, 10, now, 1, paris)
        assertEquals(paris, d.zone)
        assertEquals("Europe/Paris", d.columns()["eventTimezone"])
        assertEquals(at(day.atTime(9, 45)), alarm(d))
    }

    @Test fun anotherZoneKeepsTheWallTimesAndMovesTheReminder() {
        val d = Draft.new(day, 10, now, 1, paris).withZone(newYork)
        assertEquals(day.atTime(10, 0), d.start)
        assertEquals(day.atTime(11, 0), d.end)
        assertEquals("America/New_York", d.columns()["eventTimezone"])
        // 10:00 in New York (UTC-5 in November) is 16:00 in Paris: the reminder at 15:45 there.
        assertEquals(at(day.atTime(15, 45)), alarm(d))
        assertEquals(at(day.atTime(17, 0)), d.columns()["dtend"])
    }

    @Test fun theReminderIsMinutesBeforeTheStartAcrossTheSummerTimeChange() {
        // Paris springs forward on 29/03/2026 at 02:00: an hour's reminder of 03:00 rings at 01:00.
        val spring = LocalDate.of(2026, 3, 29)
        val d = Draft.new(spring, 3, now, 1, paris).copy(reminder = 60)
        assertEquals(java.time.Instant.parse("2026-03-29T01:00:00Z").toEpochMilli(), d.columns()["dtstart"])
        assertEquals(java.time.Instant.parse("2026-03-29T00:00:00Z").toEpochMilli(), alarm(d))
        assertEquals(spring.atTime(1, 0), LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(alarm(d)), paris))
        // A start in the gap (02:30 does not exist) is moved forward by the gap, as Java does: 03:30.
        val gap = Draft.new(spring, 2, now, 1, paris).let { it.withStart(it.start.withMinute(30)) }
        assertEquals(java.time.Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(), gap.columns()["dtstart"])
        // Autumn back on 25/10/2026: 02:30 happens twice, the first (summer) one is kept.
        val fall = Draft.new(LocalDate.of(2026, 10, 25), 2, now, 1, paris).let { it.withStart(it.start.withMinute(30)) }
        assertEquals(java.time.Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), fall.columns()["dtstart"])
    }

    @Test fun aZoneWhoseSummerTimeComesEarlierMovesTheReminderThoseWeeks() {
        // New York springs forward on 08/03/2026, Paris on 29/03: on 10/03, 09:00 there is 14:00 here, not 15:00.
        val d = Draft.new(LocalDate.of(2026, 3, 10), 9, now, 1, paris).withZone(newYork)
        assertEquals(at(LocalDate.of(2026, 3, 10).atTime(13, 45)), alarm(d))
        assertEquals(at(LocalDate.of(2026, 3, 3).atTime(14, 45)), alarm(Draft.new(LocalDate.of(2026, 3, 3), 9, now, 1, paris).withZone(newYork)))
    }

    @Test fun aStoredEventOpensInItsOwnZoneAndSavesBackUnchanged() {
        val begin = day.atTime(9, 0).atZone(newYork).toInstant().toEpochMilli()
        val stored = Stored(5, 1, "t", "", "", false, begin, begin + 3_600_000, null, 15, timezone = "America/New_York")
        val d = stored.draft(paris)
        assertEquals(newYork, d.zone)
        assertEquals(day.atTime(9, 0), d.start)
        assertEquals(begin, d.columns()["dtstart"])
        assertEquals("America/New_York", d.columns()["eventTimezone"])
        // An unknown or missing zone reads in the phone's.
        assertEquals(paris, stored.copy(timezone = "Mars/Olympus").draft(paris).zone)
        assertEquals(paris, stored.copy(timezone = null).draft(paris).zone)
    }

    @Test fun anAllDayAppointmentHasNoZoneOfItsOwn() {
        val d = Draft.new(day, 10, now, 1, paris).withZone(newYork).withAllDay(true)
        assertEquals("UTC", d.columns()["eventTimezone"])
        assertEquals(day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), d.columns()["dtstart"])
    }

    private fun at(t: LocalDateTime) = t.atZone(paris).toInstant().toEpochMilli()
}
