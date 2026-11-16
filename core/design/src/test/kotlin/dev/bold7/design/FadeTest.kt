package dev.bold7.design

import kotlin.test.Test
import kotlin.test.assertEquals

class FadeTest {
    @Test fun `a fade runs from its start to its end opacity along its curve, then stays`() {
        val f = Fade(0.65f, 1f, 90, Curves.Linear)
        assertEquals(0.65f, f.alpha(0), 1e-6f)
        assertEquals(0.825f, f.alpha(45), 1e-5f)
        assertEquals(1f, f.alpha(90), 1e-6f)
        assertEquals(1f, f.alpha(500), 1e-6f)
    }

    @Test fun `a fade out goes down`() {
        val f = Fade(1f, 0f, 150, Curves.QuadraticOut)
        assertEquals(1f, f.alpha(0), 1e-6f)
        assertEquals(1f - Curves.QuadraticOut.transform(0.5f), f.alpha(75), 1e-6f)
        assertEquals(0f, f.alpha(150), 1e-6f)
    }
}
