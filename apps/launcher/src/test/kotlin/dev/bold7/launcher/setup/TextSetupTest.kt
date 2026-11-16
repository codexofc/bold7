package dev.bold7.launcher.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextSetupTest {
    @Test
    fun `the first unlock shows the screen once, and only when Google Messages is there and consent is not given`() {
        assertEquals(Launch.SHOW, launch(asked = false, messagesInstalled = true, consent = Consent.OFF))
        assertEquals(Launch.SHOW, launch(asked = false, messagesInstalled = true, consent = Consent.UNKNOWN))
        assertEquals(Launch.SKIP, launch(asked = true, messagesInstalled = true, consent = Consent.OFF))
        assertEquals(Launch.SKIP, launch(asked = false, messagesInstalled = false, consent = Consent.OFF))
    }

    @Test
    fun `a consent already given closes the screen for good without showing it`() {
        assertEquals(Launch.FINISH, launch(asked = false, messagesInstalled = true, consent = Consent.ON))
        assertEquals(Launch.SKIP, launch(asked = true, messagesInstalled = true, consent = Consent.ON))
    }

    @Test
    fun `back from Google's page, the state read decides the next step`() {
        assertNull(afterReturn(Consent.ON))
        assertEquals(Step.STILL_OFF, afterReturn(Consent.OFF))
        assertEquals(Step.UNSURE, afterReturn(Consent.UNKNOWN))
    }

    @Test
    fun `every step offers a way out and a way to try again`() {
        for (s in Step.entries) {
            assertTrue(Press.CLOSE in s.buttons.indices.map { press(s, it) }, "$s has no way out")
        }
        for (s in listOf(Step.INTRO, Step.STILL_OFF, Step.UNSURE)) {
            assertTrue(Press.OPEN in s.buttons.indices.map { press(s, it) }, "$s cannot open the page")
        }
    }

    @Test
    fun `the first button of the first step opens Google's page and Later closes`() {
        assertEquals(Press.OPEN, press(Step.INTRO, 0))
        assertEquals(Press.CLOSE, press(Step.INTRO, 1))
        assertEquals(Press.CLOSE, press(Step.UNSURE, 0))
    }

    @Test
    fun `the texts are short`() {
        for (s in Step.entries) assertTrue(s.text.length <= 170, "$s: ${s.text.length}")
    }
}
