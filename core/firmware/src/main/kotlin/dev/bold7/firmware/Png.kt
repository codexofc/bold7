package dev.bold7.firmware

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** An image as non-premultiplied ARGB, one Int per pixel, rows from the top. */
internal class Image(val width: Int, val height: Int, val argb: IntArray = IntArray(width * height)) {
    operator fun get(x: Int, y: Int) = argb[y * width + x]
    operator fun set(x: Int, y: Int, v: Int) { argb[y * width + x] = v }

    fun crop(x: Int, y: Int, w: Int, h: Int): Image {
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > width || y + h > height) throw IOException("crop ${w}x$h+$x+$y of ${width}x$height")
        return Image(w, h).also { out -> for (r in 0 until h) System.arraycopy(argb, (y + r) * width + x, out.argb, r * w, w) }
    }
}

/**
 * PNG, as the theme's images need: decoding of every non-interlaced type and depth (palette and
 * tRNS included), encoding as 8-bit RGBA (what ImageMagick's PNG32: writes) or, for an opaque
 * image, RGB (what it writes for one without alpha, the wallpapers). The pixels match
 * ImageMagick's; the bytes do not (its own filters, zlib settings and text chunks).
 */
internal object Png {
    private val SIG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    private const val MAX_PIXELS = 4096 * 4096

