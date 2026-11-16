package dev.bold7.launcher.switcher

import dev.bold7.design.HomeDimens
import dev.bold7.launcher.frames.Frame
import dev.bold7.ui.Dir

// The app switcher of BBOS 7 as pure functions: no android.*, tested on the JVM. sim-05 (C): a
// row of the open apps' icons, the home first, the focus on the first app after it; theme.xml (A):
// 5 columns, 3 rows at most. What the capture does not show (the grid beyond one row, the focus
// when opened from an app, the edges) is our own (D).

const val COLUMNS = 5
const val ROWS = 3
const val CAPACITY = COLUMNS * ROWS

/** What a click opens: the home first, then a task of the recents. */
sealed interface Entry {
    data object Home : Entry
    data class Task(val taskId: Int, val packageName: String, val label: String, val activity: String? = null) : Entry {
        /** What its icon is cached under: the package, or the component for a task of the launcher's own. */
        val iconKey: String get() = if (activity == null) packageName else "$packageName/$activity"
    }
}

/** Options' activity, the one task of the launcher the switcher shows. */
const val OPTIONS_ACTIVITY = "dev.bold7.launcher.options.OptionsActivity"

/**
 * The home, then the tasks as the system lists them (most recently used first), [CAPACITY] at
 * most. The launcher's own tasks (the search over the home, a restarted home) are left out: the
 * home entry stands for them; Options is its own application and stays, named "Options". A task
 * of an app the grid hides ([hidden], by package: Android's Settings behind Options, Google
 * Messages behind Messages) is left out when another entry bears its name: the 9900 names an
 * application once.
 */
fun entriesOf(frames: List<Frame>, ownPackage: String, hidden: (packageName: String) -> Boolean = { false }): List<Entry> {
    val tasks = frames.mapNotNull {
        when {
            it.packageName != ownPackage -> Entry.Task(it.taskId, it.packageName, it.label)
            it.activity == OPTIONS_ACTIVITY -> Entry.Task(it.taskId, it.packageName, "Options", it.activity)
            else -> null
        }
    }
    val shown = tasks.filterNot { t -> hidden(t.packageName) && tasks.any { it !== t && it.label == t.label && !hidden(it.packageName) } }
    return (listOf<Entry>(Entry.Home) + shown).take(CAPACITY)
}

/**
 * The entry first focused: opened from the home, the app used last (sim-05, C); opened from an
 * app, which is itself the first of the tasks, the one used before it, as Alt-Tab does (D).
 */
fun initialFocus(count: Int, fromHome: Boolean): Int {
    val wanted = if (fromHome) 1 else 2
    return minOf(wanted, (count - 1).coerceAtLeast(0))
}

/** Where [dir] takes [focus] among [count] entries in [COLUMNS] columns; it stops at the edges. */
fun moveFocus(focus: Int, dir: Dir, count: Int): Int {
    val column = focus % COLUMNS
    return when (dir) {
        Dir.LEFT -> if (column > 0) focus - 1 else focus
        Dir.RIGHT -> if (column < COLUMNS - 1 && focus + 1 < count) focus + 1 else focus
        Dir.UP -> if (focus >= COLUMNS) focus - COLUMNS else focus
        // Down from a column the last row does not reach: its last entry.
        Dir.DOWN -> when {
            focus + COLUMNS < count -> focus + COLUMNS
            focus / COLUMNS < (count - 1) / COLUMNS -> count - 1
            else -> focus
        }
    }
}

/** An entry's cell: its top left corner on screen, [CELL_WIDTH] x [CELL_HEIGHT]. */
data class Cell(val x: Int, val y: Int)

/** The 106 px pitch of sim-05's icons (focus x 161 to 266, C). */
const val CELL_WIDTH = 106
const val CELL_HEIGHT = HomeDimens.ROW

/** The focus square: 106 x 91 on sim-05 (C), the icon's 92 px high here (D). */
const val FOCUS_HEIGHT = HomeDimens.ICON

/** The block of [count] entries centred in [width] by its widest row, a short last row on the columns of the first (the theme's grid, A; D), centred in [height]. */
fun cells(count: Int, width: Int, height: Int): List<Cell> {
    val rows = (count + COLUMNS - 1) / COLUMNS
    val top = (height - rows * CELL_HEIGHT) / 2
    return List(count) { i ->
        Cell((width - minOf(COLUMNS, count) * CELL_WIDTH) / 2 + i % COLUMNS * CELL_WIDTH, top + i / COLUMNS * CELL_HEIGHT)
    }
}

/** The entry under ([x], [y]), or -1. */
fun hit(cells: List<Cell>, x: Float, y: Float): Int =
    cells.indexOfFirst { x >= it.x && x < it.x + CELL_WIDTH && y >= it.y && y < it.y + CELL_HEIGHT }
