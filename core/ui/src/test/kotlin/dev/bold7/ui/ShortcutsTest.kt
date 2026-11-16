package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShortcutsTest {
    private enum class A { TOP, BOTTOM, COMPOSE }

    private val table = shortcuts('t' to A.TOP, 'B' to A.BOTTOM, 'c' to A.COMPOSE)

    @Test fun `a letter gives its action, whatever its case`() {
        assertEquals(A.TOP, table.of('t'))
        assertEquals(A.TOP, table.of('T'))
        assertEquals(A.BOTTOM, table.of('b'))
        assertEquals(A.COMPOSE, table.of('C'))
    }

    @Test fun `a letter the screen does not declare gives nothing`() {
        assertNull(table.of('r'))
        assertNull(table.of(' '))
        assertNull(table.of('\u0000')) // a key without a label (DPAD, Back)
    }

    @Test fun `nothing goes while a text field has the focus`() {
        // The letters are typed there.
        assertNull(table.of('t', textFocused = true))
    }

    @Test fun `a letter declared twice is a mistake of the table`() {
        assertFailsWith<IllegalArgumentException> { shortcuts('t' to A.TOP, 'T' to A.BOTTOM) }
    }
}
