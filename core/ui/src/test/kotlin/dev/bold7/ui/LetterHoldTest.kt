package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LetterHoldTest {
    private val a = 29
    private val b = 30

    @Test fun `a short press acts on its up`() {
        val hold = LetterHold()
        assertEquals(emptyList(), hold.down(a, 'a', repeat = 0))
        assertEquals(LetterHold.Out.Tap('a'), hold.up(a))
        assertEquals(null, hold.up(a))
    }

    @Test fun `a held letter is a hold once, and its up is nothing`() {
        val hold = LetterHold()
        hold.down(a, 'a', 0)
        assertEquals(listOf(LetterHold.Out.Hold('a')), hold.down(a, 'a', repeat = 1))
        assertEquals(emptyList(), hold.down(a, 'a', repeat = 2))
        assertEquals(null, hold.up(a))
    }

    @Test fun `another key coming first lets the first one act`() {
        val hold = LetterHold()
        hold.down(a, 'a', 0)
        assertEquals(listOf(LetterHold.Out.Tap('a')), hold.down(b, 'b', 0))
        assertEquals(LetterHold.Out.Tap('b'), hold.up(b))
        assertEquals(null, hold.up(a))
    }

    @Test fun `flushing lets a waiting letter act, clearing drops it`() {
        val hold = LetterHold()
        hold.down(a, 'a', 0)
        assertEquals(LetterHold.Out.Tap('a'), hold.flush())
        assertEquals(null, hold.flush())
        hold.down(b, 'b', 0)
        hold.clear()
        assertEquals(null, hold.up(b))
    }

    @Test fun `the up of an other key changes nothing`() {
        val hold = LetterHold()
        hold.down(a, 'a', 0)
        assertEquals(null, hold.up(b))
        assertEquals(LetterHold.Out.Tap('a'), hold.up(a))
    }

    @Test fun `a first down again starts over when its up was lost`() {
        val hold = LetterHold()
        hold.down(a, 'a', 0)
        assertEquals(listOf(LetterHold.Out.Tap('a')), hold.down(a, 'a', 0))
        assertEquals(LetterHold.Out.Tap('a'), hold.up(a))
    }
}
