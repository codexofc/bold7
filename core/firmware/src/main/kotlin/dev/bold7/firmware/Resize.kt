package dev.bold7.firmware

/**
 * ImageMagick's `-resize` (resize.c), as 7.1.2 Q16-HDRI computes it: one pass along columns then
 * one along rows when the two factors are equal (its order), the filter's support scaled by a
 * reduction. [resize]: its default filter for an image with alpha, Mitchell, each colour weighted
 * by its alpha (folder.png of Messages). [cover]: the wallpapers' Lanczos, on an opaque image.
 */
internal object Resize {
    private const val B = 1.0 / 3
    private const val C = 1.0 / 3

    private fun mitchell(x: Double): Double {
        val t = Math.abs(x)
        return when {
            t < 1 -> ((6 - 2 * B) + t * t * ((-18 + 12 * B + 6 * C) + t * (12 - 9 * B - 6 * C))) / 6
            t < 2 -> ((8 * B + 24 * C) + t * ((-12 * B - 48 * C) + t * ((6 * B + 30 * C) + t * (-B - 6 * C)))) / 6
            else -> 0.0
        }
    }

    /** For each output index, the source indices and their normalised weights. */
    private fun weights(src: Int, dst: Int, filter: (Double) -> Double = ::mitchell, filterSupport: Double = 2.0): List<Pair<Int, DoubleArray>> {
        val factor = dst.toDouble() / src
        val scale = maxOf(1 / factor, 1.0)
        val support = filterSupport * scale
        return List(dst) { x ->
            val bisect = (x + 0.5) / factor + 1e-12
            val start = maxOf((bisect - support + 0.5).toInt(), 0)
            val stop = minOf((bisect + support + 0.5).toInt(), src)
            val w = DoubleArray(stop - start) { n -> filter(Math.abs((start + n - bisect + 0.5) * (1 / scale))) }
            val density = w.sum()
            if (density != 0.0 && density != 1.0) for (n in w.indices) w[n] /= density
            start to w
        }
    }

    /** One pass along rows ([horizontal]) or columns, on RGBA doubles in 0..1. */
    private fun pass(src: Array<DoubleArray>, sw: Int, sh: Int, dw: Int, dh: Int, horizontal: Boolean): Array<DoubleArray> {
        val ws = if (horizontal) weights(sw, dw) else weights(sh, dh)
        return Array(dw * dh) { i ->
            val x = i % dw; val y = i / dw
            val (start, w) = ws[if (horizontal) x else y]
            var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0; var gamma = 0.0
            for (n in w.indices) {
                val p = if (horizontal) src[y * sw + start + n] else src[(start + n) * sw + x]
                val alpha = w[n] * p[3]
                r += alpha * p[0]; g += alpha * p[1]; b += alpha * p[2]
                a += w[n] * p[3]
                gamma += alpha
            }
            val k = if (Math.abs(gamma) < 1e-12) 1 / (if (gamma < 0) -1e-12 else 1e-12) else 1 / gamma
            doubleArrayOf(r * k, g * k, b * k, a)
        }
    }

