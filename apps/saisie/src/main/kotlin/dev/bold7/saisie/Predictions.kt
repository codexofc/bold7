package dev.bold7.saisie

/** How a word of the Predictive list is drawn (sim-94a, sim-96a, C). */
enum class Look {
    /** The word typed, unknown, first: in italics ("Teh", sim-96a). */
    TYPED,
    /** A word starting with what is typed: in bold ("This", "Think", "Third", sim-94a). */
    COMPLETION,
    /** A word for an unknown one: plain ("The", "Tea", sim-96a). */
    CORRECTION,
}

data class Suggestion(val text: String, val look: Look)

/** Letters a correction may put in or change: French and English (D). */
private const val LETTERS = "abcdefghijklmnopqrstuvwxyzàâäçéèêëîïôöùûüÿœ'"

/**
 * OS 7's Predictive list for the word being typed, [max] words at most (three on every capture,
 * D): what starts with it, the most frequent first in either language (sim-94a, sim-95b; OS 7's
 * own order is not frequency alone, "players" before "place" on sim-97a, D). A word of the
 * substitution list: itself, its replacement (sim-96a), then its completions or corrections. An
 * unknown word without completion: itself then the known words one letter away (removed, added,
 * changed or two swapped: D), the most frequent first; nothing when there is none. In the case typed.
 */
fun predict(typed: String, lexicons: List<Lexicon>, substitutions: Substitutions, max: Int = 3, macro: (Char) -> String? = { null }): List<Suggestion> {
    if (typed.isEmpty()) return emptyList()
    val sub = substitutions.find(typed, false, macro)
    val words = completions(typed, lexicons, max).map { Suggestion(cased(it, typed), Look.COMPLETION) }
    if (sub == null && (words.isNotEmpty() || known(typed, lexicons))) return words
    val lower = typed.lowercase()
    val rest = words.ifEmpty { corrections(lower, lexicons).map { Suggestion(cased(it, typed), Look.CORRECTION) } }
        .filter { it.text.lowercase() != lower && it.text.lowercase() != sub?.lowercase() }
    if (sub == null && rest.isEmpty()) return emptyList()
    return (listOf(Suggestion(typed, Look.TYPED)) + listOfNotNull(sub?.let { Suggestion(it, Look.CORRECTION) }) + rest).take(max)
}

/** Whether a list has [word] as typed, in small letters or in capitals ("ok" for "OK"); all known before the lists are read. */
fun known(word: String, lexicons: List<Lexicon>): Boolean =
    lexicons.isEmpty() || lexicons.any { it.contains(word) || it.contains(word.lowercase()) || it.contains(word.uppercase()) }

private fun frequency(word: String, lexicons: List<Lexicon>) = lexicons.maxOf { it.frequency(word) }

private fun completions(typed: String, lexicons: List<Lexicon>, max: Int): List<String> {
    val lower = typed.lowercase()
    val found = lexicons.flatMap { l -> l.complete(lower, max) + if (lower != typed) l.complete(typed, max) else emptyList() }
    return found.distinctBy { it.lowercase() }.sortedByDescending { frequency(it, lexicons) }.take(max)
}

private fun corrections(word: String, lexicons: List<Lexicon>): List<String> {
    val edits = HashSet<String>()
    for (i in 0..word.length) {
        if (i < word.length) edits += word.removeRange(i, i + 1)
        if (i + 1 < word.length) edits += word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
        for (c in LETTERS) {
            if (i < word.length) edits += word.substring(0, i) + c + word.substring(i + 1)
            edits += word.substring(0, i) + c + word.substring(i)
        }
    }
    return edits.mapNotNull { w -> frequency(w, lexicons).takeIf { it >= 0 }?.let { w to it } }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).map { it.first }
}

/** [word] in the case of [typed]: in capitals, or with its first capital. */
private fun cased(word: String, typed: String) = when {
    typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
    typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
    else -> word
}
