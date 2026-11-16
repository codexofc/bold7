package dev.bold7.launcher.connections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// sim-23, sim-24, sim-112 to sim-118, 640x480 (C), the panel's y from the banner's bottom (93):
// the box (border_groupboxfull) from 0, its title bar's line at 45 (138), rows of 60 from 46 with
// a line at 106, 166, 226 (199, 259, 319), All Off's row to the box's edge at 287 (380), the next
// box at 290 (383), its line at 335 (428), Alarm to 380 (473); Options and Status 81 px rows
// (sim-24: line 156, then 238, 319, 400), its edge 2 px under the last band (sim-116).
class ConnectionsLayoutTest {
    private val sim = ConnectionsLayout(width = 640, boxes = 3)
    private val q25 = ConnectionsLayout(width = 720, boxes = 4)

    @Test fun theFirstBoxIsSim23s() {
        assertEquals(36, sim.titleBaseline(0))
        assertEquals(45, sim.titleLine(0))
        assertEquals(46, sim.rowTop(0))
        assertEquals(listOf(106, 166, 226), sim.boxLines)
        assertEquals(87, sim.rowTop(0) + ConnectionsLayout.CHECK_BASELINE) // "Mobile Network" on 180
        assertEquals(226, sim.buttonRowTop)
        assertEquals(231, sim.buttonTop)
        assertEquals(287, sim.boxEdge(0))
    }

    @Test fun theAlarmBoxFollowsBy3() {
        assertEquals(290, sim.boxTop(1))
        assertEquals(335, sim.titleLine(1))
        assertEquals(336, sim.alarmTop)
        assertEquals(380, sim.boxEdge(1))
        assertEquals(369, sim.alarmTop + ConnectionsLayout.ALARM_BASELINE) // "Alarm" on 462
    }

    @Test fun optionsAndStatusRowsAre81() {
        val top = sim.boxTop(2)
        assertEquals(383, top)
        assertEquals(top + 46, sim.entryTop(0))
        assertEquals(listOf(1, 2, 3).map { sim.entryTop(0) + 81 * it }, sim.entryLines)
        assertEquals(sim.entryTop(0) + 4 * 81, sim.boxEdge(2))
        assertEquals(sim.boxEdge(2) + 3, sim.height)
    }

    @Test fun theQ25HasFourBoxesAndAScrollingPanel() {
        assertEquals(46 + 4 * 60 + 61, q25.boxEdge(0))
        assertEquals(816, q25.height)
        assertEquals(714, q25.right) // the box's last white column (x 634 on 640)
        assertEquals(681, q25.checkX) // 12 from the edge (601 on 640)
        assertEquals(707, q25.buttonRight) // 627 on 640
    }

    @Test fun slotsFollowTheItems() {
        // Items: banner, 4 boxes, All Off, Alarm, 4 entries.
        assertNull(q25.slot(0))
        assertEquals(46 to 106, q25.slot(1))
        assertEquals(226 to 286, q25.slot(4))
        assertEquals(286 to 347, q25.slot(5))
        assertEquals(q25.alarmTop to q25.boxEdge(1), q25.slot(6))
        assertEquals(q25.entryTop(3) to q25.boxEdge(2), q25.slot(10))
    }

    @Test fun theBandIsTheSlotLess2AtTopAndBottom() {
        // sim-114 (C): Alarm's row 429 to 472, its band 431 to 470.
        assertEquals(sim.alarmTop + 2 to sim.boxEdge(1) - 2, sim.band(sim.slot(5)!!))
    }

    @Test fun theScrollShowsTheFocusedSlotAndNoMore() {
        // sim-115, sim-118 (C): just enough, down or up; the last one shows the box's end.
        val window = 720 - 93
        assertEquals(0, q25.scrollFor(1, 0, window))
        assertEquals(0, q25.scrollFor(6, 0, window))
        assertEquals(q25.entryTop(1) + 81 - window, q25.scrollFor(8, 0, window))
        assertEquals(q25.height - window, q25.scrollFor(10, 0, window))
        assertEquals(46, q25.scrollFor(1, 150, window))
        assertEquals(150, q25.scrollFor(0, 150, window))
    }

    @Test fun aTouchFindsItsItem() {
        assertEquals(1, q25.itemAt(300f, 50f, buttonLeft = 580))
        assertEquals(4, q25.itemAt(300f, 285f, buttonLeft = 580))
        assertEquals(5, q25.itemAt(600f, q25.buttonTop + 10f, buttonLeft = 580))
        assertNull(q25.itemAt(300f, q25.buttonTop + 10f, buttonLeft = 580)) // the row beside the button
        assertEquals(6, q25.itemAt(100f, q25.alarmTop + 5f, buttonLeft = 580))
        assertEquals(9, q25.itemAt(100f, q25.entryTop(2) + 1f, buttonLeft = 580))
        assertNull(q25.itemAt(100f, 10f, buttonLeft = 580)) // a title
        assertNull(q25.itemAt(100f, q25.boxEdge(0) + 1f, buttonLeft = 580)) // between two boxes
    }

    // A text keeps to its room, the status to what the name leaves.
    @Test fun theTextsKeepToTheirRoom() {
        assertEquals(696f, q25.titleRoom())
        assertEquals((q25.checkX - 6 - ConnectionsLayout.LABEL_X).toFloat(), q25.labelRoom())
        assertEquals(q25.statusRight - ConnectionsLayout.LABEL_X - 200f - 12, q25.statusRoom(200f))
        assertEquals(0f, q25.statusRoom(10_000f))
        assertEquals(720f - 12 - 60f - 12 - ConnectionsLayout.LABEL_X, q25.alarmRoom(60f))
        assertEquals(702f, q25.entryRoom())
    }
}
