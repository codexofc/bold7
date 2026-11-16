package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Menus of seven entries and of six.
class MenuTest {
    private val seven = List(7) { MenuEntry(it) }
    private val six = List(6) { MenuEntry(it) }

    @Test fun `the highlight starts at an end and stops at the other`() {
        val menu = Menu(seven)
        assertEquals(0, menu.step(down = true).highlighted)
        assertEquals(6, menu.step(down = false).highlighted)
        assertEquals(1, menu.step(down = true).step(down = true).highlighted)
        assertEquals(6, menu.copy(highlighted = 6).step(down = true).highlighted)
        assertEquals(0, menu.copy(highlighted = 0).step(down = false).highlighted)
    }

    @Test fun `a long press opens the column, which expands with its highlight`() {
        val column = Menu(six, line = 3)
        assertEquals(false, column.expanded)
        assertEquals(true, Menu(seven).expanded)
        assertEquals(Menu(six, line = 3, expanded = true, highlighted = 2), column.copy(highlighted = 2).expand())
    }

    // The keys over a menu (BBOS7-VISUEL §4): the full menu's rows, the short menu's grid ending
    // on Full Menu.

    @Test fun `the full menu moves, chooses and closes`() {
        val menu = Menu(seven, highlighted = 2)
        assertEquals(MenuResult.Show(menu.copy(highlighted = 3)), menu.key(MenuKey.DOWN))
        assertEquals(MenuResult.Choose(2), menu.key(MenuKey.CLICK))
        assertEquals(MenuResult.Close, menu.key(MenuKey.BACK))
        assertEquals(MenuResult.Close, menu.key(MenuKey.MENU))
        assertEquals(MenuResult.Show(menu), menu.key(MenuKey.LEFT))
        assertEquals(MenuResult.Show(menu.copy(highlighted = 0)), Menu(seven).key(MenuKey.CLICK))
    }

    @Test fun `the short menu moves on its grid, Full Menu and the Menu key expand it`() {
        val short = Menu(six, line = 3, highlighted = 4)
        assertEquals(true, short.short)
        assertEquals(7, short.shortCells) // six actions and Full Menu
        assertEquals(MenuResult.Show(short.copy(highlighted = 5)), short.key(MenuKey.RIGHT))
        assertEquals(MenuResult.Show(short.copy(highlighted = 1)), short.key(MenuKey.UP))
        assertEquals(MenuResult.Choose(4), short.key(MenuKey.CLICK))
        val onFull = short.copy(highlighted = 6)
        assertEquals(MenuResult.Show(Menu(six, line = 3, expanded = true, highlighted = 0)), onFull.key(MenuKey.CLICK))
        assertEquals(MenuResult.Show(short.expand()), short.key(MenuKey.MENU))
        assertEquals(MenuResult.Close, short.key(MenuKey.BACK))
    }

    @Test fun `the short menu shows eight actions at most`() {
        val short = Menu(List(12) { MenuEntry(it) }, line = 0)
        assertEquals(9, short.shortCells)
        assertEquals(0, short.copy(highlighted = 8).expand().highlighted) // Full Menu, not the ninth action
        assertEquals(5, short.copy(highlighted = 5).expand().highlighted)
    }

    // Switch Application, the first row of every full menu of OS 7 (sim-04, sim-155, sim-182b, C),
    // alone in its group.

    @Test fun `Switch Application opens a full menu and shifts its focus`() {
        val menu = Menu(listOf(MenuEntry("send"), MenuEntry("clear"), MenuEntry("close", separatorAbove = true)), highlighted = 1)
        val with = menu.withSwitchApplication("switch")
        assertEquals(listOf("switch", "send", "clear", "close"), with.entries.map { it.action })
        assertEquals(listOf(false, true, false, true), with.entries.map { it.separatorAbove })
        assertEquals(2, with.highlighted)
        assertEquals("clear", with.entries[with.highlighted!!].action)
    }

    @Test fun `Switch Application leaves an unfocused, an empty and a short menu alone`() {
        assertEquals(null, Menu(six).withSwitchApplication(-1).highlighted)
        assertEquals(listOf(-1), Menu(emptyList<MenuEntry<Int>>()).withSwitchApplication(-1).entries.map { it.action })
        val short = Menu(six, line = 2, highlighted = 1)
        assertEquals(short, short.withSwitchApplication(-1))
    }

    private val withSub = Menu(listOf(MenuEntry(0), MenuEntry(1, submenu = true), MenuEntry(2)), highlighted = 1)
    private val sub = Menu(listOf(MenuEntry(10), MenuEntry(11)), highlighted = 0)

    @Test fun `a menu that replaces one on its submenu row is that row's submenu`() {
        assertTrue(isSubmenu(withSub, sub))
        assertFalse(isSubmenu(withSub.copy(highlighted = 0), sub)) // another row
        assertFalse(isSubmenu(null, sub)) // none before
        assertFalse(isSubmenu(withSub, null))
        assertFalse(isSubmenu(withSub, withSub.copy(highlighted = 2))) // the same entries: the menu itself
        assertFalse(isSubmenu(withSub, Menu(listOf(MenuEntry(5)), line = 3, expanded = false))) // short menu
    }

    @Test fun `the trackpad's right arrow opens a submenu and leaves other rows alone`() {
        assertEquals(MenuResult.Choose(1), withSub.key(MenuKey.RIGHT))
        assertEquals(MenuResult.Show(withSub.copy(highlighted = 0)), withSub.copy(highlighted = 0).key(MenuKey.RIGHT))
    }
}

