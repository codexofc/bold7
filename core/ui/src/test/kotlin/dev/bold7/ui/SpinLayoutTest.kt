package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpinLayoutTest {
    // Set Alarm on sim-156: box 127 to 513 (387 wide), the strip 164 to 476, the list's column 302 to 460.
    private val cols = SpinLayout.columns(387, listOf(69, 69, 158), listOf(0, 0, 0))

    @Test
    fun `the strip and its tail are centred in the box`() {
        val left = cols.first().first
        assertTrue(kotlin.math.abs(left - (387 - SpinLayout.stripEnd(cols))) <= 1, "left $left")
    }

    @Test
    fun `the focused last column's pill reaches the strip's end, another's stops at its own`() {
        assertEquals(cols.last().second + 16, SpinLayout.pillEnd(cols, 2))
        assertEquals(cols[1].second, SpinLayout.pillEnd(cols, 1))
    }

    @Test
    fun `the focused cell is the column's own width, its border shown only at the strip's ends`() {
        // border_spinboxbutton~focus.png: 3 px of margin, then 2 of border. A middle column (sim-159): neither border.
        assertEquals(cols[1].first - 5 to cols[1].second + 5, SpinLayout.cell(cols, 1))
        // The last (sim-157): its right border at the strip's end, like the strip's own image (3 px of margin).
        assertEquals(cols[2].first - 5 to SpinLayout.stripEnd(cols) + 3, SpinLayout.cell(cols, 2))
        // The first (sim-234): its left border at the strip's start.
        assertEquals(cols[0].first - 3 to cols[0].second + 5, SpinLayout.cell(cols, 0))
    }

    @Test
    fun `a column abuts the one before it and never overlaps it`() {
        val spaced = SpinLayout.columns(387, listOf(60, 20, 60), listOf(0, 10, 0))
        for (i in 1 until spaced.size) assertTrue(spaced[i].first >= spaced[i - 1].second)
        assertEquals(10, spaced[1].first - spaced[0].second)
    }

    @Test
    fun `the column's colours run down 170 px, bright at its focused row and dark at both ends`() {
        assertEquals(SpinLayout.ABOVE + 49 + SpinLayout.BELOW, SpinLayout.STOPS.last().first)
        assertEquals(SpinLayout.STOPS.first().second, SpinLayout.STOPS.last().second)
        assertTrue(SpinLayout.STOPS.zipWithNext().all { (a, b) -> a.first < b.first })
    }
}
