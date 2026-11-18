package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldMovementTest {
    @Test
    fun `a one-line field is left at once, up or down`() {
        assertTrue(leavesField(line = 0, lines = 1, down = true))
        assertTrue(leavesField(line = 0, lines = 1, down = false))
    }

    @Test
    fun `a field of several lines moves its caret until its first or last line`() {
        assertFalse(leavesField(line = 0, lines = 3, down = true))
        assertFalse(leavesField(line = 1, lines = 3, down = true))
        assertTrue(leavesField(line = 2, lines = 3, down = true))
        assertFalse(leavesField(line = 2, lines = 3, down = false))
        assertTrue(leavesField(line = 0, lines = 3, down = false))
    }
}
