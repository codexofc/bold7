package dev.bold7.firmware

import java.io.IOException

/** The first frame of a GIF, as ImageMagick reads it for `magick x.gif PNG32:x.png` (the scroll arrows). */
internal object Gif {
    fun decode(d: ByteArray): Image {
        val head = String(d, 0, minOf(6, d.size), Charsets.ISO_8859_1)
        if (head != "GIF87a" && head != "GIF89a") throw IOException("not a GIF")
        val w = u16(d, 6)
        val h = u16(d, 8)
        if (w == 0 || h == 0 || w.toLong() * h > 4096 * 4096) throw IOException("GIF of ${w}x$h")
        var pos = 13
        var global = IntArray(0)
        if ((d[10].toInt() and 0x80) != 0) {
            global = table(d, pos, 2 shl (d[10].toInt() and 7))
            pos += 3 * global.size
        }
        var transparent = -1
        val img = Image(w, h)
        while (pos < d.size) {
            when (d[pos++].toInt() and 0xFF) {
                0x21 -> {
                    val label = d[pos++].toInt() and 0xFF
                    if (label == 0xF9 && d[pos].toInt() == 4 && (d[pos + 1].toInt() and 1) != 0) transparent = d[pos + 4].toInt() and 0xFF
                    pos = skipBlocks(d, pos)
                }
                0x2C -> {
                    val left = u16(d, pos); val top = u16(d, pos + 2)
                    val fw = u16(d, pos + 4); val fh = u16(d, pos + 6)
                    val packed = d[pos + 8].toInt()
                    pos += 9
                    var colors = global
                    if ((packed and 0x80) != 0) {
                        colors = table(d, pos, 2 shl (packed and 7))
                        pos += 3 * colors.size
                    }
                    val min = d[pos++].toInt()
                    val data = java.io.ByteArrayOutputStream()
                    while (true) {
                        val n = d[pos++].toInt() and 0xFF
                        if (n == 0) break
                        data.write(d, pos, n)
                        pos += n
                    }
                    val idx = lzw(data.toByteArray(), min, fw * fh)
                    val rows = if ((packed and 0x40) != 0) interlaced(fh) else IntArray(fh) { it }
                    for (r in 0 until fh) for (x in 0 until fw) {
                        val px = left + x; val py = top + rows[r]
                        if (px >= w || py >= h) continue
                        val i = idx[r * fw + x].toInt() and 0xFF
                        img[px, py] = if (i == transparent) colors.getOrElse(i) { 0 } and 0xFFFFFF else colors.getOrElse(i) { 0xFF000000.toInt() }
                    }
                    return img
                }
                0x3B -> break
                else -> throw IOException("GIF block at ${pos - 1}")
            }
        }
        throw IOException("GIF without image")
    }

    private fun table(d: ByteArray, pos: Int, n: Int) = IntArray(n) {
        val o = pos + 3 * it
        (0xFF shl 24) or ((d[o].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() and 0xFF)
    }

    private fun skipBlocks(d: ByteArray, from: Int): Int {
        var pos = from
        while (true) {
            val n = d[pos++].toInt() and 0xFF
            if (n == 0) return pos
            pos += n
        }
    }

    /** Row of the image for each decoded row of an interlaced frame (passes 8, 8+4, 4+2, 2+1). */
    private fun interlaced(h: Int): IntArray {
        val out = ArrayList<Int>()
        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) for (y in start until h step step) out += y
        return out.toIntArray()
    }

    private fun lzw(data: ByteArray, min: Int, count: Int): ByteArray {
        if (min !in 2..8) throw IOException("GIF code size $min")
        val clear = 1 shl min
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val first = ByteArray(4096)
        val stack = ByteArray(4097)
        val out = ByteArray(count)
        var size = min + 1
        var next = clear + 2
        var old = -1
        var n = 0
        var bits = 0
        var acc = 0
        var i = 0
        for (c in 0 until clear) { suffix[c] = c.toByte(); first[c] = c.toByte() }
        while (n < count) {
            while (bits < size) {
                if (i >= data.size) return out
                acc = acc or ((data[i++].toInt() and 0xFF) shl bits)
                bits += 8
            }
            val code = acc and ((1 shl size) - 1)
            acc = acc ushr size
            bits -= size
            if (code == clear) { size = min + 1; next = clear + 2; old = -1; continue }
            if (code == clear + 1) break
            var sp = 0
            var c = code
            if (old >= 0 && code >= next) { stack[sp++] = first[old]; c = old } else if (code >= next) throw IOException("GIF code $code")
            while (c >= clear) { stack[sp++] = suffix[c]; c = prefix[c] }
            stack[sp++] = suffix[c]
            if (old >= 0 && next < 4096) {
                prefix[next] = old
                suffix[next] = stack[sp - 1]
                first[next] = first[old]
                next++
                if (next == 1 shl size && size < 12) size++
            }
            old = code
            while (sp > 0 && n < count) out[n++] = stack[--sp]
        }
        return out
    }
}
