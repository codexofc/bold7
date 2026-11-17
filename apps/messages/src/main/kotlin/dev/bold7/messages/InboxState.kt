package dev.bold7.messages

// The inbox screen's state and its reducer, pure (docs/ARCHITECTURE.md §3): the activity renders
// it and sends the events. The lines come from the feed, worked out for [InboxState.filter] and
// [InboxState.search] off the main thread.

/** The focus on the search field, above the first line. */
const val FIELD = -1

/** The home's Text Messages icon: the manifest's activity-alias, named in core/icons' tables. */
const val TEXT_MESSAGES_ALIAS = "dev.bold7.messages.TextMessages"

/**
 * The starting intent's [EXTRA_VIEW] for the missed calls: View of the Phone's "You have N new
 * missed calls.", which on the 9900 opens its list of missed calls (sim-204, C).
 */
const val EXTRA_VIEW = "dev.bold7.messages.VIEW"
const val MISSED_CALLS_VIEW = "missed_calls"

/** The starting intent's mail account (an Account id of the Hub): its icon on the home, a shortcut published by AccountShortcuts. */
const val EXTRA_MAIL_ACCOUNT = "dev.bold7.messages.ACCOUNT"

/**
 * The view the activity [className] of the starting intent opens on, and its [view] extra: the
 * missed calls, Text Messages' texts, else everything.
 */
fun entryView(className: String?, view: String? = null): Filter = when {
    view == MISSED_CALLS_VIEW -> Filter.CALLS
    className == TEXT_MESSAGES_ALIAS -> Filter.TEXT
    else -> Filter.ALL
}

/** The list, or View Folder's list of views (D: the 9900's folders are mail's, its views saved searches). */
enum class Screen { LIST, FOLDERS }

data class InboxState(
    val lines: List<InboxLine> = emptyList(),
    /** False until the first read: nothing rather than "* No Messages *". */
    val loaded: Boolean = false,
    val filter: Filter = Filter.ALL,
    /** The search field's text; null: no field. */
    val search: String? = null,
    /** The field [search] looks at: Search by's (guide 7.1 p. 86, B), else all of them. */
    val searchIn: SearchIn = SearchIn.ANY,
    /** The trackpad's focus: a line, or [FIELD]. */
    val focus: Int = 0,
    val screen: Screen = Screen.LIST,
    /** The focused view of View Folder, an index of [FOLDER_VIEWS]. */
    val folder: Int = 0,
    /** The view the home's icon opened: ALL for Messages, TEXT for Text Messages. */
    val base: Filter = Filter.ALL,
    /** The mail account whose icon opened Messages: only its items (gsm 029); null for all. */
    val account: String? = null,
    /**
     * The line ([InboxLine.id]) a range started from: the focus moved with Shift held (guide 7.1,
     * "Delete a message", M); the range runs from it to the focus ([span]).
     */
    val anchor: Long? = null,
) {
    val focusedLine: InboxLine? get() = lines.getOrNull(focus)
}

/** The range's ends, lowest first: null without an anchor, with its line gone, on it, or on the field. */
fun InboxState.span(): IntRange? {
    val a = anchor ?: return null
    if (focus < 0) return null
    // Simplification: a scan of the lines at each call (each row bound while a range is shown); an index by id if it ever shows in a frame.
    val from = lines.indexOfFirst { it.id == a }
    if (from < 0 || from == focus) return null
    return minOf(from, focus)..maxOf(from, focus)
}

/** The messages of the range, in the list's order: never a date field. */
fun InboxState.range(): List<ItemLine> = span()?.mapNotNull { lines.getOrNull(it) as? ItemLine }.orEmpty()

sealed interface InboxEvent {
    /** New lines from the feed. */
    data class Shown(val lines: List<InboxLine>) : InboxEvent

    /** The trackpad, a step up or down; with Shift held, it selects ([InboxState.anchor]). */
    data class Step(val down: Boolean, val shift: Boolean = false) : InboxEvent

    /** A jump (navigate), or a touch on line [to]. */
    data class Moved(val to: Int) : InboxEvent

    data class Filtered(val filter: Filter) : InboxEvent
    data object SearchOpened : InboxEvent
    data class Typed(val text: String) : InboxEvent

