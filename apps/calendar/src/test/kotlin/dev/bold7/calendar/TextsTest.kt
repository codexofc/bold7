package dev.bold7.calendar

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TextsTest {
    private val day = LocalDate.of(2026, 11, 13)
    private val paris = ZoneId.of("Europe/Paris")

    @Test fun theSimulatorsDates() {
        assertEquals("Friday 13 November 2026", Texts.dayTitle(day)) // sim-220
        assertEquals("Fri 13 Nov 2026", Texts.shortDay(day)) // sim-226
        assertEquals("Fri 13 Nov 2026 18:00", Texts.dateTime(day.atTime(18, 0), false)) // sim-228
        assertEquals("November 2026", Texts.monthTitle(day)) // sim-225
        assertEquals("NOV" to "2026", Texts.weekCorner(day)) // sim-224
        assertEquals("MON", Texts.weekday(day.minusDays(4)))
        assertEquals("18:30 - 19:30", Texts.span(day.atTime(18, 30), day.atTime(19, 30), false)) // sim-237
    }

    @Test fun theReminderBoxsLines() {
        // sim-245.
        val b = day.atTime(18, 30).atZone(paris).toInstant().toEpochMilli()
        assertEquals(listOf("13 Nov 2026 18:30", "13 Nov 2026 19:30"), Texts.reminderLines(b, b + 3_600_000, false, paris))
        assertEquals("Team Meeting (Office)", Texts.subject("Team Meeting", "Office", "x"))
        assertEquals("x", Texts.subject("", "", "x"))
    }

    @Test fun theFormsFields() {
        assertEquals("1 Hour 0 Mins", Texts.duration(60)) // sim-228
        assertEquals("2 Hours 30 Mins", Texts.duration(150))
        assertEquals("1 Day 0 Hours 0 Mins", Texts.duration(1440))
        assertEquals(listOf("None", "0 Min.", "5 Min.", "15 Min.", "1 Hour", "5 Hours"), listOf(null, 0, 5, 15, 60, 300).map(Texts::reminder)) // sim-231, sim-232
        assertEquals("None" to "No Recurrence.", Texts.recurrence(Recurrence.NONE)) // sim-229
    }

    @Test fun aReminderOs7DoesNotOfferIsKeptInItsList() {
        assertEquals(REMINDERS, reminderChoices(15))
        assertEquals(REMINDERS, reminderChoices(null))
        val day = reminderChoices(1440)
        assertEquals(1440, day.last())
        assertEquals(null, day.first())
        assertEquals(REMINDERS.size + 1, day.size)
    }
}
