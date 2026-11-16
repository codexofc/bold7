package dev.bold7.ui

import dev.bold7.design.MenuDimens
import kotlin.math.ceil

// The menus' geometry, pure, in px of the view that shows them (docs/BBOS7-VISUEL.md §4): a pixel
// of the 9900 is one of the Q25, so the captures' measures are taken as they are (MenuLayoutTest).

/**
 * The full menu over a view [height] px high: a dark panel against the left edge, as wide as its
 * longest label ([widths], their advances), its rows of [MenuDimens.ROW] and a separator above
 * each row of [separatorAbove], sitting on the view's bottom (its own bottom off the screen), its
 * top border no higher than [minTop] (sim-44: 3 px under the app's bar) nor than ten rows on the
 * square (D). Longer, its rows scroll in the viewport under the top border. Never right of
 * [maxRight]: a longer label is cut by its view.
 *
 * Positions: [rowTop] in the panel, from its top border pixel; [rowY] and [lineY] in the view.
 */
class FullMenuLayout(widths: FloatArray, separatorAbove: BooleanArray, private val height: Int, minTop: Int, maxRight: Int = Int.MAX_VALUE) {
    private val rowTops = IntArray(widths.size)

    /** The x of the panel's right border pixel; its rows and their focus fill everything left of it. */
    val right: Int = minOf(maxRight, MenuDimens.TEXT_X + ceil(widths.maxOrNull() ?: 0f).toInt() + MenuDimens.PAD_RIGHT)

    /** The panel's content: its pads, rows and separators. */
    val total: Int

    /** The y of the panel's top border pixel. */
    val top: Int

    /** How far its rows scroll at most. */
    val maxScroll: Int

    init {
        var y = MenuDimens.PAD_TOP
        for (i in widths.indices) {
            if (i > 0 && separatorAbove[i]) y += MenuDimens.SEPARATOR
            rowTops[i] = y
            y += MenuDimens.ROW
        }
        total = y + MenuDimens.PAD_BOTTOM
        val tallest = MenuDimens.PAD_TOP + MenuDimens.VISIBLE_ROWS * MenuDimens.ROW + MenuDimens.PAD_BOTTOM
        top = maxOf(minTop, height - total, height - tallest)
        maxScroll = maxOf(0, total - (height - top))
    }

    val count: Int get() = rowTops.size

    /** The top of row [i] in the view, its rows scrolled by [scroll]. */
    fun rowY(i: Int, scroll: Int): Int = top + rowTops[i] - scroll

    /** The y of the separator's line above row [i], or null when it has none. */
    fun lineY(i: Int, scroll: Int): Int? {
        if (i == 0 || rowTops[i] - rowTops[i - 1] == MenuDimens.ROW) return null
        return rowY(i, scroll) - MenuDimens.SEPARATOR + 1
    }

    /** The viewport's top in the view: the rows show from there down to the view's bottom. */
    val viewportTop: Int get() = top + MenuDimens.VIEWPORT_TOP

    /**
     * The scroll that shows row [focused] from [scroll]: unchanged while it is in sight, else the
     * least move; the first row shows the top pad, the last the bottom one (sim-45).
     */
    fun scrollFor(focused: Int, scroll: Int): Int {
        if (focused == 0) return 0
        if (focused == count - 1) return maxScroll
        val r = rowTops[focused]
        val s = when {
            r - scroll < MenuDimens.VIEWPORT_TOP -> r - MenuDimens.VIEWPORT_TOP
            r + MenuDimens.ROW - scroll > height - top -> r + MenuDimens.ROW - (height - top)
            else -> scroll
        }
        return s.coerceIn(0, maxScroll)
    }

    /** The row at [y] of the view, null on a separator, a pad or out of the viewport. */
    fun rowAt(y: Float, scroll: Int): Int? {
        if (y < viewportTop || y >= height) return null
        val c = y - top + scroll
        return rowTops.indices.firstOrNull { c >= rowTops[it] && c < rowTops[it] + MenuDimens.ROW }
    }
}

/**
 * A submenu over a view [height] px high, [viewWidth] wide: its own panel at the right of the menu
 * whose right border is [menuRight], [MenuDimens.SUB_GAP] px after it, as wide as its longest label
 * ([widths], their advances) and kept [MenuDimens.SUB_EDGE] px from the screen's sides (left of the
 * menu when it does not fit at its right), sitting 5 px above the view's bottom, no higher than
 * [minTop] nor than [MenuDimens.VISIBLE_ROWS] rows; more rows scroll by whole rows (sim-223,
 * sim-251, C; the limits D). [right] is exclusive.
 */
