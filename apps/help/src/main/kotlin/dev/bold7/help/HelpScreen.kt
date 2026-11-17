package dev.bold7.help

/** A row of the search's list (sim-444, C): a grey band, a topic found, or Device. */
sealed interface Row
enum class Band : Row { RESULTS, EXTENDED }
data class Result(val found: Found) : Row
data object Device : Row

/**
 * Help's screen, pure: the page shown while nothing is typed; once a letter is typed, the search's
 * list (sim-444, C): "Search Results (N)", the topics found, "Extended Search", then Device, which
 * hands the words to the home's search. [focus] is a row of [rows], never a band, or [FIELD]: while
 * typing the focus stays in the field (sim-444, C), the trackpad goes down into the list.
 */
data class HelpScreen(val query: String = "", val results: List<Found> = emptyList(), val focus: Int = FIELD) {
    companion object {
        const val FIELD = -1
    }

    val searching: Boolean get() = query.isNotEmpty()

    val rows: List<Row>
        get() = if (!searching) emptyList() else listOf(Band.RESULTS) + results.map(::Result) + listOf(Band.EXTENDED, Device)

    val found: Int get() = results.size

    fun typed(c: Char, search: (String) -> List<Found>) = searched(query + c, search)

    fun erased(search: (String) -> List<Found>) = if (query.isEmpty()) this else searched(query.dropLast(1), search)

    /** Back clears what is typed; with nothing typed, null: the page's own Back. */
    fun back(): HelpScreen? = if (searching) HelpScreen() else null

    fun step(down: Boolean): HelpScreen {
        if (!searching) return this
        val r = rows
        var i = focus
        do {
            i += if (down) 1 else -1
            if (i < 0) return copy(focus = FIELD)
            if (i !in r.indices) return this
        } while (r[i] is Band)
        return copy(focus = i)
    }

    private fun searched(q: String, search: (String) -> List<Found>): HelpScreen {
        if (q.isEmpty()) return HelpScreen()
        val found = search(q)
        return HelpScreen(q, found)
    }
}
