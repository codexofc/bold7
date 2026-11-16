package dev.bold7.firmware

import java.io.IOException
import java.io.RandomAccessFile

/** Random access to bytes: a file, a stream inside a compound file, an array in the tests. */
internal interface Source {
    val size: Long

    /** Reads exactly [len] bytes at [pos], or throws. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int)

    fun bytes(pos: Long, len: Int): ByteArray = ByteArray(len).also { read(pos, it, 0, len) }
}

internal class ArraySource(private val a: ByteArray) : Source {
    override val size get() = a.size.toLong()
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
        if (pos < 0 || pos + len > a.size) throw IOException("read past the end ($pos + $len of ${a.size})")
        System.arraycopy(a, pos.toInt(), buf, off, len)
    }
}

/** Read-only OLE compound file (MS-CFB): its directory and its streams. */
internal class Compound(private val f: RandomAccessFile) {
    data class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    private val ss: Int
    private val mss: Int
    private val cutoff: Long
    private val fat: IntArray
    val entries: List<Entry>
    private val mini: ByteArray
    private val minifat: IntArray

    init {
        val h = ByteArray(512)
        f.seek(0)
        f.readFully(h)
        if (!h.copyOf(8).contentEquals(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())))
            throw IOException("not a compound file")
        val shift = u16(h, 0x1E)
        val miniShift = u16(h, 0x20)
        if (shift !in 9..12 || miniShift !in 6..9) throw IOException("compound file: sector shift $shift")
        ss = 1 shl shift
        mss = 1 shl miniShift
        val nfat = u32(h, 0x2C)
        val dir0 = u32(h, 0x30).toInt()
        cutoff = u32(h, 0x38)
        val minifat0 = u32(h, 0x3C).toInt()
        val nminifat = u32(h, 0x40)
        var sect = u32(h, 0x44).toInt()
        val ndifat = u32(h, 0x48)
        val sectors = (f.length() / ss).toInt()
        if (nfat > sectors || ndifat > sectors) throw IOException("compound file: bad header")
        val difat = ArrayList<Int>()
        for (i in 0 until 109) difat += u32(h, 0x4C + 4 * i).toInt()
        repeat(ndifat.toInt()) {
            val more = ints(sector(sect))
            for (i in 0 until more.size - 1) difat += more[i]
            sect = more.last()
        }
        val fatList = ArrayList<Int>()
        for (i in 0 until nfat.toInt()) fatList += ints(sector(difat[i])).asList()
        fat = fatList.toIntArray()
        val raw = chain(dir0, null)
        entries = (0 until raw.size / 128).map { i ->
            val o = i * 128
            val n = u16(raw, o + 0x40).coerceIn(2, 64)
            val start = u32(raw, o + 0x74).toInt()
            var size = u32(raw, o + 0x78) or (u32(raw, o + 0x7C) shl 32)
            if (ss == 512) size = size and 0xFFFFFFFFL
            Entry(String(raw, o, n - 2, Charsets.UTF_16LE), raw[o + 0x42].toInt(), start, size)
        }
        mini = chain(entries[0].start, entries[0].size)
        minifat = if (nminifat > 0) ints(chain(minifat0, null)) else IntArray(0)
    }

    /** The bytes of a stream held in memory: the MSI's tables, never the cabinet. */
    fun stream(e: Entry): ByteArray {
        if (e.size > MAX_IN_MEMORY) throw IOException("compound stream ${e.name} too large: ${e.size}")
        if (e.size >= cutoff) return chain(e.start, e.size)
        val out = ByteArray(e.size.toInt())
        var n = e.start
        var pos = 0
        while (pos < out.size) {
            if (end(n) || n >= minifat.size || (n + 1L) * mss > mini.size) throw IOException("compound file: bad mini chain")
            val k = minOf(mss, out.size - pos)
            System.arraycopy(mini, n * mss, out, pos, k)
            pos += k
            n = minifat[n]
        }
        return out
    }

