package dev.bold7.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgendaTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 11, 13)

    private fun at(t: LocalDateTime) = t.atZone(paris).toInstant().toEpochMilli()
    private fun timed(id: Long, h: Int, m: Int = 0, minutes: Long = 60, d: LocalDate = day) =
        Occurrence(id, "e$id", at(d.atTime(h, m)), at(d.atTime(h, m).plusMinutes(minutes)))
    private fun allDay(id: Long, from: LocalDate, days: Long = 1) =
        Occurrence(id, "a$id", from.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), from.plusDays(days).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), allDay = true)

    @Test fun anAllDayAppointmentCoversItsOwnDatesInEveryZone() {
        val a = allDay(1, day)
        assertTrue(a.on(day, paris))
        assertTrue(a.on(day, ZoneId.of("America/Los_Angeles")))
        assertFalse(a.on(day.plusDays(1), paris))
        assertFalse(a.on(day.minusDays(1), paris))
    }

    @Test fun anHourHoldsWhatOverlapsItAndTheAllDayRowTheAllDayOnes() {
        // sim-237: 18:30 to 19:30 is in the 18 and 19 rows.
        val e = timed(1, 18, 30)
        assertTrue(e.covers(day, 18, paris))
        assertTrue(e.covers(day, 19, paris))
        assertFalse(e.covers(day, 20, paris))
        assertFalse(e.covers(day, ALL_DAY, paris))
        assertTrue(allDay(2, day).covers(day, ALL_DAY, paris))
        assertFalse(allDay(2, day).covers(day, 10, paris))
    }

    @Test fun blocksArePlacedToTheMinuteAndSideBySideWhenTheyOverlap() {
        val rows = blocks(day, listOf(timed(1, 18, 30), timed(2, 19, minutes = 90), timed(3, 21), allDay(4, day), timed(5, 8, minutes = 0)), paris)
        assertEquals(
            listOf(Block(timed(5, 8, minutes = 0), 480, 495, 0, 1), Block(timed(1, 18, 30), 1110, 1170, 0, 2), Block(timed(2, 19, minutes = 90), 1140, 1230, 1, 2), Block(timed(3, 21), 1260, 1320, 0, 1)),
            rows,
        )
    }

    @Test fun aBlockFromTheDayBeforeStartsAtMidnight() {
        val night = timed(1, 23, minutes = 180, d = day.minusDays(1))
        assertEquals(listOf(Block(night, 0, 120, 0, 1)), blocks(day, listOf(night), paris))
        assertEquals(listOf(Block(night, 1380, 1440, 0, 1)), blocks(day.minusDays(1), listOf(night), paris))
    }

    @Test fun weeksAndMonthsStartOnMondayAsTheSimulator() {
        assertEquals(LocalDate.of(2026, 11, 9), weekOf(day, DisplayOptions().firstDay).first()) // sim-224
        assertEquals(LocalDate.of(2026, 11, 8), weekOf(day, DayOfWeek.SUNDAY).first())
        val grid = monthGrid(day, DisplayOptions().firstDay)
        assertEquals(42, grid.size)
        assertEquals(LocalDate.of(2026, 10, 26), grid.first()) // sim-225: 26 to 31 October, then the 1st on Sunday
        assertEquals(LocalDate.of(2026, 12, 6), grid.last())
    }

    @Test fun theAgendaShowsTheFreeTimeAroundTheAppointments() {
        // sim-244: "00:00 - 18:30", the appointment, "19:30 - 00:00"; sim-226: "00:00 - 00:00".
        val e = timed(1, 18, 30)
        val rows = agendaRows(day, 2, listOf(e, allDay(2, day)), paris)
        val midnight = LocalTime.MIDNIGHT
        assertEquals(
            listOf(
                AgendaRow.Header(day), AgendaRow.Event(day, allDay(2, day)), AgendaRow.Free(day, midnight, LocalTime.of(18, 30)),
                AgendaRow.Event(day, e), AgendaRow.Free(day, LocalTime.of(19, 30), midnight),
                AgendaRow.Header(day.plusDays(1)), AgendaRow.Free(day.plusDays(1), midnight, midnight),
            ),
            rows,
        )
    }

    @Test fun theAgendaHasNoFreeTimeBetweenOverlappingAppointmentsNorAfterOneRunningPastMidnight() {
        val a = timed(1, 9, minutes = 120)
        val b = timed(2, 10)
        val c = timed(3, 23, minutes = 120)
        val rows = agendaRows(day, 1, listOf(a, b, c), paris)
        assertEquals(
            listOf(
                AgendaRow.Header(day), AgendaRow.Free(day, LocalTime.MIDNIGHT, LocalTime.of(9, 0)), AgendaRow.Event(day, a), AgendaRow.Event(day, b),
                AgendaRow.Free(day, LocalTime.of(11, 0), LocalTime.of(23, 0)), AgendaRow.Event(day, c),
            ),
            rows,
        )
    }

    @Test fun theRangeReadCoversTheViewAndADayEachSide() {
        val (from, to) = range(CalView.DAY, day, paris, DisplayOptions())
        assertEquals(at(day.minusDays(1).atStartOfDay()), from)
        assertEquals(at(day.plusDays(2).atStartOfDay()), to)
    }
}

class MonthMarkTest {
    @kotlin.test.Test fun onlyTheFocusedDayWithAppointmentsHasTheMark() {
        kotlin.test.assertTrue(monthMark(busy = true, focused = true))
        kotlin.test.assertFalse(monthMark(busy = true, focused = false))
        kotlin.test.assertFalse(monthMark(busy = false, focused = true))
        kotlin.test.assertFalse(monthMark(busy = false, focused = false))
    }
}
