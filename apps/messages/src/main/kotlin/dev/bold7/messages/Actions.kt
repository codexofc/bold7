package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.withoutEchoes
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.withSwitchApplication
import dev.bold7.ui.shortcuts
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// What the inbox's menus, keys and actions do, pure; the activity draws the menus and sends what
// these decide. Logic of the launcher's hub/HubActions.kt. Labels and keys: the
// 9900's 7.1 guide (B: p. 31, 51-52, 94-95, 103), sim-44 (C).

enum class Act(
    /** The view an Alt shortcut shows (guide p. 52), null for the others. */
    val filter: Filter? = null,
    /** What the Hub's provider is asked, null for what writes nothing. */
    val method: String? = null,
) {
    OPEN,
    MARK_READ(method = HubContract.METHOD_MARK_READ),
    MARK_UNREAD(method = HubContract.METHOD_MARK_UNREAD),
    MARK_PRIOR_READ(method = HubContract.METHOD_MARK_READ),
    DELETE(method = HubContract.METHOD_DELETE),
    DELETE_PRIOR(method = HubContract.METHOD_DELETE),
    /** Delete Messages, over a range (guide 7.1, "Delete a message", M). */
    DELETE_RANGE(method = HubContract.METHOD_DELETE),
    COMPOSE_EMAIL,
    COMPOSE,
    COMPOSE_OTHER,
    COMPOSE_TEXT,
    SEARCH,
    SEARCH_BY,
    SEARCH_SENDER,
    SEARCH_RECIPIENT,
    SEARCH_SUBJECT,
    DISPLAY_STYLE,
    STYLE_GROUP,
    STYLE_SINGLE,
    VIEW_FOLDER,
    VIEW_SAVED,
    SAVE,
    NOTIFIERS,
    TO_TOP,
    TO_BOTTOM,
    NEXT_UNREAD,
    NEXT_RELATED,
    PREVIOUS_RELATED,
    PAGE_DOWN,
    PAGE_UP,
    TOGGLE_READ,
    SHOW_INCOMING(Filter.INCOMING),
    SHOW_OUTGOING(Filter.OUTGOING),
    SHOW_TEXT(Filter.TEXT),
    SHOW_CALLS(Filter.CALLS),
    OPTIONS,
    HELP,
    CLOSE,
    SWITCH,
}

/**
 * The full menu (Menu key) over [line], as OS 7's (sim-44, sim-45, sim-289, sim-290, C): on a
 * message Open, Mark Read or Mark Unread, Save, Delete (guide p. 91-95, B); on a date field Mark
 * Prior Read and Delete Prior (p. 31, 95); then Compose Email, Compose Text Message (p. 103; the
 * text is in Compose Other ►'s submenu as in sim-44, C), Search, Search by ► (always, sim-44, C; p. 86), Display Style ► (p. 88), View Folder, View Saved Messages, Message Notifiers and
 * Options last. In [view] the saved messages, Delete removes the message from
 * them and Delete Prior is not there. Help and Close, under Options, come with [menuOf]; Switch
 * Application too (sim-44, C). Over a [range] (Shift and the trackpad), Mark Read, Mark Unread
 * and Delete Messages act on the whole of it (Delete Messages: guide 7.1, "Delete a message", M;
 * Mark Read and Mark Unread on a range: S, remembered from the 9900).
 */
fun fullMenu(line: InboxLine?, view: Filter = Filter.ALL, range: Boolean = false): List<MenuEntry<Act>> {
    val own = if (range) listOf(Act.MARK_READ, Act.MARK_UNREAD, Act.DELETE_RANGE) else when (line) {
        is ItemLine -> listOfNotNull(
            Act.OPEN, if (line.unread) Act.MARK_READ else Act.MARK_UNREAD,
            Act.SAVE.takeIf { line.item.canSave() && !line.saved }, Act.DELETE,
        )
        is DayLine -> listOfNotNull(Act.MARK_PRIOR_READ, Act.DELETE_PRIOR.takeIf { view != Filter.SAVED })
        null -> emptyList()
    }
    return own.map { MenuEntry(it) } +
        MenuEntry(Act.COMPOSE_EMAIL, separatorAbove = own.isNotEmpty()) +
        MenuEntry(Act.COMPOSE_OTHER, submenu = true) +
        MenuEntry(Act.SEARCH, separatorAbove = true) +
        MenuEntry(Act.SEARCH_BY, submenu = true) +
        MenuEntry(Act.DISPLAY_STYLE, submenu = true) +
        MenuEntry(Act.VIEW_FOLDER) +
        MenuEntry(Act.VIEW_SAVED) +
        MenuEntry(Act.NOTIFIERS, separatorAbove = true) +
        MenuEntry(Act.OPTIONS)
}

