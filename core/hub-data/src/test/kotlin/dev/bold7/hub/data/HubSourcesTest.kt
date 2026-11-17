package dev.bold7.hub.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android's providers as the sources read them, in memory. */
class FakeProviderRows : ProviderRows {
    var sms = listOf<SmsRow>()
    var mms = listOf<MmsRow>()
    var calls = listOf<CallRow>()
    var events = listOf<EventRow>()
    override fun sms() = sms
    override fun mms() = mms
    override fun calls() = calls
    override fun events() = events
}

class HubCategoryTest {
    @Test fun aNotificationKeepsItsCategoryAndItsStyle() {
        val p = Posted("k", "com.whatsapp", 5, 0, 0, false, "A", "hi", null, category = "msg", messaging = true)
        assertEquals(HubItem("notifications", "k@5", ItemKind.NOTIFICATION, 5, "A", preview = "hi", folder = "com.whatsapp", conversationId = "k", category = "msg", messaging = true), p.toItem())
        // No category is "" (known to have none), never null (unknown).
        assertEquals("", p.copy(category = null, messaging = false).toItem().category)
    }
}

class HubSourcesTest {
    private var clock = 1_000L
    private val sink = FakeHubSink { clock }
    private val rows = FakeProviderRows()
    private val sources = HubSources(sink, rows) { clock }

    private fun stored(account: String) = sink.items.values.filter { it.accountId == account }.sortedBy { it.sourceKey }

    // Text messages

    private val received = SmsRow(id = 1, threadId = 7, address = "+33600000001", name = "Alice", date = 500, body = "Hi", read = false, type = 1)

    @Test fun anSmsBecomesAnItemOfTheTextMessagesAccount() = runTest {
        rows.sms = listOf(received)
        assertEquals(1, sources.syncTexts())
        assertEquals(listOf(HubAccounts.TEXT_MESSAGES), sink.accounts.values.toList())
        assertEquals(
            listOf(HubItem("sms", "sms:1", ItemKind.SMS, 500, "Alice", address = "+33600000001", preview = "Hi", conversationId = "7", addedAt = 1_000)),
            stored("sms"),
        )
    }

    @Test fun anSmsWithoutAContactShowsItsNumberAndItsTypeGivesItsBox() = runTest {
        rows.sms = listOf(
            received.copy(name = null),
            received.copy(id = 2, type = 2, read = true),
            received.copy(id = 3, type = 3),
            received.copy(id = 4, type = 4),
            received.copy(id = 5, type = 5),
            received.copy(id = 6, type = 6),
        )
        sources.syncTexts()
        assertEquals("+33600000001", stored("sms").first().sender)
        assertEquals(listOf(Box.INBOX, Box.SENT, Box.DRAFT, Box.OUTBOX, Box.OUTBOX, Box.OUTBOX), stored("sms").map { it.box })
    }

    @Test fun anMmsSharesTheThreadOfItsSmsAndItsDateIsInSeconds() = runTest {
        rows.sms = listOf(received)
        rows.mms = listOf(MmsRow(id = 1, threadId = 7, address = "+33600000001", name = "Alice", dateSeconds = 2, subject = "Photo", text = "Look", read = true, box = 1, attachments = 2))
        sources.syncTexts()
        val mms = stored("sms").single { it.kind == ItemKind.MMS }
        // SMS and MMS ids are two sequences: the keys keep them apart.
        assertEquals("mms:1", mms.sourceKey)
        assertEquals(2_000, mms.timestamp)
        assertEquals("7", mms.conversationId)
        assertEquals("Photo", mms.subject)
        assertEquals("Look", mms.preview)
        assertEquals(2, mms.attachmentCount)
        assertTrue(mms.read)
    }

    @Test fun aGroupMmsNamesEveryMemberOfItsThreadInItsAddress() = runTest {
        // The thread's recipients (Telephony's threads, the user left out): the sender stays the
        // sender, the address names the whole group, as smsto: joins them, so that opening it
        // never opens a conversation with one member.
        val group = MmsRow(id = 1, threadId = 7, address = "+331", name = "Alice", dateSeconds = 2, subject = null, text = "Hi all", read = false, box = 1, attachments = 0, recipients = listOf("+331", "+332", "+333"))
        rows.mms = listOf(group, group.copy(id = 2, threadId = 8, recipients = listOf("+331")))
        sources.syncTexts()
        val (inGroup, alone) = stored("sms").sortedBy { it.sourceKey }
        assertEquals("+331;+332;+333", inGroup.address)
        assertEquals("Alice", inGroup.sender)
        assertEquals("+331", alone.address)
    }

