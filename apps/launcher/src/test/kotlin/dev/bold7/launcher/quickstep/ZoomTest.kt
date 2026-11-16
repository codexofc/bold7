package dev.bold7.launcher.quickstep

import dev.bold7.design.Curves
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomTest {
    private val ms = 1_000_000L
    private val eps = 1e-5f

    @Test fun `entering, the window grows from its scale to its full size while it fades in`() {
        val z = Zoom(0.75f, entering = true, durationMs = 250, curve = Curves.Linear)
        assertEquals(0.75f, z.scale(0f), eps)
        assertEquals(0f, z.alpha(0f), eps)
        assertEquals(0.875f, z.scale(0.5f), eps)
        assertEquals(0.5f, z.alpha(0.5f), eps)
        assertEquals(1f, z.scale(1f), eps)
        assertEquals(1f, z.alpha(1f), eps)
    }

    @Test fun `leaving, the window goes from its full size to its scale while it fades out`() {
        val z = Zoom(1.1f, entering = false, durationMs = 250, curve = Curves.Linear)
        assertEquals(1f, z.scale(0f), eps)
        assertEquals(1f, z.alpha(0f), eps)
        assertEquals(1.1f, z.scale(1f), eps)
        assertEquals(0f, z.alpha(1f), eps)
    }

    @Test fun `the window stays centred on itself`() {
        val z = Transitions.OPEN
        assertEquals(90f, z.offset(720f, 0.75f), eps) // 720 x 0.75 = 540, 90 on each side
        assertEquals(0f, z.offset(720f, 1f), eps)
        assertEquals(-36f, z.offset(720f, 1.1f), eps) // larger than the screen: past its edges
    }

    @Test fun `progress follows the curve over the duration, then stays at 1`() {
        val z = Zoom(0.75f, entering = true, durationMs = 250, curve = Curves.QuadraticOut)
        assertEquals(0f, z.progress(0))
        assertEquals(Curves.QuadraticOut.transform(100f / 250f), z.progress(100 * ms), 1e-6f)
        assertEquals(1f, z.progress(250 * ms))
        assertEquals(1f, z.progress(900 * ms))
        assertFalse(z.isOver(249 * ms))
        assertTrue(z.isOver(250 * ms))
    }

    @Test fun `leaving and coming back to the home zoom at 75 percent, in and out (theme, homescreen)`() {
        assertTrue(Transitions.OPEN.entering)
        assertFalse(Transitions.CLOSE.entering)
        assertEquals(0.75f, Transitions.OPEN.scale)
        assertEquals(0.75f, Transitions.CLOSE.scale)
    }

    @Test fun `the switcher leaves in a 150 ms fade without any change of scale and Manage Connections goes up in 190 ms at constant speed`() {
        val f = Transitions.SWITCHER_CLOSE
        assertEquals(150, f.durationMs)
        assertEquals(1f, f.alpha(0), eps)
        assertEquals(0f, f.alpha(150), eps)
        // Slows at the end: the page below was at 0,3 ; 0,7 ; 0,9 after 42, 84, 126 ms (measured).
        assertEquals(0.3f, 1f - f.alpha(42), 0.2f)
        assertEquals(0.9f, 1f - f.alpha(126), 0.1f)
        assertEquals(190L, Transitions.CONNECTIONS_CLOSE_MS)
        assertEquals(0.5f, Transitions.CONNECTIONS_CLOSE_CURVE.transform(0.5f), eps)
    }
}