/**
 * Search by's entries over [line], each with the text it looks for (guide p. 86, B: "highlight a
 * message, Search by > Subject or Sender"): the sender of a received message, the recipient of a
 * sent one (the Hub files it under its recipient's name), the subject when there is one. On a date
 * field or none, Sender and Subject with no text.
 */
fun searchBy(line: InboxLine?): List<Pair<Act, String>> {
    val item = (line as? ItemLine)?.item
    val own = if (item == null) emptyList() else listOfNotNull(
        (if (item.box == Box.INBOX) Act.SEARCH_SENDER else Act.SEARCH_RECIPIENT).takeIf { item.sender.isNotBlank() }?.let { it to item.sender },
        item.subject?.takeIf { it.isNotBlank() }?.let { Act.SEARCH_SUBJECT to it },
    )
    // Nothing to take from the line: the entry stays, as in the empty inbox of sim-44 (C), and the
    // search opens empty on that field (D).
    return own.ifEmpty { listOf(Act.SEARCH_SENDER to "", Act.SEARCH_SUBJECT to "") }
}

/** Compose Other's submenu (sim-44, C): what Android can send is a text message (D: OS 7's other kinds, MMS and PIN, have no source here). */
fun composeOtherMenu() = Menu(listOf(MenuEntry(Act.COMPOSE_TEXT)), highlighted = 0)

/** The Search by submenu over [line]. */
fun searchByMenu(line: InboxLine?) = Menu(searchBy(line).map { MenuEntry(it.first) }, highlighted = 0)

/** The Display Style submenu (guide p. 88, B). */
fun displayStyleMenu() = Menu(listOf(MenuEntry(Act.STYLE_GROUP), MenuEntry(Act.STYLE_SINGLE)), highlighted = 0)

/**
 * The full menu as opened by the Menu key: Switch Application, then [fullMenu], then Help and Close
 * under Options (sim-289, sim-290, C), focused on [menuFocus].
 */
fun menuOf(line: InboxLine?, view: Filter = Filter.ALL, range: Boolean = false): Menu<Act> {
    val entries = fullMenu(line, view, range) + MenuEntry(Act.HELP) + MenuEntry(Act.CLOSE)
    return Menu(entries, highlighted = menuFocus(entries, line, range)).withSwitchApplication(Act.SWITCH)
}

/**
 * The entry of [entries] a menu over [line] opens on, its default action (docs/BBOS7-VISUEL.md §4):
 * on a date field Compose Email, as the short menu of a date field focuses Compose (vid-06, C) and
 * Messages' full menu Compose Email (sim-44, sim-289, C; the short menu the same, D); else the
 * first action, Open on a message.
 */
fun menuFocus(entries: List<MenuEntry<Act>>, line: InboxLine?, range: Boolean = false): Int =
    if (line is DayLine && !range) entries.indexOfFirst { it.action == Act.COMPOSE_EMAIL }.coerceAtLeast(0) else 0

/** The short menu (the trackpad held): the same actions, Full Menu last (D: the 9900's for a message is not captured). */
fun shortMenu(line: InboxLine?, view: Filter = Filter.ALL, range: Boolean = false): List<MenuEntry<Act>> =
    fullMenu(line, view, range).filter { it.action !in FULL_ONLY }
        // The one thing Compose Other holds here is the short menu's Compose Text Message cell.
        .map { if (it.action == Act.COMPOSE_OTHER) MenuEntry(Act.COMPOSE) else it.copy(separatorAbove = false) }

