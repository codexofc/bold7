package dev.bold7.hub.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HubContractTest {
    private val full = HubItem(
        accountId = "sms", sourceKey = "sms:7", kind = ItemKind.MMS, timestamp = 1_700_000_000_123, sender = "Alice",
        address = "+33600000000", subject = "Photo", preview = "Line one\nline two", box = Box.SENT, folder = "Inbox",
        conversationId = "12", read = true, flagged = true, importance = Importance.HIGH, level1 = true,
        attachmentCount = 2, addedAt = 1_700_000_000_456, sourceRead = true, deleted = true, category = "msg", messaging = true,
    )

    /** A row as a cursor gives it back across processes: integers as Long, text as String. */
    private fun throughCursor(row: Array<Any?>): (Int) -> Any? = { i ->
        when (val v = row[i]) {
            null -> null
            is Number -> v.toLong()
            else -> v.toString()
        }
    }

    @Test fun anItemAndItsAccountColourCrossTheCursorUnchanged() {
        val row = itemRow(full, AccountColor.PURPLE)
        assertEquals(HubContract.ITEM_COLUMNS.size, row.size)
        assertEquals(full to AccountColor.PURPLE, itemFromRow(throughCursor(row)))
        val bare = HubItem("calls", "3", ItemKind.MISSED_CALL, 5, "")
        assertEquals(bare to AccountColor.NONE, itemFromRow(throughCursor(itemRow(bare, AccountColor.NONE))))
    }

    @Test fun theRowHoldsOnlyWhatACursorCarries() {
        // MatrixCursor and CursorWindow keep Long, String and null; a Boolean would not survive getLong.
        itemRow(full, AccountColor.GREEN).forEach { assertTrue(it == null || it is Long || it is String, "$it") }
    }

    @Test fun aLongTextCrossesAsMuchAsALineShows() {
        // A whole bigText or mail body would cross the binder and stay in the launcher for one line.
        val long = full.copy(subject = "s".repeat(1000), preview = "p".repeat(5000))
        val (read, _) = itemFromRow(throughCursor(itemRow(long, AccountColor.NONE)))
        assertEquals("p".repeat(HubContract.TEXT_MAX), read.preview)
        assertEquals("s".repeat(HubContract.TEXT_MAX), read.subject)
        assertEquals(full.preview, itemFromRow(throughCursor(itemRow(full, AccountColor.NONE))).first.preview)
    }

    @Test fun keysCrossACallAsTwoArraysOfTheSameLength() {
        val keys = listOf(ItemKey("sms", "sms:1"), ItemKey("notifications", "k@1"))
        val (accounts, sourceKeys) = keyArrays(keys)
        assertEquals(keys, itemKeys(accounts, sourceKeys))
        assertFailsWith<IllegalArgumentException> { itemKeys(arrayOf("sms"), emptyArray()) }
        assertFailsWith<IllegalArgumentException> { itemKeys(null, arrayOf("1")) }
        // A call carries at most KEYS_MAX: the binder's buffer is 1 MB for the whole process.
        val many = Array(HubContract.KEYS_MAX + 1) { "x" }
        assertFailsWith<IllegalArgumentException> { itemKeys(many, many) }
    }

    @Test fun aNotificationsItemNamesItsNotification() {
        // The key of a notification may hold an @ (its tag): its item adds "@<time>" after it.
        assertEquals(
            setOf("0|com.app|1|t@g|10100"),
            notificationKeys(listOf(ItemKey("notifications", "0|com.app|1|t@g|10100@880"), ItemKey("sms", "sms:1"))),
        )
    }

    @Test fun anUnknownValueFailsRatherThanGuess() {
        val row = itemRow(full, AccountColor.NONE)
        row[HubContract.ITEM_COLUMNS.indexOf("kind")] = "FAX"
        assertFailsWith<IllegalArgumentException> { itemFromRow(throughCursor(row)) }
    }
}
