package dev.bold7.launcher.quickstep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpringTest {
    private val eps = 0.001f

    @Test fun `the spring starts where and as fast as the finger left`() {
        val s = spring(from = 200f, velocity = 1500f, to = 0f, seconds = 0f)
        assertEquals(200f, s.position, eps)
        assertEquals(1500f, s.velocity, 0.01f)
        assertFalse(s.settled)
    }

    @Test fun `the spring settles on its target`() {
        assertFalse(spring(from = 200f, velocity = 1500f, to = 0f, seconds = 0.02f).settled)
        val s = spring(from = 200f, velocity = 1500f, to = 0f, seconds = 1f)
        assertEquals(0f, s.position, 0.5f)
        assertTrue(s.settled)
    }

    @Test fun `a softer spring takes longer to settle`() {
        val stiff = spring(from = 200f, velocity = 0f, to = 0f, seconds = 0.1f)
        val soft = spring(from = 200f, velocity = 0f, to = 0f, seconds = 0.1f, stiffness = SPRING_STIFFNESS / 3)
        assertTrue(soft.position > stiff.position)
        assertTrue(spring(from = 200f, velocity = 0f, to = 0f, seconds = 1f, stiffness = SPRING_STIFFNESS / 3).settled)
    }

    @Test fun `a gentle release toward the target does not overshoot`() {
        var t = 0f
        while (t < 1f) {
            assertTrue(spring(from = 300f, velocity = 2000f, to = 360f, seconds = t).position <= 360f + eps)
            t += 0.004f
        }
    }
}
