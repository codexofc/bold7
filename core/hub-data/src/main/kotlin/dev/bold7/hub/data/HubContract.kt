package dev.bold7.hub.data

/**
 * How the launcher and SystemUI read the Hub from apps/hub's process.
 *
 * - [COUNTS_URI]: the counters, one row per account in the accounts' order after a first row for
 *   the whole Hub (null [ACCOUNT], [KIND] and [SEEN]). [UNREAD] and [NEW] count every received
 *   item, the notifications that repeat an SMS or a missed call included (the launcher's list
 *   leaves those out, withoutEchoes); [SEEN] is the account's last visit, epoch ms.
 * - [KINDS_URI]: the unread items by kind ([unreadByKind]), one row per kind that has any
 *   ([KIND] an ItemKind's name, [UNREAD], null [APP]), then one row per other app ([unreadByApp]:
 *   [KIND] NOTIFICATION, [APP] its package), the newest first: the counters of the home banner, of
 *   an app's status bar and of the lock screen, one count for the three. SystemUI reads it with STATUS_BAR, like
 *   [COUNTS_URI], and observes [COUNTS_URI], which every write notifies.
 * - [ITEMS_URI]: every item, in no order, as [ITEM_COLUMNS] ([itemRow], read back by [itemFromRow]).
 * - [METHOD_OPEN]: ContentResolver.call with a notification's key as its arg: its live
 *   contentIntent in [EXTRA_INTENT] for the caller to send, or none when it is gone, has none,
 *   is not one the Hub holds, or the phone is locked. The notification is left as it is: a tap
 *   in Android's shade would close an auto-cancel one (REASON_CLICK), which a listener cannot
 *   do without sending its deleteIntent (REASON_LISTENER_CANCEL).
 * - [METHOD_MARK_READ], [METHOD_MARK_UNREAD], [METHOD_DELETE]: what the user did
 *   in the Hub, on the items named by [EXTRA_ACCOUNTS] and [EXTRA_KEYS] ([keyArrays], at most
 *   [KEYS_MAX] a call). Marked read, a notification's own "mark as read" action is sent too when
 *   it has one; deleted, a notification is cancelled in Android as well. Refused while locked.
 *   With [EXTRA_HUB_ONLY] true, a read is the Hub's alone and the app's action is not sent:
 *   the thread of OS 7 opened, rather than Mark Opened asked from a menu.
 * - [CONVERSATION_URI]/<key>: the items of one notification's conversation (its key, encoded),
 *   their texts whole, as [ITEM_COLUMNS]: the thread of OS 7 of another app's messages.
 * - [METHOD_CAN_REPLY]: with a notification's key as its arg, whether its live notification has a
 *   reply that takes free text, in [EXTRA_CAN_REPLY]; [METHOD_REPLY]: the same, with
 *   [EXTRA_TEXT], fills that reply's RemoteInput and sends its PendingIntent, the answer a
 *   [ReplyResult] name in [EXTRA_REPLY_RESULT]. Refused while locked, checked when it is sent.
 * - [METHOD_MARK_SEEN]: the accounts named by [EXTRA_ACCOUNTS] were seen in the
 *   Hub up to the arrival ([HubItem.addedAt]) given at the same place in [EXTRA_SEEN_UP_TO], the
 *   newest new item the caller showed: those items stop being new (the splat), and what arrived
 *   since stays new. Values of the store, not of a clock: a clock set back or ahead changes
 *   nothing. Refused while locked.
 *
 * [SEEN] goes to the holders of [PERMISSION] only: null for SystemUI, which reads the counters
 * with STATUS_BAR (apps/hub's manifest).
 *
 * Every write of the store notifies [COUNTS_URI], counters or not: the readers of the items
 * observe it too, with a ContentObserver. No polling.
 *
 * For the readers:
 * - before the first unlock after a boot, the query returns null (the provider's app is not direct
 *   boot aware) and no change notification follows the unlock: query again on
 *   Intent.ACTION_USER_UNLOCKED;
 * - an SQLite error in the provider reaches the caller as an exception from the query: catch it
 *   and show nothing rather than crash the launcher or SystemUI.
 */
object HubContract {
    const val AUTHORITY = "dev.bold7.hub.data"
    const val COUNTS_URI = "content://$AUTHORITY/counts"
    const val ITEMS_URI = "content://$AUTHORITY/items"
    const val KINDS_URI = "content://$AUTHORITY/kinds"

    /** Signature permission declared by apps/hub, held by Bold7's own apps. */
    const val PERMISSION = "dev.bold7.permission.READ_HUB"

    const val ACCOUNT = "account"
    const val KIND = "kind"
    const val UNREAD = "unread"
    const val APP = "app"
    const val NEW = "new"
    const val SEEN = "seen"

