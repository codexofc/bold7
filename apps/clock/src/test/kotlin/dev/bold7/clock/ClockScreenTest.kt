package dev.bold7.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockScreenTest {
    private fun acts(s: ClockScreen, running: Boolean = false, snoozed: Boolean = false, counted: Boolean = false) = s.menu(running, snoozed, counted).first.map { it.action }

    @Test fun theClocksMenuIsThe9900s() {
        // sim-154, sim-155: Help before Close.
        assertEquals(listOf(Act.SWITCH, Act.SET_ALARM, Act.SET_TIME, Act.BEDSIDE, Act.STOPWATCH, Act.TIMER, Act.OPTIONS, Act.HELP, Act.CLOSE), acts(ClockScreen()))
        assertEquals(1, ClockScreen().menu(false).second)
        assertEquals(listOf(false, true, false, true, true, false, true, false, false), ClockScreen().menu(false).first.map { it.separatorAbove })
    }

    @Test fun cancelSnoozeIsOnTheClocksMenuOnlyWhileTheAlarmSleeps() {
        // D for its place, under Set Alarm: the 9900's menu is sim-154's, this line is added while a snooze is set.
        assertEquals(listOf(Act.SWITCH, Act.SET_ALARM, Act.CANCEL_SNOOZE, Act.SET_TIME, Act.BEDSIDE, Act.STOPWATCH, Act.TIMER, Act.OPTIONS, Act.HELP, Act.CLOSE), acts(ClockScreen(), snoozed = true))
        assertEquals(listOf(false, true, false, false, true, true, false, true, false, false), ClockScreen().menu(false, true).first.map { it.separatorAbove })
        assertEquals(acts(ClockScreen(Page.STOPWATCH)), acts(ClockScreen(Page.STOPWATCH), snoozed = true))
    }

    @Test fun theStopwatchsMenuFollowsItsState() {
        // sim-166 stopped, sim-168 running.
        val s = ClockScreen(Page.STOPWATCH)
        assertEquals(listOf(Act.SWITCH, Act.START, Act.CLOCK, Act.TIMER, Act.OPTIONS, Act.HELP, Act.CLOSE), acts(s))
        assertEquals(listOf(Act.SWITCH, Act.STOP, Act.LAP, Act.CLOCK, Act.TIMER, Act.OPTIONS, Act.HELP, Act.CLOSE), acts(s, running = true))
    }

    @Test fun sendResultsIsOnTheStopwatchsMenuOnceItHasCounted() {
        val s = ClockScreen(Page.STOPWATCH)
        assertEquals(listOf(Act.SWITCH, Act.START, Act.SEND_RESULTS, Act.CLOCK, Act.TIMER, Act.OPTIONS, Act.HELP, Act.CLOSE), acts(s, counted = true))
        assertEquals(listOf(Act.SWITCH, Act.STOP, Act.LAP, Act.SEND_RESULTS, Act.CLOCK, Act.TIMER, Act.OPTIONS, Act.HELP, Act.CLOSE), acts(s, running = true, counted = true))
        assertEquals(acts(ClockScreen()), acts(ClockScreen(), counted = true))
    }

    @Test fun theButtonsOfTheStopwatchAndTheTimer() {
        val s = ClockScreen(Page.STOPWATCH)
        assertEquals(Act.START, s.click(false))
        assertEquals(Act.STOP, s.click(true))
        assertEquals(Act.LAP, s.step(right = true).click(true))
        assertEquals(Act.RESET, s.step(right = true).click(false))
        assertEquals(0, s.step(right = true).step(right = false).focus)
        assertEquals(Act.RESET, ClockScreen(Page.TIMER, 1).click(true))
        // The clock has no buttons: its click sets the alarm.
        assertEquals(ClockScreen(), ClockScreen().step(right = true))
        assertEquals(Act.SET_ALARM, ClockScreen().click(false))
    }

    @Test fun escapeLeavesBedsideModeElseClosesTheApp() {
        assertEquals(ClockScreen(), ClockScreen(Page.BEDSIDE).back())
        assertNull(ClockScreen(Page.STOPWATCH).back())
        assertNull(ClockScreen().back())
    }

    @Test fun theTimerOffersResetOnlyOnceItHasStarted() {
        // sim-326 (stopped, at its full time) and sim-328 (running): Reset appears with the count, before Set Timer.
        val t = ClockScreen(Page.TIMER)
        assertEquals(listOf(Act.SWITCH, Act.START, Act.SET_TIMER, Act.CLOCK, Act.STOPWATCH, Act.OPTIONS, Act.HELP, Act.CLOSE), acts(t))
        assertEquals(listOf(Act.SWITCH, Act.STOP, Act.RESET, Act.SET_TIMER, Act.CLOCK, Act.STOPWATCH, Act.OPTIONS, Act.HELP, Act.CLOSE), acts(t, running = true))
        assertEquals(listOf(Act.SWITCH, Act.START, Act.RESET, Act.SET_TIMER, Act.CLOCK, Act.STOPWATCH, Act.OPTIONS, Act.HELP, Act.CLOSE), acts(t, counted = true))
    }

    @Test fun bedsideModeHasTheClocksMenuWithExit() {
        // sim-324: Switch Application, Set Alarm, Set Time, Exit Bedside Mode, Stopwatch, Timer, Options.
        assertEquals(listOf(Act.SWITCH, Act.SET_ALARM, Act.SET_TIME, Act.EXIT_BEDSIDE, Act.STOPWATCH, Act.TIMER, Act.OPTIONS, Act.HELP, Act.CLOSE), acts(ClockScreen(Page.BEDSIDE)))
        assertEquals(1, ClockScreen(Page.BEDSIDE).menu(false).second)
    }
}
