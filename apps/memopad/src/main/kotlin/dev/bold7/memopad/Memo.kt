package dev.bold7.memopad

import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.withHelp
import dev.bold7.ui.withSwitchApplication
import dev.bold7.ui.matchesWords

// The memos of BBOS 7.1 and the screens over them, pure (docs/ARCHITECTURE.md §3,
// docs/BBOS7-VISUEL.md §32, §34.2): the list with "New Memo:" first, the memos in alphabetical
// order (sim-259, C), typing filters it (the search of the guide's lists, B), the form of one memo.

/** A memo: a [title] and a [body] (guide p. 206, B: "Type a title and the memo"), [modified] in ms. */
data class Memo(val id: Long, val title: String, val body: String, val modified: Long) {
    private val lines: List<String> get() = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

    /** The title, or the body's first line when it has none (D); empty for a memo with neither. */
    val shownTitle: String get() = title.trim().ifEmpty { lines.firstOrNull().orEmpty() }

}

/** The list's order: alphabetical by shown title, case ignored (sim-259, C: Apple, Moon, Zebra); the oldest first on a tie (D). */
fun ordered(memos: List<Memo>): List<Memo> = memos.sortedWith(compareBy<Memo> { it.shownTitle.lowercase() }.thenBy { it.id })

sealed interface Row
data object NewMemo : Row
data class Entry(val memo: Memo) : Row

/** The list's actions: its full menu (sim-260 to sim-262, C) and short menu (sim-265, C), Edit being what a click does. */
enum class ListAct { NEW, EDIT, DELETE, OPTIONS, HELP, CLOSE, SWITCH, HOME }

sealed interface ListEvent {
    data class Loaded(val memos: List<Memo>) : ListEvent
    data class Typed(val char: Char) : ListEvent
    data object Erased : ListEvent
    data class Step(val down: Boolean) : ListEvent
    /** A touch on the row at [row]. */
    data class Moved(val row: Int) : ListEvent
}

data class MemoList(
    val all: List<Memo> = emptyList(),
    val typed: String = "",
    /** An index into [rows]. */
    val focus: Int = 0,
    val loaded: Boolean = false,
) {
    /** The memos with, for each word typed, a word of the title or the body starting with it (case and accents ignored). */
    val shown: List<Memo> = ordered(all.filter { matchesWords(it.title + " " + it.body, typed) })

    val rows: List<Row> = listOf(NewMemo) + shown.map(::Entry)

    val focused: Row? get() = rows.getOrNull(focus)

    /** No memo to show, none stored or none matching what is typed: "* No Memos *" under New Memo: (sim-254, C). */
    val empty: Boolean get() = loaded && shown.isEmpty()

    /** What the field shows: the first letter as a capital, as Contacts' (sim-189, C). */
    val field: String get() = typed.replaceFirstChar { it.uppercaseChar() }

    fun reduce(e: ListEvent): MemoList = when (e) {
        is ListEvent.Loaded -> {
            val id = (focused as? Entry)?.memo?.id
            val next = copy(all = e.memos, loaded = true)
            val at = next.rows.indexOfFirst { it is Entry && it.memo.id == id }
            next.copy(focus = when {
                at >= 0 -> at
                !loaded -> next.firstMatch()
                else -> focus.coerceIn(0, next.rows.size - 1)
            })
        }
        is ListEvent.Typed -> retyped(typed + e.char)
        ListEvent.Erased -> if (typed.isEmpty()) this else retyped(typed.dropLast(1))
        is ListEvent.Step -> copy(focus = (focus + if (e.down) 1 else -1).coerceIn(0, rows.size - 1))
        is ListEvent.Moved -> copy(focus = e.row.coerceIn(0, rows.size - 1))
    }

    /** Escape: what is typed goes first; null leaves the screen. */
    fun back(): MemoList? = if (typed.isEmpty()) null else retyped("")

    private fun firstMatch() = rows.indexOfFirst { it is Entry }.coerceAtLeast(0)

    private fun retyped(text: String): MemoList = copy(typed = text).let { it.copy(focus = it.firstMatch()) }

    /**
     * The full menu in sim-260 to sim-262's order (C): New, Edit (focused), Delete, then Options and
     * Close; on New Memo: New, Options, Close (D). Switch Application (sim-260, C) is added by core/ui on top. Not there yet: Switch Input
     * Language/Method, Forward As, Filter, Help.
     */
    fun menu(): Menu<ListAct> =
        (if (focused is Entry) Menu(
            listOf(MenuEntry(ListAct.NEW), MenuEntry(ListAct.EDIT), MenuEntry(ListAct.DELETE), MenuEntry(ListAct.OPTIONS, separatorAbove = true), MenuEntry(ListAct.CLOSE)),
            highlighted = 1,
        )
        else Menu(listOf(MenuEntry(ListAct.NEW), MenuEntry(ListAct.OPTIONS, separatorAbove = true), MenuEntry(ListAct.CLOSE)), highlighted = 0)).withHelp(ListAct.HELP, before = ListAct.CLOSE).withSwitchApplication(ListAct.SWITCH)

    /** The short menu on a memo: Delete, then Edit by Full Menu, focused (sim-265, C, without Forward As); none on New Memo. */
    fun shortMenu(): Menu<ListAct>? {
        if (focused !is Entry) return null
        return Menu(listOf(MenuEntry(ListAct.DELETE), MenuEntry(ListAct.SWITCH), MenuEntry(ListAct.HOME), MenuEntry(ListAct.EDIT)), line = focus, highlighted = 3)
    }
}

/** MemoPad Options (sim-263, C): Confirm Delete, on by default; Number of Entries is the memos' count, read only (D). */
data class MemoOptions(val confirmDelete: Boolean = true)

/** One memo's form, a title and a body: what was typed against what was stored. */
data class MemoEditor(val id: Long?, val title: String, val body: String, private val savedTitle: String, private val savedBody: String) {
    companion object {
        fun of(memo: Memo?) = MemoEditor(memo?.id, memo?.title.orEmpty(), memo?.body.orEmpty(), memo?.title.orEmpty(), memo?.body.orEmpty())
    }

    fun withTitle(text: String) = copy(title = text)
    fun withBody(text: String) = copy(body = text)

    val changed: Boolean get() = title != savedTitle || body != savedBody

    /** Nothing to keep: a new memo with no text is not saved (D, as Contacts' empty new contact). */
    val blank: Boolean get() = title.isBlank() && body.isBlank()

    fun toSave(): Triple<Long?, String, String> = Triple(id, title, body)
}

/**
 * Find (sim-256, C, the form's menu): where [query] next starts in [text] from [from] on, without
 * regard to case, wrapping round to the start once; null when it is nowhere. An empty query finds nothing.
 */
fun findNext(text: String, query: String, from: Int): Int? {
    if (query.isEmpty()) return null
    val at = text.indexOf(query, from.coerceIn(0, text.length), ignoreCase = true)
    if (at >= 0) return at
    return text.indexOf(query, 0, ignoreCase = true).takeIf { it >= 0 }
}
