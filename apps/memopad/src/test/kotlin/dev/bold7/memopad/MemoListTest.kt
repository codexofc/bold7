package dev.bold7.memopad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoListTest {
    private val groceries = Memo(1, "Groceries", "milk\neggs", 30)
    private val plan = Memo(2, "Plan", "Call the garage\nthen the bank", 20)
    private val untitled = Memo(3, "", "First line\nSecond line", 10)
    // Alphabetical, case ignored (sim-259): First line (the body's first line), Groceries, Plan.
    private val all = listOf(groceries, plan, untitled)
    private val sorted = listOf(untitled, groceries, plan)
    private fun loaded() = MemoList().reduce(ListEvent.Loaded(all))

    @Test fun newMemoComesFirstAndTheMemosAreAlphabeticalNotByDate() {
        val s = loaded()
        assertEquals(listOf(NewMemo, Entry(untitled), Entry(groceries), Entry(plan)), s.rows)
        assertEquals(Entry(untitled), s.focused)
    }

    @Test fun theOrderIsTheCapturesWhateverTheCreationOrder() {
        // sim-259: Moon, Zebra, Apple created in this order, listed Apple, Moon, Zebra.
        val moon = Memo(1, "Moon", "", 1)
        val zebra = Memo(2, "Zebra", "", 2)
        val apple = Memo(3, "Apple", "", 3)
        assertEquals(listOf(apple, moon, zebra), ordered(listOf(moon, zebra, apple)))
        assertEquals(listOf(Memo(5, "apple", "", 0), Memo(6, "Banana", "", 0)), ordered(listOf(Memo(6, "Banana", "", 0), Memo(5, "apple", "", 0))))
    }

    @Test fun theTitleIsTheFirstLineWhenThereIsNone() {
        assertEquals("Groceries", groceries.shownTitle)
        assertEquals("First line", untitled.shownTitle)
        assertEquals("", Memo(4, " ", "\n  ", 0).shownTitle)
    }

    @Test fun typingFiltersByTheWordsOfTheTitleAndTheBody() {
        val s = loaded().reduce(ListEvent.Typed('g'))
        // Groceries by its title, Plan by "garage" in its body.
        assertEquals(listOf(groceries, plan), s.shown)
        assertEquals(Entry(groceries), s.focused)
        assertEquals(listOf(plan), s.reduce(ListEvent.Typed('a')).shown)
    }

    @Test fun noMatchLeavesOnlyNewMemo() {
        val s = loaded().reduce(ListEvent.Typed('z'))
        assertTrue(s.empty)
        assertEquals(listOf<Row>(NewMemo), s.rows)
        assertEquals(NewMemo, s.focused)
    }

    @Test fun erasingTypedLettersWidensTheListAgain() {
        val s = loaded().reduce(ListEvent.Typed('z')).reduce(ListEvent.Erased)
        assertEquals(sorted, s.shown)
        assertEquals(s, s.reduce(ListEvent.Erased))
    }

    @Test fun escapeClearsWhatIsTypedThenLeaves() {
        val s = loaded().reduce(ListEvent.Typed('g'))
        assertEquals("", s.back()?.typed)
        assertNull(loaded().back())
    }

    @Test fun theFieldShowsAFirstCapital() = assertEquals("Gr", loaded().reduce(ListEvent.Typed('g')).reduce(ListEvent.Typed('r')).field)

    @Test fun theFocusKeepsItsMemoWhenTheListIsReloaded() {
        val s = loaded().reduce(ListEvent.Step(down = true))
        assertEquals(Entry(groceries), s.focused)
        val again = s.reduce(ListEvent.Loaded(listOf(Memo(9, "Aaa", "", 40)) + all))
        assertEquals(Entry(groceries), again.focused)
    }

    @Test fun theFocusStopsAtTheEnds() {
        assertEquals(NewMemo, loaded().reduce(ListEvent.Step(down = false)).reduce(ListEvent.Step(down = false)).focused)
        var s = loaded()
        repeat(10) { s = s.reduce(ListEvent.Step(down = true)) }
        assertEquals(Entry(plan), s.focused)
    }

    @Test fun aTouchFocusesARow() = assertEquals(Entry(groceries), loaded().reduce(ListEvent.Moved(2)).focused)

    @Test fun theFullMenuOnAMemoIsSim260sWithEditFocused() {
        val m = loaded().menu()
        assertEquals(listOf(ListAct.SWITCH, ListAct.NEW, ListAct.EDIT, ListAct.DELETE, ListAct.OPTIONS, ListAct.CLOSE), m.entries.map { it.action })
        assertEquals(2, m.highlighted)
        assertTrue(m.entries[4].separatorAbove)
    }

    @Test fun onNewMemoTheMenuHasNoMemoActionsAndNoShortMenu() {
        val s = loaded().reduce(ListEvent.Moved(0))
        assertEquals(listOf(ListAct.SWITCH, ListAct.NEW, ListAct.OPTIONS, ListAct.CLOSE), s.menu().entries.map { it.action })
        assertEquals(1, s.menu().highlighted)
        assertNull(s.shortMenu())
    }

    @Test fun theShortMenuIsDeleteThenEditFocusedByFullMenu() {
        val m = loaded().shortMenu()!!
        assertEquals(listOf(ListAct.DELETE, ListAct.EDIT), m.entries.map { it.action })
        assertEquals(1, m.highlighted)
        assertEquals(1, m.line)
    }

    @Test fun anEmptyStoredListIsEmptyOnceLoadedAndNotBefore() {
        assertFalse(MemoList().empty)
        assertTrue(MemoList().reduce(ListEvent.Loaded(emptyList())).empty)
        assertEquals(NewMemo, MemoList().reduce(ListEvent.Loaded(emptyList())).focused)
    }

    @Test fun confirmDeleteIsOnByDefault() = assertTrue(MemoOptions().confirmDelete)
}