    @Test fun aSecondSyncWritesOnlyWhatChangedAndForgetsWhatTheProviderLost() = runTest {
        rows.sms = listOf(received, received.copy(id = 2))
        sources.syncTexts()
        val changes = sink.changes
        // Nothing new: no write, no change announced to the counters' readers.
        assertEquals(0, sources.syncTexts())
        assertEquals(changes, sink.changes)
        // Read in Google Messages, the other one deleted.
        clock = 2_000
        rows.sms = listOf(received.copy(read = true))
        // One rewritten, one removed.
        assertEquals(2, sources.syncTexts())
        assertEquals(listOf("sms:1"), stored("sms").map { it.sourceKey })
        assertTrue(stored("sms").single().read)
        assertEquals(1_000, stored("sms").single().addedAt)
    }

    @Test fun aNewTextGivenTheIdOfADeletedOneIsANewMessageNotTheDeletedOne() = runTest {
        // Telephony gives the _id of the last rows deleted again (no AUTOINCREMENT): sms:1 of the
        // afternoon, deleted in the Hub, then every text deleted (an empty read forgets nothing),
        // and the first text of the evening is sms:1 again, of another thread.
        rows.sms = listOf(received)
        sources.syncTexts()
        setInHub("sms", "sms:1") { it.copy(deleted = true, read = true) }
        rows.sms = emptyList()
        sources.syncTexts()
        clock = 9_000
        val evening = SmsRow(id = 1, threadId = 16, address = "+33600000002", name = "Lisa", date = 8_000, body = "Salut", read = false, type = 1)
        rows.sms = listOf(evening)
        sources.syncTexts()
        val item = stored("sms").single()
        assertEquals(listOf(false, false, 9_000L, "16", 8_000L), listOf(item.deleted, item.read, item.addedAt, item.conversationId, item.timestamp))
    }

    @Test fun aTextAlreadyMergedWithTheDeletedOneComesBack() = runTest {
        // As left on the phone by the builds before: the evening's text stored under sms:1 with its
        // own time and thread, but the afternoon's arrival and deletion. Seen by the Hub before it
        // was written: it is not that message.
        clock = 8_000_100
        rows.sms = listOf(SmsRow(id = 1, threadId = 16, address = "+33600000002", name = "Lisa", date = 8_000_000, body = "Salut", read = true, type = 2))
        sources.syncTexts()
        sink.items[("sms" to "sms:1")] = sink.items.getValue("sms" to "sms:1").copy(addedAt = 1_000, deleted = true)
        clock = 8_000_500
        sources.syncTexts()
        val item = stored("sms").single()
        assertEquals(listOf(false, 8_000_500L), listOf(item.deleted, item.addedAt))
    }

    @Test fun aTextWrittenWithTheClockSetBackAFewMinutesStaysTheSame() = runTest {
        rows.sms = listOf(received.copy(date = 1_000 + 9 * 60_000L))
        sources.syncTexts()
        setInHub("sms", "sms:1") { it.copy(deleted = true) }
        sources.syncTexts()
        assertTrue(stored("sms").single().deleted)
    }

    @Test fun aContactRenamedRenamesItsTexts() = runTest {
        rows.sms = listOf(received.copy(name = "Lulu"))
        sources.syncTexts()
        setInHub("sms", "sms:1") { it.copy(read = true) }
        rows.sms = listOf(received.copy(name = "Lisa"))
        assertEquals(1, sources.syncTexts())
        // The same message: renamed, still read in the Hub, its arrival kept.
        assertEquals(listOf(Triple("Lisa", true, 1_000L)), stored("sms").map { Triple(it.sender, it.read, it.addedAt) })
    }

    // What the user set in the Hub

    private fun setInHub(account: String, key: String, change: (HubItem) -> HubItem) {
        sink.items[account to key] = change(sink.items.getValue(account to key))
    }

    @Test fun theHubKeepsItsReadStateWhileTheSourceKeepsItsOwn() = runTest {
        rows.sms = listOf(received, received.copy(id = 2, read = true))
        sources.syncTexts()
        // Read in the Hub, unread again in the Hub: Google Messages has not changed its mind.
        setInHub("sms", "sms:1") { it.copy(read = true) }
        setInHub("sms", "sms:2") { it.copy(read = false) }
        rows.sms = listOf(received, received.copy(id = 2, read = true), received.copy(id = 3))
        assertEquals(1, sources.syncTexts())
        assertEquals(mapOf("sms:1" to true, "sms:2" to false, "sms:3" to false), stored("sms").associate { it.sourceKey to it.read })
    }

