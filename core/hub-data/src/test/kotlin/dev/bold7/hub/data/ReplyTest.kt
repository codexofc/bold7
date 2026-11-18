package dev.bold7.hub.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A reply to another app's conversation through its notification, and the Hub's own read. */
class ReplyTest {
    private val keys = arrayOf("k@1") to arrayOf("notifications")

    @Test fun aReplyNamesItsNotificationAndItsWholeText() {
        assertEquals(HubCall.Reply("k", "Hi"), hubCall(HubContract.METHOD_REPLY, "k", null, null, locked = false, text = "Hi"))
        // 5000 characters and emoji pass as they are, nothing cut nor trimmed.
        val long = "é😀 ".repeat(1250)
        assertEquals(HubCall.Reply("k", long), hubCall(HubContract.METHOD_REPLY, "k", null, null, locked = false, text = long))
        assertEquals(HubCall.CanReply("k"), hubCall(HubContract.METHOD_CAN_REPLY, "k", null, null, locked = false))
    }

    @Test fun lockedNothingIsRepliedNorAsked() {
        assertEquals(HubCall.Refused, hubCall(HubContract.METHOD_REPLY, "k", null, null, locked = true, text = "Hi"))
        assertEquals(HubCall.Refused, hubCall(HubContract.METHOD_CAN_REPLY, "k", null, null, locked = true))
    }

    @Test fun aReplyWithoutKeyOrTextFails() {
        assertFailsWith<IllegalArgumentException> { hubCall(HubContract.METHOD_REPLY, null, null, null, locked = false, text = "Hi") }
        assertFailsWith<IllegalArgumentException> { hubCall(HubContract.METHOD_REPLY, "k", null, null, locked = false) }
        assertFailsWith<IllegalArgumentException> { hubCall(HubContract.METHOD_REPLY, "k", null, null, locked = false, text = " \n") }
        assertFailsWith<IllegalArgumentException> { hubCall(HubContract.METHOD_CAN_REPLY, null, null, null, locked = false) }
    }

    @Test fun aReadForTheHubOnlyNeverReachesTheApp() {
        val write = hubCall(HubContract.METHOD_MARK_READ, null, keys.second, keys.first, locked = false, hubOnly = true)
        assertEquals(HubCall.Write(HubContract.METHOD_MARK_READ, listOf(ItemKey("notifications", "k@1")), hubOnly = true), write)
        val read = HubItem("notifications", "k@1", ItemKind.NOTIFICATION, 1, "Ann", conversationId = "k", read = true)
        assertEquals(HubEffects(), hubEffects(write as HubCall.Write, listOf(read)))
        // Asked from the menu, the app's own "mark as read" goes.
        assertEquals(HubEffects(markRead = setOf("k")), hubEffects(write.copy(hubOnly = false), listOf(read)))
    }

    // Notification.Action.SEMANTIC_ACTION_REPLY, SEMANTIC_ACTION_MARK_AS_READ.
    private val reply = 1
    private val markRead = 2

    @Test fun theReplyIsTheActionThatTakesFreeText() {
        assertEquals(0, replyAction(listOf(ActionShape(reply, freeText = true))))
        // An app that does not say: the first one with a free text field.
        assertEquals(1, replyAction(listOf(ActionShape(0, freeText = false), ActionShape(0, freeText = true))))
        // The one that says it replies, before another one that takes text.
        assertEquals(2, replyAction(listOf(ActionShape(0, freeText = false), ActionShape(0, freeText = true), ActionShape(reply, freeText = true))))
    }

    @Test fun noFreeTextNoReply() {
        assertNull(replyAction(emptyList()))
        // Choices only (allowFreeFormInput false), or data only: nothing typed could go.
        assertNull(replyAction(listOf(ActionShape(reply, freeText = false), ActionShape(markRead, freeText = false))))
        // A "mark as read" asking for text is not a reply.
        assertNull(replyAction(listOf(ActionShape(markRead, freeText = true))))
    }
}
