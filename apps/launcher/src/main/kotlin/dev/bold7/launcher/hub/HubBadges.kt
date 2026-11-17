package dev.bold7.launcher.hub

import dev.bold7.hub.data.Account
import dev.bold7.hub.data.AccountKind
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.isSystemNotice
import dev.bold7.hub.data.isText
import dev.bold7.hub.data.isUnread
import dev.bold7.hub.data.unreadByApp
import dev.bold7.hub.data.unreadByKind
import dev.bold7.hub.data.withoutEchoes
import dev.bold7.icons.CALENDAR_ICON

// What is new in the Hub and where it shows: the splats of the home and their counters. Pure,
// tested on the JVM; the home draws them.

/** The BBOS 7 icon of Messages, the unified inbox (bbos7_icons.tsv): it carries the splat of what is new. */
const val HUB_ICON = "bbos7_messages"

/** The BBOS 7 icon of Text Messages, Messages opened on its texts: the splat of new texts. */
const val TEXT_MESSAGES_ICON = "bbos7_text_messages"

/**
 * The accounts with something new, each with the arrival (HubItem.addedAt) of its newest new
 * item, their kinds, and the apps whose icon carries a splat. Empty: nothing new.
 */
data class HubBadges(
    val newest: Map<String, Long> = emptyMap(),
    val kinds: Set<AccountKind> = emptySet(),
    val apps: Set<String> = emptySet(),
    /**
     * Every unread item by kind, seen in the Hub or not, MMS counted with SMS: the numbers of the
     * home banner's indicator row (BBOS7-VISUEL §1, sim-03: "2" and the missed call icon, C).
     */
    val counts: Map<ItemKind, Int> = emptyMap(),
    /** Every other app's unread notifications, newest app first (unreadByApp): its own indicator after the counts. */
    val appCounts: Map<String, Int> = emptyMap(),
    /** Something new is a text (isText): the star of Text Messages (gsm 020, C). */
    val texts: Boolean = false,
) {
    val accounts: Set<String> get() = newest.keys

    /** Something is new: the splat of Messages. */
    val hub get() = newest.isNotEmpty()
}

/**
 * New: unread, received, and arrived in the Hub after its account was last seen there
 * (dev.bold7.hub.data.counts, D), counted on what the list shows: a notification that repeats
 * an SMS or a missed call (withoutEchoes) is not new twice. Each new item marks the app that holds
 * it: the default SMS app its texts, the dialer its missed calls, a notification its app.
 */
fun hubBadges(accounts: List<Account>, items: List<HubItem>, smsApp: String?, dialer: String?): HubBadges {
    val byId = accounts.associateBy { it.id }
    val unread = items.filterNot { it.isSystemNotice() }.withoutEchoes(smsApp, dialer).filter { it.accountId in byId && it.isUnread() }
    val counts = unreadByKind(accounts, items, smsApp, dialer)
    val appCounts = unreadByApp(accounts, items, smsApp, dialer)
    val new = unread.filter { it.addedAt > byId.getValue(it.accountId).seenAt }
    if (new.isEmpty()) return HubBadges(counts = counts, appCounts = appCounts)
    val newest = HashMap<String, Long>()
    for (item in new) newest.merge(item.accountId, item.addedAt, ::maxOf)
    return HubBadges(
        newest = newest,
        kinds = new.mapTo(HashSet()) { byId.getValue(it.accountId).kind },
        apps = new.mapNotNullTo(HashSet()) {
            when (it.kind) {
                ItemKind.SMS, ItemKind.MMS -> smsApp
                ItemKind.MISSED_CALL -> dialer
                ItemKind.NOTIFICATION -> it.folder
                ItemKind.EMAIL, ItemKind.INVITATION -> null
            }
        },
        counts = counts,
        appCounts = appCounts,
        texts = new.any { it.isText(smsApp) },
    )
}

/**
 * Whether an app's icon carries a splat: [packageName] holds something new,
 * or its table icon ([tableIcon], asked only then) is the one of Messages (anything new), Text
 * Messages (a new text) or the calendar (a new invitation). A mail account's icon ([account], a
 * shortcut of Messages) carries its own account's only (gsm 020, C).
 */
fun iconSplat(packageName: String, tableIcon: () -> String?, badges: HubBadges, account: String? = null): Boolean {
    if (!badges.hub) return false
    if (account != null) return account in badges.accounts
    if (packageName in badges.apps) return true
    return when (tableIcon()) {
        HUB_ICON -> true
        TEXT_MESSAGES_ICON -> badges.texts
        CALENDAR_ICON -> AccountKind.CALENDAR in badges.kinds
        else -> false
    }
}
