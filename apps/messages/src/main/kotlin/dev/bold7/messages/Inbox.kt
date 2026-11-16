package dev.bold7.messages

import dev.bold7.hub.data.Account
import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.isNotMessage
import dev.bold7.hub.data.HubRow
import dev.bold7.hub.data.HubView
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.hubRows
import dev.bold7.hub.data.isText
import dev.bold7.hub.data.isUnread
import dev.bold7.hub.data.withoutEchoes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// What the unified inbox of BBOS 7.1 shows (docs/BBOS7-VISUEL.md §15), worked out off the main
// thread from the Hub's store: the views only draw these strings. Pure. Logic taken from the
// launcher's hub/HubLines.kt and the filters of feat/p3-hub-filtres.

/** The phone's zone, language and clock, read with the items. */
data class InboxFormat(val zone: ZoneId, val locale: Locale, val clock24: Boolean)

/** What the Hub's store holds, read at once, and the apps whose notifications repeat it (withoutEchoes). */
data class Snapshot(
    val items: List<HubItem>,
    /** The accounts in their order, with their last visit (seenAt). */
    val accounts: List<Account>,
    val smsApp: String?,
    val dialer: String?,
    /** The made-up items of the measure (tools/perf.sh): never written back. */
    val demo: Boolean = false,
    /** The options of Messages. */
    val options: MessagesOptions = MessagesOptions(),
    /** The keys ([savedKey]) of the saved messages. */
    val saved: Set<String> = emptySet(),
)

/**
 * The views of the inbox, in the order of View Folder: everything, then those of the 9900's Alt
 * shortcuts (guide 7.1 p. 52, B: received messages and call logs, drafts and sent, text messages,
 * call logs), then the unread items and the notifications of the other apps (D).
 */
enum class Filter { ALL, INCOMING, OUTGOING, TEXT, CALLS, UNREAD, NOTIFICATIONS, SAVED }

/** View Folder's views: View Saved Messages is a row of the menu, not of this list (sim-44, C). */
val FOLDER_VIEWS = Filter.entries.filter { it != Filter.SAVED }

/**
 * Whether [filter] shows [item]. A notification of the [smsApp] is a text: an RCS chat that
 * Google Messages did not copy into Telephony reaches the Hub only so (D). Only missed calls
 * reach the Hub: the call logs are those.
 */
fun Filter.shows(item: HubItem, smsApp: String?): Boolean = (this == Filter.NOTIFICATIONS || !item.isNotMessage()) && when (this) {
    Filter.ALL -> true
    Filter.INCOMING -> item.box == Box.INBOX
    Filter.OUTGOING -> item.box != Box.INBOX
    Filter.TEXT -> item.isText(smsApp)
    Filter.CALLS -> item.kind == ItemKind.MISSED_CALL
    Filter.UNREAD -> item.isUnread()
    Filter.NOTIFICATIONS -> item.kind == ItemKind.NOTIFICATION && !item.isSmsAppNotification(smsApp)
    // Which items are saved is the snapshot's to say (shown).
    Filter.SAVED -> true
}

private fun HubItem.isSmsAppNotification(smsApp: String?) = kind == ItemKind.NOTIFICATION && smsApp != null && folder == smsApp

/** [query] in one of the item's fields, whatever the case, as the Hub's search (feat/p3-hub-filtres). No accent folding (D). */
fun HubItem.matches(query: String): Boolean =
    sender.contains(query, ignoreCase = true) ||
        address?.contains(query, ignoreCase = true) == true ||
        subject?.contains(query, ignoreCase = true) == true ||
        preview.contains(query, ignoreCase = true)

/**
 * The items [filter] and [search] show, without the notifications that repeat another item, nor the
 * deleted ones; only [account]'s when an account's icon opened Messages (gsm 029).
 */
fun Snapshot.shown(filter: Filter, search: String?, account: String? = null, searchIn: SearchIn = SearchIn.ANY): List<HubItem> {
    val query = search?.trim().orEmpty()
    val saved = filter == Filter.SAVED
    return items.withoutEchoes(smsApp, dialer).filter {
        // A saved message stays in the saved view once deleted from the inbox (guide p. 93, B).
        (if (saved) it.savedKey() in this.saved else !it.deleted && options.keeps(it, filter, account, smsApp)) &&
            (account == null || it.accountId == account) && filter.shows(it, smsApp) && (query.isEmpty() || it.matches(query, searchIn))
    }.let { found -> if (saved) found.map { it.copy(deleted = false) } else found } // shown as saved, whatever the inbox did (the Hub's views leave out the deleted)
}

