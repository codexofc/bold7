package dev.bold7.launcher.home

import dev.bold7.design.DialogDimens
import dev.bold7.ui.Dir
import kotlin.math.roundToInt

// Add Folder and Edit Folder, and their chooser of icons, pure: where things are and where the
// trackpad goes (sim-472 to sim-483, C). Positions are border pixels, inclusive.

/** The image of a folder's [icon]: the theme's folder.png, or foldernn.png of _bs1 (tools/import-assets.sh, accueil). */
internal fun folderIcon(icon: Int): String = if (icon == 0) "home_folder" else "home_folder%02d".format(icon)

/** The focusable items of Add Folder and Edit Folder, in the order the trackpad goes down. */
internal object FolderItem {
    const val ICON = 0
    const val NAME = 1
    const val OK = 2
    const val CANCEL = 3
}

/**
 * Where a trackpad step [dir] from [from] leads in Add Folder (sim-472, sim-473, C: from the empty
 * name, left reaches the icon). The rest is D: right from the icon to the name, down to the
 * buttons, up from the first button back to the name. A step that stays on the name moves its
 * caret instead (sim-478's first try, C: left in a typed name moved the caret).
 */
internal fun folderStep(from: Int, dir: Dir): Int = when (from) {
    FolderItem.ICON -> when (dir) {
        Dir.RIGHT -> FolderItem.NAME
        Dir.DOWN -> FolderItem.OK
        else -> from
    }
    FolderItem.NAME -> when (dir) {
        Dir.LEFT -> FolderItem.ICON
        Dir.DOWN -> FolderItem.OK
        else -> from
    }
    FolderItem.OK -> when (dir) {
        Dir.UP -> FolderItem.NAME
        Dir.DOWN -> FolderItem.CANCEL
        else -> from
    }
    else -> if (dir == Dir.UP) FolderItem.OK else from
}

/**
 * Add Folder's box on a screen [screenWidth] x [screenHeight] (sim-472, sim-481, C, on a box
 * whose border is x 36 to 603 and y 67 to 408): the title, a rule, the folder's icon at its
 * size and the name right of it, a rule, the two buttons stacked. As wide as the text box
 * ([dev.bold7.ui.TextDialogLayout], D on the square), centred as the other dialogs.
 */
internal class FolderBoxLayout(screenWidth: Int, screenHeight: Int) {
    private val margin = (screenWidth * DialogDimens.SCREEN_MARGIN_PERCENT / 100f).roundToInt()
    val width: Int = screenWidth - 2 * margin
    val left: Int = margin
    val height: Int = 342
    val top: Int = (screenHeight - height - 4) / 2
    val right: Int get() = left + width - 1

    val titleX: Int get() = left + 20
    val titleBaseline: Int get() = top + 49

    /** The two rules, 1 px of #878787 from 20 px in on each side, under the title and over the buttons. */
    val ruleLeft: Int get() = left + 20
    val ruleRight: Int get() = right - 20
    val rules: List<Int> get() = listOf(top + 59, top + 154)

    /** The icon, 92x92, and its focus, 106x92 (the home's focus stretched). */
    val iconX: Int get() = left + 37
    val iconY: Int get() = top + 61
    val focusLeft: Int get() = left + 30
    val focusRight: Int get() = left + 135

    /** The name: its text from x [nameX], on the icon's middle line. */
    val nameX: Int get() = left + 151
    val nameBaseline: Int get() = top + 117
    val nameRight: Int get() = ruleRight

    /** The buttons, as wide as a dialog's ([DialogDimens.BUTTON_RATIO]), the first 22 px under the second rule. */
    val buttonWidth: Int = (width * DialogDimens.BUTTON_RATIO).roundToInt()
    val buttonLeft: Int = left + (width - buttonWidth) / 2
    fun buttonTop(i: Int): Int = top + 176 + i * DialogDimens.BUTTON_PITCH

    /** The item at ([x], [y]): the icon, a button, or null (the name takes its own touches). */
    fun itemAt(x: Float, y: Float): Int? = when {
        x >= focusLeft && x <= focusRight && y >= iconY && y < iconY + 92 -> FolderItem.ICON
        x < buttonLeft || x >= buttonLeft + buttonWidth -> null
        y >= buttonTop(0) && y < buttonTop(0) + DialogDimens.BUTTON_HEIGHT -> FolderItem.OK
        y >= buttonTop(1) && y < buttonTop(1) + DialogDimens.BUTTON_HEIGHT -> FolderItem.CANCEL
        else -> null
    }
}

/**
 * The chooser of a folder's icon (sim-474, sim-475, C): [FOLDER_ICONS] images in rows of
 * [COLUMNS], 100 px apart each way, the first 21 px right of the box's border and 22 under it,
 * 19 px between the last column and the border, 10 under the last row. The 9900 shows four rows
 * and scrolls to the fifth; the square has room for all five (D), so nothing scrolls.
 */
internal class IconChooserLayout(screenWidth: Int, screenHeight: Int) {
    val rows: Int = (FOLDER_ICONS + COLUMNS - 1) / COLUMNS
    val width: Int = 21 + (COLUMNS - 1) * PITCH + ICON + 19
    val height: Int = 22 + (rows - 1) * PITCH + ICON + 10
    val left: Int = (screenWidth - width) / 2
    val top: Int = (screenHeight - height - 4) / 2

    fun iconX(i: Int): Int = left + 21 + (i % COLUMNS) * PITCH
    fun iconY(i: Int): Int = top + 22 + (i / COLUMNS) * PITCH

    /** The focus, 92x92, 1 px left of its icon (sim-474: x 74 for an icon at 75). */
    fun focusX(i: Int): Int = iconX(i) - 1

    fun itemAt(x: Float, y: Float): Int? {
        val c = ((x - left - 21) / PITCH).toInt()
        val r = ((y - top - 22) / PITCH).toInt()
        if (x < left + 21 || y < top + 22 || c >= COLUMNS || r >= rows) return null
        if (x >= iconX(0) + c * PITCH + ICON || y >= iconY(0) + r * PITCH + ICON) return null
        return (r * COLUMNS + c).takeIf { it < FOLDER_ICONS }
    }

    companion object {
        const val COLUMNS = 5
        const val PITCH = 100
        const val ICON = 92
    }
}

/**
 * A trackpad step in the chooser: along the row without leaving it, up and down by a row, onto
 * the last icon from the row above it (sim-475: right on the last icon, alone in its row, stays, C;
 * the rest D).
 */
internal fun iconStep(from: Int, dir: Dir): Int {
    val c = from % IconChooserLayout.COLUMNS
    val to = when (dir) {
        Dir.LEFT -> if (c > 0) from - 1 else from
        Dir.RIGHT -> if (c < IconChooserLayout.COLUMNS - 1) from + 1 else from
        Dir.UP -> from - IconChooserLayout.COLUMNS
        Dir.DOWN -> if (from / IconChooserLayout.COLUMNS < (FOLDER_ICONS - 1) / IconChooserLayout.COLUMNS) minOf(from + IconChooserLayout.COLUMNS, FOLDER_ICONS - 1) else from
    }
    return if (to in 0 until FOLDER_ICONS) to else from
}