class SubmenuLayout(widths: FloatArray, menuRight: Int, viewWidth: Int, height: Int, minTop: Int) {
    val count = widths.size
    val width: Int = minOf(MenuDimens.SUB_TEXT_X + ceil(widths.maxOrNull() ?: 0f).toInt() + MenuDimens.SUB_PAD_RIGHT, viewWidth - 2 * MenuDimens.SUB_EDGE)
    val left: Int = minOf(menuRight + MenuDimens.SUB_GAP, viewWidth - MenuDimens.SUB_EDGE - width).coerceAtLeast(MenuDimens.SUB_EDGE)
    val right: Int = left + width
    val bottom: Int = height - MenuDimens.SUB_BOTTOM

    /** The rows in sight at once. */
    val visible: Int = maxOf(1, minOf(count, MenuDimens.VISIBLE_ROWS, (bottom - minTop - 2 * MenuDimens.SUB_PAD) / MenuDimens.ROW))
    val top: Int = bottom - 2 * MenuDimens.SUB_PAD - visible * MenuDimens.ROW

    /** The first row's index shows from 0 to [maxFirst]: the submenu's scroll. */
    val maxFirst: Int get() = count - visible

    /** The top of row [i] with row [first] at the top. */
    fun rowY(i: Int, first: Int): Int = top + MenuDimens.SUB_PAD + (i - first) * MenuDimens.ROW

    /** The first row that shows [focused] from [first]: unchanged while in sight, else the least move. */
    fun scrollFor(focused: Int, first: Int): Int = when {
        focused < first -> focused
        focused >= first + visible -> focused - visible + 1
        else -> first
    }.coerceIn(0, maxFirst)

    /** The row at [y] with row [first] on top, null on a pad or off the panel. */
    fun rowAt(y: Float, first: Int): Int? {
        val r = Math.floorDiv((y - top - MenuDimens.SUB_PAD).toInt(), MenuDimens.ROW)
        return if (y >= top + MenuDimens.SUB_PAD && r in 0 until visible) first + r else null
    }
}

/**
 * The short menu of [cells] cells (its actions and Full Menu) over a view [width] x [height]: a box
 * of [MenuDimens.SHORT_COLUMNS] columns, centred, as sim-17 and sim-52 sit 2 px above the middle of
 * the 9900's screen with their shadow (C, D on the square).
 */
class ShortMenuLayout(val cells: Int, width: Int, height: Int) {
    val rows: Int = (cells + MenuDimens.SHORT_COLUMNS - 1) / MenuDimens.SHORT_COLUMNS
    val boxWidth: Int = MenuDimens.SHORT_WIDTH
    val boxHeight: Int = 2 * MenuDimens.SHORT_FRAME + rows * MenuDimens.SHORT_ROW
    val left: Int = (width - boxWidth) / 2
    val top: Int = (height - boxHeight - 4) / 2

    fun cellLeft(i: Int): Int = left + MenuDimens.SHORT_FRAME + i % MenuDimens.SHORT_COLUMNS * MenuDimens.SHORT_COLUMN
    fun cellTop(i: Int): Int = top + MenuDimens.SHORT_FRAME + i / MenuDimens.SHORT_COLUMNS * MenuDimens.SHORT_ROW

    /** The cell at ([x], [y]), null off the box or on an empty cell of its last row. */
    fun cellAt(x: Float, y: Float): Int? {
        val cx = x - left - MenuDimens.SHORT_FRAME
        val cy = y - top - MenuDimens.SHORT_FRAME
        if (cx < 0 || cy < 0) return null
        val col = (cx / MenuDimens.SHORT_COLUMN).toInt()
        val row = (cy / MenuDimens.SHORT_ROW).toInt()
        if (col >= MenuDimens.SHORT_COLUMNS || row >= rows) return null
        return (row * MenuDimens.SHORT_COLUMNS + col).takeIf { it < cells }
    }
}

/** The trackpad's move from cell [from] of a short menu of [cells] cells: on its grid, stopping at its edges and before an empty cell (D). */
fun shortMove(from: Int, cells: Int, dir: Dir): Int {
    val cols = MenuDimens.SHORT_COLUMNS
    val to = when (dir) {
        Dir.LEFT -> if (from % cols == 0) from else from - 1
        Dir.RIGHT -> if (from % cols == cols - 1) from else from + 1
        Dir.UP -> from - cols
        Dir.DOWN -> from + cols
    }
    return if (to in 0 until cells) to else from
}

/** [text] cut into lines no wider than [max] at its spaces, a word wider than [max] alone on its line; "\n" breaks. */
fun wrap(text: String, max: Float, measure: (String) -> Float): List<String> = text.split('\n').flatMap { paragraph ->
    if (measure(paragraph) <= max) return@flatMap listOf(paragraph)
    val lines = mutableListOf<String>()
    var line = ""
    for (word in paragraph.split(' ')) {
        val longer = if (line.isEmpty()) word else "$line $word"
        if (line.isNotEmpty() && measure(longer) > max) {
            lines += line
            line = word
        } else {
            line = longer
        }
    }
    lines + line
}
