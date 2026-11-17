package dev.bold7.sysui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockFaceTest {
    // StatusBarState: SHADE 0, KEYGUARD 1, SHADE_LOCKED 2.
    @Test fun stackHiddenOnTheLockScreenItselfOnly() {
        assertTrue(stackHidden(state = 1, awake = true, faceReady = true))
        assertFalse(stackHidden(state = 2, awake = true, faceReady = true))
        assertFalse(stackHidden(state = 0, awake = true, faceReady = true))
        assertFalse(stackHidden(state = 1, awake = false, faceReady = true))
        assertFalse(stackHidden(state = 1, awake = true, faceReady = false))
    }

    @Test fun faceFollowsAndroidsLockScreenOnTheLockScreenOnly() {
        assertEquals(0.4f, faceAlpha(0.4f, state = 1, awake = true, bouncerShown = false))
        assertEquals(0f, faceAlpha(1f, state = 2, awake = true, bouncerShown = false))
        assertEquals(0f, faceAlpha(1f, state = 1, awake = false, bouncerShown = false))
    }

    // sim-85: the lock screen stays whole under "Enter Password:" (the wallpaper's pixels under
    // the box are sim-64's), while Android fades its own lock screen out under its bouncer.
    @Test fun faceStaysWholeUnderOs7sEntry() {
        assertEquals(1f, faceAlpha(0f, state = 1, awake = true, bouncerShown = true))
        assertEquals(1f, faceAlpha(0.3f, state = 1, awake = true, bouncerShown = true))
        assertEquals(0f, faceAlpha(1f, state = 2, awake = true, bouncerShown = true))
        assertEquals(0f, faceAlpha(1f, state = 1, awake = false, bouncerShown = true))
    }

    // ScrimState's names. sim-64: the wallpaper whole under the face, no 20 % black of Android's;
    // sim-85: whole under the entry too, where Android puts its black (BOUNCER).
    @Test fun scrimsClearUnderTheFaceAndOs7sEntryOnly() {
        assertTrue(scrimsCleared("KEYGUARD", faceReady = true, bouncerDressed = false, qsExpansion = 0f, fullShadeProgress = 0f))
        assertTrue(scrimsCleared("BOUNCER", faceReady = true, bouncerDressed = true, qsExpansion = 0f, fullShadeProgress = 0f))
        // The SIM's PIN and every bouncer OS 7 does not dress: Android's black.
        assertFalse(scrimsCleared("BOUNCER", faceReady = true, bouncerDressed = false, qsExpansion = 0f, fullShadeProgress = 0f))
        // Android's face (not attached yet, or failed): Android's scrims.
        assertFalse(scrimsCleared("KEYGUARD", faceReady = false, bouncerDressed = true, qsExpansion = 0f, fullShadeProgress = 0f))
        assertFalse(scrimsCleared("BOUNCER", faceReady = false, bouncerDressed = true, qsExpansion = 0f, fullShadeProgress = 0f))
        // A pull of the shade, quick settings or notifications, keeps Android's darkening.
        assertFalse(scrimsCleared("KEYGUARD", faceReady = true, bouncerDressed = false, qsExpansion = 0.1f, fullShadeProgress = 0f))
        assertFalse(scrimsCleared("KEYGUARD", faceReady = true, bouncerDressed = false, qsExpansion = 0f, fullShadeProgress = 0.1f))
        for (state in listOf("SHADE_LOCKED", "UNLOCKED", "OFF", "AOD", "PULSING", "AUTH_SCRIMMED", "BOUNCER_SCRIMMED", "DREAMING")) {
            assertFalse(scrimsCleared(state, faceReady = true, bouncerDressed = true, qsExpansion = 0f, fullShadeProgress = 0f), state)
        }
    }

    // A pulse turns the display fully on while SystemUI still dozes: awake is the doze state's.
    @Test fun awakeIsTheDozeStateNotTheDisplay() {
        assertTrue(awake(dozing = false, dozeAmount = 0f))
        assertFalse(awake(dozing = true, dozeAmount = 0f))
        assertFalse(awake(dozing = false, dozeAmount = 0.5f))
    }

    // MotionEvent: ACTION_DOWN 0, ACTION_UP 1, ACTION_MOVE 2, ACTION_CANCEL 3.
    @Test fun aHiddenStackKeepsNoTouchButCancelIsAndroids() {
        assertEquals(true, stackTouchAnswer(action = 0, hiddenAtDown = true, intercept = true))
        assertEquals(false, stackTouchAnswer(action = 2, hiddenAtDown = true, intercept = false))
        assertEquals(null, stackTouchAnswer(action = 3, hiddenAtDown = true, intercept = true))
        assertEquals(null, stackTouchAnswer(action = 0, hiddenAtDown = false, intercept = true))
    }
}
