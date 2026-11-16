package dev.bold7.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShowcaseMaskTest {
    @Test fun theMaskAnswersAndTheRealOperatorAndAlarmAreNotRead() {
        val mask = ShowcaseMask("Carrier", alarm = true)
        assertEquals("Carrier", networkName(mask) { error("real operator read") })
        assertTrue(alarmSet(mask) { error("real alarm read") })
    }

    @Test fun withoutAMaskTheRealOnesAnswer() {
        assertEquals("Real", networkName(null) { "Real" })
        assertFalse(alarmSet(null) { false })
    }
}
