package dev.bold7.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyReleaseGateTest {
    private val enter = 66

    private fun KeyReleaseGate.run(vararg events: Pair<Boolean, Int>) = events.map { (down, repeat) -> swallows(enter, down, repeat) }

    @Test fun aPressAndItsReleasePass() {
        assertEquals(listOf(false, false), KeyReleaseGate().run(true to 0, false to 0))
    }

    @Test fun aReleaseWhosePressAnotherWindowTookIsStopped() {
        // The dialog chose on the press and closed: its release would click the page again.
        assertEquals(listOf(true), KeyReleaseGate().run(false to 0))
    }

    @Test fun onlyOnceThenTheNextClickPasses() {
        assertEquals(listOf(true, false, false), KeyReleaseGate().run(false to 0, true to 0, false to 0))
    }

    @Test fun aHeldKeyRepeatsAndItsReleasePasses() {
        assertEquals(listOf(false, false, false, false), KeyReleaseGate().run(true to 0, true to 1, true to 2, false to 0))
    }

    @Test fun aKeyHeldWhenTheWindowCameOnlyRepeatsHere() {
        // Its press reached the window before: the repeats pass, its release is not a click here.
        assertEquals(listOf(false, false, true), KeyReleaseGate().run(true to 3, true to 4, false to 0))
    }

    @Test fun keysAreTrackedOneByOne() {
        val gate = KeyReleaseGate()
        assertEquals(false, gate.swallows(59, down = true, repeat = 0))
        assertEquals(true, gate.swallows(enter, down = false, repeat = 0))
        assertEquals(false, gate.swallows(59, down = false, repeat = 0))
    }
}
