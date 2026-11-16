package dev.bold7.messages

import dev.bold7.hub.data.Account
import dev.bold7.hub.data.AccountKind
import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val paris: ZoneId = ZoneId.of("Europe/Paris")
internal val format = InboxFormat(paris, Locale.UK, clock24 = true)
internal fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, paris).toInstant().toEpochMilli()

internal fun sms(key: String, day: Int, hour: Int, read: Boolean = false, thread: String = key, sender: String = "Alice", text: String = "t$key", box: Box = Box.INBOX, address: String? = null, added: Long = 0) =
    HubItem("sms", key, ItemKind.SMS, at(day, hour), sender, address = address, preview = text, conversationId = thread, read = read, box = box, addedAt = added)

internal fun mms(key: String, day: Int, hour: Int, attachments: Int, read: Boolean = false) =
    HubItem("sms", key, ItemKind.MMS, at(day, hour), "Alice", preview = "m$key", conversationId = key, read = read, attachmentCount = attachments)

internal fun call(key: String, day: Int, hour: Int, read: Boolean = false, number: String = "+33199000000") =
    HubItem("calls", key, ItemKind.MISSED_CALL, at(day, hour), "Bob", address = number, read = read)

internal fun note(key: String, day: Int, hour: Int, app: String = "com.app", read: Boolean = false, sender: String = "Carol", text: String = "n$key") =
    HubItem("notifications", key, ItemKind.NOTIFICATION, at(day, hour), sender, preview = text, folder = app, conversationId = key, read = read)

internal val accounts = listOf(
    Account("notifications", AccountKind.NOTIFICATIONS, "", position = 0),
    Account("sms", AccountKind.TEXT_MESSAGES, "", position = 1),
    Account("calls", AccountKind.CALLS, "", position = 2),
)

internal fun snapshot(vararg items: HubItem, smsApp: String? = "sms.app", dialer: String? = "dialer.app", seen: Long = 0) =
    Snapshot(items.toList(), accounts.map { it.copy(seenAt = seen) }, smsApp, dialer)

class InboxTest {
    private fun lines(s: Snapshot, filter: Filter = Filter.ALL, search: String? = null) = inboxLines(s, filter, search, format)

    @Test fun newestFirstUnderDateHeadersWrittenAsThe9900() {
        // gsm 027: "Fri 16 Sep 2011", the time alone on the row (24 h on that phone).
        val l = lines(snapshot(sms("a", 12, 9), call("c", 13, 14), sms("b", 12, 18)))
        assertEquals(listOf("Fri 13 Nov 2026", "c", "Thu 12 Nov 2026", "b", "a"), l.map { if (it is DayLine) it.title else (it as ItemLine).item.sourceKey })
        assertEquals("14:00", (l[1] as ItemLine).time)
        assertEquals("2:00 PM", (inboxLines(snapshot(call("c", 13, 14)), Filter.ALL, null, format.copy(locale = Locale.US, clock24 = false))[1] as ItemLine).time)
    }

    @Test fun aConversationIsOneRowAtItsLatestMessage() {
        val l = lines(snapshot(sms("a", 12, 9, thread = "t", read = true), sms("b", 12, 10, thread = "t"))).filterIsInstance<ItemLine>()
        assertEquals(listOf("b"), l.map { it.item.sourceKey })
        assertTrue(l.single().unread)
    }

    @Test fun theSecondLineIsTheSubjectTheTextOrACallsNumber() {
        val l = lines(snapshot(sms("a", 12, 9, text = "one\ntwo"), call("c", 12, 8, number = "+331"))).filterIsInstance<ItemLine>()
        assertEquals(listOf("one two", "+331"), l.map { it.preview })
    }

    @Test fun theNotificationsThatRepeatAnSmsAreLeftOut() {
        val s = snapshot(sms("a", 12, 9, text = "hi"), note("n", 12, 9, app = "sms.app", sender = "Alice", text = "hi"))
        assertEquals(listOf("a"), lines(s).filterIsInstance<ItemLine>().map { it.item.sourceKey })
    }

