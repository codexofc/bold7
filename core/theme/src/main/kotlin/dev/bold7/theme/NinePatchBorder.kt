package dev.bold7.theme

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A 9-patch as aapt2 reads it from the 1 px border of a source .9.png: the stretch regions as
 * [start, end) pairs of the image without its border, and the padding (left, top, right, bottom).
 */
class NinePatchSpec(val xDivs: IntArray, val yDivs: IntArray, val padding: IntArray)

/**
 * The border of a source .9.png, the one the extracted folder holds (tools/import-assets.sh and
 * core/firmware write them with their marks; only aapt2 turns a mark into an npTc chunk, which a
 * file read at run time does not have). The rules are aapt2's (compile/NinePatch.cpp): opaque black
 * marks, everything else (transparent, the red of layout bounds) does not; without a padding line,
 * the padding is the span of the stretch regions. AaptNinePatchTest holds it to aapt2 on every
 * 9-patch of assets-gen.
 */
object NinePatchBorder {
    private const val MARK = 0xFF000000.toInt()

    /** The 9-patch of an ARGB [pixels] image of [width] x [height], border included; null without marks. */
    fun read(pixels: IntArray, width: Int, height: Int): NinePatchSpec? {
        val w = width - 2
        val h = height - 2
        if (w <= 0 || h <= 0) return null
        val top = runs(IntArray(w) { pixels[1 + it] })
        val left = runs(IntArray(h) { pixels[(1 + it) * width] })
        if (top.isEmpty() && left.isEmpty()) return null
        val bottom = runs(IntArray(w) { pixels[(height - 1) * width + 1 + it] })
        val right = runs(IntArray(h) { pixels[(1 + it) * width + width - 1] })
        val (l, r) = padding(bottom, top, w)
        val (t, b) = padding(right, left, h)
        return NinePatchSpec(top, left, intArrayOf(l, t, r, b))
    }

    /** The marked [start, end) runs of a border line, flattened. */
    private fun runs(line: IntArray): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i < line.size) {
            if (line[i] != MARK) { i++; continue }
            val start = i
            while (i < line.size && line[i] == MARK) i++
            out += start
            out += i
        }
        return out.toIntArray()
    }

    /** Start and end padding of an axis of [length]: its padding line, else its stretch span, else none. */
    private fun padding(line: IntArray, stretch: IntArray, length: Int): Pair<Int, Int> = when {
        line.isNotEmpty() -> line[0] to length - line[1]
        stretch.isNotEmpty() -> stretch.first() to length - stretch.last()
        else -> 0 to 0
    }

    /**
     * The chunk NinePatch and NinePatchDrawable take (Res_png_9patch in the device's order, which
     * the native side re-offsets when it reads it). No region colours: every region is drawn, as
     * HWUI does when their count is not the lattice's.
     */
    fun chunk(spec: NinePatchSpec): ByteArray {
        val nx = spec.xDivs.size
        val ny = spec.yDivs.size
        val b = ByteBuffer.allocate(32 + 4 * (nx + ny)).order(ByteOrder.nativeOrder())
        b.put(0).put(nx.toByte()).put(ny.toByte()).put(0)
        b.putInt(32).putInt(32 + 4 * nx)
        val (l, t, r, bottom) = spec.padding.toList()
        b.putInt(l).putInt(r).putInt(t).putInt(bottom)
        b.putInt(32 + 4 * (nx + ny))
        spec.xDivs.forEach { b.putInt(it) }
        spec.yDivs.forEach { b.putInt(it) }
        return b.array()
    }
}
