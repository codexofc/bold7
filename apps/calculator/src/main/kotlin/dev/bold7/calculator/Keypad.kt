package dev.bold7.calculator

enum class Dir { UP, DOWN, LEFT, RIGHT }

/** What a key looks like: the digits' lighter image, the others' darker one (calculator_key_landscape_numeric and the plain one, A). */
enum class Look { DIGIT, OTHER }

/** The colour of a key's label: white, orange for the clears, blue for the memory (sim-249, C). */
enum class Ink { WHITE, ORANGE, BLUE }

class Cell(val label: String, val key: Key, val row: Int, val col: Int, val look: Look = Look.OTHER, val ink: Ink = Ink.WHITE)

/**
 * The keys of the calculator as sim-249 (C) shows them, ten columns on four rows with holes, and the
 * trackpad's moves over them. Pure.
 */
object Keypad {
    const val ROWS = 4
    const val COLUMNS = 10

    // The Q25 is 720 px wide, the 9900 640: every measure of sim-249 (C) is taken at 720/640 = 1.125.
    // Measured on sim-249: key image 62 x 85 (A, 5 px of the ground around a 52 x 77 key), pitch 62 x 85,
    // first image at x 10, y 130; digits 27 px high (about 37 px), letters 18 px high (about 25 px).
    /** One key's cell: the image is drawn at this size, its own 5 px edge being the gap between keys. */
    const val CELL_WIDTH = 70
    const val CELL_HEIGHT = 96
    /** The 5 px edge of the key image, scaled: the key's visible face is the cell minus twice this. */
    const val EDGE = 5
    const val DIGIT_SIZE = 42
    const val LETTER_SIZE = 28

    /** The small size for what is spelled with letters (CE, C, M-, MC, DEL, 1/x), the big one for digits and signs (sim-249). */
    fun labelSize(label: String) = if (label.any { it.isLetter() } || label == "1/x") LETTER_SIZE else DIGIT_SIZE

    /** The room a label has inside its key's face: the face minus an inner margin on each side. */
    const val INNER_MARGIN = 4
    const val LABEL_ROOM = CELL_WIDTH - 2 * EDGE - 2 * INNER_MARGIN

    private val rows: List<List<Pair<String, Key>?>> = listOf(
        listOf("±" to Key.Negate, "1" to Key.Digit(1), "2" to Key.Digit(2), "3" to Key.Digit(3), "CE" to Key.ClearEntry, "C" to Key.Clear,
            "M-" to Key.MemSub, "−" to Key.Op(Op.SUB), "+" to Key.Op(Op.ADD), "1/x" to Key.Reciprocal),
        listOf("×" to Key.Op(Op.MUL), "4" to Key.Digit(4), "5" to Key.Digit(5), "6" to Key.Digit(6), "/" to Key.Op(Op.DIV), "MC" to Key.MemClear,
            "MR" to Key.MemRecall, "MS" to Key.MemStore, "M+" to Key.MemAdd, "DEL" to Key.Back),
        listOf(null, "7" to Key.Digit(7), "8" to Key.Digit(8), "9" to Key.Digit(9), "√" to Key.Sqrt, "%" to Key.Percent,
            "," to Key.Dot, "." to Key.Dot, null, "=" to Key.Equals),
        listOf(null, null, "0" to Key.Digit(0)),
    )

    val cells: List<Cell> = buildList {
        for ((r, row) in rows.withIndex()) for ((c, k) in row.withIndex()) {
            val (l, key) = k ?: continue
            val ink = when (key) {
                Key.Clear, Key.ClearEntry, Key.Back -> Ink.ORANGE
                Key.MemSub, Key.MemClear, Key.MemRecall, Key.MemStore, Key.MemAdd -> Ink.BLUE
                else -> Ink.WHITE
            }
            add(Cell(l, key, r, c, look = if (key is Key.Digit || key == Key.Dot) Look.DIGIT else Look.OTHER, ink = ink))
        }
    }

    /** No key has the focus when the page opens (sim-249, C: no key is blue). */
    const val NO_FOCUS = -1

    /** The key the first arrow lands on: 7 (D, the capture shows no focus to move from). */
    val start: Int = cells.indexOfFirst { it.label == "7" }

    /** The trackpad's move from [from], which may be [NO_FOCUS]: the first move only gives the focus. */
    fun step(from: Int, dir: Dir): Int = if (from == NO_FOCUS) start else move(from, dir)

    /** The room the number has inside the display's pill at [width] px: the pill's margins, its cap and the text's right margin. */
    fun displayRoom(width: Int, left: Int): Float = (width - 2 * DISPLAY_MARGIN - CAP - TEXT_RIGHT - left).toFloat()
    const val DISPLAY_MARGIN = 11
    const val TEXT_RIGHT = 20
    private const val CAP = 20

    /**
     * The key reached from [from] by the trackpad: left and right the nearest in the row, up and
     * down the nearest row that has a key, the one closest to the column; stopping at the edges.
     */
    fun move(from: Int, dir: Dir): Int {
        val c = cells[from]
        val target = when (dir) {
            Dir.LEFT -> cells.filter { it.row == c.row && it.col < c.col }.maxByOrNull { it.col }
            Dir.RIGHT -> cells.filter { it.row == c.row && it.col > c.col }.minByOrNull { it.col }
            Dir.UP, Dir.DOWN -> {
                val up = dir == Dir.UP
                val rows = cells.filter { if (up) it.row < c.row else it.row > c.row }
                val row = (if (up) rows.maxOfOrNull { it.row } else rows.minOfOrNull { it.row })
                cells.filter { it.row == row }.minByOrNull { Math.abs(it.col - c.col) }
            }
        }
        return target?.let(cells::indexOf) ?: from
    }
}
