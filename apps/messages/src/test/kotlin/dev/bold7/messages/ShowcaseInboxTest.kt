package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.ItemKind
import dev.bold7.showcase.Showcase
import dev.bold7.showcase.ShowcaseEvent
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

private val showcaseFormat = InboxFormat(ZoneId.of("America/Toronto"), Locale.ENGLISH, clock24 = false)
private const val NOW = 1_800_000_000_000L

/** Every real source of the thread, failing at any call: the showcase must reach none of them. */
private object Tripwire : ThreadReader, ContactBook, MessagingApi, AttachmentStore {
    private fun real(): Nothing = error("real source reached in the showcase")
    override fun read(threadId: Long) = real()
    override fun threads() = real()
    override fun all() = real()
    override fun photo(number: String) = real()
    override fun chosen(uri: String) = real()
    override fun state() = real()
    override fun create(to: String) = real()
    override fun createGroup(to: List<String>) = real()
    override fun send(threadId: Long, text: String, attachment: Attachment?) = real()
    override fun run(call: ApiCall) = real()
    override fun take(source: String) = real()
    override fun drop(attachment: Attachment) = real()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShowcaseInboxTest {
    private val snapshot = showcaseSnapshot(NOW)

    @Test fun theInboxHoldsTextsEmailsAndMissedCallsMadeUp() {
        val kinds = snapshot.items.map { it.kind }.toSet()
        assertEquals(setOf(ItemKind.SMS, ItemKind.MMS, ItemKind.EMAIL, ItemKind.MISSED_CALL), kinds)
        assertTrue(snapshot.demo, "never written back")
        assertTrue(snapshot.items.all { it.sourceKey.startsWith(SHOWCASE_KEY) })
        assertTrue(snapshot.items.count { it.read.not() } >= 4)
        assertTrue(snapshot.items.all { it.timestamp in NOW - 7 * 86_400_000L..NOW })
    }

    @Test fun theNewestRowIsOliviasUnreadTextWithItsSmiley() {
        val lines = inboxLines(snapshot, Filter.ALL, null, showcaseFormat).filterIsInstance<ItemLine>()
        val first = lines.first()
        assertEquals("Olivia Bennett", first.sender)
        assertTrue(first.unread)
        // Sent as a phone sends it: ":)" leaves as its emoji, which the row draws back.
        assertTrue("\uD83D\uDE42" in first.preview, first.preview)
        assertEquals(MessageIcon.SMS_UNREAD, first.icon)
        val group = lines.single { it.sender == "Daniel, Lucas, Sofia" }
        assertEquals("And the snacks! \uD83D\uDC4D", group.preview)
        val mail = lines.single { it.item.kind == ItemKind.EMAIL && it.sender == "James Whitaker" }
        assertEquals("Q4 planning agenda", mail.preview)
        assertTrue(lines.any { it.item.kind == ItemKind.MISSED_CALL && it.sender == "Grace Carter" && it.unread })
    }

    @Test fun aSentLastMessageShowsTheSentPen() {
        val lines = inboxLines(snapshot, Filter.ALL, null, showcaseFormat).filterIsInstance<ItemLine>()
        val grace = lines.single { it.sender == "Grace Carter" && it.item.kind == ItemKind.SMS }
        assertEquals(Box.SENT, grace.item.box)
        assertEquals(MessageIcon.SMS_SENT, grace.icon)
    }

    @Test fun eachTextRowOpensItsOwnShowcaseThread() {
        val ids = snapshot.items.filter { it.kind == ItemKind.SMS || it.kind == ItemKind.MMS }.mapNotNull { it.conversationId?.toLong() }.toSet()
        assertEquals(Showcase.threads.count { it.channel == dev.bold7.showcase.Channel.TEXT }, ids.size)
        assertTrue(ids.all { isShowcaseThread(it) && it != DEMO_THREAD })
    }

    @Test fun theSwitchNeverBuildsTheRealReaderWhileOn() {
        val reader = SwitchedInbox({ true }, { error("real source opened") }, ShowcaseInbox { showcaseFormat })
        assertEquals(snapshot.items.size, reader.read()!!.items.size)
        assertEquals(showcaseFormat, reader.format())
        reader.write("delete", listOf(ItemKey("showcase-sms", "showcase:t1:0")))
        reader.markSeen(mapOf("showcase-sms" to NOW))
    }

    @Test fun theSwitchReadsTheRealReaderWhileOff() {
        var built = 0
        val real = ShowcaseInbox { showcaseFormat }
        val reader = SwitchedInbox({ false }, { built++; real }, ShowcaseInbox { error("showcase read") })
        reader.read()
        reader.read()
        assertEquals(1, built)
    }

    @Test fun aGroupThreadNamesEachSenderAndOnePersonHasAPicture() {
        val group = showcaseConversation(showcaseThreadId(2), NOW)!!
        assertEquals("Daniel, Lucas, Sofia", group.title)
        assertNull(group.photo)
        assertEquals(listOf("Lucas Ferreira", "Sofia Romano", null, "Daniel Okafor", "Lucas Ferreira"), group.messages.map { it.from })
        val olivia = showcaseConversation(showcaseThreadId(1), NOW)!!
        assertEquals("Olivia Bennett", olivia.title)
        assertEquals(Showcase.person(1).photo, olivia.photo)
        assertTrue(olivia.loaded)
        assertEquals(NOW - 6 * 60_000L, olivia.messages.last().time)
        assertNull(showcaseConversation(42, NOW))
    }

    @Test fun aShowcaseThreadReachesNoRealSource() {
        val scope = TestScope(StandardTestDispatcher())
        val errors = mutableListOf<RuntimeException>()
        val conversation = Conversation(Tripwire, Tripwire, Tripwire, Tripwire, StandardTestDispatcher(scope.testScheduler), scope) { errors += it }
        conversation.show(showcaseConversation(showcaseThreadId(1), NOW)!!)
        conversation.reload()
        conversation.event(ThreadEvent.Typed('a'))
        conversation.send()
        conversation.attach("content://media/1")
        conversation.delete(listOf(conversation.state.value.messages.first().uri))
        scope.advanceUntilIdle()
        assertEquals(emptyList(), errors)
        // Sent in this screen only: the bubble shows, nothing left.
        assertEquals(5, conversation.state.value.messages.size)
        assertEquals("a", conversation.state.value.messages.last().text)
        assertTrue(conversation.state.value.messages.last().out)
        assertEquals("", conversation.state.value.draft)
    }

    @Test fun theStorysTextArrivesInTheInboxThenIsAnsweredAndRepliedToInTheThread() {
        val inbox = ShowcaseInbox({ ShowcaseEvent.MESSAGE }) { showcaseFormat }.read()
        val newest = inbox.items.maxBy { it.timestamp }
        assertTrue(newest.preview.startsWith("Dinner tonight?"), newest.preview)
        assertEquals("Daniel Okafor", newest.sender)

        val scope = TestScope(StandardTestDispatcher())
        val errors = mutableListOf<RuntimeException>()
        val conversation = Conversation(Tripwire, Tripwire, Tripwire, Tripwire, StandardTestDispatcher(scope.testScheduler), scope) { errors += it }
        val thread = showcaseThreadId(Showcase.STORY_THREAD)
        conversation.show(showcaseConversation(thread, NOW, ShowcaseEvent.MESSAGE)!!)
        val before = conversation.state.value.messages.size
        conversation.event(ThreadEvent.Edited("", Showcase.STORY_ANSWER))
        conversation.send(NOW + 1000)
        // The reply arrives: added after the answer sent here, which is not doubled.
        conversation.arrive(showcaseConversation(thread, NOW + 2000, ShowcaseEvent.REPLY)!!)
        conversation.arrive(showcaseConversation(thread, NOW + 3000, ShowcaseEvent.REPLY)!!)
        // Another thread's story changes nothing here.
        conversation.arrive(showcaseConversation(showcaseThreadId(1), NOW, ShowcaseEvent.REPLY)!!)
        scope.advanceUntilIdle()
        assertEquals(emptyList(), errors)
        val messages = conversation.state.value.messages
        assertEquals(before + 2, messages.size)
        assertEquals(listOf(false, true, false), messages.takeLast(3).map { it.out })
        assertEquals(Showcase.STORY_ANSWER, messages[messages.size - 2].text)
        assertTrue(messages.last().text.startsWith("Great, see you there"))
    }

    @Test fun anEmailOpensMadeUp() {
        val key = snapshot.items.first { it.kind == ItemKind.EMAIL && it.subject == "Q4 planning agenda" }.let { ItemKey(it.accountId, it.sourceKey) }
        val mail = assertNotNull(showcaseMail(key, NOW))
        assertEquals("James Whitaker", mail.from?.shown)
        assertEquals(Showcase.OWNER_EMAIL, mail.to.single().email)
        assertTrue(mail.text.startsWith("Hi Alex"))
        assertNull(showcaseMail(ItemKey("mail", "7"), NOW))
    }
}
