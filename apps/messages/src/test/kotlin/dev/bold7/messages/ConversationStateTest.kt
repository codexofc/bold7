package dev.bold7.messages

import dev.bold7.ui.TextAct
import dev.bold7.ui.textActs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationStateTest {
    private fun msg(id: Long, out: Boolean = false) = ThreadMessage("content://sms/$id", id, out, "t$id", Delivery.RECEIVED)
    private val thread = ConversationState(threadId = 8, title = "513").reduce(ThreadEvent.Loaded(listOf(msg(1), msg(2, out = true), msg(3))))

    @Test fun aBubbleNamesItsSender() {
        fun from(id: Long, number: String) = msg(id).copy(from = number)
        // One person: the band's name on every received bubble, "Me" on the sent ones.
        assertEquals("513", thread.senderLabel(thread.messages[0], "Me"))
        assertEquals("Me", thread.senderLabel(thread.messages[1], "Me"))
        // A group (two senders): each received bubble its sender's contact name, the number when
        // not a contact (gsm 023 names the contact, C).
        val group = ConversationState(threadId = 9, title = "Alice, 0611")
            .reduce(ThreadEvent.Loaded(listOf(from(1, "+33 6 12 34 56 78"), from(2, "0699999999"))))
            .reduce(ThreadEvent.Names(mapOf("0612345678" to "Alice")))
        assertEquals("Alice", group.senderLabel(group.messages[0], "Me"))
        assertEquals("0699999999", group.senderLabel(group.messages[1], "Me"))
        // A new message's history: its one recipient's name.
        val fresh = ConversationState(recipients = listOf(Recipient("555", "Bob"))).reduce(ThreadEvent.Loaded(listOf(msg(4))))
        assertEquals("Bob", fresh.senderLabel(fresh.messages[0], "Me"))
    }

        @Test fun theFieldHasTheFocusAtFirst() {
        assertEquals(COMPOSER, thread.focus)
        assertTrue(thread.loaded)
        assertEquals(TO, ConversationState().focus)
    }

    @Test fun upFromTheFieldGoesThroughTheBubblesAndStopsAtTheFirst() {
        var s = thread
        s = s.reduce(ThreadEvent.Step(down = false))
        assertEquals(2, s.focus)
        s = s.reduce(ThreadEvent.Step(down = false)).reduce(ThreadEvent.Step(down = false)).reduce(ThreadEvent.Step(down = false))
        assertEquals(0, s.focus)
        s = s.reduce(ThreadEvent.Step(down = true)).reduce(ThreadEvent.Step(down = true)).reduce(ThreadEvent.Step(down = true)).reduce(ThreadEvent.Step(down = true))
        assertEquals(COMPOSER, s.focus)
    }

    @Test fun aNewMessageGoesFromToToTheField() {
        var s = ConversationState()
        s = s.reduce(ThreadEvent.Step(down = false))
        assertEquals(TO, s.focus)
        s = s.reduce(ThreadEvent.Step(down = true))
        assertEquals(COMPOSER, s.focus)
        s = s.reduce(ThreadEvent.Step(down = false))
        assertEquals(TO, s.focus)
    }

    @Test fun typingGoesToTheFocusedFieldOrToTheMessage() {
        var s = ConversationState().reduce(ThreadEvent.Typed('0')).reduce(ThreadEvent.Typed('6'))
        assertEquals("06", s.to)
        s = s.reduce(ThreadEvent.Erased)
        assertEquals("0", s.to)
        // On a bubble, a letter types in the field, which takes the focus (D).
        s = thread.reduce(ThreadEvent.Step(down = false)).reduce(ThreadEvent.Typed('H')).reduce(ThreadEvent.Typed('i'))
        assertEquals("Hi", s.draft)
        assertEquals(COMPOSER, s.focus)
        assertEquals("H", s.reduce(ThreadEvent.Erased).draft)
        assertEquals("", s.reduce(ThreadEvent.FieldCleared).draft)
    }

    @Test fun sendableOnlyWithTextARecipientAndNothingLeaving() {
        assertFalse(thread.sendable)
        val typed = thread.reduce(ThreadEvent.Typed('a'))
        assertTrue(typed.sendable)
        assertFalse(typed.reduce(ThreadEvent.Sending).sendable)
        assertFalse(thread.reduce(ThreadEvent.Typed(' ')).sendable)
        assertFalse(ConversationState().reduce(ThreadEvent.Step(down = true)).reduce(ThreadEvent.Typed('a')).sendable)
        val newOne = ConversationState().reduce(ThreadEvent.Typed('5')).reduce(ThreadEvent.Step(down = true)).reduce(ThreadEvent.Typed('a'))
        assertTrue(newOne.sendable)
    }

    @Test fun sentClearsTheFieldFailedKeepsItAndSaysSo() {
        val leaving = thread.reduce(ThreadEvent.Typed('a')).reduce(ThreadEvent.Sending)
        val sent = leaving.reduce(ThreadEvent.Sent("a"))
        assertEquals("", sent.draft)
        assertFalse(sent.sending)
        // Typed while it left: kept.
        assertEquals("b", leaving.reduce(ThreadEvent.Typed('b')).reduce(ThreadEvent.Sent("a")).draft)
        val failed = leaving.reduce(ThreadEvent.Failed(Notice.NOT_SENT))
        assertEquals("a", failed.draft)
        assertFalse(failed.sending)
        assertEquals(Notice.NOT_SENT, failed.notice)
        assertEquals(null, failed.reduce(ThreadEvent.Noticed).notice)
    }

    @Test fun aCreatedConversationBecomesAThread() {
        val s = ConversationState().reduce(ThreadEvent.Typed('5')).reduce(ThreadEvent.Created(12))
        assertEquals(12L, s.threadId)
        assertEquals("5", s.title)
        assertEquals(COMPOSER, s.focus)
    }

    @Test fun theFocusedBubbleStaysWhenTheThreadIsReadAgain() {
        val onFirst = thread.reduce(ThreadEvent.Moved(0))
        val more = onFirst.reduce(ThreadEvent.Loaded(listOf(msg(0), msg(1), msg(2, out = true), msg(3))))
        assertEquals(1, more.focus)
        // Its message deleted: the field.
        assertEquals(COMPOSER, onFirst.reduce(ThreadEvent.Loaded(listOf(msg(3)))).focus)
    }

    @Test fun theMenusOnTheField() {
        assertEquals(listOf(ThreadAct.CLEAR_CONVERSATION, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH), threadMenu(thread).map { it.action })
        val typed = thread.reduce(ThreadEvent.Typed('a'))
        assertEquals(listOf(ThreadAct.SEND, ThreadAct.CLEAR_FIELD, ThreadAct.CLEAR_CONVERSATION, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH), threadMenu(typed).map { it.action })
        assertEquals(0, defaultEntry(threadMenu(typed)))
    }

    @Test fun theThreadsFullMenuHasSwitchApplicationAfterSendElseFirst() {
        val typed = fullThreadMenu(thread.reduce(ThreadEvent.Typed('a')))
        assertEquals(listOf(ThreadAct.SEND, ThreadAct.SWITCH, ThreadAct.CLEAR_FIELD, ThreadAct.CLEAR_CONVERSATION, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH, ThreadAct.CLOSE), typed.entries.map { it.action })
        assertEquals(ThreadAct.SEND, typed.entries[typed.highlighted!!].action)
        val onBubble = fullThreadMenu(thread.reduce(ThreadEvent.Step(down = false)))
        assertEquals(listOf(ThreadAct.SWITCH, ThreadAct.DELETE_MESSAGE, ThreadAct.CLEAR_CONVERSATION, ThreadAct.CLOSE), onBubble.entries.map { it.action })
        assertEquals(ThreadAct.DELETE_MESSAGE, onBubble.entries[onBubble.highlighted!!].action)
        // Nothing else to offer: the menu still opens, on Switch Application.
        val none = fullThreadMenu(ConversationState(threadId = 1).reduce(ThreadEvent.Typed('a')).reduce(ThreadEvent.Edited("a", "")))
        assertEquals(ThreadAct.SWITCH, none.entries.first().action)
        assertEquals(ThreadAct.CLOSE, none.entries.last().action)
    }

    @Test fun theFullMenuHasTheSelectionEntriesOnTheFieldBetweenClearConversationAndAddSmiley() {
        val typed = thread.reduce(ThreadEvent.Typed('a'))
        val start = listOf(TextAct.START_SELECTION)
        assertEquals(
            listOf(ThreadAct.SEND, ThreadAct.SWITCH, ThreadAct.CLEAR_FIELD, ThreadAct.CLEAR_CONVERSATION, ThreadAct.START_SELECTION, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH, ThreadAct.CLOSE),
            fullThreadMenu(typed, start).entries.map { it.action },
        )
        // A selection: Cut and Copy; the clipboard with something: Paste after them.
        assertEquals(
            listOf(ThreadAct.CLEAR_CONVERSATION, ThreadAct.CUT, ThreadAct.COPY, ThreadAct.PASTE, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH),
            threadMenu(thread, textActs(selected = true, canPaste = true)).map { it.action },
        )
        assertEquals(
            listOf(ThreadAct.CLEAR_CONVERSATION, ThreadAct.START_SELECTION, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH),
            threadMenu(thread, textActs(selected = false, canPaste = false)).map { it.action },
        )
        // Not on a bubble (the field is not where the trackpad is), and the short menu has none.
        assertEquals(
            listOf(ThreadAct.DELETE_MESSAGE, ThreadAct.CLEAR_CONVERSATION),
            threadMenu(thread.reduce(ThreadEvent.Step(down = false)), start).map { it.action },
        )
        assertEquals(threadMenu(typed), threadMenu(typed, emptyList()))
    }

    @Test fun theMenusOnABubble() {
        val onBubble = thread.reduce(ThreadEvent.Step(down = false))
        assertEquals(listOf(ThreadAct.DELETE_MESSAGE, ThreadAct.CLEAR_CONVERSATION), threadMenu(onBubble).map { it.action })
        assertEquals(0, defaultEntry(threadMenu(onBubble)))
        // A new message has no conversation to clear; on To, Choose Contact.
        // Add Smiley and Attach too: the field is under the To band (sim-51).
        assertEquals(listOf(ThreadAct.CHOOSE_CONTACT, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH), threadMenu(ConversationState()).map { it.action })
        assertEquals(
            listOf(ThreadAct.SWITCH, ThreadAct.CHOOSE_CONTACT, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH, ThreadAct.CLOSE),
            fullThreadMenu(ConversationState()).entries.map { it.action },
        )
    }

    // To

    private val batman = Contact("Batman", listOf(PhoneNumber("Mobile", "0199001234")))
    private val robin = Contact("Robin", listOf(PhoneNumber("Mobile", "0199001111"), PhoneNumber("Work", "0199002222")))
    private fun ConversationState.type(text: String) = text.fold(this) { s, c -> s.reduce(ThreadEvent.Typed(c)) }
    private val composing = ConversationState().reduce(ThreadEvent.ContactsLoaded(listOf(batman, robin)))

    @Test fun aNameTypedInToProposesContactsAndDownGoesThroughThem() {
        var s = composing.type("r")
        assertEquals(listOf(robin), s.suggestions)
        s = composing.type("b")
        assertEquals(listOf(batman), s.suggestions)
        assertEquals(-1, s.pick)
        s = s.reduce(ThreadEvent.Step(down = true))
        assertEquals(TO, s.focus)
        assertEquals(0, s.pick)
        // Past the last proposal, the field; up comes back to To's typing line.
        s = s.reduce(ThreadEvent.Step(down = true))
        assertEquals(COMPOSER, s.focus)
        s = s.reduce(ThreadEvent.Step(down = false))
        assertEquals(TO, s.focus)
        assertEquals(-1, s.pick)
        assertEquals(s.recipients.size, s.line)
        // Typing again leaves the proposal.
        assertEquals(-1, s.reduce(ThreadEvent.Step(down = true)).reduce(ThreadEvent.Typed('a')).pick)
    }

    @Test fun aChosenRecipientTakesALineAndTheFieldTheFocus() {
        val r = Recipient("0199001234", "Batman", "Mobile")
        var s = composing.type("bat").reduce(ThreadEvent.Chosen(r))
        assertEquals(listOf(r), s.recipients)
        assertEquals("", s.to)
        assertEquals(COMPOSER, s.focus)
        // Up: the empty line under the recipients (gsm 025), then the recipient's.
        s = s.reduce(ThreadEvent.Step(down = false))
        assertEquals(1, s.line)
        s = s.reduce(ThreadEvent.Step(down = false))
        assertEquals(0, s.line)
        assertEquals(0, s.reduce(ThreadEvent.Step(down = false)).line)
        // The same number again, however written, is not added twice.
        assertEquals(1, s.reduce(ThreadEvent.Chosen(Recipient("+33 1 99 00 12 34"))).recipients.size)
        // Delete on its line takes it out; a letter goes to the typing line.
        assertEquals(emptyList(), s.reduce(ThreadEvent.Erased).recipients)
        val typed = s.reduce(ThreadEvent.Typed('x'))
        assertEquals("x", typed.to)
        assertEquals(1, typed.line)
        assertEquals(1, typed.recipients.size)
    }

    @Test fun theDestinationsAreTheRecipientsAndANumberTyped() {
        val s = composing.reduce(ThreadEvent.Chosen(Recipient("0199001234", "Batman", "Mobile"))).reduce(ThreadEvent.Step(down = false)).type("0699")
        assertEquals(listOf("0199001234", "0699"), s.destinations)
        assertTrue(s.reduce(ThreadEvent.Step(down = true)).type("Yo").sendable)
        // A name typed and not chosen: nothing leaves, a part of the group would be missing.
        val named = composing.reduce(ThreadEvent.Chosen(Recipient("0199001234"))).reduce(ThreadEvent.Step(down = false)).type("rob")
        assertEquals(listOf("0199001234"), named.destinations)
        assertFalse(named.reduce(ThreadEvent.Step(down = true)).reduce(ThreadEvent.Step(down = true)).type("Yo").sendable)
        assertFalse(composing.type("Yo").sendable)
    }

    @Test fun aGroupIsNamedByItsMembers() {
        val s = composing.reduce(ThreadEvent.Chosen(Recipient("0199001234", "Batman", "Mobile"))).reduce(ThreadEvent.Chosen(Recipient("0699")))
            .reduce(ThreadEvent.Created(40))
        assertEquals("Batman (Mobile), 0699", s.title)
    }

    @Test fun chooseContactIsOnTo() {
        assertEquals(listOf(ThreadAct.CHOOSE_CONTACT, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH), threadMenu(composing).map { it.action })
        val inField = composing.reduce(ThreadEvent.Step(down = true)).type("a")
        assertEquals(listOf(ThreadAct.SEND, ThreadAct.CLEAR_FIELD, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH), threadMenu(inField).map { it.action })
    }

    @Test fun theClickOnToTakesTheNumberTheProposalOrTheFirst() {
        assertEquals(ToChoice.One(Recipient("0699")), composing.type("0699").toClick())
        assertEquals(ToChoice.One(Recipient("0199001234", "Batman", "Mobile")), composing.type("b").toClick())
        val onRobin = composing.type("r").reduce(ThreadEvent.Step(down = true))
        assertEquals(ToChoice.Numbers(robin), onRobin.toClick())
        assertEquals(null, composing.toClick())
        assertEquals(null, composing.type("zorro").toClick())
    }

    // One recipient's history before sending

    private fun msg8(id: Long) = ThreadMessage("content://sms/$id", id, false, "h$id", Delivery.RECEIVED)

    @Test fun oneChosenRecipientIsLookedUpAndASecondOneIsNot() {
        val one = composing.reduce(ThreadEvent.Chosen(Recipient("0199001234", "Batman", "Mobile")))
        assertEquals("0199001234", lookUp(composing, one))
        // A number typed and not taken does not count; the same number again is not looked up again.
        assertEquals(null, lookUp(one, one.reduce(ThreadEvent.Step(down = false)).type("0699")))
        assertEquals(null, lookUp(one, one.reduce(ThreadEvent.Chosen(Recipient("+33 1 99 00 12 34")))))
        val two = one.reduce(ThreadEvent.Chosen(Recipient("0699")))
        assertEquals(null, lookUp(one, two))
        // The second one taken out: the first is looked up again.
        val back = two.reduce(ThreadEvent.Step(down = false)).reduce(ThreadEvent.Step(down = false)).reduce(ThreadEvent.Erased)
        assertEquals(listOf("0199001234"), back.recipients.map { it.number })
        assertEquals("0199001234", lookUp(two, back))
        // A thread already open never is.
        assertEquals(null, lookUp(thread, thread))
    }

    @Test fun theFoundConversationShowsItsHistoryUntilASecondRecipient() {
        val one = composing.reduce(ThreadEvent.Chosen(Recipient("0199001234"))).type("Yo")
        val found = one.reduce(ThreadEvent.Found("0199001234", 8)).reduce(ThreadEvent.Loaded(listOf(msg8(1), msg8(2))))
        assertEquals(8L, found.found)
        assertEquals(null, found.threadId)
        assertEquals(listOf("h1", "h2"), found.messages.map { it.text })
        assertEquals("Yo", found.draft)
        // An answer for a number no longer alone in To is dropped.
        assertEquals(null, one.reduce(ThreadEvent.Chosen(Recipient("0699"))).reduce(ThreadEvent.Found("0199001234", 8)).found)
        val two = found.reduce(ThreadEvent.Chosen(Recipient("0699")))
        assertEquals(null, two.found)
        assertEquals(emptyList(), two.messages)
        assertEquals("Yo", two.draft)
        // Not found: nothing shown.
        assertEquals(null, one.reduce(ThreadEvent.Found("0199001234", null)).found)
    }

    // Attachments

    private val picture = Attachment("content://dev.bold7.messages.attachments/u1/a.jpg", "image/jpeg", "a.jpg", 1000)

    @Test fun anAttachmentAloneCanBeSent() {
        val s = thread.reduce(ThreadEvent.Attached(picture))
        assertEquals(picture, s.attachment)
        assertEquals(COMPOSER, s.focus)
        assertTrue(s.sendable)
        assertFalse(s.reduce(ThreadEvent.Sending).sendable)
        assertEquals(null, s.reduce(ThreadEvent.AttachmentRemoved).attachment)
    }

    @Test fun sentTakesTheAttachmentThatLeftAFailureKeepsIt() {
        val s = thread.reduce(ThreadEvent.Attached(picture)).reduce(ThreadEvent.Typed('a')).reduce(ThreadEvent.Sending)
        assertEquals(picture, s.reduce(ThreadEvent.Failed(Notice.NOT_SENT)).attachment)
        assertEquals("a", s.reduce(ThreadEvent.Failed(Notice.NOT_SENT)).draft)
        val sent = s.reduce(ThreadEvent.Sent("a", picture))
        assertEquals(null, sent.attachment)
        assertEquals("", sent.draft)
        // Another one attached meanwhile stays.
        val other = picture.copy(uri = "content://dev.bold7.messages.attachments/u2/b.jpg")
        assertEquals(other, s.reduce(ThreadEvent.Attached(other)).reduce(ThreadEvent.Sent("a", picture)).attachment)
    }

    @Test fun attachOrRemoveOnTheFieldOpenOnABubble() {
        val attached = thread.reduce(ThreadEvent.Attached(picture))
        val acts = threadMenu(attached).map { it.action }
        assertTrue(ThreadAct.REMOVE_ATTACHMENT in acts)
        assertFalse(ThreadAct.ATTACH in acts)
        assertEquals(ThreadAct.SEND, threadMenu(attached)[defaultEntry(threadMenu(attached))].action)
        val withPicture = ConversationState(threadId = 8).reduce(ThreadEvent.Loaded(listOf(msg(1).copy(images = listOf("content://mms/part/4")))))
            .reduce(ThreadEvent.Step(down = false))
        assertEquals(listOf(ThreadAct.OPEN_ATTACHMENT, ThreadAct.DELETE_MESSAGE, ThreadAct.CLEAR_CONVERSATION), threadMenu(withPicture).map { it.action })
        assertEquals(listOf(ThreadAct.ATTACH_PICTURE, ThreadAct.ATTACH_VIDEO, ThreadAct.ATTACH_AUDIO, ThreadAct.ATTACH_FILE), attachMenu().map { it.action })
        assertTrue(threadMenu(thread).first { it.action == ThreadAct.ATTACH }.submenu)
    }
}
