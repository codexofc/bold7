package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackpadTest {
    // Trackpad as a relative pointer: its travel is cut into steps, one axis at a time.
    @Test fun `the travel of the trackpad makes one step per threshold`() {
        val (left, steps) = Travel().moved(2.5f * TRACKPAD_STEP, 0f)
        assertEquals(listOf(Dir.RIGHT, Dir.RIGHT), steps)
        assertEquals(0.5f * TRACKPAD_STEP, left.x)
        assertEquals(listOf(Dir.UP), left.moved(0f, -TRACKPAD_STEP).second)
    }

    @Test fun `a step on one axis forgets the other, and turning back starts again`() {
        val (after, steps) = Travel(x = 0.9f * TRACKPAD_STEP).moved(0f, TRACKPAD_STEP)
        assertEquals(listOf(Dir.DOWN), steps)
        assertEquals(0f, after.x)
        assertEquals(emptyList(), Travel(x = 0.9f * TRACKPAD_STEP).moved(-0.5f * TRACKPAD_STEP, 0f).second)
        assertEquals(-0.5f * TRACKPAD_STEP, Travel(x = 0.9f * TRACKPAD_STEP).moved(-0.5f * TRACKPAD_STEP, 0f).first.x)
    }

    @Test fun `a step across forgets the travel along the other axis`() {
        val (after, steps) = Travel(y = 0.9f * TRACKPAD_STEP).moved(TRACKPAD_STEP, 0f)
        assertEquals(listOf(Dir.RIGHT), steps)
        assertEquals(0f, after.y)
    }
}
