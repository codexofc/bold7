package dev.bold7.theme

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NinePatchBorderTest {
    private val k = 0xFF000000.toInt() // a mark
    private val r = 0xFFFF0000.toInt() // a layout bound, not a mark
    private val o = 0                  // nothing

    /** A [w] x [h] image with the given border rows and columns (corners and inside empty). */
    private fun image(w: Int, h: Int, top: String, left: String, bottom: String = "", right: String = ""): IntArray {
        fun px(c: Char) = when (c) { 'k' -> k; 'r' -> r; else -> o }
        val p = IntArray(w * h) { 0xFF808080.toInt() }
        for (x in 0 until w) { p[x] = o; p[(h - 1) * w + x] = o }
        for (y in 0 until h) { p[y * w] = o; p[y * w + w - 1] = o }
        top.forEachIndexed { i, c -> p[1 + i] = px(c) }
        left.forEachIndexed { i, c -> p[(1 + i) * w] = px(c) }
        bottom.forEachIndexed { i, c -> p[(h - 1) * w + 1 + i] = px(c) }
        right.forEachIndexed { i, c -> p[(1 + i) * w + w - 1] = px(c) }
        return p
    }

    @Test
    fun withoutPaddingLinesThePaddingIsTheStretch() {
        val s = NinePatchBorder.read(image(8, 7, top = "..kk..", left = ".k..."), 8, 7)!!
        assertContentEquals(intArrayOf(2, 4), s.xDivs)
        assertContentEquals(intArrayOf(1, 2), s.yDivs)
        // left, top, right, bottom: aapt2 takes the first and last stretch regions' bounds.
        assertContentEquals(intArrayOf(2, 1, 2, 3), s.padding)
    }

    @Test
    fun paddingLinesWin() {
        val s = NinePatchBorder.read(image(8, 7, top = "..kk..", left = ".k...", bottom = ".kkkk.", right = "..kk."), 8, 7)!!
        assertContentEquals(intArrayOf(1, 2, 1, 1), s.padding)
    }

    @Test
    fun severalStretchRegionsAndLayoutBoundsIgnored() {
        val s = NinePatchBorder.read(image(9, 6, top = "rk.kk.r", left = "kk.k"), 9, 6)!!
        assertContentEquals(intArrayOf(1, 2, 3, 5), s.xDivs)
        assertContentEquals(intArrayOf(0, 2, 3, 4), s.yDivs)
        assertContentEquals(intArrayOf(1, 0, 2, 0), s.padding)
    }

    @Test
    fun anImageWithoutMarksIsNotANinePatch() {
        assertNull(NinePatchBorder.read(image(5, 5, top = "...", left = "..."), 5, 5))
    }

    @Test
    fun chunkIsTheNativeStructure() {
        val s = NinePatchSpec(intArrayOf(2, 4), intArrayOf(1, 2, 3, 4), intArrayOf(5, 6, 7, 8))
        val b = ByteBuffer.wrap(NinePatchBorder.chunk(s)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32 + 4 * 6, b.capacity())
        assertEquals(0, b.get().toInt()) // wasDeserialized
        assertEquals(2, b.get().toInt())
        assertEquals(4, b.get().toInt())
        assertEquals(0, b.get().toInt()) // no colours: every region is drawn
        assertEquals(32, b.int)
        assertEquals(40, b.int)
        // padding: left, right, top, bottom
        assertEquals(listOf(5, 7, 6, 8), List(4) { b.int })
        assertEquals(56, b.int)
        assertEquals(listOf(2, 4, 1, 2, 3, 4), List(6) { b.int })
    }
}
