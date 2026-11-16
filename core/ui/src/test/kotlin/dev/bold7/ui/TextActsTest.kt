package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TextActsTest {
    @Test fun startSelectionWithoutASelectionCutAndCopyWithOne() {
        assertEquals(listOf(TextAct.START_SELECTION), textActs(selected = false, canPaste = false))
        assertEquals(listOf(TextAct.CUT, TextAct.COPY), textActs(selected = true, canPaste = false))
    }

    @Test fun pasteOnlyWhenTheClipboardHasSomething() {
        assertEquals(listOf(TextAct.START_SELECTION, TextAct.PASTE), textActs(selected = false, canPaste = true))
        assertEquals(listOf(TextAct.CUT, TextAct.COPY, TextAct.PASTE), textActs(selected = true, canPaste = true))
    }

    private enum class Act { FIND, START_SELECTION, CUT, COPY, PASTE, CLOSE }

    @Test fun anAppsEnumTakesTheEntriesByName() {
        assertEquals(listOf(Act.CUT, Act.COPY, Act.PASTE), textActs(true, true).asEntries(Act.entries.toTypedArray()).map { it.action })
        assertEquals(listOf(Act.START_SELECTION), textActs(false, false).asEntries(Act.entries.toTypedArray()).map { it.action })
        assertEquals(TextAct.CUT, Act.CUT.textAct())
        assertEquals(null, Act.FIND.textAct())
    }
}
