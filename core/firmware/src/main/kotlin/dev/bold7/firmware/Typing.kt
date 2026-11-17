package dev.bold7.firmware

import java.io.IOException
import java.text.Normalizer

/**
 * OS 7's part of Typing's lists, from the firmware, as tools/import-assets.sh saisie writes it from
 * assets-private/saisie (whose lists the study's scripts made: assets-private/saisie/scripts,
 * build.py and slft.py, ported here):
 *
 * - the English word substitution, the lines of misspelling_words_de.txt marked English (2);
 * - a word list's part: the words only the firmware has, at the AOSP frequency of the shared words
 *   with the same firmware score (the median, made non-decreasing), each line of them that AOSP's
 *   list (Typing's, in git) lacks, sorted as `LC_ALL=C sort`.
 */
internal object Typing {
    /** misspelling_words_de.txt: typed TAB replacement TAB language (2 English, 1 German). */
    fun substitution(data: ByteArray): String = buildString {
        for (line in String(data, Charsets.US_ASCII).lines()) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            if (f.size != 3) throw IOException("misspelling_words_de.txt: bad line $line")
            if (f[2] == "2") append(f[0]).append('\t').append(UNICODE.replace(f[1]) { it.groupValues[1].toInt(16).toChar().toString() }).append('\n')
        }
    }

    private val UNICODE = Regex("\\\\u([0-9A-Fa-f]{4})")

    /** The lines of the merged list that [aosp] (word TAB frequency, one per line) lacks. */
    fun part(parts: List<ByteArray>, aosp: String): String {
        val fw = decode(parts)
        val ao = LinkedHashMap<String, Int>()
        val lines = HashSet<String>()
        for (line in aosp.lineSequence()) {
            if (line.isEmpty()) continue
            lines += line
            val tab = line.indexOf('\t')
            if (tab > 0) ao[line.substring(0, tab)] = line.substring(tab + 1).toInt()
        }
        val m = mapping(fw, ao)
        val folded = ao.keys.mapTo(HashSet(), ::fold)
        val only = fw.filter { (w, _) -> w !in ao && fold(w) !in folded && !(w.length <= 2 && w == w.lowercase() && w != w.uppercase()) }
        return only.map { (w, s) -> "$w\t${m[s]}" }.filter { it !in lines }.sortedWith(UTF8).joinToString("") { "$it\n" }
    }

    /** `LC_ALL=C sort`: the bytes of the line, unsigned. */
    private val UTF8 = Comparator<String> { a, b ->
        val x = a.toByteArray()
        val y = b.toByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (c != 0) return@Comparator c
        }
        x.size - y.size
    }

    /** build.py mapping: score to the median AOSP frequency of the shared words, running maximum. */
    private fun mapping(fw: Map<String, Int>, ao: Map<String, Int>): IntArray {
        val by = HashMap<Int, MutableList<Int>>()
        for ((w, s) in fw) ao[w]?.let { by.getOrPut(s) { ArrayList() } += it }
        val m = IntArray(256)
        var cur = 0
        for (s in 0 until 256) {
            by[s]?.let { cur = maxOf(cur, median(it)) }
            m[s] = cur
        }
        return m
    }

    /** round(statistics.median()) of Python: the mean of the middle two, rounded half to even. */
    private fun median(v: MutableList<Int>): Int {
        v.sort()
        val n = v.size
        return if (n % 2 == 1) v[n / 2] else Math.rint((v[n / 2 - 1] + v[n / 2]) / 2.0).toInt()
    }

    /** Accents off (NFD, combining marks dropped) and case folded, as build.py fold. */
    fun fold(w: String): String {
        val d = Normalizer.normalize(w, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (c in d) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
        return sb.toString().lowercase().replace("ß", "ss")
    }

    /** slft.py: word to its best score (0 to 255), in the order the list holds them. */
    fun decode(parts: List<ByteArray>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        Slft(parts.fold(ByteArray(0)) { a, b -> a + b }).words { w, s -> if (s > (out[w] ?: -1)) out[w] = s }
        return out
    }
}

/**
 * A 'SLFT' word list of BlackBerry OS 7 (net_rim_tid_dynamic_ling_data_<lang>.cod, its <lang>.NNN.wrd
 * joined), after assets-private/saisie/scripts/slft.py, which documents the layout as observed.
 */
internal class Slft(private val b: ByteArray) {
    private fun u8(o: Int) = b[o].toInt() and 0xFF
    private fun u16(o: Int) = (u8(o) shl 8) or u8(o + 1)
    private fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()

