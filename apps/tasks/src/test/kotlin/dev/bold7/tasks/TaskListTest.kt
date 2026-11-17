package dev.bold7.tasks

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskListTest {
    private val paris = java.time.ZoneId.of("Europe/Paris")
    private fun task(id: Long, title: String, status: Status = Status.NOT_STARTED, priority: Priority = Priority.NORMAL, due: LocalDateTime? = null) =
        Task(id, title, status, priority, due, "", 0)

    // sim-280: Aard deferred, Taska completed and high, Taskb in progress and low, Taskc waiting and high, Zulu.
    private val aard = task(1, "Aard", Status.DEFERRED, due = LocalDateTime.of(2026, 11, 17, 17, 0))
    private val taska = task(2, "Taska", Status.COMPLETED, Priority.HIGH, LocalDateTime.of(2026, 11, 17, 17, 0))
    private val taskb = task(3, "Taskb", Status.IN_PROGRESS, Priority.LOW, LocalDateTime.of(2026, 11, 1, 9, 0))
    private val taskc = task(4, "taskc", Status.WAITING, Priority.HIGH)
    private val zulu = task(5, "Zulu")
    private val all = listOf(zulu, taskc, taskb, taska, aard)
    private fun loaded(sort: SortBy = SortBy.SUBJECT) = TaskList().reduce(ListEvent.Loaded(all, sort))

    @Test fun theListIsSortedBySubjectTheCompletedOnesInTheirPlace() {
        assertEquals(listOf(aard, taska, taskb, taskc, zulu), ordered(all))
    }

    @Test fun theOtherSortsBreakTiesByTitle() {
        assertEquals(listOf(taska, taskc, aard, zulu, taskb), ordered(all, SortBy.PRIORITY))
        assertEquals(listOf(taskb, aard, taska, taskc, zulu), ordered(all, SortBy.DUE_DATE))
        assertEquals(listOf(zulu, taskb, taska, taskc, aard), ordered(all, SortBy.STATUS))
        assertEquals(SortBy.PRIORITY, loaded(SortBy.PRIORITY).sort)
    }

    @Test fun addTaskComesFirstAndTheFocusOpensOnTheFirstTask() {
        val s = loaded()
        assertEquals(listOf(AddTask) + listOf(aard, taska, taskb, taskc, zulu).map(::Entry), s.rows)
        assertEquals(Entry(aard), s.focused)
    }

    @Test fun hidingCompletedTasksDropsThemFromTheList() {
        val s = loaded().reduce(ListEvent.ToggleHidden)
        assertEquals(listOf(aard, taskb, taskc, zulu), s.shown)
        assertEquals(taska, loaded().reduce(ListEvent.ToggleHidden).reduce(ListEvent.ToggleHidden).shown[1])
    }

    @Test fun theFocusStaysOnItsTaskWhenTheListIsReloadedOrReordered() {
        val s = loaded().reduce(ListEvent.Moved(4))
        assertEquals(Entry(taskc), s.focused)
        assertEquals(Entry(taskc), s.reduce(ListEvent.Loaded(all + task(9, "Abc"))).focused)
        assertEquals(Entry(taskc), s.reduce(ListEvent.Loaded(all, SortBy.PRIORITY)).focused)
    }

    @Test fun theFocusMovesAndStopsAtTheEnds() {
        var s = loaded()
        repeat(20) { s = s.reduce(ListEvent.Step(down = true)) }
        assertEquals(Entry(zulu), s.focused)
        repeat(20) { s = s.reduce(ListEvent.Step(down = false)) }
        assertEquals(AddTask, s.focused)
    }

    @Test fun anEmptyListHasOnlyAddTask() {
        val s = TaskList().reduce(ListEvent.Loaded(emptyList()))
        assertEquals(listOf<Row>(AddTask), s.rows)
        assertEquals(AddTask, s.focused)
    }

    @Test fun theFullMenuOnANotStartedTaskIsSim281sWithOpenFocusedAndCloseLast() {
        val m = loaded().reduce(ListEvent.Moved(5)).menu()
        assertEquals(
            listOf(ListAct.SWITCH, ListAct.NEW, ListAct.OPEN, ListAct.DELETE, ListAct.DELETE_COMPLETED, ListAct.COMPLETE, ListAct.PROGRESS, ListAct.HIDE, ListAct.OPTIONS, ListAct.HELP, ListAct.CLOSE),
            m.entries.map { it.action },
        )
        assertEquals(2, m.highlighted)
        assertTrue(m.entries[m.entries.size - 3].separatorAbove)
    }

    @Test fun aCompletedTaskCannotBeMarkedCompletedAndOneInProgressNotInProgress() {
        val done = loaded().reduce(ListEvent.Moved(2))
        assertEquals(Entry(taska), done.focused)
        assertFalse(ListAct.COMPLETE in done.menu().entries.map { it.action })
        assertTrue(ListAct.PROGRESS in done.menu().entries.map { it.action })
        val going = loaded().reduce(ListEvent.Moved(3)).menu().entries.map { it.action }
        assertFalse(ListAct.PROGRESS in going)
        assertTrue(ListAct.COMPLETE in going)
    }

    @Test fun onAddTaskTheMenuHasNoTaskActionsAndNoShortMenu() {
        val s = loaded().reduce(ListEvent.Moved(0))
        assertEquals(listOf(ListAct.SWITCH, ListAct.NEW, ListAct.DELETE_COMPLETED, ListAct.HIDE, ListAct.OPTIONS, ListAct.HELP, ListAct.CLOSE), s.menu().entries.map { it.action })
        assertEquals(1, s.menu().highlighted)
        assertEquals(listOf(ListAct.SHOW), s.reduce(ListEvent.ToggleHidden).menu().entries.map { it.action }.filter { it == ListAct.SHOW })
        assertNull(s.shortMenu())
        assertFalse(ListAct.DELETE_COMPLETED in TaskList().reduce(ListEvent.Loaded(listOf(zulu))).menu().entries.map { it.action })
    }

    @Test fun theShortMenuIsDeleteThenMarkCompletedThenOpenFocused() {
        val m = loaded().shortMenu()!!
        assertEquals(listOf(ListAct.DELETE, ListAct.COMPLETE, ListAct.OPEN), m.entries.map { it.action })
        assertEquals(2, m.highlighted)
    }

    @Test fun anOpenTaskPastItsDueTimeIsOverdueNotACompletedOne() {
        val now = LocalDateTime.of(2026, 11, 16, 4, 0)
        assertTrue(taskb.overdue(now, paris))
        assertFalse(aard.overdue(now, paris))
        assertFalse(taska.overdue(now.plusDays(2), paris))
        assertFalse(zulu.overdue(now, paris))
        assertTrue(aard.overdue(LocalDateTime.of(2026, 11, 17, 17, 1), paris))
        // Due at 17:00 in New York (23:00 here): not overdue at 18:00 here.
        assertFalse(aard.copy(zone = java.time.ZoneId.of("America/New_York")).overdue(LocalDateTime.of(2026, 11, 17, 18, 0), paris))
    }

    @Test fun dueDatesReadAsTheFormShowsThem() {
        assertEquals("Tue 17 Nov 2026 17:00", aard.dueLabel)
        assertEquals("", zulu.dueLabel)
    }

    @Test fun typingFiltersByTheWordsOfTheTitleAndTheNotes() {
        val withNotes = listOf(aard.copy(notes = "call the garage"), zulu)
        val s = TaskList().reduce(ListEvent.Loaded(withNotes)).reduce(ListEvent.Typed('g'))
        assertEquals(listOf(withNotes[0]), s.shown)
        assertEquals(Entry(withNotes[0]), s.focused)
        assertEquals(listOf(zulu), TaskList().reduce(ListEvent.Loaded(withNotes)).reduce(ListEvent.Typed('z')).shown)
        assertEquals("Gr", s.reduce(ListEvent.Typed('r')).field)
    }

    @Test fun anythingTypedThatNoTaskMatchesLeavesOnlyAddTask() {
        val s = loaded().reduce(ListEvent.Typed('q'))
        assertEquals(listOf<Row>(AddTask), s.rows)
        assertEquals(AddTask, s.focused)
    }

    @Test fun erasingWidensTheListAndEscapeClearsThenLeaves() {
        val s = loaded().reduce(ListEvent.Typed('q')).reduce(ListEvent.Erased)
        assertEquals(listOf(aard, taska, taskb, taskc, zulu), s.shown)
        assertEquals(s, s.reduce(ListEvent.Erased))
        assertEquals("", loaded().reduce(ListEvent.Typed('t')).back()?.typed)
        assertNull(loaded().back())
    }
}
