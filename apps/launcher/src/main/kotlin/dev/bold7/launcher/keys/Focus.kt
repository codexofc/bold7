package dev.bold7.launcher.keys

import dev.bold7.design.HomeDimens
import dev.bold7.launcher.home.App
import dev.bold7.launcher.home.BannerFocus
import dev.bold7.launcher.home.Folder
import dev.bold7.launcher.home.HomeState
import dev.bold7.ui.Dir
import dev.bold7.ui.MenuKey

// The trackpad's focus on the BBOS 7 home as pure functions: no android.*, tested on the JVM.
// sim-07, sim-08, sim-22, sim-25 (C): the focus goes from the tray's icons to the tab of the
// views, to the indicator row, to the banner; sideways on the tab it changes the view. What the
// captures do not show (the edges, the way back down) is our own (D).

sealed interface Focus {
    /** The [index]th tile of the view shown. */
    data class Icon(val index: Int) : Focus

    /** The tab of the views (sim-07). */
    data object Tab : Focus

    /** The banner or a target of the indicator row. */
    data class Top(val target: BannerFocus) : Focus
}

/** Where a move lands, the view it turns to (-1 the previous one, 1 the next), and whether it raises the tray. */
data class FocusMove(val focus: Focus, val panel: Int = 0, val expand: Boolean = false)

private const val COLUMNS = HomeDimens.COLUMNS

/**
 * Where [dir] takes [focus] in a view of [count] tiles, the tray raised or not ([expanded]), the
 * icon focused last at [last]; the first move only shows it. Down from the lowered row raises the tray, the focus staying on its
 * icon (sim-70, sim-71, C): the lowered tray never scrolls.
 */
fun move(focus: Focus?, dir: Dir, count: Int, expanded: Boolean = true, last: Int = 0): FocusMove {
    val f = focus?.let { valid(it, count) } ?: return FocusMove(if (count > 0) Focus.Icon(0) else Focus.Tab)
    return when (f) {
        is Focus.Icon -> if (!expanded && dir == Dir.DOWN) FocusMove(f, expand = true) else FocusMove(icon(f.index, dir, count))
        Focus.Tab -> when (dir) {
            Dir.LEFT -> FocusMove(f, panel = -1)
            Dir.RIGHT -> FocusMove(f, panel = 1)
            // Down from the tab: back to the icon focused [last] (sim-80, C).
            Dir.DOWN -> FocusMove(if (count > 0) valid(Focus.Icon(last), count) else f)
            Dir.UP -> FocusMove(Focus.Top(BannerFocus.NOTIFICATIONS))
        }
        is Focus.Top -> FocusMove(top(f.target, dir))
    }
}

private fun icon(i: Int, dir: Dir, n: Int): Focus {
    val column = i % COLUMNS
    return when (dir) {
        Dir.UP -> if (i >= COLUMNS) Focus.Icon(i - COLUMNS) else Focus.Tab
        Dir.DOWN -> Focus.Icon(if (i + COLUMNS < n) i + COLUMNS else if (i / COLUMNS < (n - 1) / COLUMNS) n - 1 else i)
        Dir.LEFT -> Focus.Icon(if (column > 0) i - 1 else i)
        Dir.RIGHT -> Focus.Icon(if (column < COLUMNS - 1 && i + 1 < n) i + 1 else i)
    }
}

private fun top(t: BannerFocus, dir: Dir): Focus = when (dir) {
    Dir.UP -> Focus.Top(BannerFocus.BANNER)
    Dir.DOWN -> if (t == BannerFocus.BANNER) Focus.Top(BannerFocus.NOTIFICATIONS) else Focus.Tab
    Dir.LEFT -> Focus.Top(
        when (t) {
            BannerFocus.SEARCH -> BannerFocus.NOTIFICATIONS
            BannerFocus.NOTIFICATIONS, BannerFocus.SPEAKER -> BannerFocus.SPEAKER
            BannerFocus.BANNER -> t
        },
    )
    Dir.RIGHT -> Focus.Top(
        when (t) {
            BannerFocus.SPEAKER -> BannerFocus.NOTIFICATIONS
            BannerFocus.NOTIFICATIONS, BannerFocus.SEARCH -> BannerFocus.SEARCH
            BannerFocus.BANNER -> t
        },
    )
}

/** Over an open menu, a trackpad step or an arrow key is the menu's arrow (core/ui's Menu.key). */
fun menuKeyOf(dir: Dir): MenuKey = when (dir) {
    Dir.UP -> MenuKey.UP
    Dir.DOWN -> MenuKey.DOWN
    Dir.LEFT -> MenuKey.LEFT
    Dir.RIGHT -> MenuKey.RIGHT
}

/** The tray lowered (Escape, Restore Panel, a swipe): an icon's focus goes to its column in the first row (sim-75, C). */
fun lowered(focus: Focus): Focus = if (focus is Focus.Icon) Focus.Icon(focus.index % COLUMNS) else focus

/** The name the tab shows for [focus]: the focused tile's (sim-08), none off the icons. */
fun focusName(state: HomeState, focus: Focus?): String? = when (val tile = (focus as? Focus.Icon)?.index?.let(state::tileAt)) {
    is App -> tile.label
    is Folder -> tile.name
    null -> null
}

/** [focus] in a view of [count] tiles: an icon gone moves to the last one, or to the tab. */
fun valid(focus: Focus, count: Int): Focus = when {
    focus !is Focus.Icon || focus.index < count -> focus
    count > 0 -> Focus.Icon(count - 1)
    else -> Focus.Tab
}

/**
 * The first row the tray shows, [shownRows] at a time out of [rows]: moved just enough to show
 * [focusRow] (none: kept), and never past the last rows (sim-09, sim-10: a row at a time, C).
 */
fun firstRow(first: Int, focusRow: Int?, shownRows: Int, rows: Int): Int {
    val f = when {
        focusRow == null -> first
        focusRow < first -> focusRow
        focusRow >= first + shownRows -> focusRow - shownRows + 1
        else -> first
    }
    return f.coerceIn(0, (rows - shownRows).coerceAtLeast(0))
}

/**
 * Where the grid goes so [focusRow] shows, from [first], the row it rests on or already springs
 * to; null when it goes nowhere new. A spring in flight never holds a step back: the focus would
 * leave the rows shown.
 */
fun scrollTo(first: Int, focusRow: Int?, shownRows: Int, rows: Int): Int? = firstRow(first, focusRow, shownRows, rows).takeIf { it != first }
