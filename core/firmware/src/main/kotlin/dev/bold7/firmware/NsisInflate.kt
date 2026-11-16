package dev.bold7.firmware

import java.io.IOException

/**
 * Raw deflate (RFC 1951) as NSIS's own zlib writes it: a stored block's length is not followed by
 * its complement (NLEN), which java.util.zip refuses ("invalid stored block lengths"); 7-Zip reads
 * NSIS the same way. Everything else is plain deflate. Huffman codes are decoded by one table
 * lookup per symbol (the table as wide as the longest code of the block).
 */
internal class NsisInflate private constructor(private val src: ByteArray, private val max: Int) {
    private var pos = 0
    private var bitBuf = 0L
    private var bitCount = 0
    private var out = ByteArray(minOf(max, maxOf(1 shl 16, src.size * 2)))
    private var size = 0

    private class Huffman(val table: IntArray, val bits: Int)

    companion object {
        private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
        private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
        private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
        private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

        private val FIXED_LIT = huffman(IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } })
        private val FIXED_DIST = huffman(IntArray(30) { 5 })

        /** [src] inflated, refused past [max] bytes. */
        fun inflate(src: ByteArray, max: Int): ByteArray = NsisInflate(src, max).run()

        /** Canonical code from code lengths: entry = symbol shl 4 or length, 0 for no code. */
        private fun huffman(lengths: IntArray): Huffman {
            val bits = lengths.maxOrNull() ?: 0
            if (bits == 0) return Huffman(IntArray(1), 1) // an empty distance code: any use is an error
            val count = IntArray(16)
            for (l in lengths) count[l]++
            count[0] = 0
            val next = IntArray(16)
            var code = 0
            for (l in 1..15) { code = (code + count[l - 1]) shl 1; next[l] = code }
            val table = IntArray(1 shl bits)
            for ((sym, l) in lengths.withIndex()) {
                if (l == 0) continue
                val c = next[l]++
                if (c >= (1 shl l)) throw IOException("deflate: over-subscribed code")
                var rev = 0
                for (k in 0 until l) rev = rev or (((c shr k) and 1) shl (l - 1 - k))
                var i = rev
                while (i < table.size) { table[i] = (sym shl 4) or l; i += 1 shl l }
            }
            return Huffman(table, bits)
        }
    }

    private fun need(n: Int) {
        while (bitCount < n) {
            // Past the end, zeros: a code that really uses them is caught by the check in run().
            val b = if (pos < src.size) src[pos].toInt() and 0xFF else 0
            pos++
            bitBuf = bitBuf or (b.toLong() shl bitCount)
            bitCount += 8
        }
    }

    private fun bits(n: Int): Int {
        if (n == 0) return 0
        need(n)
        val v = (bitBuf and ((1L shl n) - 1)).toInt()
        bitBuf = bitBuf ushr n
        bitCount -= n
        return v
    }

    private fun decode(h: Huffman): Int {
        need(h.bits)
        val e = h.table[(bitBuf and ((1L shl h.bits) - 1)).toInt()]
        val l = e and 15
        if (l == 0) throw IOException("deflate: invalid code")
        bitBuf = bitBuf ushr l
        bitCount -= l
        return e shr 4
    }

    private fun put(b: Int) {
        if (size == out.size) {
            if (size >= max) throw IOException("deflate: over $max bytes")
            out = out.copyOf(minOf(max.toLong(), 2L * out.size).toInt())
        }
        out[size++] = b.toByte()
    }

    private fun run(): ByteArray {
        do {
            val last = bits(1)
            when (bits(2)) {
                0 -> stored()
                1 -> codes(FIXED_LIT, FIXED_DIST)
                2 -> dynamic()
                else -> throw IOException("deflate: invalid block type")
            }
            if (pos - bitCount / 8 > src.size) throw IOException("deflate: truncated")
        } while (last == 0)
        return out.copyOf(size)
    }

    private fun stored() {
        // Byte aligned: the whole bytes still in the buffer go back to the input.
        pos -= bitCount / 8
        bitBuf = 0
        bitCount = 0
        if (pos + 2 > src.size) throw IOException("deflate: truncated")
        val len = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2 // NSIS: no NLEN
        if (pos + len > src.size) throw IOException("deflate: truncated stored block")
        for (i in 0 until len) put(src[pos + i].toInt())
        pos += len
    }

    private fun dynamic() {
        val nlen = bits(5) + 257
        val ndist = bits(5) + 1
        val ncode = bits(4) + 4
        if (nlen > 286 || ndist > 30) throw IOException("deflate: bad counts")
        val cl = IntArray(19)
        for (i in 0 until ncode) cl[ORDER[i]] = bits(3)
        val clCode = huffman(cl)
        val lengths = IntArray(nlen + ndist)
        var i = 0
        while (i < lengths.size) {
            val sym = decode(clCode)
            if (sym < 16) { lengths[i++] = sym; continue }
            val (value, repeat) = when (sym) {
                16 -> { if (i == 0) throw IOException("deflate: repeat with no length"); lengths[i - 1] to 3 + bits(2) }
                17 -> 0 to 3 + bits(3)
                else -> 0 to 11 + bits(7)
            }
            if (i + repeat > lengths.size) throw IOException("deflate: too many lengths")
            repeat(repeat) { lengths[i++] = value }
        }
        if (lengths[256] == 0) throw IOException("deflate: no end of block")
        codes(huffman(lengths.copyOf(nlen)), huffman(lengths.copyOfRange(nlen, lengths.size)))
    }

    private fun codes(lit: Huffman, dist: Huffman) {
        while (true) {
            val sym = decode(lit)
            if (sym < 256) { put(sym); continue }
            if (sym == 256) return
            val l = sym - 257
            if (l >= 29) throw IOException("deflate: bad length")
            val len = LENGTH_BASE[l] + bits(LENGTH_EXTRA[l])
            val d = decode(dist)
            if (d >= 30) throw IOException("deflate: bad distance")
            val distance = DIST_BASE[d] + bits(DIST_EXTRA[d])
            if (distance > size) throw IOException("deflate: distance too far")
            for (k in 0 until len) put(out[size - distance].toInt())
        }
    }
}
