package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

// Dialogs and status messages against the simulator's captures (docs/BBOS7-VISUEL.md §12, §13).
class DialogLayoutTest {
    // BBAlpha Sans 27's advances of the strings these captures show (FreeType, the firmware's TTF).
    private val widths = mapOf("You have 2 new missed calls." to 399f, "View" to 64f, "Close" to 75f, "OK" to 40f)
    private fun measure(s: String) = widths[s] ?: (s.length * 15f)

    @Test fun `a text without icon, two buttons (sim-19)`() {
        val d = DialogLayout("You have 2 new missed calls.", listOf("View", "Close"), icon = false, measure = ::measure, screenWidth = 640, screenHeight = 480)
        assertEquals(listOf("You have 2 new missed calls."), d.lines)
        assertEquals(100, d.left)
        assertEquals(116, d.top)
        assertEquals(439, d.width)
        assertEquals(244, d.height)
        assertEquals(119, d.textX)
        assertEquals(163, d.baseline(0))
        assertEquals(194, d.buttonTop(0))
        assertEquals(268, d.buttonTop(1))
        assertEquals(239, d.buttonWidth) // 238 on the capture
        assertEquals(200, d.buttonLeft)
    }

    @Test fun `an icon, two lines centred on it, one button (sim-62)`() {
        // 15 px a character: "The password cannot be a sequence." is 510 px, more than the 452 left.
        val d = DialogLayout("The password cannot be a sequence.", listOf("OK"), icon = true, measure = ::measure, screenWidth = 640, screenHeight = 480)
        assertEquals(listOf("The password cannot be a", "sequence."), d.lines)
        assertEquals(24 * 15 + 124, d.width)
        assertEquals(218, d.height)
        assertEquals(129, d.top)
        assertEquals(d.left + 20, d.iconX)
        assertEquals(151, d.iconY)
        assertEquals(d.left + 103, d.textX)
        assertEquals(184, d.baseline(0))
        assertEquals(216, d.baseline(1))
        assertEquals(255, d.buttonTop(0))
    }

    @Test fun `a status message is a box without buttons, 20 px under its text (sim-42)`() {
        val d = DialogLayout("Screen and Keypad are locked. Press the Lock button to unlock", emptyList(), icon = false, measure = ::measure, screenWidth = 640, screenHeight = 480)
        assertEquals(2, d.lines.size)
        assertEquals(22 + 64 + 20 + 1, d.height)
        assertEquals((480 - d.height - 4) / 2, d.top)
    }

    @Test fun `the box keeps 5 percent of the screen on each side, the square's too`() {
        val d = DialogLayout("x".repeat(100), emptyList(), icon = false, measure = ::measure, screenWidth = 720, screenHeight = 720)
        assertEquals(1, d.lines.size) // one word: not broken, but cut by an ellipsis when wider than the box
        assertTrue(d.lines[0].endsWith(ELLIPSIS) && measure(d.lines[0]) <= 648 - 19 - 20 - 1)
        assertEquals(648, d.width)
        assertEquals(36, d.left)
    }

    @Test fun `a long label widens every button`() {
        val d = DialogLayout("Device is locked", listOf("Unlock", "Cancel", "x".repeat(16)), icon = true, measure = ::measure, screenWidth = 640, screenHeight = 480)
        assertEquals(16 * 15 + 40, d.buttonWidth)
    }

    @Test fun `a touch finds its button, a letter its mnemonic`() {
        val d = DialogLayout("You have 2 new missed calls.", listOf("View", "Close"), icon = false, measure = ::measure, screenWidth = 640, screenHeight = 480)
        assertEquals(0, d.buttonAt(300f, 194f))
        assertEquals(1, d.buttonAt(300f, 318f))
        assertNull(d.buttonAt(300f, 250f))
        assertNull(d.buttonAt(150f, 200f))
        assertEquals(1, mnemonic(listOf("Save", "Discard", "Cancel"), 'd'))
        assertEquals(2, mnemonic(listOf("Save", "Discard", "Cancel"), 'C'))
        assertNull(mnemonic(listOf("Save"), 'x'))
    }
}
