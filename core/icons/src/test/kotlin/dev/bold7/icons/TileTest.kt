package dev.bold7.icons

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileTest {
    // The chain: the first step that can answer wins.
    @Test fun theTableWinsOverEverything() {
        assertEquals(IconStage.TABLE, iconStage(inTable = true, adaptive = true, monochrome = true))
        assertEquals(IconStage.TABLE, iconStage(inTable = true, adaptive = false, monochrome = false))
    }

    @Test fun anAdaptiveIconWithAMonochromeLayerGetsAWhiteGlyph() {
        assertEquals(IconStage.TILE, iconStage(inTable = false, adaptive = true, monochrome = true))
    }

    @Test fun anAdaptiveIconWithoutMonochromeIsRecomposed() {
        assertEquals(IconStage.RECOMPOSED, iconStage(inTable = false, adaptive = true, monochrome = false))
    }

    @Test fun anythingElseIsALegacyIcon() {
        assertEquals(IconStage.LEGACY, iconStage(inTable = false, adaptive = false, monochrome = false))
    }

    // shortcut_mask.png: 62x62 at (15, 15) of 92, so at 92 px the geometry is the theme's own.
    @Test fun theTemplateAtTheThemesSizeIsTheOneMeasured() {
        val g = TileGeometry(92)
        assertEquals(Box(15, 15, 77, 77), g.mask)
        assertEquals(46f, g.centre)
        assertEquals(37, g.glyph)
        assertEquals(47, g.legacy)
    }

    // Scaled, never shifted: the mask stays centred in its cell.
    @Test fun theTemplateScalesAndStaysCentred() {
        for (cell in listOf(72, 90, 92, 108, 184)) {
            val g = TileGeometry(cell)
            assertEquals(g.mask.width, g.mask.height)
            assertEquals(cell / 2f, g.centre, 1f)
        }
        assertEquals(124, TileGeometry(184).mask.width)
    }

    // An adaptive layer is 108 dp of which the middle 72 show: those 72 fill the mask.
    @Test fun anAdaptiveLayerShowsItsMiddleTwoThirdsInTheMask() {
        assertEquals(Box(-1, -1, 93, 93), TileGeometry(92).layer)
        val l = TileGeometry(72).layer
        assertEquals(l.width, l.height)
        assertEquals(48 * 1.5f, l.width.toFloat(), 1f)
        assertEquals(TileGeometry(72).centre, (l.left + l.right) / 2f, 0.5f)
    }

    @Test fun theVisibleBoxIsTheBoxOfTheNonTransparentPixels() {
        val alpha = IntArray(10 * 8)
        alpha[2 * 10 + 3] = 255
        alpha[5 * 10 + 7] = 10
        assertEquals(Box(3, 2, 8, 6), visibleBox(alpha, 10, 8))
        assertNull(visibleBox(IntArray(16), 4, 4))
    }

    // The glyph's visible pixels, wherever they sit in their layer, end up centred in the mask,
    // their longer side at 60 % of it.
    @Test fun aGlyphIsFittedAndCentredOnItsVisiblePixels() {
        val g = TileGeometry(92)
        for (visible in listOf(Box(25, 25, 75, 75), Box(10, 30, 30, 70), Box(60, 5, 98, 20))) {
            val b = fit(visible, probe = 100, target = g.glyph, g = g)
            val k = b.width / 100f
            assertEquals(b.width, b.height)
            assertEquals(g.glyph.toFloat(), max(visible.width, visible.height) * k, 1f)
            assertEquals(g.centre, b.left + (visible.left + visible.right) / 2f * k, 0.75f)
            assertEquals(g.centre, b.top + (visible.top + visible.bottom) / 2f * k, 0.75f)
        }
    }

    // Drive's monochrome layer keeps 26 dp of inset on each side of 108: its glyph still ends up
    // at 60 % of the mask.
    @Test fun aGlyphWithAWideMarginStillFillsSixtyPercentOfTheMask() {
        val g = TileGeometry(92)
        val visible = Box(72, 72, 120, 120)
        val b = fit(visible, probe = 192, target = g.glyph, g = g)
        assertEquals(g.glyph.toFloat(), visible.width * b.width / 192f, 1f)
    }

    private fun square(size: Int, inside: (Int, Int) -> Boolean) = IntArray(size * size) { i -> if (inside(i % size, i / size)) 255 else 0 }

    // A monochrome layer that is the whole foreground's silhouette (Android Studio's template) is
    // a white disc or square, no glyph: the icon is recomposed instead.
    @Test fun aSolidMonochromeLayerIsNoGlyph() {
        val ring = square(100) { x, y -> ((x - 50) * (x - 50) + (y - 50) * (y - 50)).let { it in 30 * 30..40 * 40 } }
        assertEquals(Box(10, 10, 91, 91), glyphBox(ring, 100, 100))
        assertNull(glyphBox(square(100) { x, y -> (x - 50) * (x - 50) + (y - 50) * (y - 50) <= 40 * 40 }, 100, 100))
        assertNull(glyphBox(square(100) { x, y -> x in 20..79 && y in 20..79 }, 100, 100))
        assertNull(glyphBox(IntArray(100), 10, 10))
    }

    // A legacy icon that is a square fills the mask; any other shape sits at 75 % of it.
    @Test fun aLegacyIconIsPlacedByItsShape() {
        val g = TileGeometry(92)
        val tile = legacyBounds(square(100) { x, y -> x in 10..89 && y in 10..89 }, 100, 100, g)!!
        assertEquals(g.mask.width.toFloat(), 80 * tile.width / 100f, 1f)
        val disc = legacyBounds(square(100) { x, y -> (x - 50) * (x - 50) + (y - 50) * (y - 50) <= 40 * 40 }, 100, 100, g)!!
        assertEquals(g.legacy.toFloat(), 81 * disc.width / 100f, 1f)
        assertNull(legacyBounds(IntArray(100), 10, 10, g))
    }

    // A legacy icon that already is a tile (Sound Recorder: a rounded square) fills the mask; any
    // other shape (FM Radio, a circle) is set on the underlay with a margin.
    @Test fun aLegacyIconFillsTheMaskOnlyIfItIsASquare() {
        val r = 8
        val rounded = square(100) { x, y ->
            val dx = max(0, max(r - x, x - (99 - r))); val dy = max(0, max(r - y, y - (99 - r)))
            dx * dx + dy * dy <= r * r
        }
        assertTrue(fillsSquare(rounded, 100, 100))
        assertFalse(fillsSquare(square(100) { x, y -> (x - 50) * (x - 50) + (y - 50) * (y - 50) <= 48 * 48 }, 100, 100))
        assertFalse(fillsSquare(square(100) { _, y -> y in 20..79 }, 100, 100))
        assertFalse(fillsSquare(IntArray(100), 10, 10))
    }
}
