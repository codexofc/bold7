package dev.bold7.calculator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeypadTest {
    private fun at(label: String) = Keypad.cells.indexOfFirst { it.label == label }

    @Test fun theKeysAreTheTenColumnsOfTheCapture() {
        // sim-249 (C): rows of ten, ten, eight (holes under the first and the eighth), and 0 alone.
        assertEquals(listOf(10, 10, 8, 1), (0 until Keypad.ROWS).map { r -> Keypad.cells.count { it.row == r } })
        assertEquals("±123CEC M-−+1/x".replace(" ", ""), Keypad.cells.filter { it.row == 0 }.joinToString("") { it.label })
        assertEquals("×456/MCMRMSM+DEL", Keypad.cells.filter { it.row == 1 }.joinToString("") { it.label })
        assertEquals("789√%,.=", Keypad.cells.filter { it.row == 2 }.joinToString("") { it.label })
        assertEquals(2, Keypad.cells[at("0")].col)
        assertTrue(Keypad.cells.all { it.col < Keypad.COLUMNS })
    }

    @Test fun theLabelsTakeTheCaptureColours() {
        for (l in listOf("CE", "C", "DEL")) assertEquals(Ink.ORANGE, Keypad.cells[at(l)].ink)
        for (l in listOf("M-", "MC", "MR", "MS", "M+")) assertEquals(Ink.BLUE, Keypad.cells[at(l)].ink)
        assertEquals(Ink.WHITE, Keypad.cells[at("7")].ink)
    }

    @Test fun everyKeyOfTheStateHasACell() {
        val keys = Keypad.cells.map { it.key }.toSet()
        for (d in 0..9) assertTrue(Key.Digit(d) in keys)
        for (k in listOf(Key.Equals, Key.Dot, Key.Clear, Key.ClearEntry, Key.Back, Key.MemStore, Key.MemSub, Key.Reciprocal, Key.Sqrt, Key.Percent)) assertTrue(k in keys)
    }

    @Test fun theTrackpadMovesAcrossAndDown() {
        assertEquals(at("8"), Keypad.move(at("7"), Dir.RIGHT))
        assertEquals(at("4"), Keypad.move(at("7"), Dir.UP))
        assertEquals(at("7"), Keypad.move(at("4"), Dir.DOWN))
        assertEquals(at("7"), Keypad.move(at("8"), Dir.LEFT))
    }

    @Test fun theMovesStopAtTheEdgesAndJumpTheHoles() {
        assertEquals(at("±"), Keypad.move(at("±"), Dir.LEFT))
        assertEquals(at("±"), Keypad.move(at("±"), Dir.UP))
        assertEquals(at("1/x"), Keypad.move(at("1/x"), Dir.RIGHT))
        assertEquals(at("0"), Keypad.move(at("0"), Dir.DOWN))
        // Left of 7 is a hole: nothing. The key under × is the nearest of the row below, 7.
        assertEquals(at("7"), Keypad.move(at("7"), Dir.LEFT))
        assertEquals(at("7"), Keypad.move(at("×"), Dir.DOWN))
        // The hole before = : left goes to the point, and up from = reaches DEL.
        assertEquals(at("."), Keypad.move(at("="), Dir.LEFT))
        assertEquals(at("DEL"), Keypad.move(at("="), Dir.UP))
    }

    @Test fun downFromTheNineReachesTheZeroRowsNearestKey() {
        assertEquals(at("0"), Keypad.move(at("8"), Dir.DOWN))
        assertEquals(at("0"), Keypad.move(at("9"), Dir.DOWN))
        assertEquals(at("8"), Keypad.move(at("0"), Dir.UP))
    }

    // Advance widths in em of DejaVu Sans, a wide sans, at 0.9: sim-249's "DEL" is 43 px at about 25 px (1,72 em, C), DejaVu's is 1,96.
    // The view also shrinks a label that its real font would not fit (CalcView, measureText).
    private val em = mapOf('C' to 0.70, 'E' to 0.63, 'M' to 0.86, 'R' to 0.69, 'S' to 0.63, 'D' to 0.77, 'L' to 0.56, '-' to 0.36, '+' to 0.84,
        '1' to 0.64, '/' to 0.34, 'x' to 0.59)

    @Test fun noLabelOverflowsItsKey() {
        for (c in Keypad.cells) {
            val size = Keypad.labelSize(c.label)
            val width = c.label.sumOf { ch -> (em[ch] ?: 0.84) * 0.9 } * size  // 0.84: the widest sign or digit
            assertTrue(width <= Keypad.LABEL_ROOM, "${c.label}: $width px in ${Keypad.LABEL_ROOM}")
        }
    }

    @Test fun lettersAreSmallerThanDigits() {
        assertTrue(Keypad.labelSize("DEL") < Keypad.labelSize("7"))
        assertTrue(Keypad.labelSize("1/x") < Keypad.labelSize("±"))
        assertTrue(Keypad.COLUMNS * Keypad.CELL_WIDTH <= 720)
    }

    @Test fun noKeyHasTheFocusAtFirstAndTheFirstMoveGivesIt() {
        // sim-249 (C): no key is blue when the page opens; any arrow only lands on 7.
        for (d in Dir.entries) assertEquals(at("7"), Keypad.step(Keypad.NO_FOCUS, d))
        assertEquals(at("8"), Keypad.step(at("7"), Dir.RIGHT))
    }

    @Test fun theNumberHasRoomInsideThePill() {
        // 720 px: the pill's 698 px less the cap, the text's margin and the memory's "M".
        assertEquals(720f - 22 - 20 - 20 - 40, Keypad.displayRoom(720, 40))
        assertTrue(Keypad.displayRoom(720, 0) > 600)
    }
}
