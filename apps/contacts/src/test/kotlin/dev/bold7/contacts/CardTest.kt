package dev.bold7.contacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardTest {
    private val mobile = Detail(Kind.PHONE, "Mobile", "123456", 10)
    private val work = Detail(Kind.PHONE, "Work", "999", 11)
    private val email = Detail(Kind.EMAIL, "Email", "dexter@cn.com", 12)
    private val home = Detail(Kind.ADDRESS, "Home", "1 Lab Street\nMiami", 13)
    private val dexter = Card(1, "Dexter", company = "", details = listOf(email, home, work, mobile), groups = listOf("Family"))

    @Test fun sectionsComeInTheOrderOfTheCard() {
        // gsm 015: Phone Numbers, then Email Addresses (C); Addresses and Groups after them (D).
        assertEquals(
            listOf(
                Section.PHONES to listOf(work, mobile),
                Section.EMAILS to listOf(email),
                Section.ADDRESSES to listOf(home),
            ),
            dexter.sections(),
        )
    }

    @Test fun theFocusOpensOnTheFirstNumber() {
        val s = CardScreen(dexter)
        assertEquals(work, s.focused)
    }

    @Test fun theTrackpadWalksTheDetailsInTheirShownOrder() {
        val s = CardScreen(dexter).step(down = true).step(down = true)
        assertEquals(email, s.focused)
        assertEquals(home, s.step(down = true).step(down = true).focused)
        assertEquals(work, s.step(down = false).step(down = false).step(down = false).focused)
    }

    @Test fun aCardWithoutDetailsHasNoFocusAndItsMenuOpensOnEdit() {
        // sim-182b (C): Edit focused, Delete.
        val s = CardScreen(Card(2, "Nobody", "", emptyList(), emptyList()))
        assertNull(s.focused)
        val m = s.menu()
        assertEquals(listOf(CardAct.SWITCH, CardAct.EDIT, CardAct.DELETE, CardAct.SEND, CardAct.HELP, CardAct.CLOSE), m.entries.map { it.action })
        assertEquals(1, m.highlighted)
    }

    @Test fun onANumberTheMenuOpensOnCall() {
        // sim-195, sim-196 (C): Switch Application | Copy | Edit, Delete | Call | Email, Text.
        val m = CardScreen(dexter).menu()
        assertEquals(listOf(CardAct.SWITCH, CardAct.COPY, CardAct.EDIT, CardAct.DELETE, CardAct.SEND, CardAct.CALL, CardAct.EMAIL, CardAct.TEXT, CardAct.HELP, CardAct.CLOSE), m.entries.map { it.action })
        assertEquals(CardAct.CALL, m.entries[m.highlighted!!].action)
        assertEquals(listOf(CardAct.COPY, CardAct.EDIT, CardAct.CALL, CardAct.EMAIL, CardAct.HELP), m.entries.filter { it.separatorAbove }.map { it.action })
    }

    @Test fun onAnAddressTheMenuOpensOnEmailAndCallsTheFirstNumber() {
        val s = CardScreen(dexter).moved(email)
        val m = s.menu()
        assertEquals(CardAct.EMAIL, m.entries[m.highlighted!!].action)
        assertEquals(work, s.number)
        assertEquals(email, s.email)
    }

    @Test fun aPostalAddressOpensOnEdit() {
        val m = CardScreen(dexter).moved(home).menu()
        assertEquals(CardAct.EDIT, m.entries[m.highlighted!!].action)
    }

    @Test fun aReloadKeepsTheFocusedDetail() {
        val s = CardScreen(dexter).moved(email)
        val again = s.loaded(dexter.copy(details = listOf(mobile, email.copy(value = "new@cn.com"))))
        assertEquals(12, again.focused?.dataId)
    }

    @Test fun theShortMenuOfANumberEndsOnCall() {
        // sim-197 (C).
        val m = CardScreen(dexter).shortMenu()
        assertEquals(listOf(CardAct.COPY, CardAct.EDIT, CardAct.TEXT, CardAct.CALL), m.entries.map { it.action })
        assertEquals(3, m.highlighted)
    }
}
