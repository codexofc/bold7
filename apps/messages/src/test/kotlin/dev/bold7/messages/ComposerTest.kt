package dev.bold7.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerTest {
    private fun msg(id: Long) = ThreadMessage("content://sms/$id", id, false, "t$id", Delivery.RECEIVED)
    private val thread = ConversationState(threadId = 8, title = "513").reduce(ThreadEvent.Loaded(listOf(msg(1), msg(2))))

    @Test fun theEditorsTextIsTheDraftAndTypingOnABubbleBringsTheFocusBack() {
        val s = thread.reduce(ThreadEvent.Step(down = false)).reduce(ThreadEvent.Edited("", "J'ai"))
        assertEquals("J'ai", s.draft)
        assertEquals(COMPOSER, s.focus)
        // The same text again (the state written back into the editor): nothing moves.
        val onBubble = s.reduce(ThreadEvent.Step(down = false))
        assertEquals(onBubble, onBubble.reduce(ThreadEvent.Edited("J'ai", "J'ai")))
    }

    @Test fun whatIsTypedWhileASendEndsIsKeptAfterIt() {
        // "ab" left while the field went on to "abc": "c" stays, "ab" is not left there to go twice.
        val sent = thread.reduce(ThreadEvent.Edited("", "ab")).reduce(ThreadEvent.Sending).reduce(ThreadEvent.Sent("ab"))
        assertEquals("", sent.draft)
        assertEquals("c", sent.reduce(ThreadEvent.Edited("ab", "abc")).draft)
        // An edit inside the old text: the draft stands, the editor is given it again.
        assertEquals("", sent.reduce(ThreadEvent.Edited("ab", "b")).draft)
    }

    @Test fun rightAtTheFieldsEndFocusesTheSmileyButtonAndLeftComesBack() {
        val smiley = thread.reduce(ThreadEvent.Side(right = true))
        assertEquals(SMILEY, smiley.focus)
        assertEquals(SMILEY, smiley.reduce(ThreadEvent.Side(right = true)).focus)
        assertEquals(SMILEY, smiley.reduce(ThreadEvent.Step(down = true)).focus)
        assertEquals(COMPOSER, smiley.reduce(ThreadEvent.Side(right = false)).focus)
        assertEquals(1, smiley.reduce(ThreadEvent.Step(down = false)).focus)
        // On a bubble or on To, the sides do nothing.
        val onBubble = thread.reduce(ThreadEvent.Step(down = false))
        assertEquals(onBubble, onBubble.reduce(ThreadEvent.Side(right = true)))
        assertEquals(TO, ConversationState().reduce(ThreadEvent.Side(right = true)).focus)
        // A touch on the field brings the focus there, from anywhere.
        assertEquals(COMPOSER, onBubble.reduce(ThreadEvent.FieldTouched).focus)
        assertEquals(COMPOSER, ConversationState().reduce(ThreadEvent.FieldTouched).focus)
    }

    @Test fun addSmileyOnTheFieldAndTheButtonOnly() {
        assertEquals(listOf(ThreadAct.CLEAR_CONVERSATION, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH), threadMenu(thread).map { it.action })
        val typed = threadMenu(thread.reduce(ThreadEvent.Edited("", "a"))).map { it.action }
        assertEquals(listOf(ThreadAct.SEND, ThreadAct.CLEAR_FIELD, ThreadAct.CLEAR_CONVERSATION, ThreadAct.ADD_SMILEY, ThreadAct.ATTACH), typed)
        assertTrue(ThreadAct.ADD_SMILEY in threadMenu(thread.reduce(ThreadEvent.Side(right = true))).map { it.action })
        assertFalse(ThreadAct.ADD_SMILEY in threadMenu(thread.reduce(ThreadEvent.Step(down = false))).map { it.action })
        // A new message's To band has the field under it (sim-51).
        assertTrue(ThreadAct.ADD_SMILEY in threadMenu(ConversationState()).map { it.action })
    }

    @Test fun theEditorTakesTheKeysThatTypeTheThreadTakesTheOthers() {
        // In the field: text, Delete and the caret's moves are the editor's (and so the input method's).
        assertTrue(editorTakes(FieldKey.TEXT, COMPOSER, caretAtEnd = true))
        assertTrue(editorTakes(FieldKey.DELETE, COMPOSER, caretAtEnd = true))
        assertTrue(editorTakes(FieldKey.LEFT, COMPOSER, caretAtEnd = true))
        assertTrue(editorTakes(FieldKey.RIGHT, COMPOSER, caretAtEnd = false))
        // Right at the end goes to the smiley button; up, down, the click, Escape, Menu are OS 7's.
        assertFalse(editorTakes(FieldKey.RIGHT, COMPOSER, caretAtEnd = true))
        for (k in listOf(FieldKey.UP, FieldKey.DOWN, FieldKey.CLICK, FieldKey.BACK, FieldKey.MENU)) {
            assertFalse(editorTakes(k, COMPOSER, caretAtEnd = false), "$k")
        }
        // On a bubble or the button, a letter or Delete still types in the field; the sides do not move its caret.
        assertTrue(editorTakes(FieldKey.TEXT, 0, caretAtEnd = true))
        assertTrue(editorTakes(FieldKey.DELETE, SMILEY, caretAtEnd = true))
        assertFalse(editorTakes(FieldKey.LEFT, 0, caretAtEnd = true))
        assertFalse(editorTakes(FieldKey.LEFT, SMILEY, caretAtEnd = true))
        // To is not an editor: the thread types into it.
        assertFalse(editorTakes(FieldKey.TEXT, TO, caretAtEnd = true))
        assertFalse(editorTakes(FieldKey.DELETE, TO, caretAtEnd = true))
    }

    @Test fun shiftAndTheTrackpadUpOrDownSelectInTheFieldOnly() {
        for (k in listOf(FieldKey.UP, FieldKey.DOWN)) {
            assertTrue(shiftSelects(k, COMPOSER, shift = true), "$k")
            // Without Shift, or off the field (a bubble, the button, To), the trackpad keeps its steps.
            assertFalse(shiftSelects(k, COMPOSER, shift = false), "$k")
            assertFalse(shiftSelects(k, 0, shift = true), "$k")
            assertFalse(shiftSelects(k, SMILEY, shift = true), "$k")
            assertFalse(shiftSelects(k, TO, shift = true), "$k")
        }
        // The sides are Typing's and the editor's; the click and the rest stay OS 7's.
        for (k in listOf(FieldKey.LEFT, FieldKey.RIGHT, FieldKey.CLICK, FieldKey.TEXT)) assertFalse(shiftSelects(k, COMPOSER, shift = true), "$k")
    }
}
