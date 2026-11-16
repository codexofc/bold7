package dev.bold7.launcher.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrayGestureTest {
    @Test fun aDragPicksItsAxisPastTheSlopOnly() {
        assertNull(axis(3f, 4f, slop = 8f))
        assertEquals(Axis.SIDEWAYS, axis(20f, 5f, slop = 8f))
        assertEquals(Axis.UPDOWN, axis(5f, -20f, slop = 8f))
    }

    @Test fun theTrayFollowsTheFingerAndSettlesByDistanceOrFling() {
        // Lowered, a finger 100 px up of 400 raises it a quarter.
        assertEquals(0.25f, raised(0f, -100f, travel = 400f))
        assertEquals(1f, raised(0f, -900f, travel = 400f))
        assertEquals(0.5f, raised(1f, 200f, travel = 400f))
        assertEquals(true, settlesRaised(0.6f, velocity = 0f))
        assertEquals(false, settlesRaised(0.4f, velocity = 0f))
        // A flick wins over the distance: up (negative) raises, down lowers.
        assertEquals(true, settlesRaised(0.1f, velocity = -2000f))
        assertEquals(false, settlesRaised(0.9f, velocity = 2000f))
    }

    @Test fun aSidewaysDragTurnsTheViewPastASixthOrOnAFlickAndAFlickBackCancels() {
        assertEquals(1, panelStep(-130f, 0f, width = 720f))
        assertEquals(0, panelStep(-100f, 0f, width = 720f))
        assertEquals(-1, panelStep(130f, 0f, width = 720f))
        assertEquals(1, panelStep(-30f, -2000f, width = 720f))
        assertEquals(0, panelStep(-300f, 2000f, width = 720f))
    }

    @Test fun theGridScrollsByTheFingerAndRestsOnARow() {
        // Offsets in px, negative scrolled down; 8 rows, 5 shown: 3 rows of 97 at most.
        assertEquals(-291f, scrolled(-97f, -500f, rows = 8, shown = 5))
        assertEquals(0f, scrolled(-97f, 300f, rows = 8, shown = 5))
        assertEquals(2, restRow(-150f, 0f, rows = 8, shown = 5))
        assertEquals(1, restRow(-140f, 0f, rows = 8, shown = 5))
        // A flick goes one row further in its direction.
        assertEquals(2, restRow(-100f, -2000f, rows = 8, shown = 5))
        assertEquals(0, restRow(0f, -2000f, rows = 1, shown = 5))
    }

    @Test fun theCellUnderTheFingerCountsTheScrolledRowsAndStaysWithinTheTiles() {
        assertEquals(0, cellUnder(10f, 10f, firstRow = 0, count = 8))
        assertEquals(7, cellUnder(700f, 100f, firstRow = 0, count = 8))
        assertEquals(6 + 2, cellUnder(250f, 10f, firstRow = 1, count = 20))
        assertEquals(19, cellUnder(700f, 400f, firstRow = 0, count = 20))
        assertEquals(0, cellUnder(-20f, -40f, firstRow = 0, count = 3))
    }

    @Test fun nearTheGridsEdgesAMovedIconScrollsIt() {
        assertEquals(-1, edgeScroll(5f, height = 485f))
        assertEquals(1, edgeScroll(480f, height = 485f))
        assertEquals(0, edgeScroll(240f, height = 485f))
    }

    @Test fun anAppHeldOverAFoldersMiddleGoesIntoItElseTheOthersMakeRoom() {
        val app = App("a", "a.A", "A")
        val tiles = listOf(app, Folder("F", emptyList()), App("b", "b.B", "B"))
        // Over the folder's icon: into it (our choice, BBOS7-VISUEL §3); at its cell's edge: a place.
        assertEquals(Drop.IntoFolder(1), dropAt(tiles, from = 0, cell = 1, inIcon = true))
        assertEquals(Drop.At(1), dropAt(tiles, from = 0, cell = 1, inIcon = false))
        assertEquals(Drop.At(2), dropAt(tiles, from = 0, cell = 2, inIcon = true))
        // A folder never goes into a folder.
        assertEquals(Drop.At(1), dropAt(listOf(Folder("G", emptyList()), Folder("F", emptyList())), from = 0, cell = 1, inIcon = true))
    }
}