enum class Strip { MESSAGE, PHONE }

/**
 * A row's status icon, a cell of a 42 px strip of the theme (A): net_rim_Message-icons42x42.png of
 * net_rim_theme_BlackBerry7_640x480_b, net_rim_Phone-icons42x42.png of its _bs1. The 42 px size and
 * the cells of email and of a sent text are measured (gsm 027, 029, sim-53, C); the other cells are
 * read on the strips, by eye: row 0 mail, row 1 a text, row 3 a text with attachments; column 1
 * unread, 2 read, 4 the green pen of sent (sim-53), 5 the clock of waiting; row 3's column 22 and
 * 23 the dark and the light BlackBerry of a message from the system (gsm 027, read); the phone's
 * column 2 and 3 the orange and the grey missed call.
 */
enum class MessageIcon(val strip: Strip, val row: Int, val column: Int) {
    EMAIL_UNREAD(Strip.MESSAGE, 0, 1),
    EMAIL_READ(Strip.MESSAGE, 0, 2),
    EMAIL_SENT(Strip.MESSAGE, 0, 4),
    EMAIL_PENDING(Strip.MESSAGE, 0, 5),
    SMS_UNREAD(Strip.MESSAGE, 1, 1),
    SMS_READ(Strip.MESSAGE, 1, 2),
    SMS_SENT(Strip.MESSAGE, 1, 4),
    SMS_PENDING(Strip.MESSAGE, 1, 5),
    MMS_UNREAD(Strip.MESSAGE, 3, 1),
    MMS_READ(Strip.MESSAGE, 3, 2),
    MMS_SENT(Strip.MESSAGE, 3, 4),
    MMS_PENDING(Strip.MESSAGE, 3, 5),
    SYSTEM_UNREAD(Strip.MESSAGE, 3, 22),
    SYSTEM_READ(Strip.MESSAGE, 3, 23),
    CALL_MISSED_UNREAD(Strip.PHONE, 0, 2),
    CALL_MISSED_READ(Strip.PHONE, 0, 3),
}

/**
 * The row's icon. A draft has the pen of sent (D: no draft cell is known). An MMS without
 * attachment is a text: Google Messages copies its RCS chats into Telephony's MMS (D). A
 * notification is a message from the system, a text if it is the [smsApp]'s; an invitation, a mail (D).
 */
fun HubRow.Entry.icon(smsApp: String?): MessageIcon {
    val unread = unread > 0
    fun of(unreadIcon: MessageIcon, read: MessageIcon, sent: MessageIcon, pending: MessageIcon) = when (item.box) {
        Box.INBOX -> if (unread) unreadIcon else read
        Box.SENT, Box.DRAFT -> sent
        Box.OUTBOX -> pending
    }
    val sms = { of(MessageIcon.SMS_UNREAD, MessageIcon.SMS_READ, MessageIcon.SMS_SENT, MessageIcon.SMS_PENDING) }
    return when (item.kind) {
        ItemKind.SMS -> sms()
        ItemKind.MMS -> if (item.attachmentCount == 0) sms() else of(MessageIcon.MMS_UNREAD, MessageIcon.MMS_READ, MessageIcon.MMS_SENT, MessageIcon.MMS_PENDING)
        ItemKind.EMAIL, ItemKind.INVITATION -> of(MessageIcon.EMAIL_UNREAD, MessageIcon.EMAIL_READ, MessageIcon.EMAIL_SENT, MessageIcon.EMAIL_PENDING)
        ItemKind.MISSED_CALL -> if (unread) MessageIcon.CALL_MISSED_UNREAD else MessageIcon.CALL_MISSED_READ
        ItemKind.NOTIFICATION -> when {
            item.isSmsAppNotification(smsApp) -> sms()
            unread -> MessageIcon.SYSTEM_UNREAD
            else -> MessageIcon.SYSTEM_READ
        }
    }
}

sealed interface InboxLine {
    /** Stable through updates: a row by its conversation, a day below zero. */
    val id: Long
}

/** A date field, [date] in the phone's zone: what the prior actions count from. */
data class DayLine(override val id: Long, val title: String, val date: LocalDate) : InboxLine

