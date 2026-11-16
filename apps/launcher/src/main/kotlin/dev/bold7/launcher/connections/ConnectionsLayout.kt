package dev.bold7.launcher.connections

/**
 * Manage Connections' panel (docs/BBOS7-VISUEL.md §6), pure: positions in pixels of the 9900, which
 * the Q25 takes as they are, y from the banner's bottom. Measured on sim-23, sim-24 and sim-112 to
 * sim-118 (C): three group boxes (border_groupboxfull, A) one under the other, 3 px apart, each its
 * title bar (border_groupboxtitlebar, A, its line 45 under the box's top); Manage Connections' rows
 * of 60 then All Off's of 61, Set Clock Alarm's row of 44, Options and Status' rows of 81, a 1 px
 * line between two rows. The Q25's 720 px are wider (the right-hand parts keep their distance to
 * the right edge) and show more of the panel, which scrolls (D). [boxes] check boxes.
 *
 * Items, as [Connections.items]: 0 the banner, then the boxes, All Off, Alarm, the entries.
 */
class ConnectionsLayout(val width: Int, val boxes: Int) {
    /** The box's white from x 5 to [right] (634 on 640); lines and bands from 9 to [right] - 4. */
    val right = width - 6
    val lineLeft = 9
    val lineRight = width - 10 // the last column, x 630 on 640

    /** The check box, 12 from the edge as Options' (sim-23: x 601 to 627); the status ends 6 left of it. */
    val checkX = width - 12 - 27
    val statusRight = checkX - 6

    /** All Off: its right edge 13 from the screen's (627), its top 5 under its row's line. */
    val buttonRight = width - 13

    fun rowTop(k: Int) = TITLE + k * CHECK_ROW
    val boxLines = (1..boxes).map(::rowTop)
    val buttonRowTop = rowTop(boxes)
    val buttonTop = buttonRowTop + 5

    private val edges = IntArray(3)
    private val tops = IntArray(3)

    init {
        edges[0] = buttonRowTop + BUTTON_ROW
        tops[1] = edges[0] + GAP
        edges[1] = tops[1] + TITLE + ALARM_ROW
        tops[2] = edges[1] + GAP
        edges[2] = tops[2] + TITLE + ENTRIES.size * ENTRY_ROW
    }

    fun boxTop(box: Int) = tops[box]

    // The room each text gets (ConnectionsLayoutTest): what [fit] cuts it to.
    /** A group's title, between its margins. */
    fun titleRoom(): Float = (width - 2 * TITLE_X).toFloat()

    /** A connection's name, before its status and its box. */
    fun labelRoom(): Float = (checkX - 6 - LABEL_X).toFloat()

    /** The status (the network's name), what the name of [labelWidth] px leaves, 12 px of air between. */
    fun statusRoom(labelWidth: Float): Float = (statusRight - LABEL_X - labelWidth - 12).coerceAtLeast(0f)

    /** The alarm's name, before its value of [valueWidth] px at the right (12 px margin and air). */
    fun alarmRoom(valueWidth: Float): Float = (width - 12 - valueWidth - 12 - LABEL_X).coerceAtLeast(0f)

    /** An entry's title or subtitle, between the margins of the row. */
    fun entryRoom(): Float = (width - 2 * TEXT_X).toFloat()

    /** The box's lower border's first row (#C6C6C6), 2 rows of shade under it. */
    fun boxEdge(box: Int) = edges[box]
    fun titleLine(box: Int) = tops[box] + TITLE - 1
    fun titleBaseline(box: Int) = tops[box] + TITLE_BASELINE

    val alarmTop = tops[1] + TITLE
    fun entryTop(k: Int) = tops[2] + TITLE + k * ENTRY_ROW
    val entryLines = (1 until ENTRIES.size).map(::entryTop)

    /** The panel's height: the last box and its shade. */
    val height = edges[2] + GAP

    /** Item [i]'s rows, top inclusive, bottom exclusive; null for the banner. */
    fun slot(i: Int): Pair<Int, Int>? = when {
        i <= 0 -> null
        i <= boxes -> rowTop(i - 1) to rowTop(i)
        i == boxes + 1 -> buttonRowTop to edges[0]
        i == boxes + 2 -> alarmTop to edges[1]
        i - boxes - 3 < ENTRIES.size -> (i - boxes - 3).let { k -> entryTop(k) to if (k == ENTRIES.size - 1) edges[2] else entryTop(k + 1) }
        else -> null
    }

    /** The blue band of a focused row: 2 px inside its slot at the top and the bottom (sim-114, C). */
    fun band(slot: Pair<Int, Int>) = slot.first + 2 to slot.second - 2

    /**
     * The scroll that shows item [i] in a window of [window] px, from [current]: unchanged if it
     * shows, else just enough (sim-115, sim-118, C); the last one shows the panel's end (sim-116).
     */
    fun scrollFor(i: Int, current: Int, window: Int): Int {
        val (top, slotBottom) = slot(i) ?: return current
        val bottom = if (i == boxes + 2 + ENTRIES.size) height else slotBottom
        return when {
            top < current -> top
            bottom > current + window -> bottom - window
            else -> current
        }.coerceIn(0, (height - window).coerceAtLeast(0))
    }

    /** The item under a touch at ([x], [y]) of the panel, All Off only on its button (from [buttonLeft]); null elsewhere. */
    fun itemAt(x: Float, y: Float, buttonLeft: Int): Int? {
        for (i in 1..boxes + 2 + ENTRIES.size) {
            val (top, bottom) = slot(i) ?: continue
            if (y < top || y >= bottom) continue
            if (i == boxes + 1 && (x < buttonLeft || x >= buttonRight || y < buttonTop || y >= buttonTop + BUTTON_HEIGHT)) return null
            return i
        }
        return null
    }

    companion object {
        /** The title bar, its line included (sim-23: 93 to 138). */
        const val TITLE = 46
        /** Bold 23 px, grey, on 36 under the box's top, at x 12 ("Manage Connections" from x 13). */
        const val TITLE_BASELINE = 36
        const val TITLE_X = 12
        const val GAP = 3

        /** A check box's row (sim-23): its glyph's 40 px cell at x 9, centred; its label at x 55 on 41. */
        const val CHECK_ROW = 60
        const val CHECK_BASELINE = 41
        const val ICON = 40
        const val ICON_X = 9
        const val LABEL_X = 55

        /** All Off's row, its line included, and its button: 51 high, its label's width plus 20 each side (sim-23: 118 for "All Off"; sim-117: 138 for "Restore"). */
        const val BUTTON_ROW = 61
        const val BUTTON_HEIGHT = 51
        const val BUTTON_PAD = 20

        /** Set Clock Alarm's row (sim-23): "Alarm" on 33, "OFF" ending 12 from the edge. */
        const val ALARM_ROW = 44
        const val ALARM_BASELINE = 33

        /** Options and Status (sim-24): title on 36 and grey subtitle on 68, at x 9. */
        const val ENTRY_ROW = 81
        const val ENTRY_TITLE_BASELINE = 36
        const val ENTRY_SUBTITLE_BASELINE = 68
        const val TEXT_X = 9
    }
}
