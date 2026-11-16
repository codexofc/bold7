package dev.bold7.saisie

/**
 * OS 7's Word Learning, "This option adds new words that you type to your custom dictionary"
 * (sim-92a, C): words and how often each was typed, nothing
 * else (no order, no sentence, no app, no field). A word is known, for Predictive and for the
 * underlining, from its second time (D: a slip typed once stays unknown). At most [max] words, the
 * least frequent leave first (D). Only letters and apostrophes, 2 to 32 of them.
 *
 * Learned on the typing thread, read there by Predictive, written out by TypingStore on its own
 * thread: every method holds this object's lock, each for a pass over a few thousand words at most.
 */
class Learned(private val max: Int = MAX) : Lexicon {
    private val counts = HashMap<String, Int>()

    val size @Synchronized get() = counts.size

    /** One more use of [word]; false for what is no word. */
    @Synchronized
    fun learn(word: String): Boolean {
        if (!learnable(word)) return false
        val k = key(word)
        val n = counts[k]
        if (n == null && counts.size >= max) counts.remove(counts.minByOrNull { it.value }!!.key)
        counts[k] = minOf((n ?: 0) + 1, CEILING)
        return true
    }

    @Synchronized
    fun clear() = counts.clear()

    @Synchronized
    override fun frequency(word: String): Int {
        val n = counts[key(word)] ?: return -1
        return if (n < KNOWN) -1 else minOf(255, BASE + STEP * (n - KNOWN))
    }

    // A pass over every word, about 40 µs for 5 000 on a desktop JVM; keep a sorted copy updated at
    // each change if the Q25 turns out to find it slow.
    @Synchronized
    override fun complete(prefix: String, max: Int): List<String> =
        counts.entries.filter { it.value >= KNOWN && it.key.startsWith(prefix, ignoreCase = true) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(max).map { it.key }

    /** Lines "word TAB count", for TypingStore's file. */
    @Synchronized
    fun text(): String = buildString { for ((w, n) in counts) append(w).append('\t').append(n).append('\n') }

    companion object {
        const val MAX = 5000
        /** Typed twice: known (D). */
        private const val KNOWN = 2
        /** Below the lists' common words ("work" 164, "the" 222), climbing with use (D). */
        private const val BASE = 120
        private const val STEP = 8
        private const val CEILING = 1000

        fun learnable(word: String) = word.length in 2..32 && word.any { it.isLetter() } && word.all { it.isLetter() || it == '\'' }

        /** A capital at the start only is the same word ("Zinwa" at a sentence's start); "iPhone" stays itself. */
        private fun key(word: String) = if (word.drop(1).none { it.isUpperCase() }) word.lowercase() else word

        /** What [text] gave back; broken lines and what is no word skipped. */
        fun parse(text: String, max: Int = MAX): Learned {
            val l = Learned(max)
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                val n = if (tab > 0) line.substring(tab + 1).toIntOrNull() else null
                val w = if (tab > 0) line.substring(0, tab) else ""
                if (n != null && n > 0 && learnable(w) && (l.counts.size < max || key(w) in l.counts)) l.counts[key(w)] = minOf(n, CEILING)
            }
            return l
        }
    }
}
