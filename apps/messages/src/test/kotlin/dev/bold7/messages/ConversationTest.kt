package dev.bold7.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

/** Telephony by thread, by hand (docs/ARCHITECTURE.md §2). */
private class FakeThreads : ThreadReader {
    val threads = HashMap<Long, MutableList<ThreadMessage>>()
    /** Each thread's members, newest first. */
    val members = mutableListOf<Pair<Long, List<String>>>()
    var reads = 0
    override fun read(threadId: Long): List<ThreadMessage>? {
        reads++
        return threads[threadId]?.toList()
    }

    override fun threads(): List<Pair<Long, List<String>>>? = members.toList()
}

/** The address book, by hand. */
private class FakeContacts : ContactBook {
    val people = mutableListOf<Contact>()
    val photos = HashMap<String, String>()
    val rows = HashMap<String, Recipient>()
    override fun all() = people.toList()
    override fun photo(number: String) = photos.entries.firstOrNull { sameNumber(it.key, number) }?.value
    override fun name(number: String) = people.firstOrNull { c -> c.numbers.any { sameNumber(it.number, number) } }?.name
    override fun chosen(uri: String) = rows[uri]
}

/** Google Messages' API, by hand: what it was asked, and what it throws. */
private class FakeApi(private val threads: FakeThreads) : MessagingApi {
    var state: ApiState = ApiState.Open
    var failing = false
    val calls = mutableListOf<Any>()
    override fun state() = state
    override fun create(to: String): Long {
        calls += "create $to"
        if (failing) throw MessagingApiException("UNAVAILABLE")
        threads.threads.getOrPut(30) { mutableListOf() }
        return 30
    }

    override fun createGroup(to: List<String>): Long {
        calls += "group ${to.joinToString(",")}"
        if (failing) throw MessagingApiException("UNAVAILABLE")
        threads.threads.getOrPut(31) { mutableListOf() }
        return 31
    }

    override fun send(threadId: Long, text: String, attachment: Attachment?) {
        calls += if (attachment == null) "send $threadId $text" else "send $threadId $text +${attachment.name}"
        if (failing) throw MessagingApiException("UNAVAILABLE")
        // Google Messages copies what it sends into Telephony.
        threads.threads.getValue(threadId) += ThreadMessage("content://sms/${100 + calls.size}", 9, true, text, Delivery.SENDING)
    }

    override fun run(call: ApiCall) {
        calls += call
        if (failing) throw MessagingApiException("UNAVAILABLE")
        if (call is ApiCall.Delete) threads.threads.values.forEach { list -> list.removeAll { it.uri == call.uri } }
    }
}

/** Messages' copies of the files attached, by hand. */
private class FakeStore : AttachmentStore {
    val taken = mutableListOf<String>()
    val dropped = mutableListOf<Attachment>()
    var answer: Taken = Taken.Ok(Attachment("content://dev.bold7.messages.attachments/u1/a.jpg", "image/jpeg", "a.jpg", 1000))
    override fun take(source: String): Taken {
        taken += source
        return answer
    }

    override fun drop(attachment: Attachment) {
        dropped += attachment
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val threads = FakeThreads().apply {
        threads[8] = mutableListOf(
            ThreadMessage("content://sms/1", 1, false, "a", Delivery.RECEIVED),
            ThreadMessage("content://sms/2", 2, false, "b", Delivery.UNREAD),
        )
    }
    private val api = FakeApi(threads)
    private val errors = mutableListOf<RuntimeException>()
    private val contacts = FakeContacts()
    private val store = FakeStore()
    private val conversation = Conversation(threads, api, contacts, store, dispatcher, scope) { errors += it }

    private fun type(text: String) = text.forEach { conversation.event(ThreadEvent.Typed(it)) }
    private val state get() = conversation.state.value

    @Test fun openingReadsTheThreadAndMarksItRead() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        assertEquals(listOf("a", "b"), state.messages.map { it.text })
        assertEquals("513", state.title)
        assertEquals(listOf<Any>(ApiCall.MarkConversationRead(8)), api.calls)
    }

    @Test fun aThreadAllReadIsNotMarkedAgain() {
        threads.threads[8]!![1] = threads.threads[8]!![1].copy(delivery = Delivery.RECEIVED)
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        assertEquals(emptyList(), api.calls)
    }

