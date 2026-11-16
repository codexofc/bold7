package dev.bold7.flashlight

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LampTest {
    @Test fun clickTurnsItOnThenOff() {
        val on = Lamp().toggle()
        assertEquals(true, on.command)
        assertEquals(true, on.next.pending)
        // The camera's report is what says it is on, not the request.
        val reported = on.next.reported(true)
        assertEquals(true, reported.on)
        assertEquals(false, reported.toggle().command)
    }

    @Test fun noFlashDoesNothingAndSaysSo() {
        val l = Lamp(flash = false)
        assertNull(l.toggle().command)
        assertEquals(Lamp.Status.NO_FLASH, l.status)
    }

    @Test fun anotherAppHoldingTheCameraBlocksItAndShowsIt() {
        val l = Lamp(on = true).unavailable()
        assertEquals(Lamp.Status.IN_USE, l.status)
        assertEquals(false, l.on)
        assertNull(l.toggle().command)
        // It is back when the camera reports its torch again.
        assertEquals(Lamp.Status.OFF, l.reported(false).status)
    }

    @Test fun aReportOfAnotherAppTurningItOnIsShown() {
        assertEquals(Lamp.Status.ON, Lamp().reported(true).status)
    }

    @Test fun keysOfTheTrackpadAndTheKeyboard() {
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE)) {
            assertEquals(LampKey.TOGGLE, lampKey(k, 0))
            assertNull(lampKey(k, 1)) // held: no flicker
        }
        assertEquals(LampKey.CLOSE, lampKey(KeyEvent.KEYCODE_ESCAPE, 0))
        assertEquals(LampKey.CLOSE, lampKey(KeyEvent.KEYCODE_BACK, 0))
        assertEquals(LampKey.MENU, lampKey(KeyEvent.KEYCODE_MENU, 0))
        assertNull(lampKey(KeyEvent.KEYCODE_A, 0))
    }
}
