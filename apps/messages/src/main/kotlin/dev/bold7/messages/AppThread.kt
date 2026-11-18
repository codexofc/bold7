package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.ReplyResult

// Another app's conversation in the thread of OS 7: the Hub's items of one
// notification as bubbles, and the replies handed to the app until it shows them back. Pure;
// Conversation reads the Hub and sends through it.

/** A bubble of the Hub's: its item's source key after this. */
const val HUB_URI = "hub:"

/** A reply handed to the app, its echo awaited: its number after this. */
const val PENDING_URI = "pending:"

/** The threadId of an app's conversation: not one of Telephony's, nor the demo's (-1) nor the showcase's (-100 and below). */
const val APP_THREAD = -2L

/** How long a reply waits for the app's echo before it shows as not confirmed (D). */
const val ECHO_MS = 30_000L

/** One other app's conversation as the Hub holds it (AndroidInboxReader): blocking, off the main thread only. */
interface AppThreads {
    /** The items of the notification [key]'s conversation, their texts whole; null: unreadable. */
    fun items(key: String): List<HubItem>?

    /** Whether its live notification has a reply that takes text, the phone unlocked. */
    fun canReply(key: String): Boolean

    /** [text] through its notification's reply; throws when the Hub cannot be asked. */
    fun reply(key: String, text: String): ReplyResult

    /** A write of the Hub's (HubContract.METHOD_MARK_READ…); [hubOnly]: the app's own "mark as read" is not sent. */
    fun write(method: String, keys: List<ItemKey>, hubOnly: Boolean)
}

/**
 * The conversation's [items] as bubbles, oldest first, the deleted left out: the user's own
 * messages sent (the tick, D: the app does not say whether they were delivered), the others
 * received or unread. In a group (its items have the group's name as subject, HubSources), each
 * received bubble names its sender (D).
 */
fun appMessages(items: List<HubItem>): List<ThreadMessage> {
    val shown = items.filter { !it.deleted }
    val group = shown.any { it.subject != null }
    return shown.sortedWith(compareBy<HubItem> { it.timestamp }.thenBy { it.sourceKey }).map {
        val out = it.box == Box.SENT
        val delivery = when {
            out -> Delivery.SENT
            it.read -> Delivery.RECEIVED
            else -> Delivery.UNREAD
        }
        ThreadMessage(HUB_URI + it.sourceKey, it.timestamp, out, it.preview, delivery, from = it.sender.takeIf { group && !out })
    }
}

/** A reply handed to the app at [at]; [known]: the sent bubbles of the same text already there, which are not its echo. */
data class Pending(val id: Long, val text: String, val at: Long, val known: Set<String>)

/** The reply [text] handed to the app at [at], [shown] the bubbles then. */
fun pendingReply(id: Long, text: String, at: Long, shown: List<ThreadMessage>) =
    Pending(id, text, at, shown.filter { it.out && same(it.text, text) }.mapTo(HashSet()) { it.uri })

/**
 * [shown] with each reply of [pending] that has no echo yet: a sent bubble of its text that was
 * not there when it left, each echo claimed by one reply only (two replies of one text need two).
 * A reply waiting shows as sending, then as not confirmed [ECHO_MS] after it left (D). Returns the
 * bubbles and the replies still waiting.
 */
fun withPending(shown: List<ThreadMessage>, pending: List<Pending>, now: Long): Pair<List<ThreadMessage>, List<Pending>> {
    val claimed = HashSet<String>()
    val waiting = pending.filter { p ->
        val echo = shown.firstOrNull { it.out && it.uri !in p.known && it.uri !in claimed && same(it.text, p.text) }
        echo?.let { claimed += it.uri } == null
    }
    val bubbles = waiting.map { ThreadMessage("$PENDING_URI${it.id}", it.at, true, it.text, if (now - it.at >= ECHO_MS) Delivery.UNCONFIRMED else Delivery.SENDING) }
    return (shown + bubbles).sortedBy { it.time } to waiting
}

/** An app may trim what it shows back. */
private fun same(a: String, b: String) = a.trim() == b.trim()
