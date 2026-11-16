package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SymbolsTest {
    @Test fun twoPagesOfThreeRowsLaidOverTheKeys() {
        // sim-303, sim-304: ten, nine, then seven keys and the arrow over SYM.
        for (p in Symbols.PAGES) assertEquals(listOf(10, 9, 8), p.map { it.length })
        assertEquals("[<{«~=±“^%", Symbols.PAGES[0][0])
        assertEquals("789?!,.◂", Symbols.PAGES[1][2])
    }

    @Test fun aLetterChoosesTheGlyphOverItsKeyOnThePageShown() {
        assertEquals('[', Symbols.of('q', 0))
        assertEquals('#', Symbols.of('Q', 1))
        assertEquals('7', Symbols.of('z', 1))
        assertEquals('¤', Symbols.of('M', 0))
        assertNull(Symbols.of('1', 0))
    }

    @Test fun theArrowOverSymTurnsThePage() {
        val f = Symbols.Focus(0, 2, 7)
        assertTrue(f.flips)
        assertEquals(Symbols.Focus(1, 2, 7), f.flipped())
        assertEquals(Symbols.Focus(0, 2, 7), f.flipped().flipped())
        assertTrue(!Symbols.Focus(0, 2, 6).flips)
    }

    @Test fun theTrackpadMovesTheFocusInTheGridAndStopsAtItsEdges() {
        var f = Symbols.Focus(0, 0, 0)
        f = f.move(-1, 0)
        assertEquals(Symbols.Focus(0, 0, 0), f)
        f = f.move(0, 2).move(9, 0)
        assertEquals(Symbols.Focus(0, 2, 7), f)
        f = f.move(0, -1)
        assertEquals(Symbols.Focus(0, 1, 7), f)
        f = f.move(9, 0).move(0, -1).move(1, 0)
        assertEquals(Symbols.Focus(0, 0, 9), f)
        f = f.move(0, 1)
        assertEquals(Symbols.Focus(0, 1, 8), f, "down from a column the row below lacks: its last")
        assertEquals('=', Symbols.Focus(0, 0, 5).symbol)
    }
}
