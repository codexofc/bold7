package dev.bold7.design

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversionsTest {

    // The 10.3 typography table of the BlackBerry UI Guidelines gives points and pixels on a
    // 356 dpi phone, the screen_dpi the firmware declares for the Z10 (deviceproperties/r069.public):
    // the conversion must reproduce it, within the table's own rounding.
    @Test
    fun pointsToPxReproducesTheOfficialTypographyTable() {
        val table = mapOf(4.8f to 24, 6f to 30, 7.11f to 35, 8f to 40, 9f to 45, 10f to 50, 12f to 59, 16f to 79)
        table.forEach { (pt, px) ->
            val actual = pointsToPx(pt, 356)
            assertEquals(true, kotlin.math.abs(actual - px) <= 1, "$pt pt gave $actual px, table says $px")
        }
    }

    @Test
    fun pointsToPxRoundsHalfUp() {
        assertEquals(28, pointsToPx(6f, 330)) // 27.5
        assertEquals(41, pointsToPx(9f, 330)) // 41.25
    }

    @Test
    fun dpToPxUsesTheQ25Factor() {
        assertEquals(31, Density.dpToPx(24f)) // 31.2: Android's 24 dp status bar, 31 px measured on the Q25
        assertEquals(13, Density.dpToPx(10f))
        assertEquals(2, Density.dpToPx(1.5f)) // 1.95
        assertEquals(0, Density.dpToPx(0f))
    }

    // Same rule as TypedValue.complexToDimensionPixelSize: a non-zero size never vanishes.
    @Test
    fun dpToPxKeepsHairlinesVisible() {
        assertEquals(1, Density.dpToPx(0.2f))
        assertEquals(-1, Density.dpToPx(-0.2f))
    }

    @Test
    fun everyPixelSizeOfTheScreenSurvivesARoundTripThroughDp() {
        for (px in 0..720) assertEquals(px, Density.dpToPx(Density.pxToDp(px)), "$px px")
    }

    // ARGB values read from firmware images confirm the rounding: a list header is white at
    // 90 % (0xE6) and the folder background black at 30 % (0x4D).
    @Test
    fun withOpacityRoundsTheAlphaLikeTheFirmwareImages() {
        assertEquals(0xE6FFFFFF.toInt(), withOpacity(0xFFFFFF, 0.9f))
        assertEquals(0x4D000000, withOpacity(0x000000, 0.3f))
        assertEquals(0x1A000000, withOpacity(0x000000, 0.1f)) // 25.5
        assertEquals(0xB3087099.toInt(), withOpacity(0x087099, 0.7f)) // trackpad focus of the Classic home, 178.5
        assertEquals(0x00FFFFFF, withOpacity(0xFFFFFF, 0f))
        assertEquals(0xFFFFFFFF.toInt(), withOpacity(0xFFFFFF, 1f))
    }

    @Test
    fun withOpacityIgnoresAnAlphaAlreadyInTheColor() {
        assertEquals(0x80ABD4E0.toInt(), withOpacity(0xFFABD4E0.toInt(), 0.5f))
    }
}
