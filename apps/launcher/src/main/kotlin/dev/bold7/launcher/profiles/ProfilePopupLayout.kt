package dev.bold7.launcher.profiles

/**
 * "Select a Profile" (docs/BBOS7-VISUEL.md §8), pure: positions in pixels of the 9900, which the
 * Q25 takes as they are, from sim-28 and sim-29 (C). The box is 512 wide, centred on the screen
 * (D, sim-28's 640 gave x 64), anchored at y 32; [rows] rows of 53 under the title, the list's line,
 * the button 8 under it. The capture scrolls its seventh row away in a window of 285 px; the Q25's
 * 720 px show all seven (D), so [listHeight] is the rows' own unless given.
 */
class ProfilePopupLayout(screenWidth: Int, rows: Int, listHeight: Int = rows * ROW) {
    val rowCount = rows
    val width = WIDTH
    val left = (screenWidth - WIDTH) / 2
    val top = TOP
    val titleBaseline = TOP + 39
    val titleLine = TOP + 48

    /** The rows' blue band and the title's own x, inside the box (sim-28). */
    val bandLeft = left + 14
    val bandWidth = WIDTH - 14 - 13
    val textX = left + 75
    val iconX = bandLeft
    val tickRight = left + 484

    private val rowsTop = TOP + 50
    fun rowTop(i: Int) = rowsTop + i * ROW

    val listLine = rowsTop + listHeight
    val buttonTop = listLine + 8
    val buttonLeft = left + 18
    val buttonWidth = WIDTH - 18 - 17
    val height = buttonTop + BUTTON_HEIGHT - 1 + 18 - TOP + 1

    // The room each text gets (fit of core/ui; ProfilePopupLayoutTest): the title in the band, a name
    // between its icon's text x and the tick (20 px wide, 12 px of air), the button's label inside its edges.
    val titleRoom = bandWidth.toFloat()
    val nameRoom = (tickRight - 20 - 12 - textX).toFloat()
    val buttonRoom = (buttonWidth - 2 * BUTTON_PAD).toFloat()

    /** The row at ([x], [y]), the button after the last row, or null. */
    fun itemAt(x: Float, y: Float): Int? {
        if (x >= bandLeft && x < bandLeft + bandWidth && y >= rowsTop && y < listLine) return ((y - rowsTop) / ROW).toInt().takeIf { it < rowCount }
        if (x >= buttonLeft && x < buttonLeft + buttonWidth && y >= buttonTop && y < buttonTop + BUTTON_HEIGHT) return rowCount
        return null
    }

    companion object {
        const val ROW = 53
        const val WIDTH = 512
        const val TOP = 32
        const val BUTTON_HEIGHT = 51
        const val BUTTON_PAD = 20
    }
}
