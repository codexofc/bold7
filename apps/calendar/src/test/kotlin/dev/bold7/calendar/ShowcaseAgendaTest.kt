package dev.bold7.calendar

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShowcaseAgendaTest {
    private val zone = ZoneId.of("America/Toronto")
    private val monday = LocalDate.of(2026, 11, 16)
    private val agenda = agendaFor(true, real = { error("real source opened") }, zone) { monday }

    private fun range(view: CalView) = range(view, monday, zone, DisplayOptions())

    @Test fun theShowcaseNeverOpensTheRealSource() {
        var opened = false
        val real = object : AgendaSource by agenda {}
        assertTrue(agendaFor(false, { opened = true; real }, zone) { monday } === real && opened)
        opened = false
        // Every read the views and the form make, and every write: none reaches the real one.
        val (from, to) = range(CalView.MONTH)
        val read = agenda.occurrences(from, to)
        assertTrue(read.isNotEmpty())
        read.forEach { assertTrue(agenda.event(it.eventId) != null, it.title) }
        assertEquals(emptyList(), agenda.alerts(from))
        val draft = agenda.event(read.first().eventId)!!.draft(zone)
        assertNull(agenda.save(draft))
        assertNull(agenda.save(draft.copy(id = null)))
        assertFalse(agenda.delete(read.first().eventId))
        assertFalse(agenda.deleteOccurrence(read.first().eventId, read.first().begin))
        assertFalse(agenda.answer(read.first().eventId, ATTENDEE_ACCEPTED))
        assertTrue(agenda.defaultCalendar() != null, "New opens its form")
        assertFalse(opened)
    }

    @Test fun todayIsFullAndTimedOnesAreInThePhonesZone() {
        val (from, to) = range(CalView.DAY)
        val today = agenda.occurrences(from, to).filter { it.on(monday, zone) }
        assertTrue(today.size >= 5)
        val review = today.single { it.title == "Design review with Olivia" }
        assertEquals(monday.atTime(10, 30), review.start(zone))
        assertEquals(monday.atTime(11, 30), review.finish(zone))
        assertTrue(review.reminder)
        assertTrue(today.single { it.title == "Team stand-up" }.recurring)
        assertTrue(today.single { it.title.startsWith("Call with") }.invitation)
        assertEquals(today.sortedBy { it.begin }, today)
    }

    @Test fun allDayOnesAreAtUtcMidnights() {
        val (from, to) = range(CalView.MONTH)
        val cottage = agenda.occurrences(from, to).single { it.title == "Weekend at the cottage" }
        assertTrue(cottage.allDay)
        assertEquals(monday.plusDays(19).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), cottage.begin)
        assertEquals(monday.plusDays(21).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), cottage.end)
        assertTrue(cottage.on(monday.plusDays(20), zone))
    }

    @Test fun theWeekHasItsWeekendAndTheFormItsDetails() {
        val (from, to) = range(CalView.WEEK)
        val week = agenda.occurrences(from, to)
        val hike = week.single { it.title.startsWith("Hike") }
        assertEquals(LocalDate.of(2026, 11, 21).atTime(LocalTime.of(7, 30)), hike.start(zone))
        val call = agenda.event(week.single { it.invitation }.eventId)!!
        assertTrue(call.invitation)
        assertEquals("Conference line", call.location)
        val standUp = agenda.event(week.first { it.recurring }.eventId)!!
        assertTrue(standUp.rule != null)
        assertFalse(standUp.readOnly)
    }
}
