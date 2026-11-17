package dev.bold7.firmware

import java.io.ByteArrayOutputStream
import java.io.IOException

/*
 * A reader of RIM's "YK" compression, the format of the 9900's help pages (`.html.ykz`) and of
 * their shared dictionary (`sidedata.data`), written for Bold7 so that the help in the user's own
 * file of the 9900 can be read on their phone (interoperability).
 *
 * Where the knowledge comes from: no description of RIM's bit stream is public. It was worked out
 * from the behaviour of RIM's decoder (YKDecode, native code of the 9900's Java VM) as shipped in
 * the official BlackBerry Simulator 7.1 for the 9900: its x86 code was disassembled and run in an
 * emulator on the help of the 9900, outside this repository, and every page this file decodes was
 * checked byte for byte against it. None of RIM's code is copied here. The format is written up in
 * docs/YK.md; the method is Yang and Kieffer's grammar-based compression ("Efficient universal
 * lossless data compression algorithms based on a greedy sequential grammar transform", IEEE
 * Trans. Inf. Theory, 2000) with a range coder after Michael Schindler's.
 */

/**
 * A grammar built once from a side-data file, then the decoder of every page compressed against
 * it. The grammar is fixed after [load]; a page only adapts its own copy of the models.
 */
class YkDictionary internal constructor(
    private val strings: Array<ByteArray>,
    private val followers: Array<IntArray>,
    private val start: Models,
    private val history: Int,
    private val previous: Int,
) {
    /** How many symbols the side data defined beyond the 256 bytes and the escape. */
    val symbols: Int get() = strings.size - FIRST_RULE

    /** The text of one compressed page; throws [IOException] on a stream this dictionary cannot read. */
    fun decode(file: ByteArray): ByteArray {
        val rc = RangeDecoder(blocks(file))
        val m = start.copy()
        val out = ByteArrayOutputStream(4096)
        var hist = history
        var prev = previous
        while (true) {
            val bit = rc.bit(m.repeats, (hist and 0x3f) * 2)
            hist = (hist shl 1 or bit) and 0xff
            val sym = if (bit == 0) {
                var s = m.alphabet.decode(rc)
                if (s == ESCAPE) {
                    when (rc.escapeCount()) {
                        0 -> break
                        3 -> s = m.alphabet.decode(rc).also { if (it <= ESCAPE) throw IOException("YK: bad escaped symbol") }
                        else -> throw IOException("YK: unknown escape in a page")
                    }
                }
                s
            } else {
                val f = followers.getOrNull(prev) ?: throw IOException("YK: no symbol before a repeat")
                if (f.isEmpty()) throw IOException("YK: repeat of a symbol never followed")
                f[rc.uniform(f.size / 3) * 3]
            }
            val text = strings.getOrNull(sym)?.takeIf { sym != ESCAPE } ?: throw IOException("YK: symbol $sym not in the side data")
            out.write(text)
            prev = sym
        }
        var cut = 0
        repeat(16) { cut = cut shl 1 or rc.bit(m.tail, 0) }
        if (cut != 0) {
            val sym = m.alphabet.decode(rc)
            val text = strings.getOrNull(sym)?.takeIf { sym != ESCAPE && it.size >= cut } ?: throw IOException("YK: bad last phrase")
            out.write(text, 0, cut)
        }
        return out.toByteArray()
    }

    companion object {
        /** Builds the grammar of a side-data file; throws [IOException] if it is not one. */
        fun load(file: ByteArray): YkDictionary = Builder().apply { run(blocks(file)) }.finish()
    }
}

/**
 * Side data that ends like a page, leaving the grammar to grow with each page (docs/YK.md, section 7):
 * the help of the simulator 7.1.0.318 is so, the OS Loader's is not. Not read.
 */
class YkAdaptiveException : IOException("YK: side data whose pages grow the grammar, not supported")

