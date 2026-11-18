package dev.bold7.hub.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The notifications in MessagingStyle, their retention, and the sources the user turned off. */
class MessagingStyleTest {
    private var clock = 1_000_000L
    private val sink = FakeHubSink { clock }
    private val sources = HubSources(sink, FakeProviderRows()) { clock }

    private fun stored() = sink.items.values.filter { it.accountId == "notifications" }.sortedBy { it.sourceKey }

    private val whatsapp = Posted(
        key = "0|com.whatsapp|1|chat|10200",
        packageName = "com.whatsapp",
        postTime = 900_000,
        whenTime = 890_000,
        flags = 0,
        media = false,
        title = "Erin",
        text = "2 messages",
        bigText = null,
        category = "msg",
        messaging = true,
        userKey = "me",
    )

    private fun msg(time: Long, text: String, sender: String? = "Erin", key: String? = sender) = PostedMessage(text, time, sender, key)

    @Test fun everyMessageOfAnUpdateIsAnItemOfTheConversation() = runTest {
        assertEquals(2, sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "Hi"), msg(890_000, "Lunch?")))))
        assertEquals(listOf("${whatsapp.key}@880000", "${whatsapp.key}@890000"), stored().map { it.sourceKey })
        assertEquals(listOf("Hi", "Lunch?"), stored().map { it.preview })
        assertEquals(listOf(880_000L, 890_000L), stored().map { it.timestamp })
        assertTrue(stored().all { it.conversationId == whatsapp.key && it.box == Box.INBOX && !it.read && it.sender == "Erin" })
    }

    @Test fun anUpdateRepeatingItsMessagesWritesOnlyTheNewOne() = runTest {
        sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "Hi"))))
        val changes = sink.changes
        assertEquals(0, sources.posted(whatsapp.copy(postTime = 950_000, messages = listOf(msg(880_000, "Hi")))))
        assertEquals(changes, sink.changes)
        assertEquals(1, sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "Hi"), msg(890_000, "Lunch?")))))
        assertEquals(2, stored().size)
    }

    @Test fun fiftyUpdatesOfTwoHundredMessagesKeepOneItemPerMessage() = runTest {
        val all = (1..200).map { msg(it * 1_000L, "message $it") }
        repeat(50) { sources.posted(whatsapp.copy(postTime = 900_000L + it, messages = all)) }
        assertEquals(200, stored().size)
    }

    @Test fun twoMessagesOfTheSameTimeAreTwoItemsAndTheSameOneTwiceIsOne() = runTest {
        sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "a"), msg(880_000, "b"), msg(880_000, "b"))))
        assertEquals(listOf("a", "b"), stored().map { it.preview }.sorted())
        assertTrue(stored().all { it.sourceKey.startsWith("${whatsapp.key}@880000#") })
        assertTrue(stored().all { it.timestamp == 880_000L && notificationKeyOf(it.sourceKey) == whatsapp.key })
        // The same two again: nothing new.
        assertEquals(0, sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "b"), msg(880_000, "a")))))
    }

    // Instagram, once I reply, titles its notification with my own name: a 1:1 conversation keeps
    // the name of the one I write to, the sender of its received messages (seen on the Q25).
    @Test fun myReplyInAOneToOneIsNamedAfterTheOtherOneNotTheTitle() = runTest {
        sources.posted(whatsapp.copy(title = "Me Myself", messages = listOf(msg(880_000, "Hi"), msg(890_000, "Hello", "Me Myself", "me"))))
        assertEquals(listOf("Erin" to Box.INBOX, "Erin" to Box.SENT), stored().map { it.sender to it.box })
    }

    @Test fun noMessageFallsBackToTheTitleAndText() = runTest {
        assertEquals(1, sources.posted(whatsapp.copy(messages = emptyList())))
        assertEquals(listOf("Erin" to "2 messages"), stored().map { it.sender to it.preview })
    }

    @Test fun anEmptyALongAndARightToLeftTextAreKeptAsTheyAre() = runTest {
        val long = "x".repeat(10_000)
        val rtl = "مرحبا 😀"
        sources.posted(whatsapp.copy(messages = listOf(msg(1_000, ""), msg(2_000, long), msg(3_000, rtl))))
        assertEquals(listOf("", long, rtl), stored().sortedBy { it.timestamp }.map { it.preview })
    }

    @Test fun myOwnMessagesAreSentAndRead() = runTest {
        sources.posted(
            whatsapp.copy(
                messages = listOf(msg(880_000, "Hi"), msg(885_000, "Hello", sender = null), msg(887_000, "Me again", sender = "Me", key = "me")),
            ),
        )
        val (received, mine, again) = stored().sortedBy { it.timestamp }
        assertEquals(Box.INBOX to false, received.box to received.read)
        assertEquals(Box.SENT to true, mine.box to mine.read)
        assertEquals(Box.SENT to true, again.box to again.read)
        // Sent to the conversation, not received from it.
        assertEquals("Erin", mine.sender)
        assertTrue(!mine.isUnread() && !again.isUnread())
    }

    @Test fun aGroupKeepsItsNameAndEachMessageItsSender() = runTest {
        val members = (1..30).map { "Member $it" }
        sources.posted(
            whatsapp.copy(
                title = "Climbing", conversationTitle = "Climbing", group = true,
                messages = members.mapIndexed { i, m -> msg(1_000L + i, "hello", sender = m) } + msg(2_000, "On my way", sender = null),
            ),
        )
        assertEquals(31, stored().size)
        assertTrue(stored().all { it.subject == "Climbing" })
        assertEquals(members.toSet(), stored().filter { it.box == Box.INBOX }.map { it.sender }.toSet())
        assertEquals("Climbing", stored().single { it.box == Box.SENT }.sender)
    }

    @Test fun aConversationTitleOutsideAGroupIsNoSubject() = runTest {
        sources.posted(whatsapp.copy(conversationTitle = "Erin", messages = listOf(msg(1_000, "Hi"))))
        assertEquals(null, stored().single().subject)
    }

    @Test fun aMessageReadInItsAppAndShownAgainStaysRead() = runTest {
        sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "Hi"))))
        sources.removed(whatsapp.key)
        // The next message comes with the history the app shows again.
        assertEquals(1, sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "Hi"), msg(950_000, "Still there?")))))
        assertEquals(listOf(true, false), stored().sortedBy { it.timestamp }.map { it.read })
    }

    @Test fun afterARestartAPostedConversationIsNotWrittenAgain() = runTest {
        val posted = whatsapp.copy(messages = listOf(msg(880_000, "Hi"), msg(885_000, "Hello", sender = null)))
        sources.posted(posted)
        val sources = HubSources(sink, FakeProviderRows()) { clock }
        val changes = sink.changes
        assertEquals(0, sources.connected(listOf(posted)))
        assertEquals(changes, sink.changes)
    }

    // Retention

    @Test fun notificationsOlderThanThirtyDaysAreForgottenOnceADay() = runTest {
        sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "Hi"))))
        sources.posted(whatsapp.copy(key = "other", messages = listOf(msg(890_000, "Old"))))
        sources.removed(whatsapp.key)
        sources.removed("other")
        clock = 890_000 + KEEP_NOTIFICATIONS_MS + 1
        sources.posted(whatsapp.copy(key = "new", messages = listOf(msg(clock, "New"))))
        assertEquals(listOf("New"), stored().map { it.preview })
    }

    @Test fun aNotificationStillPostedIsKeptWhateverItsAge() = runTest {
        sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "Hi"))))
        clock += KEEP_NOTIFICATIONS_MS + 1
        sources.posted(whatsapp.copy(key = "new", messages = listOf(msg(clock, "New"))))
        assertEquals(listOf("Hi", "New"), stored().sortedBy { it.timestamp }.map { it.preview })
    }

    @Test fun aMessageAlreadyOlderThanThirtyDaysDoesNotEnter() = runTest {
        clock = 2 * KEEP_NOTIFICATIONS_MS
        assertEquals(1, sources.posted(whatsapp.copy(messages = listOf(msg(1_000, "Ancient"), msg(clock, "Today")))))
        assertEquals(listOf("Today"), stored().map { it.preview })
    }

    @Test fun onlyTheNotificationsExpire() {
        val now = 10 * KEEP_NOTIFICATIONS_MS
        val sms = HubItem("sms", "sms:1", ItemKind.SMS, 0, "A")
        val old = HubItem("notifications", "k@0", ItemKind.NOTIFICATION, 0, "A", conversationId = "k")
        val posted = HubItem("notifications", "p@0", ItemKind.NOTIFICATION, 0, "A", conversationId = "p")
        val recent = HubItem("notifications", "k@1", ItemKind.NOTIFICATION, now - 1_000, "A", conversationId = "k")
        assertEquals(listOf("k@0"), expired(listOf(sms, old, posted, recent), now, posted = setOf("p")))
    }

    // Sources turned off

    @Test fun signalIsOffByDefault() {
        assertTrue("org.thoughtcrime.securesms" in DEFAULT_DISABLED_SOURCES)
    }

    @Test fun anAppTurnedOffNoLongerEntersAndItsItemsAreForgotten() = runTest {
        sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "Hi"))))
        sources.posted(whatsapp.copy(key = "0|org.telegram.messenger|1|t|1", packageName = "org.telegram.messenger", messages = listOf(msg(880_000, "Yo"))))
        sources.disable(setOf("com.whatsapp"))
        assertEquals(listOf("Yo"), stored().map { it.preview })
        assertEquals(0, sources.posted(whatsapp.copy(messages = listOf(msg(890_000, "Lunch?")))))
        assertEquals(listOf("Yo"), stored().map { it.preview })
        // Turned on again: what it posts enters, its earlier messages included.
        sources.disable(emptySet())
        assertEquals(2, sources.posted(whatsapp.copy(messages = listOf(msg(880_000, "Hi"), msg(890_000, "Lunch?")))))
    }

    @Test fun anAccountTurnedOffIsForgottenAndNoLongerRead() = runTest {
        val rows = FakeProviderRows().apply { sms = listOf(SmsRow(1, 7, "+33600000001", "Alice", 500, "Hi", false, 1)) }
        val sources = HubSources(sink, rows) { clock }
        sources.syncTexts()
        sources.disable(setOf(HubAccounts.TEXT_MESSAGES.id))
        assertTrue(sink.items.values.none { it.accountId == "sms" })
        assertEquals(0, sources.syncTexts())
        assertTrue(sink.items.values.none { it.accountId == "sms" })
        // The account itself stays: Messages lists it to be turned on again.
        assertTrue("sms" in sink.accounts)
        sources.disable(emptySet())
        assertEquals(1, sources.syncTexts())
    }

    @Test fun theGateDropsWhatATurnedOffSourceWrites() = runTest {
        var off = setOf("mail:1")
        val gated = GatedSink(sink) { off }
        gated.putAccount(Account("mail:1", AccountKind.EMAIL, "a@example.com"))
        gated.putAccount(HubAccounts.NOTIFICATIONS)
        gated.put(listOf(HubItem("mail:1", "1", ItemKind.EMAIL, 0, "A")))
        gated.replace("mail:1", emptyList(), listOf(HubItem("mail:1", "2", ItemKind.EMAIL, 0, "A")))
        gated.putAttachments("mail:1", "1", listOf(Attachment("mail:1", "1", 0, "a.pdf", "application/pdf")))
        assertTrue(sink.items.isEmpty() && sink.attachments.isEmpty())
        off = setOf("com.whatsapp")
        gated.put(listOf(HubItem("mail:1", "1", ItemKind.EMAIL, 0, "A"), HubItem("notifications", "k@1", ItemKind.NOTIFICATION, 0, "A", folder = "com.whatsapp")))
        assertEquals(listOf("1"), sink.items.values.map { it.sourceKey })
    }
}
