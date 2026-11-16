package dev.bold7.phone

/**
 * The room each hand-drawn text of the Phone has, in px, so that none runs under its neighbour or off
 * the screen (the cut itself is core/ui's `fit`). Pure: the views only measure and draw.
 */
object Rooms {
    /** The call screen's side margin for a text centred under the caller (D, the grey texts start at x 6). */
    const val CALL_MARGIN = 6

    /** The caller's name or number: bestfit's 470 px (activecallscreen.pme, A) while the screen is wide enough. */
    fun caller(width: Int): Float = minOf(CallView.CALLER_WIDTH, width - 2 * CALL_MARGIN).toFloat()

    /** The line under the caller (number and type, or the incoming call's second line): the screen between its margins. */
    fun line2(width: Int): Float = (width - 2 * CALL_MARGIN).toFloat()

    /** "My Number:" on the first line: from the right margin back to the screen's left margin. */
    fun myNumber(width: Int): Float = (width - CallView.MY_NUMBER_RIGHT - CALL_MARGIN).toFloat()

    /** The tones typed, under the timer: the whole width less the margins (the volume bar is higher). */
    fun tones(width: Int): Float = (width - 2 * CallView.STATUS_X).toFloat()

    /** A button's label in the bar: its cell less 4 px on each side. */
    fun barLabel(width: Int, buttons: Int): Float = (width / buttons.coerceAtLeast(1) - 8).toFloat()

    /** A log row's name and number: from the text's left to the date's left, [gap] px of air between (LogRowView). */
    fun logText(width: Int, whenWidth: Float): Float = width - LogRowView.RIGHT - whenWidth - LogRowView.GAP - LogRowView.TEXT_X
}
