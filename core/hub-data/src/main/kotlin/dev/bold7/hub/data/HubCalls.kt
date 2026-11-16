package dev.bold7.hub.data

// What HubProvider.call decides, pure: the call it was asked, refused while locked, and what
// Android is told once a write is in the store. HubProvider only checks its permission, reads and
// writes the store, and does what these say.

sealed interface HubCall {
    /** A notification's live contentIntent ([HubContract.METHOD_OPEN]). */
    data class Open(val key: String) : HubCall

    /** The user's [HubContract.METHOD_MARK_READ], [HubContract.METHOD_MARK_UNREAD] or [HubContract.METHOD_DELETE]. */
    data class Write(val method: String, val keys: List<ItemKey>) : HubCall

    /** The user saw each account in the Hub up to the arrival it maps to ([HubContract.METHOD_MARK_SEEN]). */
    data class Seen(val upTo: Map<String, Long>) : HubCall

    /** The phone is locked: nothing is opened nor written (CONTRIBUTING.md), the answer is empty. */
    data object Refused : HubCall
}

/** The call [method] with its [arg] and extras' arrays; throws on an unknown or malformed one, locked or not. */
fun hubCall(method: String, arg: String?, accounts: Array<String>?, keys: Array<String>?, locked: Boolean, upTo: LongArray? = null): HubCall = when (method) {
    HubContract.METHOD_OPEN -> {
        requireNotNull(arg) { "Hub open: no key" }
        if (locked) HubCall.Refused else HubCall.Open(arg)
    }
    HubContract.METHOD_MARK_READ, HubContract.METHOD_MARK_UNREAD, HubContract.METHOD_DELETE -> {
        val named = itemKeys(accounts, keys)
        if (locked) HubCall.Refused else HubCall.Write(method, named)
    }
    HubContract.METHOD_MARK_SEEN -> {
        require(accounts != null && upTo != null && accounts.size == upTo.size) { "Hub seen: two arrays of one length" }
        require(accounts.size <= HubContract.KEYS_MAX) { "Hub seen: at most ${HubContract.KEYS_MAX}" }
        if (locked) HubCall.Refused else HubCall.Seen(accounts.indices.associate { accounts[it] to upTo[it] })
    }
    else -> throw IllegalArgumentException("Unknown Hub call")
}

/** Notifications to read through their own "mark as read" action, and to cancel in Android. */
data class HubEffects(val markRead: Set<String> = emptySet(), val cancel: Set<String> = emptySet())

/**
 * What Android is told after [write], [stored] being the items of the notifications it names as
 * the store holds them now. A notification is the whole conversation of its items, and an action
 * on some of them (the prior ones of a day, a text and its echo) must not reach the others: it is
 * read in its app only once none of its items is left unread (deleted ones aside), cancelled only
 * once all of them are deleted. A notification the Hub does not hold is never touched.
 */
fun hubEffects(write: HubCall.Write, stored: List<HubItem>): HubEffects {
    val named = notificationKeys(write.keys)
    val byNotification = stored.filter { it.accountId == HubAccounts.NOTIFICATIONS.id && it.conversationId in named }.groupBy { it.conversationId!! }
    return when (write.method) {
        HubContract.METHOD_MARK_READ -> HubEffects(markRead = byNotification.filterValues { items -> items.none { it.isUnreadInHub() } }.keys)
        HubContract.METHOD_DELETE -> HubEffects(cancel = byNotification.filterValues { items -> items.all { it.deleted } }.keys)
        else -> HubEffects()
    }
}

private fun HubItem.isUnreadInHub() = !read && !deleted
