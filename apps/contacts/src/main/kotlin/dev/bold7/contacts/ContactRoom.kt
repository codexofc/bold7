package dev.bold7.contacts

import dev.bold7.ui.TextRoom

/**
 * The width each hand-drawn text of Contacts gets, pure, in px of the view: what core/ui's `fit`
 * cuts it to ("…"), so that no name or value runs under the edit button,
 * the picture or the label beside it (ContactRoomTest). A text that fits keeps its
 * drawing.
 */
object ContactRoom {
    /** A gap between two texts on one line, by eye. */
    const val GAP = 8

    /** A list row's name or note: from after the picture to the right margin (the rows of core/ui). */
    fun listRow(width: Int): Float = TextRoom.listRow(width, iconed = true)

    /** The card's name and company: from the name's x to the edit button's left edge, less a gap. */
    fun cardName(width: Int, withPicture: Boolean): Float =
        (width - (if (withPicture) CardView.NAME_X else CardView.NAME_X_BARE) - CardView.BUTTON_RIGHT - CardView.BUTTON_WIDTH - GAP).coerceAtLeast(0).toFloat()

    /** A detail's value, right-aligned: from after its label of [labelWidth] to the band's right edge. */
    fun cardValue(width: Int, labelWidth: Float): Float =
        (width - CardView.VALUE_RIGHT - CardView.BAND_PAD - CardView.LABEL_X - labelWidth - GAP).coerceAtLeast(0f)

    /** A box's title, on the card and in the form. */
    fun boxTitle(width: Int): Float = (width - 2 * CardView.TITLE_X).coerceAtLeast(0).toFloat()

    /** A button of the form of [buttonWidth] px, its text inside; [arrow] reserves the choice arrow's place. */
    fun button(buttonWidth: Int, arrow: Boolean): Float =
        TextRoom.inside(buttonWidth - if (arrow) dev.bold7.design.ListDimens.CHOICE_ARROW_WIDTH + dev.bold7.design.ListDimens.CHOICE_ARROW_RIGHT else 0)
}
