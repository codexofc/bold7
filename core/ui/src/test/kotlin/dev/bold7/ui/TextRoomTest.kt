package dev.bold7.ui

import dev.bold7.design.DialogDimens
import dev.bold7.design.ListDimens
import dev.bold7.design.MenuDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What each component of core/ui leaves its texts, against its neighbours, with a fixed measure
// (14 px a unit): a text of any length ends before the icon, value, arrow or edge beside it.
class TextRoomTest {
    private val measure = { s: String -> s.length * 14f }
    private val long = "An extremely long label that no row of the square can hold on one line"
    private val widths = listOf(0, 40, 150, 300, 720)

    private fun end(text: String, room: Float) = measure(fit(text, room, measure))

    @Test fun `a list row's texts stop before the right margin, after the icon`() {
        for (w in widths) for (icon in listOf(false, true)) {
            val x = if (icon) ListDimens.ICON_TEXT_X else ListDimens.TEXT_X
            assertTrue(x + end(long, TextRoom.listRow(w, icon)) <= maxOf(w - ListDimens.TEXT_X, x), "w $w icon $icon")
        }
    }

    @Test fun `a one-line row's title stops before its margin`() {
        for (w in widths) for (x in listOf(4, 40)) assertTrue(x + end(long, TextRoom.lineRow(w, x)) <= maxOf(w - 4, x))
    }

    @Test fun `a centred header keeps its margins on both sides`() {
        for (w in widths) assertTrue(end(long, TextRoom.header(w)) <= maxOf(0, w - 2 * ListDimens.TEXT_X))
    }

    @Test fun `a screen title stays in the bar, an Options page's too`() {
        val x = TitleBar.TITLE_X
        for (w in widths) assertTrue(x + end(long, TextRoom.title(w)) <= maxOf(w - x, x))
    }

    @Test fun `a check box's label stops before the box`() {
        for (w in widths) {
            val box = w - ListDimens.CHECK_RIGHT - ListDimens.CHECK_BOX
            assertTrue(ListDimens.TEXT_X + end(long, TextRoom.checkLabel(w)) <= maxOf(box, ListDimens.TEXT_X), "w $w")
        }
    }

    @Test fun `a label after its box starts at 44 as in sim-228 and stops before the right margin`() {
        assertEquals(44, TextRoom.CHECK_LABEL_AFTER_BOX)
        for (w in widths) assertTrue(TextRoom.CHECK_LABEL_AFTER_BOX + end(long, TextRoom.checkLabelAfterBox(w)) <= maxOf(w - ListDimens.TEXT_X, TextRoom.CHECK_LABEL_AFTER_BOX), "w $w")
    }

    @Test fun `a stacked choice row holds its label line and a whole field row (sim-170)`() {
        assertEquals(89, ChoiceView.STACKED_HEIGHT)
        // The field of Home Time Zone sits at 172 to 223 on a label line starting at 135: 37 to 88 in the row.
        assertEquals(172 - 135, ChoiceView.STACK_LABEL + ListDimens.CHOICE_FIELD_TOP)
        assertTrue(ChoiceView.STACK_LABEL + ListDimens.CHOICE_FIELD_TOP + ListDimens.CHOICE_FIELD_HEIGHT <= ChoiceView.STACKED_HEIGHT)
    }

    @Test fun `a choice row's label stops before its field and its value before the arrow`() {
        for (field in listOf(0, 100, 534)) assertTrue(ListDimens.TEXT_X + end(long, TextRoom.choiceLabel(field)) <= maxOf(field, ListDimens.TEXT_X))
        assertTrue(end(long, TextRoom.inside(166)) <= 166 - 2 * TextRoom.VALUE_PAD)
        assertEquals(0f, TextRoom.inside(5))
    }

    @Test fun `a date row's label stops before its values`() {
        for (w in widths) {
            val values = 260f
            val dateX = w - 8 - values
            assertTrue(ListDimens.TEXT_X + end(long, TextRoom.dateLabel(w, values)) <= maxOf(dateX, ListDimens.TEXT_X.toFloat()), "w $w")
        }
    }

    @Test fun `a menu label stops before the margin or the submenu's arrow`() {
        for (right in listOf(0, 120, 300, 680)) for (sub in listOf(false, true)) {
            // The arrow keeps the menu's own gap from the label (on the device, "Send Contact Card" touched it).
            val reserve = if (sub) MenuDimens.SUBMENU_ARROW_WIDTH + MenuDimens.PAD_RIGHT else MenuDimens.PAD_RIGHT
            assertTrue(MenuDimens.TEXT_X + end(long, TextRoom.menuLabel(right, sub)) <= maxOf(right - reserve, MenuDimens.TEXT_X), "right $right sub $sub")
        }
    }

