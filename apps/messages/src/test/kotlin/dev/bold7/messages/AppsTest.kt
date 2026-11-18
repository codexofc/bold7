package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The other apps' messages in Messages: their icons, their group lines, their views, hidden or turned off. */
class AppsTest {
    private fun line(vararg items: dev.bold7.hub.data.HubItem, filter: Filter = Filter.ALL, app: String? = null, options: MessagesOptions = MessagesOptions()) =
        inboxLines(snapshot(*items).copy(options = options), filter, null, format, app = app).filterIsInstance<ItemLine>()

    private fun entry(item: dev.bold7.hub.data.HubItem, unread: Boolean = !item.read) = HubRow.Entry(item, 1, if (unread) 1 else 0)

    // Icons

    @Test fun facebookAndTwitterWearTheirOwnStrips() {
        assertEquals(MessageIcon.FACEBOOK_UNREAD, entry(note("f", 12, 9, app = "com.facebook.katana")).icon("sms.app"))
        assertEquals(MessageIcon.FACEBOOK_READ, entry(note("f", 12, 9, app = "com.facebook.katana", read = true)).icon("sms.app"))
        assertEquals(MessageIcon.FACEBOOK_SENT, entry(note("f", 12, 9, app = "com.facebook.lite").copy(box = Box.SENT, read = true)).icon("sms.app"))
        assertEquals(MessageIcon.FACEBOOK_SENDING, entry(note("f", 12, 9, app = "com.facebook.katana").copy(box = Box.OUTBOX)).icon("sms.app"))
        assertEquals(MessageIcon.TWITTER_UNREAD, entry(note("t", 12, 9, app = "com.twitter.android")).icon("sms.app"))
        assertEquals(MessageIcon.TWITTER_SENT, entry(note("t", 12, 9, app = "com.twitter.android").copy(box = Box.SENT, read = true)).icon("sms.app"))
        // Their strips are 40 px: drawn at their pixels in the row's 42 px cell, never scaled.
        assertEquals(40, MessageIcon.FACEBOOK_UNREAD.strip.cell)
        assertEquals(42, MessageIcon.SMS_UNREAD.strip.cell)
    }

    @Test fun anyOtherAppWearsItsOwnIconOverTheSystemsUntilDrawn() {
        val whatsapp = line(note("w", 12, 9, app = "com.whatsapp")).single()
        assertEquals("com.whatsapp", whatsapp.app)
        // Shown while the app's icon is not drawn, or when it has none (uninstalled).
        assertEquals(MessageIcon.SYSTEM_UNREAD, whatsapp.icon)
        assertNull(line(note("s", 12, 9, app = "com.android.systemui")).singleOrNull()?.app)
        assertNull(line(note("f", 12, 9, app = "com.facebook.katana")).single().app)
        assertNull(line(note("r", 12, 9, app = "sms.app", sender = "Zed", text = "rcs")).single().app)
    }

    // Groups

    @Test fun aGroupShowsItsNameThenTheSenderBeforeTheText() {
        val group = note("g", 12, 9, app = "com.whatsapp", sender = "Erin", text = "On my way").copy(subject = "Climbing", messaging = true)
        assertEquals("Climbing" to "Erin: On my way", line(group).single().let { it.sender to it.preview })
        // Mine, sent to the group: the text alone.
        val mine = group.copy(sender = "Climbing", box = Box.SENT, read = true)
        assertEquals("Climbing" to "On my way", line(mine).single().let { it.sender to it.preview })
        // A conversation of two: its sender, its text.
        assertEquals("Erin" to "On my way", line(group.copy(subject = null)).single().let { it.sender to it.preview })
    }

    // One view per app

    @Test fun theAppsOfTheViewsAreThoseWithMessagesNewestFirst() {
        val s = snapshot(
            note("w", 12, 9, app = "com.whatsapp"), note("t", 12, 10, app = "org.telegram.messenger"), note("w2", 12, 8, app = "com.whatsapp"),
            note("rcs", 12, 11, app = "sms.app"), note("sys", 12, 12, app = "com.android.systemui"), note("gone", 12, 13, app = "com.old").copy(deleted = true),
        )
        assertEquals(listOf("org.telegram.messenger", "com.whatsapp"), messagingApps(s))
        // Turned off: no view.
        assertEquals(listOf("com.whatsapp"), messagingApps(s.copy(options = MessagesOptions(disabledSources = setOf("org.telegram.messenger")))))
    }

    @Test fun anAppsViewShowsItsNotificationsOnly() {
        val items = arrayOf(note("w", 12, 9, app = "com.whatsapp"), note("t", 12, 10, app = "org.telegram.messenger"), sms("a", 12, 11))
        assertEquals(listOf("w"), line(*items, filter = Filter.NOTIFICATIONS, app = "com.whatsapp").map { it.item.sourceKey })
    }

