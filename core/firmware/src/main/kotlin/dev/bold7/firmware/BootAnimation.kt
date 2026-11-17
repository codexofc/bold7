package dev.bold7.firmware

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * tools/bootanim.py build: the 9900's boot screen as Android's bootanimation.zip, byte for byte the
 * script's output for the same logo (same frames, same PNG and zip writers, zlib level 9). On black,
 * the BlackBerry logo, and under it the progress bar measured on the simulator's native capture,
 * filling over [EXPECTED] seconds, then held full until Android asks bootanim to exit.
 *
 * The logo comes from the boot screen of the 9900's file ([BootScreen]) on the phone, from a
 * capture of the simulator in the development build; built with none, the box stays black above
 * the bar.
 */
internal object BootAnimation {
    private const val SIZE = 720
    private const val FPS = 12
    private const val EXPECTED = 13.7
    const val LOGO_W = 332
    const val LOGO_H = 59
    private const val BAR_W = 532
    private const val BAR_H = 6
    private val TRACK = intArrayOf(0x31, 0x30, 0x31)
    private val FILL = arrayOf(
        intArrayOf(0xFF, 0xFF, 0xFF), intArrayOf(0xF7, 0xF3, 0xF7), intArrayOf(0xDE, 0xDF, 0xDE),
        intArrayOf(0xCE, 0xCB, 0xCE), intArrayOf(0xB5, 0xB7, 0xB5), intArrayOf(0xA5, 0xA2, 0xA5),
    )
    private val LOGO_Y = Math.rint(SIZE * 0.46).toInt() - LOGO_H / 2
    private const val BAR_Y = SIZE - 45
    private const val X0 = (SIZE - BAR_W) / 2
    private val Y0 = LOGO_Y
    private val BOX_H = BAR_Y + BAR_H - Y0

    /** Python's round(): half to even. */
    private fun fillWidth(i: Int, frames: Int) = Math.rint(BAR_W * minOf(1.0, i.toDouble() / maxOf(1, frames - 1))).toInt()

    private fun frame(logo: Image?, width: Int): ByteArray {
        val buf = ByteArray(BAR_W * BOX_H * 3)
        if (logo != null) {
            val lx = (SIZE - logo.width) / 2 - X0
            for (y in 0 until logo.height) for (x in 0 until logo.width) {
                val i = ((y + LOGO_Y - Y0) * BAR_W + lx + x) * 3
                val p = logo[x, y]
                buf[i] = (p shr 16).toByte(); buf[i + 1] = (p shr 8).toByte(); buf[i + 2] = p.toByte()
            }
        }
        for (y in 0 until BAR_H) for (x in 0 until BAR_W) {
            val i = ((BAR_Y + y - Y0) * BAR_W + x) * 3
            val c = if (x < width) FILL[y] else TRACK
            for (k in 0..2) buf[i + k] = c[k].toByte()
        }
        return buf
    }

    /** 8-bit RGB, no row filter, zlib level 9: bootanim.py's png(). */
    private fun png(w: Int, h: Int, rgb: ByteArray): ByteArray {
        val raw = ByteArray((w * 3 + 1) * h)
        for (y in 0 until h) System.arraycopy(rgb, y * w * 3, raw, y * (w * 3 + 1) + 1, w * 3)
        val d = Deflater(9)
        val packed = ByteArrayOutputStream()
        try {
            d.setInput(raw)
            d.finish()
            val b = ByteArray(1 shl 16)
            while (!d.finished()) packed.write(b, 0, d.deflate(b))
        } finally {
            d.end()
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(tag: String, body: ByteArray) {
            val t = tag.toByteArray(Charsets.ISO_8859_1)
            val crc = CRC32().apply { update(t); update(body) }
            DataOutputStream(out).run { writeInt(body.size); write(t); write(body); writeInt(crc.value.toInt()) }
        }
        chunk("IHDR", ByteArrayOutputStream().also { DataOutputStream(it).run { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray())
        chunk("IDAT", packed.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun build(logo: Image?): ByteArray {
        if (logo != null && (logo.width != LOGO_W || logo.height != LOGO_H)) throw IOException("logo: ${logo.width}x${logo.height}, expected ${LOGO_W}x$LOGO_H")
        val count = (EXPECTED * FPS).toInt()
        val parts = linkedMapOf(
            "part0" to List(count) { png(BAR_W, BOX_H, frame(logo, fillWidth(it, count))) },
            "part1" to listOf(png(BAR_W, BOX_H, frame(logo, BAR_W))),
        )
        val zip = StoredZip()
        // Pauses are 0: a part is held by its count, never by a pause (bootanim.py's desc()).
        zip.put("desc.txt", "$SIZE $SIZE $FPS\np 1 0 part0\np 0 0 part1\n".toByteArray())
        for ((name, frames) in parts) {
            frames.forEachIndexed { i, data -> zip.put("$name/%04d.png".format(i), data) }
            zip.put("$name/trim.txt", "${BAR_W}x$BOX_H+$X0+$Y0\n".repeat(frames.size).toByteArray())
        }
        return zip.finish()
    }

    /**
     * A zip of stored entries as python's zipfile writes them from writestr on a seekable file:
     * version 2.0 made on Unix, dated 1980-01-01 00:00, mode 0644, no extra field, no data descriptor.
     */
    private class StoredZip {
        private val out = ByteArrayOutputStream()
        private val central = ByteArrayOutputStream()
        private var count = 0

        private fun ByteArrayOutputStream.le(v: Int, bytes: Int) { for (k in 0 until bytes) write(v ushr (8 * k)) }

        fun put(name: String, data: ByteArray) {
            val n = name.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(data) }.value.toInt()
            val offset = out.size()
            out.le(0x04034b50, 4); out.le(20, 1); out.le(0, 1); out.le(0, 2); out.le(0, 2)
            out.le(0, 2); out.le(33, 2) // time 00:00, date 1980-01-01
            out.le(crc, 4); out.le(data.size, 4); out.le(data.size, 4); out.le(n.size, 2); out.le(0, 2)
            out.write(n); out.write(data)
            central.le(0x02014b50, 4); central.le(20, 1); central.le(3, 1); central.le(20, 1); central.le(0, 1)
            central.le(0, 2); central.le(0, 2); central.le(0, 2); central.le(33, 2)
            central.le(crc, 4); central.le(data.size, 4); central.le(data.size, 4)
            central.le(n.size, 2); central.le(0, 2); central.le(0, 2); central.le(0, 2); central.le(0, 2)
            central.le(0x1A4 shl 16, 4) // 0644, as bootanim.py sets it (no file type bits)
            central.le(offset, 4)
            central.write(n)
            count++
        }

        fun finish(): ByteArray {
            val start = out.size()
            central.writeTo(out)
            out.le(0x06054b50, 4); out.le(0, 2); out.le(0, 2); out.le(count, 2); out.le(count, 2)
            out.le(central.size(), 4); out.le(start, 4); out.le(0, 2)
            return out.toByteArray()
        }
    }
}
