package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PredictionsTest {
    private val en = WordList.parse("this\t200\nthink\t133\nthird\t156\nthe\t222\ntea\t121\nten\t140\nwork\t164\nworld\t145\nworking\t150\nOK\t150\nParis\t130\n")
    private val fr = WordList.parse("thé\t90\nthis\t80\ntravail\t150\nj'ai\t122\nl'adresse\t109\n")
    private val lists = listOf(fr, en)
    private val subs = Substitutions.parse("teh\tthe\njai\tj'ai")

    private fun predict(typed: String) = predict(typed, lists, subs)
    private fun texts(typed: String) = predict(typed).map { it.text }

    @Test fun completionsOfBothListsByFrequencyThreeAtMost() {
        // sim-94a (C): "Thi" gives three words, in bold.
        assertEquals(listOf("This", "Third", "Think"), texts("Thi"))
        assertTrue(predict("Thi").all { it.look == Look.COMPLETION })
        assertEquals(listOf("work", "working", "world"), texts("wor"))
    }

    @Test fun theCaseTyped() {
        assertEquals(listOf("THIS", "THIRD", "THINK"), texts("THI"))
        assertEquals(listOf("this", "third", "think"), texts("thi"))
    }

    @Test fun aWordOfTheSubstitutionListGivesTheTypedWordThenItsReplacementThenCorrections() {
        // sim-96a (C): "Teh" in italics, "The", "Tea".
        val p = predict("Teh")
        assertEquals(Suggestion("Teh", Look.TYPED), p[0])
        assertEquals(Suggestion("The", Look.CORRECTION), p[1])
        assertEquals(3, p.size)
        assertEquals(Look.CORRECTION, p[2].look)
        assertEquals(listOf("jai", "j'ai"), texts("jai").take(2))
    }

    @Test fun anUnknownWordWithoutCompletionGetsCorrectionsAtOneLetter() {
        assertEquals(listOf("tez", "ten", "tea"), texts("tez"))
        assertEquals(Look.TYPED, predict("tez")[0].look)
        assertEquals(listOf("wrok", "work"), texts("wrok"))
    }

    @Test fun nothingToPropose() {
        assertEquals(emptyList(), predict("zzzz"))
        assertEquals(emptyList(), predict(""))
    }

    @Test fun properNounsAndCapitalsComplete() {
        assertEquals(listOf("Paris"), texts("Par"))
    }

    @Test fun knownWords() {
        assertTrue(known("this", lists))
        assertTrue(known("This", lists))
        assertTrue(known("ok", lists), "OK in capitals")
        assertTrue(known("nasa", listOf(WordList.parse("NASA\t100\n"))))
        assertTrue(known("l'adresse", lists))
        assertFalse(known("reciev", lists))
        assertTrue(known("reciev", emptyList()), "nothing is unknown before the lists are read")
    }
}
