package dev.bold7.icons

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileTest {
    // The chain: the first step that can answer wins; a monochrome layer never makes a white glyph.
    @Test fun theTableWinsOverEverything() {
        assertEquals(IconStage.TABLE, iconStage(inTable = true, adaptive = true, square = false))
        assertEquals(IconStage.TABLE, iconStage(inTable = true, adaptive = false, square = true))
    }

    @Test fun anAdaptiveIconIsAlwaysRecomposedInColour() {
        assertEquals(IconStage.RECOMPOSED, iconStage(inTable = false, adaptive = true, square = false))
        assertEquals(IconStage.RECOMPOSED, iconStage(inTable = false, adaptive = true, square = true))
    }

    @Test fun aLegacyIconIsATileIfSquareElseAShape() {
        assertEquals(IconStage.SQUARE, iconStage(inTable = false, adaptive = false, square = true))
        assertEquals(IconStage.SHAPE, iconStage(inTable = false, adaptive = false, square = false))
    }

    // shortcut_mask.png: 62x62 at (15, 15) of 92, so at 92 px the geometry is the theme's own.
    @Test fun theTemplateAtTheThemesSizeIsTheOneMeasured() {
        val g = TileGeometry(92)
        assertEquals(Box(15, 15, 77, 77), g.mask)
        assertEquals(46f, g.centre)
        assertEquals(64, g.shape)
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

    // An image's visible pixels, wherever they sit in their square, end up centred in the mask,
    // their longer side at the size asked.
    @Test fun anImageIsFittedAndCentredOnItsVisiblePixels() {
        val g = TileGeometry(92)
        for (visible in listOf(Box(25, 25, 75, 75), Box(10, 30, 30, 70), Box(60, 5, 98, 20))) {
            val b = fit(visible, probe = 100, target = g.shape, g = g)
            val k = b.width / 100f
            assertEquals(b.width, b.height)
            assertEquals(g.shape.toFloat(), max(visible.width, visible.height) * k, 1f)
            assertEquals(g.centre, b.left + (visible.left + visible.right) / 2f * k, 0.75f)
            assertEquals(g.centre, b.top + (visible.top + visible.bottom) / 2f * k, 0.75f)
        }
    }

    private fun square(size: Int, inside: (Int, Int) -> Boolean) = IntArray(size * size) { i -> if (inside(i % size, i / size)) 255 else 0 }

    // A legacy icon that is a square fills the mask; any other shape is as large as the rim.
    @Test fun aLegacyIconIsPlacedByItsShape() {
        val g = TileGeometry(92)
        val tile = legacyBounds(square(100) { x, y -> x in 10..89 && y in 10..89 }, 100, 100, g)!!
        assertEquals(g.mask.width.toFloat(), 80 * tile.width / 100f, 1f)
        val disc = legacyBounds(square(100) { x, y -> (x - 50) * (x - 50) + (y - 50) * (y - 50) <= 40 * 40 }, 100, 100, g)!!
        assertEquals(g.shape.toFloat(), 81 * disc.width / 100f, 1f)
        assertNull(legacyBounds(IntArray(100), 10, 10, g))
    }

    // A legacy icon that already is a tile (Sound Recorder: a rounded square) fills the mask; any
    // other shape (FM Radio, a circle) keeps it.
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

    // A third-party app's indicator takes a counter's frame (33x29), its icon as the list's header draws it.
    @Test fun anIndicatorIconTakesACountersFrame() {
        assertEquals(Box(2, 0, 31, 29), INDICATOR_BOX)
        assertEquals(INDICATOR_FRAME_HEIGHT, INDICATOR_BOX.bottom)
        assertEquals(listOf(34, 29), downscaleSteps(TEMPLATE_SIZE - 2 * INDICATOR_MARGIN, INDICATOR_SIDE))
    }

    // The light, measured on Facebook's home screen icon and YouTube's tile (A).
    @Test fun theSheenCoversTheUpperHalfOnly() {
        assertEquals(0.33f, gloss(0f))
        assertEquals(0.29f, gloss(0.125f), 0.01f)
        assertEquals(0.15f, gloss(0.28f), 0.01f)
        assertEquals(0.08f, gloss(0.36f), 0.01f)
        assertEquals(0f, gloss(0.5f))
        assertEquals(0f, gloss(1f))
        assertEquals(1f, shade(0f))
        assertEquals(219f / 254f, shade(1f), 0.005f)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun Int.r() = this shr 16 and 0xFF
    private fun Int.g() = this shr 8 and 0xFF
    private fun Int.b() = this and 0xFF

    /** A [size] px tile, [ground] everywhere but a centred square glyph of [glyph] over its middle third. */
    private fun tile(size: Int, ground: Int, glyph: Int) = IntArray(size * size) { i ->
        val x = i % size; val y = i / size
        if (x in size / 3 until 2 * size / 3 && y in size / 3 until 2 * size / 3) glyph else ground
    }

    // WhatsApp's look: a flat green tile with a white glyph comes out green, lighter at the top,
    // darker at the bottom, the hue kept.
    @Test fun aFlatColourIsLitFromTheTopAndKeepsItsHue() {
        val green = argb(255, 37, 211, 102)
        val px = tile(90, green, argb(255, 255, 255, 255))
        glaze(px, 90, 0, 90)
        val top = px[45]; val middle = px[50 * 90 + 5]; val bottom = px[89 * 90 + 45]
        assertEquals((37 + 218 * 0.33f).toInt().toFloat(), top.r().toFloat(), 1.5f)
        assertEquals(211f * shade(50.5f / 90), middle.g().toFloat(), 1f)
        assertEquals(37f * shade(89.5f / 90), bottom.r().toFloat(), 1f)
        for (c in listOf(top, middle, bottom)) assertTrue(c.g() > c.b() && c.b() > c.r(), "still green: ${c.toString(16)}")
        assertTrue(top.g() > middle.g() && middle.g() > bottom.g())
        // The glyph's middle row is past the sheen: only the shade, white still.
        assertEquals(255f * shade(45.5f / 90), px[45 * 90 + 45].g().toFloat(), 1f)
    }

    // Never a black tile with a white glyph: Android's flat monochrome icon is turned over.
    @Test fun aDarkGreyTileIsTurnedLight() {
        val px = tile(90, argb(255, 0, 0, 0), argb(255, 255, 255, 255))
        glaze(px, 90, 0, 90)
        val ground = px[80 * 90 + 5]; val glyph = px[45 * 90 + 45]
        assertTrue(ground.r() > 200 && ground.g() > 200 && ground.b() > 200, "light ground: ${ground.toString(16)}")
        assertTrue(glyph.r() < 40, "dark glyph: ${glyph.toString(16)}")
    }

    // A white tile with a black glyph, a dark tile with a coloured glyph: their own, only lit.
    @Test fun aLightOrColouredIconIsNotTurned() {
        assertFalse(darkAndGrey(tile(90, argb(255, 255, 255, 255), argb(255, 0, 0, 0))))
        val red = IntArray(90 * 90) { i -> val x = i % 90 - 45; val y = i / 90 - 45; if (x * x + y * y < 35 * 35) argb(255, 220, 30, 30) else argb(255, 10, 10, 10) }
        assertFalse(darkAndGrey(red))
        val px = red.copyOf()
        glaze(px, 90, 0, 90)
        assertTrue(px[85 * 90 + 2].r() < 20, "the black ground stays black")
        assertTrue(darkAndGrey(tile(90, argb(255, 30, 30, 32), argb(255, 250, 250, 250))))
        assertFalse(darkAndGrey(IntArray(16)))
    }

    // Light only within the rows given, and never on alpha or on transparent pixels.
    @Test fun theGlazeKeepsAlphaAndTransparentPixels() {
        val px = IntArray(10 * 10) { if (it % 2 == 0) argb(128, 100, 100, 200) else 0 }
        glaze(px, 10, 2, 8)
        for (i in px.indices) if (i % 2 == 0) assertEquals(128, px[i] ushr 24) else assertEquals(0, px[i])
        assertTrue(px[2 * 10].b() > 200, "the first row of the box is lit")
    }

    // The shadow of a free shape: under it rather than above, gone far away, never opaque.
    @Test fun aShapeCastsTheUnderlaysShadow() {
        val disc = square(40) { x, y -> (x - 20) * (x - 20) + (y - 18) * (y - 18) <= 10 * 10 }
        val s = dropShadow(disc, 40, 40)
        assertTrue(s[29 * 40 + 20] > s[5 * 40 + 20], "more below than above")
        assertTrue(s[29 * 40 + 20] in 60..128, "about half opaque just under it: ${s[29 * 40 + 20]}")
        assertEquals(0, s[2 * 40 + 2])
        assertEquals(0, s[39 * 40 + 20])
        assertTrue(s.all { it <= 128 })
    }
}
