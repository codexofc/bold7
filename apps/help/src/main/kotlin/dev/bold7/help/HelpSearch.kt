package dev.bold7.help

/** A topic found by the search: its page and title. */
data class Found(val page: String, val title: String)

/**
 * Help's search over searchContent.txt, one topic a line, `<page>|<title>[|<keyword>,…]` (A,
 * docs/BBOS7-VISUEL.md §39), pure. A topic matches when every word typed starts a word of its title
 * or of its keywords (`_` joins a keyword's words), in the file's order (D: the 9900's search never
 * returned under the simulator).
 */
class HelpSearch(searchContent: String) {
    private class Topic(val found: Found, val words: List<String>)

    private val topics = searchContent.lineSequence().map { it.trimEnd('\r') }.filter { it.count { c -> c == '|' } >= 1 }.map { line ->
        val parts = line.split('|')
        val words = (parts[1] + " " + parts.getOrElse(2) { "" }.replace(',', ' ').replace('_', ' ')).lowercase().split(SPLIT).filter { it.isNotEmpty() }
        Topic(Found(parts[0], parts[1]), words)
    }.toList()

    fun find(query: String): List<Found> {
        val typed = query.lowercase().split(SPLIT).filter { it.isNotEmpty() }
        if (typed.isEmpty()) return emptyList()
        return topics.filter { t -> typed.all { q -> t.words.any { it.startsWith(q) } } }.map { it.found }
    }

    private companion object {
        val SPLIT = Regex("[^\\p{L}\\p{N}]+")
    }
}
