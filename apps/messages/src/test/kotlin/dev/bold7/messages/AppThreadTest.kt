package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Another app's conversation in the thread of OS 7: the Hub's items as bubbles, the replies awaiting their echo. */
class AppThreadTest {
    private val key = "0|com.instagram.android|1|t|10200"

    private fun item(at: Long, text: String, sender: String = "Erin", mine: Boolean = false, read: Boolean = mine, group: String? = null, deleted: Boolean = false) =
        HubItem(
            "notifications", "$key@$at", ItemKind.NOTIFICATION, at, sender, subject = group, preview = text,
            box = if (mine) Box.SENT else Box.INBOX, folder = "com.instagram.android", conversationId = key, read = read, deleted = deleted, messaging = true,
        )

    @Test fun theConversationIsItsItemsOldestFirstReceivedAndSent() {
        val shown = appMessages(listOf(item(3, "Lunch?"), item(1, "Hi", read = true), item(2, "Hello", sender = "Erin", mine = true)))
        assertEquals(listOf("Hi", "Hello", "Lunch?"), shown.map { it.text })
        assertEquals(listOf(false, true, false), shown.map { it.out })
        assertEquals(listOf(Delivery.RECEIVED, Delivery.SENT, Delivery.UNREAD), shown.map { it.delivery })
        assertEquals("$HUB_URI$key@1", shown.first().uri)
        // One person: no name on the bubbles, the band's.
        assertTrue(shown.all { it.from == null })
    }

    @Test fun aGroupNamesTheSenderOfEachReceivedBubble() {
        val shown = appMessages(listOf(item(1, "Hi", sender = "Ann", group = "Climbing"), item(2, "Yo", sender = "Bob", group = "Climbing"), item(3, "Me", sender = "Climbing", mine = true, group = "Climbing")))
        assertEquals(listOf("Ann", "Bob", null), shown.map { it.from })
        val state = ConversationState(threadId = APP_THREAD, title = "Climbing", notification = key, app = "com.instagram.android", messages = shown)
        assertEquals(listOf("Ann", "Bob", "Me"), shown.map { state.senderLabel(it, "Me") })
    }

    @Test fun aDeletedMessageIsNotShown() {
        assertEquals(listOf("Hi"), appMessages(listOf(item(1, "Hi"), item(2, "Gone", deleted = true))).map { it.text })
    }

    @Test fun aReplyShowsSendingUntilItsEchoThenSent() {
        val before = appMessages(listOf(item(1, "Hi")))
        val reply = pendingReply(0, "On my way", at = 10_000, before)
        val (waiting, still) = withPending(before, listOf(reply), now = 11_000)
        assertEquals(Delivery.SENDING, waiting.last().delivery)
        assertEquals("On my way", waiting.last().text)
        assertTrue(waiting.last().out)
        assertEquals(listOf(reply), still)
        // The app updates its notification with the message: the Hub keeps it as a sent one.
        val echoed = appMessages(listOf(item(1, "Hi", read = true), item(12_000, "On my way", mine = true)))
        val (shown, left) = withPending(echoed, listOf(reply), now = 13_000)
        assertEquals(listOf(Delivery.RECEIVED, Delivery.SENT), shown.map { it.delivery })
        assertTrue(left.isEmpty())
    }

    @Test fun withoutEchoInThirtySecondsTheReplyIsNotConfirmed() {
        val reply = pendingReply(0, "OK", at = 10_000, emptyList())
        assertEquals(Delivery.SENDING, withPending(emptyList(), listOf(reply), now = 10_000 + ECHO_MS - 1).first.single().delivery)
        assertEquals(Delivery.UNCONFIRMED, withPending(emptyList(), listOf(reply), now = 10_000 + ECHO_MS).first.single().delivery)
        // A late echo still settles it.
        val (shown, left) = withPending(appMessages(listOf(item(99_000, "OK", mine = true))), listOf(reply), now = 100_000)
        assertEquals(listOf(Delivery.SENT), shown.map { it.delivery })
        assertTrue(left.isEmpty())
    }

    @Test fun anEarlierMessageOfTheSameTextIsNoEcho() {
        val before = appMessages(listOf(item(1, "OK", mine = true)))
        val reply = pendingReply(0, "OK", at = 10_000, before)
        val (shown, left) = withPending(before, listOf(reply), now = 11_000)
        assertEquals(listOf(Delivery.SENT, Delivery.SENDING), shown.map { it.delivery })
        assertEquals(1, left.size)
    }

    @Test fun twoQuickRepliesOfOneTextNeedTwoEchoes() {
        val first = pendingReply(0, "ok", at = 10_000, emptyList())
        val second = pendingReply(1, "ok", at = 10_500, emptyList())
        val one = appMessages(listOf(item(10_200, "ok", mine = true)))
        val (shown, left) = withPending(one, listOf(first, second), now = 11_000)
        assertEquals(listOf(Delivery.SENT, Delivery.SENDING), shown.map { it.delivery })
        assertEquals(listOf(second), left)
        val two = appMessages(listOf(item(10_200, "ok", mine = true), item(10_600, "ok", mine = true)))
        assertTrue(withPending(two, left, now = 12_000).second.isEmpty())
    }

    @Test fun aLongTextAndEmojiAreTheirOwnEcho() {
        val long = "😀 ".repeat(2500).trim()
        val reply = pendingReply(0, long, at = 1, emptyList())
        // An app that trims what it echoes.
        assertTrue(withPending(appMessages(listOf(item(2, " $long ", mine = true))), listOf(reply), now = 3).second.isEmpty())
    }

    @Test fun aMessagingAppsConversationOpensItsThread() {
        assertEquals(Opening.AppThread(key, "com.instagram.android"), opening(item(1, "Hi")))
        // Not MessagingStyle (a mail app's, a delivery's): the app, as before.
        assertEquals(Opening.Notification(key, "com.instagram.android"), opening(item(1, "Hi").copy(messaging = false)))
    }
}
