package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordListTest {
    // Unsorted on purpose, as a list could come; a broken line, an empty word.
    private val words = WordList.parse("the\t220\nthere\t150\nPrague\t90\ntheir\t180\nbroken\n\t12\nthem\t160\nété\t200\n")

    @Test fun knownWordsWhateverTheCaseTypedAtTheStart() {
        assertTrue(words.contains("the"))
        assertTrue(words.contains("The"))
        assertTrue(words.contains("Prague"))
        assertTrue(words.contains("été"))
        assertFalse(words.contains("prague"), "a proper noun keeps its capital")
        assertFalse(words.contains("th"))
        assertFalse(words.contains("broken"))
        assertEquals(6, words.size)
    }

    @Test fun completionsByFrequency() {
        assertEquals(listOf("the", "their", "them"), words.complete("th", 3))
        assertEquals(listOf("there"), words.complete("ther", 3))
        assertEquals(emptyList(), words.complete("x", 3))
        assertEquals(listOf("été"), words.complete("é", 1))
        assertEquals(listOf("the"), words.complete("th", 1), "the most frequent kept, not the first in order")
    }

    @Test fun frequencies() {
        assertEquals(220, words.frequency("the"))
        assertEquals(220, words.frequency("The"), "a capital typed at the start")
        assertEquals(90, words.frequency("Prague"))
        assertEquals(-1, words.frequency("prague"))
        assertEquals(-1, words.frequency("th"))
    }

    @Test fun theFirstLineOfAWordWins() {
        // OS 7's part is read before AOSP's list: where both have a word, OS 7's line stays.
        val laid = WordList.parse("colour\t120\n" + "\n" + "colour\t200\ncolor\t100\n")
        assertEquals(120, laid.frequency("colour"))
        assertEquals(100, laid.frequency("color"))
        assertEquals(2, laid.size)
    }

    @Test fun aospListsLoadAndOs7PartOnlyAdds() {
        // AOSP's lists are in git; OS 7's part comes from tools/import-assets.sh saisie when this checkout has it.
        for (lang in listOf("fr", "en")) {
            val aosp = java.io.File("src/main/assets/dictionary/$lang.tsv").readText()
            assertTrue(WordList.parse(aosp).size > 150_000, lang)
            val os7 = java.io.File("../../assets-gen/saisie/assets/os7/dictionary/$lang.tsv").takeIf { it.isFile }?.readText() ?: continue
            val both = WordList.parse(os7 + "\n" + aosp)
            assertTrue(both.size > WordList.parse(aosp).size, lang)
        }
    }
}
