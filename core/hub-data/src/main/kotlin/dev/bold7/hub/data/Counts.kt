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
 * Every unread item by kind, seen in the Hub or not: the numbers of the home banner's indicator
 * row, of an app's status bar and of the lock screen (BBOS7-VISUEL §1, sim-03, sim-43, sim-64: the
 * same counters everywhere, C). A text counts as SMS, an MMS or a chat of the SMS app [smsApp]
 * alike ([isText]). Any other app's notifications count by app ([unreadByApp]), with the app's own
 * icon. A notification that only repeats an
 * SMS or a missed call is left out too ([withoutEchoes]): one call, one count.
 */
fun unreadByKind(accounts: List<Account>, items: List<HubItem>, smsApp: String?, dialer: String?): Map<ItemKind, Int> {
    val ids = accounts.mapTo(HashSet()) { it.id }
    return items.filterNot { it.isSystemNotice() }.withoutEchoes(smsApp, dialer)
        .filter { it.accountId in ids && it.isUnread() }
        .mapNotNull { if (it.isText(smsApp)) ItemKind.SMS else it.kind.takeIf { k -> k != ItemKind.NOTIFICATION } }
        .groupingBy { it }.eachCount()
}

/**
 * Every other app's unread notifications, by package, the app with the newest unread item first:
 * its own indicator in the banners, after the counters of [unreadByKind] (the firmware's Facebook,
 * Twitter and App World carry their own ribbon icon, so OS 7 showed a third-party app's indicator
 * beside its own, A; the order is D). What [unreadByKind] counts or leaves out stays out here: the
 * SMS app's chats, echoes, Android's own notices.
 */
fun unreadByApp(accounts: List<Account>, items: List<HubItem>, smsApp: String?, dialer: String?): Map<String, Int> {
    val ids = accounts.mapTo(HashSet()) { it.id }
    return items.filterNot { it.isSystemNotice() }.withoutEchoes(smsApp, dialer)
        .filter { it.accountId in ids && it.isUnread() && it.kind == ItemKind.NOTIFICATION && it.folder != null && !it.isText(smsApp) }
        .sortedByDescending { it.timestamp }
        .groupingBy { it.folder!! }.eachCount()
}