/** What the short menu leaves to the full one: the submenus and the rows OS 7's short menu has no cell for (D). */
private val FULL_ONLY = setOf(Act.SAVE, Act.SEARCH_BY, Act.DISPLAY_STYLE, Act.VIEW_SAVED, Act.NOTIFIERS)

private val PLAIN = shortcuts(
    'c' to Act.COMPOSE,
    't' to Act.TO_TOP,
    'b' to Act.TO_BOTTOM,
    'u' to Act.NEXT_UNREAD,
    'j' to Act.NEXT_RELATED,
    'k' to Act.PREVIOUS_RELATED,
)

private val ALT = shortcuts(
    'u' to Act.TOGGLE_READ,
    'i' to Act.SHOW_INCOMING,
    'o' to Act.SHOW_OUTGOING,
    's' to Act.SHOW_TEXT,
    'p' to Act.SHOW_CALLS,
)

/**
 * The inbox's letters (guide 7.1 p. 51-52, B), with the Alt key or without. Space and Shift Space
 * (a screen down, up) are keys, not letters: the activity sends PAGE_DOWN and PAGE_UP. W (flag),
 * Alt F (flagged) and Alt V (voice mail) wait for a source that flags or leaves a voice mail.
 */
fun shortcut(label: Char, alt: Boolean, mail: Boolean = false): Act? {
    val act = (if (alt) ALT else PLAIN).of(label)
    // C composes a mail once a mail account is there (as OS 7 does), a text otherwise.
    return if (act == Act.COMPOSE && mail) Act.COMPOSE_EMAIL else act
}

/**
 * The line [act] moves to from line [from], or null when there is none; [page] lines make a
 * screen (Space). Next unread skips the date fields; the related messages share [ItemLine.related].
 */
fun navigate(act: Act, lines: List<InboxLine>, from: Int, page: Int): Int? {
    if (lines.isEmpty()) return null
    val related = (lines.getOrNull(from) as? ItemLine)?.related
    return when (act) {
        Act.TO_TOP -> 0
        Act.TO_BOTTOM -> lines.lastIndex
        Act.PAGE_DOWN -> (from.coerceAtLeast(0) + page).coerceAtMost(lines.lastIndex)
        Act.PAGE_UP -> (from - page).coerceAtLeast(0)
        Act.NEXT_UNREAD -> (from + 1 until lines.size).firstOrNull { (lines[it] as? ItemLine)?.unread == true }
        Act.NEXT_RELATED -> related?.let { r -> (from + 1 until lines.size).firstOrNull { (lines[it] as? ItemLine)?.related == r } }
        Act.PREVIOUS_RELATED -> related?.let { r -> (from - 1 downTo 0).firstOrNull { (lines[it] as? ItemLine)?.related == r } }
        else -> null
    }
}

/**
 * What the user asked of the Hub's store: on a row's [item], or a range's [rows], or on a [day]
 * and those before it as the list was when its menu opened ([asOf], epoch ms): what came since is
 * not written.
 */
data class Request(
    val act: Act,
    val item: HubItem? = null,
    val day: LocalDate? = null,
    val asOf: Long = Long.MAX_VALUE,
    val rows: List<HubItem> = listOfNotNull(item),
)

private fun HubItem.key() = ItemKey(accountId, sourceKey)

private fun HubItem.sameRow(other: HubItem) =
    accountId == other.accountId && if (conversationId == null) sourceKey == other.sourceKey else conversationId == other.conversationId

/**
 * The items [request] writes: a row read or deleted is its whole conversation up to the item it
 * showed (not a message come since); unread again, that item; prior, every item the view
 * ([filter], [search]) showed on that day or before (inclusive, D), none newer than the menu. Read
 * or deleted, the notifications the list hides as repeating them go too, so that the app's own
 * action reads them and Android's shade loses them. Never a deleted item.
 */