    @Test fun theSourcesOwnChangeOfReadReachesTheHub() = runTest {
        rows.sms = listOf(received, received.copy(id = 2, read = true))
        sources.syncTexts()
        setInHub("sms", "sms:2") { it.copy(read = false) }
        // Read in Google Messages; the other one marked unread there.
        rows.sms = listOf(received.copy(read = true), received.copy(id = 2, read = false))
        assertEquals(2, sources.syncTexts())
        assertEquals(mapOf("sms:1" to true, "sms:2" to false), stored("sms").associate { it.sourceKey to it.read })
        assertEquals(listOf(true, false), stored("sms").map { it.sourceRead })
    }

    @Test fun anItemDeletedInTheHubStaysDeletedAndUnwritten() = runTest {
        rows.calls = listOf(missed)
        sources.syncCalls()
        setInHub("calls", "3") { it.copy(deleted = true) }
        val changes = sink.changes
        assertEquals(0, sources.syncCalls())
        assertEquals(changes, sink.changes)
        // Seen in the Phone app: still deleted, now read.
        rows.calls = listOf(missed.copy(new = false))
        sources.syncCalls()
        assertEquals(listOf(true to true), stored("calls").map { it.deleted to it.read })
    }

    @Test fun anEmptyReadForgetsNothing() = runTest {
        // READ_SMS on ignore in AppOps answers an empty cursor: the account stays as it was,
        // rather than coming back whole later as new.
        rows.sms = listOf(received)
        rows.calls = listOf(missed)
        rows.events = listOf(invited)
        sources.syncTexts()
        sources.syncCalls()
        sources.syncInvitations()
        val changes = sink.changes
        rows.sms = emptyList()
        rows.calls = emptyList()
        rows.events = emptyList()
        assertEquals(0, sources.syncTexts() + sources.syncCalls() + sources.syncInvitations())
        assertEquals(changes, sink.changes)
        assertEquals(listOf("sms:1", "3", "9"), (stored("sms") + stored("calls") + stored("calendar")).map { it.sourceKey })
    }

    @Test fun mmsAloneAreNotAnEmptyRead() = runTest {
        rows.sms = listOf(received)
        rows.mms = listOf(MmsRow(id = 1, threadId = 7, address = null, name = null, dateSeconds = 2, subject = null, text = "Look", read = true, box = 1, attachments = 0))
        sources.syncTexts()
        // The SMS deleted, the MMS left: a real read, which forgets the SMS.
        rows.sms = emptyList()
        assertEquals(1, sources.syncTexts())
        assertEquals(listOf("mms:1"), stored("sms").map { it.sourceKey })
    }

    // Calls

    private val missed = CallRow(id = 3, number = "+33600000002", name = "Bob", date = 700, type = CallRow.MISSED, new = true, isRead = false)

    @Test fun onlyMissedCallsEnterTheHub() = runTest {
        rows.calls = listOf(missed, missed.copy(id = 4, type = 1), missed.copy(id = 5, type = 2), missed.copy(id = 6, type = 5))
        sources.syncCalls()
        assertEquals(listOf(HubAccounts.CALLS), sink.accounts.values.toList())
        assertEquals(
            listOf(HubItem("calls", "3", ItemKind.MISSED_CALL, 700, "Bob", address = "+33600000002", addedAt = 1_000)),
            stored("calls"),
        )
    }

    @Test fun aMissedCallIsReadOnceSeenOrMarkedRead() = runTest {
        rows.calls = listOf(missed.copy(name = null), missed.copy(id = 4, new = false), missed.copy(id = 5, isRead = true))
        sources.syncCalls()
        assertEquals(listOf(false, true, true), stored("calls").map { it.read })
        assertEquals("+33600000002", stored("calls").first().sender)
    }

    @Test fun aMissedCallGoneFromTheLogLeavesTheHub() = runTest {
        rows.calls = listOf(missed)
        sources.syncCalls()
        // The log keeps another call.
        rows.calls = listOf(missed.copy(id = 4, type = 1))
        sources.syncCalls()
        assertEquals(emptyList(), stored("calls"))
    }

