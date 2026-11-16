package dev.bold7.ui

/**
 * The strip of a date or time box, pure (sim-156, sim-157, C, 640x480 pixels, the Q25's own): the
 * grey strip runs [TAIL] px past its last column, so the focused last column's pill, which reaches
 * the strip's end (x 302 to 476 on a column of 302 to 460), is wider than the blue column under
 * it; strip and columns are centred in the box (164 to 476 in the box of 127 to 513: 37 px each side).
 */
object SpinLayout {
    /** What the strip and the focused pill run past the last column. */
    const val TAIL = 16

    /** The column's height above its focused row and below it (sim-156: y 124 to 185, 234 to 294). */
    const val ABOVE = 61
    const val BELOW = 60

    /** The columns' left and right in a box [boxWidth] wide, [widths] wide each after [gaps] px (their separator), centred with the tail. */
    fun columns(boxWidth: Int, widths: List<Int>, gaps: List<Int>): List<Pair<Int, Int>> {
        var x = (boxWidth - widths.sum() - gaps.sum() - TAIL) / 2
        return widths.indices.map { i ->
            x += gaps[i]
            (x to x + widths[i]).also { x += widths[i] }
        }
    }

    /** The strip's right edge, past the last column. */
    fun stripEnd(columns: List<Pair<Int, Int>>): Int = columns.last().second + TAIL

    /** The focused column's cell right edge: the strip's end for the last column, its own for another. */
    fun pillEnd(columns: List<Pair<Int, Int>>, focus: Int): Int = if (focus == columns.lastIndex) stripEnd(columns) else columns[focus].second

    /** border_spinboxbutton~focus.png's transparent margin and its border, each side (A). */
    const val CELL_MARGIN = 3
    const val CELL_BORDER = 2

    /**
     * The left and right of the focused cell's image, drawn clipped to the column (its left) and
     * [pillEnd]: a full blue cell, its border hidden, but at the strip's ends, where it rounds like
     * the strip (sim-157, sim-159, sim-234, C; it was a pill inset by its margin and border).
     */
    fun cell(columns: List<Pair<Int, Int>>, focus: Int): Pair<Int, Int> {
        fun out(end: Boolean) = if (end) CELL_MARGIN else CELL_MARGIN + CELL_BORDER
        return columns[focus].first - out(focus == 0) to pillEnd(columns, focus) + out(focus == columns.lastIndex)
    }

    /** The blue column's colours from its top, as (offset in px, colour) read down sim-156's column of 61 + 49 + 60 px. */
    val STOPS: List<Pair<Int, Int>> = listOf(
        0 to 0xFF082042.toInt(), 6 to 0xFF0B2C5B.toInt(), 16 to 0xFF124086.toInt(), 26 to 0xFF1855B0.toInt(), 36 to 0xFF1F69DA.toInt(),
        46 to 0xFF257CFF.toInt(), 116 to 0xFF257CFF.toInt(), 136 to 0xFF1D65D1.toInt(), 146 to 0xFF1751A7.toInt(), 156 to 0xFF113C7C.toInt(),
        166 to 0xFF0A2852.toInt(), 170 to 0xFF082042.toInt(),
    )

    /** The neighbours' text on the column (sim-157: the brightest pixel of "ON" and "OFF"). */
    const val NEIGHBOUR_TEXT = 0xFF73A3F7.toInt()
}