    @Test fun `a menu never goes past the strip left of the page's right edge`() {
        val menu = FullMenuLayout(floatArrayOf(5000f, 100f), booleanArrayOf(false, false), 720, 0, TextRoom.menuRight(720))
        assertEquals(720 - TextRoom.MENU_STRIP, menu.right)
        assertEquals(266, FullMenuLayout(floatArrayOf(252f), booleanArrayOf(false), 480, 0, TextRoom.menuRight(720)).right)
    }

    @Test fun `a button's label stays inside its button`() {
        for (b in listOf(0, 60, 200, 400)) assertTrue(end(long, TextRoom.button(b)) <= maxOf(0, b - 2 * DialogDimens.BUTTON_PAD))
    }

    @Test fun `a text that fits is drawn whole`() {
        assertEquals("Switch Application", fit("Switch Application", TextRoom.menuLabel(266, false), { it.length * 14f }))
        assertEquals("Options", fit("Options", TextRoom.listRow(720, true), measure))
    }

    @Test fun `a choice field grows to hold its value, never under the label nor smaller than before`() {
        // Analogue (sim-170) fits the usual field; "Paris (GMT+1)" and "Evolving Dream" outgrow it.
        assertEquals(ListDimens.CHOICE_FIELD_WIDTH, TextRoom.choiceField(720, 120f, 127f))
        for (value in listOf(195f, 260f, 330f)) {
            val field = TextRoom.choiceField(720, 120f, value)
            assertTrue(field - TextRoom.CHOICE_VALUE_EXTRA >= value, "value $value in $field")
            assertTrue(ListDimens.TEXT_X + 120 + ListDimens.TEXT_X <= 720 - ListDimens.CHECK_RIGHT - field)
        }
        // A label that leaves no room: the usual field, the value cut by fit.
        assertEquals(ListDimens.CHOICE_FIELD_WIDTH, TextRoom.choiceField(720, 600f, 400f))
        assertEquals(720 - ListDimens.CHECK_RIGHT - 2 * ListDimens.TEXT_X - 300, TextRoom.choiceField(720, 300f, 900f))
    }

    @Test fun `a submenu label stops before its panel's right pad`() {
        for (w in listOf(0, 60, 120, 400)) assertTrue(MenuDimens.SUB_TEXT_X + end(long, TextRoom.submenuLabel(w)) <= maxOf(w - MenuDimens.SUB_PAD_RIGHT, MenuDimens.SUB_TEXT_X), "w $w")
    }

    // sim-270, sim-271: an open list as wide as its longest value and 8 px each side ("Not Started"
    // fits whole), never narrower than OS 7's 78 px (sim-66), and only as wide as the screen leaves it.
    @Test fun anOpenListTakesItsLongestValue() {
        assertEquals(ListDimens.CHOICE_LIST_WIDTH, TextRoom.choiceList(ListDimens.CHOICE_LIST_WIDTH, 48f, 600))
        assertEquals(157 + 2 * TextRoom.LIST_PAD, TextRoom.choiceList(ListDimens.CHOICE_LIST_WIDTH, 156.3f, 600))
        assertEquals(154, TextRoom.choiceList(154, 80f, 600))
        assertEquals(300, TextRoom.choiceList(ListDimens.CHOICE_LIST_WIDTH, 500f, 300))
        // sim-417: a list too wide for the room right of its field moves left, its right edge on the screen's.
        assertEquals(500, TextRoom.choiceListLeft(500, 120, 720))
        assertEquals(480, TextRoom.choiceListLeft(500, 240, 720))
        assertEquals(0, TextRoom.choiceListLeft(500, 900, 720))
        assertTrue(TextRoom.inside(TextRoom.choiceList(ListDimens.CHOICE_LIST_WIDTH, 156.3f, 600)) >= 156.3f)
    }

    // sim-170, sim-270: in a group box (white from x 5) the label is at 11 and the field 12 px from the screen's right edge.
    @Test fun aRowCountsItsMarginsFromTheScreenThroughItsBox() {
        assertEquals(0, TextRoom.screenGap(0))
        assertEquals(5, TextRoom.screenGap(5))
        assertEquals(ListDimens.BOX_INSET, TextRoom.screenGap(40))
        assertEquals(0, TextRoom.screenGap(-3))
    }
}