    private val syms = ArrayList<String>()
    private val prim: String
    private val tab: LongArray
    private val data: Int

    init {
        if (b.size < 8 || String(b, 0, 4, Charsets.US_ASCII) != "SLFT") throw IOException("not an SLFT word list")
        val hdr = u16(4)
        val n = u16(hdr)
        val sec = HashMap<Int, Int>()
        for (i in 0 until n) sec[u16(hdr + 2 + 6 * i)] = u32(hdr + 4 + 6 * i).toInt()
        val base = hdr + 2 + 6 * n
        val s1 = base + sec.getValue(1)
        val na = u8(s1)
        val nprim = u8(s1 + 1)
        val alpha = String(b, s1 + 2, 2 * na, Charsets.UTF_16BE)
        alpha.forEach { syms += it.toString() }
        var o = s1 + 2 + 2 * na
        val s3 = base + sec.getValue(3)
        while (o < s3) {
            val c = u8(o)
            val ln = if (c and 0x80 != 0) 2 else 3
            o++
            repeat(c and 0x7F) {
                syms += String(b, o, 2 * ln, Charsets.UTF_16BE)
                o += 2 * ln
            }
        }
        prim = alpha.substring(0, nprim)
        tab = LongArray(nprim * nprim) { u32(s3 + 4 * it) }
        data = base + sec.getValue(4)
    }

    fun words(sink: (String, Int) -> Unit) {
        val n = prim.length
        for (i in tab.indices) {
            val s = (tab[i] and 0x7FFFFFFF).toInt()
            val end = if (i + 1 < tab.size) (tab[i + 1] and 0x7FFFFFFF).toInt() else b.size - data
            if (end <= s) continue
            val prefix = "${prim[i / n]}${prim[i % n]}"
            if (tab[i] and 0x80000000L != 0L) node(data + s, data + end, prefix, sink) else leaf(data + s, data + end, prefix, sink)
        }
    }

    private fun case(w: String, marks: List<Int>): String {
        var r = w
        for (m in marks) r = when (m) {
            1 -> r.take(1).uppercase() + r.drop(1)
            2 -> r.uppercase()
            4 -> r.indexOf('\'').let { i -> if (i > 0) r.substring(0, i).uppercase() + r.substring(i) else r.uppercase() }
            else -> r
        }
        return r
    }

    /** slft.py score: word j of a leaf from the bytes after its count byte. */
    private fun score(f: Int, j: Int, h: Int, c: Int): Int {
        fun at(k: Int) = u8(f + k)
        return if (h and 0x80 != 0) {
            if (c == 1) at(0) else Math.rint(at(0) * at(j + 2) / 255.0).toInt()
        } else {
            if (j == 0) at(0) else Math.rint(at(0) * at(j + 1) / 255.0).toInt()
        }
    }

    private fun leaf(from: Int, to: Int, prefix: String, sink: (String, Int) -> Unit) {
        val h = u8(from)
        val c = h and 0x7F
        val hb = if (h and 0x80 != 0) 2 else 1
        var o = from + 1 + hb + if (!(h and 0x80 != 0 && c == 1)) c else 0
        val freqs = from + 1
        var w = StringBuilder()
        val marks = ArrayList<Int>()
        var j = 0
        while (j < c) {
            val x = u8(o++)
            val v = x and 0x7F
            if (v >= 5) w.append(syms[v - 5]) else marks += v
            if (x and 0x80 != 0) {
                sink(case(prefix + w, marks), score(freqs, j, h, c))
                w = StringBuilder()
                marks.clear()
                j++
            }
        }
        if (o != to) throw IOException("SLFT: leaf $prefix ends at $o, not $to")
    }

    private fun node(from: Int, to: Int, prefix: String, sink: (String, Int) -> Unit) {
        val k = u8(from) and 0x7F
        var o = from + 1 + 2 * k
        val cnt = u8(o)
        val codes = IntArray(cnt) { u8(o + 1 + it) }
        o += 1 + cnt
        val offs = IntArray(cnt + 1) { u16(o + 2 * it) }
        o += 2 * (cnt + 1)
        val len = to - o
        for (i in 0..cnt) {
            val a = offs[i] and 0x7FFF
            val z = if (i < cnt) offs[i + 1] and 0x7FFF else len
            if (z <= a) continue
            val sub = if (i < cnt) syms[codes[i] - 5] else ""
            if (offs[i] and 0x8000 != 0) node(o + a, o + z, prefix + sub, sink) else leaf(o + a, o + z, prefix + sub, sink)
        }
    }
}
