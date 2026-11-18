package dev.bold7.launcher.home

import kotlin.test.Test
import kotlin.test.assertEquals

// The open folder's tab against the simulator: the slant follows the name (sim-16, sim-374, sim-482).
class FolderHeaderTest {
    // BBAlpha Sans at 27 px, each glyph's advance (hmtx of the firmware's TTF).
    private val instantMessaging = floatArrayOf(7.963f, 17.112f, 14.067f, 10.586f, 16.545f, 17.112f, 10.586f, 8.583f, 23.295f, 16.611f, 14.067f, 14.067f, 16.545f, 17.139f, 7.501f, 17.112f, 17.139f)
    private val media = floatArrayOf(23.295f, 16.611f, 17.139f, 7.501f, 16.545f)
    private val zz = floatArrayOf(18.497f, 14.172f)

    @Test fun theSlantStartsWhereTheNinetyNineHundredPutsIt() {
        assertEquals(303, folderSlantX(instantMessaging, 640)) // sim-16
        assertEquals(137, folderSlantX(media, 640)) // sim-374
        assertEquals(87, folderSlantX(zz, 640)) // sim-482
    }

    @Test fun theSlantStaysBetweenTheTwoEnds() {
        assertEquals(55, folderSlantX(FloatArray(0), 720)) // no name: right after the pen
        assertEquals(720 - TAB_END - TAB_SLANT, folderSlantX(FloatArray(60) { 20f }, 720))
    }

    @Test fun theFiveSlicesFillTheWidth() {
        val s = folderTabSlices(303, 640)
        assertEquals(listOf(0, 38, 303, 355, 602), s.map { it.first })
        assertEquals(listOf(38, 265, 52, 247, 38), s.map { it.second })
        assertEquals(720, folderTabSlices(303, 720).sumOf { it.second })
    }
}
