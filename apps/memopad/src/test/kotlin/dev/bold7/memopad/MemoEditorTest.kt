package dev.bold7.memopad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoEditorTest {
    @Test fun aNewMemoStartsEmptyAndUnchanged() {
        val e = MemoEditor.of(null)
        assertTrue(e.blank)
        assertFalse(e.changed)
    }

    @Test fun anOpenMemoIsUnchangedUntilEdited() {
        val e = MemoEditor.of(Memo(1, "T", "body", 5))
        assertEquals("T", e.title)
        assertEquals("body", e.body)
        assertFalse(e.changed)
        assertTrue(e.withBody("body 2").changed)
        assertTrue(e.withTitle("U").changed)
    }

    @Test fun typingBackTheOriginalIsNoChange() {
        val e = MemoEditor.of(Memo(1, "T", "b", 5)).withTitle("X").withTitle("T")
        assertFalse(e.changed)
    }

    @Test fun aNewMemoWithTextIsChangedAndNotBlank() {
        val e = MemoEditor.of(null).withBody("x")
        assertTrue(e.changed)
        assertFalse(e.blank)
    }

    @Test fun aNewMemoWithOnlySpacesIsBlank() = assertTrue(MemoEditor.of(null).withTitle("  ").withBody("\n").blank)

    @Test fun savingKeepsTheTextAsTyped() {
        val (id, title, body) = MemoEditor.of(Memo(7, "T", "b", 5)).withBody("b\nc").toSave()
        assertEquals(7L, id)
        assertEquals("T", title)
        assertEquals("b\nc", body)
    }

    @Test fun aNewMemoSavesWithoutAnId() = assertEquals(null, MemoEditor.of(null).withTitle("A").toSave().first)

    @Test fun findIgnoresCaseFromThePlaceAndWrapsOnce() {
        val text = "Milk, bread, MILK again"
        assertEquals(0, findNext(text, "milk", 0))
        assertEquals(13, findNext(text, "milk", 1))
        assertEquals(0, findNext(text, "milk", 14))
        assertNull(findNext(text, "eggs", 0))
        assertNull(findNext(text, "", 0))
    }
}
