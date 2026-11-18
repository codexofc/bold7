package dev.bold7.launcher.notices

import dev.bold7.design.NoticeDimens
import dev.bold7.hub.data.Account
import dev.bold7.hub.data.AccountKind
import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import dev.bold7.launcher.home.UNREAD_CALENDAR
import dev.bold7.launcher.home.UNREAD_EMAIL
import dev.bold7.launcher.home.UNREAD_MISSED_CALL
import dev.bold7.launcher.home.UNREAD_OTHER
import dev.bold7.launcher.home.UNREAD_SMS
import dev.bold7.launcher.hub.HubFormat
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoticesTest {
    private val notifications = Account("notifications", AccountKind.NOTIFICATIONS, "", position = 0)
    private val texts = Account("sms", AccountKind.TEXT_MESSAGES, "", position = 1)
    private val calls = Account("calls", AccountKind.CALLS, "", position = 2)
    private val calendar = Account("calendar", AccountKind.CALENDAR, "", position = 3)
    private val mail = Account("mail", AccountKind.EMAIL, "", position = 4)
    private val accounts = listOf(notifications, texts, calls, calendar, mail)
    private val names = NoticeNames("Messages", "Text Messages", "Phone", "Calendar", "Missed Call")
    private val utc24 = HubFormat(ZoneOffset.UTC, Locale.UK, clock24 = true)

    private fun at(h: Int, m: Int) = LocalDateTime.of(2025, 3, 13, h, m).toEpochSecond(ZoneOffset.UTC) * 1000

    private fun item(account: Account, key: String, kind: ItemKind, time: Long, sender: String = "Ann", preview: String = "hi", folder: String? = null, subject: String? = null, address: String? = null) =
        HubItem(account.id, key, kind, timestamp = time, sender = sender, preview = preview, folder = folder, subject = subject, address = address)

    private fun lines(items: List<HubItem>, format: HubFormat = utc24, appName: (String) -> String? = { mapOf("com.whatsapp" to "WhatsApp", "org.app" to "App")[it] }) =
        notices(accounts, items, smsApp = "sms.app", dialer = "dialer.app", format, names, appName)

    // sim-26 (C): a header per app, its items under it, newest first: time, sender, summary.
    @Test fun aHeaderPerAppAndItsUnopenedItemsNewestFirst() {
        val l = lines(
            listOf(
                item(calls, "1", ItemKind.MISSED_CALL, at(0, 21), sender = "5551234", address = "5551234"),
                item(calls, "2", ItemKind.MISSED_CALL, at(4, 13), sender = "5559876", address = "5559876"),
            ),
        )
        assertEquals(NoticeHeader("Phone", UNREAD_MISSED_CALL, "dialer.app"), l[0])
        assertEquals(listOf("04:13", "00:21"), l.drop(1).map { (it as NoticeRow).time })
        assertEquals(listOf("5559876", "5551234"), l.drop(1).map { (it as NoticeRow).sender })
        // "Missed Call: 13/03/2025…" (sim-26, cut there by the row): the call's date and time.
        assertEquals("Missed Call: 13/03/2025 04:13", (l[1] as NoticeRow).summary)
    }

    // The sections in the order of the banner's counters (email, texts, calls, invitations), then
    // each other app under its own name and the "?" bubble, the app with the newest item first (D).
    @Test fun sectionsInTheCountersOrder() {
        val l = lines(
            listOf(
                item(notifications, "n1", ItemKind.NOTIFICATION, at(9, 0), folder = "org.app"),
                item(notifications, "n2", ItemKind.NOTIFICATION, at(10, 0), folder = "com.whatsapp"),
                item(calendar, "c", ItemKind.INVITATION, at(8, 0), subject = "Lunch"),
                item(calls, "m", ItemKind.MISSED_CALL, at(7, 0)),
                item(texts, "t", ItemKind.MMS, at(6, 0)),
                item(mail, "e", ItemKind.EMAIL, at(5, 0), sender = "Kyle", subject = "Test email"),
            ),
        )
        assertEquals(
            listOf(
                NoticeHeader("Messages", UNREAD_EMAIL, null),
                NoticeHeader("Text Messages", UNREAD_SMS, "sms.app"),
                NoticeHeader("Phone", UNREAD_MISSED_CALL, "dialer.app"),
                NoticeHeader("Calendar", UNREAD_CALENDAR, null),
                NoticeHeader("WhatsApp", UNREAD_OTHER, "com.whatsapp"),
                NoticeHeader("App", UNREAD_OTHER, "org.app"),
            ),
            l.filterIsInstance<NoticeHeader>(),
        )
        // gsm 012 (C): "13:45 Kyle Test email", a mail's subject.
        assertEquals(NoticeRow("05:00", "Kyle", "Test email", l[1].let { (it as NoticeRow).item }), l[1])
    }

    // What the counters count, and nothing else: opened, sent, deleted and echoed items are out.
    @Test fun onlyWhatTheCountersCount() {
        val l = lines(
            listOf(
                item(texts, "read", ItemKind.SMS, at(1, 0)).copy(read = true),
                item(texts, "sent", ItemKind.SMS, at(1, 0)).copy(box = Box.SENT),
                item(texts, "deleted", ItemKind.SMS, at(1, 0)).copy(deleted = true),
                // The SMS app's own notification repeats an SMS (withoutEchoes).
                item(notifications, "echo", ItemKind.NOTIFICATION, at(1, 0), folder = "sms.app"),
                HubItem("gone", "x", ItemKind.SMS, at(1, 0), "Ann"), // an account the Hub no longer has
            ),
        )
        assertEquals(emptyList(), l)
    }

    // 12 hours: "8:10p" (ps 33240, C).
    @Test fun twelveHourTimes() {
        val f = utc24.copy(clock24 = false)
        assertEquals("8:10p", noticeTime(at(20, 10), f))
        assertEquals("12:05a", noticeTime(at(0, 5), f))
        assertEquals("08:10", noticeTime(at(8, 10), utc24))
    }

    // Texts on one line; an app with no name the home knows keeps its package.
    @Test fun oneLineAndAName() {
        val l = lines(listOf(item(notifications, "n", ItemKind.NOTIFICATION, at(1, 0), folder = "x.y", preview = "a\nb")), appName = { null })
        assertEquals(NoticeHeader("x.y", UNREAD_OTHER, "x.y"), l[0])
        assertEquals("a b", (l[1] as NoticeRow).summary)
    }

    // Audit 3: Android's colour emoji are read as OS 7's emoticons (the table of Messages), so the
    // list draws the theme's images for them.
    @Test fun androidEmojiAreReadAsEmoticons() {
        val l = lines(listOf(item(notifications, "e", ItemKind.NOTIFICATION, at(1, 0), folder = "x.y", preview = "ok \uD83D\uDE02")), appName = { null })
        assertEquals("ok " + dev.bold7.ui.osEmoji("\uD83D\uDE02"), (l[1] as NoticeRow).summary)
        assertEquals(false, (l[1] as NoticeRow).summary.contains("\uD83D\uDE02"))
    }

    // The sender's name goes through the same table: a name is drawn with the emoticon images too.
    @Test fun androidEmojiInASenderAreReadAsEmoticons() {
        val l = lines(listOf(item(notifications, "s", ItemKind.NOTIFICATION, at(1, 0), sender = "Lisa \uD83D\uDE02", folder = "x.y")), appName = { null })
        assertEquals("Lisa " + dev.bold7.ui.osEmoji("\uD83D\uDE02"), (l[1] as NoticeRow).sender)
        assertEquals(false, (l[1] as NoticeRow).sender.contains("\uD83D\uDE02"))
    }

    // OS 7 lists messages and the alerts of its apps, never Android's own
    // notices (charging, Vector, the keyboard layout, Key Mapper, the dialer's): they have no line
    // and no header ("Options" with the "?" bubble was Android's technical text).
    @Test fun androidsOwnNoticesAreNotListed() {
        val l = lines(
            listOf(
                item(notifications, "s", ItemKind.NOTIFICATION, at(5, 0), folder = "android"),
                item(notifications, "v", ItemKind.NOTIFICATION, at(4, 0), folder = "org.matrix.vector"),
                item(notifications, "k", ItemKind.NOTIFICATION, at(3, 0), folder = "io.github.sds100.keymapper"),
                item(notifications, "u", ItemKind.NOTIFICATION, at(2, 0), folder = "com.android.systemui"),
                item(notifications, "p", ItemKind.NOTIFICATION, at(1, 0), folder = "com.google.android.dialer"),
                item(notifications, "w", ItemKind.NOTIFICATION, at(0, 30), folder = "com.whatsapp"),
            ),
        )
        assertEquals(listOf(NoticeHeader("WhatsApp", UNREAD_OTHER, "com.whatsapp")), l.filterIsInstance<NoticeHeader>())
    }

    // The trackpad (sim-25 to sim-27, C): down from the row's zone to the first line, then line by
    // line, staying on the last; up back to the zone from the first.
    @Test fun theTrackpadWalksTheList() {
        assertEquals(0, noticeStep(null, size = 3, down = true))
        assertEquals(1, noticeStep(0, size = 3, down = true))
        assertEquals(2, noticeStep(2, size = 3, down = true))
        assertEquals(1, noticeStep(2, size = 3, down = false))
        assertNull(noticeStep(0, size = 3, down = false))
        assertNull(noticeStep(null, size = 0, down = true))
    }

    // The list changed under the focus (a row opened, read elsewhere): it stays in the list.
    @Test fun theFocusStaysInTheList() {
        assertEquals(1, noticeFocus(3, size = 2))
        assertEquals(0, noticeFocus(0, size = 2))
        assertNull(noticeFocus(1, size = 0))
        assertNull(noticeFocus(null, size = 2))
    }
    // 12 rows show of the list's 580 px (docs/BBOS7-VISUEL.md §5, D), then it scrolls: only the
    // lines in its window are drawn, wherever it is scrolled (19 notices drew only their first 13).
    @Test fun onlyTheLinesInTheWindowAreDrawn() {
        val window = 720 - NoticeDimens.TOP
        assertEquals((0..12).toList(), (0 until 200).filter { noticeShown(it, scroll = 0, window = window) })
        assertEquals((7..19).toList(), (0 until 200).filter { noticeShown(it, scroll = 341, window = window) })
        val end = NoticeDimens.GAP + 200 * NoticeDimens.LINE - window
        assertEquals((187..199).toList(), (0 until 200).filter { noticeShown(it, scroll = end, window = window) })
        assertEquals(emptyList(), (0 until 200).filter { noticeShown(it, scroll = 0, window = 0) })
    }

    // The trackpad's focus drives the scroll: the focused line comes whole into the window, the
    // list does not move while it is already there.
    @Test fun theScrollFollowsTheFocus() {
        val window = 720 - NoticeDimens.TOP
        assertNull(noticeScroll(0, scroll = 0, window = window))
        assertNull(noticeScroll(11, scroll = 0, window = window))
        val last = 199
        val bottom = NoticeDimens.GAP + (last + 1) * NoticeDimens.LINE
        assertEquals(bottom - window, noticeScroll(last, scroll = 0, window = window))
        assertNull(noticeScroll(last, scroll = bottom - window, window = window))
        assertEquals(NoticeDimens.GAP, noticeScroll(0, scroll = bottom - window, window = window))
        assertEquals(NoticeDimens.GAP + 12 * NoticeDimens.LINE + NoticeDimens.LINE - window, noticeScroll(12, scroll = 0, window = window))
    }
}
