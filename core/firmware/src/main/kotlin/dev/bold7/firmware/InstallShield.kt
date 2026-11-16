package dev.bold7.firmware

import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * The InstallShield self-extracting container of the OS Loader, after ISx (lifenjoiner), as
 * tools/extract-bbos7.py reads it. Header: signature (14 bytes), u16 file count, 30 bytes. Each
 * file: name (260 bytes), u32 flags, u32, u32 size, 8 bytes, u16, 30 bytes, then its data,
 * obfuscated by a key derived from its name in blocks of 1024 bytes, then zlib-compressed.
 */
internal object InstallShield {
    private val SIG = "InstallShield\u0000".toByteArray(Charsets.ISO_8859_1)
    private val MAGIC = intArrayOf(0x13, 0x35, 0x86, 0x07)
    private const val HEAD = 0x2E
    private const val ATTR = 312
    const val BLOCK = 1024

    data class Entry(val name: String, val offset: Long, val size: Long)

    fun entries(f: RandomAccessFile, base: Long): List<Entry> {
        val head = ByteArray(HEAD)
        f.seek(base)
        f.readFully(head)
        if (!head.copyOf(SIG.size).contentEquals(SIG)) throw IOException("not an InstallShield container")
        val count = u16(head, 14)
        if (count == 0) throw IOException("empty InstallShield container")
        var pos = base + HEAD
        val attr = ByteArray(ATTR)
        return List(count) {
            f.seek(pos)
            f.readFully(attr)
            val end = (0 until 260).firstOrNull { attr[it] == 0.toByte() } ?: 260
            val name = String(attr, 0, end, Charsets.ISO_8859_1)
            val flags = u32(attr, 260)
            val size = u32(attr, 268)
            if ((flags and 6L) != 6L || name.isEmpty() || '/' in name || '\\' in name || name == "." || name == "..")
                throw IOException("unexpected InstallShield entry $name (flags $flags)")
            if (pos + ATTR + size > f.length()) throw IOException("InstallShield entry $name past the end")
            Entry(name, pos + ATTR, size).also { pos += ATTR + size }
        }
    }

    /** The container's offset, the first signature in the first 4 MiB whose entries read. */
    fun find(f: RandomAccessFile): Long {
        val head = ByteArray(minOf(f.length(), 4L shl 20).toInt())
        f.seek(0)
        f.readFully(head)
        var i = indexOf(head, SIG, 0)
        while (i >= 0) {
            try {
                entries(f, i.toLong())
                return i.toLong()
            } catch (_: IOException) {
                i = indexOf(head, SIG, i + 1)
            }
        }
        throw IOException("no InstallShield container in the first 4 MiB")
    }

    /** The key of [name], inverted and repeated over a whole block: decoding is then one xor. */
    fun key(name: String): ByteArray {
        val k = name.toByteArray(Charsets.ISO_8859_1)
        if (k.isEmpty()) throw IOException("InstallShield entry without a name")
        return ByteArray(BLOCK) { i -> (k[i % k.size].toInt() xor MAGIC[i % k.size % 4]).inv().toByte() }
    }

    private val ROT4 = ByteArray(256) { b -> (((b shl 4) or (b ushr 4)) and 0xFF).toByte() }

    /** One block in place: b -> ~(key ^ rot4(b)), the key restarting at each block. */
    fun decodeBlock(block: ByteArray, length: Int, key: ByteArray) {
        for (i in 0 until length) block[i] = (ROT4[block[i].toInt() and 0xFF].toInt() xor key[i].toInt()).toByte()
    }

    /** Decodes then inflates [entry] into [out]; [read] counts the bytes of the container read. */
    fun extract(f: RandomAccessFile, entry: Entry, out: OutputStream, read: (Int) -> Unit) {
        val key = key(entry.name)
        val block = ByteArray(BLOCK)
        val buf = ByteArray(1 shl 16)
        val z = Inflater()
        try {
            f.seek(entry.offset)
            var left = entry.size
            while (left > 0) {
                val n = minOf(BLOCK.toLong(), left).toInt()
                f.readFully(block, 0, n)
                left -= n
                decodeBlock(block, n, key)
                z.setInput(block, 0, n)
                while (!z.needsInput() && !z.finished()) {
                    val k = z.inflate(buf)
                    if (k == 0 && z.needsDictionary()) throw IOException("${entry.name}: unexpected zlib dictionary")
                    out.write(buf, 0, k)
                }
                read(n)
            }
            if (!z.finished()) throw IOException("${entry.name}: truncated zlib stream")
        } finally {
            z.end()
        }
    }

    private fun indexOf(a: ByteArray, p: ByteArray, from: Int): Int {
        outer@ for (i in from..a.size - p.size) {
            for (j in p.indices) if (a[i + j] != p[j]) continue@outer
            return i
        }
        return -1
    }
}

/**
 * Reads one entry of the container. Its deobfuscation is the only hand-written loop over the whole
 * loader (260 MB) and the slowest step without a JIT: if it ever
 * makes the extraction on the Q25 exceed the installer's one-minute budget, a native reader
 * takes the place of [InstallShield.extract] here, the rest unchanged.
 */
internal fun interface EntryReader {
    fun extract(f: RandomAccessFile, entry: InstallShield.Entry, out: OutputStream, read: (Int) -> Unit)
}

internal fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

internal fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