    @Test fun deletedItemsAndEmptyDaysDoNotShow() {
        val s = snapshot(sms("a", 12, 9).copy(deleted = true), sms("b", 11, 9))
        assertEquals(listOf("Wed 11 Nov 2026", "b"), lines(s).map { if (it is DayLine) it.title else (it as ItemLine).item.sourceKey })
    }

    // The views of the 9900's inbox (guide 7.1 p. 52, Alt shortcuts) and View Folder.

    private val mixed = snapshot(
        sms("in", 12, 9), sms("out", 12, 8, box = Box.SENT, read = true), sms("draft", 12, 7, box = Box.DRAFT),
        call("c", 12, 6), note("n", 12, 5), note("rcs", 12, 4, app = "sms.app"), sms("read", 12, 3, read = true),
    )

    private fun keys(filter: Filter, search: String? = null) = lines(mixed, filter, search).filterIsInstance<ItemLine>().map { it.item.sourceKey }

    @Test fun systemNotificationsStayOutOfTheMessages() {
        val pkgs = listOf("android", "com.android.shell", "org.matrix.vector", "io.github.sds100.keymapper", "ph.dgsd.benos.attestmon", "com.google.android.dialer", "com.google.android.permissioncontroller", "com.google.android.gms", "com.android.vending", "com.android.settings")
        val s = snapshot(*(pkgs.mapIndexed { i, p -> note("sys$i", 10, 8, app = p) } + note("wa", 10, 9, app = "com.whatsapp") + sms("t", 10, 7)).toTypedArray())
        fun keys(f: Filter) = lines(s, f).filterIsInstance<ItemLine>().map { it.item.sourceKey }
        assertEquals(listOf("wa", "t"), keys(Filter.ALL))
        // The notifications folder, like the banner, still lists them.
        assertEquals(pkgs.size + 1, keys(Filter.NOTIFICATIONS).size)
    }

    @Test fun aSendFailureNoticeIsNoReceivedMessage() {
        val gm = "com.google.android.apps.messaging"
        fun gmNote(key: String, tag: String) = note(key, 10, 8, app = gm).copy(conversationId = "0|$gm|2|$tag|10091")
        val s = snapshot(
            gmNote("fail", "null"), gmNote("chat", "$gm:incoming_message:12"),
            note("wa", 10, 9, app = "com.whatsapp").copy(conversationId = "0|com.whatsapp|1|null|10100"),
        )
        // A tagless notification of another app stays a message; the failure stays in the notifications folder.
        assertEquals(setOf("chat", "wa"), lines(s, Filter.ALL).filterIsInstance<ItemLine>().map { it.item.sourceKey }.toSet())
        assertEquals(3, lines(s, Filter.NOTIFICATIONS).filterIsInstance<ItemLine>().size)
    }

    @Test fun theCategoryOfANotificationDecidesWhetherItIsAMessage() {
        fun cat(key: String, app: String, category: String?, messaging: Boolean = false) =
            note(key, 10, 8, app = app).copy(category = category, messaging = messaging)
        val s = snapshot(
            cat("wa", "com.whatsapp", "msg", messaging = true), cat("tg", "org.telegram.messenger", "", messaging = true),
            cat("gmail", "com.google.android.gm", "email"), cat("insta", "com.instagram.android", "social"),
            cat("food", "com.delivery", ""), cat("dl", "com.app", "progress"),
            cat("sys", "com.android.settings", "msg"), note("old", 10, 8, app = "com.old"),
        )
        fun keys(f: Filter) = lines(s, f).filterIsInstance<ItemLine>().map { it.item.sourceKey }
        // An old row (unknown category) keeps the package's rule; the notifications folder keeps everything.
        assertEquals(setOf("wa", "tg", "gmail", "insta", "old"), keys(Filter.ALL).toSet())
        assertEquals(8, keys(Filter.NOTIFICATIONS).size)
    }

