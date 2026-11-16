package dev.bold7.ui

import dev.bold7.design.DialogDimens
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A dialog of OS 7 on a screen [screenWidth] x [screenHeight], pure (docs/BBOS7-VISUEL.md §12,
 * §13): its [text] wrapped, an [icon] at its left or none, its [buttons] stacked and centred under
 * it; without buttons, an app's status message (sim-42). [measure] gives a string's advance in the
 * dialog's font (BBAlpha Sans 27), the buttons' labels in the same. Box positions are its border
 * pixels, inclusive: [left] to [left] + [width] - 1. Checked against sim-19, sim-62 and sim-42
 * (DialogLayoutTest).
 *
 * A text wider than the box wraps at its spaces; a word wider than it, or lines past the screen's
 * height, end in an ellipsis ([fit]) instead of leaving the box (D: OS 7's cut of
 * such a text is not sourced).
 */
class DialogLayout(
    text: String,
    buttons: List<String>,
    icon: Boolean,
    measure: (String) -> Float,
    screenWidth: Int,
    screenHeight: Int,
) {
    private val margin = (screenWidth * DialogDimens.SCREEN_MARGIN_PERCENT / 100f).roundToInt()
    private val extra = if (icon) DialogDimens.ICON_TEXT_X + DialogDimens.PAD_RIGHT + 1 else DialogDimens.TEXT_X + DialogDimens.PAD_RIGHT + 1

    private val room = (screenWidth - 2 * margin - extra).toFloat()
    private val fixed = DialogDimens.PAD_TOP + 1 + if (buttons.isEmpty()) {
        DialogDimens.PAD_BOTTOM
    } else {
        DialogDimens.BUTTON_TOP + (buttons.size - 1) * DialogDimens.BUTTON_PITCH + DialogDimens.BUTTON_HEIGHT - 1 + DialogDimens.PAD_BOTTOM_BUTTONS
    }
    private val maxLines = maxOf(1, (screenHeight - 4 - fixed) / DialogDimens.LINE)

    // The box is as wide as the lines wrapped, or as the screen allows when one is cut.
    private val kept: List<String> = wrap(text, room, measure).let { all ->
        if (all.size <= maxLines) all else all.take(maxLines - 1) + all.drop(maxLines - 1).joinToString(" ")
    }
    val lines: List<String> = kept.map { fit(it, room, measure) }
    val width: Int = minOf(screenWidth - 2 * margin, ceil(kept.maxOf(measure)).toInt() + extra)
    val left: Int = (screenWidth - width) / 2

    private val content = maxOf(if (icon) DialogDimens.ICON else 0, lines.size * DialogDimens.LINE)
    private val count = buttons.size

    /** Height of the box, border pixels included. */
    val height: Int = DialogDimens.PAD_TOP + content + 1 + if (count == 0) {
        DialogDimens.PAD_BOTTOM
    } else {
        DialogDimens.BUTTON_TOP + (count - 1) * DialogDimens.BUTTON_PITCH + DialogDimens.BUTTON_HEIGHT - 1 + DialogDimens.PAD_BOTTOM_BUTTONS
    }

    /** Centred with its 4 px of shadow under it (sim-19, sim-47, sim-62, sim-42: 2 px above the middle, C). */
    val top: Int = (screenHeight - height - 4) / 2

    val iconX: Int = left + DialogDimens.ICON_X
    val iconY: Int = top + DialogDimens.PAD_TOP
    val textX: Int = left + if (icon) DialogDimens.ICON_TEXT_X else DialogDimens.TEXT_X

    /** The baseline of line [i], the lines centred on the content's height (the icon's). */
    fun baseline(i: Int): Int = iconY + (content - lines.size * DialogDimens.LINE) / 2 + DialogDimens.ASCENT + i * DialogDimens.LINE

    val buttonWidth: Int = minOf(
        width - 2 * DialogDimens.BUTTON_PAD,
        maxOf((width * DialogDimens.BUTTON_RATIO).roundToInt(), ceil(buttons.maxOfOrNull(measure) ?: 0f).toInt() + 2 * DialogDimens.BUTTON_PAD),
    )
    val buttonLeft: Int = left + (width - buttonWidth) / 2

    /** The buttons' labels as drawn: cut to their button when it is too narrow for them. */
    val buttonLabels: List<String> = buttons.map { fit(it, TextRoom.button(buttonWidth), measure) }

    /** The top border pixel of button [i]; it is [DialogDimens.BUTTON_HEIGHT] high. */
    fun buttonTop(i: Int): Int = iconY + content + DialogDimens.BUTTON_TOP + i * DialogDimens.BUTTON_PITCH

    /** The button at ([x], [y]), or null. */
    fun buttonAt(x: Float, y: Float): Int? {
        if (x < buttonLeft || x >= buttonLeft + buttonWidth) return null
        return (0 until count).firstOrNull { y >= buttonTop(it) && y < buttonTop(it) + DialogDimens.BUTTON_HEIGHT }
    }
}

/** The button whose label starts with [letter], whatever its case (the underlined letter, sim-47), or null. */
fun mnemonic(labels: List<String>, letter: Char): Int? =
    labels.indexOfFirst { it.firstOrNull()?.equals(letter, ignoreCase = true) == true }.takeIf { it >= 0 }
