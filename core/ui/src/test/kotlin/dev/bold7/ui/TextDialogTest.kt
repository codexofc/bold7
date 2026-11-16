package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The text box of OS 7 against sim-61 (docs/BBOS7-VISUEL.md §12): "New Password:" and its field, no button.
class TextDialogTest {
    private val measure = { s: String -> s.length * 14f }

    @Test fun `one label and its field (sim-61)`() {
        val d = TextDialogLayout("New Password:", measure, 640, 480)
        assertEquals(listOf("New Password:"), d.lines)
        assertEquals(170, d.top) // the capture's box: y 170 to 304
        assertEquals(135, d.height)
        assertEquals(217, d.baseline(0))
        assertEquals(228, d.fieldTop)
        assertEquals(56, d.fieldHeight)
        assertEquals(d.left + 20, d.fieldLeft) // 56 of a box at 36
        assertEquals(d.width - 39, d.fieldWidth) // 529 of 568
        assertEquals(d.left + 19, d.textX)
    }

    @Test fun `on the square the box is as wide as the screen allows`() {
        val d = TextDialogLayout("Folder Name:", measure, 720, 720)
        assertEquals(36, d.left)
        assertEquals(648, d.width)
    }

    @Test fun `a long label wraps to two lines at most and every line fits`() {
        val d = TextDialogLayout("word ".repeat(80), measure, 720, 720)
        assertEquals(2, d.lines.size)
        assertTrue(d.lines.all { measure(it) <= d.width - 19 - 20 - 1 })
        assertTrue(d.lines.last().endsWith(ELLIPSIS))
        assertTrue(d.top >= 0 && d.top + d.height <= 720)
        assertEquals(d.top + 22 + 64 + 4, d.fieldTop)
    }

    @Test fun `a typed text is cleaned and an empty one is refused`() {
        assertEquals("Games", accepted("  Games ", String::trim))
        assertNull(accepted("   ", String::trim))
        assertNull(accepted("a\t", { "" }))
        assertEquals("a b", accepted("a  b", { it.replace(Regex("\\s+"), " ") }))
    }
}
