package dev.bold7.ui

// A choice field's open list, pure: no android.*, tested on the JVM (ChoiceTest).

/** The open list of a choice field: [count] values, [chosen] under the trackpad's focus. */
data class ChoiceList(val count: Int, val chosen: Int) {
    /** The trackpad moves the focus one value, and stops at the ends. */
    fun step(down: Boolean) = copy(chosen = (chosen + if (down) 1 else -1).coerceIn(0, count - 1))

    /**
     * The list's top, its rows [row] px: the chosen value on the field's row ([rowTop]), moved to
     * stay between [floor] (under the screen's title bar) and the bottom of a screen [screen] px high
     * (D); taller than that room, it keeps the chosen row in place as far as its ends allow.
     */
    fun top(rowTop: Int, row: Int, screen: Int, floor: Int = 0): Int {
        val height = count * row
        val top = rowTop - chosen * row
        return if (height <= screen - floor) top.coerceIn(floor, screen - height) else top.coerceIn(screen - height, floor)
    }
}
