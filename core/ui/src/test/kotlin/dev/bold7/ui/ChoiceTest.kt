package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ChoiceTest {
    // Trackpad Sensitivity's nine values (100 to 20), rows of 57 px, the Q25's 720 px screen.
    private val sensitivity = ChoiceList(count = 9, chosen = 3)

    @Test
    fun `the chosen value sits on the field's row`() {
        assertEquals(300 - 3 * 57, sensitivity.top(rowTop = 300, row = 57, screen = 720))
    }

    @Test
    fun `kept on the screen at the top and at the bottom`() {
        assertEquals(0, sensitivity.top(rowTop = 100, row = 57, screen = 720))
        assertEquals(720 - 9 * 57, sensitivity.copy(chosen = 8).top(rowTop = 700, row = 57, screen = 720))
    }

    @Test
    fun `the title bar stays clear`() {
        // Launch by Typing's list, Application Shortcuts chosen, its field just under the 35 px title bar.
        val launch = ChoiceList(count = 2, chosen = 1)
        assertEquals(35, launch.top(rowTop = 70, row = 57, screen = 720, floor = 35))
        assertEquals(70, launch.copy(chosen = 0).top(rowTop = 70, row = 57, screen = 720, floor = 35))
        // Taller than the room under the bar, it keeps the chosen row in place as far as its ends allow.
        assertEquals(300, sensitivity.top(rowTop = 600, row = 57, screen = 720, floor = 300)) // no gap above it
        assertEquals(720 - 9 * 57, sensitivity.copy(chosen = 8).top(rowTop = 650, row = 57, screen = 720, floor = 300)) // nor under it
    }

    @Test
    fun `taller than the screen, the ends allow what they can`() {
        // 9 x 57 = 513 on the 9900's 480: the list starts above the screen (the simulator's capture).
        assertEquals(150 - 3 * 57, sensitivity.top(rowTop = 150, row = 57, screen = 480))
        assertEquals(480 - 513, sensitivity.top(rowTop = 84, row = 57, screen = 480)) // no gap under it
        assertEquals(0, sensitivity.copy(chosen = 0).top(rowTop = 84, row = 57, screen = 480))
        assertEquals(480 - 513, sensitivity.copy(chosen = 8).top(rowTop = 20, row = 57, screen = 480))
    }

    @Test
    fun `the trackpad stops at the ends`() {
        assertEquals(4, sensitivity.step(down = true).chosen)
        assertEquals(0, ChoiceList(9, 0).step(down = false).chosen)
        assertEquals(8, ChoiceList(9, 8).step(down = true).chosen)
    }
}
