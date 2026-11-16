package dev.bold7.firmware

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * The resources of a .cod file, after its observed layout (tools/extract-bbos7.py): a COD module,
 * or a zip of sibling modules (<name>-<n>.cod). A resource is a byte array of the module's data
 * section that its static initialiser registers (`2d 02 <len> <ptr>` pushes it, `28 <ptr>` its
 * name); one larger than a module is split into `__<name>@<offset>` chunks over the siblings.
 */
internal object Cod {
    private val SIBLING = Regex("-(\\d+)\\.cod$")
    private val CHUNK = Regex("__(.+)@(\\d+)")
    private val PART = Regex("(.+)\\.(\\d{3})")

    /** The COD modules of a .cod file: itself, or the siblings of its zip in numeric order. */
    fun modules(data: ByteArray): List<ByteArray> {
        if (data.size < 2 || data[0] != 'P'.code.toByte() || data[1] != 'K'.code.toByte()) return listOf(data)
        val entries = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(data)).use { z ->
            var total = 0L
            while (true) {
                val e = z.nextEntry ?: break
                val bytes = z.readBytes()
                total += bytes.size
                if (total > Cabinet.MAX_FILE) throw IOException("zipped .cod larger than ${Cabinet.MAX_FILE}")
                entries += e.name to bytes
            }
        }
        return entries.sortedBy { SIBLING.find(it.first)?.groupValues?.get(1)?.toLongOrNull() ?: -1L }.map { it.second }
    }

    /** A data section object: u16 length, u16 tag (0xd004 bytes, 0xd824 string), content. */
    private fun obj(data: ByteArray, from: Int, to: Int, ptr: Int, tag: Int): ByteArray? {
        if (ptr < 4 || ptr > to - from) return null
        val n = u16(data, from + ptr - 4)
        val t = u16(data, from + ptr - 2)
        return if (t == tag && ptr + n <= to - from) data.copyOfRange(from + ptr, from + ptr + n) else null
    }

    private fun isName(s: ByteArray) =
        s.isNotEmpty() && s[0] in 0x21..0x7E && s.all { it in 0x20..0x7E }

    /** (name, bytes) of the resources a COD module registers, in the order of its code. */
    fun moduleResources(mod: ByteArray): List<Pair<String, ByteArray>> {
        if (mod.size < 44 || u32(mod, 0) != 0xFFFFC0DEL) return emptyList()
        val codeEnd = minOf(44 + u16(mod, 38), mod.size)
        val dataFrom = codeEnd
        val dataTo = minOf(dataFrom + u16(mod, 40), mod.size)
        fun b(i: Int) = mod[i].toInt() and 0xFF
        fun at(i: Int) = u16(mod, i)
        val out = ArrayList<Pair<String, ByteArray>>()
        var i = 44
        while (i + 12 <= codeEnd) {
            // 2d 02 <len> <ptr> 56 3f 28 <name> 40
            if (b(i) != 0x2D || b(i + 1) != 0x02 || b(i + 6) != 0x56 || b(i + 7) != 0x3F || b(i + 8) != 0x28 || b(i + 11) != 0x40) {
                i++
                continue
            }
            val n = at(i + 2)
            val ptr = at(i + 4)
            val names = arrayListOf(at(i + 9))
            var pos = i + 12
            i = pos
            val blob = obj(mod, dataFrom, dataTo, ptr, 0xD004)
            if (blob == null || blob.size != n) continue
            // Further names: (01 ff ff 03 | any two bytes) cd 3f 28 <name> 40
            while (true) {
                pos = when {
                    pos + 10 <= codeEnd && b(pos) == 0x01 && b(pos + 1) == 0xFF && b(pos + 2) == 0xFF && b(pos + 3) == 0x03 &&
                        b(pos + 4) == 0xCD && b(pos + 5) == 0x3F && b(pos + 6) == 0x28 && b(pos + 9) == 0x40 -> {
                        names += at(pos + 7); pos + 10
                    }
                    pos + 8 <= codeEnd && b(pos + 2) == 0xCD && b(pos + 3) == 0x3F && b(pos + 4) == 0x28 && b(pos + 7) == 0x40 -> {
                        names += at(pos + 5); pos + 8
                    }
                    else -> break
                }
            }
            for (p in names) {
                val s = obj(mod, dataFrom, dataTo, p, 0xD824) ?: continue
                if (isName(s)) out += String(s, Charsets.US_ASCII) to blob
            }
        }
        return out
    }

    /**
     * {name: bytes} of a .cod file, chunks joined; a name without a path is dropped when the same
     * bytes exist under a path ending with that name.
     */
    fun resources(data: ByteArray): Map<String, ByteArray> {
        val named = LinkedHashMap<String, ByteArray>()
        val chunks = LinkedHashMap<String, MutableMap<Long, ByteArray>>()
        for (mod in modules(data)) {
            for ((name, blob) in moduleResources(mod)) {
                val m = CHUNK.matchEntire(name)
                if (m != null) chunks.getOrPut(m.groupValues[1]) { HashMap() }[m.groupValues[2].toLong()] = blob
                else named.putIfAbsent(name, blob)
            }
        }
        for ((name, parts) in chunks) {
            val joined = java.io.ByteArrayOutputStream()
            for (off in parts.keys.sorted()) {
                if (off != joined.size().toLong()) throw IOException("$name: chunk at $off, expected ${joined.size()}")
                joined.write(parts.getValue(off))
            }
            named[name] = joined.toByteArray()
        }
        val withPath = HashMap<String, MutableList<ByteArray>>()
        for ((name, blob) in named) if ('/' in name) withPath.getOrPut(name.substringAfterLast('/')) { ArrayList() } += blob
        return named.filter { (n, b) -> '/' in n || withPath[n].orEmpty().none { it.contentEquals(b) } }
    }

    /** TrueType fonts split in parts `<font>.001`, `.002`... joined into `<font>.ttf`, and RIM sff4. */
    fun fonts(resources: Map<String, ByteArray>): Map<String, ByteArray> {
        val parts = LinkedHashMap<String, MutableMap<Int, ByteArray>>()
        for ((name, blob) in resources) {
            val m = PART.matchEntire(name) ?: continue
            parts.getOrPut(m.groupValues[1]) { HashMap() }[m.groupValues[2].toInt()] = blob
        }
        val fonts = LinkedHashMap<String, ByteArray>()
        val signatures = listOf(byteArrayOf(0, 1, 0, 0), "OTTO".toByteArray(), "true".toByteArray())
        for ((base, p) in parts) {
            val first = p[1]?.copyOf(4) ?: continue
            if ((p[1]?.size ?: 0) < 4 || signatures.none { it.contentEquals(first) } || p.keys.sorted() != (1..p.size).toList()) continue
            val joined = java.io.ByteArrayOutputStream()
            for (k in p.keys.sorted()) joined.write(p.getValue(k))
            fonts[base.substringAfterLast('/') + ".ttf"] = joined.toByteArray()
        }
        for ((name, blob) in resources) {
            val lower = name.lowercase()
            if (lower.endsWith(".ttf") || lower.endsWith(".sff4")) fonts[name.substringAfterLast('/')] = blob
        }
        return fonts
    }
}
