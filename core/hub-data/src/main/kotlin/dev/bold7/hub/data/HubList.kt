package dev.bold7.hub.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// What the Hub's list shows and in what order. Pure: the launcher's
// page and apps/hub render the rows, they decide nothing.

/** Pinch filter criteria. Priority and meeting invites are not filters yet. */
enum class Pinch { UNREAD, FLAGGED, DRAFTS, SENT, LEVEL1 }

/** The list's view: the whole Hub or one account, maybe one of its folders, maybe pinched. */
data class HubView(
    /** null: the Hub, every account. */
    val account: String? = null,
    /** A folder of [account]; null: all of them. */
    val folder: String? = null,
    val pinch: Pinch? = null,
)

sealed interface HubViewEvent {
    data class AccountChosen(val account: String?) : HubViewEvent
    data class FolderChosen(val folder: String?) : HubViewEvent
    data class Pinched(val criterion: Pinch) : HubViewEvent
    data object Spread : HubViewEvent
}

fun HubView.reduce(event: HubViewEvent): HubView = when (event) {
    // A folder belongs to its account. Keeping the pinch across accounts is my guess (D).
    is HubViewEvent.AccountChosen -> HubView(account = event.account, pinch = pinch)
    is HubViewEvent.FolderChosen -> if (account == null) this else copy(folder = event.folder)
    is HubViewEvent.Pinched -> copy(pinch = event.criterion)
    HubViewEvent.Spread -> copy(pinch = null)
}

fun HubView.shows(item: HubItem): Boolean =
    !item.deleted &&
        (account == null || item.accountId == account) &&
        (folder == null || item.folder == folder) &&
        when (pinch) {
            null -> true
            Pinch.UNREAD -> item.isUnread()
            Pinch.FLAGGED -> item.flagged
            Pinch.DRAFTS -> item.box == Box.DRAFT
            Pinch.SENT -> item.box == Box.SENT
            Pinch.LEVEL1 -> item.level1
        }

/** How far apart an item and the notification that announced it can be (D: by eye). */
const val ECHO_WINDOW_MS = 5 * 60_000L

/**
 * A name or a number as two sources may write it: "+33 6 12 34 56 78" is "+33612345678", and a
 * number is known by its last 9 digits, so that "07 00 00 00 93" is "+33700000093" (seen on the
 * phone: the provider writes the international form, Google Messages the national one). A shorter
 * number, a short code, must match whole.
 */
fun numberKey(name: String): String {
    val kept = name.filter { it.isLetterOrDigit() || it == '+' }.lowercase()
    val digits = kept.removePrefix("+")
    // Simplification: last 9 digits, the national part in France and the US; use PhoneNumberUtils'
    // loose match if a country with longer numbers ever writes two forms.
    return if (digits.length >= 9 && digits.all { it.isDigit() }) digits.takeLast(9) else kept
}

/**
 * Without the notifications that only repeat an item a provider gave the Hub, each within
 * [ECHO_WINDOW_MS] of it, before or after, by its last post or by its Notification.when: the [smsApp]'s for an SMS or MMS of the same text from
 * the same sender, the [dialer]'s for a missed call from the caller it names (its title or its
 * text: the dialer titles it "Missed call"). Everything the providers lack stays, an RCS chat of
 * the SMS app first of all. Left out of the list only: the trace stays in the store. A null app
 * leaves nothing out; an unknown sender never matches.
 */
fun List<HubItem>.withoutEchoes(smsApp: String?, dialer: String?): List<HubItem> {
    val texts = HashMap<Pair<String, String>, MutableList<Long>>()
    val calls = HashMap<String, MutableList<Long>>()
    for (item in this) {
        val sender = numberKey(item.sender).ifEmpty { continue }
        when (item.kind) {
            ItemKind.SMS, ItemKind.MMS -> texts.getOrPut(item.preview.trim() to sender) { ArrayList() } += item.timestamp
            ItemKind.MISSED_CALL -> calls.getOrPut(sender) { ArrayList() } += item.timestamp
            else -> Unit
        }
    }
    fun near(times: List<Long>?, item: HubItem): Boolean {
        // The item's time is the last postTime; its key keeps Notification.when (Posted.toItem),
        // which a notification posted again (Google Messages restarted) does not change.
        val shown = item.sourceKey.substringAfterLast('@').toLongOrNull()
        return times.orEmpty().any { kotlin.math.abs(it - item.timestamp) <= ECHO_WINDOW_MS || shown != null && kotlin.math.abs(it - shown) <= ECHO_WINDOW_MS }
    }
    return filterNot { item ->
        item.kind == ItemKind.NOTIFICATION && when (item.folder) {
            null -> false
            smsApp -> near(texts[item.preview.trim() to numberKey(item.sender)], item)
            dialer -> near(calls[numberKey(item.sender)], item) || near(calls[numberKey(item.preview)], item)
            else -> false
        }
    }
}

sealed interface HubRow {
    /** Sticky day header, in the phone's zone. */
    data class Day(val date: LocalDate) : HubRow

    /** One item, or a conversation at its latest item: [count] items, [unread] of them unread. */
    data class Entry(val item: HubItem, val count: Int, val unread: Int) : HubRow
}

private val newestFirst = compareByDescending<HubItem> { it.timestamp }.thenBy { it.accountId }.thenBy { it.sourceKey }

/**
 * The list's rows: [items] the [view] shows, newest first, a [HubRow.Day] before each day. With
 * [conversations], the items of one conversation (same account and conversationId) are one entry.
 * Sorts everything in memory: fine for a few thousand items, to page in SQL beyond.
 */
fun hubRows(items: List<HubItem>, view: HubView, zone: ZoneId, conversations: Boolean): List<HubRow> {
    val shown = items.filter(view::shows)
    val entries = if (conversations) {
        shown.groupBy { it.conversationId?.let { c -> it.accountId to c } ?: it }.values.map { group ->
            HubRow.Entry(group.minWith(newestFirst), group.size, group.count { it.isUnread() })
        }
    } else {
        shown.map { HubRow.Entry(it, 1, if (it.isUnread()) 1 else 0) }
    }
    val rows = mutableListOf<HubRow>()
    var day: LocalDate? = null
    for (entry in entries.sortedWith(compareBy(newestFirst) { it.item })) {
        val date = Instant.ofEpochMilli(entry.item.timestamp).atZone(zone).toLocalDate()
        if (date != day) rows += HubRow.Day(date).also { day = date }
        rows += entry
    }
    return rows
}
