package dev.bold7.hub.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountsTest {
    private val sms = Account("sms", AccountKind.TEXT_MESSAGES, "Text Messages", position = 1, seenAt = 100)
    private val mail = Account("mail", AccountKind.EMAIL, "Mail", position = 0)
    private val calls = Account("calls", AccountKind.CALLS, "Calls", position = 2)

    // [arrived] is when the store got it; the item's own time does not decide what is new.
    private fun item(account: String, key: String, arrived: Long, read: Boolean = false, box: Box = Box.INBOX) =
        HubItem(account, key, ItemKind.SMS, timestamp = 0, sender = "x", read = read, box = box, addedAt = arrived)

    @Test fun unreadReceivedItemsCountAndThoseAfterTheLastVisitAreNew() {
        val counts = counts(
            listOf(sms, mail, calls),
            listOf(
                item("sms", "1", arrived = 50),
                item("sms", "2", arrived = 150),
                item("sms", "3", arrived = 200, read = true),
                item("mail", "1", arrived = 10),
                // Drafts, sent and outgoing are never unread for the counters.
                item("mail", "2", arrived = 20, box = Box.DRAFT),
                item("mail", "3", arrived = 30, box = Box.OUTBOX),
            ),
        )
        assertEquals(Count(unread = 2, new = 1), counts.byAccount.getValue("sms"))
        assertEquals(Count(unread = 1, new = 1), counts.byAccount.getValue("mail"))
        assertEquals(Count(0, 0), counts.byAccount.getValue("calls"))
        assertEquals(Count(unread = 3, new = 2), counts.total)
    }

    @Test fun newIsJudgedByArrivalNotByTheItemsOwnTime() {
        // A mail dated 21:50, synced at 7:00 after the Hub was seen at 22:00, is new.
        val late = HubItem("sms", "1", ItemKind.EMAIL, timestamp = 50, sender = "x", addedAt = 150)
        assertEquals(Count(unread = 1, new = 1), counts(listOf(sms), listOf(late)).total)
    }

    @Test fun anItemArrivedExactlyAtTheLastVisitIsNotNew() {
        assertEquals(Count(unread = 1, new = 0), counts(listOf(sms), listOf(item("sms", "1", arrived = 100))).total)
    }

    @Test fun accountsComeInTheirOrder() {
        assertEquals(listOf("mail", "sms", "calls"), counts(listOf(sms, calls, mail), emptyList()).byAccount.keys.toList())
    }

    @Test fun anItemOfAnUnknownAccountIsIgnored() {
        assertEquals(Count(0, 0), counts(listOf(sms), listOf(item("gone", "1", 500))).total)
    }

    // What Text Messages shows: SMS, MMS, and a notification of the SMS app,
    // an RCS chat Google Messages did not copy into Telephony.
    @Test fun aTextIsAnSmsAnMmsOrANotificationOfTheSmsApp() {
        fun of(kind: ItemKind, folder: String? = null) = HubItem("a", "1", kind, timestamp = 0, sender = "x", folder = folder)
        assertTrue(of(ItemKind.SMS).isText("sms.app"))
        assertTrue(of(ItemKind.MMS).isText(null))
        assertTrue(of(ItemKind.NOTIFICATION, folder = "sms.app").isText("sms.app"))
        assertFalse(of(ItemKind.NOTIFICATION, folder = "other.app").isText("sms.app"))
        assertFalse(of(ItemKind.NOTIFICATION, folder = null).isText(null))
        assertFalse(of(ItemKind.MISSED_CALL).isText("sms.app"))
        assertFalse(of(ItemKind.EMAIL).isText("sms.app"))
    }

    @Test fun aDeletedItemIsNeverUnread() {
        val deleted = item("sms", "1", arrived = 500).copy(deleted = true)
        assertEquals(Count(0, 0), counts(listOf(sms), listOf(deleted)).total)
    }

    // The home banner's, app bar's and lock screen's numbers: unread by kind, MMS and the SMS app's
    // own chats with SMS, the dialer's echo of a missed call, Android's own notices and every other
    // app's notifications left out (they count by app, unreadByApp), an unknown account ignored.
    @Test fun unreadByKindIsTheBannersCount() {
        val notes = Account(HubAccounts.NOTIFICATIONS.id, AccountKind.NOTIFICATIONS, "Notifications", position = 3)
        fun note(key: String, folder: String, sender: String = "x", preview: String = "") =
            HubItem(notes.id, key, ItemKind.NOTIFICATION, timestamp = 1_000, sender = sender, preview = preview, folder = folder)
        val items = listOf(
            item("sms", "1", arrived = 1),
            item("sms", "2", arrived = 1).copy(kind = ItemKind.MMS),
            item("sms", "3", arrived = 1, read = true),
            HubItem("calls", "c", ItemKind.MISSED_CALL, timestamp = 1_000, sender = "+33600000000"),
            // The dialer's notification of that same call, and Android's own notices.
            note("d", "dialer.app", sender = "+33600000000"),
            note("s", "android"),
            note("g", "com.google.android.gsf"),
            note("w", "com.whatsapp"),
            note("r", "sms.app", sender = "Someone", preview = "an RCS chat"),
            item("gone", "1", arrived = 1),
        )
        assertEquals(
            mapOf(ItemKind.SMS to 3, ItemKind.MISSED_CALL to 1),
            unreadByKind(listOf(sms, calls, notes), items, smsApp = "sms.app", dialer = "dialer.app"),
        )
        assertEquals(emptyMap(), unreadByKind(listOf(sms), emptyList(), null, null))
    }

    // A third-party app's indicator in the same banners (the firmware's Facebook, Twitter and App
    // World carry their own ribbon icon, A): one count per app, the app with the newest unread
    // item first; Android's notices, the SMS app's chats (counted as texts), echoes, read items and
    // unknown accounts left out.
    @Test fun unreadByAppIsEachOtherAppsCount() {
        val notes = Account(HubAccounts.NOTIFICATIONS.id, AccountKind.NOTIFICATIONS, "Notifications", position = 3)
        fun note(key: String, folder: String, at: Long, sender: String = "x", read: Boolean = false) =
            HubItem(notes.id, key, ItemKind.NOTIFICATION, timestamp = at, sender = sender, folder = folder, read = read)
        val items = listOf(
            note("w1", "com.whatsapp", 1_000),
            note("w2", "com.whatsapp", 3_000),
            note("t1", "com.twitter.android", 2_000),
            note("t2", "com.twitter.android", 4_000, read = true),
            note("g", "com.google.android.gm", 5_000),
            note("d", "dialer.app", 6_000, sender = "+33600000000"),
            note("s", "android", 7_000),
            note("r", "sms.app", 8_000),
            HubItem("calls", "c", ItemKind.MISSED_CALL, timestamp = 1_000, sender = "+33600000000"),
        )
        val byApp = unreadByApp(listOf(calls, notes), items, smsApp = "sms.app", dialer = "dialer.app")
        assertEquals(listOf("com.google.android.gm" to 1, "com.whatsapp" to 2, "com.twitter.android" to 1), byApp.toList())
        assertEquals(emptyMap(), unreadByApp(listOf(calls), items, "sms.app", "dialer.app"))
    }
}