    /** A large stream read in place, sector by sector (the cabinet, hundreds of MB). */
    fun source(e: Entry): Source {
        if (e.size < cutoff) return ArraySource(stream(e))
        val count = ((e.size + ss - 1) / ss).toInt()
        val sectors = IntArray(count)
        var n = e.start
        for (i in 0 until count) {
            if (end(n) || n >= fat.size) throw IOException("compound file: short chain for ${e.name}")
            sectors[i] = n
            n = fat[n]
        }
        return object : Source {
            override val size = e.size
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
                if (pos < 0 || pos + len > size) throw IOException("read past the end of ${e.name}")
                var p = pos
                var o = off
                var left = len
                while (left > 0) {
                    val i = (p / ss).toInt()
                    val within = (p % ss).toInt()
                    val k = minOf(left, ss - within)
                    f.seek((sectors[i] + 1L) * ss + within)
                    f.readFully(buf, o, k)
                    p += k; o += k; left -= k
                }
            }
        }
    }

    private fun end(n: Int) = Integer.toUnsignedLong(n) >= 0xFFFFFFFAL

    private fun sector(n: Int): ByteArray {
        val pos = (Integer.toUnsignedLong(n) + 1) * ss
        if (pos + ss > f.length()) throw IOException("compound file: sector $n past the end")
        return ByteArray(ss).also { f.seek(pos); f.readFully(it) }
    }

    private fun chain(start: Int, size: Long?): ByteArray {
        if (size != null && size > MAX_IN_MEMORY) throw IOException("compound stream too large: $size")
        val out = java.io.ByteArrayOutputStream()
        var n = start
        var count = 0
        while (!end(n) && (size == null || out.size() < size)) {
            if (n >= fat.size || ++count > fat.size) throw IOException("compound file: bad chain")
            out.write(sector(n))
            n = fat[n]
        }
        val all = out.toByteArray()
        return if (size == null) all else all.copyOf(minOf(size.toInt(), all.size))
    }

    private fun ints(b: ByteArray) = IntArray(b.size / 4) { u32(b, 4 * it).toInt() }

    private companion object {
        const val MAX_IN_MEMORY = 64L shl 20
    }
}

/** The MSI database of the loader: its streams by name, its string pool and its tables. */
internal class Msi(f: RandomAccessFile) {
    val cf = Compound(f)
    val streams: Map<String, Compound.Entry> = cf.entries.filter { it.type == 2 }.associateBy { msiName(it.name) }
    private val strings = ArrayList<String>()
    private val sw: Int
    private val columns = HashMap<String, MutableList<Triple<Int, String, Int>>>()

    init {
        val pool = cf.stream(stream("!_StringPool"))
        val data = cf.stream(stream("!_StringData"))
        sw = if ((u32(pool, 0) and 0x80000000L) != 0L) 3 else 2
        strings += ""
        var pos = 0
        var i = 4
        while (i + 4 <= pool.size) {
            var n = u16(pool, i)
            val refs = u16(pool, i + 2)
            i += 4
            if (n == 0 && refs != 0) { // a long string: its length is the next entry, as 32 bits
                n = u32(pool, i).toInt()
                i += 4
            }
            if (n < 0 || pos + n > data.size) throw IOException("MSI string pool past its data")
            strings += String(data, pos, n, charset("windows-1252"))
            pos += n
        }
        for (r in rows("_Columns", "sisi")) {
            columns.getOrPut(r[0] as String) { ArrayList() } += Triple(r[1] as Int, r[2] as String, r[3] as Int)
        }
    }

    fun stream(name: String) = streams[name] ?: throw IOException("MSI without stream $name")

    /** Rows of a table stored column by column; kinds: s (string), i (16 bits), l (32 bits). */
    private fun rows(table: String, kinds: String): List<List<Any?>> {
        val raw = cf.stream(stream("!$table"))
        val widths = kinds.map { if (it == 's') sw else if (it == 'i') 2 else 4 }
        val n = raw.size / widths.sum()
        val cols = ArrayList<List<Any?>>()
        var pos = 0
        for ((k, w) in kinds.toList().zip(widths)) {
            cols += List(n) {
                var v = 0L
                for (b in 0 until w) v = v or ((raw[pos + b].toLong() and 0xFF) shl (8 * b))
                pos += w
                when {
                    k == 's' -> strings.getOrNull(v.toInt()) ?: throw IOException("MSI string $v out of the pool")
                    v == 0L -> null
                    else -> (v xor (1L shl (8 * w - 1))).toInt()
                }
            }
        }
        return List(n) { r -> cols.map { it[r] } }
    }

    fun table(name: String): List<Map<String, Any?>> {
        val cols = (columns[name] ?: throw IOException("MSI without table $name")).sortedBy { it.first }
        val kinds = cols.joinToString("") {
            val typ = it.third xor 0x8000
            if ((typ and 0x0800) != 0) "s" else if ((typ and 0xFF) <= 2) "i" else "l"
        }
        return rows(name, kinds).map { r -> cols.map { it.second }.zip(r).toMap() }
    }

    companion object {
        private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._"

        /** Stream names of an MSI are packed two base-64 characters per UTF-16 unit. */
        fun msiName(name: String): String = buildString {
            for (ch in name) {
                val c = ch.code
                when {
                    c in 0x3800 until 0x4800 -> { append(CHARS[(c - 0x3800) and 0x3F]); append(CHARS[((c - 0x3800) shr 6) and 0x3F]) }
                    c in 0x4800 until 0x4840 -> append(CHARS[c - 0x4800])
                    c == 0x4840 -> append('!')
                    else -> append(ch)
                }
            }
        }
    }
}
