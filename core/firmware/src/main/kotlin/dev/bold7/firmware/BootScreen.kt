package dev.bold7.firmware

import java.io.IOException

/**
 * The 9900's boot screen (docs/BBOS7-VISUEL.md, section 11), which the device draws natively: one
 * 640x480 RGB565 image, black, the BlackBerry logo and the empty track of the progress bar,
 * run-length coded. It is in the OS image of the loader (OS/rim0x07001204.sfi, 45 MB in) and in
 * the simulator's [SIMULATOR_DLL], the same pixels in both. Nothing of it is in a .cod.
 *
 * The code: a count byte, then either `count` pixels of one value (top bit clear) or `count & 0x7F`
 * literal pixels (top bit set), each pixel 16 bits little-endian; runs cross the rows. In the OS
 * image the code follows a header (`FFFFFFFF`, 9, width, height, then 2 bytes) and is cut by the
 * image's records: 12 bytes (load address, length, 0x20, a checksum), then up to 0x3FF0 bytes.
 * In the simulator's dll it is whole and has no header; it starts with the black of the first 190
 * rows, 127 pixels at a time.
 *
 * Only the logo is kept, the 332x59 box at (154, 190); the bar is drawn by [BootAnimation]. The
 * screen is checked before: black but for the box and the bar, the bar the track of [TRACK], the
 * logo touching the four edges of its box.
 */
internal object BootScreen {
    const val SIMULATOR_DLL = "fledge9900.dll"

    /** Where the extraction puts it, as tools/import-assets.sh demarrage puts the development one in assets-gen/. */
    const val LOGO = "bootanim/logo.png"

    private const val W = 640
    private const val H = 480
    private const val X = 154
    private const val Y = 190
    const val LOGO_W = 332
    const val LOGO_H = 59
    private const val BAR_X = 54
    private const val BAR_Y = 435
    private const val BAR_W = 532
    private const val BAR_H = 6
    private const val TRACK = 0x3186 // #313031

    private const val RECORD = 0x3FF0
    private const val MAX_CODE = 1 shl 20 // a screen of literals would be 615 KB
    private const val BLACK_RUNS = 900 // the first 190 rows are 957 runs of 127 black pixels
    private val HEADER = byteArrayOf(-1, -1, -1, -1, 9, 0, (W and 0xFF).toByte(), (W shr 8).toByte(), (H and 0xFF).toByte(), (H shr 8).toByte())

    /** The logo of the boot screen in [file], the OS image or the simulator's dll; refused when no screen passes the checks. */
    fun logo(file: ByteArray): Image = logoOrNull(file) ?: throw IOException("no boot screen of the 9900 in this file")

    fun logoOrNull(file: ByteArray): Image? {
        for (start in starts(file)) {
            for (code in listOf(file.copyOfRange(start, minOf(file.size, start + MAX_CODE)), unwrap(file, start))) {
                if (code == null) continue
                val screen = decode(code) ?: continue
                if (isBootScreen(screen)) return crop(screen)
            }
        }
        return null
    }

