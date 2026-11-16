package dev.bold7.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The missed calls' dialog of OS 7 (sim-19): when it comes, and the count it says. */
class MissedTest {
    private fun Missed.removed(missed: Boolean, left: Int = 0) = reduce(MissedEvent.Removed(missed, left))

    @Test fun aMissedCallAsksAtOnceWhenNoCallIsLeft() {
        val m = Missed().removed(missed = true)
        assertEquals(1, m.count)
        assertTrue(m.show)
    }

    @Test fun anAnsweredOrIgnoredCallAsksNothing() {
        val m = Missed().removed(missed = false)
        assertEquals(0, m.count)
        assertFalse(m.show)
    }

    @Test fun theCountGrowsUntilTheDialogCloses() {
        // sim-19: "You have 2 new missed calls." after two calls nobody answered.
        val two = Missed().removed(missed = true).removed(missed = true)
        assertEquals(2, two.count)
        assertTrue(two.show)
        val closed = two.reduce(MissedEvent.Closed)
        assertEquals(Missed(), closed)
        assertEquals(1, closed.removed(missed = true).count)
    }

    @Test fun aCallMissedDuringAnotherWaitsForItsEnd() {
        // A second call rang and stopped during a call: nothing over the call screen.
        val during = Missed().removed(missed = true, left = 1)
        assertEquals(1, during.count)
        assertFalse(during.show)
        // The call ends: the dialog comes then.
        val after = during.removed(missed = false, left = 0)
        assertTrue(after.show)
        assertEquals(1, after.count)
        // Once asked, a later call ending without a miss does not ask again.
        assertFalse(after.removed(missed = false).show)
    }
}