private const val ESCAPE = 0x100
private const val FIRST_RULE = 0x101
private const val HOLE = 0xffff
/** The start rule, as the rule of a follower entry. */
private const val START = 0xffff
private const val MAX_START = 0xffff

/** The data of the 0x84 blocks of a YK file, which ends with an 0x83 block. */
internal fun blocks(file: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(file.size)
    var i = 0
    while (true) {
        if (i + 4 > file.size) throw IOException("YK: no end block")
        val type = file[i].toInt() and 0xff
        val n = (file[i + 1].toInt() and 0xff shl 16) or (file[i + 2].toInt() and 0xff shl 8) or (file[i + 3].toInt() and 0xff)
        i += 4
        when (type) {
            0x83 -> return out.toByteArray()
            0x84 -> {
                if (i + n > file.size) throw IOException("YK: block cut short")
                out.write(file, i, n)
                i += n
            }
            else -> throw IOException("YK: block type %02x".format(type))
        }
    }
}

/**
 * Range decoder: 32-bit code value and range, refilled a byte at a time while the range is at most
 * 2^23, each byte's low bit carried into the next. Past the end of the data it reads zeros.
 */
internal class RangeDecoder(private val data: ByteArray, private var pos: Int = 0) {
    private var carry = next()
    private var low = (carry shr 1).toLong()
    private var range = 0x80L

    init { refill() }