    @Test fun sendGoesThroughTheApiAndEmptiesTheField() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        type("Salut")
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals("send 8 Salut", api.calls[1])
        assertEquals("", state.draft)
        assertEquals("Salut", state.messages.last().text)
    }

    @Test fun aFailedSendKeepsTheTextAndSaysSo() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        api.failing = true
        type("Salut")
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals("Salut", state.draft)
        assertEquals(Notice.NOT_SENT, state.notice)
        assertEquals(1, errors.size)
    }

    @Test fun nothingLeavesWithoutText() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        api.calls.clear()
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals(emptyList(), api.calls)
    }

    @Test fun aNewMessageCreatesTheConversationThenSends() {
        conversation.open(null, "")
        type("0199")
        conversation.event(ThreadEvent.Step(down = true))
        type("Yo")
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals(listOf<Any>("create 0199", "send 30 Yo"), api.calls)
        assertEquals(30L, state.threadId)
        assertEquals("0199", state.title)
        assertEquals("Yo", state.messages.single().text)
    }

    @Test fun aNewMessageNotCreatedKeepsEverything() {
        conversation.open(null, "")
        type("0199")
        conversation.event(ThreadEvent.Step(down = true))
        type("Yo")
        api.failing = true
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals(null, state.threadId)
        assertEquals("0199", state.to)
        assertEquals("Yo", state.draft)
        assertEquals(Notice.NOT_CREATED, state.notice)
    }

    @Test fun deleteAndClear() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        conversation.delete(listOf("content://sms/1"))
        scope.advanceUntilIdle()
        assertEquals(listOf("b"), state.messages.map { it.text })
        conversation.delete(state.messages.map { it.uri })
        scope.advanceUntilIdle()
        assertEquals(emptyList(), state.messages)
    }

    @Test fun theMadeUpThreadNeverReadsNorWrites() {
        conversation.demo(500, now = 1_000_000)
        scope.advanceUntilIdle()
        assertEquals(DEMO_MAX, state.messages.size)
        assertEquals(true, state.messages.all { it.uri.startsWith("demo:") })
        assertEquals(true, state.messages.zipWithNext().all { (a, b) -> a.time <= b.time })
        type("x")
        conversation.send()
        conversation.reload()
        conversation.delete(listOf(state.messages[0].uri))
        scope.advanceUntilIdle()
        assertEquals(0, threads.reads)
        assertEquals(emptyList(), api.calls)
        assertEquals(DEMO_MAX, state.messages.size)
    }

    @Test fun aFailedDeleteSaysSo() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        api.failing = true
        conversation.delete(listOf("content://sms/1"))
        scope.advanceUntilIdle()
        assertEquals(Notice.NOT_DELETED, state.notice)
        assertEquals(2, state.messages.size)
    }

    // Recipients and contacts

    @Test fun textFromContactsOpensTheExistingConversation() {
        threads.members += 8L to listOf("+33199001234")
        contacts.photos["0199001234"] = "content://photo/1"
        conversation.compose("01 99 00 12 34", "Batman")
        scope.advanceUntilIdle()
        assertEquals(8L, state.threadId)
        assertEquals("Batman", state.title)
        assertEquals(listOf("a", "b"), state.messages.map { it.text })
        assertEquals("content://photo/1", state.photo)
    }

    @Test fun textFromContactsWithoutAConversationIsANewMessageToIt() {
        threads.members += 8L to listOf("+33199009999")
        conversation.compose("0199001234", "Batman")
        scope.advanceUntilIdle()
        assertEquals(null, state.threadId)
        assertEquals(listOf(Recipient("0199001234", "Batman")), state.recipients)
        assertEquals(COMPOSER, state.focus)
        type("Yo")
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals(listOf<Any>("create 0199001234", "send 30 Yo"), api.calls)
        assertEquals("Batman", state.title)
    }

    @Test fun aNewMessageReadsTheAddressBookForTo() {
        contacts.people += Contact("Batman", listOf(PhoneNumber("Mobile", "0199001234")))
        conversation.open(null, "")
        scope.advanceUntilIdle()
        type("bat")
        assertEquals(listOf("Batman"), state.suggestions.map { it.name })
    }

    @Test fun severalRecipientsMakeAGroup() {
        conversation.open(null, "")
        conversation.event(ThreadEvent.Chosen(Recipient("0199001234", "Batman", "Mobile")))
        conversation.event(ThreadEvent.Chosen(Recipient("0199001111")))
        type("Yo")
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals(listOf<Any>("group 0199001234,0199001111", "send 31 Yo"), api.calls)
        assertEquals(31L, state.threadId)
        assertEquals("Batman (Mobile), 0199001111", state.title)
    }

    @Test fun aNameNotChosenIsSaidNotSent() {
        conversation.open(null, "")
        conversation.event(ThreadEvent.Chosen(Recipient("0199001234")))
        conversation.event(ThreadEvent.Step(down = false))
        type("rob")
        conversation.event(ThreadEvent.Step(down = true))
        type("Yo")
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals(emptyList(), api.calls)
        assertEquals(Notice.NO_RECIPIENT, state.notice)
        assertEquals("Yo", state.draft)
    }

    @Test fun chooseContactAddsItsNumber() {
        contacts.rows["content://com.android.contacts/data/4"] = Recipient("0199001234", "Batman", "Mobile")
        conversation.open(null, "")
        conversation.choose("content://com.android.contacts/data/4")
        scope.advanceUntilIdle()
        assertEquals(listOf("Batman (Mobile)"), state.recipients.map { it.shown })
        conversation.choose("content://com.android.contacts/data/5")
        scope.advanceUntilIdle()
        assertEquals(Notice.NOT_CHOSEN, state.notice)
    }

    @Test fun aGroupThreadKeepsTheDefaultPicture() {
        threads.members += 8L to listOf("0199001234", "0199001111")
        contacts.photos["0199001234"] = "content://photo/1"
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        assertEquals(null, state.photo)
    }

    @Test fun oneRecipientWithAConversationShowsItsHistoryThenSendsThere() {
        threads.members += 8L to listOf("+33199001234")
        conversation.open(null, "")
        scope.advanceUntilIdle()
        conversation.event(ThreadEvent.Step(down = true))
        type("Yo")
        conversation.event(ThreadEvent.Chosen(Recipient("01 99 00 12 34", "Batman", "Mobile")))
        scope.advanceUntilIdle()
        assertEquals(null, state.threadId)
        assertEquals(listOf("a", "b"), state.messages.map { it.text })
        assertEquals("Yo", state.draft)
        api.calls.clear()
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals(listOf<Any>("send 8 Yo"), api.calls.filterIsInstance<String>())
        assertEquals(8L, state.threadId)
    }

    @Test fun aSecondRecipientLeavesTheHistoryRemovingItComesBack() {
        threads.members += 8L to listOf("+33199001234")
        conversation.open(null, "")
        conversation.event(ThreadEvent.Chosen(Recipient("0199001234")))
        scope.advanceUntilIdle()
        conversation.event(ThreadEvent.Chosen(Recipient("0699")))
        scope.advanceUntilIdle()
        assertEquals(emptyList(), state.messages)
        conversation.event(ThreadEvent.ToTouched(1))
        conversation.event(ThreadEvent.Erased)
        scope.advanceUntilIdle()
        assertEquals(listOf("a", "b"), state.messages.map { it.text })
        conversation.event(ThreadEvent.Chosen(Recipient("0699")))
        type("Yo")
        api.calls.clear()
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals(listOf<Any>("group 0199001234,0699", "send 31 Yo"), api.calls)
    }

    // Attachments

    @Test fun anAttachedFileGoesWithTheTextThenItsCopyGoes() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        conversation.attach("content://picker/9")
        scope.advanceUntilIdle()
        assertEquals(listOf("content://picker/9"), store.taken)
        val a = state.attachment!!
        type("Vu")
        conversation.send()
        scope.advanceUntilIdle()
        assertTrue("send 8 Vu +a.jpg" in api.calls)
        assertEquals(null, state.attachment)
        assertEquals(listOf(a), store.dropped)
    }

    @Test fun anAttachmentAloneIsSent() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        conversation.attach("content://picker/9")
        scope.advanceUntilIdle()
        conversation.send()
        scope.advanceUntilIdle()
        assertTrue("send 8  +a.jpg" in api.calls)
    }

    @Test fun aRefusedSendKeepsTheTextAndTheAttachment() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        conversation.attach("content://picker/9")
        scope.advanceUntilIdle()
        type("Vu")
        api.failing = true
        conversation.send()
        scope.advanceUntilIdle()
        assertEquals("Vu", state.draft)
        assertEquals("a.jpg", state.attachment?.name)
        assertEquals(Notice.NOT_SENT, state.notice)
        assertEquals(emptyList(), store.dropped)
    }

    @Test fun aFileRefusedSaysWhyAndLeavesTheFieldAsItWas() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        type("Vu")
        store.answer = Taken.Refused(Notice.TOO_LARGE)
        conversation.attach("content://picker/9")
        scope.advanceUntilIdle()
        assertEquals(null, state.attachment)
        assertEquals("Vu", state.draft)
        assertEquals(Notice.TOO_LARGE, state.notice)
    }

    @Test fun anotherFileTakesThePlaceAndTheFirstCopyGoes() {
        conversation.open(8, "513")
        scope.advanceUntilIdle()
        conversation.attach("content://picker/9")
        scope.advanceUntilIdle()
        val first = state.attachment!!
        store.answer = Taken.Ok(first.copy(uri = "content://dev.bold7.messages.attachments/u2/b.jpg", name = "b.jpg"))
        conversation.attach("content://picker/10")
        scope.advanceUntilIdle()
        assertEquals("b.jpg", state.attachment?.name)
        assertEquals(listOf(first), store.dropped)
        conversation.removeAttachment()
        scope.advanceUntilIdle()
        assertEquals(null, state.attachment)
        assertEquals(2, store.dropped.size)
    }
}