    @Test fun eachViewShowsItsKind() {
        assertEquals(listOf("in", "out", "draft", "c", "n", "rcs", "read"), keys(Filter.ALL))
        assertEquals(listOf("in", "c", "n", "rcs", "read"), keys(Filter.INCOMING))
        assertEquals(listOf("out", "draft"), keys(Filter.OUTGOING))
        // An RCS chat reaches the Hub as the SMS app's notification when Telephony lacks it: a text message (D).
        assertEquals(listOf("in", "out", "draft", "rcs", "read"), keys(Filter.TEXT))
        assertEquals(listOf("c"), keys(Filter.CALLS))
        assertEquals(listOf("in", "c", "n", "rcs"), keys(Filter.UNREAD))
        assertEquals(listOf("n"), keys(Filter.NOTIFICATIONS))
    }

    @Test fun searchLooksInEveryFieldWhateverTheCase() {
        val s = snapshot(sms("a", 12, 9, sender = "Alice", text = "Lunch?"), call("c", 12, 8, number = "+3319900"), note("n", 12, 7, text = "Your parcel"))
        fun found(q: String?) = lines(s, Filter.ALL, q).filterIsInstance<ItemLine>().map { it.item.sourceKey }
        assertEquals(listOf("a"), found("lunch"))
        assertEquals(listOf("a"), found("ALI"))
        assertEquals(listOf("c"), found("19900"))
        assertEquals(listOf("n"), found(" parcel "))
        assertEquals(listOf("a", "c", "n"), found("  "))
        assertEquals(listOf("a", "c", "n"), found(null))
        assertEquals(emptyList(), lines(s, Filter.ALL, "nothing"))
    }

    // Status icons: net_rim_Message-icons42x42.png (theme _b) and net_rim_Phone-icons42x42.png (_bs1), A.

    private fun icon(item: HubItem, s: Snapshot = snapshot(item)) = lines(s).filterIsInstance<ItemLine>().single().icon

    @Test fun iconsFollowTheKindAndTheState() {
        assertEquals(MessageIcon.SMS_UNREAD, icon(sms("a", 12, 9)))
        assertEquals(MessageIcon.SMS_READ, icon(sms("a", 12, 9, read = true)))
        assertEquals(MessageIcon.SMS_SENT, icon(sms("a", 12, 9, box = Box.SENT)))
        assertEquals(MessageIcon.SMS_SENT, icon(sms("a", 12, 9, box = Box.DRAFT)))
        assertEquals(MessageIcon.SMS_PENDING, icon(sms("a", 12, 9, box = Box.OUTBOX)))
        assertEquals(MessageIcon.MMS_UNREAD, icon(mms("m", 12, 9, attachments = 1)))
        // An MMS without attachment is a text: an RCS chat Google Messages copied into Telephony (D).
        assertEquals(MessageIcon.SMS_READ, icon(mms("m", 12, 9, attachments = 0, read = true)))
        assertEquals(MessageIcon.CALL_MISSED_UNREAD, icon(call("c", 12, 9)))
        assertEquals(MessageIcon.CALL_MISSED_READ, icon(call("c", 12, 9, read = true)))
        assertEquals(MessageIcon.SYSTEM_UNREAD, icon(note("n", 12, 9)))
        assertEquals(MessageIcon.SYSTEM_READ, icon(note("n", 12, 9, read = true)))
        assertEquals(MessageIcon.SMS_UNREAD, icon(note("n", 12, 9, app = "sms.app")))
    }

    @Test fun theStripsCellsAreTheOnesMeasured() {
        // sim-53 (C): a sent text's row shows the green bubble of column 4 of the 42 px strip (row 3's matched
        // it best, row 1's is near identical: rows 1 and 3 are texts without and with an attachment, by eye);
        // gsm 028 (C): Phone-icons42x42 column 1, the placed call; the missed ones are columns 2 and 3 (A, by eye).
        assertEquals(Triple(Strip.MESSAGE, 1, 4), MessageIcon.SMS_SENT.let { Triple(it.strip, it.row, it.column) })
        assertEquals(Triple(Strip.MESSAGE, 3, 1), MessageIcon.MMS_UNREAD.let { Triple(it.strip, it.row, it.column) })
        assertEquals(Triple(Strip.PHONE, 0, 2), MessageIcon.CALL_MISSED_UNREAD.let { Triple(it.strip, it.row, it.column) })
        assertEquals(Triple(Strip.MESSAGE, 3, 23), MessageIcon.SYSTEM_READ.let { Triple(it.strip, it.row, it.column) })
    }

