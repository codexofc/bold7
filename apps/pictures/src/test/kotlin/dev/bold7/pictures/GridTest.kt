package dev.bold7.pictures

import kotlin.test.Test
import kotlin.test.assertEquals

class GridTest {
    @Test fun theNinetyNineHundredsFourColumnsOf160() {
        val g = GridLayout(640, 413, 14)
        assertEquals(4, g.columns)
        assertEquals(160, g.cell)
        assertEquals(4, g.rows)
    }

    @Test fun theSquareKeepsFourColumnsOf180() {
        val g = GridLayout(720, 653, 500)
        assertEquals(4, g.columns)
        assertEquals(180, g.cell)
        assertEquals(125, g.rows)
    }

    @Test fun leftAndRightCrossTheRowsAndStopAtTheEnds() {
        assertEquals(4, move(3, 14, 4, Step.RIGHT))
        assertEquals(3, move(4, 14, 4, Step.LEFT))
        assertEquals(0, move(0, 14, 4, Step.LEFT))
        assertEquals(13, move(13, 14, 4, Step.RIGHT))
    }

    @Test fun upAndDownGoARowAndDownStopsOnTheLastPicture() {
        assertEquals(5, move(1, 14, 4, Step.DOWN))
        assertEquals(1, move(5, 14, 4, Step.UP))
        assertEquals(1, move(1, 14, 4, Step.UP))
        // 14 pictures: the last row holds 12 and 13; from 10 (third row) down goes to 13, the last.
        assertEquals(13, move(10, 14, 4, Step.DOWN))
        assertEquals(13, move(13, 14, 4, Step.DOWN))
        assertEquals(0, move(0, 0, 4, Step.DOWN))
    }

    @Test fun itScrollsOnlyAsFarAsTheFocusNeeds() {
        val g = GridLayout(720, 653, 100) // 3 rows of 180 show
        assertEquals(0, g.topFor(8, 0))
        assertEquals(1, g.topFor(12, 0))
        assertEquals(3, g.topFor(12, 5))
        assertEquals(1, g.topFor(12, 1))
    }
}
