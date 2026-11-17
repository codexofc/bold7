package dev.bold7.launcher.home

import dev.bold7.ui.Dir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Add Folder, Edit Folder and the chooser of a folder's icon against the simulator (sim-472 to sim-483).
class FolderBoxesTest {
    @Test fun addFolderOnTheNinetyNineHundred() {
        val d = FolderBoxLayout(640, 480)
        assertEquals(67, d.top) // the box's border: y 67 to 408
        assertEquals(408, d.top + d.height - 1)
        assertEquals(116, d.titleBaseline)
        assertEquals(listOf(126, 221), d.rules)
        assertEquals(128, d.iconY)
        assertEquals(d.left + 37, d.iconX) // 73 of a box at 36
        assertEquals(d.left + 151, d.nameX) // "Zz" from x 187
        assertEquals(184, d.nameBaseline)
        assertEquals(243, d.buttonTop(0))
        assertEquals(317, d.buttonTop(1))
        assertEquals(d.right - 20, d.ruleRight)
    }

    @Test fun addFolderOnTheSquareAndItsTouches() {
        val d = FolderBoxLayout(720, 720)
        assertEquals(36, d.left)
        assertEquals(648, d.width)
        assertEquals((720 - 342 - 4) / 2, d.top)
        assertEquals(FolderItem.ICON, d.itemAt(d.iconX + 10f, d.iconY + 10f))
        assertEquals(FolderItem.OK, d.itemAt(360f, d.buttonTop(0) + 5f))
        assertEquals(FolderItem.CANCEL, d.itemAt(360f, d.buttonTop(1) + 5f))
        assertNull(d.itemAt(d.nameX + 20f, d.nameBaseline - 5f))
    }

    @Test fun theTrackpadGoesFromTheNameToTheIconAndDownToTheButtons() {
        assertEquals(FolderItem.ICON, folderStep(FolderItem.NAME, Dir.LEFT))
        assertEquals(FolderItem.NAME, folderStep(FolderItem.ICON, Dir.RIGHT))
        assertEquals(FolderItem.OK, folderStep(FolderItem.ICON, Dir.DOWN))
        assertEquals(FolderItem.CANCEL, folderStep(FolderItem.OK, Dir.DOWN))
        assertEquals(FolderItem.CANCEL, folderStep(FolderItem.CANCEL, Dir.DOWN))
        assertEquals(FolderItem.OK, folderStep(FolderItem.CANCEL, Dir.UP))
        assertEquals(FolderItem.NAME, folderStep(FolderItem.OK, Dir.UP))
        assertEquals(FolderItem.NAME, folderStep(FolderItem.NAME, Dir.RIGHT))
    }

    @Test fun theChooserOnTheNinetyNineHundredsGrid() {
        // The 9900's box: x 54 to 585, the first icon at (75, 48), five to a row, 100 px apart.
        val c = IconChooserLayout(640, 480)
        assertEquals(54, c.left)
        assertEquals(532, c.width)
        assertEquals(75, c.iconX(0))
        assertEquals(c.top + 22, c.iconY(0))
        assertEquals(175, c.iconX(1))
        assertEquals(c.iconY(0) + 100, c.iconY(5))
        assertEquals(74, c.focusX(0))
        assertEquals(5, c.rows)
        // The square shows its five rows: 22 + 400 + 92 + 10.
        val s = IconChooserLayout(720, 720)
        assertEquals(524, s.height)
        assertEquals(7, s.itemAt(s.iconX(7) + 1f, s.iconY(7) + 91f))
        assertNull(s.itemAt(s.iconX(7) + 95f, s.iconY(7) + 10f)) // between two icons
        assertNull(s.itemAt(s.iconX(20) + 101f, s.iconY(20) + 10f)) // after the last one
    }

    @Test fun theChooserKeepsTheFocusOnItsIcons() {
        assertEquals(20, iconStep(20, Dir.RIGHT)) // sim-475
        assertEquals(4, iconStep(4, Dir.RIGHT))
        assertEquals(5, iconStep(5, Dir.LEFT))
        assertEquals(20, iconStep(17, Dir.DOWN))
        assertEquals(20, iconStep(20, Dir.DOWN))
        assertEquals(15, iconStep(20, Dir.UP))
        assertEquals(2, iconStep(2, Dir.UP))
        assertEquals(8, iconStep(3, Dir.DOWN))
    }
}
