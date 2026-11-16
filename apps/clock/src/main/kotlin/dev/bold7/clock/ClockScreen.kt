package dev.bold7.clock

import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.withSwitchApplication

// The Clock's pages and their menus, pure (sim-154, sim-155, sim-166, sim-168, C). Tested on the
// JVM (ClockScreenTest).

enum class Page { CLOCK, STOPWATCH, TIMER, BEDSIDE }

enum class Act { SET_ALARM, SET_TIME, BEDSIDE, EXIT_BEDSIDE, STOPWATCH, TIMER, CLOCK, START, STOP, LAP, RESET, SET_TIMER, OPTIONS, CLOSE, CANCEL_SNOOZE, SEND_RESULTS, SWITCH }

/**
 * The page shown and the trackpad's focus on its buttons: on the stopwatch and the timer, 0 the left
 * button (start or stop, sim-165), 1 the right one (lap while the stopwatch runs, reset otherwise).
 * The app reopens on the page it left (sim-165, C), so the page outlives the menu.
 */
data class ClockScreen(val page: Page = Page.CLOCK, val focus: Int = 0) {
    /**
     * The full menu (Help is not there yet), Switch Application first (sim-154, sim-166). Clock: Set Alarm, Cancel
     * Snooze while the alarm sleeps (D), Set Time |
     * Bedside Mode | Stopwatch, Timer | Options, Close, Set Alarm under the focus (sim-154). Stopwatch:
     * Start or Stop, Lap while running, Send Results once it has counted (guide p. 204, B; its place is D), Clock, Timer | Options, Close (sim-166, sim-168). Timer and
     * Bedside Mode: D, after the stopwatch's.
     */
    fun menu(running: Boolean, snoozed: Boolean = false, counted: Boolean = false): Pair<List<MenuEntry<Act>>, Int> {
        val tail = listOf(MenuEntry(Act.OPTIONS, separatorAbove = true), MenuEntry(Act.CLOSE))
        val entries = when (page) {
            Page.CLOCK -> listOfNotNull(
                MenuEntry(Act.SET_ALARM), MenuEntry(Act.CANCEL_SNOOZE).takeIf { snoozed }, MenuEntry(Act.SET_TIME), MenuEntry(Act.BEDSIDE, separatorAbove = true),
                MenuEntry(Act.STOPWATCH, separatorAbove = true), MenuEntry(Act.TIMER),
            )
            Page.STOPWATCH -> listOfNotNull(
                MenuEntry(if (running) Act.STOP else Act.START), MenuEntry(Act.LAP).takeIf { running },
                MenuEntry(Act.SEND_RESULTS).takeIf { counted }, MenuEntry(Act.CLOCK), MenuEntry(Act.TIMER),
            )
            Page.TIMER -> listOfNotNull(
                MenuEntry(if (running) Act.STOP else Act.START), MenuEntry(Act.RESET).takeIf { running || counted }, MenuEntry(Act.SET_TIMER),
                MenuEntry(Act.CLOCK), MenuEntry(Act.STOPWATCH),
            )
            Page.BEDSIDE -> listOf(
                MenuEntry(Act.SET_ALARM), MenuEntry(Act.SET_TIME), MenuEntry(Act.EXIT_BEDSIDE, separatorAbove = true),
                MenuEntry(Act.STOPWATCH, separatorAbove = true), MenuEntry(Act.TIMER),
            )
        }
        val full = Menu(entries + tail, highlighted = 0).withSwitchApplication(Act.SWITCH)
        return full.entries to full.highlighted!!
    }

    /** A page opened from the menu, its focus on the left button. */
    fun open(page: Page) = ClockScreen(page, 0)

    /** The trackpad left or right between the two buttons. */
    fun step(right: Boolean) = if (page == Page.STOPWATCH || page == Page.TIMER) copy(focus = if (right) 1 else 0) else this

    /** What the trackpad's click does here. */
    fun click(running: Boolean): Act? = when (page) {
        Page.CLOCK -> Act.SET_ALARM
        Page.STOPWATCH -> if (focus == 0) (if (running) Act.STOP else Act.START) else (if (running) Act.LAP else Act.RESET)
        Page.TIMER -> if (focus == 0) (if (running) Act.STOP else Act.START) else Act.RESET
        Page.BEDSIDE -> null
    }

    /** Escape: Bedside Mode goes back to the clock (guide p. 204); elsewhere the app closes (sim-166). */
    fun back(): ClockScreen? = if (page == Page.BEDSIDE) ClockScreen() else null
}
