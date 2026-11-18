package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogArmTest {
    @Test fun `a dialog without a delay chooses at once, even before its window has the focus`() {
        assertTrue(armed(focusedAt = -1, now = 0, armMs = 0))
        assertTrue(armed(focusedAt = 100, now = 100, armMs = 0))
    }

    // "Call …?": the second click of a quick double click on a missed call must not call.
    @Test fun `with a delay, nothing is chosen before the window has had the focus that long`() {
        assertFalse(armed(focusedAt = -1, now = 10_000, armMs = 500))
        assertFalse(armed(focusedAt = 1_000, now = 1_000, armMs = 500))
        assertFalse(armed(focusedAt = 1_000, now = 1_499, armMs = 500))
        assertTrue(armed(focusedAt = 1_000, now = 1_500, armMs = 500))
    }
}
