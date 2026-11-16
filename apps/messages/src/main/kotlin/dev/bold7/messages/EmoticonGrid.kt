package dev.bold7.messages

import dev.bold7.ui.Dir

/**
 * Add Smiley's grid (sim-145, sim-147, C): [columns] across, filled row by row, the last row's
 * cells centred. Right at a row's end and left at its start stay; up and down go to the cell of
 * the nearest column (D for the partial row, whose moves are not captured).
 */
class EmoticonGrid(private val count: Int, private val columns: Int) {
    val rows = (count + columns - 1) / columns

    private fun row(i: Int) = i / columns
    private fun inRow(r: Int) = minOf(columns, count - r * columns)

    /** The column [i] is drawn in: the last row's are shifted to centre them. */
    fun column(i: Int): Int = i % columns + (columns - inRow(row(i))) / 2

    fun next(from: Int, dir: Dir): Int {
        val r = row(from)
        return when (dir) {
            Dir.LEFT -> if (from % columns == 0) from else from - 1
            Dir.RIGHT -> if (from % columns == inRow(r) - 1) from else from + 1
            Dir.UP -> if (r == 0) from else nearest(r - 1, column(from))
            Dir.DOWN -> if (r == rows - 1) from else nearest(r + 1, column(from))
        }
    }

    private fun nearest(r: Int, col: Int): Int {
        val first = r * columns
        return (first until first + inRow(r)).minBy { kotlin.math.abs(column(it) - col) }
    }
}