    fun resize(img: Image, w: Int, h: Int): Image {
        val src = Array(img.argb.size) { i ->
            val p = img.argb[i]
            doubleArrayOf((p shr 16 and 0xFF) / 255.0, (p shr 8 and 0xFF) / 255.0, (p and 0xFF) / 255.0, (p ushr 24) / 255.0)
        }
        val mid = pass(src, img.width, img.height, img.width, h, horizontal = false)
        val out = pass(mid, img.width, h, w, h, horizontal = true)
        fun q(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        return Image(w, h, IntArray(w * h) { i -> val p = out[i]; (q(p[3]) shl 24) or (q(p[0]) shl 16) or (q(p[1]) shl 8) or q(p[2]) })
    }

    // SincFast of resize.c for 16-bit quanta: Robidoux and Racette's polynomial, not sin(πx)/(πx);
    // the pixels match only with it.
    private const val S0 = 0.173611107357320220183368594093166520811e-2
    private const val S1 = -0.384240921114946632192116762889211361285e-3
    private const val S2 = 0.394201182359318128221229891724947048771e-4
    private const val S3 = -0.250963301609117217660068889165550534856e-5
    private const val S4 = 0.111902032818095784414237782071368805120e-6
    private const val S5 = -0.372895101408779549368465614321137048875e-8
    private const val S6 = 0.957694196677572570319816780188718518330e-10
    private const val S7 = -0.187208577776590710853865174371617338991e-11
    private const val S8 = 0.253524321426864752676094495396308636823e-13
    private const val S9 = -0.177084805010701112639035485248501049364e-15

    private fun sincFast(x: Double): Double {
        if (x > 4.0) return Math.sin(Math.PI * x) / (Math.PI * x)
        val xx = x * x
        val p = S0 + xx * (S1 + xx * (S2 + xx * (S3 + xx * (S4 + xx * (S5 + xx * (S6 + xx * (S7 + xx * (S8 + xx * S9))))))))
        return (xx - 1.0) * (xx - 4.0) * (xx - 9.0) * (xx - 16.0) * p
    }

    /** Lanczos: SincFast windowed by SincFast over a support of 3. */
    private fun lanczos(x: Double) = sincFast(x * (1.0 / 3)) * sincFast(x)

    private const val QUANTUM = 65535.0

    /**
     * `magick <img> -background black -alpha remove -alpha off -filter Lanczos -resize SxS^
     * -gravity center -extent SxS`: scaled to cover a [size] square, its centre kept, flattened on
     * black first. The pixels are ImageMagick's, so its arithmetic is kept: quanta in 0..65535
     * stored as Float between steps (HDRI), sums in Double, the final scaling to 8 bits in Float.
     */
    fun cover(img: Image, size: Int): Image {
        val sw = img.width
        val sh = img.height
        val f = maxOf(size.toDouble() / sw, size.toDouble() / sh)
        val dw = Math.floor(f * sw + 0.5).toInt()
        val dh = Math.floor(f * sh + 0.5).toInt()
        // -alpha remove over opaque black (channel.c FlattenPixelInfo, composite-private.h MagickOver_).
        val qs = 1.0 / QUANTUM
        val src = Array(3) { FloatArray(sw * sh) }
        for (i in 0 until sw * sh) {
            val p = img.argb[i]
            val alpha = (p ushr 24) * 257.0
            val sa = qs * QUANTUM
            val da = qs * alpha
            val gamma = 1.0 / (sa * -da + sa + da)
            for (c in 0..2) src[c][i] = (gamma * (qs * alpha * ((p shr (16 - 8 * c) and 0xFF) * 257.0))).toFloat()
        }
        // Columns first (the factors are equal), then rows, only for the columns the square keeps.
        val ox = (dw - size) / 2
        val oy = (dh - size) / 2
        val vw = weights(sh, dh, ::lanczos, 3.0)
        val mid = Array(3) { FloatArray(sw * dh) }
        for (c in 0..2) for (y in 0 until dh) {
            val (start, w) = vw[y]
            for (x in 0 until sw) {
                var sum = 0.0
                for (n in w.indices) sum += w[n] * src[c][(start + n) * sw + x]
                mid[c][y * sw + x] = sum.toFloat()
            }
        }
        val hw = weights(sw, dw, ::lanczos, 3.0)
        val out = Image(size, size)
        for (y in 0 until size) for (x in 0 until size) {
            val (start, w) = hw[x + ox]
            var argb = 0xFF000000.toInt()
            for (c in 0..2) {
                var sum = 0.0
                val row = (y + oy) * sw
                for (n in w.indices) sum += w[n] * mid[c][row + start + n]
                argb = argb or (toChar(sum.toFloat()) shl (16 - 8 * c))
            }
            out[x, y] = argb
        }
        return out
    }

    /** ScaleQuantumToChar of quantum.h for Q16-HDRI, in Float as there. */
    private fun toChar(q: Float): Int {
        if (q.isNaN() || q <= 0f) return 0
        val scaled = q / 257.0f
        return if (scaled >= 255.0f) 255 else (scaled + 0.5f).toInt()
    }
}
