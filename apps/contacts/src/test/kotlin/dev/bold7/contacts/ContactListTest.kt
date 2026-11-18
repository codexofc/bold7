package dev.bold7.contacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactListTest {
    private val dexter = Person(1, "Dexter Morgan", phone = "123456", email = "dexter@cn.com")
    private val deeDee = Person(2, "Dee Dee", phone = null, email = null)
    private val batman = Person(3, "Batman", phone = "555", email = null)
    private val eloise = Person(4, "Éloïse de Saint-Exupéry", phone = "777", email = null)
    private val loaded = ContactList().reduce(ListEvent.Loaded(listOf(batman, deeDee, dexter, eloise)))

    private fun ContactList.type(text: String) = text.fold(this) { s, c -> s.reduce(ListEvent.Typed(c)) }

    @Test fun aContactJustSavedTakesTheFocusWhetherTheListReloadsBeforeOrAfter() {
        // sim-193 (C): Save of a new contact, back to the list, the new contact focused.
        val alice = Person(9, "Alice", phone = null, email = null)
        val onNew = loaded.reduce(ListEvent.Moved(0))
        val again = ListEvent.Loaded(listOf(alice, batman, deeDee, dexter, eloise))
        assertEquals(Entry(alice), onNew.reduce(ListEvent.Created(9)).reduce(again).focused)
        assertEquals(Entry(alice), onNew.reduce(again).reduce(ListEvent.Created(9)).focused)
        assertEquals(NewContact, onNew.reduce(ListEvent.Created(9)).reduce(again).reduce(ListEvent.Moved(0)).reduce(again).focused)
    }

    @Test fun newContactComesFirstThenTheContactsInTheirOrder() {
        assertEquals(listOf(NewContact, Entry(batman), Entry(deeDee), Entry(dexter), Entry(eloise)), loaded.rows)
    }

    @Test fun theFocusOpensOnTheFirstContact() {
        // sim-178 (C).
        assertEquals(Entry(batman), loaded.focused)
        assertEquals(NewContact, ContactList().reduce(ListEvent.Loaded(emptyList())).focused)
    }

    @Test fun selectingAContactHasTheSameRows() {
        // sim-183 (C; its [Use Once] row is not done).
        val pick = ContactList(picking = true).reduce(ListEvent.Loaded(listOf(batman, dexter)))
        assertEquals(listOf(NewContact, Entry(batman), Entry(dexter)), pick.rows)
        assertEquals(Entry(batman), pick.focused)
    }

    @Test fun typingFiltersOnTheStartOfAnyWordOfTheName() {
        assertEquals(listOf(deeDee, dexter, eloise), loaded.type("d").shown)
        assertEquals(listOf(dexter), loaded.type("mor").shown)
        assertEquals(emptyList(), loaded.type("exter").shown)
    }

    @Test fun eachTypedWordMustStartAWordOfTheName() {
        assertEquals(listOf(dexter), loaded.type("d m").shown)
        assertEquals(listOf(deeDee), loaded.type("dee d").shown)
    }

    @Test fun matchingIgnoresCaseAndAccents() {
        assertEquals(listOf(eloise), loaded.type("ELOI").shown)
        assertEquals(listOf(eloise), loaded.type("exu").shown)
    }

    @Test fun theFieldShowsItsFirstLetterAsACapital() = assertEquals("Dex", loaded.type("dex").field)

    @Test fun typingMovesTheFocusToTheFirstMatch() = assertEquals(Entry(deeDee), loaded.type("de").focused)

    @Test fun withoutAMatchNewContactStaysAndTakesTheFocus() {
        // sim-190 (C).
        val s = loaded.type("zzz")
        assertEquals(NewContact, s.rows.single())
        assertEquals(NewContact, s.focused)
        assertTrue(s.noMatch)
        assertFalse(loaded.type("de").noMatch)
    }

    @Test fun eraseRemovesTheLastCharacter() {
        val s = loaded.type("dex").reduce(ListEvent.Erased)
        assertEquals("de", s.typed)
        assertEquals(listOf(deeDee, dexter, eloise), s.shown)
    }

    @Test fun backClearsWhatIsTypedThenLeaves() {
        assertEquals("", loaded.type("de").back()!!.typed)
        assertNull(loaded.back())
    }

    @Test fun theTrackpadStopsAtTheEnds() {
        assertEquals(NewContact, loaded.reduce(ListEvent.Step(down = false)).reduce(ListEvent.Step(down = false)).focused)
        val last = (1..10).fold(loaded) { s, _ -> s.reduce(ListEvent.Step(down = true)) }
        assertEquals(Entry(eloise), last.focused)
    }

    @Test fun aReloadKeepsTheFocusedContact() {
        val onDexter = loaded.reduce(ListEvent.Moved(3))
        val again = onDexter.reduce(ListEvent.Loaded(listOf(dexter.copy(name = "Dexter"), eloise)))
        assertEquals(dexter.id, (again.focused as Entry).person.id)
    }

    @Test fun spacesAloneDoNotFilter() = assertEquals(4, loaded.type(" ").shown.size)

    @Test fun theMenuOfAContactOffersWhatItHas() {
        // sim-198 (C): View, Edit, Delete | New Contact | Email, Call, Text.
        assertEquals(
            listOf(ListAct.SWITCH, ListAct.VIEW, ListAct.EDIT, ListAct.DELETE, ListAct.SEND, ListAct.NEW, ListAct.EMAIL, ListAct.CALL, ListAct.TEXT, ListAct.HELP, ListAct.CLOSE),
            loaded.reduce(ListEvent.Moved(3)).menu().entries.map { it.action },
        )
        // sim-179, sim-180 (C): no number nor address, nothing to reach.
        assertEquals(listOf(ListAct.SWITCH, ListAct.VIEW, ListAct.EDIT, ListAct.DELETE, ListAct.SEND, ListAct.NEW, ListAct.HELP, ListAct.CLOSE), loaded.reduce(ListEvent.Moved(2)).menu().entries.map { it.action })
    }

    @Test fun theFullMenuOpensOnViewAndOnNewContactFromItsRow() {
        val m = loaded.menu()
        assertEquals(ListAct.VIEW, m.entries[m.highlighted!!].action)
        val n = loaded.reduce(ListEvent.Moved(0)).menu()
        assertEquals(listOf(ListAct.SWITCH, ListAct.NEW, ListAct.HELP, ListAct.CLOSE), n.entries.map { it.action })
    }

    @Test fun groupsAreSeparated() {
        val m = loaded.reduce(ListEvent.Moved(3)).menu()
        assertEquals(listOf(ListAct.VIEW, ListAct.NEW, ListAct.EMAIL, ListAct.HELP), m.entries.filter { it.separatorAbove }.map { it.action })
    }

    @Test fun sendContactCardOpensASubmenu() {
        val m = loaded.reduce(ListEvent.Moved(3)).menu()
        assertTrue(m.entries.first { it.action == ListAct.SEND }.submenu)
        assertEquals(listOf(ListAct.SEND_EMAIL, ListAct.SEND_TEXT), sendCardMenu().entries.map { it.action })
    }

    @Test fun theShortMenuEndsOnView() {
        // sim-181 (C): Delete, Edit, …, View focused by Full Menu.
        val m = loaded.reduce(ListEvent.Moved(3)).shortMenu()!!
        assertEquals(listOf(ListAct.DELETE, ListAct.EDIT, ListAct.EMAIL, ListAct.CALL, ListAct.TEXT, ListAct.VIEW), m.entries.map { it.action })
        assertEquals(5, m.highlighted)
        assertNull(loaded.reduce(ListEvent.Moved(0)).shortMenu())
    }

    @Test fun selectingOffersSelectFirst() {
        val pick = ContactList(picking = true).reduce(ListEvent.Loaded(listOf(batman)))
        assertEquals(listOf(ListAct.SWITCH, ListAct.SELECT), pick.menu().entries.take(2).map { it.action })
        assertEquals(ListAct.SELECT, pick.shortMenu()!!.let { it.entries[it.highlighted!!].action })
    }
}
