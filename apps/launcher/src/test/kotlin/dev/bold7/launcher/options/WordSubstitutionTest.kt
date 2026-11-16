package dev.bold7.launcher.options

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WordSubstitutionTest {
    private val list = listOf(Substitution("ttyl", "talk to you later"), Substitution("brb", "be right back"))

    @Test
    fun `a new entry goes in by Replace, one entry per Replace whatever its case`() {
        val l = list.put(null, Substitution("addr", "1 rue de Paris"))
        assertEquals(listOf("addr", "brb", "ttyl"), l.map { it.replace })
        val again = l.put(null, Substitution("TTYL", "ta ta"))
        assertEquals(listOf("addr", "brb", "TTYL"), again.map { it.replace }, "the old one replaced")
    }

    @Test
    fun `an edited entry replaces its old self, renamed or not`() {
        val l = list.put(list[0], Substitution("ttyl2", "talk to you later", smart = false))
        assertEquals(listOf("brb", "ttyl2"), l.map { it.replace })
        assertEquals(false, l[1].smart)
    }

    @Test
    fun `delete takes the entry out`() {
        assertEquals(listOf("brb"), list.remove(list[0]).map { it.replace })
    }

    @Test
    fun `Replace is one word, With is not empty`() {
        assertNull(problem("ttyl", "talk"))
        assertEquals(ENTER_REPLACE, problem(" ", "talk"))
        assertEquals(ENTER_REPLACE, problem("t t", "talk"))
        assertEquals(ENTER_REPLACE, problem("b-day", "birthday"))
        assertNull(problem("cant", "can't"))
        assertEquals(ENTER_WITH, problem("ttyl", "  "))
    }

    @Test
    fun `the macros are those Typing expands`() {
        // apps/saisie Substitutions.MACROS: "pPVbdDtT%", the number and the PIN having no value.
        assertEquals(listOf("%d", "%D", "%t", "%T", "%b", "%V", "%%"), MACROS.map { it.second })
    }

    @Test
    fun `the search keeps the entries whose Replace or With contain it, and a row reads as on sim-300`() {
        assertEquals(list, list.matching("  "))
        assertEquals(listOf(list[1]), list.matching("RIGHT"))
        assertEquals(listOf(list[0]), list.matching("tty"))
        assertEquals("ttyl (talk to you later)", list[0].rowText)
        assertEquals("SmartCase", list[0].mode)
        assertEquals("Specified Case", Substitution("a", "b", smart = false).mode)
    }
}