data class ItemLine(
    override val id: Long,
    val sender: String,
    val time: String,
    /** The second line: subject, text or number, on one line. */
    val preview: String,
    val icon: MessageIcon,
    /** The sender in bold (gsm 029, C). */
    val unread: Boolean,
    /** The conversation's latest item: what Open opens. */
    val item: HubItem,
    /** What J and K follow, "the next related message" (guide p. 52): its number or address, else its sender (D). */
    val related: String,
    /** In the saved messages: Save is not offered again. */
    val saved: Boolean = false,
) : InboxLine

/** "Fri 16 Sep 2011" (gsm 027, C), in the phone's language as the home's banner writes its date (dots dropped). */
private fun dayFormatter(locale: Locale) = DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)

private fun timeFormatter(format: InboxFormat) = DateTimeFormatter.ofPattern(if (format.clock24) "HH:mm" else "h:mm a", format.locale)

/** A mail's or an invitation's subject, a call's number (gsm 028), otherwise the text's start. */
private fun HubItem.secondLine(): String = when (kind) {
    ItemKind.EMAIL, ItemKind.INVITATION -> subject ?: preview
    ItemKind.MISSED_CALL -> address.orEmpty()
    else -> preview
}.replace('\n', ' ')

/** A 64-bit hash of the row's conversation, never negative (days are): String.hashCode's 32 bits collide in a few thousand rows. */
private fun rowId(item: HubItem, single: Boolean): Long {
    var h = 1125899906842597L
    for (c in "${item.accountId}/${if (single) item.sourceKey else item.conversationId ?: item.sourceKey}") h = 31 * h + c.code
    return h and Long.MAX_VALUE
}
private fun dayId(date: LocalDate) = -1 - date.toEpochDay()

/**
 * The inbox's lines for [filter] and [search]: newest first, a date field before each day, a
 * conversation as one row at its latest message (the Hub's choice, kept: D).
 */
fun inboxLines(snapshot: Snapshot, filter: Filter, search: String?, format: InboxFormat, account: String? = null, searchIn: SearchIn = SearchIn.ANY): List<InboxLine> {
    // Single Messages (Display Style), and the saved messages, one row each.
    val single = !snapshot.options.group || filter == Filter.SAVED
    val rows = hubRows(snapshot.shown(filter, search, account, searchIn), HubView(), format.zone, conversations = !single)
    val times = timeFormatter(format)
    val dates = dayFormatter(format.locale)
    return rows.map { row ->
        when (row) {
            is HubRow.Day -> DayLine(dayId(row.date), dates.format(row.date).replace(".", ""), row.date)
            is HubRow.Entry -> ItemLine(
                id = rowId(row.item, single),
                sender = row.item.sender,
                time = times.format(Instant.ofEpochMilli(row.item.timestamp).atZone(format.zone)),
                preview = row.item.secondLine(),
                icon = row.icon(snapshot.smsApp),
                unread = row.unread > 0,
                item = row.item,
                related = row.item.address?.takeIf { it.isNotBlank() } ?: row.item.sender,
                saved = row.item.savedKey() in snapshot.saved,
            )
        }
    }
}

/**
 * The accounts with something new, each up to the arrival of its newest new item: what opening
 * Messages marks seen ("click the Messages icon" removes the new indicator, guide p. 31, B), what
 * arrives after stays new. New: unread and arrived after its account's last visit, the echoes
 * left out, as the launcher counts it (HubBadges). Only what [filter] shows is seen: opening Text
 * Messages sees the texts and leaves the rest new. An account is seen up to a
 * time, not item by item: a text that came as a notification of the SMS app sees the other apps'
 * notifications that arrived before it (D).
 */
fun toSee(snapshot: Snapshot, filter: Filter = Filter.ALL, account: String? = null): Map<String, Long> {
    val seen = snapshot.accounts.associate { it.id to it.seenAt }
    val newest = HashMap<String, Long>()
    for (item in snapshot.items.withoutEchoes(snapshot.smsApp, snapshot.dialer)) {
        if (account != null && item.accountId != account) continue
        val since = seen[item.accountId] ?: continue
        if (item.isUnread() && item.addedAt > since && filter.shows(item, snapshot.smsApp)) newest.merge(item.accountId, item.addedAt, ::maxOf)
    }
    return newest
}
