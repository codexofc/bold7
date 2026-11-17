package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals

// Help of the full menus: just before Close (sim-442, C).
class HelpTest {
    private fun menu(vararg a: String, highlighted: Int? = null) = Menu(a.map { MenuEntry(it) }, highlighted = highlighted)

    @Test fun `Help goes just before Close, in Close's group`() {
        val m = Menu(listOf(MenuEntry("new"), MenuEntry("options", separatorAbove = true), MenuEntry("close"))).withHelp("help", before = "close")
        assertEquals(listOf("new", "options", "help", "close"), m.entries.map { it.action })
        assertEquals(listOf(false, true, false, false), m.entries.map { it.separatorAbove })
    }

    @Test fun `a separator above Close moves to Help`() {
        val m = Menu(listOf(MenuEntry("save"), MenuEntry("close", separatorAbove = true))).withHelp("help", before = "close")
        assertEquals(listOf(false, true, false), m.entries.map { it.separatorAbove })
    }

    @Test fun `the focus stays on its action`() {
        assertEquals(2, menu("a", "b", "close", highlighted = 1).withHelp("help", before = "close").highlighted?.let { it + 1 })
        assertEquals(3, menu("a", "b", "close", highlighted = 2).withHelp("help", before = "close").highlighted)
        assertEquals(null, menu("a", "close").withHelp("help", before = "close").highlighted)
    }

    @Test fun `without Close Help ends the menu, a short menu is left as it is`() {
        assertEquals(listOf("a", "help"), menu("a").withHelp("help", before = "close").entries.map { it.action })
        val short = Menu(listOf(MenuEntry("a"), MenuEntry("close")), line = 0)
        assertEquals(short, short.withHelp("help", before = "close"))
    }
}