    @Test fun viewFolderListsTheViewsThenOneViewPerApp() {
        val apps = listOf("com.whatsapp", "org.telegram.messenger")
        val views = folderViews(apps)
        assertEquals(FOLDER_VIEWS.size + 2, views.size)
        assertEquals(FolderView(Filter.NOTIFICATIONS, "org.telegram.messenger"), views.last())
        val s = InboxState().reduce(InboxEvent.Shown(emptyList(), apps)).reduce(InboxEvent.FoldersOpened)
        var down = s
        repeat(30) { down = down.reduce(InboxEvent.FolderStep(down = true)) }
        assertEquals(views.lastIndex, down.folder)
        val telegram = down.reduce(InboxEvent.Filtered(Filter.NOTIFICATIONS, "org.telegram.messenger"))
        assertEquals(Filter.NOTIFICATIONS to "org.telegram.messenger", telegram.filter to telegram.app)
        assertEquals(views.lastIndex, telegram.reduce(InboxEvent.FoldersOpened).folder)
        // Escape: back to every message, the app's view left.
        assertEquals(Filter.ALL to null, telegram.back()!!.let { it.filter to it.app })
        // Another view leaves the app's.
        assertNull(telegram.reduce(InboxEvent.Filtered(Filter.UNREAD)).app)
    }

    // Hidden, or turned off

    @Test fun aHiddenAppShowsInItsOwnViewOnly() {
        val o = MessagesOptions(hiddenApps = setOf("com.whatsapp"))
        val items = arrayOf(note("w", 12, 9, app = "com.whatsapp"), note("t", 12, 10, app = "org.telegram.messenger"))
        assertEquals(listOf("t"), line(*items, options = o).map { it.item.sourceKey })
        assertEquals(listOf("w"), line(*items, filter = Filter.NOTIFICATIONS, app = "com.whatsapp", options = o).map { it.item.sourceKey })
    }

    @Test fun aSourceTurnedOffShowsNowhere() {
        val o = MessagesOptions(disabledSources = setOf("com.whatsapp", "sms"))
        val items = arrayOf(note("w", 12, 9, app = "com.whatsapp"), sms("a", 12, 11), call("c", 12, 8))
        assertEquals(listOf("c"), line(*items, options = o).map { it.item.sourceKey })
        assertTrue(line(*items, filter = Filter.NOTIFICATIONS, app = "com.whatsapp", options = o).isEmpty())
        assertTrue(line(*items, filter = Filter.TEXT, options = o).isEmpty())
    }

    @Test fun signalIsOffUntilTurnedOn() {
        assertTrue("org.thoughtcrime.securesms" in MessagesOptions().disabledSources)
    }

    @Test fun inboxManagementAddsOneCheckPerApp() {
        val mail = listOf("mail:1")
        val apps = listOf("com.whatsapp", "org.telegram.messenger")
        val o = MessagesOptions().toggled(OptionsScreen.INBOX, 3, mail, apps)
        assertEquals(setOf("org.telegram.messenger"), o.hiddenApps)
        assertEquals(listOf(true, true, true, false), o.inboxChecks(mail, apps))
    }

    @Test fun theSourcesAreTextsMailCallsInvitationsThenEachApp() {
        val mail = listOf("mail:1")
        val apps = listOf("com.whatsapp", "org.thoughtcrime.securesms")
        assertEquals(listOf("sms", "mail:1", "calls", "calendar", "com.whatsapp", "org.thoughtcrime.securesms"), sourceIds(mail, apps))
        val o = MessagesOptions()
        assertEquals(listOf(true, true, true, true, true, false), o.sourceChecks(mail, apps))
        val on = o.toggled(OptionsScreen.SOURCES, 5, mail, apps).toggled(OptionsScreen.SOURCES, 0, mail, apps)
        assertEquals(setOf("sms"), on.disabledSources)
        assertEquals(on, on.toggled(OptionsScreen.SOURCES, 9, mail, apps))
    }

    @Test fun theOptionsSourcesListsTheAppsSeenAndThoseTurnedOff() {
        val s = snapshot(note("w", 12, 9, app = "com.whatsapp")).copy(options = MessagesOptions(disabledSources = setOf("org.telegram.messenger", "sms")))
        assertEquals(listOf("com.whatsapp", "org.telegram.messenger"), sourceApps(s, installed = { true }))
        // An app off by default and not installed is not offered.
        assertEquals(listOf("com.whatsapp"), sourceApps(s.copy(options = MessagesOptions()), installed = { false }))
    }
}
