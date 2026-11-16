package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextFitTest {
    // 10 px a UTF-16 unit, the mark too.
    private val measure = { s: String -> s.length * 10f }

    @Test
    fun `a text that fits stays whole`() {
        assertEquals("Hello", fit("Hello", 50f, measure))
        assertEquals("", fit("", 0f, measure))
    }

    @Test
    fun `a text too wide is cut with the mark and fits`() {
        val out = fit("Hello world", 60f, measure)
        assertEquals("Hello…", out)
        assertTrue(measure(out) <= 60f)
    }

    @Test
    fun `spaces before the mark are dropped`() {
        assertEquals("Hello…", fit("Hello world", 70f, measure))
    }

    @Test
    fun `nothing fits but the mark`() {
        assertEquals("…", fit("Hello", 10f, measure))
        assertEquals("", fit("Hello", 5f, measure))
        assertEquals("", fit("Hello", 0f, measure))
    }

    @Test
    fun `a pair of units or a mark is never cut`() {
        val smile = "\uD83D\uDE00"
        val accent = "e\u0301"
        // 3 units of room: "a" then the emoji (2 units) would leave only the half.
        assertEquals("a…", fit("a${smile}b$smile", 30f, measure))
        assertEquals("a$smile…", fit("a${smile}b$smile", 40f, measure))
        assertEquals("a…", fit("a${accent}bcdef", 30f, measure))
        assertEquals("a$accent…", fit("a${accent}bcdef", 40f, measure))
    }

    @Test
    fun `any room gives a text no wider than it`() {
        val text = "A rather long label, with an emoji 😀 and é inside"
        for (room in 0..text.length * 10 step 7) assertTrue(measure(fit(text, room.toFloat(), measure)) <= room, "room $room")
    }

    @Test
    fun `a size shrinks until the text fits its room, never grows`() {
        assertEquals(27f, fitSize(27f, 30f, 36f))
        assertEquals(27f, fitSize(27f, 28f, 28f))
        assertEquals(24f, fitSize(27f, 36f, 32f), 0.01f)
        assertEquals(27f, fitSize(27f, 30f, 0f))
    }
}
