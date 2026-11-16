package dev.bold7.phone

import dev.bold7.ui.fit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Phone's hand-drawn texts end before their neighbour, at any screen width and any text. */
class RoomsTest {
    private val px = { s: String -> s.length * 14f } // a monospaced font: the layout is what is under test

    @Test fun aLogRowTextEndsBeforeTheDate() {
        for (width in listOf(480, 640, 720)) for (whenWidth in listOf(60f, 110f)) {
            val room = Rooms.logText(width, whenWidth)
            val cut = fit("A contact whose name is far too long for one row of the call log", room, px)
            assertTrue(LogRowView.TEXT_X + px(cut) + LogRowView.GAP <= width - LogRowView.RIGHT - whenWidth, "$width $whenWidth: $cut")
        }
    }

    @Test fun theCallerKeepsItsFixedWidthOnTheQ25AndNeverMoreThanTheScreen() {
        assertEquals(CallView.CALLER_WIDTH.toFloat(), Rooms.caller(720))
        assertEquals(CallView.CALLER_WIDTH.toFloat(), Rooms.caller(640))
        assertTrue(Rooms.caller(400) <= 400 - 2 * Rooms.CALL_MARGIN)
    }

    @Test fun everyCallTextFitsItsLine() {
        val long = "1 (519) 888-7465 1 (519) 888-7465 1 (519) 888-7465 1 (519) 888-7465"
        for (room in listOf(Rooms.line2(720), Rooms.myNumber(720), Rooms.tones(720), Rooms.barLabel(720, 4), Rooms.barLabel(720, 5))) {
            assertTrue(px(fit(long, room, px)) <= room)
        }
        assertEquals(172f, Rooms.barLabel(720, 4))
    }
}
