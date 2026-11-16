package dev.bold7.ui

import dev.bold7.design.MenuDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The menus' geometry against the 9900 simulator's captures (docs/BBOS7-VISUEL.md §4), in its
// pixels: 640x480, the same as the Q25's.
class MenuLayoutTest {
    // sim-04, the home's menu: Switch Application | Maximise Panel | Add Folder | Manage Panels, Options.
    private val home = FullMenuLayout(
        widths = floatArrayOf(252f, 212f, 147f, 190f, 101f),
        separatorAbove = booleanArrayOf(false, true, true, true, false),
        height = 480,
        minTop = 0,
    )

    // sim-44 and sim-45, Messages: Switch Application | Compose Email, Compose Other | Search,
    // Search by, Display Style, View Folder, View Saved Messages | Message Notifiers, Options.
    private val messages = FullMenuLayout(
        widths = floatArrayOf(252f, 214f, 214f, 90f, 130f, 180f, 150f, 299f, 230f, 110f),
        separatorAbove = booleanArrayOf(false, true, false, true, false, false, false, false, true, false),
        height = 480,
        minTop = 70,
    )

    @Test fun `the full menu is as wide as its longest label and sits on the bottom`() {
        assertEquals(266, home.right)
        assertEquals(164, home.top)
        assertEquals(0, home.maxScroll)
        assertEquals(181, home.rowY(0, 0))
        assertEquals(241, home.rowY(1, 0)) // the focus band of Maximise Panel, 241 to 297
        assertEquals(239, home.lineY(1, 0)) // its separator's line
        assertEquals(418, home.rowY(4, 0)) // Options, right under Manage Panels
        assertNull(home.lineY(4, 0))
    }

    @Test fun `a long menu stops under the app's bar and scrolls to its end`() {
        assertEquals(313, messages.right)
        assertEquals(70, messages.top)
        assertEquals(601, messages.total)
        assertEquals(191, messages.maxScroll)
        assertEquals(147, messages.rowY(1, 0)) // Compose Email focused, sim-44
        assertEquals(191, messages.scrollFor(9, 0)) // Options focused, sim-45
        assertEquals(73, messages.rowY(3, 191)) // Search at the viewport's top
        assertEquals(418, messages.rowY(9, 191))
        assertEquals(359, messages.lineY(8, 191))
    }

    @Test fun `the scroll follows the focus and goes back to the top on the first row`() {
        assertEquals(0, messages.scrollFor(5, 0))
        assertEquals(12, messages.scrollFor(6, 0)) // View Folder's bottom on the screen's
        assertEquals(12, messages.scrollFor(5, 12))
        assertEquals(191, messages.scrollFor(3, 191)) // Search stays at the viewport's top
        assertEquals(131, messages.scrollFor(2, 191)) // Compose Other comes back 3 px under the border
        assertEquals(0, messages.scrollFor(0, 100))
    }

    @Test fun `the square shows ten rows at most`() {
        val long = FullMenuLayout(FloatArray(15) { 100f }, BooleanArray(15), height = 720, minTop = 3)
        assertEquals(720 - (17 + 10 * 57 + 5), long.top)
    }

    @Test fun `a touch finds its row, not a separator nor what is off the viewport`() {
        assertEquals(0, home.rowAt(181f, 0))
        assertEquals(0, home.rowAt(237f, 0))
        assertNull(home.rowAt(239f, 0))
        assertEquals(1, home.rowAt(241f, 0))
        assertNull(home.rowAt(170f, 0))
        assertNull(messages.rowAt(72f, 191))
        assertEquals(3, messages.rowAt(73f, 191))
    }

    // sim-17: five actions and Full Menu on two rows; sim-52: eight and Full Menu on three.
    @Test fun `the short menu is centred, three columns, a row per three cells`() {
        val home = ShortMenuLayout(cells = 6, width = 640, height = 480)
        assertEquals(2, home.rows)
        assertEquals(151, home.left)
        assertEquals(117, home.top)
        assertEquals(337, home.boxWidth)
        assertEquals(242, home.boxHeight)
        assertEquals(264, home.cellLeft(4))
        assertEquals(238, home.cellTop(4))
        val sms = ShortMenuLayout(cells = 9, width = 640, height = 480)
        assertEquals(57, sms.top)
        assertEquals(361, sms.boxHeight)
        assertEquals(266, sms.cellLeft(4) + 2) // Send's focus, x 266 to 372
    }

    @Test fun `a touch on the short menu finds its cell`() {
        val home = ShortMenuLayout(cells = 5, width = 640, height = 480)
        assertEquals(0, home.cellAt(160f, 125f))
        assertEquals(4, home.cellAt(300f, 300f))
        assertNull(home.cellAt(440f, 300f)) // the sixth cell is empty
        assertNull(home.cellAt(100f, 300f))
    }

    @Test fun `the trackpad moves on the short menu's grid and stops at its edges`() {
        assertEquals(1, shortMove(0, 9, Dir.RIGHT))
        assertEquals(2, shortMove(2, 9, Dir.RIGHT))
        assertEquals(3, shortMove(0, 9, Dir.DOWN))
        assertEquals(0, shortMove(0, 9, Dir.UP))
        assertEquals(0, shortMove(0, 9, Dir.LEFT))
        assertEquals(2, shortMove(2, 5, Dir.DOWN)) // nothing under it
        assertEquals(4, shortMove(1, 5, Dir.DOWN))
    }

    // sim-223: the Calendar's menu (right border at 291) opens View's submenu, Week | Month | Agenda,
    // at its right: panel x 295 to 415, down to y 475 of 480 (sim-251: 270 after a border at 266).
    private val menuRight = 291

    @Test fun `a submenu opens at the right of its menu, on the bottom`() {
        val s = SubmenuLayout(floatArrayOf(98f, 98f, 102f), menuRight, 640, 480, 0)
        assertEquals(295, s.left)
        assertEquals(295 + 102 + MenuDimens.SUB_TEXT_X + MenuDimens.SUB_PAD_RIGHT, s.right)
        assertEquals(475, s.bottom)
        assertEquals(3, s.visible)
        assertEquals(475 - 3 * MenuDimens.ROW - 2 * MenuDimens.SUB_PAD, s.top)
        assertEquals(s.top + MenuDimens.SUB_PAD, s.rowY(0, 0))
    }

    @Test fun `a submenu stays on the screen and keeps its rows in sight`() {
        val wide = SubmenuLayout(floatArrayOf(900f), menuRight, 720, 720, 0)
        assertTrue(wide.right <= 720 - MenuDimens.SUB_EDGE && wide.left >= MenuDimens.SUB_EDGE)
        val tall = SubmenuLayout(FloatArray(14) { 80f }, 266, 720, 720, 100)
        assertEquals(MenuDimens.VISIBLE_ROWS, tall.visible)
        assertTrue(tall.top >= 100)
        assertEquals(0, tall.scrollFor(0, 0))
        val last = tall.scrollFor(13, 0)
        assertEquals(14 - tall.visible, last)
        assertEquals(last, tall.scrollFor(12, last)) // still in sight: unchanged
        assertEquals(12, tall.scrollFor(12, 0).let { it + tall.visible - 1 }) // the least move
        assertEquals(5, tall.rowAt(tall.rowY(5, 3).toFloat() + 3, 3))
        assertEquals(null, tall.rowAt(tall.top.toFloat(), 0)) // the top pad
    }
}

