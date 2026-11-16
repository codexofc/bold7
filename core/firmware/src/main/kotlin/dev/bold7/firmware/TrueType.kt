package dev.bold7.firmware

import java.io.IOException
import java.nio.ByteBuffer

/**
 * tools/ttf_metrics.py: a TrueType font's hhea ascender, descender and line gap set to OS/2's
 * winAscent, -winDescent and 0 (BBAlphaSans.ttf alone has a shorter line than its siblings, its
 * accented capitals clipped), table checksum and head.checkSumAdjustment recomputed.
 */
internal object TrueType {
    private class Table(val entry: Int, val offset: Int, val length: Int)

    private fun tables(b: ByteBuffer): Map<String, Table> {
        val tag = b.getInt(0)
        if (tag != 0x00010000 && tag != 0x74727565) throw IOException("not a TrueType font")
        val out = HashMap<String, Table>()
        for (i in 0 until (b.getShort(4).toInt() and 0xFFFF)) {
            val e = 12 + 16 * i
            val name = String(ByteArray(4) { b.get(e + it) }, Charsets.ISO_8859_1)
            out[name] = Table(e, b.getInt(e + 8), b.getInt(e + 12))
        }
        for (t in listOf("head", "hhea", "OS/2")) if (t !in out) throw IOException("no $t table")
        return out
    }

    /** True when hhea already is (winAscent, -winDescent, 0). */
    fun consistent(font: ByteArray): Boolean {
        val b = ByteBuffer.wrap(font)
        val t = tables(b)
        val hhea = t.getValue("hhea").offset
        val os2 = t.getValue("OS/2").offset
        return b.getShort(hhea + 4).toInt() == (b.getShort(os2 + 74).toInt() and 0xFFFF) &&
            b.getShort(hhea + 6).toInt() == -(b.getShort(os2 + 76).toInt() and 0xFFFF) &&
            b.getShort(hhea + 8).toInt() == 0
    }

    private fun sum(b: ByteBuffer, from: Int, length: Int): Int {
        var s = 0
        var i = 0
        while (i < length) {
            var word = 0
            for (k in 0 until 4) word = (word shl 8) or (if (i + k < length) b.get(from + i + k).toInt() and 0xFF else 0)
            s += word
            i += 4
        }
        return s
    }

    fun fixLineMetrics(font: ByteArray): ByteArray {
        if (consistent(font)) return font
        val out = font.copyOf()
        val b = ByteBuffer.wrap(out)
        val t = tables(b)
        val hhea = t.getValue("hhea")
        val os2 = t.getValue("OS/2").offset
        val winAscent = b.getShort(os2 + 74).toInt() and 0xFFFF
        val winDescent = b.getShort(os2 + 76).toInt() and 0xFFFF
        b.putShort(hhea.offset + 4, winAscent.toShort())
        b.putShort(hhea.offset + 6, (-winDescent).toShort())
        b.putShort(hhea.offset + 8, 0)
        b.putInt(hhea.entry + 4, sum(b, hhea.offset, hhea.length))
        val head = t.getValue("head").offset
        b.putInt(head + 8, 0)
        b.putInt(head + 8, (0xB1B0AFBA - sum(b, 0, out.size)).toInt())
        return out
    }
}
