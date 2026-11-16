package dev.bold7.firmware

import org.junit.Test
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** PNG, GIF, 9-patch, resize and TrueType, against ImageIO where it is a fair judge. */
class ImagesTest {
    private fun write(img: BufferedImage, format: String) = ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()

    private fun argbOf(img: BufferedImage) = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)

    /** Equal, a fully transparent pixel's colour aside. */
    private fun assertSamePixels(want: IntArray, got: IntArray) {
        assertEquals(want.size, got.size)
        for (i in want.indices) if (want[i] ushr 24 != 0 || got[i] ushr 24 != 0) assertEquals(want[i], got[i], "pixel $i")
    }

    private fun palette(n: Int, transparent: Int): IndexColorModel {
        val r = ByteArray(n) { (it * 37).toByte() }
        val g = ByteArray(n) { (it * 91).toByte() }
        val b = ByteArray(n) { (it * 13 + 5).toByte() }
        return IndexColorModel(8, n, r, g, b, transparent)
    }

    private fun indexed(w: Int, h: Int, n: Int, transparent: Int) = BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, palette(n, transparent)).apply {
        for (y in 0 until h) for (x in 0 until w) raster.setSample(x, y, 0, (x * 7 + y * y * 3 + x * y) % n)
    }

    @Test
    fun pngEncodingIsWhatImageIoReads() {
        val img = Image(37, 11, IntArray(37 * 11) { it * 0x01030507 })
        assertContentEquals(img.argb, argbOf(ImageIO.read(ByteArrayInputStream(Png.encode(img)))))
    }

    @Test
    fun pngDecodingOfRgbaAndPalette() {
        val rgba = BufferedImage(19, 7, BufferedImage.TYPE_INT_ARGB).apply { for (i in 0 until 19 * 7) setRGB(i % 19, i / 19, i * 0x0F0E0D0B) }
        assertContentEquals(argbOf(rgba), Png.decode(write(rgba, "png")).argb)
        for (n in listOf(2, 16, 256)) {
            val pal = indexed(23, 9, n, transparent = 1) // ImageIO writes 1, 4 or 8 bit palettes, with tRNS
            assertSamePixels(argbOf(pal), Png.decode(write(pal, "png")).argb)
        }
    }

    @Test
    fun pngDecodingOfGrey() {
        val grey = BufferedImage(13, 5, BufferedImage.TYPE_BYTE_GRAY).apply { for (i in 0 until 65) raster.setSample(i % 13, i / 13, 0, i * 3) }
        val got = Png.decode(write(grey, "png"))
        for (i in 0 until 65) assertEquals(0xFF000000.toInt() or (i * 3 * 0x010101), got.argb[i])
    }

    @Test
    fun gifFirstFrame() {
        // A large noisy frame makes the codes grow to 12 bits and the table clear.
        for (img in listOf(indexed(19, 9, 4, transparent = 2), indexed(300, 200, 256, transparent = 255))) {
            assertSamePixels(argbOf(ImageIO.read(ByteArrayInputStream(write(img, "gif")))), Gif.decode(write(img, "gif")).argb)
        }
    }

    @Test
    fun ninepatchBorder() {
        val src = Image(4, 3, IntArray(12) { 0x11223344 + it })
        val n = ninepatch(src, 1, 2, 0, 0)
        assertEquals(6 to 5, n.width to n.height)
        val black = 0xFF000000.toInt()
        assertEquals(listOf(0, 0, black, black, 0, 0), (0 until 6).map { n[it, 0] }) // columns 1 and 2 of the image
        assertEquals(listOf(0, black, 0, 0, 0), (0 until 5).map { n[0, it] })
        for (y in 0 until 3) for (x in 0 until 4) assertEquals(src[x, y], n[x + 1, y + 1])
        assertEquals(0, n[5, 4])
    }

    @Test
    fun resizeWeighsColoursByAlpha() {
        // Opaque blue on transparent red: the red must not bleed into the result.
        val img = Image(92, 92, IntArray(92 * 92) { if ((it % 92) in 30..60) 0xFF0000FF.toInt() else 0x00FF0000 })
        val out = Resize.resize(img, 52, 52)
        for (p in out.argb) if (p ushr 24 != 0) assertEquals(0x0000FF, p and 0xFFFFFF)
        // A flat opaque image stays flat.
        val flat = Resize.resize(Image(92, 92, IntArray(92 * 92) { 0xFF336699.toInt() }), 52, 52)
        assertTrue(flat.argb.all { it == 0xFF336699.toInt() })
    }

    @Test
    fun ninepatchPadMarksTheContentBlackAndTheRestRed() {
        val n = ninepatchPad(Image(6, 5), 2, 3, 1, 2, 1, 4, 2, 3)
        val (k, r) = 0xFF000000.toInt() to 0xFFFF0000.toInt()
        assertEquals(listOf(0, r, k, k, k, k, r, 0), (0 until 8).map { n[it, 6] }) // columns 1 to 4 of the image
        assertEquals(listOf(0, r, r, k, k, r, 0), (0 until 7).map { n[7, it] }) // rows 2 and 3
        assertEquals(listOf(0, 0, 0, k, k, 0, 0, 0), (0 until 8).map { n[it, 0] }) // the stretch lines stay
    }

    @Test
    fun coverScalesToTheSquareAndFlattensOnBlack() {
        // 8x6 to cover 9: 12x9, the centre kept; transparent pixels come out black.
        val img = Image(8, 6, IntArray(48) { if (it % 8 < 4) 0xFF336699.toInt() else 0x00FFFFFF })
        val out = Resize.cover(img, 9)
        assertEquals(9 to 9, out.width to out.height)
        assertTrue(out.argb.all { it ushr 24 == 0xFF })
        assertTrue((0..16 step 8).all { (out[8, 4] shr it and 0xFF) <= 2 }) // black, but Lanczos' ringing
        assertEquals(0xFF336699.toInt(), out[0, 4])
        val flat = Resize.cover(Image(64, 48, IntArray(64 * 48) { 0xFF808182.toInt() }), 72)
        assertTrue(flat.argb.all { it == 0xFF808182.toInt() })
    }

    @Test
    fun pngWithoutAlphaIsRgb() {
        val img = Image(13, 4, IntArray(52) { 0xFF000000.toInt() or it * 0x030507 })
        val png = Png.encode(img, alpha = false)
        assertEquals(2, png[25].toInt()) // IHDR colour type: RGB
        assertContentEquals(img.argb, argbOf(ImageIO.read(ByteArrayInputStream(png))))
    }

    /** A font with the head, hhea and OS/2 tables only, its hhea [asc], [desc], [gap]. */
    private fun font(asc: Int, desc: Int, gap: Int): ByteArray {
        val b = ByteBuffer.allocate(12 + 3 * 16 + 56 + 36 + 80)
        b.putInt(0x00010000).putShort(3).position(12)
        var off = 12 + 3 * 16
        for ((tag, len) in listOf("head" to 56, "hhea" to 36, "OS/2" to 80)) {
            b.put(tag.toByteArray()).putInt(0).putInt(off).putInt(len)
            off += len
        }
        b.putShort(12 + 48 + 56 + 4, asc.toShort()).putShort(12 + 48 + 56 + 6, desc.toShort()).putShort(12 + 48 + 56 + 8, gap.toShort())
        b.putShort(12 + 48 + 56 + 36 + 74, 1901).putShort(12 + 48 + 56 + 36 + 76, 483)
        return b.array()
    }

    @Test
    fun lineMetricsOfBbAlphaSans() {
        val fixed = TrueType.fixLineMetrics(font(1556, -483, 410))
        assertTrue(TrueType.consistent(fixed))
        val b = ByteBuffer.wrap(fixed)
        assertEquals(listOf<Short>(1901, -483, 0), listOf(b.getShort(120), b.getShort(122), b.getShort(124)))
        var sum = 0
        for (i in fixed.indices step 4) sum += b.getInt(i)
        assertEquals(0xB1B0AFBA.toInt(), sum) // head.checkSumAdjustment makes the whole font sum to it
        val already = font(1901, -483, 0)
        assertSame(already, TrueType.fixLineMetrics(already))
    }
}