    private fun next(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else { pos++; 0 }

    private fun refill() {
        while (range <= 0x800000L) {
            low = ((low shl 8) or ((carry and 1).toLong() shl 7)) and 0xffffffffL
            carry = next()
            low = low or (carry shr 1).toLong()
            range = range shl 8
        }
    }

    /** A bit with the adaptive counts of zeros and ones at [c] and [c] + 1, halved past 254. */
    fun bit(counts: IntArray, c: Int): Int {
        val zeros = counts[c]
        val split = range / (zeros + counts[c + 1]) * zeros
        val b: Int
        if (low < split) {
            range = split; counts[c]++; b = 0
        } else {
            low -= split; range -= split; counts[c + 1]++; b = 1
        }
        if (counts[c] + counts[c + 1] > 0xfe) {
            counts[c] = (counts[c] + 1) shr 1
            counts[c + 1] = (counts[c + 1] + 1) shr 1
        }
        refill()
        return b
    }

    /** A bit of probability one half, nothing learnt. */
    fun flatBit(): Int {
        val half = range shr 1
        val b = if (low < half) { range = half; 0 } else { low -= half; range -= half; 1 }
        refill()
        return b
    }

    /** Count of one bits before a zero, at most five: what follows an escape symbol. */
    fun escapeCount(): Int {
        var n = 0
        while (flatBit() == 1 && n <= 4) n++
        return n
    }

    /**
     * A symbol of a frequency table: [at] is the count of all symbols (at [base]), then for
     * k = 1, 2… the count of the symbols from k on, ending with 0. Symbol 0 takes the rounding
     * left over at the top. With [learn], the counts of the symbol found and of the total go up.
     */
    fun cumulative(at: IntArray, base: Int, learn: Boolean): Int {
        val total = at[base]
        val unit = range / total
        val v = low / unit
        var k = 1
        var above = total
        while (v < at[base + k]) {
            above = at[base + k]
            if (learn) at[base + k]++
            k++
        }
        val from = at[base + k].toLong()
        low -= unit * from
        if (above < total) range = unit * above
        range -= unit * from
        refill()
        return k - 1
    }

    /** One of [n] equally likely choices. */
    fun uniform(n: Int): Int {
        val table = IntArray(n + 1) { n - it }
        return cumulative(table, 0, false)
    }
}

/**
 * The adaptive model of the symbols: groups of sixteen, each a frequency table, chosen by a binary
 * tree of adaptive bits over the groups. A symbol is its index, added in order of creation.
 */
internal class Alphabet(
    var size: Int = 0,
    var groups: IntArray = IntArray(17 * 32),
    var nodes: IntArray = IntArray(64),
) {
    private val groupCount get() = (size + 15) / 16
    private val nodeCount get() = maxOf(0, groupCount - 1)
    private val depth get() = 32 - Integer.numberOfLeadingZeros(maxOf(0, groupCount - 1))

    fun copy() = Alphabet(size, groups.copyOf(), nodes.copyOf())

    fun add() {
        val k = size and 15
        if (k == 0) {
            val g = groupCount
            if ((g + 1) * 17 > groups.size) groups = groups.copyOf(groups.size * 2)
            groups.fill(0, g * 17, g * 17 + 17)
            groups[g * 17] = 1
            if (g >= 1) {
                if (2 * g > nodes.size) nodes = nodes.copyOf(nodes.size * 2)
                nodes[2 * (g - 1)] = 1
                nodes[2 * (g - 1) + 1] = 1
            }
        } else {
            val t = (groupCount - 1) * 17
            var i = 1
            while (groups[t + i] != 0) groups[t + i++]++
            groups[t + i]++
            groups[t]++
            if (groups[t] == 0x3fff) {
                i = 0
                while (groups[t + i] != 0) { groups[t + i] = (groups[t + i] + 1) shr 1; i++ }
            }
        }
        size++
    }

    fun decode(rc: RangeDecoder): Int {
        var group = 0
        var c = depth
        if (c > 0) {
            var node = (1 shl (c - 1)) - 1
            var b = rc.bit(nodes, 2 * node)
            group = b
            c--
            walk@ while (c > 0) {
                if (b == 0) {
                    node -= 1 shl (c - 1)
                } else {
                    node += 1 shl (c - 1)
                    while (node >= nodeCount) { // no such node: the bit is 0
                        group = group shl 1
                        if (--c == 0) break@walk
                        node -= 1 shl (c - 1)
                    }
                }
                b = rc.bit(nodes, 2 * node)
                group = group shl 1 or b
                c--
            }
        }
        if (group >= groupCount) throw IOException("YK: group $group of $groupCount")
        val t = group * 17
        val inGroup = rc.cumulative(groups, t, true)
        groups[t]++
        if (groups[t] == 0x3fff) halve(t)
        val s = group shl 4 or inGroup
        if (s >= size) throw IOException("YK: symbol $s of $size")
        return s
    }

    /** Halves a table that reached 0x3fff, every count kept above the next. */
    private fun halve(t: Int) {
        var last = 0
        while (groups[t + last + 1] != 0) last++
        var lift = 0
        for (j in last downTo 0) {
            groups[t + j] = ((groups[t + j] + 1) shr 1) + lift
            if (groups[t + j] == groups[t + j + 1]) { groups[t + j]++; lift++ }
        }
    }
}

/** What a page starts from: the counts the side data left. */
internal class Models(val repeats: IntArray, val tail: IntArray, val alphabet: Alphabet) {
    fun copy() = Models(repeats.copyOf(), tail.copyOf(), alphabet.copy())
}

/** A growable list of ints. */
private class Ints(capacity: Int = 4) {
    var a = IntArray(capacity)
    var n = 0
    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Int) { a[i] = v }
    fun add(v: Int) { if (n == a.size) a = a.copyOf(maxOf(4, n * 2)); a[n++] = v }
    fun toArray() = a.copyOf(n)
}

/**
 * Reads the side data: the grammar grows as it decodes. The start rule is a list of symbols; every
 * other rule is a symbol's list. Both keep holes (0xffff) where a pair became a new symbol, so that
 * positions stay put. For each symbol, its followers: (symbol after it, rule, position in that rule)
 * for every pair the grammar holds, the most recent pair of the start rule excepted.
 */
