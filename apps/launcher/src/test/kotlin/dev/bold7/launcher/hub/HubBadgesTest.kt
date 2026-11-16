package dev.bold7.launcher.hub

import dev.bold7.hub.data.Account
import dev.bold7.hub.data.AccountKind
import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.unreadByKind
import dev.bold7.icons.CALENDAR_ICON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubBadgesTest {
    private val notifications = Account("notifications", AccountKind.NOTIFICATIONS, "", position = 0, seenAt = 100)
    private val texts = Account("sms", AccountKind.TEXT_MESSAGES, "", position = 1, seenAt = 100)
    private val calls = Account("calls", AccountKind.CALLS, "", position = 2, seenAt = 100)
    private val calendar = Account("calendar", AccountKind.CALENDAR, "", position = 3, seenAt = 100)
    private val accounts = listOf(notifications, texts, calls, calendar)

    private fun item(account: Account, key: String, kind: ItemKind, addedAt: Long, read: Boolean = false, folder: String? = null, sender: String = "Ann", preview: String = "hi") =
        HubItem(account.id, key, kind, timestamp = addedAt, sender = sender, preview = preview, read = read, folder = folder, addedAt = addedAt)

    @Test fun onlyUnreadItemsArrivedAfterTheirAccountsVisitAreNew() {
        val items = listOf(
            item(texts, "old", ItemKind.SMS, addedAt = 50), // before the visit
            item(calls, "read", ItemKind.MISSED_CALL, addedAt = 200, read = true),
            item(calls, "sent", ItemKind.MISSED_CALL, addedAt = 200).copy(box = Box.SENT),
            item(calls, "deleted", ItemKind.MISSED_CALL, addedAt = 200).copy(deleted = true),
        )
        // Nothing new; the old unread SMS is only counted (the banner's number).
        assertEquals(HubBadges(counts = mapOf(ItemKind.SMS to 1)), hubBadges(accounts, items, smsApp = "sms.app", dialer = "dialer.app"))
        val news = hubBadges(accounts, items + item(texts, "new", ItemKind.SMS, addedAt = 101) + item(texts, "newer", ItemKind.SMS, addedAt = 150), "sms.app", "dialer.app")
        assertEquals(setOf("sms"), news.accounts)
        // Each account up to its newest new item: what a visit marks seen.
        assertEquals(mapOf("sms" to 150L), news.newest)
        assertTrue(news.hub)
    }

    @Test fun eachNewItemMarksTheAppThatHoldsIt() {
        val items = listOf(
            item(texts, "1", ItemKind.SMS, addedAt = 200),
            item(calls, "2", ItemKind.MISSED_CALL, addedAt = 200),
            item(notifications, "3", ItemKind.NOTIFICATION, addedAt = 200, folder = "com.whatsapp"),
            item(calendar, "4", ItemKind.INVITATION, addedAt = 200),
        )
        val badges = hubBadges(accounts, items, smsApp = "sms.app", dialer = "dialer.app")
        assertEquals(setOf("sms.app", "dialer.app", "com.whatsapp"), badges.apps)
        assertEquals(setOf(AccountKind.TEXT_MESSAGES, AccountKind.CALLS, AccountKind.NOTIFICATIONS, AccountKind.CALENDAR), badges.kinds)
        // Without a default SMS app or dialer, their items mark no app.
        assertEquals(setOf("com.whatsapp"), hubBadges(accounts, items, null, null).apps)
    }

    @Test fun theBannerCountsEveryUnreadItemByKindSeenOrNot() {
        val items = listOf(
            item(texts, "old", ItemKind.SMS, addedAt = 50), // seen in the Hub, still unread: counted
            item(texts, "mms", ItemKind.MMS, addedAt = 200),
            item(calls, "1", ItemKind.MISSED_CALL, addedAt = 200),
            item(calls, "2", ItemKind.MISSED_CALL, addedAt = 300),
            item(calls, "read", ItemKind.MISSED_CALL, addedAt = 200, read = true),
            item(calls, "sent", ItemKind.MISSED_CALL, addedAt = 200).copy(box = Box.SENT),
            item(notifications, "3", ItemKind.NOTIFICATION, addedAt = 200, folder = "com.whatsapp"),
        )
        val badges = hubBadges(accounts, items, smsApp = "sms.app", dialer = "dialer.app")
        // Texts and MMS are one count, as on the 9900's banner; another app's notifications none.
        assertEquals(mapOf(ItemKind.SMS to 2, ItemKind.MISSED_CALL to 2), badges.counts)
        // Nothing unread: no count, even with nothing new.
        assertEquals(emptyMap(), hubBadges(accounts, items.map { it.copy(read = true) }, "sms.app", "dialer.app").counts)
        // A notification that repeats an SMS is counted once.
        val echo = item(notifications, "4", ItemKind.NOTIFICATION, addedAt = 200, folder = "sms.app", sender = "Ann", preview = "hi")
        assertEquals(1, hubBadges(accounts, listOf(item(texts, "5", ItemKind.SMS, addedAt = 50), echo), "sms.app", null).counts[ItemKind.SMS])
        assertEquals(null, hubBadges(accounts, listOf(item(texts, "5", ItemKind.SMS, addedAt = 50), echo), "sms.app", null).counts[ItemKind.NOTIFICATION])
    }

    @Test fun aNotificationThatRepeatsAnSmsIsNotNewTwice() {
        val sms = item(texts, "1", ItemKind.SMS, addedAt = 200, sender = "Ann", preview = "On se voit ?")
        val echo = item(notifications, "2", ItemKind.NOTIFICATION, addedAt = 200, folder = "sms.app", sender = "Ann", preview = "On se voit ?")
        val badges = hubBadges(accounts, listOf(sms.copy(read = false), echo), smsApp = "sms.app", dialer = null)
        assertEquals(setOf("sms"), badges.accounts)
    }

    @Test fun anIconCarriesItsAppsSplatTheHubIconTheHubsTheCalendarIconItsAccounts() {
        val badges = HubBadges(newest = mapOf("calendar" to 1L), kinds = setOf(AccountKind.CALENDAR), apps = setOf("com.whatsapp"))
        // The icon table is asked only when the package alone does not decide.
        assertTrue(iconSplat("com.whatsapp", { error("not asked") }, badges))
        assertFalse(iconSplat("org.other", { null }, badges))
        assertTrue(iconSplat("com.google.android.gm", { HUB_ICON }, badges))
        assertTrue(iconSplat("com.google.android.calendar", { CALENDAR_ICON }, badges))
        assertFalse(iconSplat("com.google.android.gm", { error("not asked") }, HubBadges()))
        assertFalse(iconSplat("com.google.android.calendar", { CALENDAR_ICON }, HubBadges(newest = mapOf("sms" to 1L), kinds = setOf(AccountKind.TEXT_MESSAGES))))
    }

    // Text Messages carries the star of new texts only (gsm 020, C), an RCS
    // chat that reached the Hub as a notification of the SMS app included.
    @Test fun theTextMessagesIconCarriesTheStarOfNewTextsOnly() {
        val call = item(calls, "1", ItemKind.MISSED_CALL, addedAt = 200)
        val other = item(notifications, "2", ItemKind.NOTIFICATION, addedAt = 200, folder = "com.whatsapp")
        val none = hubBadges(accounts, listOf(call, other), "sms.app", "dialer.app")
        assertFalse(none.texts)
        assertFalse(iconSplat("dev.bold7.messages", { TEXT_MESSAGES_ICON }, none))
        assertTrue(iconSplat("dev.bold7.messages", { HUB_ICON }, none))
        val mms = hubBadges(accounts, listOf(call, item(texts, "3", ItemKind.MMS, addedAt = 200)), "sms.app", "dialer.app")
        assertTrue(mms.texts)
        assertTrue(iconSplat("dev.bold7.messages", { TEXT_MESSAGES_ICON }, mms))
        val rcs = item(notifications, "4", ItemKind.NOTIFICATION, addedAt = 200, folder = "sms.app", sender = "Bea", preview = "rcs")
        assertTrue(hubBadges(accounts, listOf(rcs), "sms.app", "dialer.app").texts)
        // Seen (before the visit) or read: no star.
        assertFalse(hubBadges(accounts, listOf(item(texts, "5", ItemKind.SMS, addedAt = 50)), "sms.app", null).texts)
    }

    // A mail account's icon (a shortcut of Messages) carries the star of that account's new mail only.
    @Test fun aMailAccountsIconCarriesTheStarOfItsNewMailOnly() {
        val mail1 = Account("mail-1", AccountKind.EMAIL, "a@gmail.com", seenAt = 100)
        val mail2 = Account("mail-2", AccountKind.EMAIL, "b@outlook.com", seenAt = 100)
        val badges = hubBadges(accounts + mail1 + mail2, listOf(item(mail1, "m", ItemKind.EMAIL, addedAt = 200)), "sms.app", "dialer.app")
        assertTrue(iconSplat("dev.bold7.messages", { error("not asked") }, badges, account = "mail-1"))
        assertFalse(iconSplat("dev.bold7.messages", { error("not asked") }, badges, account = "mail-2"))
        assertTrue(iconSplat("dev.bold7.messages", { HUB_ICON }, badges), "Messages still carries it")
    }

    // One rule for the home, an app's bar and the lock screen: core/hub-data's unreadByKind.
    @Test fun theBannerCountsAreTheSharedRule() {
        val voicemail = item(notifications, "v", ItemKind.NOTIFICATION, addedAt = 200, folder = "com.google.android.dialer")
        val items = listOf(
            item(texts, "1", ItemKind.SMS, addedAt = 200),
            item(calls, "2", ItemKind.MISSED_CALL, addedAt = 200),
            item(notifications, "3", ItemKind.NOTIFICATION, addedAt = 200, folder = "com.whatsapp"),
            item(notifications, "4", ItemKind.NOTIFICATION, addedAt = 200, folder = "com.android.providers.downloads"),
            voicemail,
        )
        val counts = hubBadges(accounts, items, "sms.app", "dialer.app").counts
        assertEquals(unreadByKind(accounts, items, "sms.app", "dialer.app"), counts)
        assertEquals(mapOf(ItemKind.SMS to 1, ItemKind.MISSED_CALL to 1), counts)
    }
}
