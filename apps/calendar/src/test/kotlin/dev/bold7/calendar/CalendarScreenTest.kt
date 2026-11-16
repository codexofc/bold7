package dev.bold7.calendar

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarScreenTest {
    private val day = LocalDate.of(2026, 11, 13)

    @Test fun spaceAndShiftSpaceMoveADayAWeekOrAMonth() {
        // Guide p. 58: Space forward, Shift+Space back, in the Week, Month and Day views.
        assertEquals(day.plusDays(1), CalendarScreen(CalView.DAY, day).key(CalKey.NEXT).day)
        assertEquals(day.minusDays(1), CalendarScreen(CalView.DAY, day).key(CalKey.PREVIOUS).day)
        assertEquals(day.plusWeeks(1), CalendarScreen(CalView.WEEK, day).key(CalKey.NEXT).day)
        assertEquals(day.minusMonths(1), CalendarScreen(CalView.MONTH, day).key(CalKey.PREVIOUS).day)
        assertEquals(day, CalendarScreen(CalView.AGENDA, day).key(CalKey.NEXT, rows = 1).day)
    }

    @Test fun theTrackpadMovesTheFocusInsideTheView() {
        val d = CalendarScreen(CalView.DAY, day, hour = 0)
        assertEquals(ALL_DAY, d.key(CalKey.UP).hour)
        assertEquals(ALL_DAY, d.key(CalKey.UP).key(CalKey.UP).hour)
        assertEquals(23, CalendarScreen(CalView.WEEK, day, hour = 23).key(CalKey.DOWN).hour)
        assertEquals(day.minusDays(1), CalendarScreen(CalView.WEEK, day).key(CalKey.LEFT).day)
        assertEquals(18, CalendarScreen(CalView.DAY, day, hour = 18).key(CalKey.RIGHT).hour) // the hour stays on another day
        val m = CalendarScreen(CalView.MONTH, day)
        assertEquals(day.minusWeeks(1), m.key(CalKey.UP).day)
        assertEquals(day.plusDays(1), m.key(CalKey.RIGHT).day)
        val a = CalendarScreen(CalView.AGENDA, day, row = 1)
        assertEquals(2, a.key(CalKey.DOWN, rows = 3).row)
        assertEquals(2, a.key(CalKey.DOWN, rows = 3).key(CalKey.DOWN, rows = 3).row)
        assertEquals(0, a.key(CalKey.UP).key(CalKey.UP).row)
    }

    @Test fun anotherViewKeepsTheDayAndTheHour() {
        val s = CalendarScreen(CalView.DAY, day, hour = 18).show(CalView.WEEK)
        assertEquals(CalendarScreen(CalView.WEEK, day, 18, 0), s)
        assertEquals(s, s.show(CalView.WEEK))
        assertEquals(0, CalendarScreen(CalView.AGENDA, day, row = 5).goTo(day.plusDays(3)).row)
    }

    @Test fun theClickOpensSchedulesOrShowsADay() {
        val e = Occurrence(1, "e", 0, 1)
        assertEquals(Click.Open(e), CalendarScreen(CalView.DAY, day, hour = 18).click(emptyList(), listOf(e)))
        assertEquals(Click.New(day, 18), CalendarScreen(CalView.DAY, day, hour = 18).click(emptyList(), emptyList()))
        assertEquals(Click.New(day, 10), CalendarScreen(CalView.WEEK, day, hour = 10).click(emptyList(), emptyList()))
        assertEquals(Click.ShowDay(day), CalendarScreen(CalView.MONTH, day).click(emptyList(), emptyList()))
        val agenda = listOf(AgendaRow.Header(day), AgendaRow.Event(day, e), AgendaRow.Free(day.plusDays(1), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT))
        assertEquals(Click.Open(e), CalendarScreen(CalView.AGENDA, day, row = 1).click(agenda, emptyList()))
        assertEquals(Click.ShowDay(day.plusDays(1)), CalendarScreen(CalView.AGENDA, day, row = 2).click(agenda, emptyList()))
        assertNull(CalendarScreen(CalView.AGENDA, day, row = 2).focused(agenda, emptyList()))
    }

    @Test fun theMenuIsTheSimulatorsWithoutWhatIsNotThereYet() {
        // sim-221, sim-222: New Appointment under the focus without an appointment.
        val (empty, focus) = menu(null)
        assertEquals(listOf(Act.SWITCH, Act.NEW, Act.NEW_ALARM, Act.NEW_TASK, Act.TODAY, Act.GO_TO_DATE, Act.VIEW, Act.CLOSE), empty.map { it.action })
        assertEquals(1, focus)
        // sim-240: Open under the focus on an appointment, Delete after it.
        val (onEvent, focus2) = menu(Occurrence(1, "e", 0, 1))
        assertEquals(listOf(Act.SWITCH, Act.NEW, Act.NEW_ALARM, Act.NEW_TASK, Act.OPEN, Act.DELETE, Act.TODAY, Act.GO_TO_DATE, Act.VIEW, Act.CLOSE), onEvent.map { it.action })
        assertEquals(4, focus2)
        val invitation = menu(Occurrence(1, "e", 0, 1, invitation = true)).first.map { it.action }
        assertEquals(listOf(Act.SWITCH, Act.NEW, Act.NEW_ALARM, Act.NEW_TASK, Act.OPEN, Act.DELETE, Act.ACCEPT, Act.TENTATIVE, Act.DECLINE, Act.TODAY, Act.GO_TO_DATE, Act.VIEW, Act.CLOSE), invitation)
    }

    @Test fun viewsSubmenuListsTheOtherViews() =
        // sim-223, from the Day view.
        assertEquals(listOf(Act.WEEK, Act.MONTH, Act.AGENDA), viewMenu(CalView.DAY).map { it.action })

    @Test fun lettersAreQuickEntryInTheDayViewAndShortcutsElsewhere() {
        assertTrue(quickEntry(CalView.DAY, 'c'))
        assertFalse(quickEntry(CalView.WEEK, 'c'))
        assertEquals(Letter.MONTH, LETTERS['m'])
        assertEquals(Letter.TODAY, LETTERS['t'])
    }

    @Test fun quickEntryBackspaceClosesTheBoxOnceTheSubjectIsEmpty() {
        assertEquals("Lunc", quickBackspace("Lunch"))
        assertEquals(null, quickBackspace("L"))
    }
}
