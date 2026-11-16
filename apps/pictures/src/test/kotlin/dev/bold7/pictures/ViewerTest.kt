package dev.bold7.pictures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewerTest {
    // A 2000x1000 picture on the Q25's 720x720 square.
    private val frame = Frame(2000, 1000, 720, 720)
    private val fit = Zoom.fit(frame)

    @Test fun itOpensFittedAndCentred() {
        assertEquals(0.36f, fit.scale(frame), 1e-4f)
        assertEquals(1000f, fit.x)
        assertEquals(500f, fit.y)
        val small = Frame(100, 50, 720, 720)
        assertEquals(1f, Zoom.fit(small).scale(small))
    }

    @Test fun eachClickZoomsBy118SixTimesAtMost() {
        val one = fit.zoomIn(frame)
        assertEquals(0.36f * 1.18f, one.scale(frame), 1e-4f)
        val deepest = generateSequence(fit) { it.zoomIn(frame) }.take(20).last()
        assertEquals(MAX_LEVEL, deepest.level)
        assertEquals(6, deepest.level)
        assertEquals(0.36f * 2.6996f, deepest.scale(frame), 1e-3f)
    }

    @Test fun escapeZoomsOutAStepThenCloses() {
        val two = fit.zoomIn(frame).zoomIn(frame)
        assertEquals(1, two.zoomOut(frame)?.level)
        assertNull(fit.zoomOut(frame))
    }

    @Test fun aStepPans64ScreenPixelsAndStopsAtTheEdge() {
        val zoomed = fit.zoomIn(frame).zoomIn(frame).zoomIn(frame)
        val s = zoomed.scale(frame)
        assertEquals(1000f + 64 / s, zoomed.pan(1, 0, frame).x, 1e-2f)
        val far = generateSequence(zoomed) { it.pan(1, 0, frame) }.take(100).last()
        assertEquals(2000f - 360 / s, far.x, 1e-2f)
        assertEquals(fit, fit.pan(1, 1, frame))
    }

    @Test fun aTurnedPictureFitsByItsTurnedSides() {
        val turned = Frame(2000, 1000, 720, 720, quarter = 1)
        assertEquals(1000, turned.shownWidth)
        assertEquals(0.36f, turned.fitScale, 1e-4f)
        assertEquals(500f, Zoom.fit(turned).x)
    }

    @Test fun fittedTheTrackpadGoesToTheNextPictureAndStopsAtTheEnds() {
        val v = Viewing(0, 3)
        assertEquals(1, v.step(true).index)
        assertEquals(0, v.step(false).index)
        assertEquals(2, Viewing(2, 3).step(true).index)
        assertEquals(v.copy(level = 2), v.copy(level = 2).step(true))
    }

    @Test fun rotateTurnsAQuarterAndFitsAgain() {
        val v = Viewing(1, 3, quarter = 3, level = 4).rotate()
        assertEquals(0, v.quarter)
        assertEquals(0, v.level)
        assertEquals(1, Viewing(1, 3).rotate().quarter)
    }

    @Test fun thePositionReadsAsTheSimulators() {
        assertEquals("2 of 14", Viewing(1, 14).position)
    }
}
