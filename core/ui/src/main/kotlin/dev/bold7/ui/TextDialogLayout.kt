package dev.bold7.ui

import dev.bold7.design.DialogDimens
import kotlin.math.roundToInt

/**
 * The text box of OS 7 on a screen [screenWidth] x [screenHeight], pure (docs/BBOS7-VISUEL.md §12,
 * sim-61, C): the [label] in the dialog's font, its field under it, no button. The box is as wide
 * as the screen allows (D: sim-61 is 568 of 640, the margin of the other dialogs 5 %), the label
 * wraps and ends in an ellipsis after two lines (D). Checked against sim-61 (TextDialogTest).
 */
class TextDialogLayout(label: String, measure: (String) -> Float, screenWidth: Int, screenHeight: Int) {
    private val margin = (screenWidth * DialogDimens.SCREEN_MARGIN_PERCENT / 100f).roundToInt()
    val width: Int = screenWidth - 2 * margin
    val left: Int = (screenWidth - width) / 2

    private val room = (width - DialogDimens.TEXT_X - DialogDimens.PAD_RIGHT - 1).toFloat()
    val lines: List<String> = wrap(label, room, measure).let { all ->
        if (all.size <= MAX_LINES) all else all.take(MAX_LINES - 1) + all.drop(MAX_LINES - 1).joinToString(" ")
    }.map { fit(it, room, measure) }

    val height: Int = DialogDimens.PAD_TOP + lines.size * DialogDimens.LINE + DialogDimens.FIELD_GAP + DialogDimens.FIELD_HEIGHT + 1 + DialogDimens.PAD_BOTTOM

    /** Centred with its 4 px of shadow under it, as the other dialogs. */
    val top: Int = (screenHeight - height - 4) / 2

    val textX: Int = left + DialogDimens.TEXT_X
    fun baseline(i: Int): Int = top + DialogDimens.PAD_TOP + DialogDimens.ASCENT + i * DialogDimens.LINE

    val fieldLeft: Int = left + DialogDimens.FIELD_LEFT
    val fieldWidth: Int = width - DialogDimens.FIELD_LEFT - DialogDimens.FIELD_RIGHT
    val fieldTop: Int = top + DialogDimens.PAD_TOP + lines.size * DialogDimens.LINE + DialogDimens.FIELD_GAP
    val fieldHeight: Int = DialogDimens.FIELD_HEIGHT

    private companion object {
        const val MAX_LINES = 2
    }
}

/** What a text box hands back: [typed] cleaned by [clean], or null when nothing is left (the box stays open, D). */
fun accepted(typed: String, clean: (String) -> String): String? = clean(typed).ifEmpty { null }
