package dev.bold7.messages

import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeTest {
    private val all = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)

    @Test fun theDoorIsOpenWhenEveryMethodUsedIsThere() {
        assertEquals(ApiState.Open, contract(all))
        // MarkMessagesAsRead (2) only serves Mark Prior Read: not required.
        assertEquals(ApiState.Open, contract(listOf(1, 3, 5, 7)))
    }

    @Test fun aMissingMethodShutsIt() {
        assertEquals(ApiState.Shut(Why.CONTRACT), contract(listOf(1, 3, 5)))
        assertEquals(ApiState.Shut(Why.CONTRACT), contract(emptyList()))
    }

    @Test fun whyTheDoorIsShut() {
        // The consent switch answers without the consent: off, that is the reason, whatever the code.
        assertEquals(Why.NO_CONSENT, why("FAILED_PRECONDITION", consent = false))
        assertEquals(Why.NO_CONSENT, why("PERMISSION_DENIED", consent = false))
        // "Messaging API service disabled": the hook is not in (or Messages is gone).
        assertEquals(Why.CLOSED, why("UNAVAILABLE", consent = null))
        assertEquals(Why.REFUSED, why("PERMISSION_DENIED", consent = true))
        assertEquals(Why.REFUSED, why("UNAUTHENTICATED", consent = null))
        assertEquals(Why.FAILED, why("INTERNAL", consent = true))
    }

    // The door waited for: only a reason that may be Google Messages
    // starting is asked again, a bounded number of times.

    private fun probe(vararg answers: ApiState): Pair<MutableList<ApiState>, suspend () -> ApiState> {
        val left = answers.toMutableList()
        return left to { left.removeAt(0) }
    }

    @Test fun anOpenDoorIsAskedOnce() = runTest {
        val (left, ask) = probe(ApiState.Open, ApiState.Open)
        assertEquals(ApiState.Open, awaitDoor(3, 1_000, ask))
        assertEquals(1, left.size)
        assertEquals(0, currentTime)
    }

    @Test fun aClosedDoorIsAskedAgainWhileGoogleMessagesStarts() = runTest {
        val (_, ask) = probe(ApiState.Shut(Why.CLOSED), ApiState.Shut(Why.FAILED), ApiState.Open)
        assertEquals(ApiState.Open, awaitDoor(3, 1_000, ask))
        assertEquals(2_000, currentTime)
    }

    @Test fun aCertainReasonIsNotAskedAgain() = runTest {
        for (why in listOf(Why.NO_PERMISSION, Why.NO_CONSENT, Why.REFUSED, Why.CONTRACT)) {
            val (left, ask) = probe(ApiState.Shut(why), ApiState.Open)
            assertEquals(ApiState.Shut(why), awaitDoor(3, 1_000, ask))
            assertEquals(1, left.size)
        }
    }

    @Test fun aDoorStillClosedAfterTheLastTryIsShut() = runTest {
        // The way back to Google Messages stays: nothing is lost when the door is really shut.
        val (left, ask) = probe(ApiState.Shut(Why.CLOSED), ApiState.Shut(Why.CLOSED), ApiState.Shut(Why.CLOSED), ApiState.Open)
        assertEquals(ApiState.Shut(Why.CLOSED), awaitDoor(3, 1_000, ask))
        assertEquals(1, left.size)
        assertEquals(2_000, currentTime)
    }

    private fun sms(id: Long, thread: String, kind: ItemKind = ItemKind.SMS, read: Boolean = false) =
        HubItem("sms", "${if (kind == ItemKind.MMS) "mms" else "sms"}:$id", kind, id, "x", conversationId = thread, read = read)

    private val call = HubItem("calls", "9", ItemKind.MISSED_CALL, 1, "x")

    @Test fun markReadMarksEachConversationOnce() {
        assertEquals(
            listOf(ApiCall.MarkConversationRead(4), ApiCall.MarkConversationRead(7)),
            apiCalls(Act.MARK_READ, listOf(sms(1, "4"), sms(2, "4", ItemKind.MMS), sms(3, "7"), call)),
        )
    }

    @Test fun markPriorReadNamesTheUnreadMessages() {
        assertEquals(
            listOf(ApiCall.MarkMessagesRead(listOf("content://sms/1", "content://mms/3"))),
            apiCalls(Act.MARK_PRIOR_READ, listOf(sms(1, "4"), sms(2, "4", read = true), sms(3, "7", ItemKind.MMS), call)),
        )
        assertEquals(emptyList(), apiCalls(Act.MARK_PRIOR_READ, listOf(sms(2, "4", read = true))))
    }

    @Test fun deleteDeletesEachMessageOfTheRowInGoogleMessages() {
        val expected = listOf(ApiCall.Delete("content://sms/1"), ApiCall.Delete("content://mms/3"))
        assertEquals(expected, apiCalls(Act.DELETE, listOf(sms(1, "4"), sms(3, "4", ItemKind.MMS), call)))
        assertEquals(expected, apiCalls(Act.DELETE_PRIOR, listOf(sms(1, "4"), sms(3, "4", ItemKind.MMS), call)))
    }

    @Test fun markUnreadHasNoMethod() {
        assertEquals(emptyList(), apiCalls(Act.MARK_UNREAD, listOf(sms(1, "4"))))
    }

    @Test fun aNotificationOrAStrangeKeyIsLeftOut() {
        val notification = HubItem("notifications", "k", ItemKind.NOTIFICATION, 1, "x", conversationId = "k")
        val odd = HubItem("sms", "weird", ItemKind.SMS, 1, "x", conversationId = "nope")
        assertEquals(emptyList(), apiCalls(Act.DELETE, listOf(notification, odd)))
        assertEquals(emptyList(), apiCalls(Act.MARK_READ, listOf(notification, odd)))
    }

    @Test fun telephonyUris() {
        assertEquals("content://sms/12", telephonyUri("sms:12"))
        assertEquals("content://mms/5", telephonyUri("mms:5"))
        assertEquals(null, telephonyUri("demo:1"))
        assertEquals(null, telephonyUri("sms:x"))
    }
}
