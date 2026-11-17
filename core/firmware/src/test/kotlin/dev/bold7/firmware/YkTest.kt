package dev.bold7.firmware

import org.junit.Test
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The YK decoder on small streams made for Bold7 (docs/YK.md): no byte of BlackBerry. They were
 * written by an encoder kept out of the repository, and RIM's own decoder reads them back the same
 * way. The help of the 9900 itself is decoded in RealFirmwareTest.
 */
class YkTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Side data "ab" and nothing else, then a page of five terminals. */
    private val side1 = hex("8400000a006100627d000000000083000000")
    private val page1 = hex("8400000b16ee3aab78aa57f000000083000000")

    /**
     * Side data "abca", then a repeat of "ab" grown to "abc", then "x": one new symbol, "abc".
     * The page: that symbol, the one known to follow it (itself), "!", then two bytes of it.
     */
    private val side2 = hex("8400000e006100621b0fcae552843f00000083000000")
    private val page2 = hex("840000097b92d52c67b900000083000000")

    @Test
    fun pageOfTerminals() {
        assertEquals("hello", String(YkDictionary.load(side1).decode(page1)))
    }

    @Test
    fun repeatBecomesASymbolAndItsFollowers() {
        val d = YkDictionary.load(side2)
        assertEquals(1, d.symbols)
        assertEquals("abcabc!ab", String(d.decode(page2)))
    }

    /** Each page starts from the models the side data left: decoding one does not change the next. */
    @Test
    fun pagesAreIndependent() {
        val d = YkDictionary.load(side2)
        val first = d.decode(page2)
        assertContentEquals(first, d.decode(page2))
        assertEquals("hello", String(YkDictionary.load(side1).decode(page1)))
    }

    /** A block is 84 00 <u16 length>, the stream ends with 83 00 00 00: the same text cut otherwise reads the same. */
    @Test
    fun blocksAreOneStream() {
        val body = page2.copyOfRange(4, page2.size - 4)
        val split = byteArrayOf(0x84.toByte(), 0, 0, 4) + body.copyOfRange(0, 4) +
            byteArrayOf(0x84.toByte(), 0, 0, (body.size - 4).toByte()) + body.copyOfRange(4, body.size) +
            byteArrayOf(0x83.toByte(), 0, 0, 0)
        assertEquals("abcabc!ab", String(YkDictionary.load(side2).decode(split)))
    }

    @Test
    fun refusesWhatIsNotYk() {
        assertFailsWith<IOException> { YkDictionary.load(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0)) }
        assertFailsWith<IOException> { YkDictionary.load(hex("8400000a0061")) } // cut short
        assertFailsWith<IOException> { YkDictionary.load(hex("8400000401000062" + "83000000")) } // a first symbol past 255
        val d = YkDictionary.load(side1)
        assertFailsWith<IOException> { d.decode(hex("82000000")) }
    }

    /** A stream whose symbols run past what the side data defined is refused, not read out of bounds. */
    @Test
    fun refusesAPageThatIsNotForThisSideData() {
        assertFailsWith<IOException> { YkDictionary.load(side1).decode(page2) }
    }
}
