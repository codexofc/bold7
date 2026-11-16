package dev.bold7.launcher.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// sim-28 and sim-29, 640x480 (C): the box's border pixels x 64 to 575 and y 32 to 443, the title's
// baseline 71 and its line at y 80, rows of 53 from y 82 (Normal's blue band 135 to 187), the list's
// line at y 367 under the six rows' window of 285, the button's border pixels y 375 to 425 and
// x 82 to 558. The popup shows the seven rows at once on the Q25 (D).
class ProfilePopupLayoutTest {
    // The window of sim-28: 285 px, i.e. 5.4 rows: the layout of 5 whole rows plus 20 px is not
    // what the Q25 draws, so the capture's numbers are checked through the fixed parts only.
    private val l = ProfilePopupLayout(screenWidth = 640, rows = 7)

    @Test fun theBoxIs512WideCentredAndAnchoredAtTheCapturesTop() {
        assertEquals(64, l.left)
        assertEquals(512, l.width)
        assertEquals(32, l.top)
        assertEquals(104, ProfilePopupLayout(720, 7).left)
    }

    @Test fun theTitleAndItsLineAreSim28s() {
        assertEquals(71, l.titleBaseline)
        assertEquals(80, l.titleLine)
        assertEquals(78, l.bandLeft)
        assertEquals(485, l.bandWidth)
    }

    @Test fun rowsAre53FromY82() {
        assertEquals(82, l.rowTop(0))
        assertEquals(135, l.rowTop(1))
        assertEquals(82 + 6 * 53, l.rowTop(6))
        assertEquals(53, ProfilePopupLayout.ROW)
    }

    @Test fun theButtonFollowsTheListLineBy8AndTheBoxEnds18UnderIt() {
        assertEquals(82 + 7 * 53, l.listLine)
        assertEquals(l.listLine + 8, l.buttonTop)
        assertEquals(82, l.buttonLeft)
        assertEquals(477, l.buttonWidth)
        assertEquals(l.buttonTop + 50 + 18, l.top + l.height - 1)
        // With the capture's own 285 px window the same rules give sim-28's 375 and 443.
        val window = ProfilePopupLayout(640, rows = 0, listHeight = 285)
        assertEquals(367, window.listLine)
        assertEquals(375, window.buttonTop)
        assertEquals(443, window.top + window.height - 1)
    }

    @Test fun aTouchFindsItsRowOrTheButton() {
        assertEquals(0, l.itemAt(300f, 100f))
        assertEquals(1, l.itemAt(300f, 135f))
        assertEquals(6, l.itemAt(300f, l.rowTop(6) + 52f))
        assertEquals(7, l.itemAt(300f, l.buttonTop + 10f))
        assertNull(l.itemAt(300f, 50f))
        assertNull(l.itemAt(10f, 100f))
        assertNull(l.itemAt(300f, l.listLine + 2f))
    }

    // The texts stay in their frame (the name before the tick, the label in the button).
    @Test fun theTextsKeepToTheirRoom() {
        val q = ProfilePopupLayout(720, 7)
        assertEquals(485f, q.titleRoom) // the band: 512 - 14 - 13
        assertEquals((q.tickRight - 32 - q.textX).toFloat(), q.nameRoom)
        assertTrue(q.textX + q.nameRoom <= q.tickRight - 20)
        assertEquals(q.buttonWidth - 40f, q.buttonRoom)
    }
}