    // Calendar

    private val invited = EventRow(id = 9, title = "Review", organizer = "carol@example.com", start = 5_000, status = EventRow.INVITED, isOrganizer = false, hasAttendees = true)

    @Test fun anInvitationFromSomeoneElseEntersTheHubUnreadUntilAnswered() = runTest {
        rows.events = listOf(
            invited,
            invited.copy(id = 10, status = 1),
            // My own meeting, an event without attendees, one I am not in: not invitations.
            invited.copy(id = 11, isOrganizer = true),
            invited.copy(id = 12, hasAttendees = false),
            invited.copy(id = 13, status = 0),
        )
        sources.syncInvitations()
        assertEquals(listOf(HubAccounts.CALENDAR), sink.accounts.values.toList())
        val items = stored("calendar")
        assertEquals(listOf("10", "9"), items.map { it.sourceKey })
        assertEquals(listOf(true, false), items.map { it.read })
        assertEquals(HubItem("calendar", "9", ItemKind.INVITATION, 1_000, "carol@example.com", address = "carol@example.com", subject = "Review", addedAt = 1_000), items[1])
    }

    @Test fun anInvitationIsDatedWhenTheHubFirstSawItOrAtItsMeetingIfThatIsEarlier() = runTest {
        // A meeting to come: dated now, not at the top of the Hub until it starts.
        rows.events = listOf(invited, invited.copy(id = 10, start = 400))
        sources.syncInvitations()
        assertEquals(listOf(400L, 1_000L), stored("calendar").map { it.timestamp })
        // Answered later: still where it was in the list.
        clock = 3_000
        rows.events = listOf(invited.copy(status = 1), invited.copy(id = 10, start = 400))
        assertEquals(1, sources.syncInvitations())
        assertEquals(listOf(400L, 1_000L), stored("calendar").map { it.timestamp })
    }

    @Test fun anInvitationToAMeetingAlreadyStartedIsRead() = runTest {
        rows.events = listOf(invited.copy(start = 999), invited.copy(id = 10, start = 1_000), invited.copy(id = 11, start = 1_001))
        sources.syncInvitations()
        assertEquals(mapOf("9" to true, "10" to true, "11" to false), stored("calendar").associate { it.sourceKey to it.read })
    }

    // Notifications

    private val gmail = Posted(
        key = "0|com.google.android.gm|1|tag|10100",
        packageName = "com.google.android.gm",
        postTime = 900,
        whenTime = 880,
        flags = 0x10,
        media = false,
        title = "Dave",
        text = "Lunch",
        bigText = "Lunch\nAt noon?",
    )

    @Test fun aNotificationBecomesAnItemKeyedByItsNotificationAndItsTime() = runTest {
        assertEquals(1, sources.posted(gmail))
        assertEquals(listOf(HubAccounts.NOTIFICATIONS), sink.accounts.values.toList())
        assertEquals(
            listOf(
                HubItem(
                    "notifications", "0|com.google.android.gm|1|tag|10100@880", ItemKind.NOTIFICATION, 900, "Dave",
                    preview = "Lunch\nAt noon?", folder = "com.google.android.gm",
                    conversationId = "0|com.google.android.gm|1|tag|10100", addedAt = 1_000, category = "",
                ),
            ),
            stored("notifications"),
        )
    }

    @Test fun aConversationShowsItsLastMessageAndItsSender() = runTest {
        sources.posted(gmail.copy(conversationTitle = "Team", lastSender = "Erin", lastMessage = "On my way", text = "2 new messages"))
        val item = stored("notifications").single()
        assertEquals("Erin", item.sender)
        assertEquals("Team", item.subject)
        assertEquals("On my way", item.preview)
    }

    @Test fun ongoingForegroundSummaryMediaAndOwnNotificationsStayOut() = runTest {
        assertEquals(0, sources.posted(gmail.copy(flags = Posted.FLAG_ONGOING_EVENT)))
        assertEquals(0, sources.posted(gmail.copy(flags = Posted.FLAG_FOREGROUND_SERVICE)))
        assertEquals(0, sources.posted(gmail.copy(flags = Posted.FLAG_GROUP_SUMMARY)))
        assertEquals(0, sources.posted(gmail.copy(media = true)))
        assertEquals(0, sources.posted(gmail.copy(packageName = "dev.bold7.hub")))
        assertEquals(0, sources.posted(gmail.copy(packageName = "dev.bold7")))
        // Anything else enters by default, a lookalike package included.
        assertEquals(1, sources.posted(gmail.copy(packageName = "dev.bold7ur", key = "other")))
        // The test app stands for a third-party app.
        assertEquals(1, sources.posted(gmail.copy(packageName = "dev.bold7.essai", key = "essai")))
        assertEquals(listOf("essai@880", "other@880"), stored("notifications").map { it.sourceKey }.sorted())
    }

