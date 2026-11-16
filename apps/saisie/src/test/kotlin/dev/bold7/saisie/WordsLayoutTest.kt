package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class WordsLayoutTest {
    @Test fun aListThatFitsIsUntouched() {
        assertContentEquals(intArrayOf(80, 120), WordsLayout.cells(intArrayOf(80, 120), 720, 2, 10))
    }

    @Test fun theWidestCellsGiveWayUntilTheFrameFitsTheScreen() {
        val natural = intArrayOf(90, 700, 640, 100)
        val cut = WordsLayout.cells(natural, 720, 2, 10)
        assertTrue(cut.sum() + 3 * 2 + 20 <= 720, cut.joinToString())
        assertTrue(cut[0] == 90 && cut[3] == 100, "short words keep their width")
        assertTrue(cut[1] == cut[2] && cut[1] < 640)
    }

    @Test fun oneWordWiderThanTheScreenIsCutToIt() {
        assertContentEquals(intArrayOf(700), WordsLayout.cells(intArrayOf(2000), 720, 2, 10))
    }
}
