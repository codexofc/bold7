package dev.bold7.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Help's screen: a page, or the search's results once something is typed (sim-444, C).
class HelpScreenTest {
    private val topics = listOf(Found("1.html", "Delete a message"), Found("2.html", "Forward a message"))
    private val search = { q: String -> if ("mess".startsWith(q) || q.startsWith("mess")) topics else emptyList() }

    @Test fun `typing shows the results, the bands and Device, the focus staying in the field`() {
        val s = HelpScreen().typed('m', search)
        assertEquals("m", s.query)
        assertEquals(listOf(Band.RESULTS, Result(topics[0]), Result(topics[1]), Band.EXTENDED, Device), s.rows)
        assertEquals(HelpScreen.FIELD, s.focus)
        assertEquals(1, s.step(down = true).focus)
    }

    @Test fun `the trackpad skips the bands`() {
        val s = HelpScreen().typed('m', search).step(down = true).step(down = true).step(down = true)
        assertEquals(4, s.focus)
        assertEquals(1, s.step(down = false).step(down = false).focus)
        assertEquals(HelpScreen.FIELD, s.step(down = false).step(down = false).step(down = false).focus)
    }

    @Test fun `nothing found, the trackpad goes down to Device`() {
        val s = HelpScreen().typed('x', search)
        assertEquals(listOf(Band.RESULTS, Band.EXTENDED, Device), s.rows)
        assertEquals(2, s.step(down = true).focus)
        assertEquals(0, s.found)
    }

    @Test fun `erasing the last letter, or Back, goes back to the page`() {
        val s = HelpScreen().typed('m', search)
        assertEquals(HelpScreen(), s.erased(search))
        assertEquals(HelpScreen(), s.back())
        assertNull(HelpScreen().back())
    }
}
