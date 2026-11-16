package dev.bold7.ui

import dev.bold7.design.MenuDimens

// The menus of BlackBerry OS 7.1, pure (docs/BBOS7-VISUEL.md §4): the full menu (Menu key) and the
// short one (the trackpad held, or a long press), which MenuView draws.

/**
 * A row of a menu. [separatorAbove]: a line between it and the row above, between two groups of
 * the full menu (sim-04, sim-44, C). [shortcut]: a key written at the row's right, which OS 7
 * does not draw; no menu sets it. [submenu]: it opens a submenu, a triangle at
 * the full menu's right (sim-292, C).
 */
data class MenuEntry<A>(val action: A, val shortcut: String = "", val separatorAbove: Boolean = false, val submenu: Boolean = false)

/**
 * A menu open over a page: the full menu ([expanded]), or after a long press on line [line] the
 * short menu, a grid of its first actions ending on Full Menu, which expands it. [highlighted]: the
 * entry under the trackpad's focus, the default action when it opens (sim-04, sim-17, C); on the
 * short menu, [shortCells] - 1 is Full Menu.
 */
data class Menu<A>(
    val entries: List<MenuEntry<A>>,
    val line: Int? = null,
    val expanded: Boolean = line == null,
    val highlighted: Int? = null,
) {
    /** The trackpad moves the highlight, from the first entry down or the last one up, and stops at the ends. */
    fun step(down: Boolean): Menu<A> {
        val last = entries.size - 1
        val next = when (val h = highlighted) {
            null -> if (down) 0 else last
            else -> (if (down) h + 1 else h - 1).coerceIn(0, last)
        }
        return copy(highlighted = next)
    }

    /** The short menu's: drawn as a grid, until it expands. */
    val short: Boolean get() = line != null && !expanded

    /** The short menu's cells: its first actions, at most [MenuDimens.SHORT_MAX_ACTIONS], then Full Menu. */
    val shortCells: Int get() = minOf(entries.size, MenuDimens.SHORT_MAX_ACTIONS) + 1

    /** The full menu, its focus kept on an action; from the short menu's Full Menu cell, on the first row. */
    fun expand() = copy(expanded = true, highlighted = highlighted?.takeIf { !short || it < shortCells - 1 } ?: 0)

    /** A key over this menu: arrows move, the click chooses, Back closes, Menu closes the full menu and expands the short one (D). */
    fun key(key: MenuKey): MenuResult<A> {
        val h = highlighted
        if (key == MenuKey.BACK) return MenuResult.Close
        if (!short) return when (key) {
            MenuKey.UP -> MenuResult.Show(step(down = false))
            MenuKey.DOWN -> MenuResult.Show(step(down = true))
            MenuKey.RIGHT -> if (h != null && entries[h].submenu) MenuResult.Choose(h) else MenuResult.Show(this)
            MenuKey.CLICK -> if (h == null) MenuResult.Show(copy(highlighted = 0)) else MenuResult.Choose(h)
            MenuKey.MENU -> MenuResult.Close
            else -> MenuResult.Show(this)
        }
        val dir = when (key) {
            MenuKey.UP -> Dir.UP
            MenuKey.DOWN -> Dir.DOWN
            MenuKey.LEFT -> Dir.LEFT
            MenuKey.RIGHT -> Dir.RIGHT
            MenuKey.MENU -> return MenuResult.Show(expand())
            else -> null
        }
        if (dir != null) return MenuResult.Show(copy(highlighted = if (h == null) 0 else shortMove(h, shortCells, dir)))
        return when (h) {
            null -> MenuResult.Show(copy(highlighted = 0))
            shortCells - 1 -> MenuResult.Show(expand())
            else -> MenuResult.Choose(h)
        }
    }
}

/** The keys a menu takes: the trackpad's moves and click (or Enter), Back (Escape), Menu. */
enum class MenuKey { UP, DOWN, LEFT, RIGHT, CLICK, BACK, MENU }

/** What a key does to a menu: another state of it, the entry chosen, or the menu closed. */
sealed interface MenuResult<out A> {
    data class Show<A>(val menu: Menu<A>) : MenuResult<A>
    data class Choose(val index: Int) : MenuResult<Nothing>
    data object Close : MenuResult<Nothing>
}

/**
 * Whether [new], bound over [old], is the submenu of [old]'s highlighted row: a full menu that
 * replaces a full one whose focus was on a row marked [MenuEntry.submenu] (View, To Metric, Attach).
 * The apps bind a submenu in place of their menu; [MenuView] then draws it at its right, the menu
 * staying behind it (sim-223, sim-251).
 */
fun <A> isSubmenu(old: Menu<A>?, new: Menu<A>?): Boolean {
    if (old == null || new == null || old.short || new.short || !old.expanded || !new.expanded) return false
    if (old.entries === new.entries || old.entries == new.entries) return false
    return old.highlighted?.let { old.entries.getOrNull(it)?.submenu } == true
}