fun writeKeys(request: Request, snapshot: Snapshot, filter: Filter, search: String?, zone: ZoneId, account: String? = null, searchIn: SearchIn = SearchIn.ANY, app: String? = null): List<ItemKey> {
    val items = snapshot.items.filter { !it.deleted }
    val targets: List<HubItem> = when (request.act) {
        Act.MARK_UNREAD -> request.rows.filter { !it.deleted }
        // Single Messages: the row is one message, not its conversation.
        Act.MARK_READ, Act.DELETE ->
            if (!snapshot.options.group) request.rows.filter { !it.deleted }
            // Simplification: rows times items, on the io thread; a map by conversation if a long range is ever slow.
            else items.filter { item -> request.rows.any { row -> item.sameRow(row) && item.timestamp <= row.timestamp } }
        Act.MARK_PRIOR_READ, Act.DELETE_PRIOR -> {
            val day = request.day ?: return emptyList()
            // By message, not by row: a conversation shown under a later day loses its messages
            // of that day and before (D).
            snapshot.shown(filter, search, account, searchIn, app).filter {
                it.timestamp <= request.asOf && !Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().isAfter(day)
            }
        }
        else -> emptyList()
    }
    if (request.act == Act.MARK_UNREAD || targets.isEmpty()) return targets.map { it.key() }
    // An echo is hidden while its target is there, and shows once the target is left out.
    val visible = snapshot.items.withoutEchoes(snapshot.smsApp, snapshot.dialer).toHashSet()
    val targetSet = targets.toHashSet()
    val echoes = (snapshot.items - targetSet).withoutEchoes(snapshot.smsApp, snapshot.dialer).filter { it !in visible && !it.deleted }
    return (targets + echoes).map { it.key() }
}

/**
 * What Delete (one row) or Delete Messages (a range) does with the [rows]' items, as the 9900's
 * Delete does: in the saved messages, taken out of them ([unsave], guide p. 93, B); a draft,
 * this app's own file ([drafts]); the rest deleted in the Hub ([hub]).
 */
data class Deletion(val unsave: List<String> = emptyList(), val drafts: List<String> = emptyList(), val hub: List<HubItem> = emptyList())

fun deletion(rows: List<HubItem>, view: Filter): Deletion =
    if (view == Filter.SAVED) Deletion(unsave = rows.map { it.savedKey() })
    else rows.partition { it.accountId == DRAFTS_ACCOUNT }.let { (drafts, hub) -> Deletion(drafts = drafts.map { it.sourceKey }, hub = hub) }

/** Where Open goes: the item in its own app. */
sealed interface Opening {
    /** The notification's contentIntent while it lives, else its [app]. */
    data class Notification(val key: String, val app: String?) : Opening

    /** A messaging app's conversation, the notification [key]'s, in the thread of OS 7. */
    data class AppThread(val key: String, val app: String) : Opening

    /**
     * The SMS app on the conversation with [addresses]: one, or every member of a group (the Hub
     * joins them with ";", as smsto: does), never a single member of a group; none known, the SMS app.
     */
    data class Conversation(val addresses: List<String>) : Opening

    /** OS 7's "Call [number]?" (sim-104), asked by the Phone of Bold7. */
    data class Call(val number: String) : Opening

    /** The Phone on its log: a missed call without number. */
    data object CallLog : Opening

    data class Event(val id: Long) : Opening

    /** A mail of an account, read in Messages. */
    data class Mail(val key: ItemKey) : Opening

    /** A draft kept on the phone, written again in Compose. */
    data class Draft(val id: String) : Opening

    data object None : Opening
}

fun opening(item: HubItem): Opening = when (item.kind) {
    ItemKind.NOTIFICATION -> item.conversationId?.let { key ->
        // MessagingStyle: a conversation, which the thread shows; anything else in its app.
        if (item.messaging && item.folder != null) Opening.AppThread(key, item.folder!!) else Opening.Notification(key, item.folder)
    } ?: Opening.None
    ItemKind.SMS, ItemKind.MMS -> Opening.Conversation(item.address.orEmpty().split(';').filter { it.isNotBlank() })
    ItemKind.MISSED_CALL -> item.address?.takeIf { it.isNotBlank() }?.let(Opening::Call) ?: Opening.CallLog
    ItemKind.INVITATION -> item.sourceKey.toLongOrNull()?.let(Opening::Event) ?: Opening.None
    ItemKind.EMAIL -> if (item.accountId == DRAFTS_ACCOUNT) Opening.Draft(item.sourceKey) else Opening.Mail(item.key())
}
