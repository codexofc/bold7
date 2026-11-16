package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapsTest {
    private val sentences = Field(Kind.TEXT, capsSentences = true)
    private fun caps(before: String, complete: Boolean = true, field: Field = sentences) = capsAt(before, complete, field)

    @Test fun startOfTextAndOfParagraph() {
        assertTrue(caps(""))
        assertTrue(caps("  "))
        assertTrue(caps("Hello\n"))
        assertTrue(caps("Hello\n  "))
        assertFalse(caps("", complete = false))
    }

    @Test fun afterTheEndOfASentenceAndASpace() {
        assertTrue(caps("Hi. "))
        assertTrue(caps("Really? "))
        assertTrue(caps("Stop!  "))
        assertTrue(caps("He said \"go.\" "))
        assertTrue(caps("Done. (", complete = true))
    }

    @Test fun notInsideAWordNorAfterACommaNorRightAfterThePeriod() {
        assertFalse(caps("Hel"))
        assertFalse(caps("Hi, "))
        assertFalse(caps("Hi."))
        assertFalse(caps("see e.g. "))
    }

    @Test fun wordsAndCharactersFields() {
        val words = Field(Kind.TEXT, capsWords = true)
        assertTrue(caps("john ", field = words))
        assertFalse(caps("jo", field = words))
        assertTrue(caps("jo", field = Field(Kind.TEXT, capsChars = true)))
    }

    @Test fun noCapsWithoutTheFlagsNorOutsideText() {
        assertFalse(caps("", field = Field(Kind.TEXT)))
        assertFalse(caps("", field = Field(Kind.PLAIN)))
    }
}
