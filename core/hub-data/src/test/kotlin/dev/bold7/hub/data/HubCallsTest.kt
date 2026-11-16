package dev.bold7.hub.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HubCallsTest {
    private val keys = arrayOf("k@1") to arrayOf("notifications")

    @Test fun aCallIsOpenOrAWriteOfTheItemsItNames() {
        assertEquals(HubCall.Open("k"), hubCall(HubContract.METHOD_OPEN, "k", null, null, locked = false))
        assertEquals(
            HubCall.Write(HubContract.METHOD_DELETE, listOf(ItemKey("notifications", "k@1"))),
            hubCall(HubContract.METHOD_DELETE, null, keys.second, keys.first, locked = false),
        )
    }

    @Test fun lockedNothingIsOpenedNorWritten() {
        assertEquals(HubCall.Refused, hubCall(HubContract.METHOD_OPEN, "k", null, null, locked = true))
        for (method in listOf(HubContract.METHOD_MARK_READ, HubContract.METHOD_MARK_UNREAD, HubContract.METHOD_DELETE)) {
            assertEquals(HubCall.Refused, hubCall(method, null, keys.second, keys.first, locked = true))
        }
    }

    @Test fun aVisitNamesItsAccountsAndIsRefusedLocked() {
        assertEquals(
            HubCall.Seen(mapOf("sms" to 20L, "calls" to 30L)),
            hubCall(HubContract.METHOD_MARK_SEEN, null, arrayOf("sms", "calls"), null, locked = false, upTo = longArrayOf(20, 30)),
        )
        assertEquals(HubCall.Refused, hubCall(HubContract.METHOD_MARK_SEEN, null, arrayOf("sms"), null, locked = true, upTo = longArrayOf(1)))
        assertFailsWith<IllegalArgumentException> { hubCall(HubContract.METHOD_MARK_SEEN, null, arrayOf("sms"), null, locked = false) }
        assertFailsWith<IllegalArgumentException> { hubCall(HubContract.METHOD_MARK_SEEN, null, null, null, locked = false, upTo = longArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { hubCall(HubContract.METHOD_MARK_SEEN, null, arrayOf("sms", "calls"), null, locked = false, upTo = longArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> {
            val many = HubContract.KEYS_MAX + 1
            hubCall(HubContract.METHOD_MARK_SEEN, null, Array(many) { "a$it" }, null, locked = false, upTo = LongArray(many))
        }
    }

    @Test fun anUnknownOrMalformedCallFails() {
        assertFailsWith<IllegalArgumentException> { hubCall("drop", null, null, null, locked = false) }
        assertFailsWith<IllegalArgumentException> { hubCall(HubContract.METHOD_OPEN, null, null, null, locked = false) }
        assertFailsWith<IllegalArgumentException> { hubCall(HubContract.METHOD_MARK_READ, null, arrayOf("sms"), null, locked = false) }
    }

    // A notification of two messages: yesterday's K@1, today's K@2 still posted.
    private fun note(at: Int, read: Boolean = false, deleted: Boolean = false) =
        HubItem("notifications", "K@$at", ItemKind.NOTIFICATION, at.toLong(), "Ann", conversationId = "K", read = read, deleted = deleted)

    private fun write(method: String, vararg sourceKeys: String) = HubCall.Write(method, sourceKeys.map { ItemKey("notifications", it) })

    @Test fun theNotificationIsReadInItsAppOnlyOnceNothingOfItIsUnread() {
        // "Mark Prior Read" on yesterday: today's message stays unread, Android is told nothing.
        assertEquals(HubEffects(), hubEffects(write(HubContract.METHOD_MARK_READ, "K@1"), listOf(note(1, read = true), note(2))))
        assertEquals(
            HubEffects(markRead = setOf("K")),
            hubEffects(write(HubContract.METHOD_MARK_READ, "K@1", "K@2"), listOf(note(1, read = true), note(2, read = true))),
        )
        // A deleted unread message holds nothing back.
        assertEquals(
            HubEffects(markRead = setOf("K")),
            hubEffects(write(HubContract.METHOD_MARK_READ, "K@2"), listOf(note(1, deleted = true), note(2, read = true))),
        )
    }

    @Test fun theNotificationIsCancelledOnlyOnceAllOfItIsDeleted() {
        assertEquals(HubEffects(), hubEffects(write(HubContract.METHOD_DELETE, "K@1"), listOf(note(1, deleted = true), note(2))))
        assertEquals(
            HubEffects(cancel = setOf("K")),
            hubEffects(write(HubContract.METHOD_DELETE, "K@1", "K@2"), listOf(note(1, deleted = true), note(2, deleted = true))),
        )
    }

    @Test fun onlyTheNotificationsNamedAndHeldAreTouched() {
        val other = HubItem("notifications", "J@1", ItemKind.NOTIFICATION, 1, "Bo", conversationId = "J", read = true)
        val sms = HubItem("sms", "sms:1", ItemKind.SMS, 1, "Al", conversationId = "K", read = true)
        assertEquals(HubEffects(markRead = setOf("K")), hubEffects(write(HubContract.METHOD_MARK_READ, "K@1", "X@1"), listOf(note(1, read = true), other, sms)))
        assertEquals(HubEffects(), hubEffects(write(HubContract.METHOD_MARK_UNREAD, "K@1"), listOf(note(1))))
    }
}