    /** Search by > Sender, Recipient or Subject of the message the menu was on: its results, the first line focused. */
    data class SearchedBy(val field: SearchIn, val text: String) : InboxEvent
    data object FoldersOpened : InboxEvent
    data class FolderStep(val down: Boolean) : InboxEvent

    /** Opened by the home's icon of [base]: Messages (ALL) or Text Messages (TEXT), or a mail [account]'s. */
    data class Opened(val base: Filter, val account: String? = null) : InboxEvent
}

fun InboxState.reduce(event: InboxEvent): InboxState = when (event) {
    is InboxEvent.Shown -> {
        // The focus stays on its line, wherever it went; gone, on the same place.
        val id = focusedLine?.id
        val same = if (id == null) -1 else event.lines.indexOfFirst { it.id == id }
        val focus = when {
            focus == FIELD -> FIELD
            same >= 0 -> same
            else -> focus.coerceIn(0, (event.lines.size - 1).coerceAtLeast(0))
        }
        // The range stays while its anchor's line is there.
        copy(lines = event.lines, loaded = true, focus = focus, anchor = anchor?.takeIf { a -> event.lines.any { it.id == a } })
    }
    is InboxEvent.Step -> copy(
        focus = when {
            event.down && focus == FIELD -> if (lines.isEmpty()) FIELD else 0
            event.down -> (focus + 1).coerceAtMost((lines.size - 1).coerceAtLeast(0))
            focus == 0 && search != null -> FIELD
            else -> (focus - 1).coerceAtLeast(if (search != null) FIELD else 0)
        },
        // Shift anchors where the focus was, unless a range is already shown; without it, no range.
        anchor = if (!event.shift) null else if (span() != null) anchor else focusedLine?.id,
    )
    is InboxEvent.Moved -> copy(focus = event.to.coerceIn(0, (lines.size - 1).coerceAtLeast(0)), anchor = null)
    is InboxEvent.Filtered -> copy(filter = event.filter, search = null, searchIn = SearchIn.ANY, focus = 0, screen = Screen.LIST, anchor = null)
    InboxEvent.SearchOpened -> copy(search = search ?: "", focus = FIELD, screen = Screen.LIST, anchor = null)
    is InboxEvent.SearchedBy -> copy(search = event.text, searchIn = event.field, focus = 0, screen = Screen.LIST, anchor = null)
    is InboxEvent.Typed -> copy(search = event.text, focus = FIELD, anchor = null)
    InboxEvent.FoldersOpened -> copy(screen = Screen.FOLDERS, folder = FOLDER_VIEWS.indexOf(filter).coerceAtLeast(0), anchor = null)
    is InboxEvent.FolderStep -> copy(folder = (folder + if (event.down) 1 else -1).coerceIn(0, FOLDER_VIEWS.lastIndex))
    // The same icon again finds the app as it was left; the other one starts over on its view.
    is InboxEvent.Opened -> if (event.base == base && event.account == account) this else copy(base = event.base, account = event.account, filter = event.base, search = null, searchIn = SearchIn.ANY, focus = 0, screen = Screen.LIST, anchor = null)
}

/**
 * Escape, one thing at a time: View Folder closes, then a range goes (D), then the search, then a view goes back to all
 * the messages ("View all your messages: Escape", guide 7.1 p. 52, B), or to the texts when Text
 * Messages opened the app (D); null at the end, where it leaves the app for the home.
 */
fun InboxState.back(): InboxState? = when {
    screen == Screen.FOLDERS -> copy(screen = Screen.LIST)
    span() != null -> copy(anchor = null)
    search != null -> copy(search = null, searchIn = SearchIn.ANY, focus = 0, anchor = null)
    filter != base -> copy(filter = base, focus = 0, anchor = null)
    else -> null
}

/**
 * Whether the search field's editor takes [key] while it has the focus (the input method types
 * into it): text, Delete and its caret's moves; up, down, the click, Escape and Menu stay the list's.
 */
fun searchTakes(key: FieldKey): Boolean = key == FieldKey.TEXT || key == FieldKey.DELETE || key == FieldKey.LEFT || key == FieldKey.RIGHT
