package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuKeyGateTest {
    private val click = 23

    @Test fun `the release of a click that chose in a menu does not reach the page`() {
        val g = MenuKeyGate()
        assertFalse(g.swallows(click, down = true, repeat = 0, menuOpen = true))
        // The press chose: the menu is closed when the release comes.
        assertTrue(g.swallows(click, down = false, repeat = 0, menuOpen = false))
    }

    @Test fun `its repeats, the click held after the choice, do not reach the page either`() {
        val g = MenuKeyGate()
        g.swallows(click, down = true, repeat = 0, menuOpen = true)
        assertTrue(g.swallows(click, down = true, repeat = 1, menuOpen = false))
        assertTrue(g.swallows(click, down = false, repeat = 0, menuOpen = false))
    }

    @Test fun `a release while the menu is still open is the menu's, for the views that choose on it`() {
        val g = MenuKeyGate()
        g.swallows(click, down = true, repeat = 0, menuOpen = true)
        assertFalse(g.swallows(click, down = true, repeat = 1, menuOpen = true))
        // A submenu opened on the press, or a launcher menu that chooses on the release.
        assertFalse(g.swallows(click, down = false, repeat = 0, menuOpen = true))
        // Passed once: the next press, menu closed, is the page's.
        assertFalse(g.swallows(click, down = true, repeat = 0, menuOpen = false))
        assertFalse(g.swallows(click, down = false, repeat = 0, menuOpen = false))
    }

    @Test fun `a click pressed with no menu is the page's, even if it opens one`() {
        val g = MenuKeyGate()
        assertFalse(g.swallows(click, down = true, repeat = 0, menuOpen = false))
        // Held into a short menu: its repeats and release are the view's (held).
        assertFalse(g.swallows(click, down = true, repeat = 1, menuOpen = true))
        assertFalse(g.swallows(click, down = false, repeat = 0, menuOpen = true))
    }

    @Test fun `only the key pressed over the menu is held back`() {
        val g = MenuKeyGate()
        g.swallows(click, down = true, repeat = 0, menuOpen = true)
        assertFalse(g.swallows(66, down = false, repeat = 0, menuOpen = false))
        assertTrue(g.swallows(click, down = false, repeat = 0, menuOpen = false))
        // Released, it is forgotten.
        assertFalse(g.swallows(click, down = false, repeat = 0, menuOpen = false))
    }

    @Test fun `Escape that closed the menu is held back the same way`() {
        val g = MenuKeyGate()
        val escape = 111
        g.swallows(escape, down = true, repeat = 0, menuOpen = true)
        assertTrue(g.swallows(escape, down = false, repeat = 0, menuOpen = false))
    }
}
