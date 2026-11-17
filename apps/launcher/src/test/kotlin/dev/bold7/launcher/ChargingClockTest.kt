package dev.bold7.launcher

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChargingClockTest {
    @Test fun theChargerOpensTheClockOnTheHomeScreenAndOverTheLockScreen() {
        // sim-412: the home, unlocked; sim-414: locked, the screen off.
        assertTrue(opensClock(locked = false, homeShown = true, screenOn = true, inCall = false))
        assertTrue(opensClock(locked = true, homeShown = false, screenOn = false, inCall = false))
        assertTrue(opensClock(locked = false, homeShown = false, screenOn = false, inCall = false))
    }

    @Test fun anotherAppInFrontOrACallKeepsTheScreenAsItIs() {
        assertFalse(opensClock(locked = false, homeShown = false, screenOn = true, inCall = false))
        assertFalse(opensClock(locked = true, homeShown = false, screenOn = false, inCall = true))
        assertFalse(opensClock(locked = false, homeShown = true, screenOn = true, inCall = true))
    }
}
