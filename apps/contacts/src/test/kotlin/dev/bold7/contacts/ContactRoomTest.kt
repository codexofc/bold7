package dev.bold7.contacts

import dev.bold7.ui.fit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactRoomTest {
    /** A text 10 px a letter: what the room leaves is what fit keeps. */
    private val measure = { s: String -> s.length * 10f }

    @Test fun aNameNeverReachesTheEditButton() {
        for (w in listOf(300, 480, 720, 1080)) for (picture in listOf(true, false)) {
            val x = if (picture) CardView.NAME_X else CardView.NAME_X_BARE
            val shown = fit("A very long name indeed, for a contact of eighty characters or so, really", ContactRoom.cardName(w, picture), measure)
            assertTrue(x + measure(shown) <= w - CardView.BUTTON_RIGHT - CardView.BUTTON_WIDTH, "w=$w picture=$picture")
        }
    }

    @Test fun aValueNeverRunsOverItsLabel() {
        val label = 120f
        for (w in listOf(300, 480, 720)) {
            val shown = fit("a very long address@example.com of no end", ContactRoom.cardValue(w, label), measure)
            assertTrue(w - CardView.VALUE_RIGHT - measure(shown) >= CardView.LABEL_X + label, "w=$w")
        }
    }

    @Test fun aShortTextKeepsItsDrawing() {
        assertEquals("Dexter", fit("Dexter", ContactRoom.listRow(720), measure))
        assertEquals("Dexter", fit("Dexter", ContactRoom.cardName(720, true), measure))
    }

    @Test fun aListNameStopsBeforeTheMargin() {
        val shown = fit("x".repeat(100), ContactRoom.listRow(720), measure)
        assertTrue(dev.bold7.design.ListDimens.ICON_TEXT_X + measure(shown) <= 720 - dev.bold7.design.ListDimens.TEXT_X)
        assertTrue(shown.endsWith("…"))
    }

    @Test fun narrowRoomsAreNeverNegative() {
        assertEquals(0f, ContactRoom.cardName(50, true))
        assertEquals(0f, ContactRoom.cardValue(50, 100f))
    }
}
