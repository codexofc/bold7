package dev.bold7.launcher.switcher

import dev.bold7.launcher.frames.Frame
import dev.bold7.ui.Dir
import kotlin.test.Test
import kotlin.test.assertEquals

class SwitcherTest {
    private fun frames(n: Int) = List(n) { Frame(it + 10, "pkg$it", "App $it", 0L) }

    @Test fun theHomeComesFirstThenTheTasksInTheSystemsOrder() {
        val e = entriesOf(frames(2), "own")
        assertEquals(Entry.Home, e[0])
        assertEquals(Entry.Task(10, "pkg0", "App 0"), e[1])
        assertEquals(3, e.size)
    }

    @Test fun theLaunchersOwnTasksAreLeftOutTheHomeStandsForThem() {
        val f = frames(3) + Frame(99, "own", "Bold7", 0L)
        assertEquals(listOf<Entry>(Entry.Home, Entry.Task(10, "pkg0", "App 0"), Entry.Task(11, "pkg1", "App 1"), Entry.Task(12, "pkg2", "App 2")), entriesOf(f, "own"))
        assertEquals(listOf<Entry>(Entry.Home), entriesOf(f.takeLast(1), "own"))
    }

    @Test fun optionsIsTheLaunchersOnlyTaskTheSwitcherShowsUnderItsOwnNameAndIcon() {
        val f = frames(2) + Frame(98, "own", "Bold7", 0L, OPTIONS_ACTIVITY) + Frame(99, "own", "Bold7", 0L, "dev.bold7.launcher.LauncherActivity")
        val e = entriesOf(f, "own")
        assertEquals(Entry.Task(98, "own", "Options", OPTIONS_ACTIVITY), e.last())
        assertEquals(4, e.size)
        assertEquals("own/$OPTIONS_ACTIVITY", (e.last() as Entry.Task).iconKey)
        assertEquals("pkg0", (e[1] as Entry.Task).iconKey)
    }

    @Test fun aHiddenAppsTaskThatBearsAnotherEntrysNameIsLeftOut() {
        // Android's Settings (named Options) beside Options, Google Messages (named Messages) beside Messages.
        val f = listOf(
            Frame(1, "settings", "Options", 0L),
            Frame(2, "own", "Bold7", 0L, OPTIONS_ACTIVITY),
            Frame(3, "gmsg", "Messages", 0L),
            Frame(4, "bmsg", "Messages", 0L),
            Frame(5, "other", "Other", 0L),
        )
        val e = entriesOf(f, "own") { it == "settings" || it == "gmsg" }
        assertEquals(listOf(Entry.Home, Entry.Task(2, "own", "Options", OPTIONS_ACTIVITY), Entry.Task(4, "bmsg", "Messages"), Entry.Task(5, "other", "Other")), e)
    }

    @Test fun aHiddenAppsTaskAloneStaysUnderItsName() {
        val e = entriesOf(listOf(Frame(1, "settings", "Options", 0L)), "own") { it == "settings" }
        assertEquals(listOf(Entry.Home, Entry.Task(1, "settings", "Options")), e)
    }

    @Test fun noMoreThanFifteenEntries() {
        assertEquals(CAPACITY, entriesOf(frames(40), "own").size)
        assertEquals(15, CAPACITY)
    }

    @Test fun fromTheHomeTheFocusIsOnTheLastUsedAppFromAnAppOnTheOneBeforeIt() {
        assertEquals(1, initialFocus(count = 4, fromHome = true))
        assertEquals(2, initialFocus(count = 4, fromHome = false))
        // Few apps: the nearest entry that exists.
        assertEquals(0, initialFocus(count = 1, fromHome = true))
        assertEquals(1, initialFocus(count = 2, fromHome = false))
        assertEquals(0, initialFocus(count = 0, fromHome = true))
    }

    @Test fun focusMovesInFiveColumnsAndStopsAtTheEdges() {
        assertEquals(1, moveFocus(0, Dir.RIGHT, 8))
        assertEquals(4, moveFocus(4, Dir.RIGHT, 8))
        assertEquals(0, moveFocus(0, Dir.LEFT, 8))
        assertEquals(5, moveFocus(0, Dir.DOWN, 8))
        assertEquals(0, moveFocus(5, Dir.UP, 8))
        assertEquals(2, moveFocus(2, Dir.UP, 8))
        // The last row is short: no move to the right of its last entry, down lands on it.
        assertEquals(7, moveFocus(7, Dir.RIGHT, 8))
        assertEquals(7, moveFocus(4, Dir.DOWN, 8))
        assertEquals(7, moveFocus(7, Dir.DOWN, 8))
    }

    @Test fun aRowIsCentredOnTheScreen() {
        val c = cells(5, 720, 720)
        assertEquals((720 - 5 * CELL_WIDTH) / 2, c[0].x)
        assertEquals(c[0].x + CELL_WIDTH, c[1].x)
        // Alone, a short row is centred; a single entry sits in the middle.
        assertEquals((720 - 3 * CELL_WIDTH) / 2, cells(3, 720, 720)[0].x)
        assertEquals((720 - CELL_WIDTH) / 2, cells(1, 720, 720)[0].x)
    }

    @Test fun rowsAreABlockCentredInTheHeight() {
        val c = cells(7, 720, 720)
        assertEquals((720 - 2 * CELL_HEIGHT) / 2, c[0].y)
        assertEquals(c[0].y + CELL_HEIGHT, c[5].y)
        assertEquals(c[0].x, c[5].x) // the second row starts on the first's first column
    }

    @Test fun aTouchFindsItsCell() {
        val c = cells(7, 720, 720)
        assertEquals(6, hit(c, c[6].x + 3f, c[6].y + 3f))
        assertEquals(-1, hit(c, 1f, 1f))
    }
}