    @Test fun aNewMessageInTheSameNotificationIsANewItemOfItsConversation() = runTest {
        sources.posted(gmail)
        sources.posted(gmail.copy(postTime = 950, whenTime = 940, text = "Dinner", bigText = null))
        assertEquals(listOf("Lunch\nAt noon?", "Dinner"), stored("notifications").map { it.preview })
        assertEquals(setOf(gmail.key), stored("notifications").map { it.conversationId }.toSet())
    }

    @Test fun aRepostOfTheSameContentRewritesTheSameItem() = runTest {
        sources.posted(gmail)
        // Same content, a new time: an app updating its notification without anything new.
        val changes = sink.changes
        assertEquals(0, sources.posted(gmail.copy(postTime = 990, whenTime = 990)))
        assertEquals(changes, sink.changes)
        assertEquals(listOf("${gmail.key}@880"), stored("notifications").map { it.sourceKey })
        // Without a time of its own the post time keys it.
        sources.posted(gmail.copy(key = "k2", whenTime = 0, postTime = 999))
        assertTrue(stored("notifications").any { it.sourceKey == "k2@999" })
    }

    @Test fun aNewSenderOrSubjectWithTheSameTextIsANewItem() = runTest {
        sources.posted(gmail.copy(conversationTitle = "Team", lastSender = "Erin", lastMessage = "OK"))
        assertEquals(1, sources.posted(gmail.copy(whenTime = 940, conversationTitle = "Team", lastSender = "Frank", lastMessage = "OK")))
        assertEquals(1, sources.posted(gmail.copy(whenTime = 960, conversationTitle = "Other team", lastSender = "Frank", lastMessage = "OK")))
        assertEquals(3, stored("notifications").size)
    }

    @Test fun aRemovedNotificationLeavesItsItemsInTheHubRead() = runTest {
        sources.posted(gmail)
        sources.posted(gmail.copy(postTime = 950, whenTime = 940, text = "Dinner", bigText = null))
        sources.posted(gmail.copy(key = "other"))
        assertEquals(2, sources.removed(gmail.key))
        assertEquals(mapOf("${gmail.key}@880" to true, "${gmail.key}@940" to true, "other@880" to false), stored("notifications").associate { it.sourceKey to it.read })
        // Removed again (a second callback, a rebinding): nothing left to write or announce.
        val changes = sink.changes
        assertEquals(0, sources.removed(gmail.key))
        assertEquals(changes, sink.changes)
    }

    @Test fun afterARemovalTheSameContentPostedAgainIsANewItem() = runTest {
        sources.posted(gmail)
        sources.removed(gmail.key)
        sources.posted(gmail.copy(postTime = 2_000, whenTime = 2_000))
        assertEquals(listOf(true, false), stored("notifications").sortedBy { it.timestamp }.map { it.read })
    }

    @Test fun onConnectingTheActiveNotificationsEnterAndThoseRemovedMeanwhileAreRead() = runTest {
        sources.posted(gmail)
        sources.posted(gmail.copy(key = "still"))
        // The listener was unbound: "gmail" was removed meanwhile, "still" is there, "new" came.
        val sources = HubSources(sink, rows) { clock }
        // One written, one marked read.
        assertEquals(2, sources.connected(listOf(gmail.copy(key = "still"), gmail.copy(key = "new"), gmail.copy(key = "ongoing", flags = Posted.FLAG_ONGOING_EVENT))))
        assertEquals(mapOf("${gmail.key}@880" to true, "still@880" to false, "new@880" to false), stored("notifications").associate { it.sourceKey to it.read })
    }

    @Test fun aSnapshotTakenOnceUnboundMarksNothingRead() = runTest {
        sources.posted(gmail)
        // The listener was unbound again before its snapshot, which is empty for that reason alone.
        sources.connected(emptyList(), complete = false)
        assertEquals(listOf(false), stored("notifications").map { it.read })
        // What it holds still enters.
        sources.connected(listOf(gmail.copy(key = "new")), complete = false)
        assertEquals(mapOf("${gmail.key}@880" to false, "new@880" to false), stored("notifications").associate { it.sourceKey to it.read })
    }