    const val CONVERSATION_URI = "content://$AUTHORITY/conversation"

    const val METHOD_OPEN = "open"
    const val EXTRA_INTENT = "intent"

    const val METHOD_CAN_REPLY = "canReply"
    const val EXTRA_CAN_REPLY = "canReply"
    const val METHOD_REPLY = "reply"
    const val EXTRA_TEXT = "text"
    const val EXTRA_REPLY_RESULT = "replyResult"
    const val EXTRA_HUB_ONLY = "hubOnly"

    const val METHOD_MARK_READ = "markRead"
    const val METHOD_MARK_UNREAD = "markUnread"
    const val METHOD_DELETE = "delete"
    const val METHOD_MARK_SEEN = "markSeen"
    const val EXTRA_SEEN_UP_TO = "seenUpTo"
    const val EXTRA_ACCOUNTS = "accounts"
    const val EXTRA_KEYS = "keys"

    /** Items a call names at most: a few tens of kB, far under the binder's 1 MB per process. */
    const val KEYS_MAX = 500

    /**
     * Characters of a subject or a preview that cross to the launcher: more than a row of the
     * list shows; the whole text stays in the Hub's database.
     */
    const val TEXT_MAX = 200

    /** The items' cursor: HubItem's fields in its order, then its account's colour. */
    val ITEM_COLUMNS = arrayOf(
        "accountId", "sourceKey", "kind", "timestamp", "sender", "address", "subject", "preview", "box", "folder",
        "conversationId", "read", "flagged", "importance", "level1", "attachmentCount", "addedAt", "accountColor",
        "sourceRead", "deleted", "category", "messaging",
    )
}

private fun Boolean.bit() = if (this) 1L else 0L

/** [item] as a row of the items' cursor: only Long, String and null, what a cursor carries; texts cut at [max]. */
fun itemRow(item: HubItem, color: AccountColor, max: Int = HubContract.TEXT_MAX): Array<Any?> = with(item) {
    arrayOf(
        accountId, sourceKey, kind.name, timestamp, sender, address, subject?.take(max), preview.take(max), box.name, folder,
        conversationId, read.bit(), flagged.bit(), importance.name, level1.bit(), attachmentCount.toLong(), addedAt, color.name,
        sourceRead.bit(), deleted.bit(), category, messaging.bit(),
    )
}

/**
 * An item and its account's colour from a row of the items' cursor, [value] giving column i of
 * [HubContract.ITEM_COLUMNS]. Throws on a value it does not know, an enum from a newer Hub included.
 */
fun itemFromRow(value: (Int) -> Any?): Pair<HubItem, AccountColor> {
    fun text(i: Int) = value(i) as String
    fun number(i: Int) = (value(i) as Number).toLong()
    return HubItem(
        accountId = text(0), sourceKey = text(1), kind = ItemKind.valueOf(text(2)), timestamp = number(3),
        sender = text(4), address = value(5) as String?, subject = value(6) as String?, preview = text(7),
        box = Box.valueOf(text(8)), folder = value(9) as String?, conversationId = value(10) as String?,
        read = number(11) != 0L, flagged = number(12) != 0L, importance = Importance.valueOf(text(13)),
        level1 = number(14) != 0L, attachmentCount = number(15).toInt(), addedAt = number(16),
        sourceRead = number(18) != 0L, deleted = number(19) != 0L,
        category = value(20) as String?, messaging = number(21) != 0L,
    ) to AccountColor.valueOf(text(17))
}

/** [keys] as the two arrays of a call's extras ([HubContract.EXTRA_ACCOUNTS], [HubContract.EXTRA_KEYS]). */
fun keyArrays(keys: List<ItemKey>): Pair<Array<String>, Array<String>> =
    Array(keys.size) { keys[it].accountId } to Array(keys.size) { keys[it].sourceKey }

/** The items a call names; throws on arrays missing, of different lengths, or too long. */
fun itemKeys(accounts: Array<String>?, keys: Array<String>?): List<ItemKey> {
    require(accounts != null && keys != null && accounts.size == keys.size) { "Hub keys: two arrays of one length" }
    require(keys.size <= HubContract.KEYS_MAX) { "Hub keys: at most ${HubContract.KEYS_MAX}" }
    return List(keys.size) { ItemKey(accounts[it], keys[it]) }
}

/** The notifications (StatusBarNotification keys) behind those of [keys] that are notifications' items. */
fun notificationKeys(keys: List<ItemKey>): Set<String> =
    keys.filter { it.accountId == HubAccounts.NOTIFICATIONS.id }.mapTo(HashSet()) { notificationKeyOf(it.sourceKey) }
