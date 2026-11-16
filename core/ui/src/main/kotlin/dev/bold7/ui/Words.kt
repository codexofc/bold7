package dev.bold7.ui

import java.text.Normalizer

// Searching names by what is typed, pure: Contacts' list and To in Messages
// find a contact the same way.

private val MARKS = Regex("\\p{Mn}+")

/** Lower case without accents: "Éloïse" is "eloise". */
fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")

/**
 * Whether [name] has, for each word of [typed], a word starting with it; case and accents ignored
 * ("d m" finds Dexter Morgan, D). Nothing typed matches every name.
 */
fun matchesWords(name: String, typed: String): Boolean {
    val words = fold(typed).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val names = fold(name).split(' ', '-', '.', ',', '\'').filter { it.isNotEmpty() }
    return words.all { w -> names.any { it.startsWith(w) } }
}
