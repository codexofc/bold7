package dev.bold7.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewerTest {
    // A 2000x1000 picture on the Q25's 720x720 square.
    private val frame = Frame(2000, 1000, 720, 720)
    private val fit = Zoom.fit(frame)

    @Test fun itOpensFittedAndCentred() {
        assertEquals(0, fit.level)
        assertEquals(0.36f, fit.scale(frame), 1e-4f)
        assertEquals(1000f, fit.x)
        assertEquals(500f, fit.y)
    }

    @Test fun aSmallPictureIsNotEnlargedAtFirst() {
        val small = Frame(100, 50, 720, 720)
        assertEquals(1f, Zoom.fit(small).scale(small))
    }

    @Test fun theClickZoomsInEscapeZoomsOutThenCloses() {
        val two = fit.zoomIn(frame).zoomIn(frame)
        assertEquals(2, two.level)
        assertEquals(0.36f * 4, two.scale(frame), 1e-4f)
        assertEquals(MAX_LEVEL, generateSequence(fit) { it.zoomIn(frame) }.take(10).last().level)
        assertEquals(1, two.zoomOut(frame)?.level)
        assertNull(fit.zoomOut(frame))
    }

    @Test fun panningMovesAnEighthOfTheScreenAndStopsAtTheEdges() {
        val zoomed = fit.zoomIn(frame) // 0.72: 1440x720 on the screen
        val right = zoomed.pan(1, 0, frame)
        assertEquals(1000f + 90 / 0.72f, right.x, 1e-2f)
        // The picture's edge stays at the screen's: its centre no further than half a screen from it.
        val far = generateSequence(zoomed) { it.pan(1, 0, frame) }.take(50).last()
        assertEquals(2000f - 360 / 0.72f, far.x, 1e-2f)
        // As high as the screen: it does not move up nor down.
        assertEquals(500f, zoomed.pan(0, 3, frame).y)
    }

    @Test fun fittedItDoesNotPanAndZoomingOutKeepsItInside() {
        assertEquals(fit, fit.pan(1, 1, frame))
        val far = generateSequence(fit.zoomIn(frame).zoomIn(frame)) { it.pan(1, 1, frame) }.take(50).last()
        val back = far.zoomOut(frame)!!
        assertEquals(2000f - 360 / 0.72f, back.x, 1e-2f)
    }

    @Test fun aDragMovesByItsPixels() {
        val zoomed = fit.zoomIn(frame)
        assertEquals(1000f + 36 / 0.72f, zoomed.drag(36f, 0f, frame).x, 1e-2f)
    }
}