    /** Where a screen's code may start: after the OS image's header, and where a long black run begins. */
    private fun starts(d: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + HEADER.size + 2 <= d.size) {
            if ((HEADER.indices).all { d[i + it] == HEADER[it] }) out += i + HEADER.size + 2
            i++
        }
        i = 0
        while (i + 3 <= d.size) {
            var n = 0
            while (i + 3 * n + 3 <= d.size && d[i + 3 * n] == 0x7F.toByte() && d[i + 3 * n + 1] == 0.toByte() && d[i + 3 * n + 2] == 0.toByte()) n++
            if (n >= BLACK_RUNS) out += i
            i += maxOf(1, 3 * n)
        }
        return out
    }

    private fun u32(d: ByteArray, p: Int) = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8) or
        ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 3].toInt() and 0xFF) shl 24)

    /** A record of the OS image at [p]: its address and length, or null. */
    private fun record(d: ByteArray, p: Int): Pair<Int, Int>? {
        if (p < 0 || p + 12 > d.size || d[p + 8] != 0x20.toByte() || d[p + 9] != 0.toByte()) return null
        val n = u32(d, p + 4)
        return if (n in 1..RECORD && p + 12 + n <= d.size) u32(d, p) to n else null
    }

    /**
     * The code from [start] with the records' headers taken out: the record holding [start] is the
     * one whose next record follows it in memory; then each next record while that holds.
     */
    private fun unwrap(d: ByteArray, start: Int): ByteArray? {
        var p = start - 12
        var rec: Pair<Int, Int>? = null
        while (p >= maxOf(0, start - 12 - RECORD)) {
            val r = record(d, p)
            if (r != null && p + 12 + r.second > start && record(d, p + 12 + r.second)?.first == r.first + r.second) { rec = r; break }
            p--
        }
        if (rec == null) return null
        val out = java.io.ByteArrayOutputStream()
        out.write(d, start, p + 12 + rec.second - start)
        var addr = rec.first + rec.second
        var q = p + 12 + rec.second
        while (out.size() < MAX_CODE) {
            val r = record(d, q) ?: break
            if (r.first != addr) break
            out.write(d, q + 12, r.second)
            addr += r.second
            q += 12 + r.second
        }
        return out.toByteArray()
    }

    /** The 640x480 RGB565 pixels of [code], or null if it ends first or has an empty count. */
    internal fun decode(code: ByteArray): IntArray? {
        val px = IntArray(W * H)
        var n = 0
        var o = 0
        while (n < px.size) {
            if (o >= code.size) return null
            val c = code[o++].toInt() and 0xFF
            val count = c and 0x7F
            if (count == 0 || n + count > px.size) return null
            if (c and 0x80 != 0) {
                if (o + 2 * count > code.size) return null
                repeat(count) { px[n++] = (code[o].toInt() and 0xFF) or ((code[o + 1].toInt() and 0xFF) shl 8); o += 2 }
            } else {
                if (o + 2 > code.size) return null
                val v = (code[o].toInt() and 0xFF) or ((code[o + 1].toInt() and 0xFF) shl 8)
                o += 2
                px.fill(v, n, n + count)
                n += count
            }
        }
        return px
    }

    private fun inBox(x: Int, y: Int) = x in X until X + LOGO_W && y in Y until Y + LOGO_H

    private fun inBar(x: Int, y: Int) = x in BAR_X until BAR_X + BAR_W && y in BAR_Y until BAR_Y + BAR_H

    internal fun isBootScreen(px: IntArray): Boolean {
        for (y in 0 until H) for (x in 0 until W) {
            val v = px[y * W + x]
            when {
                inBox(x, y) -> {}
                inBar(x, y) -> if (v != TRACK) return false
                v != 0 -> return false
            }
        }
        fun lit(x0: Int, x1: Int, y0: Int, y1: Int) = (y0..y1).any { y -> (x0..x1).any { x -> px[y * W + x] != 0 } }
        val (x1, y1) = X + LOGO_W - 1 to Y + LOGO_H - 1
        return lit(X, X, Y, y1) && lit(x1, x1, Y, y1) && lit(X, x1, Y, Y) && lit(X, x1, y1, y1)
    }

    /** The box, opaque, each channel widened by repeating its top bits (5 to 8, 6 to 8). */
    private fun crop(px: IntArray): Image {
        val img = Image(LOGO_W, LOGO_H)
        for (y in 0 until LOGO_H) for (x in 0 until LOGO_W) {
            val v = px[(Y + y) * W + X + x]
            val r = v shr 11 and 0x1F
            val g = v shr 5 and 0x3F
            val b = v and 0x1F
            img[x, y] = (0xFF shl 24) or (((r shl 3) or (r shr 2)) shl 16) or (((g shl 2) or (g shr 4)) shl 8) or ((b shl 3) or (b shr 2))
        }
        return img
    }
}
