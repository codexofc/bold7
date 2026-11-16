package dev.bold7.firmware

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * An NSIS 2 installer (ANSI), each file deflated on its own (not solid), as the simulator's: after
 * the Windows stub, at a multiple of 512 bytes, the first header (`0xDEADBEEF`, `NullsoftInst`),
 * then the install script's header and the files, each a block (length, its top bit for "deflated",
 * then raw deflate as NSIS's zlib writes it, [NsisInflate]). The script lists the files: `SetOutPath` sets the directory of the `File`
 * entries that follow, which name a file and its block. Read after NSIS's fileform.h and 7-Zip's
 * description of the format; nothing of the installer is run.
 */
internal object Nsis {
    class Entry(val dir: String, val name: String, val block: Long)

    class Archive(val data: Long, val files: List<Entry>)

    private const val MAX_HEADER = 16 shl 20
    private const val MAX_STRING = 4096

    // Opcodes of NSIS 2's script (fileform.h) and the codes of its ANSI strings.
    private const val EW_CREATEDIR = 11
    private const val EW_EXTRACTFILE = 20
    private const val NS_SKIP = 252
    private const val NS_VAR = 253
    private const val NS_SHELL = 254
    private const val NS_LANG = 255

    fun open(f: RandomAccessFile): Archive {
        val first = findFirstHeader(f)
        val fh = ByteArray(28).also { f.seek(first); f.readFully(it) }
        val length = u32(fh, 20)
        if (length < 36 || length > MAX_HEADER) throw IOException("NSIS header of $length bytes")
        val (header, end) = block(f, first + 28, length.toInt())
        if (header.size.toLong() != length) throw IOException("NSIS header: ${header.size} bytes of $length")
        // flags, then blocks of (offset, count): pages, sections, entries, strings, ...
        val entries = u32(header, 4 + 8 * 2)
        val count = u32(header, 8 + 8 * 2)
        val strings = u32(header, 4 + 8 * 3)
        if (entries + 28 * count > header.size || strings >= header.size) throw IOException("NSIS header: blocks out of range")
        val files = ArrayList<Entry>()
        var dir = ""
        for (i in 0 until count.toInt()) {
            val e = (entries + 28L * i).toInt()
            fun parm(k: Int) = u32(header, e + 4 + 4 * k)
            when (u32(header, e).toInt()) {
                EW_CREATEDIR -> if (parm(1) != 0L) dir = string(header, strings, parm(0))
                EW_EXTRACTFILE -> files += Entry(dir, string(header, strings, parm(1)), parm(2))
            }
        }
        return Archive(end, files)
    }

    /** The bytes of [e], refused past [max] once inflated. */
    fun read(f: RandomAccessFile, a: Archive, e: Entry, max: Int): ByteArray = block(f, a.data + e.block, max).first

    private fun findFirstHeader(f: RandomAccessFile): Long {
        val b = ByteArray(20)
        var pos = 0L
        while (pos + 28 <= f.length() && pos < MAX_HEADER) {
            f.seek(pos)
            f.readFully(b)
            if (u32(b, 4) == 0xDEADBEEFL && String(b, 8, 12, Charsets.ISO_8859_1) == "NullsoftInst") return pos
            pos += 512
        }
        throw IOException("not an NSIS installer")
    }

    /** The block at [pos], inflated when its top bit says so, and where the next one starts. */
    private fun block(f: RandomAccessFile, pos: Long, max: Int): Pair<ByteArray, Long> {
        if (pos < 0 || pos + 4 > f.length()) throw IOException("NSIS block at $pos past the end")
        val head = ByteArray(4).also { f.seek(pos); f.readFully(it) }
        val n = u32(head, 0)
        val size = n and 0x7FFFFFFF
        if (pos + 4 + size > f.length()) throw IOException("NSIS block at $pos of $size bytes past the end")
        // Deflate never grows data by much: twice the limit bounds what is held before inflating.
        val deflated = (n and 0x80000000L) != 0L
        if (size > (if (deflated) 2L * max else max.toLong())) throw IOException("NSIS block at $pos of $size bytes, over $max")
        val raw = ByteArray(size.toInt()).also { f.readFully(it) }
        val next = pos + 4 + size
        if (!deflated) return raw to next
        return try {
            NsisInflate.inflate(raw, max) to next
        } catch (e: IOException) {
            throw IOException("NSIS block at $pos: ${e.message}")
        }
    }

    /** A string of the script, its variables, shell folders and language strings as `$<code:index>`. */
    private fun string(header: ByteArray, strings: Long, offset: Long): String {
        var i = strings + offset
        if (offset < 0 || i >= header.size) throw IOException("NSIS string at $offset out of range")
        val s = StringBuilder()
        fun byte(k: Long) = if (k < header.size) header[k.toInt()].toInt() and 0xFF else throw IOException("NSIS string past the end")
        while (true) {
            if (s.length > MAX_STRING) throw IOException("NSIS string too long")
            val c = byte(i++)
            when (c) {
                0 -> return s.toString()
                NS_SKIP -> s.append(byte(i++).toChar())
                NS_VAR, NS_SHELL, NS_LANG -> {
                    val v = (byte(i) and 0x7F) or ((byte(i + 1) and 0x7F) shl 7)
                    i += 2
                    s.append("\$<$c:$v>")
                }
                else -> s.append(c.toChar())
            }
        }
    }
}

/**
 * The simulator: its `Java` directory (`SetOutPath $_OUTDIR\Java`, variable 31) holds the same
 * kind of .cod as the loader's CodFiles, 1785 of them; tools/extract-bbos7.py takes the .cod of `Java/`.
 */
internal class SimulatorUnpacker : Unpacker {
    override fun unpack(file: File, work: File, tree: File, progress: (Float) -> Unit) {
        RandomAccessFile(file, "r").use { f ->
            val nsis = Nsis.open(f)
            val cods = nsis.files.filter { it.dir.substringAfterLast('\\') == "Java" && it.name.endsWith(".cod") }
            if (cods.isEmpty()) throw IOException("no .cod in the simulator")
            cods.forEachIndexed { i, e ->
                // A plain file name: the script is the user's file, as any name in it.
                if ('\\' in e.name || '/' in e.name || e.name.startsWith(".")) throw IOException("unsafe .cod name ${e.name}")
                writeCod(e.name, Nsis.read(f, nsis, e, Cabinet.MAX_FILE), tree)
                progress((i + 1f) / cods.size)
            }
        }
    }
}
