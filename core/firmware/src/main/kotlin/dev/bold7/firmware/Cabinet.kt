package dev.bold7.firmware

import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A Microsoft cabinet (MSZIP only, as the loader's Data1.cab), read in one pass: each folder's
 * blocks are inflated in order, and only the wanted files are kept, one at a time, so the memory
 * stays that of the largest wanted file (a .cod of 6 MB), whatever the cabinet's size.
 */
internal object Cabinet {
    /** No .cod is near this: a larger wanted file is refused rather than held in memory. */
    const val MAX_FILE = 64 shl 20

    private class Item(val name: String, val offset: Long, val size: Int) {
        var data: ByteArray? = null
        var filled = 0
    }

    /**
     * Calls [onFile] with each file whose name [wanted] accepts, in the cabinet's order;
     * [read] counts the compressed bytes consumed.
     */
    fun extract(
        src: Source,
        wanted: (String) -> Boolean,
        read: (Long) -> Unit,
        onFile: (String, ByteArray) -> Unit,
    ) {
        val head = src.bytes(0, 36)
        if (String(head, 0, 4, Charsets.ISO_8859_1) != "MSCF") throw IOException("not a cabinet")
        val filesOff = u32(head, 16)
        val nfolders = u16(head, 26)
        val nfiles = u16(head, 28)
        val flags = u16(head, 30)
        if (flags != 0) throw IOException("cabinet flags $flags not handled")
        val folders = (0 until nfolders).map { i ->
            val b = src.bytes(36L + 8 * i, 8)
            Triple(u32(b, 0), u16(b, 4), u16(b, 6))
        }
        val byFolder = sortedMapOf<Int, MutableList<Item>>()
        var pos = filesOff
        repeat(nfiles) {
            val b = src.bytes(pos, 16)
            pos += 16
            val size = u32(b, 0)
            val off = u32(b, 4)
            val folder = u16(b, 8)
            val attr = u16(b, 14)
            val name = java.io.ByteArrayOutputStream()
            while (true) {
                val c = src.bytes(pos++, 1)[0]
                if (c == 0.toByte()) break
                if (name.size() > 1024) throw IOException("cabinet: file name too long")
                name.write(c.toInt())
            }
            val s = String(name.toByteArray(), if ((attr and 0x80) != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
            if (wanted(s)) {
                if (folder >= nfolders) throw IOException("cabinet: $s in folder $folder of $nfolders")
                if (size > MAX_FILE) throw IOException("cabinet: $s too large ($size bytes)")
                byFolder.getOrPut(folder) { ArrayList() } += Item(s, off, size.toInt())
            }
        }
        val out = ByteArray(MAX_BLOCK)
        val decoder = Mszip()
        for ((folder, list) in byFolder) {
            val (start, nblocks, typ) = folders[folder]
            if ((typ and 0xF) != 1) throw IOException("cabinet compression $typ not handled")
            val items = list.sortedBy { it.offset }.toMutableList()
            var at = start
            var done = 0L // uncompressed offset of the current block
            var prev = 0 // length of the previous block's output, the next one's dictionary
            val dict = ByteArray(MAX_BLOCK)
            for (b in 0 until nblocks) {
                if (items.isEmpty()) break
                val h = src.bytes(at, 8)
                val csize = u16(h, 4)
                val usize = u16(h, 6)
                if (csize < 2 || usize > MAX_BLOCK) throw IOException("cabinet: bad block $b")
                val block = src.bytes(at + 8, csize)
                at += 8 + csize
                if (block[0] != 'C'.code.toByte() || block[1] != 'K'.code.toByte()) throw IOException("MSZIP block expected")
                decoder.decode(block, csize, dict, prev, out, usize)
                read(8L + csize)
                // Hand the block's bytes to every item it covers.
                val it = items.iterator()
                while (it.hasNext()) {
                    val item = it.next()
                    if (item.offset >= done + usize) break
                    val from = maxOf(item.offset, done)
                    val to = minOf(item.offset + item.size, done + usize)
                    if (from < to) {
                        val data = item.data ?: ByteArray(item.size).also { d -> item.data = d }
                        System.arraycopy(out, (from - done).toInt(), data, (from - item.offset).toInt(), (to - from).toInt())
                        item.filled += (to - from).toInt()
                    }
                    if (item.filled == item.size) {
                        onFile(item.name, item.data ?: ByteArray(0))
                        item.data = null
                        it.remove()
                    }
                }
                System.arraycopy(out, 0, dict, 0, usize)
                prev = usize
                done += usize
            }
            if (items.isNotEmpty()) throw IOException("cabinet: ${items.first().name} past the end of its folder")
        }
    }

    const val MAX_BLOCK = 32768 + 6144
}

/**
 * Inflates one MSZIP block ("CK" then raw deflate) into [out], the previous block's output as its
 * dictionary: the JDK's zlib, native on the Mac and on Android alike.
 */
private class Mszip {
    private val z = Inflater(true)

    fun decode(block: ByteArray, length: Int, dict: ByteArray, dictLength: Int, out: ByteArray, usize: Int) {
        z.reset()
        if (dictLength > 0) z.setDictionary(dict, 0, dictLength)
        z.setInput(block, 2, length - 2)
        var n = 0
        try {
            while (n < usize && !z.finished()) {
                val k = z.inflate(out, n, usize - n)
                if (k == 0 && (z.needsInput() || z.needsDictionary())) break
                n += k
            }
        } catch (e: DataFormatException) {
            throw IOException("MSZIP block: ${e.message}")
        }
        if (n != usize) throw IOException("MSZIP block size $n, expected $usize")
    }
}
