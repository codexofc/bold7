package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubstitutionsTest {
    private val subs = Substitutions.parse(
        """
        # comment
        teh	the	smart
        ttyl	talk to you later	smart
        bb	BlackBerry	specified

        broken line
        """.trimIndent(),
    )

    @Test fun smartCaseFollowsTheTypedWordAndTheSentence() {
        assertEquals("the", subs.find("teh", sentenceStart = false))
        assertEquals("The", subs.find("Teh", sentenceStart = false))
        assertEquals("The", subs.find("teh", sentenceStart = true))
        assertEquals("THE", subs.find("TEH", sentenceStart = false))
        assertEquals("Talk to you later", subs.find("Ttyl", sentenceStart = false))
    }

    @Test fun specifiedCaseIsKeptAsWritten() {
        assertEquals("BlackBerry", subs.find("bb", sentenceStart = false))
        assertEquals("BlackBerry", subs.find("BB", sentenceStart = true))
    }

    @Test fun unknownWordsAndBadLinesFindNothing() {
        assertNull(subs.find("the", sentenceStart = false))
        assertNull(subs.find("broken", sentenceStart = false))
        assertNull(Substitutions.NONE.find("teh", sentenceStart = false))
    }

    @Test fun os7sListHasNoCaseColumnAndAMacroWithoutValueLeavesTheWord() {
        val s = Substitutions.parse("dont\tdon't\ni\tI\nmynumber\t%p\nrb\t%b")
        assertEquals("Don't", s.find("dont", sentenceStart = true))
        assertEquals("I", s.find("i", sentenceStart = false))
        assertNull(s.find("mynumber", sentenceStart = false))
        assertNull(s.find("rb", sentenceStart = false), "no macro given: the word typed stays")
        val macros = { c: Char -> if (c == 'b') "80%" else null }
        assertEquals("80%", s.find("rb", sentenceStart = false, macros))
        assertNull(s.find("mynumber", sentenceStart = false, macros), "the number has none (D)")
    }

    @Test fun macrosAreExpandedThenCased() {
        val s = Substitutions.parse("mydate\ttoday is %d\nsale\t50% off, 100%% sure\nnow\t%t %x")
        val macros = { c: Char -> when (c) { 'd' -> "25/12/2009"; 't' -> "10:30"; else -> null } }
        assertEquals("Today is 25/12/2009", s.find("mydate", sentenceStart = true, macros))
        assertEquals("50% off, 100% sure", s.find("sale", sentenceStart = false, macros), "a % that is no macro stays")
        assertEquals("10:30 %x", s.find("now", sentenceStart = false, macros))
    }

    @Test fun thePersonalListGoesToTextAndBack() {
        val rows = listOf(
            UserEntry("ttyl", "talk to you later", smart = true),
            UserEntry("Bold", "Bold7\tOS", smart = false),
            UserEntry("", "nothing", smart = true),
            UserEntry("addr", "1 rue\nde Paris", smart = true),
        )
        val text = UserEntry.text(rows)
        assertEquals(
            listOf(UserEntry("ttyl", "talk to you later", true), UserEntry("Bold", "Bold7 OS", false), UserEntry("addr", "1 rue de Paris", true)),
            UserEntry.rows(text),
        )
        val s = Substitutions.parse(text)
        assertEquals("Talk to you later", s.find("ttyl", sentenceStart = true))
        assertEquals("Bold7 OS", s.find("bold", sentenceStart = true))
    }

    @Test fun thePersonalListWinsOverTheOthers() {
        val mine = Substitutions.parse(UserEntry.text(listOf(UserEntry("teh", "tea", smart = true))))
        val both = mine + Substitutions.parse("teh\tthe\nhte\tthe")
        assertEquals("tea", both.find("teh", sentenceStart = false))
        assertEquals("the", both.find("hte", sentenceStart = false))
    }

    @Test fun theListsParse() {
        // OS 7's, copied by tools/import-assets.sh saisie.
        val en = Substitutions.parse(java.io.File("../../assets-gen/saisie/assets/substitution/en.tsv").readText())
        assertEquals("The", en.find("teh", sentenceStart = true))
        assertEquals("the", en.find("hte", sentenceStart = false))
        assertEquals("don't", en.find("dont", sentenceStart = false))
        assertNull(en.find("mynumber", sentenceStart = false))
    }

    @Test fun frenchWinsAndEnglishFormsThatAreFrenchWordsAreLeftOut() {
        val fr = Substitutions.parse("cest\tc'est\njai\tj'ai")
        val en = Substitutions.parse("dont\tdon't\nteh\tthe\nil\tI'll\ncest\tit's")
        val both = fr + en.without { it in setOf("dont", "il") }
        assertNull(both.find("dont", sentenceStart = false))
        assertNull(both.find("Il", sentenceStart = true))
        assertEquals("the", both.find("teh", sentenceStart = false))
        assertEquals("c'est", both.find("cest", sentenceStart = false))
        assertEquals("J'ai", both.find("jai", sentenceStart = true))
    }

    @Test fun bothRealListsTogetherBreakNoFrenchWord() {
        // OS 7's English list and the French word list, copied by tools/import-assets.sh saisie;
        // our French list, in git. the user writes French with English words, whatever the subtype.
        val words = WordList.parse(java.io.File("../../assets-gen/saisie/assets/dictionary/fr.tsv").readText())
        val en = Substitutions.parse(java.io.File("../../assets-gen/saisie/assets/substitution/en.tsv").readText())
        val fr = Substitutions.parse(java.io.File("src/main/assets/substitution/fr.tsv").readText())
        val both = fr + en.without(words::contains)
        for (w in listOf("dont", "il", "sec", "secs", "mo", "id", "mn", "hel")) assertNull(both.find(w, sentenceStart = false), w)
        assertEquals("the", both.find("teh", sentenceStart = false))
        assertEquals("don't", en.find("dont", sentenceStart = false))
        assertEquals("j'ai", both.find("jai", sentenceStart = false))
        assertEquals("C'est", both.find("cest", sentenceStart = true))
        assertEquals(111, fr.size)
    }

    @Test fun aOneLetterCapitalIsNotAllCaps() {
        val s = Substitutions.parse("i\tI\tsmart")
        assertEquals("I", s.find("i", sentenceStart = false))
    }
}
