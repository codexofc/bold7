package dev.bold7.saisie

/**
 * A word list (AOSP's, with OS 7's part laid over it) in one sorted block: the words end to
 * end in one String, their starts and their frequencies (0 to 255) in two arrays, about 4 MB for
 * the French list's 200 000 words. Built once, off the main thread; read by binary search.
 */
class WordList private constructor(private val text: String, private val starts: IntArray, private val freqs: ByteArray) : Lexicon {
    val size get() = freqs.size

    /** The word's frequency (0 to 255), or that of its first letter made small (a capital typed at the start of a sentence); -1 if absent. */
    override fun frequency(word: String): Int {
        val i = find(word).takeIf { it >= 0 } ?: word.replaceFirstChar { it.lowercaseChar() }.takeIf { it != word }?.let(::find) ?: -1
        return if (i < 0) -1 else freqs[i].toInt() and 0xff
    }

    /**
     * Up to [max] words starting with [prefix], the most frequent first (alphabetical between equals).
     * One pass over the words of the prefix, keeping the best [max]: a single letter is a tenth of
     * the list, too many to sort at each key.
     */
    override fun complete(prefix: String, max: Int): List<String> {
        val best = IntArray(max)
        var n = 0
        var i = lowerBound(prefix)
        while (i < size && text.startsWith(prefix, starts[i]) && starts[i] + prefix.length <= end(i)) {
            val f = freqs[i].toInt() and 0xff
            var k = n
            while (k > 0 && f > (freqs[best[k - 1]].toInt() and 0xff)) k--
            if (k < max) {
                if (n < max) n++
                System.arraycopy(best, k, best, k + 1, n - 1 - k)
                best[k] = i
            }
            i++
        }
        return List(n) { word(best[it]) }
    }

    private fun end(i: Int) = starts[i + 1] - 1
    private fun word(i: Int) = text.substring(starts[i], end(i))

    /** text's word i against [s], as String.compareTo. */
    private fun compare(i: Int, s: String): Int {
        val a = starts[i]
        val n = end(i) - a
        for (k in 0 until minOf(n, s.length)) {
            val d = text[a + k] - s[k]
            if (d != 0) return d
        }
        return n - s.length
    }

    private fun lowerBound(s: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, s) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun find(s: String): Int {
        val i = lowerBound(s)
        return if (i < size && compare(i, s) == 0) i else -1
    }

    companion object {
        /** Lines "word TAB frequency", in any order; broken lines skipped. */
        fun parse(tsv: String): WordList {
            val entries = tsv.lineSequence().mapNotNull { line ->
                val tab = line.indexOf('\t')
                val f = if (tab > 0) line.substring(tab + 1).toIntOrNull() else null
                if (f == null || f !in 0..255) null else line.substring(0, tab) to f
            }.sortedBy { it.first }.distinctBy { it.first }.toList()
            val text = StringBuilder()
            val starts = IntArray(entries.size + 1)
            val freqs = ByteArray(entries.size)
            entries.forEachIndexed { i, (w, f) ->
                starts[i] = text.length
                text.append(w).append('\n')
                freqs[i] = f.toByte()
            }
            starts[entries.size] = text.length
            return WordList(text.toString(), starts, freqs)
        }
    }
}
