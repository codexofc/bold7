package dev.bold7.launcher.notices

import dev.bold7.design.NoticeDimens
import dev.bold7.design.OS7_LOCALE
import dev.bold7.hub.data.Account
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.isSystemNotice
import dev.bold7.hub.data.isUnread
import dev.bold7.hub.data.withoutEchoes
import dev.bold7.launcher.home.UNREAD_CALENDAR
import dev.bold7.launcher.home.UNREAD_EMAIL
import dev.bold7.launcher.home.UNREAD_MISSED_CALL
import dev.bold7.launcher.home.UNREAD_OTHER
import dev.bold7.launcher.home.UNREAD_SMS
import dev.bold7.launcher.hub.HubFormat
import dev.bold7.launcher.hub.hubTime
import dev.bold7.ui.osEmoji
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// The list of notifications that drops from the home's banner (docs/BBOS7-VISUEL.md §5), pure:
// worked out with the Hub's read (HubFeed), off the main thread; NoticesView only draws it.

/** A line of the list: an app's header, or one of its unopened items under it. */
sealed interface NoticeLine

/**
 * An app's header (sim-26, C: the missed call's icon and "Phone"): its icon is a frame of the
 * banner's counters strip, [app] the package a click on it opens (null: none).
 */
data class NoticeHeader(val title: String, val frame: Int, val app: String?) : NoticeLine

/** "04:13  5559876  Missed Call: 13/03/2025…" (sim-26, C): the row of [item]. */
data class NoticeRow(val time: String, val sender: String, val summary: String, val item: HubItem) : NoticeLine

/** The words the list writes, from the launcher's strings: the 9900's apps' names (sim-26, gsm 021, ps 33240, C). */
data class NoticeNames(val messages: String, val textMessages: String, val phone: String, val calendar: String, val missedCall: String)

/**
 * The time column: "04:13" in 24 hours (sim-26, C), "8:10p" in 12 (ps 33240, C), whatever the
 * language (D).
 */
fun noticeTime(epochMs: Long, format: HubFormat): String {
    if (format.clock24) return hubTime(epochMs, format)
    val t = Instant.ofEpochMilli(epochMs).atZone(format.zone)
    return "%d:%02d%c".format((t.hour + 11) % 12 + 1, t.minute, if (t.hour < 12) 'a' else 'p')
}

/**
 * The list: every item the banner's counters count (unread, received, of an account the Hub has,
 * echoes left out: HubBadges.counts), a section per app in the counters' order (email, texts,
 * missed calls, invitations: D, as the banner), then each other app under its home name
 * ([appName], else its package; never Android's own notices) and the counters' "?" bubble, the app with the newest item first
 * (D); in a section, newest first (sim-26, C).
 */
fun notices(
    accounts: List<Account>,
    items: List<HubItem>,
    smsApp: String?,
    dialer: String?,
    format: HubFormat,
    names: NoticeNames,
    appName: (String) -> String?,
): List<NoticeLine> {
    val ids = accounts.mapTo(HashSet()) { it.id }
    val unread = items.filterNot { it.isSystemNotice() }.withoutEchoes(smsApp, dialer).filter { it.accountId in ids && it.isUnread() }.sortedByDescending { it.timestamp }
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(OS7_LOCALE)
    val lines = ArrayList<NoticeLine>()
    fun section(header: NoticeHeader, rows: List<HubItem>) {
        if (rows.isEmpty()) return
        lines += header
        for (item in rows) {
            val summary = when (item.kind) {
                ItemKind.EMAIL, ItemKind.INVITATION -> item.subject ?: item.preview
                ItemKind.MISSED_CALL -> "${names.missedCall}: ${date.format(Instant.ofEpochMilli(item.timestamp).atZone(format.zone))} ${noticeTime(item.timestamp, format)}"
                else -> item.preview
            }
            lines += NoticeRow(noticeTime(item.timestamp, format), osEmoji(item.sender), osEmoji(summary.replace('\n', ' ')), item)
        }
    }
    section(NoticeHeader(names.messages, UNREAD_EMAIL, null), unread.filter { it.kind == ItemKind.EMAIL })
    section(NoticeHeader(names.textMessages, UNREAD_SMS, smsApp), unread.filter { it.kind == ItemKind.SMS || it.kind == ItemKind.MMS })
    section(NoticeHeader(names.phone, UNREAD_MISSED_CALL, dialer), unread.filter { it.kind == ItemKind.MISSED_CALL })
    section(NoticeHeader(names.calendar, UNREAD_CALENDAR, null), unread.filter { it.kind == ItemKind.INVITATION })
    // Grouped in the order of their newest item, the items being sorted already.
    unread.filter { it.kind == ItemKind.NOTIFICATION && it.folder != null }.groupBy { it.folder!! }.forEach { (app, rows) ->
        val name = appName(app) ?: app
        section(NoticeHeader(name, UNREAD_OTHER, app), rows)
    }
    return lines
}

/**
 * The trackpad's step from [focus] (null: the indicator row's zone, sim-100 to sim-103, C): down to
 * the next line, staying on the last; up to the line before, from the first back to the zone.
 */
fun noticeStep(focus: Int?, size: Int, down: Boolean): Int? = when {
    size == 0 -> null
    down -> if (focus == null) 0 else minOf(focus + 1, size - 1)
    focus == null || focus == 0 -> null
    else -> focus - 1
}

/** The focus once the list changed under it (a row opened, an item read elsewhere): still in the list (D). */
fun noticeFocus(focus: Int?, size: Int): Int? = if (focus == null || size == 0) null else minOf(focus, size - 1)

/** Whether line [i] shows, even in part, in the list's [window] px scrolled by [scroll]: only those are drawn. */
fun noticeShown(i: Int, scroll: Int, window: Int): Boolean {
    val top = NoticeDimens.GAP + i * NoticeDimens.LINE
    return top + NoticeDimens.LINE > scroll && top < scroll + window
}

/** Where the list scrolls to bring the focused [line] whole into its [window] px; null: it is already there. */
fun noticeScroll(line: Int, scroll: Int, window: Int): Int? {
    val top = NoticeDimens.GAP + line * NoticeDimens.LINE
    return when {
        top < scroll -> top
        top + NoticeDimens.LINE > scroll + window -> top + NoticeDimens.LINE - window
        else -> null
    }
}
