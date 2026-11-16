package dev.bold7.ui

import dev.bold7.design.DialogDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A dialog's texts on the square, with a fixed measure (14 px a unit): wrapped, then cut to the box.
class DialogTextTest {
    private val measure = { s: String -> s.length * 14f }

    @Test fun `a long text wraps and every line fits the box`() {
        val d = DialogLayout("Do you really want to delete this very long named contact now?", listOf("Delete", "Cancel"), true, measure, 720, 720)
        assertTrue(d.lines.size > 1)
        val room = d.width - DialogDimens.ICON_TEXT_X - DialogDimens.PAD_RIGHT - 1
        assertTrue(d.lines.all { measure(it) <= room })
    }

    @Test fun `a word wider than the box is cut by an ellipsis`() {
        val d = DialogLayout("x".repeat(200), listOf("OK"), false, measure, 720, 720)
        assertEquals(1, d.lines.size)
        assertTrue(d.lines[0].endsWith(ELLIPSIS))
        assertTrue(d.left >= 0 && d.left + d.width <= 720)
        assertTrue(d.textX + measure(d.lines[0]) <= d.left + d.width)
    }

    @Test fun `a text taller than the screen ends in an ellipsis and the box stays on the screen`() {
        val d = DialogLayout("word ".repeat(400), listOf("OK"), false, measure, 720, 720)
        assertTrue(d.lines.last().endsWith(ELLIPSIS))
        assertTrue(d.top >= 0 && d.top + d.height <= 720)
    }

    @Test fun `a button label too wide for the box is cut inside its button`() {
        val d = DialogLayout("Question?", listOf("A button label wider than any box can hold at all"), false, measure, 720, 720)
        assertTrue(measure(d.buttonLabels[0]) <= TextRoom.button(d.buttonWidth))
        assertEquals(listOf("OK"), DialogLayout("Delete this contact?", listOf("OK"), false, measure, 720, 720).buttonLabels)
    }
}
