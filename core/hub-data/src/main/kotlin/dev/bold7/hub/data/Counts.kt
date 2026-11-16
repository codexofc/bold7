package dev.bold7.hub.data

/** An account's counters: [unread] under its icon (accounts list, Peek, lock screen), [new] for the splat. */
data class Count(val unread: Int, val new: Int)

/** Per account, in the accounts' order, and the Hub's total. */
data class Counts(val byAccount: Map<String, Count>, val total: Count)

/**
 * Only received items count: a draft, a sent or an outgoing item is never waiting to be read, nor
 * is an item deleted in the Hub.
 */
fun HubItem.isUnread() = !read && box == Box.INBOX && !deleted

/**
 * A text message, what Text Messages shows and its icon's star counts: an
 * SMS, an MMS, or a notification of the default SMS app [smsApp], an RCS chat that Google
 * Messages did not copy into Telephony (D).
 */
fun HubItem.isText(smsApp: String?) =
    kind == ItemKind.SMS || kind == ItemKind.MMS || (kind == ItemKind.NOTIFICATION && smsApp != null && folder == smsApp)

/**
 * The counters of [accounts]. "New" is unread and arrived after the
 * account's last visit (addedAt after seenAt): a mail dated before the visit but synced after it is
 * new. That the splat marks this, not the unread count, is a deduction (D).
 */
fun counts(accounts: List<Account>, items: List<HubItem>): Counts {
    val unread = items.filter { it.isUnread() }.groupBy { it.accountId }
    val byAccount = accounts.sortedBy { it.position }.associate { account ->
        val own = unread[account.id].orEmpty()
        account.id to Count(own.size, own.count { it.addedAt > account.seenAt })
    }
    return Counts(byAccount, Count(byAccount.values.sumOf { it.unread }, byAccount.values.sumOf { it.new }))
}

/** Packages whose notifications the Hub stores for the banner's list but never counts, beyond [isSystemNotification]. */
private val SYSTEM_PACKAGES = setOf(
    "com.android.providers.downloads",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.google.android.gsf",
)

/** Android's own notices (charging, USB, a module updated...): no line of OS 7, no counter. */
fun HubItem.isSystemNotice() = kind == ItemKind.NOTIFICATION && (isSystemNotification() || folder in SYSTEM_PACKAGES)

/**
 * Every unread item by kind, seen in the Hub or not, MMS counted with SMS: the numbers of the home
 * banner's indicator row, of an app's status bar and of the lock screen (BBOS7-VISUEL §1, sim-03,
 * sim-43, sim-64: the same counters everywhere, C). Android's own notices are left out, and so is
 * a notification that only repeats an SMS or a missed call ([withoutEchoes]): one call, one count.
 */
fun unreadByKind(accounts: List<Account>, items: List<HubItem>, smsApp: String?, dialer: String?): Map<ItemKind, Int> {
    val ids = accounts.mapTo(HashSet()) { it.id }
    return items.filterNot { it.isSystemNotice() }.withoutEchoes(smsApp, dialer)
        .filter { it.accountId in ids && it.isUnread() }
        .groupingBy { if (it.kind == ItemKind.MMS) ItemKind.SMS else it.kind }.eachCount()
}