    @Test fun afterARestartTheActiveNotificationsAreNeitherRewrittenNorDoubled() = runTest {
        sources.posted(gmail)
        // A new process (an update, a crash): its memory of the posted notifications is gone.
        val sources = HubSources(sink, rows) { clock }
        val changes = sink.changes
        assertEquals(0, sources.connected(listOf(gmail)))
        assertEquals(changes, sink.changes)
        // An app reposting the same content with a new time, as those that never call setWhen do.
        assertEquals(0, sources.posted(gmail.copy(postTime = 990, whenTime = 990)))
        assertEquals(listOf("${gmail.key}@880"), stored("notifications").map { it.sourceKey })
    }

    @Test fun afterARestartTheLatestItemOfANotificationIsTheOneCompared() = runTest {
        sources.posted(gmail)
        sources.posted(gmail.copy(postTime = 950, whenTime = 940, text = "Dinner", bigText = null))
        val sources = HubSources(sink, rows) { clock }
        val changes = sink.changes
        assertEquals(0, sources.connected(listOf(gmail.copy(postTime = 950, whenTime = 940, text = "Dinner", bigText = null))))
        assertEquals(changes, sink.changes)
    }

    @Test fun afterARestartTheSameContentPostedAgainAfterARemovalIsANewItem() = runTest {
        // "Missed call / Bob", removed yesterday, posted again today with another time.
        sources.posted(gmail)
        sources.removed(gmail.key)
        val sources = HubSources(sink, rows) { clock }
        assertEquals(1, sources.connected(listOf(gmail.copy(postTime = 2_000, whenTime = 2_000))))
        assertEquals(listOf(true, false), stored("notifications").sortedBy { it.timestamp }.map { it.read })
    }

    @Test fun aNotificationReadInTheHubStaysReadWhileItsAppRepostsIt() = runTest {
        sources.posted(gmail)
        setInHub("notifications", "${gmail.key}@880") { it.copy(read = true) }
        // A new process: the notification is still posted, the Hub has read it.
        val sources = HubSources(sink, rows) { clock }
        assertEquals(0, sources.connected(listOf(gmail)))
        assertEquals(0, sources.posted(gmail.copy(postTime = 990, whenTime = 990)))
        assertEquals(listOf(true), stored("notifications").map { it.read })
    }

    @Test fun anUpdateOfTheSameMessageKeepsWhatTheHubSet() = runTest {
        // Deleted in the Hub, still posted (not cancelled: FLAG_NO_CLEAR, or no listener bound),
        // then updated with the same time: it stays deleted. Read in the Hub, it stays read.
        sources.posted(gmail)
        sources.posted(gmail.copy(key = "other"))
        setInHub("notifications", "${gmail.key}@880") { it.copy(deleted = true) }
        setInHub("notifications", "other@880") { it.copy(read = true) }
        sources.posted(gmail.copy(text = "Lunch?", bigText = null))
        sources.posted(gmail.copy(key = "other", text = "Lunch?", bigText = null))
        assertEquals(
            mapOf("${gmail.key}@880" to (true to false), "other@880" to (false to true)),
            stored("notifications").associate { it.sourceKey to (it.deleted to it.read) },
        )
        assertEquals("Lunch?", stored("notifications").first().preview)
    }

    @Test fun aRemovalIsTheSourcesOwnRead() = runTest {
        sources.posted(gmail)
        sources.removed(gmail.key)
        assertEquals(listOf(true to true), stored("notifications").map { it.read to it.sourceRead })
        // Read in the Hub before its removal: the removal still counts as the source's.
        sources.posted(gmail.copy(key = "other"))
        setInHub("notifications", "other@880") { it.copy(read = true) }
        assertEquals(1, sources.removed("other"))
    }

    @Test fun aNotificationRemovedWhileUnboundAndPostedAgainIsANewItem() = runTest {
        sources.posted(gmail)
        // Same process, the listener unbound then bound again: gmail was removed meanwhile.
        sources.connected(emptyList())
        assertEquals(1, sources.posted(gmail.copy(postTime = 2_000, whenTime = 2_000)))
        assertEquals(listOf(true, false), stored("notifications").sortedBy { it.timestamp }.map { it.read })
    }
}