    fun decode(data: ByteArray): Image {
        if (data.size < 8 || !data.copyOf(8).contentEquals(SIG)) throw IOException("not a PNG")
        var pos = 8
        var w = 0; var h = 0; var depth = 0; var type = 0
        var palette = IntArray(0)
        var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        while (pos + 8 <= data.size) {
            val len = be32(data, pos)
            val tag = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            val body = pos + 8
            if (len < 0 || body + len > data.size) throw IOException("PNG chunk $tag past the end")
            when (tag) {
                "IHDR" -> {
                    w = be32(data, body); h = be32(data, body + 4)
                    depth = data[body + 8].toInt(); type = data[body + 9].toInt()
                    if (data[body + 12].toInt() != 0) throw IOException("interlaced PNG not handled")
                    if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) throw IOException("PNG of ${w}x$h")
                }
                "PLTE" -> palette = IntArray(len / 3) { i ->
                    val o = body + 3 * i
                    (0xFF shl 24) or ((data[o].toInt() and 0xFF) shl 16) or ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o + 2].toInt() and 0xFF)
                }
                "tRNS" -> trns = data.copyOfRange(body, body + len)
                "IDAT" -> idat.write(data, body, len)
                "IEND" -> break
            }
            pos = body + len + 4
        }
        if (w == 0) throw IOException("PNG without IHDR")
        val channels = when (type) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> throw IOException("PNG colour type $type") }
        if (depth !in setOf(1, 2, 4, 8, 16) || (depth < 8 && type != 0 && type != 3) || (depth == 16 && type == 3))
            throw IOException("PNG depth $depth for type $type")
        val bpp = maxOf(1, channels * depth / 8)
        val stride = (w.toLong() * channels * depth + 7).toInt() / 8
        val raw = ByteArray((stride + 1) * h)
        val z = Inflater()
        try {
            z.setInput(idat.toByteArray())
            var n = 0
            while (n < raw.size && !z.finished()) {
                val k = z.inflate(raw, n, raw.size - n)
                if (k == 0 && (z.needsInput() || z.needsDictionary())) break
                n += k
            }
            if (n != raw.size) throw IOException("PNG data short: $n of ${raw.size}")
        } finally {
            z.end()
        }
        unfilter(raw, stride, bpp, h)
        val img = Image(w, h)
        for (y in 0 until h) {
            val row = y * (stride + 1) + 1
            for (x in 0 until w) img[x, y] = pixel(raw, row, x, type, depth, palette, trns)
        }
        return img
    }

    private fun unfilter(raw: ByteArray, stride: Int, bpp: Int, h: Int) {
        for (y in 0 until h) {
            val row = y * (stride + 1)
            val f = raw[row].toInt()
            for (i in 1..stride) {
                val a = if (i > bpp) raw[row + i - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) raw[row - stride - 1 + i].toInt() and 0xFF else 0
                val c = if (y > 0 && i > bpp) raw[row - stride - 1 + i - bpp].toInt() and 0xFF else 0
                val add = when (f) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    4 -> paeth(a, b, c)
                    else -> throw IOException("PNG filter $f")
                }
                raw[row + i] = (raw[row + i] + add).toByte()
            }
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** Sample [i] of a row, scaled to 8 bits as ImageMagick does ((v + 128) / 257 for 16 bits). */
    private fun sample(raw: ByteArray, row: Int, i: Int, depth: Int): Int = when (depth) {
        8 -> raw[row + i].toInt() and 0xFF
        16 -> (((raw[row + 2 * i].toInt() and 0xFF) shl 8 or (raw[row + 2 * i + 1].toInt() and 0xFF)) + 128) / 257
        else -> {
            val bit = i * depth
            ((raw[row + bit / 8].toInt() and 0xFF) shr (8 - depth - bit % 8)) and ((1 shl depth) - 1)
        }
    }

    private fun pixel(raw: ByteArray, row: Int, x: Int, type: Int, depth: Int, palette: IntArray, trns: ByteArray?): Int {
        fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
        fun raw16(i: Int) = if (depth == 16) ((raw[row + 2 * i].toInt() and 0xFF) shl 8) or (raw[row + 2 * i + 1].toInt() and 0xFF) else sample(raw, row, i, depth)
        fun trnsAt(k: Int) = if (trns != null && trns.size >= 2 * k + 2) ((trns[2 * k].toInt() and 0xFF) shl 8) or (trns[2 * k + 1].toInt() and 0xFF) else -1
        return when (type) {
            3 -> {
                val i = sample(raw, row, x, depth)
                val c = palette.getOrElse(i) { 0xFF000000.toInt() }
                val a = if (trns != null && i < trns.size) trns[i].toInt() and 0xFF else 0xFF
                (a shl 24) or (c and 0xFFFFFF)
            }
            0 -> {
                val g = scaled(sample(raw, row, x, depth), depth)
                argb(if (raw16(x) == trnsAt(0)) 0 else 0xFF, g, g, g)
            }
            4 -> {
                val g = sample(raw, row, 2 * x, depth)
                argb(sample(raw, row, 2 * x + 1, depth), g, g, g)
            }
            2 -> {
                val opaque = !(raw16(3 * x) == trnsAt(0) && raw16(3 * x + 1) == trnsAt(1) && raw16(3 * x + 2) == trnsAt(2))
                argb(if (opaque) 0xFF else 0, sample(raw, row, 3 * x, depth), sample(raw, row, 3 * x + 1, depth), sample(raw, row, 3 * x + 2, depth))
            }
            else -> argb(sample(raw, row, 4 * x + 3, depth), sample(raw, row, 4 * x, depth), sample(raw, row, 4 * x + 1, depth), sample(raw, row, 4 * x + 2, depth))
        }
    }

    /** A grey sample of less than 8 bits stretched to 0..255. */
    private fun scaled(v: Int, depth: Int) = if (depth >= 8) v else v * 255 / ((1 shl depth) - 1)

    fun encode(img: Image, alpha: Boolean = true): ByteArray {
        val w = img.width
        val bpp = if (alpha) 4 else 3
        val stride = bpp * w
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        val best = ByteArray(stride + 1)
        val trial = ByteArray(stride + 1)
        val filtered = ByteArrayOutputStream()
        for (y in 0 until img.height) {
            for (x in 0 until w) {
                val p = img[x, y]
                cur[bpp * x] = (p shr 16).toByte(); cur[bpp * x + 1] = (p shr 8).toByte(); cur[bpp * x + 2] = p.toByte()
                if (alpha) cur[4 * x + 3] = (p ushr 24).toByte()
            }
            // The filter whose output has the smallest sum of absolute values (the PNG spec's heuristic).
            var bestSum = Long.MAX_VALUE
            for (f in 0..4) {
                trial[0] = f.toByte()
                var sum = 0L
                for (i in 0 until stride) {
                    val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                    val b = prev[i].toInt() and 0xFF
                    val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                    val pred = when (f) { 0 -> 0; 1 -> a; 2 -> b; 3 -> (a + b) / 2; else -> paeth(a, b, c) }
                    val v = (cur[i] - pred).toByte()
                    trial[i + 1] = v
                    sum += Math.abs(v.toInt())
                }
                if (sum < bestSum) { bestSum = sum; System.arraycopy(trial, 0, best, 0, stride + 1) }
            }
            filtered.write(best)
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        // zlib's default level: 9 is five times slower on the wallpapers' filtered rows for 1 % less.
        val d = Deflater(6)
        val packed = ByteArrayOutputStream()
        try {
            d.setInput(filtered.toByteArray())
            d.finish()
            val buf = ByteArray(1 shl 16)
            while (!d.finished()) packed.write(buf, 0, d.deflate(buf))
        } finally {
            d.end()
        }
        val out = ByteArrayOutputStream()
        out.write(SIG)
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).run { writeInt(w); writeInt(img.height); write(byteArrayOf(8, if (alpha) 6 else 2, 0, 0, 0)) } }
        chunk(out, "IHDR", ihdr.toByteArray())
        chunk(out, "IDAT", packed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, tag: String, body: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(body.size)
        val t = tag.toByteArray(Charsets.ISO_8859_1)
        d.write(t)
        d.write(body)
        d.writeInt(CRC32().apply { update(t); update(body) }.value.toInt())
    }

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