    @Test fun aRowsIdStaysWhenANewMessageComesToItsConversation() {
        val before = lines(snapshot(sms("a", 12, 9, thread = "t"))).filterIsInstance<ItemLine>().single()
        val after = lines(snapshot(sms("a", 12, 9, thread = "t"), sms("b", 12, 10, thread = "t"))).filterIsInstance<ItemLine>().single()
        assertEquals(before.id, after.id)
        // Days below zero, rows at or above: never the same id.
        assertTrue(lines(snapshot(sms("a", 12, 9), sms("b", 11, 9))).all { (it is DayLine) == (it.id < 0) })
        assertEquals(LocalDate.of(2026, 11, 12), (lines(snapshot(sms("a", 12, 9)))[0] as DayLine).date)
    }

    // What opening Messages marks seen: the guide's "click the Messages icon" removes the new indicator (B p. 31).

    @Test fun openingMessagesSeesEveryAccountUpToItsNewestArrival() {
        val s = snapshot(sms("a", 12, 9, added = 50), sms("b", 12, 10, added = 70), call("c", 12, 8).copy(addedAt = 60), sms("r", 12, 7, read = true, added = 90), seen = 55)
        assertEquals(mapOf("sms" to 70L, "calls" to 60L), toSee(s))
        assertEquals(emptyMap(), toSee(snapshot(sms("a", 12, 9, added = 50), seen = 55)))
    }

    // opening Text Messages sees the texts only, so the star stays on Messages
    // while a missed call or another app's notification is still new; an RCS chat that reached the
    // Hub as a notification of the SMS app is a text.
    @Test fun openingTextMessagesSeesTheTextsOnly() {
        val s = snapshot(
            sms("a", 12, 9, added = 70),
            call("c", 12, 8).copy(addedAt = 60),
            note("n", 12, 7).copy(addedAt = 80),
            note("r", 12, 6, app = "sms.app").copy(addedAt = 65),
            seen = 55,
        )
        assertEquals(mapOf("sms" to 70L, "notifications" to 65L), toSee(s, Filter.TEXT))
        assertEquals(mapOf("sms" to 70L, "calls" to 60L, "notifications" to 80L), toSee(s, Filter.ALL))
    }

    // an account's icon on the home opens Messages on that account (gsm 029, C).
    private fun mail(account: String, key: String, day: Int, hour: Int, added: Long = 0) =
        HubItem(account, key, ItemKind.EMAIL, at(day, hour), "Kyle", subject = "Test email", conversationId = key, addedAt = added)

    private val withMail = Snapshot(
        listOf(mail("mail-1", "a", 12, 9, added = 70), mail("mail-2", "b", 12, 8, added = 80), sms("s", 12, 7, added = 90)),
        accounts.map { it.copy(seenAt = 55) } + listOf(Account("mail-1", AccountKind.EMAIL, "a@gmail.com", seenAt = 55), Account("mail-2", AccountKind.EMAIL, "b@outlook.com", seenAt = 55)),
        "sms.app", "dialer.app",
    )

    @Test fun anAccountsViewShowsItsMailOnlyAndSeesItOnly() {
        assertEquals(listOf("a"), inboxLines(withMail, Filter.ALL, null, format, account = "mail-1").filterIsInstance<ItemLine>().map { it.item.sourceKey })
        assertEquals(listOf("a", "b", "s"), inboxLines(withMail, Filter.ALL, null, format).filterIsInstance<ItemLine>().map { it.item.sourceKey })
        assertEquals(emptyList(), inboxLines(withMail, Filter.TEXT, null, format, account = "mail-1").filterIsInstance<ItemLine>())
        assertEquals(mapOf("mail-1" to 70L), toSee(withMail, Filter.ALL, account = "mail-1"))
    }
}
