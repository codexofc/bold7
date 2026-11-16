package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearnedTest {
    @Test fun aWordIsKnownOnceTypedTwice() {
        val l = Learned()
        assertTrue(l.learn("zinwa"))
        assertFalse(l.contains("zinwa"), "a slip typed once stays unknown")
        l.learn("zinwa")
        assertTrue(l.contains("zinwa"))
        assertTrue(l.frequency("zinwa") in 0..255)
    }

    @Test fun moreUseMeansMoreFrequent() {
        val l = Learned()
        repeat(2) { l.learn("zinwa") }
        repeat(5) { l.learn("benos") }
        assertTrue(l.frequency("benos") > l.frequency("zinwa"))
        assertEquals(listOf("benos"), l.complete("ben", 3))
    }

    @Test fun aCapitalOnlyAtTheStartIsTheSameWord() {
        val l = Learned()
        l.learn("Zinwa")
        l.learn("zinwa")
        assertTrue(l.contains("Zinwa"))
        assertTrue(l.contains("zinwa"))
        assertEquals(listOf("zinwa"), l.complete("Zin", 3))
        l.learn("iPhone")
        l.learn("iPhone")
        assertTrue(l.contains("iPhone"))
        assertFalse(l.contains("iphone"))
    }

    @Test fun onlyWordsAreLearned() {
        val l = Learned()
        for (w in listOf("b5", "robert@bold7", "a", "x".repeat(33), "bold-7", "foo.bar", "''", "", "l'adresse")) l.learn(w)
        assertEquals(1, l.size, "only l'adresse")
        assertTrue(Learned.learnable("été"))
        assertTrue(Learned.learnable("aujourd'hui"))
        assertFalse(Learned.learnable("b5"))
        assertFalse(Learned.learnable("a@b"))
    }

    @Test fun theLeastFrequentGoWhenFull() {
        val l = Learned(max = 3)
        repeat(3) { l.learn("alpha") }
        repeat(2) { l.learn("beta") }
        l.learn("gamma")
        l.learn("delta")
        assertEquals(3, l.size)
        assertTrue(l.contains("alpha"))
        assertTrue(l.contains("beta"))
        assertFalse(l.text().contains("gamma"))
    }

    @Test fun keptAsTextAndReadBack() {
        val l = Learned()
        repeat(3) { l.learn("zinwa") }
        l.learn("benos")
        val back = Learned.parse(l.text() + "broken\nb5\t4\n")
        assertEquals(2, back.size)
        assertEquals(l.frequency("zinwa"), back.frequency("zinwa"))
        assertFalse(back.contains("benos"))
        back.learn("benos")
        assertTrue(back.contains("benos"))
    }

    @Test fun clearForgetsEverything() {
        val l = Learned()
        repeat(2) { l.learn("zinwa") }
        l.clear()
        assertEquals(0, l.size)
        assertFalse(l.contains("zinwa"))
        assertEquals("", l.text())
    }
}
