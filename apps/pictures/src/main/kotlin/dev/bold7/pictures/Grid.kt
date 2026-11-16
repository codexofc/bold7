package dev.bold7.pictures

// The thumbnails of a category, pure (docs/BBOS7-VISUEL.md §37.2): the 9900 draws 4 columns of
// 160 px squares on its 640 px (sim-345, C); the Q25's 720 px keeps 4 columns, each 180 px (D: the
// columns of the 9900, no picture smaller than there), and more rows show. The trackpad moves the
// focus a cell at a time.

/** The width of a column on the 9900 (sim-345, C). */
const val NATIVE_CELL = 160

/** A grid of [count] cells on a screen [width] wide and [height] high (under the title, over the bar). */
data class GridLayout(val width: Int, val height: Int, val count: Int) {
    val columns: Int get() = maxOf(1, width / NATIVE_CELL)
    val cell: Int get() = width / columns
    val rows: Int get() = (count + columns - 1) / columns

    /** The first row to draw so that [focus] shows, from the [top] row shown before: it scrolls only as far as it must (D). */
    fun topFor(focus: Int, top: Int): Int {
        val row = focus / columns
        val visible = maxOf(1, height / cell)
        return when {
            row < top -> row
            row >= top + visible -> row - visible + 1
            else -> top
        }.coerceIn(0, maxOf(0, rows - 1))
    }
}

/** A step of the trackpad over the grid. */
enum class Step { UP, DOWN, LEFT, RIGHT }

/**
 * Where the focus goes from [focus] among [count] cells of [columns]: left and right go to the
 * previous and the next picture, across the end of a row (D), up and down a row; down from the
 * last full row stops on the last picture (D); nothing beyond the ends.
 */
fun move(focus: Int, count: Int, columns: Int, step: Step): Int {
    if (count == 0) return 0
    val next = when (step) {
        Step.LEFT -> focus - 1
        Step.RIGHT -> focus + 1
        Step.UP -> if (focus - columns >= 0) focus - columns else focus
        Step.DOWN -> when {
            focus + columns < count -> focus + columns
            focus / columns < (count - 1) / columns -> count - 1
            else -> focus
        }
    }
    return next.coerceIn(0, count - 1)
}
