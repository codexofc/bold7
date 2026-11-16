package dev.bold7.launcher.keys

import dev.bold7.launcher.home.App
import dev.bold7.launcher.home.BannerFocus
import dev.bold7.launcher.home.HomeEvent
import dev.bold7.launcher.home.HomeState
import dev.bold7.launcher.home.reduce
import dev.bold7.ui.Dir
import dev.bold7.ui.MenuKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusTest {
    private fun icon(i: Int) = Focus.Icon(i)
    private val tab = Focus.Tab
    private fun top(t: BannerFocus) = Focus.Top(t)

    @Test fun theFirstMoveShowsTheFocusOnTheFirstIconOrTheTabWhenTheViewIsEmpty() {
        assertEquals(FocusMove(icon(0)), move(null, Dir.RIGHT, count = 8))
        assertEquals(FocusMove(tab), move(null, Dir.DOWN, count = 0))
    }

    @Test fun iconsMoveInSixColumnsAndStopAtTheEdges() {
        assertEquals(FocusMove(icon(1)), move(icon(0), Dir.RIGHT, 8))
        assertEquals(FocusMove(icon(5)), move(icon(5), Dir.RIGHT, 8))
        assertEquals(FocusMove(icon(6)), move(icon(0), Dir.DOWN, 8))
        assertEquals(FocusMove(icon(0)), move(icon(0), Dir.LEFT, 8))
        // Down from a column the last row does not reach: its last icon.
        assertEquals(FocusMove(icon(7)), move(icon(4), Dir.DOWN, 8))
        assertEquals(FocusMove(icon(7)), move(icon(7), Dir.DOWN, 8))
        assertEquals(FocusMove(icon(7)), move(icon(7), Dir.RIGHT, 8))
    }

    @Test fun upFromTheFirstRowGoesToTheTabThenTheIndicatorRowThenTheBanner() {
        assertEquals(FocusMove(tab), move(icon(3), Dir.UP, 8))
        assertEquals(FocusMove(top(BannerFocus.NOTIFICATIONS)), move(tab, Dir.UP, 8))
        assertEquals(FocusMove(top(BannerFocus.BANNER)), move(top(BannerFocus.NOTIFICATIONS), Dir.UP, 8))
        assertEquals(FocusMove(top(BannerFocus.BANNER)), move(top(BannerFocus.BANNER), Dir.UP, 8))
        assertEquals(FocusMove(top(BannerFocus.NOTIFICATIONS)), move(top(BannerFocus.BANNER), Dir.DOWN, 8))
    }

    @Test fun theIndicatorRowGoesSpeakerNotificationsSearch() {
        assertEquals(FocusMove(top(BannerFocus.SPEAKER)), move(top(BannerFocus.NOTIFICATIONS), Dir.LEFT, 8))
        assertEquals(FocusMove(top(BannerFocus.SEARCH)), move(top(BannerFocus.NOTIFICATIONS), Dir.RIGHT, 8))
        assertEquals(FocusMove(top(BannerFocus.SPEAKER)), move(top(BannerFocus.SPEAKER), Dir.LEFT, 8))
        assertEquals(FocusMove(tab), move(top(BannerFocus.SEARCH), Dir.DOWN, 8))
    }

    @Test fun sidewaysOnTheTabChangesTheViewDownEntersIt() {
        assertEquals(FocusMove(tab, panel = -1), move(tab, Dir.LEFT, 8))
        assertEquals(FocusMove(tab, panel = 1), move(tab, Dir.RIGHT, 8))
        assertEquals(FocusMove(icon(0)), move(tab, Dir.DOWN, 8))
        assertEquals(FocusMove(tab), move(tab, Dir.DOWN, 0))
    }

    @Test fun anIconGoneLeavesTheFocusOnTheLastOne() {
        assertEquals(icon(2), valid(icon(9), 3))
        assertEquals(tab, valid(icon(0), 0))
        assertEquals(tab, valid(tab, 0))
    }

    @Test fun overAnOpenMenuEachTrackpadStepIsAMenuArrow() {
        assertEquals(listOf(MenuKey.UP, MenuKey.DOWN, MenuKey.LEFT, MenuKey.RIGHT), listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT).map(::menuKeyOf))
    }

    @Test fun theTrayScrollsByRowsToKeepTheFocusedRowShown() {
        // 5 rows shown expanded, 1 collapsed (BBOS7-VISUEL §2, D).
        assertEquals(0, firstRow(first = 0, focusRow = 4, shownRows = 5, rows = 8))
        assertEquals(1, firstRow(first = 0, focusRow = 5, shownRows = 5, rows = 8))
        assertEquals(2, firstRow(first = 3, focusRow = 2, shownRows = 5, rows = 8))
        assertEquals(3, firstRow(first = 0, focusRow = 3, shownRows = 1, rows = 8))
        // Never past the last rows, never below 0.
        assertEquals(3, firstRow(first = 7, focusRow = 7, shownRows = 5, rows = 8))
        assertEquals(0, firstRow(first = 4, focusRow = null, shownRows = 5, rows = 2))
    }

    /**
     * The trackpad walked down 45 icons and back, a step at a time, each step coming while the
     * grid still springs to the row the step before asked for (the
     * focus went under the last row shown and the grid stayed). The grid's target follows every
     * step, and the focused row is always one of those it shows.
     */
    @Test fun theFocusedRowStaysInSightStepAfterStepEvenWhileTheGridSprings() {
        for (shown in listOf(5, 1)) { // expanded, collapsed
            val count = 45
            val rows = (count + 5) / 6
            var first = 0 // the row the grid rests on or springs to
            var focus: Focus = icon(0)
            fun step(dir: Dir) {
                focus = move(focus, dir, count).focus
                val row = (focus as? Focus.Icon)?.index?.div(6)
                scrollTo(first, row, shown, rows)?.let { first = it }
                if (row != null) assertTrue(row in first until first + shown, "row $row shown from $first ($shown rows)")
            }
            repeat(rows) { step(Dir.DOWN) }
            assertEquals(rows - shown, first)
            repeat(rows) { step(Dir.UP) }
            assertEquals(tab, focus)
            assertEquals(0, first)
        }
    }

    @Test fun theGridStaysWhereItGoesWhenTheFocusIsAlreadyShown() {
        // A spring in flight to row 2 (a flick) is left to land: no target, no jump.
        assertNull(scrollTo(first = 2, focusRow = 4, shownRows = 5, rows = 8))
        assertNull(scrollTo(first = 2, focusRow = null, shownRows = 5, rows = 8))
        assertEquals(1, scrollTo(first = 0, focusRow = 5, shownRows = 5, rows = 8))
    }

    /**
     * lowered, the trackpad going down scrolled the one row and
     * the tray stayed down. On the 9900 (sim-70, sim-71, C) going down from the lowered row
     * raises the tray, the focus staying on its icon; raised, down moves a row as before.
     */
    @Test fun downFromTheLoweredRowRaisesTheTrayTheFocusStaying() {
        assertEquals(FocusMove(icon(3), expand = true), move(icon(3), Dir.DOWN, 45, expanded = false))
        assertEquals(FocusMove(icon(9)), move(icon(3), Dir.DOWN, 45, expanded = true))
        // Sideways it moves in the row, never past its ends (sim-76, sim-77, C); up goes to the tab.
        assertEquals(FocusMove(icon(5)), move(icon(5), Dir.RIGHT, 45, expanded = false))
        assertEquals(FocusMove(icon(4)), move(icon(5), Dir.LEFT, 45, expanded = false))
        assertEquals(FocusMove(tab), move(icon(3), Dir.UP, 45, expanded = false))
        // The first move only shows the focus, the tab enters the row: nothing raises.
        assertEquals(FocusMove(icon(0)), move(null, Dir.DOWN, 45, expanded = false))
        assertEquals(FocusMove(icon(0)), move(tab, Dir.DOWN, 45, expanded = false))
    }

    /** Down from the tab goes back to the icon focused last (sim-80, C), the last one if it is gone. */
    @Test fun downFromTheTabGoesBackToTheLastIcon() {
        assertEquals(FocusMove(icon(5)), move(tab, Dir.DOWN, 45, expanded = false, last = 5))
        assertEquals(FocusMove(icon(13)), move(tab, Dir.DOWN, 45, expanded = true, last = 13))
        assertEquals(FocusMove(icon(2)), move(tab, Dir.DOWN, 3, last = 13))
    }

    /** Raised, up from the first row goes to the tab, then the row and the banner: the tray stays up (sim-72 to sim-74, C). */
    @Test fun upFromTheRaisedTrayNeverLowersIt() {
        assertEquals(FocusMove(tab), move(icon(2), Dir.UP, 45, expanded = true))
        assertEquals(FocusMove(top(BannerFocus.NOTIFICATIONS)), move(tab, Dir.UP, 45, expanded = true))
    }

    /** Lowered (Escape, sim-75, C), the focus goes to its column in the first row, the one the tray shows. */
    @Test fun loweredTheFocusGoesToItsColumnInTheFirstRow() {
        assertEquals(icon(1), lowered(icon(7)))
        assertEquals(icon(0), lowered(icon(24)))
        assertEquals(icon(4), lowered(icon(4)))
        assertEquals(tab, lowered(tab))
        assertEquals(top(BannerFocus.SEARCH), lowered(top(BannerFocus.SEARCH)))
    }

    /**
     * the tab named Calendar with the focus on Camera. Walked
     * through every step, lowered then raised, the name is always the focused icon's.
     */
    @Test fun theTabNamesTheFocusedIconAtEveryStep() {
        val apps = (0 until 20).map { App("p$it", "p$it.Main", "App %02d".format(it)) }
        var state = reduce(HomeState(), HomeEvent.AppsLoaded(apps))
        var focus: Focus? = null
        fun step(dir: Dir) {
            val m = move(focus, dir, state.shown.size, state.expanded)
            if (m.expand) state = reduce(state, HomeEvent.Expand(true))
            focus = m.focus
            val i = (focus as? Focus.Icon)?.index
            assertEquals(i?.let { "App %02d".format(it) }, focusName(state, focus))
        }
        repeat(6) { step(Dir.RIGHT) }
        step(Dir.DOWN)
        assertTrue(state.expanded)
        assertEquals(icon(5), focus)
        repeat(3) { step(Dir.DOWN) }
        repeat(4) { step(Dir.LEFT) }
        repeat(4) { step(Dir.UP) }
        assertNull(focusName(state, tab))
    }
}
