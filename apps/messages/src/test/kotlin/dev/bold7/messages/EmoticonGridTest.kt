package dev.bold7.messages

import dev.bold7.ui.Dir
import kotlin.test.Test
import kotlin.test.assertEquals

class EmoticonGridTest {
    @Test fun theGridOfSevenColumnsAndItsCentredLastRow() {
        val g = EmoticonGrid(45, 7)
        assertEquals(7, g.rows)
        // Right at a row's end stays there (sim-147, C); left at its start too.
        assertEquals(6, g.next(6, Dir.RIGHT))
        assertEquals(7, g.next(7, Dir.LEFT))
        assertEquals(1, g.next(0, Dir.RIGHT))
        assertEquals(14, g.next(7, Dir.DOWN))
        assertEquals(3, g.next(10, Dir.UP))
        assertEquals(3, g.next(3, Dir.UP))
        // The last row's three are centred in columns 2 to 4 (sim-147, C): down goes to the nearest.
        assertEquals(2, g.column(42))
        assertEquals(42, g.next(35, Dir.DOWN))
        assertEquals(43, g.next(38, Dir.DOWN))
        assertEquals(44, g.next(41, Dir.DOWN))
        assertEquals(37, g.next(42, Dir.UP))
        assertEquals(44, g.next(44, Dir.DOWN))
    }
}