private class Builder {
    val strings = ArrayList<ByteArray?>()
    val rules = ArrayList<Ints?>()
    val follow = ArrayList<Ints>()
    val s0 = Ints(1024)
    val models = Models(IntArray(128) { 1 }, intArrayOf(1, 1), Alphabet())
    var hist = 0
    var previous = 0

    // The repeat being read: where its first copy is, its length so far, the position of its last symbol.
    var repeatRule = 0
    var repeatFrom = 0
    var repeatLength = 0
    var repeatLast = 0

    init {
        for (c in 0 until 256) newSymbol(byteArrayOf(c.toByte()))
        newSymbol(null) // the escape
    }

    fun newSymbol(text: ByteArray?) {
        strings += text
        rules += null
        follow += Ints(3)
        models.alphabet.add()
    }

    fun rule(r: Int): Ints = if (r == START) s0 else rules[r] ?: throw IOException("YK: rule of a pair symbol")

    fun next(r: Ints, p: Int): Int { var q = p + 1; while (r[q] == HOLE) q++; return q }
    fun prev(r: Ints, p: Int): Int { var q = p - 1; while (r[q] == HOLE) q--; return q }

    fun lastWith(f: Ints, sym: Int): Int { var i = f.n - 3; while (i >= 0 && f[i] != sym) i -= 3; return i }

    /** The entry of the pair (owner of [f], [sym]) at [pos]: the last with [sym], or its neighbour. */
    fun entry(f: Ints, sym: Int, pos: Int): Int {
        var i = lastWith(f, sym)
        if (i < 0) return -1
        if (pos < f[i + 2]) i -= 3 else if (f[i + 2] < pos) i += 3
        if (i < 0 || i >= f.n) throw IOException("YK: lost pair")
        return i
    }

    fun remove(f: Ints, i: Int) {
        System.arraycopy(f.a, i + 3, f.a, i, f.n - i - 3)
        f.n -= 3
    }

    fun run(p: ByteArray) {
        if (p.size < 4) throw IOException("YK: side data too short")
        val a = (p[0].toInt() and 0xff shl 8) or (p[1].toInt() and 0xff)
        val b = (p[2].toInt() and 0xff shl 8) or (p[3].toInt() and 0xff)
        if (a > 0xff || b > 0xff) throw IOException("YK: not side data")
        s0.add(a); s0.add(b)
        val rc = RangeDecoder(p, 4)
        val alphabet = models.alphabet
        while (true) {
            val bit = rc.bit(models.repeats, (hist and 0x3f) * 2)
            hist = (hist shl 1 or bit) and 0xff
            if (bit == 1) {
                if (hist and 2 == 0) startRepeat(rc) else growRepeat()
                continue
            }
            val sym = alphabet.decode(rc)
            if (sym != ESCAPE) {
                if (hist and 2 != 0) endRepeat()
                append(sym)
                continue
            }
            when (rc.escapeCount()) {
                4 -> {}
                0 -> throw YkAdaptiveException()
                else -> throw IOException("YK: unknown escape in side data")
            }
            if (strings.size > FIRST_RULE && strings.last() == null) throw IOException("YK: side data ends inside a repeat")
            hist = hist shr 1
            previous = 4 // what the side data leaves as the symbol before a page
            return
        }
    }

    fun startRepeat(rc: RangeDecoder) {
        if (s0.n < 2) throw IOException("YK: repeat too early")
        val f = follow[s0[s0.n - 1]]
        if (f.n == 0) throw IOException("YK: repeat of a symbol never followed")
        val i = rc.uniform(f.n / 3) * 3
        repeatRule = f[i + 1]
        repeatFrom = f[i + 2]
        repeatLength = 2
        repeatLast = next(rule(repeatRule), repeatFrom)
        newSymbol(null)
    }

    fun growRepeat() {
        val r = rule(repeatRule)
        val limit = if (repeatRule == START) r.n - 2 else r.n - 1
        if (repeatLast >= limit) throw IOException("YK: repeat past its rule")
        val q = next(r, repeatLast)
        if (r.n - 1 <= q - repeatFrom) throw IOException("YK: repeat of a whole rule")
        repeatLast = q
        repeatLength++
    }

