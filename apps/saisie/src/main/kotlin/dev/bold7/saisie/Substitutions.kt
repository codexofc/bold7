package dev.bold7.saisie

/**
 * OS 7's word substitution (guide 7.1 p. 216-217, B): a word typed then Space is replaced, with its
 * case either as specified or SmartCase. SmartCase follows the context (D): the typed word in
 * capitals gives the entry in capitals, a typed capital or the start of a sentence a first capital.
 * A word matches whatever its case (D).
 */
class Substitutions private constructor(private val entries: Map<String, Entry>) {
    private class Entry(val with: String, val smart: Boolean)

    val size get() = entries.size

    /** Both lists; this one wins where both have the word. */
    operator fun plus(other: Substitutions) = Substitutions(other.entries + entries)

    /** Without the entries whose typed form [isWord] says is a word (of the other language). */
    fun without(isWord: (String) -> Boolean) = Substitutions(entries.filterKeys { !isWord(it) })

    /**
     * The entry for [word], its macros given by [macro] (a code of [MACROS] to its value, null when
     * there is none: the entry is left out and the word typed stays, D), then cased.
     */
    fun find(word: String, sentenceStart: Boolean, macro: (Char) -> String? = { null }): String? {
        val e = entries[word.lowercase()] ?: return null
        val with = expand(e.with, macro) ?: return null
        if (!e.smart) return with
        return when {
            word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() } -> with.uppercase()
            word[0].isUpperCase() || sentenceStart -> with.replaceFirstChar { it.uppercaseChar() }
            else -> with
        }
    }

    private fun expand(with: String, macro: (Char) -> String?): String? {
        if ('%' !in with) return with
        val out = StringBuilder()
        var i = 0
        while (i < with.length) {
            val c = with[i]
            val code = with.getOrNull(i + 1)
            if (c != '%' || code == null || code !in MACROS) {
                out.append(c)
                i++
                continue
            }
            out.append(if (code == '%') "%" else macro(code) ?: return null)
            i += 2
        }
        return out.toString()
    }

    companion object {
        val NONE = Substitutions(emptyMap())

        /**
         * The macros: those of OS 7's own list (%p the phone's number, %P the PIN, %V the version,
         * %b the battery: net_rim_tid_misspellings_de.cod, A), the date and the time the guide names
         * ("Insert Macro (for example, the current date or time)", p. 217, B) as %d, %D, %t, %T (D),
         * and %% for a percent sign (D). Any other % is itself.
         */
        const val MACROS = "pPVbdDtT%"

        /**
         * Lines "replace TAB with [TAB smart|specified]", SmartCase without the third column (OS 7's
         * list has none, D); # comments, blank and broken lines skipped.
         */
        fun parse(text: String) = Substitutions(
            text.lineSequence()
                .filterNot { it.startsWith("#") }
                .map { it.split('\t') }
                .filter { it.size in 2..3 && it[0].isNotEmpty() && it[1].isNotEmpty() }
                .filter { it.size == 2 || it[2] in setOf("smart", "specified") }
                .associate { it[0].lowercase() to Entry(it[1], it.size == 2 || it[2] == "smart") },
        )
    }
}

/**
 * An entry of the user's own list (Options > Typing and Language > Word Substitution, guide 7.1 p.
 * 217, B): Replace, With, Using SmartCase or Specified Case. Kept by TypingStore in the format
 * [Substitutions.parse] reads.
 */
data class UserEntry(val replace: String, val with: String, val smart: Boolean) {
    companion object {
        /** One line each; tabs and line breaks become spaces, an entry with an empty side is dropped. */
        fun text(rows: List<UserEntry>) = rows.mapNotNull { e ->
            val r = clean(e.replace)
            val w = clean(e.with)
            if (r.isEmpty() || w.isEmpty() || r.startsWith("#")) null else "$r\t$w\t${if (e.smart) "smart" else "specified"}\n"
        }.joinToString("")

        fun rows(text: String) = text.lineSequence().map { it.split('\t') }
            .filter { it.size == 3 && it[0].isNotEmpty() && it[1].isNotEmpty() && it[2] in setOf("smart", "specified") }
            .map { UserEntry(it[0], it[1], it[2] == "smart") }.toList()

        private fun clean(s: String) = s.replace(Regex("[\t\r\n]+"), " ").trim()
    }
}

/** A word list: Predictive's completions and the underlining of unknown words. */
interface Lexicon {
    /** 0 to 255, -1 for a word the list does not have. */
    fun frequency(word: String): Int
    fun contains(word: String) = frequency(word) >= 0
    fun complete(prefix: String, max: Int): List<String>
}
