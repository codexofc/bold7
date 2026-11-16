package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MenuFadesTest {
    @Test fun `the short menu appears at 65 percent in 90 ms`() {
        assertEquals(0.65f, MenuFades.OPEN.alpha(0), 1e-6f)
        assertEquals(1f, MenuFades.OPEN.alpha(90), 1e-6f)
        assertTrue(MenuFades.OPEN.alpha(45) > 0.9f) // fast: 0,98 on the second frame
    }

    @Test fun `it leaves in 190 ms, slowly first, a cubic start`() {
        assertEquals(190, MenuFades.CLOSE.durationMs)
        assertEquals(0.17f, 1f - MenuFades.CLOSE.alpha(100), 0.1f)
        assertEquals(0f, MenuFades.CLOSE.alpha(190), 1e-6f)
        assertTrue(MenuFades.CLOSE.alpha(40) > 0.9f)
    }
}