    /** Appends a symbol to the start rule; the pair it ends is not recorded until the next one. */
    fun append(sym: Int) {
        if (s0.n >= MAX_START) throw IOException("YK: start rule full")
        val last = s0[s0.n - 1]
        val before = prev(s0, s0.n - 1)
        if (lastWith(follow[last], sym) >= 0) throw IOException("YK: known pair sent as a symbol")
        val f = follow[s0[before]]
        f.add(last); f.add(START); f.add(before)
        s0.add(sym)
    }

    /** The repeat is complete: it becomes the new symbol, in its first copy and at the end of the start rule. */
    fun endRepeat() {
        val r = rule(repeatRule)
        val body = Ints(repeatLength)
        var p = repeatFrom
        body.add(r[p])
        repeat(repeatLength - 1) { p = next(r, p); body.add(r[p]) }
        val n = strings.size - 1
        val text = ByteArrayOutputStream()
        for (i in 0 until body.n) text.write(strings[body[i]]!!)
        strings[n] = text.toByteArray()
        rules[n] = body
        var overlap = false
        if (repeatLast < r.n - 1) {
            val x = r[repeatLast]
            overlap = x == r[prev(r, repeatLast)] && x == r[next(r, repeatLast)]
        }
        substitute(n, body, overlap)
    }

    fun substitute(n: Int, body: Ints, overlap: Boolean) {
        val first = follow[body[0]]
        var e = entry(first, body[1], repeatFrom)
        if (e < 0) throw IOException("YK: repeat without its pair")
        val ruleId = first[e + 1]
        val at = first[e + 2]
        val r = rule(ruleId)
        val length = r.n
        if (r[at] != body[0] || r[at] != s0[s0.n - 1]) throw IOException("YK: repeat not where its pair says")
        var before = 0
        if (at != 0) {
            before = prev(r, at)
            val g = r[before]
            val gf = follow[g]
            val ge = entry(gf, body[0], before)
            gf[ge] = n
            if (g == body[0]) e = entry(first, body[1], repeatFrom)
        }
        var end = next(r, at)
        if (body.n == 2) {
            remove(first, e)
        } else {
            first[e + 1] = n
            first[e + 2] = 0
            for (i in 1 until body.n - 1) {
                val nx = next(r, end)
                val f = follow[r[end]]
                val k = entry(f, r[nx], end)
                r[end] = HOLE
                f[k + 1] = n
                f[k + 2] = i
                end = nx
            }
        }
        if (length - 1 <= end) {
            // The copy ended its rule: the rule now ends with the new symbol.
            if (ruleId == START || at == 0) throw IOException("YK: repeat of a whole rule")
            r.n = at + 1
            if (before == 0) {
                val f = follow[r[0]]
                remove(f, entry(f, n, 0))
            }
        } else {
            val nx = next(r, end)
            val k = if (overlap && ruleId == START && end == s0.n - 2) -1 else entry(follow[r[end]], r[nx], end)
            if (k < 0) {
                if (ruleId != START) throw IOException("YK: pair after a repeat not found")
                s0.n = at + 2
            } else {
                remove(follow[r[end]], k)
                r[end] = HOLE
                if (!(at == 0 && nx == length - 1)) {
                    val f = follow[n]
                    f.add(r[nx]); f.add(ruleId); f.add(at)
                }
            }
        }
        r[at] = n
        s0[s0.n - 1] = n
    }

    fun finish(): YkDictionary {
        val text = Array(strings.size) { strings[it] ?: ByteArray(0) }
        // A page only needs each follower's symbol, three ints apart as here.
        val f = Array(follow.size) { follow[it].toArray() }
        return YkDictionary(text, f, models, hist, previous)
    }
}
