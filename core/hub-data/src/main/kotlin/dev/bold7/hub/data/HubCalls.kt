package dev.bold7.hub.data

// What HubProvider.call decides, pure: the call it was asked, refused while locked, and what
// Android is told once a write is in the store. HubProvider only checks its permission, reads and
// writes the store, and does what these say.

sealed interface HubCall {
    /** A notification's live contentIntent ([HubContract.METHOD_OPEN]). */
    data class Open(val key: String) : HubCall

    /**
     * The user's [HubContract.METHOD_MARK_READ], [HubContract.METHOD_MARK_UNREAD] or [HubContract.METHOD_DELETE].
     * [hubOnly]: read in the Hub alone ([HubContract.EXTRA_HUB_ONLY]), the app's own "mark as read" is not sent.
     */
    data class Write(val method: String, val keys: List<ItemKey>, val hubOnly: Boolean = false) : HubCall

    /** [text] typed in the thread of OS 7, for the notification [key]'s reply action ([HubContract.METHOD_REPLY]). */
    data class Reply(val key: String, val text: String) : HubCall

    /** Whether the notification [key] can be answered from the thread now ([HubContract.METHOD_CAN_REPLY]). */
    data class CanReply(val key: String) : HubCall

    /** The user saw each account in the Hub up to the arrival it maps to ([HubContract.METHOD_MARK_SEEN]). */
    data class Seen(val upTo: Map<String, Long>) : HubCall

    /** The phone is locked: nothing is opened nor written (CONTRIBUTING.md), the answer is empty. */
    data object Refused : HubCall
}

/** The call [method] with its [arg] and extras' arrays; throws on an unknown or malformed one, locked or not. */
fun hubCall(
    method: String,
    arg: String?,
    accounts: Array<String>?,
    keys: Array<String>?,
    locked: Boolean,
    upTo: LongArray? = null,
    text: String? = null,
    hubOnly: Boolean = false,
): HubCall = when (method) {
    HubContract.METHOD_OPEN -> {
        requireNotNull(arg) { "Hub open: no key" }
        if (locked) HubCall.Refused else HubCall.Open(arg)
    }
    HubContract.METHOD_MARK_READ, HubContract.METHOD_MARK_UNREAD, HubContract.METHOD_DELETE -> {
        val named = itemKeys(accounts, keys)
        if (locked) HubCall.Refused else HubCall.Write(method, named, hubOnly)
    }
    // Only what the user typed, whole: never an empty reply (D).
    HubContract.METHOD_REPLY -> {
        requireNotNull(arg) { "Hub reply: no key" }
        require(!text.isNullOrBlank()) { "Hub reply: no text" }
        if (locked) HubCall.Refused else HubCall.Reply(arg, text)
    }
    HubContract.METHOD_CAN_REPLY -> {
        requireNotNull(arg) { "Hub reply: no key" }
        if (locked) HubCall.Refused else HubCall.CanReply(arg)
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
        HubContract.METHOD_MARK_READ -> if (write.hubOnly) HubEffects() else HubEffects(markRead = byNotification.filterValues { items -> items.none { it.isUnreadInHub() } }.keys)
        HubContract.METHOD_DELETE -> HubEffects(cancel = byNotification.filterValues { items -> items.all { it.deleted } }.keys)
        else -> HubEffects()
    }
}

private fun HubItem.isUnreadInHub() = !read && !deleted

/** What became of a reply ([HubContract.METHOD_REPLY]), in [HubContract.EXTRA_REPLY_RESULT]. */
enum class ReplyResult {
    /** Handed to the app's reply action: its notification's update says whether it went. */
    SENT,

    /** The notification is gone, has no reply that takes text, or no listener is bound. */
    NO_REPLY,

    /** The app cancelled its action's PendingIntent: nothing was sent. */
    CANCELED,

    /** Locked, or a notification the Hub does not hold. */
    REFUSED,
}

/**
 * A notification's action as the reply chooses it: its Notification.Action semantic action, and
 * whether one of its RemoteInputs takes free text (allowFreeFormInput, not data only).
 */
data class ActionShape(val semantic: Int, val freeText: Boolean)

/**
 * The action a reply goes through, an index of [actions]: the one that says it replies
 * (SEMANTIC_ACTION_REPLY) and takes free text, else the first that takes free text and is not a
 * "mark as read"; null when no action takes free text (choices only, data only, none).
 */
fun replyAction(actions: List<ActionShape>): Int? {
    val text = actions.withIndex().filter { it.value.freeText && it.value.semantic != SEMANTIC_MARK_AS_READ }
    return (text.firstOrNull { it.value.semantic == SEMANTIC_REPLY } ?: text.firstOrNull())?.index
}

// Notification.Action's values, for the JVM's tests.
private const val SEMANTIC_REPLY = 1
private const val SEMANTIC_MARK_AS_READ = 2
